package com.muuxa.aurelium.pattern;

import appeng.api.inventories.InternalInventory;
import com.muuxa.aurelium.AureliumConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * AURELIUM pattern tool: moves or duplicates the pattern stock of any supported pattern container.
 *
 * <ul>
 *   <li><b>Cut tool</b> ({@code copyOnly = false}) — sneak + right-click a container cuts every
 *       pattern out of it (up to the configured limit, default 10000) into this item's NBT; a plain
 *       right-click pastes them back and <b>consumes</b> what was pasted.</li>
 *   <li><b>Copy tool</b> ({@code copyOnly = true}) — sneak + right-click copies the stock, leaving
 *       the source untouched (no limit); a plain right-click pastes without ever consuming the
 *       stored patterns, so it can paste again and again.</li>
 * </ul>
 *
 * <p>The gesture follows AE2's memory card convention: sneaking acts on the source, a plain click
 * acts on the target. Interception happens in {@link #onItemUseFirst}, which MC runs <b>before</b>
 * the block's own interaction on both sides — so an empty tool (or a non-container target) simply
 * passes through and the container GUI opens as usual.</p>
 *
 * <p><b>Both directions are tick-sliced and off-thread where possible.</b> A large multiblock
 * (the Useless Mod omniversal pattern assembly, a full super-assembler matrix) exposes thousands of
 * slots; walking it in one pass used to stall the server thread and freeze the game. Each gesture
 * now runs through {@link PatternToolWorker}: a bounded number of slots per tick, a progress line
 * in the action bar, and the CPU-heavy serialise/compress/decode half on a worker thread. The
 * outcome is identical to the old one-shot behaviour, just spread over ticks.</p>
 *
 * <p>Sneak + right-click into the air ejects the stored patterns (never lost).</p>
 */
public class PatternToolItem extends Item {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Aurelium/PatternTool");

    private final boolean copyOnly;

    public PatternToolItem(Properties properties, boolean copyOnly) {
        super(properties);
        this.copyOnly = copyOnly;
    }

    public boolean isCopyOnly() {
        return copyOnly;
    }

    private static final String PROGRESS_READ = PatternToolWorker.PROGRESS_READ;
    private static final String PROGRESS_WRITE = PatternToolWorker.PROGRESS_WRITE;
    private static final String PROGRESS_PASTE = PatternToolWorker.PROGRESS_PASTE;

    // --- interaction -----------------------------------------------------------------------

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!PatternContainerAccess.looksLikeContainer(level, pos)) return InteractionResult.PASS;

        if (player.isSecondaryUseActive()) {
            // Sneak = gather from the source container.
            if (level.isClientSide) return InteractionResult.SUCCESS;
            return beginGather(stack, level, pos, context.getClickedFace(), player);
        }
        // Plain click = paste into the target container (only when there is something to paste).
        if (PatternToolData.count(stack) <= 0) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        return beginPaste(stack, level, pos, context.getClickedFace(), player);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isSecondaryUseActive()) return InteractionResultHolder.pass(stack);
        if (PatternToolData.count(stack) <= 0) return InteractionResultHolder.pass(stack);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        if (PatternToolWorker.isOperation(player.getUUID(), PatternToolData.operation(stack))) {
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.job_locked"), true);
            return InteractionResultHolder.success(stack);
        }
        eject(stack, player, level);
        return InteractionResultHolder.success(stack);
    }

    @Override
    public boolean onDroppedByPlayer(ItemStack item, Player player) {
        if (PatternToolWorker.isOperation(player.getUUID(), PatternToolData.operation(item))) {
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.job_locked"), true);
            return false;
        }
        return true;
    }


    // --- gather (cut / copy) ---------------------------------------------------------------

    private InteractionResult beginGather(ItemStack tool, Level level, BlockPos pos,
                                          Direction face, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

        int storedCount = PatternToolData.count(tool);
        int room = copyOnly ? Integer.MAX_VALUE
                : Math.max(0, AureliumConfig.patternCutLimit() - storedCount);
        if (room <= 0) {
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.cut_full", AureliumConfig.patternCutLimit()), true);
            return InteractionResult.SUCCESS;
        }

        InternalInventory inv = PatternContainerAccess.find(level, pos, face);
        if (inv == null) return InteractionResult.PASS;
        Predicate<ItemStack> allowlist =
                PatternContainerAccess.patternAllowlist(level.getBlockEntity(pos));

        // One container slot per unit for the progress line.
        // start() itself is the atomic "is this player already busy?" check, so two clicks in the
        // same tick can never both get through.
        String operation = UUID.randomUUID().toString();
        PatternToolData.setOperation(tool, operation);
        if (!PatternToolWorker.start(serverPlayer,
                new GatherStep(copyOnly, inv, room, allowlist, tool, storedCount, operation),
                operation, PROGRESS_READ, inv.size())) {
            PatternToolData.clearOperation(tool, operation);
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.busy"), true);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * The cut/copy job: walk the source (sliced), then hand the serialise-and-compress half to a
     * worker thread and write the finished payload back on the server thread.
     */
    private final class GatherStep implements PatternToolWorker.Step {
        private final boolean copying;
        private final InternalInventory inv;
        private final int limit;
        private final Predicate<ItemStack> allowlist;
        private final ItemStack tool;
        private final int storedCount;
        private final String operation;
        private PatternContainerAccess.BulkSession session;
        private boolean walking = true;
        private List<ItemStack> gathered;
        private boolean done;
        private boolean resolved;
        private ServerPlayer owner;

        GatherStep(boolean copying, InternalInventory inv, int limit, Predicate<ItemStack> allowlist,
                   ItemStack tool, int storedCount, String operation) {
            this.copying = copying;
            this.inv = inv;
            this.limit = limit;
            this.allowlist = allowlist;
            this.tool = tool;
            this.storedCount = storedCount;
            this.operation = operation;
        }

        @Override
        public PatternToolWorker.Progress tick(ServerPlayer player, int minSlots, long deadline) {
            owner = player;
            if (done) return PatternToolWorker.Progress.DONE;
            if (walking) {
                if (session == null) {
                    session = copying
                            ? new PatternContainerAccess.CollectSession(inv, limit, allowlist)
                            : new PatternContainerAccess.CutSession(inv, limit, allowlist);
                }
                boolean complete = session.step(minSlots, deadline);
                if (session instanceof PatternContainerAccess.CutSession cut && cut.limitReached()) {
                    complete = true;
                }
                if (session instanceof PatternContainerAccess.CollectSession collect
                        && collect.limitReached()) {
                    complete = true;
                }
                PatternToolWorker.setProgress(player, session.cursor());
                if (!complete) return PatternToolWorker.Progress.CONTINUE;
                walking = false;
                gathered = session instanceof PatternContainerAccess.CutSession cut
                        ? cut.taken()
                        : ((PatternContainerAccess.CollectSession) session).collected();
                session.close();
                if (gathered.isEmpty()) {
                    player.displayClientMessage(Component.translatable(
                            "item.aurelium.pattern_tool.nothing"), true);
                    resolved = true;
                    done = true;
                    return PatternToolWorker.Progress.DONE;
                }
                // Serialising + compressing the batch is the CPU-heavy half; do it off-thread.
                ListTag existing = PatternToolData.storedTags(tool);
                List<ItemStack> batch = gathered;
                PatternToolWorker.submitBackground(player,
                        () -> PatternToolData.prepareAppend(existing, batch, player.registryAccess()),
                        prepared -> applyGather(player, existing, prepared));
                return PatternToolWorker.Progress.WAITING;
            }
            // The background half already ran; nothing left to do.
            done = true;
            return PatternToolWorker.Progress.DONE;
        }

        /** Runs on the server thread with the prepared payload (or {@code null} on failure). */
        private void applyGather(ServerPlayer player, ListTag existing,
                                 PatternToolData.Prepared prepared) {
            if (prepared == null || prepared.compressed() == null) {
                returnGathered(player, "item.aurelium.pattern_tool.cut_save_failed");
                return;
            }
            int accepted = prepared.count() - existing.size();
            if (accepted <= 0) {
                returnGathered(player, "item.aurelium.pattern_tool.cut_budget_full",
                        PatternToolData.MAX_COMPRESSED_BYTES / 1024);
                return;
            }
            // The tool may have been dropped or moved while the worker was encoding.
            ToolRef held = findTool(player, operation, tool);
            if (held == null) {
                returnGathered(player, "item.aurelium.pattern_tool.tool_moved");
                return;
            }
            if (!PatternToolData.writeCompressed(held.stack(), prepared.count(), prepared.compressed())) {
                returnGathered(player, "item.aurelium.pattern_tool.cut_save_failed");
                return;
            }
            PatternToolData.clearOperation(held.stack(), operation);
            held.markChanged(player);
            resolved = true;
            if (accepted < gathered.size() && !copying) {
                List<ItemStack> overflow = new ArrayList<>(gathered.subList(accepted, gathered.size()));
                dropUnrestorable(player, PatternContainerAccess.restore(inv, overflow));
            }
            int total = storedCount + accepted;
            if (copying) {
                player.displayClientMessage(Component.translatable(
                        "item.aurelium.pattern_tool.copied", accepted, total), true);
            } else {
                Component message = Component.translatable(
                        "item.aurelium.pattern_tool.cut", accepted, total);
                if (accepted < gathered.size()
                        || (limit <= accepted && PatternContainerAccess.hasAny(inv))) {
                    message = message.copy().append(Component.translatable(
                            "item.aurelium.pattern_tool.cut_limited"));
                }
                player.displayClientMessage(message, true);
            }
        }

        /** Puts the batch back into the container (cut tool) and reports the failure. */
        private void returnGathered(ServerPlayer player, String messageKey, Object... args) {
            ToolRef current = findTool(player, operation, tool);
            if (current != null) {
                PatternToolData.clearOperation(current.stack(), operation);
                current.markChanged(player);
            }
            if (!copying && gathered != null) {
                dropUnrestorable(player, PatternContainerAccess.restore(inv, new ArrayList<>(gathered)));
            }
            resolved = true;
            player.displayClientMessage(Component.translatable(messageKey, args), true);
        }

        @Override
        public void finish() {
            List<ItemStack> abandoned = null;
            if (!resolved && !copying) {
                if (gathered != null) {
                    abandoned = new ArrayList<>(gathered);
                } else if (session instanceof PatternContainerAccess.CutSession cut) {
                    abandoned = new ArrayList<>(cut.taken());
                }
            }
            if (session != null && !session.isClosed()) session.close();
            if (abandoned != null && !abandoned.isEmpty() && owner != null) {
                dropUnrestorable(owner, PatternContainerAccess.restore(inv, abandoned));
            }
            if (owner != null && !resolved) {
                ToolRef current = findTool(owner, operation, tool);
                if (current != null) {
                    PatternToolData.clearOperation(current.stack(), operation);
                    current.markChanged(owner);
                }
            }
        }
    }

    /** Hands the player anything that could not be put back, so a restore can never lose data. */
    private static void dropUnrestorable(Player player, List<ItemStack> leftovers) {
        for (ItemStack stack : leftovers) {
            if (stack == null || stack.isEmpty()) continue;
            if (!player.getInventory().add(stack.copy())) {
                player.drop(stack.copy(), false);
            }
        }
    }

    /** A live tool stack plus the dropped entity that owns it, when it is not in the inventory. */
    private record ToolRef(ItemStack stack, ItemEntity entity) {
        void markChanged(Player player) {
            if (entity != null) {
                entity.setItem(stack.copy());
            } else {
                player.getInventory().setChanged();
            }
        }
    }

    /**
     * Locates the exact tool stack that started an async operation. The temporary marker makes this
     * safe even when the item is moved to another inventory slot or dropped; without it, a fallback
     * that merely matched the item type could retarget a second tool and corrupt its payload.
     */
    private static ToolRef findTool(Player player, String operation, ItemStack original) {
        ItemStack main = player.getMainHandItem();
        if (isOperationTool(main, operation)) return new ToolRef(main, null);
        ItemStack off = player.getOffhandItem();
        if (isOperationTool(off, operation)) return new ToolRef(off, null);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isOperationTool(stack, operation)) return new ToolRef(stack, null);
        }
        if (original != null && player.getMainHandItem() == original) {
            return new ToolRef(original, null);
        }
        if (operation != null && !operation.isBlank() && player.level() != null) {
            for (ItemEntity entity : player.level().getEntitiesOfClass(
                    ItemEntity.class, player.getBoundingBox().inflate(64.0))) {
                ItemStack stack = entity.getItem();
                if (isOperationTool(stack, operation)) return new ToolRef(stack, entity);
            }
        }
        return null;
    }

    private static boolean isOperationTool(ItemStack stack, String operation) {
        return stack != null
                && !stack.isEmpty()
                && stack.getItem() instanceof PatternToolItem
                && operation != null
                && operation.equals(PatternToolData.operation(stack));
    }

    // --- paste -----------------------------------------------------------------------------

    private InteractionResult beginPaste(ItemStack tool, Level level, BlockPos pos,
                                         Direction face, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;

        InternalInventory inv = PatternContainerAccess.find(level, pos, face);
        if (inv == null) return InteractionResult.PASS;

        ListTag tags = PatternToolData.storedTags(tool);
        if (tags.isEmpty()) return InteractionResult.PASS;

        // Phase 1 counts the tool's own entries ("reading N patterns"), which is the number the
        // player recognises; the scan switches to the container's slot count afterwards.
        String operation = UUID.randomUUID().toString();
        PatternToolData.setOperation(tool, operation);
        if (!PatternToolWorker.start(serverPlayer,
                new PasteStep(inv, level, pos, tool, tags, operation),
                operation, PROGRESS_READ, tags.size())) {
            PatternToolData.clearOperation(tool, operation);
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.busy"), true);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * The paste job, in three phases:
     *
     * <ol>
     *   <li><b>decode</b> the payload (parsing off-thread, pattern validity on the server thread);</li>
     *   <li><b>one scan</b> of the target, which collects both the primary outputs already present
     *       and the exact free slots — sliced, with progress;</li>
     *   <li><b>place</b> the accepted entries straight into those recorded slots, sliced.</li>
     * </ol>
     *
     * <p>Earlier revisions walked the container three times (outputs, free-slot pre-check, then
     * placement). On a multi-thousand-slot matrix that was the reason pasting felt slow and its
     * progress line appeared to restart; a single scan removes both.</p>
     */
    private final class PasteStep implements PatternToolWorker.Step {
        private final InternalInventory inv;
        private final Level level;
        private final BlockPos pos;
        private final ItemStack tool;
        private final ListTag tags;
        private final String operation;

        private PatternContainerAccess.TargetScan scan;
        private PatternContainerAccess.PlaceSession placeSession;
        private List<Parsed> parsedPayload;
        private List<ItemStack> stored;
        private List<Integer> tagIndex;
        private boolean[] validTag;
        /** Tag indexes of entries that are not usable patterns: kept in the tool, never pasted. */
        private final List<Integer> invalidTagIndex = new ArrayList<>();
        private int invalid;

        private final List<ItemStack> pending = new ArrayList<>();
        private final List<ItemStack> duplicateIncoming = new ArrayList<>();
        private final List<ItemStack> displaced = new ArrayList<>();
        private ToolRef held;
        private int placed;
        /** Entries this paste intends to place (denominator of the placement progress line). */
        private int placeTotal;
        private int phase; // 0 = decode, 1 = scan, 2 = place, 3 = write back, 4 = done
        private boolean pendingBackground;

        PasteStep(InternalInventory inv, Level level, BlockPos pos, ItemStack tool, ListTag tags,
                  String operation) {
            this.inv = inv;
            this.level = level;
            this.pos = pos;
            this.tool = tool;
            this.tags = tags;
            this.operation = operation;
        }

        @Override
        public PatternToolWorker.Progress tick(ServerPlayer player, int minSlots, long deadline) {
            switch (phase) {
                case 0 -> {
                    owner = player;
                    return decodePhase(player);
                }
                case 1 -> {
                    owner = player;
                    return scanPhase(player, minSlots, deadline);
                }
                case 2 -> {
                    owner = player;
                    return placePhase(player, minSlots, deadline);
                }
                case 3 -> {
                    // Anything swapped out in replace mode goes back to the player before the rest
                    // of the outcome is computed.
                    dropUnrestorable(player, displaced);
                    prepareWriteBack(player);
                    phase = 4;
                    return PatternToolWorker.Progress.WAITING;
                }
                case 4 -> {
                    writeBack(player);
                    return PatternToolWorker.Progress.DONE;
                }
                default -> {
                    return PatternToolWorker.Progress.DONE;
                }
            }
        }

        /** Parse off-thread, then filter on this thread (pattern decoding needs the recipe manager). */
        private PatternToolWorker.Progress decodePhase(ServerPlayer player) {
            if (!pendingBackground) {
                pendingBackground = true;
                PatternToolWorker.submitBackground(player,
                        () -> parsePayload(tags, player),
                        parsed -> parsedPayload = parsed);
                return PatternToolWorker.Progress.WAITING;
            }
            pendingBackground = false;
            if (parsedPayload == null) return PatternToolWorker.Progress.DONE; // cancelled
            // The tooltip count comes from a cached int; the real list length is what actually gets
            // pasted. If an older build ever wrote them out of sync, this is where it shows up.
            int cachedCount = PatternToolData.count(tool);
            if (cachedCount != tags.size()) {
                LOGGER.warn("AURELIUM：样板工具计数不一致 —— 缓存 {} / 实际 {}，已按实际值处理",
                        cachedCount, tags.size());
            }
            Decoded decoded = filterUsable(parsedPayload, tags.size(), player);
            parsedPayload = null;
            stored = decoded.stacks();
            tagIndex = decoded.tagIndex();
            invalid = decoded.invalid();
            validTag = decoded.validTag();
            invalidTagIndex.clear();
            for (int i = 0; i < validTag.length; i++) {
                if (!validTag[i]) invalidTagIndex.add(i);
            }
            // The validity pass also refreshed the count the tooltip shows.
            ToolRef carried = findTool(player, operation, tool);
            if (carried != null) {
                PatternToolData.setInvalidCount(carried.stack(), invalid);
                carried.markChanged(player);
            }
            if (stored.isEmpty()) {
                player.displayClientMessage(invalidOnlyMessage(invalid), true);
                return PatternToolWorker.Progress.DONE;
            }
            held = findTool(player, operation, tool);
            if (held == null) {
                player.displayClientMessage(Component.translatable(
                        "item.aurelium.pattern_tool.tool_moved"), true);
                return PatternToolWorker.Progress.DONE;
            }
            phase = 1;
            return PatternToolWorker.Progress.CONTINUE;
        }

        /** One sliced pass: existing outputs + the free slots a paste may use. */
        private PatternToolWorker.Progress scanPhase(ServerPlayer player, int minSlots, long deadline) {
            if (scan == null) {
                java.util.function.Predicate<ItemStack> allowlist =
                        PatternContainerAccess.patternAllowlist(level.getBlockEntity(pos));
                scan = new PatternContainerAccess.TargetScan(inv, level, allowlist);
                // Report the size AE2 actually exposes for this container, so a mismatch with what
                // the player expects is visible immediately instead of showing up as a stray number
                // in the progress line.
                player.displayClientMessage(Component.translatable(
                        "item.aurelium.pattern_tool.container_size", inv.size()), true);
                // Switch the line to the container dimension: the tool has been read by now.
                PatternToolWorker.setPhase(player, PROGRESS_WRITE, inv.size());
            }
            boolean complete = scan.step(minSlots, deadline);
            PatternToolWorker.setProgress(player, scan.cursor());
            if (!complete) return PatternToolWorker.Progress.CONTINUE;
            scan.close();
            decidePlacement(player);
            if (pending.isEmpty() && duplicateIncoming.isEmpty()) {
                // Nothing at all to place: the player was already told why.
                return PatternToolWorker.Progress.DONE;
            }
            List<Integer> free = scan.freeSlots();
            // Tell the player what the container looks like before anything is written: total slots,
            // how many are free, and how many entries are about to go in.
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.container_stats",
                    scan.size(), free.size(), scan.occupied(), pending.size()), true);
            if (free.isEmpty() || pending.isEmpty()) {
                reportNothingPlaced(player);
                return PatternToolWorker.Progress.DONE;
            }
            placeSession = new PatternContainerAccess.PlaceSession(inv, scan.allowlist(),
                    pending, free);
            // Third phase, counted in ENTRIES rather than container slots: that is the number the
            // player is actually waiting on ("placed 320 / 656"), and it keeps moving while the
            // placement runs. Previously this phase reused the slot count, so the line sat frozen
            // at the scan's end value (2077 / 2077).
            placeTotal = pending.size();
            PatternToolWorker.setPhase(player, PROGRESS_PASTE, Math.max(1, placeTotal));
            phase = 2;
            return PatternToolWorker.Progress.CONTINUE;
        }

        private PatternToolWorker.Progress placePhase(ServerPlayer player, int minSlots, long deadline) {
            boolean complete = placeSession.step(minSlots, deadline);
            placed = placeSession.placed();
            PatternToolWorker.setProgress(player, placed);
            if (!complete) return PatternToolWorker.Progress.CONTINUE;
            // Replace the pending list with what the session could not place, so the write-back
            // below keeps exactly the entries that did NOT land (and the paste consumes the rest).
            pending.clear();
            pending.addAll(placeSession.leftover());
            refusedByContainer = placeSession.filterRefused();
            batchingActive = placeSession.batched();
            phase = 3;
            return PatternToolWorker.Progress.CONTINUE;
        }

        /**
         * Duplicate guard + the "is there room at all?" decision, both from the single scan.
         * {@code pending} holds what may be placed; {@code duplicateIncoming} what was filtered out.
         */
        private void decidePlacement(ServerPlayer player) {
            java.util.Set<appeng.api.stacks.AEKey> targetOutputs = scan.outputs();
            java.util.Set<appeng.api.stacks.AEKey> incoming = new java.util.HashSet<>();
            boolean replaceMode = PatternToolData.isReplaceMode(held.stack());
            pending.clear();
            duplicateIncoming.clear();
            for (ItemStack candidate : stored) {
                appeng.api.stacks.AEKey output = PatternOutputs.primaryOutput(candidate, level);
                boolean duplicate = output != null
                        && (targetOutputs.contains(output) || !incoming.add(output));
                if (!duplicate) {
                    pending.add(candidate);
                    continue;
                }
                if (replaceMode && targetOutputs.contains(output)) {
                    PatternContainerAccess.RemoveResult removed =
                            PatternContainerAccess.removePrimaryOutputSlot(inv, output, level);
                    if (removed != null) {
                        displaced.add(removed.removed());
                        targetOutputs.remove(output);
                        pending.add(candidate);
                        // The slot just vacated is free again. The scan ran before this, so it has
                        // to be added explicitly or the replacement would have nowhere to go.
                        scan.addFreeSlot(removed.slot());
                        continue;
                    }
                }
                duplicateIncoming.add(candidate);
            }
            if (pending.isEmpty()) {
                dropUnrestorable(player, displaced);
                player.displayClientMessage(
                        duplicatesMessage(duplicateIncoming.size(), invalid), true);
            }
        }

        /**
         * The scan found no usable slot, or nothing could be placed. The message distinguishes the
         * three causes, because they need different fixes: duplicates already in the target, the
         * container's own filter refusing the patterns, or genuinely no free slot.
         */
        private void reportNothingPlaced(ServerPlayer player) {
            dropUnrestorable(player, displaced);
            int refused = placeSession != null ? placeSession.filterRefused() : 0;
            int storedTotal = stored == null ? 0 : stored.size();
            if (storedTotal > 0 && duplicateIncoming.size() >= storedTotal) {
                player.displayClientMessage(
                        duplicatesMessage(duplicateIncoming.size(), invalid), true);
                return;
            }
            if (refused > 0) {
                player.displayClientMessage(Component.translatable(
                        "item.aurelium.pattern_tool.rejected_by_container", refused), true);
                return;
            }
            player.displayClientMessage(
                    fullMessage(duplicateIncoming.size(), invalid,
                            scan == null ? 0 : scan.occupied(),
                            scan == null ? 0 : scan.freeSlots().size()),
                    true);
        }

        /** Cut tool: rebuild the leftover payload from the original tags and compress it off-thread. */
        private void prepareWriteBack(ServerPlayer player) {
            if (copyOnly) return;
            // `pending` now holds exactly the entries that did NOT land, so the rebuilt payload is
            // "unplaced + duplicates + invalid" — this is what makes a paste consume what it placed.
            java.util.IdentityHashMap<ItemStack, Integer> indexOf = new java.util.IdentityHashMap<>();
            for (int i = 0; i < stored.size(); i++) indexOf.put(stored.get(i), tagIndex.get(i));
            boolean[] keptTag = new boolean[tags.size()];
            for (ItemStack leftover : pending) {
                Integer idx = indexOf.get(leftover);
                if (idx != null) keptTag[idx] = true;
            }
            for (ItemStack leftover : duplicateIncoming) {
                Integer idx = indexOf.get(leftover);
                if (idx != null) keptTag[idx] = true;
            }
            for (int idx : invalidTagIndex) {
                if (idx >= 0 && idx < keptTag.length) keptTag[idx] = true;
            }
            ListTag remaining = new ListTag();
            for (int i = 0; i < tags.size(); i++) {
                if (keptTag[i]) remaining.add(tags.get(i));
            }
            remainingCount = remaining.size();
            PatternToolWorker.submitBackground(player,
                    () -> PatternToolData.compressOnly(remaining),
                    compressed -> pendingCompressed = compressed);
        }

        private int remainingCount;
        private byte[] pendingCompressed;
        /** Diagnostics carried from the placement phase into the final report. */
        private int refusedByContainer;
        private boolean batchingActive;
        private boolean resolved;
        private ServerPlayer owner;

        /** Cut tool: write the compressed leftovers back and report. */
        private void writeBack(ServerPlayer player) {
            owner = player;
            ToolRef current = findTool(player, operation, tool);
            if (current == null) {
                int rolledBack = !copyOnly && placed > 0 && placeSession != null
                        ? placeSession.rollback() : 0;
                closePlaceSession();
                resolved = true;
                if (copyOnly) {
                    reportCopyOutcome(player, null);
                } else if (placed > 0) {
                    player.displayClientMessage(Component.translatable(
                            "item.aurelium.pattern_tool.tool_moved_rollback", rolledBack), true);
                } else {
                    player.displayClientMessage(Component.translatable(
                            "item.aurelium.pattern_tool.tool_moved"), true);
                }
                return;
            }
            if (!copyOnly && pendingCompressed == null) {
                int rolledBack = placeSession == null ? 0 : placeSession.rollback();
                closePlaceSession();
                resolved = true;
                player.displayClientMessage(Component.translatable(
                        "item.aurelium.pattern_tool.paste_rollback_failed", rolledBack), true);
                return;
            }
            if (!copyOnly) {
                PatternToolData.writeCompressed(current.stack(), remainingCount, pendingCompressed);
            }
            PatternToolData.setInvalidCount(current.stack(), invalid);
            PatternToolData.clearOperation(current.stack(), operation);
            current.markChanged(player);
            closePlaceSession();
            resolved = true;
            if (copyOnly) {
                reportCopyOutcome(player, current);
                return;
            }
            if (remainingCount > 0) {
                if (placed == 0 && refusedByContainer > 0
                        && duplicateIncoming.size() + invalid < stored.size()) {
                    player.displayClientMessage(Component.translatable(
                            "item.aurelium.pattern_tool.rejected_by_container", refusedByContainer), true);
                    return;
                }
                if (duplicateIncoming.isEmpty() && invalid == 0) {
                    player.displayClientMessage(Component.translatable(
                            "item.aurelium.pattern_tool.pasted_partial", placed, remainingCount), true);
                } else {
                    player.displayClientMessage(Component.translatable(
                            "item.aurelium.pattern_tool.pasted_partial_dup", placed, remainingCount,
                            duplicateIncoming.size() + invalid), true);
                }
            } else if (!duplicateIncoming.isEmpty() || invalid > 0) {
                player.displayClientMessage(
                        duplicatesMessage(duplicateIncoming.size(), invalid), true);
            } else {
                player.displayClientMessage(Component.translatable(
                        "item.aurelium.pattern_tool.pasted", placed), true);
            }
        }

        /** Copy tool: it never consumes, so only the placement outcome is reported. */
        private void reportCopyOutcome(ServerPlayer player, ToolRef current) {
            if (current == null) {
                player.displayClientMessage(Component.translatable(
                        "item.aurelium.pattern_tool.tool_moved"), true);
                return;
            }
            if (placed == 0) {
                if (refusedByContainer > 0
                        && duplicateIncoming.size() + invalid < stored.size()) {
                    player.displayClientMessage(Component.translatable(
                            "item.aurelium.pattern_tool.rejected_by_container", refusedByContainer), true);
                } else {
                    player.displayClientMessage(
                            fullMessage(duplicateIncoming.size(), invalid,
                                    scan == null ? 0 : scan.occupied(),
                                    scan == null ? 0 : scan.freeSlots().size()), true);
                }
                return;
            }
            if (!duplicateIncoming.isEmpty() || invalid > 0) {
                player.displayClientMessage(
                        duplicatesMessage(duplicateIncoming.size(), invalid), true);
            } else if (placed < stored.size()) {
                player.displayClientMessage(Component.translatable(
                        "item.aurelium.pattern_tool.pasted_partial_copy", placed,
                        stored.size() - placed), true);
            } else {
                player.displayClientMessage(Component.translatable(
                        "item.aurelium.pattern_tool.pasted", placed), true);
            }
        }

        @Override
        public void finish() {
            if (scan != null && !scan.isClosed()) scan.close();
            if (!resolved && placeSession != null && !placeSession.isClosed()) {
                placeSession.rollback();
            }
            closePlaceSession();
            if (owner != null) {
                ToolRef current = findTool(owner, operation, tool);
                if (current != null) {
                    PatternToolData.clearOperation(current.stack(), operation);
                    current.markChanged(owner);
                }
            }
        }

        private void closePlaceSession() {
            if (placeSession != null && !placeSession.isClosed()) placeSession.close();
        }
    }
    /** A decoded payload: the usable stacks, the tag index each came from, and the dead count. */
    private record Decoded(List<ItemStack> stacks, List<Integer> tagIndex, int invalid,
                           boolean[] validTag) {
    }

    /** One successfully materialised entry, remembering which tag it came from. */
    private record Parsed(int tagIndex, ItemStack stack) {
    }

    /**
     * Parses the stored tags on a worker thread, dropping entries that cannot be materialised at
     * all (blank tags, items whose mod is gone).
     *
     * <p>Nothing here touches world state: pattern decoding itself needs the server's recipe
     * manager and therefore stays on the server thread (see {@link #filterUsable}).</p>
     */
    private static List<Parsed> parsePayload(ListTag tags, ServerPlayer player) {
        List<Parsed> out = new ArrayList<>(tags.size());
        for (int i = 0; i < tags.size(); i++) {
            ItemStack parsed = ItemStack.parseOptional(player.registryAccess(), tags.getCompound(i));
            if (!parsed.isEmpty()) out.add(new Parsed(i, parsed));
        }
        return out;
    }

    /**
     * Server-thread pass: keeps only entries that are real patterns. A blank, foreign or malformed
     * entry used to be queued like any other, be refused by every slot, and make a perfectly empty
     * container report "target is full" — it is now counted as invalid and only mentioned when
     * there actually are any.
     */
    private static Decoded filterUsable(List<Parsed> parsed, int tagCount, ServerPlayer player) {
        List<ItemStack> stacks = new ArrayList<>(parsed.size());
        List<Integer> index = new ArrayList<>(parsed.size());
        boolean[] validTag = new boolean[tagCount];
        for (Parsed entry : parsed) {
            if (!PatternContainerAccess.isUsablePattern(entry.stack(), player.level())) continue;
            stacks.add(entry.stack());
            index.add(entry.tagIndex());
            validTag[entry.tagIndex()] = true;
        }
        int invalid = tagCount - stacks.size();
        return new Decoded(stacks, index, invalid, validTag);
    }
    // --- outcome messages (invalid entries are only mentioned when there are any) ------------

    private static Component invalidOnlyMessage(int invalid) {
        return Component.translatable("item.aurelium.pattern_tool.no_valid_patterns", invalid);
    }

    /**
     * "The target is full". Slots are included so the player can see the container really is
     * occupied (total / free) rather than guessing from a bare refusal.
     */
    private static Component fullMessage(int duplicates, int invalid, int occupied, int free) {
        if (duplicates > 0 || invalid > 0) {
            return Component.translatable("item.aurelium.pattern_tool.paste_full_reason",
                    occupied, occupied + free, duplicates, invalid);
        }
        return Component.translatable("item.aurelium.pattern_tool.paste_full_slots",
                occupied, occupied + free);
    }

    private static Component duplicatesMessage(int duplicates, int invalid) {
        if (invalid > 0) {
            return duplicateAndInvalid(duplicates, invalid);
        }
        return Component.translatable("item.aurelium.pattern_tool.pasted_duplicates", duplicates);
    }

    private static Component duplicateAndInvalid(int duplicates, int invalid) {
        if (duplicates > 0) {
            return Component.translatable("item.aurelium.pattern_tool.pasted_duplicates_invalid",
                    duplicates, invalid);
        }
        return Component.translatable("item.aurelium.pattern_tool.invalid_skipped", invalid);
    }

    private void eject(ItemStack tool, Player player, Level level) {
        // Drop the stored stacks straight from the tags: an ejection must empty the tool whatever
        // happens, and going through the parsed list would also pay a full decode for nothing.
        ListTag tags = PatternToolData.storedTags(tool);
        if (tags.isEmpty()) return;
        int ejected = 0;
        for (int i = 0; i < tags.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(level.registryAccess(), tags.getCompound(i));
            if (stack.isEmpty()) continue;
            player.drop(stack, false);
            ejected++;
        }
        PatternToolData.saveTags(tool, new ListTag());
        player.displayClientMessage(Component.translatable(
                "item.aurelium.pattern_tool.ejected", ejected), true);
    }

    // --- tooltip ---------------------------------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        if (copyOnly) {
            lines.add(Component.translatable("item.aurelium.pattern_tool.copy.hint1"));
            lines.add(Component.translatable("item.aurelium.pattern_tool.copy.hint2"));
        } else {
            lines.add(Component.translatable("item.aurelium.pattern_tool.cut.hint1"));
            lines.add(Component.translatable("item.aurelium.pattern_tool.cut.hint2"));
        }
        lines.add(Component.translatable("item.aurelium.pattern_tool.stored",
                PatternToolData.count(stack)));
        int invalid = PatternToolData.invalidCount(stack);
        if (invalid > 0) {
            lines.add(Component.translatable("item.aurelium.pattern_tool.invalid", invalid));
        }
        if (PatternToolData.isReplaceMode(stack)) {
            lines.add(Component.translatable("item.aurelium.pattern_tool.replace_mode"));
        }
        lines.add(Component.translatable("item.aurelium.pattern_tool.hint3"));
    }
}
