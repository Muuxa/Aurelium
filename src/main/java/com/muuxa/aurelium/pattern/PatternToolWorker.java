package com.muuxa.aurelium.pattern;

import com.muuxa.aurelium.Aurelium;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tick-sliced, phase-aware job engine behind the pattern tools.
 *
 * <p>Bulk pattern work has two very different costs:</p>
 * <ul>
 *   <li><b>CPU-only work</b> — parse/serialise/gzip. Runs on a worker thread.</li>
 *   <li><b>Container access</b> — reading and writing slots. Must happen on the server thread, but
 *       a large multiblock (the Useless Mod omniversal pattern assembly, a full super-assembler
 *       matrix) has thousands of slots, so it is sliced across ticks.</li>
 * </ul>
 *
 * <p>The action-bar line is <b>phase aware</b>: a job switches its own text/dimension as it moves
 * from reading the tool to writing the container, so the number shown always matches what the
 * player is waiting on (patterns while decoding, slots while placing) instead of a single
 * meaningless total.</p>
 */
@EventBusSubscriber(modid = Aurelium.ID)
public final class PatternToolWorker {

    /** Minimum slots a job must get through per tick, so progress can never stall. */
    public static final int MIN_SLOTS_PER_TICK = 64;

    /**
     * Wall-clock budget for one tick's slice. A fixed slot count was far too conservative once the
     * per-slot save was removed: a 4096-slot container spent 32 ticks on nothing. With a time budget
     * a cheap container finishes in one or two ticks, while an expensive one still yields the tick
     * after a few milliseconds instead of freezing.
     */
    public static final long TICK_BUDGET_NANOS = 3_000_000L;

    /** Lang keys whose first two arguments are "done" and "total". */
    public static final String PROGRESS_READ = "item.aurelium.pattern_tool.progress_read";
    public static final String PROGRESS_WRITE = "item.aurelium.pattern_tool.progress_write";
    /** Third phase: writing the entries into the container (counted in entries, not slots). */
    public static final String PROGRESS_PASTE = "item.aurelium.pattern_tool.progress_paste";

