package com.muuxa.aurelium.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

/**
 * AURELIUM infinite disk: a drive-mounted storage cell whose amounts are {@link BigInteger}
 * (unbounded, far beyond {@code long}), and which — like the vault — keeps the <b>item free of any
 * content data</b>. The stack only carries a UUID ({@value #UUID_TAG}); every stored amount lives in
 * {@code <world>/data/Aurelium/disk_cells_<uuid>.nbt} (see {@link InfiniteDiskWorldData}).
 *
 * <p>AC2 always sees a single key per type, reported saturated to {@code long} (rendered {@code ∞}).</p>
 */
public final class InfiniteDiskInventory implements StorageCell {

    /** UUID tag on the item. Mirrors the vault's tag name. */
    public static final String UUID_TAG = "aurelium_uuid";

    private final ItemStack stack;
    @SuppressWarnings("unused")
    private final ISaveProvider saveProvider;

    InfiniteDiskInventory(ItemStack stack, ISaveProvider saveProvider) {
        this.stack = stack;
        this.saveProvider = saveProvider;
    }

    private static CompoundTag tag(ItemStack item) {
        CustomData data = item.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void tag(ItemStack item, CompoundTag value) {
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(value));
    }

    /** Existing UUID on the item, or {@code null}. */
    public static UUID existingId(ItemStack item) {
        CompoundTag data = tag(item);
        return data.hasUUID(UUID_TAG) ? data.getUUID(UUID_TAG) : null;
    }

    /** Ensure the item carries a UUID (creating one if needed). */
    public static void ensureId(ItemStack item) {
        CompoundTag data = tag(item);
        UUID id = data.hasUUID(UUID_TAG) ? data.getUUID(UUID_TAG) : UUID.randomUUID();
        // Keep ONLY the uuid on the stack, exactly like the vault: no stored-item ids and no amount
        // arrays ever live on the item — every amount stays in data/Aurelium/disk_cells_<uuid>.nbt.
        CompoundTag only = new CompoundTag();
        only.putUUID(UUID_TAG, id);
        tag(item, only);
    }

    private static MinecraftServer server() {
        return ServerLifecycleHooks.getCurrentServer();
    }

    private UUID uuid(boolean create) {
        CompoundTag data = tag(stack);
        if (!data.hasUUID(UUID_TAG) && create) {
            ensureId(stack);
            if (saveProvider != null) saveProvider.saveChanges();
            data = tag(stack);
        }
        return data.hasUUID(UUID_TAG) ? data.getUUID(UUID_TAG) : null;
    }

    /** The live content map for this disk (never a copy); empty when off the server thread. */
    private Map<AEKey, BigInteger> snapshot() {
        MinecraftServer server = server();
        if (server == null || !server.isSameThread()) return Map.of();
        UUID id = uuid(true);
        if (id == null) return Map.of();
        return InfiniteDiskWorldData.get(server).liveMap(id, server.registryAccess());
    }

    /** A defensive copy (used by the IO port and the tooltip summary). */
    public Map<AEKey, BigInteger> contents() {
        return new java.util.HashMap<>(snapshot());
    }

    // --- Summary (for the tooltip) ---

    public record Summary(BigInteger total, int itemTypes, int fluidTypes, int otherTypes) {
        public int types() { return itemTypes + fluidTypes + otherTypes; }
    }

    public static Summary summary(ItemStack stack) {
        return new InfiniteDiskInventory(stack, null).computeSummary();
    }

    public Summary computeSummary() {
        BigInteger total = BigInteger.ZERO;
        int items = 0, fluids = 0, others = 0;
        for (var e : contents().entrySet()) {
            BigInteger a = e.getValue();
            if (a.signum() > 0) total = total.add(a);
            if (e.getKey() instanceof AEItemKey) items++;
            else if (e.getKey() instanceof AEFluidKey) fluids++;
            else others++;
        }
        return new Summary(total, items, fluids, others);
    }

    // --- BigInteger API (used by the Advanced IO Port) ---

    /** Live snapshot of every stored key with its full (possibly >long) amount. */
    public Map<AEKey, BigInteger> snapshotMap() {
        return contents();
    }

    /** Insert an arbitrary BigInteger amount (unbounded). Returns the amount accepted. */
    public BigInteger insertBig(AEKey key, BigInteger amount) {
        if (key == null || amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        MinecraftServer server = server();
        if (server == null || !server.isSameThread()) return BigInteger.ZERO;
        UUID id = uuid(true);
        if (id == null) return BigInteger.ZERO;
        return InfiniteDiskWorldData.get(server).credit(id, key, amount, server.registryAccess());
    }

    /** Extract up to {@code requested}; returns the amount actually taken. */
    public BigInteger extractBig(AEKey key, BigInteger requested) {
        if (key == null || requested == null || requested.signum() <= 0) return BigInteger.ZERO;
        MinecraftServer server = server();
        if (server == null || !server.isSameThread()) return BigInteger.ZERO;
        UUID id = uuid(true);
        if (id == null) return BigInteger.ZERO;
        return InfiniteDiskWorldData.get(server).debit(id, key, requested, server.registryAccess());
    }

    // --- StorageCell ---

    @Override
    public CellState getStatus() {
        return snapshot().isEmpty() ? CellState.EMPTY : CellState.NOT_EMPTY;
    }

    @Override
    public double getIdleDrain() {
        return 1.0;
    }

    @Override
    public boolean canFitInsideCell() {
        return false;
    }

    @Override
    public void persist() {
        // write-through on every credit/debit; nothing buffered on the item
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (key == null || amount <= 0L) return 0L;
        // SIMULATE must report the full amount as insertable, otherwise AE2 (and its IO ports, which
        // probe with SIMULATE before a real insert) believes the disk has no room and never tries.
        if (mode == Actionable.SIMULATE) return amount;
        BigInteger accepted = insertBig(key, BigInteger.valueOf(amount));
        // MEStorage.insert returns the amount ACCEPTED (not the leftover): AE2 does
        // `remaining -= inventory.insert(...)`. Returning leftover here made every insert look
        // like it failed, so items could never be stored.
        return accepted.longValue();
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        if (key == null || amount <= 0L) return 0L;
        BigInteger stored = snapshot().getOrDefault(key, BigInteger.ZERO);
        BigInteger taken = stored.min(BigInteger.valueOf(amount));
        if (taken.signum() <= 0) return 0L;
        if (mode == Actionable.MODULATE) {
            extractBig(key, taken);
        }
        return taken.longValue();
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        BigInteger cap = BigInteger.valueOf(Long.MAX_VALUE);
        for (var e : snapshot().entrySet()) {
            BigInteger a = e.getValue();
            if (a.signum() > 0) out.add(e.getKey(), a.min(cap).longValue());
        }
    }

    @Override
    public Component getDescription() {
        return stack.getHoverName();
    }
}