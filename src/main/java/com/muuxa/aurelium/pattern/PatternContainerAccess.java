package com.muuxa.aurelium.pattern;

import appeng.api.inventories.InternalInventory;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.AEKey;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.inv.InternalInventoryHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-mod bridge to the pattern slots of every supported pattern container.
 *
 * <p>AE2's own contract for "something that shows up in the pattern access terminal" is
 * {@link PatternContainer}: {@link PatternContainer#getTerminalPatternInventory()} exposes the
 * provider's pattern slots as a generic {@link InternalInventory}, and every major add-on
 * (ExtendedAE assembler-matrix pattern cores, ExtendedAE-Plus super matrix cores, AE2LT matrix
 * ports / pattern storages, Useless Mod's ME pattern assembly and alloy furnace, NeoEcoAE pattern
 * buses, Pigmee providers, …) implements it. Cable-mounted provider parts are reached through
 * {@link IPartHost}.</p>
 *
 * <p>Containers that expose the same method <b>without</b> implementing the interface (the AE2LT
 * matrix port does this) are reached through a cached reflective lookup, so the tools keep working
 * even when an add-on lags behind.</p>
 *
 * <p><b>Bulk writes:</b> AE2's {@code AppEngInternalInventory} fires a host notification on every
 * {@code setItemDirect}, and a pattern provider reacts by re-decoding its whole stock. A 1000-slot
 * paste would thus trigger ~10^6 decodes. While writing we raise AE2's own re-entrancy flag
 * ({@code notifyingChanges}) and trigger one single refresh afterwards.</p>
 *
 * <p><b>NeoEcoAE (ECO):</b> the pattern bus also exposes a bulk API
 * ({@code beginPatternBatch} → {@code setPatternDirect} … → {@code endPatternBatch}) that rebuilds
 * its pattern catalog exactly once, and its terminal view may append <b>read-only auxiliary mirror
 * rows</b> that belong to other containers. Cut/paste therefore go through the terminal view with
 * a write-after-verify check: a slot that refuses the write (auxiliary row) is never counted as
 * cut, and pasting uses the bus' own {@code insertPatternWithResult} so capacity, duplicate and
 * auxiliary rules stay in the container's hands.</p>
 */
public final class PatternContainerAccess {

    private PatternContainerAccess() {
    }

    // --- discovery -------------------------------------------------------------------------

    /**
     * Finds the pattern inventory of the container at {@code pos} (server side only).
     *
     * @param clickedFace the face the player clicked, used to pick the right cable-mounted part
     * @return the live inventory, or {@code null} when the target is not a pattern container
     */
    public static InternalInventory find(Level level, BlockPos pos, Direction clickedFace) {
        if (level == null || level.isClientSide) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        // Useless Mod's Compact F9 keeps its pattern slots in a shadow "exposed bus" BE rather than
        // on the crafting-system controller the player clicks. The bus implements the same ECO
        // pattern-terminal contract as a normal ECO pattern bus, so unwrapping it here makes the
        // whole cut/copy/paste pipeline work unchanged.
        Object compactEco = compactEcoHost(be);
        if (compactEco != null) {
            InternalInventory compact = fromObject(compactEco);
            if (compact != null) return compact;
        }
        // A formed multiblock matrix wins over the single block: clicking any part of an
        // assembled matrix (housing, frame, or one of its pattern cores) must act on the whole
        // structure, exactly like the pattern access terminal shows it.
        InternalInventory multiblock = fromMultiblock(be);
        if (multiblock != null) return multiblock;
        InternalInventory direct = fromObject(be);
        if (direct != null) return direct;
        return fromParts(be, clickedFace);
    }

    /** True when the block at {@code pos} is (probably) a pattern container. Client-safe. */
    public static boolean looksLikeContainer(Level level, BlockPos pos) {
        if (level == null) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;
        if (compactEcoClass(be)) return true;
        if (be instanceof PatternContainer) return true;
        if (be instanceof IPartHost) return true;
        if (looksLikeMultiblock(be)) return true;
        return methodBySignature(be.getClass(), "getTerminalPatternInventory") != null;
    }

    /**
     * Shared lookup for block entities <b>and</b> cable-mounted parts: interface first, then the
     * cached reflective fallback (covers add-ons that expose the method without implementing the
     * interface).
     */
    private static InternalInventory fromObject(Object owner) {
        if (owner instanceof PatternContainer container) {
            InternalInventory inv = container.getTerminalPatternInventory();
            if (isUsable(inv)) return inv;
        }
        Method m = methodBySignature(owner.getClass(), "getTerminalPatternInventory");
        if (m == null) return null;
        try {
            InternalInventory inv = (InternalInventory) m.invoke(owner);
            return isUsable(inv) ? inv : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static InternalInventory fromParts(BlockEntity be, Direction clickedFace) {
        if (!(be instanceof IPartHost host)) return null;
        if (clickedFace != null) {
            InternalInventory hit = fromPart(host.getPart(clickedFace));
            if (hit != null) return hit;
        }
        InternalInventory first = null;
        for (Direction side : Direction.values()) {
            InternalInventory found = fromPart(host.getPart(side));
            if (found == null) continue;
            if (first != null) return first; // ambiguous: keep the first that was found
            first = found;
        }
        return first;
    }

    private static InternalInventory fromPart(IPart part) {
        if (part == null) return null;
        return fromObject(part);
    }

    /** Useless Mod's compact crafting controller (currently Compact F9). */
    private static final String USELESS_COMPACT_F9_BE =
            "com.sorrowmist.useless.compat.neoecoae.compact.entity.CompactF9BlockEntity";

    /** True without touching the shadow bus, so the client-side click can recognise the block. */
    private static boolean compactEcoClass(BlockEntity be) {
        return be != null && USELESS_COMPACT_F9_BE.equals(be.getClass().getName());
    }

    /**
     * The shadow ECO bus that owns Compact F9's visible pattern page, or {@code null} when this
     * block is not a compact controller (or is not assembled yet).
     */
    private static Object compactEcoHost(BlockEntity be) {
        if (!compactEcoClass(be)) return null;
        Field field = cachedField(be.getClass(), "exposedBus");
        if (field == null) return null;
        try {
            Object bus = field.get(be);
            if (bus == null || !hasEcoBulkApi(bus)) return null;
            return fromObject(bus) != null ? bus : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUsable(InternalInventory inv) {
        return inv != null && inv.size() > 0;
    }

    // --- multiblock matrices ----------------------------------------------------------------

    /**
     * The pattern inventories of a formed multiblock matrix, reached from <b>any</b> of its
     * blocks. Clicking a housing or frame must work exactly like clicking a pattern core — that is
     * how the player actually interacts with an assembled matrix.
     *
     * <ul>
     *   <li>ExtendedAE assembler matrix: {@code getCluster()} → {@code getPatterns()} → each
     *       pattern core's terminal inventory.</li>
     *   <li>ExtendedAE-Plus super assembler matrix: {@code eap$getSuperMatrixCluster()} →
     *       {@code getPatternInventories()}.</li>
     * </ul>
     *
     * @return the individual inventories, or {@code null} when the block is not part of one
     */
    private static List<InternalInventory> multiblockInventories(BlockEntity be) {
        // EAEP super matrix (method name is mod-specific, so there is no ambiguity).
        Object superCluster = invokeNoArg(be, "eap$getSuperMatrixCluster");
        if (superCluster != null) {
            Object inventories = invokeNoArg(superCluster, "getPatternInventories");
            if (inventories instanceof InternalInventory[] array) {
                List<InternalInventory> out = new ArrayList<>(array.length);
                for (InternalInventory inv : array) {
                    if (isUsable(inv)) out.add(inv);
                }
                if (!out.isEmpty()) return out;
            }
        }
        // EAE assembler matrix: every block of the cluster exposes getCluster(); only accept a
        // cluster that actually carries pattern cores (see looksLikeMultiblock for details).
        Object cluster = invokeNoArg(be, "getCluster");
        if (cluster != null && methodBySignature(cluster.getClass(), "getPatterns") != null) {
            Object patterns = invokeNoArg(cluster, "getPatterns");
            if (patterns instanceof Iterable<?> cores) {
                List<InternalInventory> out = new ArrayList<>();
                for (Object core : cores) {
                    InternalInventory inv = fromObject(core);
                    if (isUsable(inv)) out.add(inv);
                }
                if (!out.isEmpty()) return out;
            }
        }
        return null;
    }

    /**
     * Client-safe "could this block belong to a pattern matrix?" probe. The cluster object itself
     * only exists on the logical server, but the methods are declared on the class, so both sides
     * can recognise the click and let the server decide.
     *
     * <p>The check is deliberately narrow: {@code getCluster()} alone is shared by every AE2
     * multiblock (spatial pylons, quantum rings, …), so a bare {@code getCluster} only counts when
     * the returned cluster type actually exposes pattern storage ({@code getPatterns}). Two
     * pitfalls are handled explicitly:</p>
     *
     * <ul>
     *   <li>javac emits a <b>bridge</b> {@code getCluster()} returning the interface type
     *       ({@code IAECluster}) next to the covariant implementation. {@code Class#getMethod}
     *       does not specify which one it returns, so <b>every</b> overload is inspected instead
     *       of trusting the first hit — otherwise the check silently fails and the click falls
     *       through to the container GUI.</li>
     *   <li>ExtendedAE's assembler-matrix housings/frames and ExtendedAE-Plus' super-matrix parts
     *       live in dedicated packages; that package prefix is the most precise signal and does
     *       not depend on reflection ordering at all.</li>
     * </ul>
     */
    private static boolean looksLikeMultiblock(BlockEntity be) {
        Class<?> type = be.getClass();
        String name = type.getName();
        // ExtendedAE assembler matrix (housings, frames, speed/pattern cores) …
        if (name.startsWith("com.glodblock.github.extendedae.common.tileentities.matrix.")) return true;
        // … and ExtendedAE-Plus super assembler matrix (walls, frames, all cores).
        if (name.startsWith("com.extendedae_plus.content.matrix.")) return true;
        if (methodBySignature(type, "eap$getSuperMatrixCluster") != null) return true;
        for (Method m : type.getMethods()) {
            if (m.getParameterCount() != 0 || !m.getName().equals("getCluster")) continue;
            if (hasPatternStorage(m.getReturnType())) return true;
        }
        return false;
    }

    /** True when the type (or its hierarchy) exposes a pattern-storage accessor. */
    private static boolean hasPatternStorage(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (methodBySignature(c, "getPatterns") != null
                    || methodBySignature(c, "getPatternInventories") != null) {
                return true;
            }
            for (Class<?> iface : c.getInterfaces()) {
                if (methodBySignature(iface, "getPatterns") != null
                        || methodBySignature(iface, "getPatternInventories") != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private static InternalInventory fromMultiblock(BlockEntity be) {
        List<InternalInventory> inventories = multiblockInventories(be);
        if (inventories == null || inventories.isEmpty()) return null;
        return inventories.size() == 1 ? inventories.get(0) : new CompositeInventory(inventories);
    }

    /**
     * A read/write view over several inventories in a fixed order. Slot indexes are the
     * concatenation of the parts, so the generic bulk walk, the capacity probe and the paste
     * logic all work unchanged; writes land in the owning core, whose own change notification
     * rebuilds that core's pattern list.
     */
    private static final class CompositeInventory implements InternalInventory {
        private final List<InternalInventory> parts;
        private final int[] offsets;
        private final int size;

        private CompositeInventory(List<InternalInventory> parts) {
            this.parts = List.copyOf(parts);
            this.offsets = new int[this.parts.size()];
            int total = 0;
            for (int i = 0; i < this.parts.size(); i++) {
                offsets[i] = total;
                total += this.parts.get(i).size();
            }
            this.size = total;
        }

        /** The wrapped inventories, in slot order (used by the quiet-write walker). */
        private List<InternalInventory> parts() {
            return parts;
        }

        private InternalInventory part(int slot) {
            for (int i = parts.size() - 1; i >= 0; i--) {
                if (slot >= offsets[i]) return parts.get(i);
            }
            return null;
        }

        private int localSlot(int slot) {
            for (int i = parts.size() - 1; i >= 0; i--) {
                if (slot >= offsets[i]) return slot - offsets[i];
            }
            return -1;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            InternalInventory part = part(slot);
            int local = localSlot(slot);
            if (part == null || local < 0 || local >= part.size()) return ItemStack.EMPTY;
            return part.getStackInSlot(local);
        }

        @Override
        public void setItemDirect(int slot, ItemStack stack) {
            InternalInventory part = part(slot);
            int local = localSlot(slot);
            if (part == null || local < 0 || local >= part.size()) return;
            part.setItemDirect(local, stack);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            InternalInventory part = part(slot);
            int local = localSlot(slot);
            if (part == null || local < 0 || local >= part.size()) return false;
            return part.isItemValid(local, stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            InternalInventory part = part(slot);
            int local = localSlot(slot);
            if (part == null || local < 0 || local >= part.size()) return 0;
            return part.getSlotLimit(local);
        }
    }

    // --- bulk read / write -----------------------------------------------------------------

    /**
     * Cuts up to {@code limit} stacks out of the inventory (empties the source slots).
     *
     * <p>Every clear is <b>verified against the live view</b> before the stack is reported as
     * taken: a slot that silently refuses the write (a read-only view, an auxiliary-owned row, a
     * detached multiblock part) must never end up "cut" in the message while still sitting in the
     * source — or worse, disappear from the source while never reaching the tool.</p>
     */
    public static List<ItemStack> cut(InternalInventory inv, int limit) {
        return cut(inv, limit, null);
    }

    /**
     * As {@link #cut(InternalInventory, int)}, with an optional allowlist. Entries the allowlist
     * rejects are <b>left in place</b> (not cut, not counted).
     */
    public static List<ItemStack> cut(InternalInventory inv, int limit,
                                      java.util.function.Predicate<ItemStack> allowlist) {
        List<ItemStack> taken = new ArrayList<>();
        Object quiet = beginQuiet(inv);
        try {
            int size = inv.size();
            for (int slot = 0; slot < size && taken.size() < limit; slot++) {
                ItemStack stack = inv.getStackInSlot(slot);
                if (stack == null || stack.isEmpty()) continue;
                if (allowlist != null && !allowlist.test(stack)) continue;
                inv.setItemDirect(slot, ItemStack.EMPTY);
                if (inv.getStackInSlot(slot).isEmpty()) {
                    taken.add(stack.copy());
                }
                // else: the slot refused the clear — leave it untouched and count nothing.
            }
        } finally {
            endQuiet(inv, quiet);
        }
        return taken;
    }

    /** Copies up to {@code limit} stacks out of the inventory (leaves the source untouched). */
    public static List<ItemStack> collect(InternalInventory inv, int limit) {
        return collect(inv, limit, null);
    }

    /** As {@link #collect(InternalInventory, int)}, with an optional allowlist. */
    public static List<ItemStack> collect(InternalInventory inv, int limit,
                                          java.util.function.Predicate<ItemStack> allowlist) {
        List<ItemStack> out = new ArrayList<>();
        int size = inv.size();
        for (int slot = 0; slot < size && out.size() < limit; slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) continue;
            if (allowlist != null && !allowlist.test(stack)) continue;
            out.add(stack.copy());
        }
        return out;
    }

    /** True when at least one slot still holds a stack (used to explain a capped cut). */
    public static boolean hasAny(InternalInventory inv) {
        int size = inv.size();
        for (int slot = 0; slot < size; slot++) {
            if (!inv.getStackInSlot(slot).isEmpty()) return true;
        }
        return false;
    }

    /**
     * How many of {@code requested} stacks the inventory can take right now (its free slots).
     * Used to pre-check a paste so the player is told the truth before anything is moved.
     */
    public static int freeSlots(InternalInventory inv, int requested) {
        int free = 0;
        int size = inv.size();
        for (int slot = 0; slot < size && free < requested; slot++) {
            if (inv.getStackInSlot(slot).isEmpty()) free++;
        }
        return free;
    }

    /**
     * Fills the first empty slots of the inventory from {@code source} (in order). When
     * {@code consume} is set, placed entries are removed from {@code source} so the caller can
     * persist whatever did not fit. Every write is verified against the live view, so a slot that
     * silently refuses the write is never counted (and never consumed).
     *
     * @return how many stacks were placed
     */
    public static int paste(InternalInventory inv, List<ItemStack> source, boolean consume) {
        return paste(inv, source, consume, null);
    }

    /**
     * As {@link #paste(InternalInventory, List, boolean)}, with an optional extra allowlist that
     * is stricter than the container's own filter (see {@link #patternAllowlist}).
     */
    public static int paste(InternalInventory inv, List<ItemStack> source, boolean consume,
                            java.util.function.Predicate<ItemStack> allowlist) {
        if (source == null || source.isEmpty()) return 0;
        int placed = 0;
        // Entries a slot refuses stay queued for the remaining slots; an entry refused by EVERY
        // remaining slot is skipped for this paste (it stays in the tool either way).
        java.util.ArrayDeque<ItemStack> queue = new java.util.ArrayDeque<>(source);
        Object quiet = beginQuiet(inv);
        try {
            int size = inv.size();
            int consecutiveSkips = 0;
            for (int slot = 0; slot < size && !queue.isEmpty(); slot++) {
                if (!inv.getStackInSlot(slot).isEmpty()) continue;
                ItemStack next = queue.peekFirst();
                if (allowlist != null && !allowlist.test(next)) {
                    queue.addLast(queue.removeFirst());
                    if (++consecutiveSkips >= queue.size()) break;
                    continue;
                }
                // Respect the container's own admissibility rules: the Useless Mod pattern
                // assembly only accepts omniversal patterns (its own GUI enforces that), and its
                // single-block alloy furnace only accepts decodable patterns. Writing straight
                // into the slot would bypass that filter and "force" foreign patterns in.
                if (!accepts(inv, slot, next)) {
                    // Rotate this entry to the back of the queue and keep scanning: another slot
                    // may still accept it. Bail out once every remaining entry was refused in a
                    // row, so unfillable slots can never spin forever.
                    queue.addLast(queue.removeFirst());
                    if (++consecutiveSkips >= queue.size()) break;
                    continue;
                }
                consecutiveSkips = 0;
                inv.setItemDirect(slot, next.copy());
                if (inv.getStackInSlot(slot).isEmpty()) break; // read-only/refused slot
                queue.removeFirst();
                placed++;
            }
        } finally {
            endQuiet(inv, quiet);
        }
        if (consume) {
            // Drop the placed entries from the caller's list, keeping anything left queued.
            source.clear();
            source.addAll(queue);
        }
        return placed;
    }

    /**
     * The container's own admissibility check for {@code slot}. {@code InternalInventory} defaults
     * to "yes" ({@code isItemValid} returns true); containers that restrict their pattern slots
     * (the Useless Mod omniversal machinery) return false for foreign pattern types, and a paste
     * must honour that instead of forcing the stack in.
     */
    private static boolean accepts(InternalInventory inv, int slot, ItemStack stack) {
        try {
            return inv.isItemValid(slot, stack);
        } catch (Throwable failure) {
            return true; // a container that cannot answer must not block the paste
        }
    }

    // --- Useless Mod omniversal machinery: stricter per-container allowlists --------------

    /**
     * The primary outputs already present in the container. Used by the paste-side guard so a
     * container never ends up with two patterns that produce the same thing.
     *
     * <p>Decoding every occupied slot costs one pattern parse per slot; containers reach a few
     * hundred slots at most (the largest supported matrices hold a few dozen cores), so this stays
     * well below the async threshold that governs tool payloads.</p>
     */
    public static java.util.Set<AEKey> collectPrimaryOutputs(InternalInventory inv, Level level) {
        java.util.Set<AEKey> out = new java.util.HashSet<>();
        if (inv == null || level == null) return out;
        int size = inv.size();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) continue;
            AEKey output = PatternOutputs.primaryOutput(stack, level);
            if (output != null) out.add(output);
        }
        return out;
    }

    /**
     * Removes the first pattern in the container whose primary output matches {@code output}, and
     * returns it (or {@code null} when nothing matched or the slot refused the clear).
     *
     * <p>Read-back verified like every other write in this class: a slot that silently refuses the
     * clear is left alone and reported as "not removed".</p>
     */
    public static ItemStack removePrimaryOutput(InternalInventory inv, AEKey output, Level level) {
        RemoveResult result = removePrimaryOutputSlot(inv, output, level);
        return result == null ? null : result.removed();
    }

    /**
     * The removed stack together with the slot it came from, so a paste running in replace mode can
     * reuse the freshly emptied slot instead of only the ones its scan saw.
     */
    public record RemoveResult(ItemStack removed, int slot) {
    }

    /** As {@link #removePrimaryOutput}, but also reports which slot was vacated. */
    public static RemoveResult removePrimaryOutputSlot(InternalInventory inv, AEKey output, Level level) {
        if (inv == null || level == null || output == null) return null;
        Object quiet = beginQuiet(inv);
        try {
            int size = inv.size();
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = inv.getStackInSlot(slot);
                if (stack == null || stack.isEmpty()) continue;
                if (!output.equals(PatternOutputs.primaryOutput(stack, level))) continue;
                inv.setItemDirect(slot, ItemStack.EMPTY);
                if (inv.getStackInSlot(slot).isEmpty()) return new RemoveResult(stack.copy(), slot);
                return null; // refused: treat as "could not replace"
            }
        } finally {
            endQuiet(inv, quiet);
        }
        return null;
    }

    /**
     * The pattern types a specific container accepts, overriding the container's own (looser)
     * filter. The omniversal alloy furnace's multiblock pattern assembly deliberately accepts its
     * own omniversal patterns <b>and</b> AE2 crafting patterns in its GUI; the player-facing rule
     * for AURELIUM's tools is stricter:
     *
     * <ul>
     *   <li>multiblock pattern assembly — omniversal patterns only;</li>
     *   <li>single-block omniversal alloy furnace — processing patterns and omniversal patterns.</li>
     * </ul>
     *
     * <p>Returns {@code null} for containers that keep their own rules (the common case), so this
     * stays a targeted exception rather than a global filter.</p>
     */
    public static java.util.function.Predicate<ItemStack> patternAllowlist(BlockEntity be) {
        if (be == null) return null;
        String name = be.getClass().getName();
        if (name.equals("com.sorrowmist.useless.content.blockentities.multiblock.MePatternAssemblyBlockEntity")) {
            return PatternContainerAccess::isOmniversalPattern;
        }
        if (name.equals("com.sorrowmist.useless.content.blockentities.AdvancedAlloyFurnaceBlockEntity")) {
            return stack -> isOmniversalPattern(stack) || isAe2ProcessingPattern(stack);
        }
        return null;
    }

    /**
     * True when a stored stack is something the tools may move: an encoded AE2 pattern (item or
     * processing) or the omniversal pattern. Blank/foreign/malformed entries are not patterns and
     * are reported as invalid rather than being pasted (a queued invalid entry is refused by every
     * slot, which used to make an empty container answer "target is full").
     */
    public static boolean isUsablePattern(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty()) return false;
        if (isOmniversalPattern(stack)) return true;
                try {
            return appeng.api.crafting.PatternDetailsHelper.decodePattern(stack, level) != null;
        } catch (Throwable undecodable) {
            return false;
        }
    }
    /** Matches the omniversal pattern item by registry id (the class is mod-local). */
    static boolean isOmniversalPattern(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.toString().equals("useless_mod:omniversal_pattern");
    }

    /** Matches AE2's processing pattern ({@code ae2:processing_pattern}). */
    private static boolean isAe2ProcessingPattern(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && id.toString().equals("ae2:processing_pattern");
    }

    /**
     * Puts stacks back into the first empty slots of the inventory. Used to return the overflow of
     * a cut whose batch did not fit the tool's size budget, so nothing is ever destroyed.
     *
     * <p>Like {@link #cut}, every placement is verified; entries the container refuses stay in the
     * returned list so the caller can hand them to the player instead of losing them.</p>
     *
     * @return the stacks that could <b>not</b> be placed back (empty when everything fit)
     */
    public static List<ItemStack> restore(InternalInventory inv, List<ItemStack> stacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        if (stacks == null || stacks.isEmpty()) return leftovers;
        Object quiet = beginQuiet(inv);
        try {
            int index = 0;
            int size = inv.size();
            for (int slot = 0; slot < size && index < stacks.size(); slot++) {
                if (!inv.getStackInSlot(slot).isEmpty()) continue;
                ItemStack candidate = stacks.get(index);
                inv.setItemDirect(slot, candidate.copy());
                if (inv.getStackInSlot(slot).isEmpty()) break; // refused: stop, keep the rest
                index++;
            }
            for (int i = index; i < stacks.size(); i++) leftovers.add(stacks.get(i));
        } finally {
            endQuiet(inv, quiet);
        }
        return leftovers;
    }

    // --- NeoEcoAE (ECO) specialisation -----------------------------------------------------

    private static final String ECO_BEGIN = "beginPatternBatch";
    private static final String ECO_SET = "setPatternDirect";
    private static final String ECO_END = "endPatternBatch";
    private static final String ECO_INSERT = "insertPatternWithResult";

    /**
     * The ECO host behind the container at {@code pos}, or {@code null} when it is not a NeoEcoAE
     * pattern bus. Detected by the bulk API, so no compile-time NeoEcoAE reference is needed.
     */
    public static Object ecoHost(Level level, BlockPos pos, Direction clickedFace) {
        if (level == null || level.isClientSide) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        return hasEcoBulkApi(be) ? be : null;
    }

    private static boolean hasEcoBulkApi(Object owner) {
        Class<?> type = owner.getClass();
        return methodBySignature(type, ECO_BEGIN) != null
                && methodBySignature(type, ECO_SET) != null
                && methodBySignature(type, ECO_END) != null;
    }

    /**
     * Copies a NeoEcoAE bus' pattern listing (the terminal view; read-only auxiliary rows are
     * included only when the terminal shows them).
     *
     * @return the copied stacks, or {@code null} when the host is not an ECO bus
     */
    public static List<ItemStack> copyEco(Object host) {
        InternalInventory view = fromObject(host);
        if (view == null) return null;
        List<ItemStack> out = new ArrayList<>();
        int size = view.size();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = view.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) continue;
            out.add(stack.copy());
        }
        return out;
    }

    /**
     * Cuts a NeoEcoAE bus' patterns through the terminal view inside the bus' batch window, so its
     * pattern catalog is rebuilt once. Each clear is verified against the live view; a slot that
     * refuses the clear (read-only auxiliary row) is left alone and never reported as cut.
     *
     * @return the cut stacks, or {@code null} when the host is not an ECO bus
     */
    public static List<ItemStack> cutEco(Object host, int limit) {
        InternalInventory view = fromObject(host);
        if (view == null) return null;
        Method begin = methodBySignature(host.getClass(), ECO_BEGIN);
        Method end = methodBySignature(host.getClass(), ECO_END);
        List<ItemStack> taken = new ArrayList<>();
        boolean batchStarted = false;
        if (begin != null && end != null) {
            try {
                begin.invoke(host);
                batchStarted = true;
            } catch (Throwable failure) {
                batchStarted = false;
            }
        }
        Object quiet = batchStarted ? null : beginQuiet(view);
        try {
            int size = view.size();
            for (int slot = 0; slot < size && taken.size() < limit; slot++) {
                ItemStack stack = view.getStackInSlot(slot);
                if (stack == null || stack.isEmpty()) continue;
                view.setItemDirect(slot, ItemStack.EMPTY);
                if (view.getStackInSlot(slot).isEmpty()) {
                    taken.add(stack.copy());
                }
                // else: the slot refused (read-only auxiliary row) — leave it untouched.
            }
        } finally {
            // A started batch must always be closed, or the bus stays stuck in batch mode.
            if (batchStarted) {
                try {
                    end.invoke(host);
                } catch (Throwable ignored) {
                    // released on save at the latest
                }
            } else {
                endQuiet(view, quiet);
            }
        }
        return taken;
    }

    /**
     * Pastes into a NeoEcoAE bus slot by slot through {@code setPatternDirect}, inside the bus'
     * batch window so its pattern catalog is rebuilt exactly once.
     *
     * <p><b>Why not {@code insertPatternWithResult} in a loop:</b> that path picks its target slot
     * from the catalog's {@code emptyPatternSlots} bit set, which the bus only rebuilds when a
     * change arrives outside a batch — during a batch every insert therefore sees the same "first
     * empty slot" and the second pattern lands on an occupied one, yielding {@code NO_SPACE}. The
     * observed symptom was "only one pattern gets pasted". Writing through {@code setPatternDirect}
     * addresses each slot explicitly and is batch-safe.</p>
     *
     * <p>Slots are chosen from the live views: an occupied raw slot is never touched (that covers
     * auxiliary-owned rows the terminal view hides), and every write is verified against the view
     * that owns it. Unlike the bus' own insert path this deliberately does not de-duplicate, so the
     * copy tool can clone the same pattern into several slots — the same behaviour AE2's own
     * providers have.</p>
     *
     * @return the number of stacks placed, or {@code -1} when the host is not an ECO bus
     */
    public static int pasteEco(Object host, List<ItemStack> source, boolean consume) {
        if (source == null || source.isEmpty()) return 0;
        Method set = methodBySignature(host.getClass(), ECO_SET);
        if (set == null) return -1;
        InternalInventory terminal = fromObject(host);
        if (terminal == null) return -1;
        int bound = ecoWritableBound(host, terminal);
        if (bound <= 0) return -1;
        // The raw window (when the bus exposes one) reveals slots the terminal view hides, so an
        // auxiliary-owned row is recognised as occupied instead of being silently overwritten.
        InternalInventory raw = ecoRawInventory(host);
        Method begin = methodBySignature(host.getClass(), ECO_BEGIN);
        Method end = methodBySignature(host.getClass(), ECO_END);
        boolean batchStarted = false;
        if (begin != null && end != null) {
            try {
                begin.invoke(host);
                batchStarted = true;
            } catch (Throwable failure) {
                batchStarted = false;
            }
        }
        int placed = 0;
        // Walk the batch with a read cursor and record which entries landed. Entries a slot
        // refuses stay queued for the NEXT slot (mirroring the generic path), and the survivors
        // are written back in one pass at the end — no per-iteration list shifting.
        boolean[] taken = new boolean[source.size()];
        int cursor = 0;
        try {
            for (int slot = 0; slot < bound; slot++) {
                if (raw != null && !raw.getStackInSlot(slot).isEmpty()) continue;
                if (!terminal.getStackInSlot(slot).isEmpty()) continue;
                while (cursor < source.size() && taken[cursor]) cursor++;
                if (cursor >= source.size()) break;
                ItemStack next = source.get(cursor);
                set.invoke(host, slot, next.copy());
                boolean landed = raw != null
                        ? !raw.getStackInSlot(slot).isEmpty()
                        : !terminal.getStackInSlot(slot).isEmpty();
                if (!landed) continue; // refused (auxiliary row): the same entry retries next slot
                taken[cursor] = true;
                placed++;
            }
        } catch (Throwable failure) {
            if (placed == 0) return -1;
        } finally {
            if (batchStarted) {
                try {
                    end.invoke(host);
                } catch (Throwable ignored) {
                    // released on save at the latest
                }
            }
        }
        if (consume && placed > 0) {
            List<ItemStack> left = new ArrayList<>(source.size() - placed);
            for (int i = 0; i < source.size(); i++) {
                if (!taken[i]) left.add(source.get(i));
            }
            source.clear();
            source.addAll(left);
        }
        return placed;
    }

    /**
     * How many leading slots of an ECO bus are writable. The bus' own
     * {@code getPatternSlotCount()} is authoritative (it excludes terminal-appended auxiliary
     * rows); the terminal view is used as a clamp so a mismatched add-on can never index out of
     * bounds.
     */
    private static int ecoWritableBound(Object host, InternalInventory terminal) {
        int bound = terminal.size();
        Method count = methodBySignature(host.getClass(), "getPatternSlotCount");
        if (count != null) {
            try {
                Object value = count.invoke(host);
                if (value instanceof Integer i && i > 0) bound = Math.min(bound, i);
            } catch (Throwable ignored) {
                // fall back to the terminal size
            }
        }
        InternalInventory raw = ecoRawInventory(host);
        if (raw != null) bound = Math.min(bound, raw.size());
        return bound;
    }

    /** The bus' raw slot window ({@code getPatternSlotInventory}) when it exposes one. */
    private static InternalInventory ecoRawInventory(Object host) {
        Method m = methodBySignature(host.getClass(), "getPatternSlotInventory");
        if (m == null) return null;
        try {
            Object value = m.invoke(host);
            return value instanceof InternalInventory ii && ii.size() > 0 ? ii : null;
        } catch (Throwable failure) {
            return null;
        }
    }

    // --- reflection helpers ----------------------------------------------------------------

    /**
     * Cached reflective lookup, keyed by class <b>and</b> method name. For
     * {@code setPatternDirect} the 2-arg overload is required; for
     * {@code insertPatternWithResult} the 1-arg one.
     */
    private static final Map<String, Optional<Method>> METHOD_CACHE = new ConcurrentHashMap<>();

    private static Method methodBySignature(Class<?> owner, String name) {
        String key = owner.getName() + '#' + name;
        return METHOD_CACHE.computeIfAbsent(key, ignored -> {
            try {
                int expected = switch (name) {
                    case ECO_SET -> 2;
                    case ECO_INSERT -> 1;
                    default -> 0;
                };
                for (Method m : owner.getMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != expected) continue;
                    if (name.equals("getTerminalPatternInventory")
                            && !InternalInventory.class.isAssignableFrom(m.getReturnType())) {
                        continue;
                    }
                    m.setAccessible(true);
                    return Optional.of(m);
                }
            } catch (Throwable ignored2) {
                // fall through to an empty result
            }
            return Optional.empty();
        }).orElse(null);
    }

    // --- Useless Mod change batching ---------------------------------------------------------
    //
    // The Useless Mod external-inventory containers (the omniversal pattern assembly and its
    // siblings) keep their slots in world saved data and re-serialise the WHOLE container from
    // onContentsChanged. A bulk write therefore cost one full NBT dump per slot: a spark profile of
    // a paste on a 4096-slot assembly measured ~9 ms inside every setItemDirect, all of it under
    // ExternalInventoryStore.save -> saveFrom. Writing a few hundred slots meant seconds of freeze.
    //
    // The handler guards that callback with a plain depth counter (onContentsChanged returns early
    // while the depth is positive, remembering that one notification is owed). We raise that counter
    // for the duration of a bulk walk and lower it afterwards, so the container still notifies — and
    // saves — exactly once per batch instead of once per slot.
    //
    // Reflection, not a hard reference: Useless Mod is an optional runtime dependency and its
    // classes are absent from the dev classpath. Anything unexpected (absent mod, renamed field,
    // blocked reflection) silently degrades to the previous unbatched behaviour.

    /** The Useless Mod handler class that owns the depth counter. */
    private static final String USELESS_HANDLER =
            "com.sorrowmist.useless.content.blockentities.RecoverableItemStackHandler";

    /** Reports the batching state once per JVM, so a silent fallback cannot go unnoticed. */
    private static final java.util.concurrent.atomic.AtomicBoolean BATCH_REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** One open change-batch scope. */
    private interface ChangeBatch {
        /** Lowers the depth counter, firing the container's single deferred change if owed. */
        void release();

        /**
         * Raises the handler's change-batch depth, or returns {@code null} when the container is not
         * a Useless Mod external inventory (or the field is not reachable).
         */
        static ChangeBatch open(InternalInventory inv) {
            if (inv == null) return null;
            try {
                Class<?> handlerClass = Class.forName(USELESS_HANDLER);
                Object handler = findHandler(inv, handlerClass, 0);
                if (handler == null) return null;
                BatchScope scope = new BatchScope(handler);
                if (!scope.enter()) {
                    warnUnbatched("changeBatchDepth 不可达");
                    return null;
                }
                if (BATCH_REPORTED.compareAndSet(false, true)) {
                    LOGGER.info("AURELIUM：已启用无用之物外部库存的批量写入（整个批次只保存一次容器）。");
                }
                return scope;
            } catch (ClassNotFoundException absent) {
                return null; // Useless Mod is not installed: nothing to batch
            } catch (Throwable unsupported) {
                warnUnbatched(unsupported.toString());
                return null;
            }
        }
    }

    /** Direct manipulation of the handler's depth counter (see the section comment for why). */
    private static final class BatchScope implements ChangeBatch {
        private final Object handler;
        private boolean entered;

        BatchScope(Object handler) {
            this.handler = handler;
        }

        /** Raises the depth by one; false when the field is not reachable. */
        boolean enter() {
            Field field = cachedField(handler.getClass(), "changeBatchDepth");
            if (field == null) return false;
            try {
                // A bulk walk runs on the server thread only, so a plain read/write is enough; the
                // counter is what the handler itself checks on every change.
                field.setInt(handler, field.getInt(handler) + 1);
                entered = true;
                return true;
            } catch (Throwable unreachable) {
                return false;
            }
        }

        @Override
        public void release() {
            if (!entered) return;
            entered = false;
            Field field = cachedField(handler.getClass(), "changeBatchDepth");
            if (field == null) return;
            try {
                int depth = Math.max(0, field.getInt(handler) - 1);
                field.setInt(handler, depth);
                if (depth > 0) return;
                // Depth is back to zero: if a change was deferred, fire the handler's own listener,
                // which is exactly what withChangeBatch would have done on exit.
                Field pending = cachedField(handler.getClass(), "changePending");
                Field listener = cachedField(handler.getClass(), "changeListener");
                if (pending == null || listener == null || !pending.getBoolean(handler)) return;
                pending.setBoolean(handler, false);
                Object runnable = listener.get(handler);
                if (runnable instanceof Runnable r) r.run();
            } catch (Throwable ignored) {
                // best-effort: the container simply keeps its last saved state until the next change
            }
        }
    }

    /** One-shot diagnostic: batching was expected but could not be enabled. */
    private static void warnUnbatched(String why) {
        if (BATCH_REPORTED.compareAndSet(false, true)) {
            LOGGER.warn("AURELIUM：检测到无用之物的外部库存，但未能启用批量写入（{}）。"
                    + "批量剪切/粘贴会退化为逐槽保存，可能明显卡顿。", why);
        }
    }

    /**
     * Finds the Useless Mod handler behind an inventory.
     *
     * <p>Deliberately <b>not</b> driven by a list of remembered field names: AE2 wraps these
     * inventories differently per add-on (combined views, filtered views, item-handler adapters),
     * and the pattern assembly even hands back an anonymous subclass of the handler. Instead the
     * object graph is walked and every non-static field whose type could hold an inventory is
     * followed, which stays correct when a wrapper is renamed or swapped.</p>
     */
    private static Object findHandler(Object node, Class<?> handlerClass, int depth) {
        if (node == null || depth > 4) return null;
        if (handlerClass.isInstance(node)) return node;
        if (node instanceof CompositeInventory composite) {
            for (InternalInventory part : composite.parts()) {
                Object found = findHandler(part, handlerClass, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (node instanceof InternalInventory[] array) {
            for (InternalInventory part : array) {
                Object found = findHandler(part, handlerClass, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (node instanceof Iterable<?> iterable && !(node instanceof InternalInventory)) {
            for (Object element : iterable) {
                Object found = findHandler(element, handlerClass, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        // Follow the object's own fields. Only values that actually look like an inventory are
        // descended into, so this cannot wander into arbitrary world state.
        for (Class<?> type = node.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field f : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!INVENTORY_LIKE.isAssignableFrom(f.getType())
                        && !InternalInventory.class.isAssignableFrom(f.getType())
                        && !f.getType().isArray()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object value = f.get(node);
                    Object found = findHandler(value, handlerClass, depth + 1);
                    if (found != null) return found;
                } catch (Throwable ignored) {
                    // best-effort: an unreadable field simply is not followed
                }
            }
        }
        return null;
    }

    /** Field types that may hold a wrapped inventory (NeoForge's item-handler contract). */
    private static final Class<?> INVENTORY_LIKE;

    static {
        Class<?> handlerType;
        try {
            handlerType = Class.forName("net.neoforged.neoforge.items.IItemHandler");
        } catch (Throwable absent) {
            handlerType = Object.class;
        }
        INVENTORY_LIKE = handlerType;
    }
    // --- resumable (tick-sliced) bulk walks ------------------------------------------------
    //
    // A held container can expose thousands of slots (the omniversal pattern assembly glues every
    // core of its multiblock into one view). The one-shot walks further up are fine for a few dozen
    // slots but stall the server tick on that scale, which is what made cut/paste "freeze".
    //
    // These sessions run the same logic with a per-call slot budget, so a caller can spread one
    // walk over many ticks and show progress in between.

    /**
     * A resumable walk over one inventory. The AE2 re-entrancy flag is raised for the whole
     * session and released by {@link #close()} — callers must always close, including when the job
     * aborts (a player logging out mid-walk would otherwise leave the flag stuck on).
     */
    public abstract static class BulkSession implements AutoCloseable {
        final InternalInventory inv;
        private final Object quiet;
        /**
         * The Useless Mod change-batch scope, when the container is one of its external-inventory
         * machines. Without it, every single slot write fires the host's {@code onContentsChanged},
         * which re-serialises the whole container into world-saved data — measured at roughly 9 ms
         * per write on a 4096-slot pattern assembly, i.e. seconds of freeze for one paste.
         */
        private final ChangeBatch uselessBatch;
        private int cursor;
        private boolean closed;

        BulkSession(InternalInventory inv) {
            this.inv = inv;
            this.quiet = beginQuiet(inv);
            this.uselessBatch = ChangeBatch.open(inv);
        }

        /** Total slots in the wrapped inventory (the denominator for progress display). */
        public int size() {
            return inv.size();
        }

        /** Slots already visited. */
        public int cursor() {
            return cursor;
        }

        /** True when the underlying quiet flag has been released. */
        public synchronized boolean isClosed() {
            return closed;
        }

        /** True when this container's per-slot save is being collapsed into one. */
        public boolean batched() {
            return uselessBatch != null;
        }

        /**
         * Processes up to {@code budget} more slots. Returns {@code true} when the whole inventory
         * has been visited.
         */
        public boolean step(int minSlots, long deadline) {
            int size = inv.size();
            int floor = Math.max(1, minSlots);
            int done = 0;
            while (cursor < size && (done < floor || System.nanoTime() < deadline)) {
                visit(cursor);
                cursor++;
                done++;
            }
            return cursor >= size;
        }

        /** Called for each visited slot. */
        abstract void visit(int slot);

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            // Close the Useless batch first: it fires its one deferred change notification (and thus
            // the single save) while AE2's re-entrancy flag is still raised, so the refresh that
            // follows does not re-trigger a per-slot notification storm.
            if (uselessBatch != null) uselessBatch.release();
            endQuiet(inv, quiet);
        }
    }

    /** Resumable {@link #cut}: clears accepted slots and records what was actually taken. */
    public static final class CutSession extends BulkSession {
        private final int limit;
        private final java.util.function.Predicate<ItemStack> allowlist;
        private final List<ItemStack> taken = new ArrayList<>();

        public CutSession(InternalInventory inv, int limit,
                          java.util.function.Predicate<ItemStack> allowlist) {
            super(inv);
            this.limit = limit;
            this.allowlist = allowlist;
        }

        /** Everything successfully removed so far. */
        public List<ItemStack> taken() {
            return taken;
        }

        @Override
        void visit(int slot) {
            if (taken.size() >= limit) return;
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) return;
            if (allowlist != null && !allowlist.test(stack)) return;
            inv.setItemDirect(slot, ItemStack.EMPTY);
            // Verified against the live view: a slot that refuses the write is never reported as
            // taken (see the one-shot cut for the reasoning).
            if (inv.getStackInSlot(slot).isEmpty()) taken.add(stack.copy());
        }

        public boolean limitReached() {
            return taken.size() >= limit;
        }
    }

    /** Resumable {@link #collect}: reads accepted slots, leaving the source untouched. */
    public static final class CollectSession extends BulkSession {
        private final int limit;
        private final java.util.function.Predicate<ItemStack> allowlist;
        private final List<ItemStack> collected = new ArrayList<>();

        public CollectSession(InternalInventory inv, int limit,
                              java.util.function.Predicate<ItemStack> allowlist) {
            super(inv);
            this.limit = limit;
            this.allowlist = allowlist;
        }

        public List<ItemStack> collected() {
            return collected;
        }

        @Override
        void visit(int slot) {
            if (collected.size() >= limit) return;
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) return;
            if (allowlist != null && !allowlist.test(stack)) return;
            collected.add(stack.copy());
        }

        public boolean limitReached() {
            return collected.size() >= limit;
        }
    }

    /**
     * One pass over the target that gathers <b>everything a paste needs</b>:
     *
     * <ul>
     *   <li>the primary output of every occupied slot (the duplicate guard), and</li>
     *   <li>the exact free slots an entry may be written into.</li>
     * </ul>
     *
     * <p>Doing both in one walk is what keeps a paste to a single traversal. The earlier version
     * scanned the container for outputs, called {@link #freeSlots} for the capacity check and then
     * walked it a third time while placing — three full passes over a multi-thousand-slot matrix,
     * which is why pasting was slow and its progress appeared to restart.</p>
     *
     * <p>The slot list is recorded while the session is quiet, so it stays valid for the whole
     * placement: no other write happens in between.</p>
     */
    public static final class TargetScan extends BulkSession {
        private final Level level;
        private final java.util.function.Predicate<ItemStack> allowlist;
        private final java.util.Set<AEKey> outputs = new java.util.HashSet<>();
        private final List<Integer> freeSlots = new ArrayList<>();
        /** Identity keys of stacks already decoded, so repeated patterns are parsed once. */
        private final java.util.Set<String> seen = new java.util.HashSet<>();
        private int occupied;

        public TargetScan(InternalInventory inv, Level level,
                          java.util.function.Predicate<ItemStack> allowlist) {
            super(inv);
            this.level = level;
            this.allowlist = allowlist;
        }

        public java.util.Set<AEKey> outputs() {
            return outputs;
        }

        /** Slot indexes that were empty when the scan ran, in ascending order. */
        public List<Integer> freeSlots() {
            return freeSlots;
        }

        /** The stricter per-container filter (the omniversal machinery), or {@code null}. */
        public java.util.function.Predicate<ItemStack> allowlist() {
            return allowlist;
        }

        /** Slots that already hold something (reported to the player as "container usage"). */
        public int occupied() {
            return occupied;
        }

        /**
         * Records a slot that became free after the scan (replace mode vacating a duplicate's slot).
         * Without this the replacement could not reuse the slot it had just cleared.
         */
        public void addFreeSlot(int slot) {
            if (slot >= 0 && !freeSlots.contains(slot)) freeSlots.add(slot);
        }

        @Override
        void visit(int slot) {
            ItemStack stack = inv.getStackInSlot(slot);
            if (stack == null || stack.isEmpty()) {
                freeSlots.add(slot);
                return;
            }
            occupied++;
            // Decoding a pattern is the expensive half of a scan, and pattern slots are highly
            // repetitive (a matrix typically holds the same handful of stacks many times over).
            // Key each slot by item + components so an identical stack is decoded only once.
            if (!seen.add(stack.getItem() + "\u0000" + stack.getComponents().toString())) return;
            AEKey output = PatternOutputs.primaryOutput(stack, level);
            if (output != null) outputs.add(output);
        }
    }

    /**
     * Writes pre-decided entries into pre-scanned slots. Both lists come from {@link TargetScan},
     * so the placement is a straight copy with no further inspection of the container.
     *
     * <p>Entries are consumed from {@code pending} as they land, which is what lets the caller tell
     * "placed" from "left over" without a second pass.</p>
     */
    public static final class PlaceSession implements AutoCloseable {
        private final InternalInventory inv;
        private final Object quiet;
        /**
         * The Useless Mod change-batch scope (see {@link ChangeBatch}): collapses the container's
         * per-slot save into a single one when the scope closes.
         */
        private final ChangeBatch uselessBatch;
        private final java.util.function.Predicate<ItemStack> allowlist;
        /**
         * Entries still to place. An {@link java.util.ArrayDeque} rather than a {@code List}:
         * rotating refused entries moves the head to the tail, which is O(1) here and O(n) on an
         * {@code ArrayList} — with thousands of entries that difference alone made a pasted batch
         * crawl.
         */
        private final java.util.ArrayDeque<ItemStack> pending;
        private final int[] slots;
        private int slotCursor;
        private int consecutiveSkips;
        private int placed;
        private final List<Placement> placements = new ArrayList<>();
        /** Entries the container's own filter refused at least once (never placed, never consumed). */
        private int filterRefused;
        private boolean closed;

        /** One verified write, retained so an interrupted paste can be rolled back exactly. */
        public record Placement(int slot, ItemStack stack) {
        }

        /**
         * @param allowlist extra filter stricter than the container's own (may be {@code null})
         * @param pending   entries to place; entries that land are <b>consumed</b> from this queue
         * @param slots     the free slots discovered by the scan, in the order they were found
         */
        public PlaceSession(InternalInventory inv, java.util.function.Predicate<ItemStack> allowlist,
                            List<ItemStack> pending, List<Integer> slots) {
            this.inv = inv;
            this.quiet = beginQuiet(inv);
            this.uselessBatch = ChangeBatch.open(inv);
            this.allowlist = allowlist;
            this.pending = new java.util.ArrayDeque<>(pending);
            this.slots = new int[slots.size()];
            for (int i = 0; i < slots.size(); i++) this.slots[i] = slots.get(i);
        }

        public int placed() {
            return placed;
        }

        /** Free slots examined so far (drives the second half of the progress bar). */
        public int slotsVisited() {
            return slotCursor;
        }

        /** True when this container's per-slot save is being collapsed into one. */
        public boolean batched() {
            return uselessBatch != null;
        }

        /** Entries the container itself refused — the reason a paste can place nothing at all. */
        public int filterRefused() {
            return filterRefused;
        }

        /** Total free slots this session may write into. */
        public int totalSlots() {
            return slots.length;
        }

        /** True when every entry found a slot. */
        public boolean pendingEmpty() {
            return pending.isEmpty();
        }

        /** Entries that could not be placed (the caller keeps them in the tool). */
        public List<ItemStack> leftover() {
            return new ArrayList<>(pending);
        }

        /** Entries this session has successfully written so far. */
        public List<Placement> placements() {
            return List.copyOf(placements);
        }

        public synchronized boolean isClosed() {
            return closed;
        }

        /**
         * Places up to {@code budget} entries. Returns {@code true} when there is nothing left to
         * place (either everything landed or every remaining entry was refused).
         */
        public boolean step(int minSlots, long deadline) {
            if (pending.isEmpty()) return true;
            int floor = Math.max(1, minSlots);
            int processed = 0;
            while (slotCursor < slots.length
                    && (processed < floor || System.nanoTime() < deadline)) {
                // Everything found a slot: stop immediately. Without this the loop kept going,
                // peekFirst() returned null on the next empty slot, removeFirst() threw
                // NoSuchElementException, and the abort skipped the write-back entirely — the
                // container kept the patterns while the tool appeared not to consume them.
                if (pending.isEmpty()) break;
                int slot = slots[slotCursor];
                slotCursor++;
                processed++;
                if (!inv.getStackInSlot(slot).isEmpty()) continue; // taken by someone else
                ItemStack next = pending.peekFirst();
                if (allowlist != null && !allowlist.test(next)) {
                    filterRefused++;
                    // Rotate and keep scanning; bail out once every remaining entry was refused in
                    // a row so unfillable slots can never spin forever.
                    pending.addLast(pending.removeFirst());
                    if (++consecutiveSkips >= pending.size()) break;
                    continue;
                }
                if (!accepts(inv, slot, next)) {
                    filterRefused++;
                    pending.addLast(pending.removeFirst());
                    if (++consecutiveSkips >= pending.size()) break;
                    continue;
                }
                consecutiveSkips = 0;
                inv.setItemDirect(slot, next.copy());
                if (inv.getStackInSlot(slot).isEmpty()) continue; // read-only/refused slot
                pending.removeFirst();
                placements.add(new Placement(slot, next.copy()));
                placed++;
            }
            return pending.isEmpty() || slotCursor >= slots.length;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            // Close the Useless batch first: it fires its one deferred change notification (and thus
            // the single save) while AE2's re-entrancy flag is still raised, so the refresh that
            // follows does not re-trigger a per-slot notification storm.
            if (uselessBatch != null) uselessBatch.release();
            endQuiet(inv, quiet);
        }

        /** Removes every write made by this session that is still present unchanged. */
        public int rollback() {
            return rollbackPlacements(inv, placements);
        }
    }

    /** Best-effort exact undo for an interrupted placement session. */
    private static int rollbackPlacements(InternalInventory inv, List<PlaceSession.Placement> placements) {
        if (inv == null || placements == null || placements.isEmpty()) return 0;
        Object quiet = beginQuiet(inv);
        ChangeBatch batch = ChangeBatch.open(inv);
        try {
            int removed = 0;
            for (int i = placements.size() - 1; i >= 0; i--) {
                PlaceSession.Placement placement = placements.get(i);
                ItemStack current = inv.getStackInSlot(placement.slot());
                if (current == null || current.isEmpty()) continue;
                if (!ItemStack.isSameItemSameComponents(current, placement.stack())) continue;
                if (current.getCount() != placement.stack().getCount()) continue;
                inv.setItemDirect(placement.slot(), ItemStack.EMPTY);
                if (inv.getStackInSlot(placement.slot()).isEmpty()) removed++;
            }
            return removed;
        } finally {
            if (batch != null) batch.release();
            endQuiet(inv, quiet);
        }
    }

    // --- notification handling -------------------------------------------------------------

    private static volatile Field notifyField;
    private static volatile boolean notifyFieldResolved;

    private record Quiet(List<AppEngInternalInventory> inventories) {
    }

    /**
     * Raises AE2's per-inventory re-entrancy flag so a bulk write does not fire one host
     * notification per slot. The flag lives on the innermost {@link AppEngInternalInventory}, so
     * composite wrappers ({@code CombinedInternalInventory}, {@code FilteredInternalInventory},
     * this file's {@code CompositeInventory}) are walked through reflectively; without that, every
     * slot of a 72-core matrix would rebuild the whole pattern catalog. Returns {@code null} when
     * no wrapped inventory supports the mechanism.
     */
    private static Object beginQuiet(InternalInventory inv) {
        Field field = notifyField();
        if (field == null) return null;
        List<AppEngInternalInventory> targets = new ArrayList<>(4);
        collectQuietTargets(inv, targets, 0);
        if (targets.isEmpty()) return null;
        try {
            List<AppEngInternalInventory> raised = new ArrayList<>(targets.size());
            for (AppEngInternalInventory ai : targets) {
                if (field.getBoolean(ai)) continue; // already inside a notification: leave it
                field.setBoolean(ai, true);
                raised.add(ai);
            }
            return raised.isEmpty() ? null : new Quiet(List.copyOf(raised));
        } catch (Throwable failure) {
            releaseQuiet(field, targets);
            return null;
        }
    }

    private static void endQuiet(InternalInventory inv, Object token) {
        if (token instanceof Quiet quiet) {
            Field field = notifyField();
            if (field != null) {
                for (AppEngInternalInventory ai : quiet.inventories()) {
                    try {
                        field.setBoolean(ai, false);
                    } catch (Throwable ignored) {
                        // best-effort; the refresh below still runs
                    }
                }
            }
        }
        refresh(inv);
    }

    private static void releaseQuiet(Field field, List<AppEngInternalInventory> targets) {
        for (AppEngInternalInventory ai : targets) {
            try {
                field.setBoolean(ai, false);
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static final java.util.Set<Class<?>> QUIET_HANDLED = ConcurrentHashMap.newKeySet();

    /** Collects the wrapped {@link AppEngInternalInventory} instances of a composite inventory. */
    private static void collectQuietTargets(InternalInventory inv, List<AppEngInternalInventory> out,
                                            int depth) {
        if (inv == null || depth > 4 || out.size() >= 256) return;
        if (inv instanceof AppEngInternalInventory ai) {
            out.add(ai);
            return;
        }
        if (inv instanceof CompositeInventory composite) {
            for (InternalInventory part : composite.parts()) {
                collectQuietTargets(part, out, depth + 1);
            }
            return;
        }
        // AE2's CombinedInternalInventory & FilteredInternalInventory: walk their arrays/delegate.
        Class<?> type = inv.getClass();
        for (String fieldName : new String[] { "inventories", "delegate" }) {
            Field f = cachedField(type, fieldName);
            if (f == null) continue;
            try {
                Object value = f.get(inv);
                if (value instanceof InternalInventory[] array) {
                    for (InternalInventory part : array) {
                        collectQuietTargets(part, out, depth + 1);
                    }
                } else if (value instanceof InternalInventory single) {
                    collectQuietTargets(single, out, depth + 1);
                }
            } catch (Throwable ignored) {
                // best-effort: the slot still gets written, it just notifies
            }
        }
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Aurelium/PatternContainer");

    private static final Map<String, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private static Field cachedField(Class<?> owner, String name) {
        String key = owner.getName() + '#' + name;
        return FIELD_CACHE.computeIfAbsent(key, ignored -> {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return Optional.of(f);
                } catch (Throwable notPresent) {
                    // keep walking the hierarchy
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** Trigger one pattern refresh (and a save) after a silent bulk write. */
    private static void refresh(InternalInventory inv) {
        if (!(inv instanceof AppEngInternalInventory ai)) return;
        InternalInventoryHost host = ai.getHost();
        if (host == null) return;
        try {
            host.saveChangedInventory(ai);
        } catch (Throwable ignored) {
            // best-effort save; the refresh below still runs
        }
        // Providers rebuild their decoded pattern list here; hosts whose saveChangedInventory
        // already does that simply run it twice, which is harmless.
        invokeNoArg(host, "updatePatterns");
    }

    /**
     * Invokes the given no-argument method, returning its value ({@code null} when the method is
     * missing, not no-arg, or threw). Used both for optional refresh hooks (ignoring the result)
     * and for the multiblock cluster walk (using it).
     */
    private static Object invokeNoArg(Object owner, String name) {
        try {
            Method m = owner.getClass().getMethod(name);
            if (m.getParameterCount() != 0) return null;
            return m.invoke(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field notifyField() {
        if (!notifyFieldResolved) {
            synchronized (PatternContainerAccess.class) {
                if (!notifyFieldResolved) {
                    try {
                        Field f = AppEngInternalInventory.class.getDeclaredField("notifyingChanges");
                        f.setAccessible(true);
                        notifyField = f;
                    } catch (Throwable notPresent) {
                        notifyField = null;
                    }
                    notifyFieldResolved = true;
                }
            }
        }
        return notifyField;
    }
}
