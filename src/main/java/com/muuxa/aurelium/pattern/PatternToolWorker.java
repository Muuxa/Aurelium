package com.muuxa.aurelium.pattern;

import com.muuxa.aurelium.Aurelium;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the tool's heavy payload work off the server thread, then applies the result on a later
 * tick.
 *
 * <p>A tool holding more than {@link #SYNC_THRESHOLD} patterns would otherwise decode/encode
 * thousands of item stacks inline and stall the tick. Small batches are still finished instantly
 * (the hand-off would dominate); large ones are decoded on a worker thread, and the container
 * writes happen back on the main thread — the only place world state may be touched.</p>
 *
 * <p>Only one job per player is kept: clicking again replaces the pending job, so a player can
 * never queue up work faster than it drains.</p>
 */
@EventBusSubscriber(modid = Aurelium.ID)
public final class PatternToolWorker {

    /** Batches at or below this size are decoded inline (no perceptible cost). */
    public static final int SYNC_THRESHOLD = 500;

    private static final ExecutorService WORKER = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "Aurelium-PatternTool");
        thread.setDaemon(true);
        return thread;
    });

    /** One queued job per player; a second click while one runs replaces the pending one. */
    private static final Map<UUID, Job> PENDING = new ConcurrentHashMap<>();

    private PatternToolWorker() {
    }

    /** What to do once the payload has been read, run on the main thread. */
    public interface MainThreadAction {
        void run(ServerPlayer player, List<ItemStack> decoded);
    }

    /** A job as it moves between threads. */
    private static final class Job {
        final ListTag tags;
        final MainThreadAction action;
        final BlockPos pos;
        final Direction face;
        volatile List<ItemStack> decoded;
        volatile boolean ready;

        Job(ListTag tags, MainThreadAction action, BlockPos pos, Direction face) {
            this.tags = tags;
            this.action = action;
            this.pos = pos;
            this.face = face;
        }
    }

    /**
     * Decodes {@code tags} and then hands the stacks to {@code action} on the main thread.
     *
     * @return the decoded list when the batch was small enough to finish inline; {@code null} when
     *         the work was scheduled instead (the player has already been told it is reading)
     */
    public static List<ItemStack> decodeOrSchedule(ServerPlayer player, ListTag tags,
                                                   MainThreadAction action,
                                                   BlockPos pos, Direction face) {
        if (tags.size() <= SYNC_THRESHOLD) {
            return decode(tags, player.registryAccess());
        }
        UUID id = player.getUUID();
        Job job = new Job(tags, action, pos, face);
        PENDING.put(id, job);
        player.displayClientMessage(
                Component.translatable("item.aurelium.pattern_tool.reading", tags.size()), true);
        WORKER.submit(() -> {
            List<ItemStack> decoded = decode(tags, player.registryAccess());
            job.decoded = decoded;
            job.ready = true;
            // The main thread picks it up on its next tick (see onServerTick).
        });
        return null;
    }

    /** True when a worker job is waiting to be applied for this player. */
    public static boolean isBusy(UUID playerId) {
        return PENDING.containsKey(playerId);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        for (Map.Entry<UUID, Job> entry : new ArrayList<>(PENDING.entrySet())) {
            Job job = entry.getValue();
            if (!job.ready) continue;
            PENDING.remove(entry.getKey());
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue; // logged out while reading
            try {
                job.action.run(player, job.decoded == null ? List.of() : job.decoded);
            } catch (Throwable failure) {
                LOGGER.error("AURELIUM：样板工具的异步读取结果应用失败", failure);
            }
        }
    }

    private static List<ItemStack> decode(ListTag tags, HolderLookup.Provider registries) {
        List<ItemStack> out = new ArrayList<>(tags.size());
        for (int i = 0; i < tags.size(); i++) {
            ItemStack parsed = ItemStack.parseOptional(registries, tags.getCompound(i));
            if (!parsed.isEmpty()) out.add(parsed);
        }
        return out;
    }


    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Aurelium/PatternTool");
}
