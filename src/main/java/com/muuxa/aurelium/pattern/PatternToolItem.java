package com.muuxa.aurelium.pattern;

import appeng.api.inventories.InternalInventory;
import com.muuxa.aurelium.AureliumConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

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
 * <p>Sneak + right-click into the air ejects the stored patterns (never lost).</p>
 */
public class PatternToolItem extends Item {

    private final boolean copyOnly;

    public PatternToolItem(Properties properties, boolean copyOnly) {
        super(properties);
        this.copyOnly = copyOnly;
    }

    public boolean isCopyOnly() {
        return copyOnly;
    }

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
            if (PatternToolWorker.isBusy(player.getUUID())) {
                player.displayClientMessage(Component.translatable(
                        "item.aurelium.pattern_tool.busy"), true);
                return InteractionResult.SUCCESS;
            }
            return gather(stack, level, pos, context.getClickedFace(), player);
        }
        // Plain click = paste into the target container (only when there is something to paste).
        if (PatternToolData.count(stack) <= 0) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (PatternToolWorker.isBusy(player.getUUID())) {
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.busy"), true);
            return InteractionResult.SUCCESS;
        }
        return paste(stack, level, pos, context.getClickedFace(), player);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isSecondaryUseActive()) return InteractionResultHolder.pass(stack);
        if (PatternToolData.count(stack) <= 0) return InteractionResultHolder.pass(stack);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        eject(stack, player, level);
        return InteractionResultHolder.success(stack);
    }

    // --- actions ---------------------------------------------------------------------------

    private InteractionResult gather(ItemStack tool, Level level, BlockPos pos,
                                     net.minecraft.core.Direction face, Player player) {
        // Merge into the tool so cutting two containers in a row never destroys the first batch.
        // Only the COUNT is read up front: decoding the whole stored batch (thousands of item
        // stacks) just to append to it was the main cost of a large cut.
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

        // NeoEcoAE buses own a bulk API (and read-only auxiliary mirror rows), so they get their
        // own path; every other container uses the generic inventory walk.
        Object ecoHost = PatternContainerAccess.ecoHost(level, pos, face);
        // Containers whose GUI filter is looser than their real contract (the omniversal
        // machinery) get the stricter allowlist applied to cuts and copies as well — only the
        // patterns they are meant to hold are taken.
        java.util.function.Predicate<ItemStack> allowlist =
                PatternContainerAccess.patternAllowlist(level.getBlockEntity(pos));
        List<ItemStack> gathered;
        if (ecoHost != null) {
            gathered = copyOnly
                    ? PatternContainerAccess.copyEco(ecoHost)
                    : PatternContainerAccess.cutEco(ecoHost, room);
            if (gathered == null) gathered = List.of();
        } else {
            gathered = copyOnly
                    ? PatternContainerAccess.collect(inv, room, allowlist)
                    : PatternContainerAccess.cut(inv, room, allowlist);
        }
        if (gathered.isEmpty()) {
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.nothing"), true);
            return InteractionResult.SUCCESS;
        }

        // Tag-through write: append the gathered stacks to the STORED payload without decoding or
        // re-encoding the entries already in the tool, then compress once. On budget overflow the
        // unaccepted additions are put back / handed to the player, never lost.
        int accepted = PatternToolData.appendToStored(tool, gathered, level.registryAccess());
        if (accepted < 0) {
            if (!copyOnly) {
                dropUnrestorable(player, PatternContainerAccess.restore(inv, new ArrayList<>(gathered)));
            }
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.cut_save_failed"), true);
            return InteractionResult.SUCCESS;
        }
        if (accepted < gathered.size()) {
            List<ItemStack> overflow = new ArrayList<>(gathered.subList(accepted, gathered.size()));
            if (!copyOnly) {
                dropUnrestorable(player, PatternContainerAccess.restore(inv, overflow));
            }
        }
        if (accepted == 0) {
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.cut_budget_full",
                    PatternToolData.MAX_COMPRESSED_BYTES / 1024), true);
            return InteractionResult.SUCCESS;
        }

        int total = storedCount + accepted;
        if (copyOnly) {
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.copied", accepted, total), true);
        } else {
            Component message = Component.translatable(
                    "item.aurelium.pattern_tool.cut", accepted, total);
            if (accepted < gathered.size()
                    || (accepted >= room && PatternContainerAccess.hasAny(inv))) {
                message = message.copy().append(Component.translatable(
                        "item.aurelium.pattern_tool.cut_limited"));
            }
            player.displayClientMessage(message, true);
        }
        return InteractionResult.SUCCESS;
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

    /**
     * Locates the exact tool stack that started an async paste. The original reference is expected
     * to remain in the player's inventory when they merely switch hotbar slots; if it has been
     * moved to a container or dropped, the operation must not silently retarget another tool of
     * the same type and corrupt its payload.
     */
    private static ItemStack findCarriedTool(Player player, ItemStack original) {
        if (player.getMainHandItem() == original || player.getOffhandItem() == original) {
            return original;
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot) == original) return original;
        }
        return null;
    }

    private InteractionResult paste(ItemStack tool, Level level, BlockPos pos,
                                    net.minecraft.core.Direction face, Player player) {
        InternalInventory inv = PatternContainerAccess.find(level, pos, face);
        if (inv == null) return InteractionResult.PASS;

        // Work from the stored TAGS: entries that stay in the tool are never re-encoded — after a
        // 2000-pattern paste the leftovers are written back by compressing the original tags,
        // instead of serialising every stack again (that re-encode was the paste-side lag).
        net.minecraft.nbt.ListTag tags = PatternToolData.storedTags(tool);
        if (tags.isEmpty()) return InteractionResult.PASS;

        // Parsing thousands of pattern stacks is the remaining heavy step; small batches stay
        // inline (below the threshold) while large ones are decoded on a worker thread and the
        // placement happens on a later tick with a "reading…" notice in the meantime.
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        List<ItemStack> decoded = PatternToolWorker.decodeOrSchedule(
                serverPlayer, tags,
                (server, stacks) -> pasteDecoded(server, tool, level, pos, face, tags, stacks),
                pos, face);
        if (decoded == null) {
            return InteractionResult.SUCCESS; // scheduled: the worker will finish it
        }
        return pasteDecoded(serverPlayer, tool, level, pos, face, tags, decoded);
    }

    /**
     * Placement half of {@link #paste}: runs on the main thread with the tool's payload already
     * decoded (either inline, or by {@link PatternToolWorker}).
     */
    private InteractionResult pasteDecoded(net.minecraft.server.level.ServerPlayer player,
                                           ItemStack tool, Level level, BlockPos pos,
                                           net.minecraft.core.Direction face,
                                           net.minecraft.nbt.ListTag tags, List<ItemStack> decoded) {
        InternalInventory inv = PatternContainerAccess.find(level, pos, face);
        if (inv == null) {
            // The container vanished while a large payload was being read on the worker thread.
            // Say so instead of silently doing nothing (the click is long consumed by then).
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.container_gone"), true);
            return InteractionResult.PASS;
        }
        // The player may have switched hotbar slots while the worker was reading. The original
        // stack is still the one whose payload must be updated; if it has left the inventory, stop
        // rather than retargeting a different pattern tool.
        ItemStack held = findCarriedTool(player, tool);
        if (held == null) {
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.tool_moved"), true);
            return InteractionResult.PASS;
        }
        List<ItemStack> stored = decoded;
        if (stored.isEmpty()) return InteractionResult.PASS;
        // Tag index of each parsed stack, used to write the leftovers back from the original
        // tags without re-encoding anything. Both the inline and the worker decode walk the tags
        // in order and skip unparseable entries, so replaying that walk here keeps the mapping.
        List<Integer> storedTagIndex = new ArrayList<>(stored.size());
        for (int i = 0; i < tags.size() && storedTagIndex.size() < stored.size(); i++) {
            ItemStack parsed = ItemStack.parseOptional(level.registryAccess(), tags.getCompound(i));
            if (!parsed.isEmpty()) storedTagIndex.add(i);
        }
        if (storedTagIndex.size() != stored.size()) storedTagIndex.clear(); // mismatch: safer fallback
        boolean indexUsable = !storedTagIndex.isEmpty();
        int before = stored.size();
        List<ItemStack> snapshot = new ArrayList<>(stored);

        // Duplicate guard on the PRIMARY OUTPUT: a container should not accumulate two patterns
        // that produce the same thing. In replace mode the matching target entry is swapped out
        // (and handed back to the player); otherwise the incoming pattern is skipped and stays in
        // the tool. Only decodable patterns participate — anything else passes through untouched.
        boolean replaceMode = PatternToolData.isReplaceMode(held);
        java.util.Set<appeng.api.stacks.AEKey> targetOutputs =
                PatternContainerAccess.collectPrimaryOutputs(inv, level);
        java.util.Set<appeng.api.stacks.AEKey> incomingOutputs = new java.util.HashSet<>();
        List<ItemStack> toPlace = new ArrayList<>(stored.size());
        List<ItemStack> duplicateIncoming = new ArrayList<>();
        List<ItemStack> displaced = new ArrayList<>();
        for (ItemStack candidate : stored) {
            appeng.api.stacks.AEKey output = PatternOutputs.primaryOutput(candidate, level);
            boolean duplicate = output != null
                    && (targetOutputs.contains(output) || !incomingOutputs.add(output));
            if (!duplicate) {
                toPlace.add(candidate);
                continue;
            }
            if (replaceMode && targetOutputs.contains(output)) {
                // Swap the target's existing entry out for this one; the old stack goes back to
                // the player so nothing is destroyed.
                ItemStack old = PatternContainerAccess.removePrimaryOutput(inv, output, level);
                if (old != null) {
                    displaced.add(old);
                    targetOutputs.remove(output);
                    toPlace.add(candidate);
                    continue;
                }
            }
            duplicateIncoming.add(candidate);
        }
        if (toPlace.isEmpty()) {
            dropUnrestorable(player, displaced);
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.pasted_duplicates",
                    duplicateIncoming.size()), true);
            return InteractionResult.SUCCESS;
        }

        // Read the target's remaining capacity FIRST, so the player learns before anything moves:
        // enough room → one-shot paste; not enough → fill what fits and keep the rest in the tool.
        int room = PatternContainerAccess.freeSlots(inv, toPlace.size());
        if (room <= 0) {
            dropUnrestorable(player, displaced);
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.paste_full"), true);
            return InteractionResult.SUCCESS;
        }
        // The copy tool never consumes; the cut tool drains whatever was actually placed.
        Object ecoHost = PatternContainerAccess.ecoHost(level, pos, face);
        // A few containers publish a looser filter in their GUI than the rules they should follow
        // from the tools (the omniversal machinery); apply the stricter allowlist when one exists.
        java.util.function.Predicate<ItemStack> allowlist =
                PatternContainerAccess.patternAllowlist(level.getBlockEntity(pos));
        int placed = -1;
        if (ecoHost != null) {
            placed = PatternContainerAccess.pasteEco(ecoHost, toPlace, !copyOnly);
        }
        if (placed < 0) {
            placed = PatternContainerAccess.paste(inv, toPlace, !copyOnly, allowlist);
        }
        if (placed == 0) {
            dropUnrestorable(player, displaced);
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.paste_full"), true);
            return InteractionResult.SUCCESS;
        }
        dropUnrestorable(player, displaced);
        if (!copyOnly) {
            // Rebuild the leftover payload from the ORIGINAL tags (the stacks still present in
            // `toPlace` are exactly the unplaced ones), then compress once. No item re-encoding.
            // Duplicates skipped by the guard also stay in the tool.
            java.util.IdentityHashMap<ItemStack, Integer> indexOf = new java.util.IdentityHashMap<>();
            for (int i = 0; i < before; i++) {
                indexOf.put(snapshot.get(i), indexUsable ? storedTagIndex.get(i) : i);
            }
            boolean[] keptTag = new boolean[tags.size()];
            for (ItemStack leftover : toPlace) {
                Integer tagIndex = indexOf.get(leftover);
                if (tagIndex != null) keptTag[tagIndex] = true;
            }
            for (ItemStack leftover : duplicateIncoming) {
                Integer tagIndex = indexOf.get(leftover);
                if (tagIndex != null) keptTag[tagIndex] = true;
            }
            net.minecraft.nbt.ListTag remaining = new net.minecraft.nbt.ListTag();
            for (int i = 0; i < tags.size(); i++) {
                if (keptTag[i]) remaining.add(tags.get(i));
            }
            PatternToolData.saveTags(held, remaining);
            int remainingCount = remaining.size();
            if (remainingCount > 0) {
                // The target filled up before the whole batch fit: say so instead of silently
                // keeping (or worse, dropping) the rest.
                Component message = duplicateIncoming.isEmpty()
                        ? Component.translatable(
                                "item.aurelium.pattern_tool.pasted_partial", placed, remainingCount)
                        : Component.translatable(
                                "item.aurelium.pattern_tool.pasted_partial_dup", placed,
                                remainingCount, duplicateIncoming.size());
                player.displayClientMessage(message, true);
                return InteractionResult.SUCCESS;
            }
        } else if (placed < before - duplicateIncoming.size()) {
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.pasted_partial_copy",
                    placed, before - placed - duplicateIncoming.size()), true);
            return InteractionResult.SUCCESS;
        } else if (!duplicateIncoming.isEmpty()) {
            player.displayClientMessage(Component.translatable(
                    "item.aurelium.pattern_tool.pasted_duplicates",
                    duplicateIncoming.size()), true);
            return InteractionResult.SUCCESS;
        }
        player.displayClientMessage(Component.translatable(
                "item.aurelium.pattern_tool.pasted", placed), true);
        return InteractionResult.SUCCESS;
    }

    private void eject(ItemStack tool, Player player, Level level) {
        // Drop the stored stacks straight from the tags: an ejection must empty the tool whatever
        // happens, and going through the parsed list would also pay a full decode for nothing.
        net.minecraft.nbt.ListTag tags = PatternToolData.storedTags(tool);
        if (tags.isEmpty()) return;
        int ejected = 0;
        for (int i = 0; i < tags.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(level.registryAccess(), tags.getCompound(i));
            if (stack.isEmpty()) continue;
            player.drop(stack, false);
            ejected++;
        }
        PatternToolData.saveTags(tool, new net.minecraft.nbt.ListTag());
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
        if (PatternToolData.isReplaceMode(stack)) {
            lines.add(Component.translatable("item.aurelium.pattern_tool.replace_mode"));
        }
        lines.add(Component.translatable("item.aurelium.pattern_tool.hint3"));
    }
}
