package com.muuxa.aurelium.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.muuxa.aurelium.api.AureliumInfinite;
import com.muuxa.aurelium.api.InfiniteKindSpec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AE2 storage cell that makes a declared set of items infinitely available.
 *
 * <p>Behaviour:</p>
 * <ul>
 *   <li><b>Extract</b> of a marked item always succeeds and never depletes — the cell
 *       reports {@link Long#MAX_VALUE} available, which AE2 renders as {@code ∞}.</li>
 *   <li><b>Insert</b> of a marked item is accepted and then <b>voided</b> (nothing is kept),
 *       so piping items in destroys them instead of growing a count.</li>
 *   <li>Unmarked items are neither stored nor served.</li>
 * </ul>
 *
 * <p>The configured kind is read from the cell's {@code CustomData} tag (like packs), so a
 * single item id can back many differently-configured infinite cells.</p>
 */
public final class InfiniteCellInventory implements StorageCell {
    public static final String KIND_TAG = "aurelium_infinite";

    private final ItemStack stack;
    @SuppressWarnings("unused")
    private final ISaveProvider saveProvider;

    InfiniteCellInventory(ItemStack stack, ISaveProvider saveProvider) {
        this.stack = stack;
        this.saveProvider = saveProvider;
    }

    /** The declared kind id stored on this cell, or {@code null} when unset/unknown. */
    public static String kindId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag tag = data(stack);
        return tag.contains(KIND_TAG) ? tag.getString(KIND_TAG) : null;
    }

    public static void setKindId(ItemStack stack, String kindId) {
        CompoundTag tag = data(stack);
        tag.putString(KIND_TAG, kindId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    private static CompoundTag data(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        return custom == null ? new CompoundTag() : custom.copyTag();
    }

    /** Resolved list of keys this cell serves infinitely (all AEKey types), or empty. */
    public java.util.List<AEKey> infiniteKeys() {
        java.util.List<AEKey> keys = new java.util.ArrayList<>();
        for (String token : infiniteItems()) {
            AEKey key = keyOf(token);
            if (key != null) keys.add(key);
        }
        return keys;
    }

    /** Live registries, or {@code null} when unavailable (e.g. off the server thread). */
    private static net.minecraft.core.HolderLookup.Provider registries() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.registryAccess();
    }

    private boolean isInfinite(AEKey key) {
        if (key == null) return false;
        for (AEKey served : infiniteKeys()) {
            if (served.equals(key)) return true;
        }
        return false;
    }
    public Set<String> infiniteItems() {
        String kind = kindId(stack);
        if (kind == null) return Set.of();
        InfiniteKindSpec spec = AureliumInfinite.get(kind);
        if (spec == null) return Set.of();
        return new LinkedHashSet<>(spec.items());
    }

    /** Public query: is {@code key} one this cell serves infinitely? (for compat bridges) */
    public boolean servesInfinitely(AEKey key) {
        return isInfinite(key);
    }

    @Override
    public CellState getStatus() {
        return infiniteItems().isEmpty() ? CellState.EMPTY : CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() { return 0.0; }

    @Override
    public boolean canFitInsideCell() { return false; }

    @Override
    public void persist() { /* nothing to persist; the cell holds no real contents */ }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0L || !isInfinite(key)) return 0L;
        // Accept then void: the cell never grows, marked items are destroyed on input.
        return amount;
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (amount <= 0L || !isInfinite(key)) return 0L;
        // Never depletes: always serve the full request.
        return amount;
    }

    /**
     * Reported available amount for each infinite key.
     *
     * <p>Deliberately NOT {@link Long#MAX_VALUE}: AE2 sums reported amounts with a plain
     * {@code long +} (no saturation), so two infinite cells reporting MAX would overflow
     * to a negative number. This value is large enough to always read as "infinite" in the
     * UI, yet small enough that stacking many such cells cannot overflow (needs ~64 of
     * them to reach MAX). The client treats any amount >= this as infinite.</p>
     */
    public static final long REPORTED_AMOUNT = Long.MAX_VALUE / 64;

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (AEKey key : infiniteKeys()) {
            out.add(key, REPORTED_AMOUNT);
        }
    }

    /**
     * Resolve a declaration token to a live key of <b>any</b> AEKey type.
     *
     * <p>Uses {@link KeyCodec#decode} with live registries, so alongside plain items
     * ({@code minecraft:diamond}) and fluids ({@code fluid:minecraft:water}) this also handles the
     * generic {@code key:<SNBT>} form — e.g. Mekanism chemicals, or item stacks with components.
     * Returns {@code null} when registries are unavailable or the token cannot be resolved.</p>
     */
    private static AEKey keyOf(String token) {
        if (token == null) return null;
        var registries = registries();
        if (registries == null) return null;
        try {
            return KeyCodec.decode(token, registries);
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    @Override
    public Component getDescription() {
        String kind = kindId(stack);
        InfiniteKindSpec spec = kind == null ? null : AureliumInfinite.get(kind);
        if (spec == null || !spec.hasCustomTitle()) return stack.getHoverName();
        return Component.literal(spec.title());
    }
}