    private static final ExecutorService WORKER = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "Aurelium-PatternTool");
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<UUID, Job> JOBS = new ConcurrentHashMap<>();

    private PatternToolWorker() {
    }

    /** What a step wants to happen next. */
    public enum Progress {
        /** More work remains; call again next tick (a progress line is drawn). */
        CONTINUE,
        /** A background task is in flight; resume once its result has been applied. */
        WAITING,
        /** The whole job is finished. */
        DONE
    }

    /** One tick-sliced unit of work. {@link #tick} runs on the server thread. */
    public interface Step {
        /**
         * @param minSlots slots that must be processed this call
         * @param deadline  {@link System#nanoTime()} value after which the slice should yield
         */
        Progress tick(ServerPlayer player, int minSlots, long deadline);

        /**
         * Called exactly once when the job ends, however it ends — including a player logging out
         * mid-job. Steps must release anything they hold here (the AE2 re-entrancy flag above all:
         * leaving it raised would stop that container from ever refreshing again).
         */
        default void finish() {
        }
    }

    private static final class Job {
        final Step step;
        final String operation;
        /** Lang key of the current phase's progress line, or {@code null} to stay silent. */
        volatile String progressKey;
        volatile int done;
        volatile int total;
        volatile boolean waiting;
        volatile Runnable apply;
        volatile boolean cancelled;
        final Object lock = new Object();
        boolean finished;

        Job(Step step, String operation, String progressKey, int total) {
            this.step = step;
            this.operation = operation;
            this.progressKey = progressKey;
            this.total = total;
        }
    }

    /** True when this player already has a job running. */
    public static boolean isBusy(UUID playerId) {
        return JOBS.containsKey(playerId);
    }

    /** True when this player's active job owns the given tool-operation marker. */
    public static boolean isOperation(UUID playerId, String operation) {
        Job job = JOBS.get(playerId);
        return job != null && operation != null && operation.equals(job.operation);
    }

    /**
     * Queues {@code step} for {@code player}.
     *
     * @param progressKey lang key of the first phase's progress line, or {@code null} for silence
     * @param total       expected number of units for that phase
     * @return {@code false} when the player already had a job (nothing was queued)
     */
    public static boolean start(ServerPlayer player, Step step, String operation,
                                String progressKey, int total) {
        Job job = new Job(step, operation, progressKey, Math.max(1, total));
        return JOBS.putIfAbsent(player.getUUID(), job) == null;
    }

    /**
     * Switches the job's progress line to a new phase. Called by steps from the server thread.
     *
     * @param total the dimension of this phase (patterns in the tool, slots in the container, …)
     */
    public static void setPhase(ServerPlayer player, String progressKey, int total) {
        Job job = JOBS.get(player.getUUID());
        if (job == null) return;
        job.progressKey = progressKey;
        job.total = Math.max(1, total);
        job.done = 0;
    }

    /** Updates the progress counter. Safe to call from a worker thread. */
    public static void setProgress(ServerPlayer player, int done) {
        Job job = JOBS.get(player.getUUID());
        if (job != null) job.done = done;
    }

    /**
     * Runs {@code work} on a worker thread and schedules {@code done} to run on the server thread on
     * a following tick, after which the owning step's {@link Step#tick} is called again.
     *
     * <p>Must be called from the server thread (from inside a step).</p>
     */
    public static <T> void submitBackground(ServerPlayer player, java.util.concurrent.Callable<T> work,
                                            java.util.function.Consumer<T> done) {
        Job job = JOBS.get(player.getUUID());
        if (job == null) return;
        job.waiting = true;
        WORKER.submit(() -> {
            T value;
            try {
                value = work.call();
            } catch (Throwable failure) {
                LOGGER.error("AURELIUM：样板工具的后台任务失败", failure);
                value = null;
            }
            T result = value;
            synchronized (job.lock) {
                if (job.cancelled || job.finished) return; // job ended: nothing will read the result
                job.apply = () -> done.accept(result);
            }
        });
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        for (Map.Entry<UUID, Job> entry : new ArrayList<>(JOBS.entrySet())) {
            Job job = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                abandon(entry.getKey(), job);
                continue;
            }
            try {
                // 1) Apply a finished background value (if any) before deciding what to do next.
                Runnable apply = job.apply;
                if (apply != null) {
                    job.apply = null;
                    job.waiting = false;
                    apply.run();
                } else if (job.waiting) {
                    // Still computing: keep the line visible with the progress reported so far,
                    // instead of going silent for the whole off-thread pass.
                    show(player, job);
                    continue;
                }
                // 2) Advance the step.
                Progress progress = job.step.tick(player, MIN_SLOTS_PER_TICK,
                        System.nanoTime() + TICK_BUDGET_NANOS);
                if (progress == Progress.DONE) {
                    finish(entry.getKey(), job);
                    continue;
                }
                show(player, job);
            } catch (Throwable failure) {
                LOGGER.error("AURELIUM：样板工具的分片任务失败", failure);
                finish(entry.getKey(), job);
            }
        }
    }

    private static void show(ServerPlayer player, Job job) {
        String key = job.progressKey;
        if (key == null) return;
        player.displayClientMessage(Component.translatable(key,
                Math.min(job.done, job.total), job.total), true);
    }

    private static void finish(UUID id, Job job) {
        JOBS.remove(id);
        cleanup(job);
    }

    /**
     * Drop a job whose player is gone. The step still gets its cleanup call — the session it holds
     * may have an AE2 re-entrancy flag raised, and skipping the release would silently break that
     * container for the rest of the session.
     */
    private static void abandon(UUID id, Job job) {
        job.cancelled = true;
        JOBS.remove(id);
        cleanup(job);
    }

    private static void cleanup(Job job) {
        synchronized (job.lock) {
            if (job.finished) return;
            job.finished = true;
            job.cancelled = true;
        }
        try {
            job.step.finish();
        } catch (Throwable failure) {
            LOGGER.error("AURELIUM：样板工具的收尾失败", failure);
        }
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Aurelium/PatternTool");
}
