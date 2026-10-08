package com.muuxa.aurelium.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.CellState;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import com.muuxa.aurelium.api.AureliumPacks;
import com.muuxa.aurelium.api.PackSpec;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AE2 StorageCell implementation backed by one file per vault UUID.
 *
 * <p>The item deliberately carries <b>only</b> the UUID ({@code aurelium_uuid}); all counts live
 * in {@code <world>/data/Aurelium/vault_cells_<uuid>.nbt}. Keeping the item free of summary data
 * makes it trivial to trace a disk back to its file — and stops stale item NBT from ever
 * disagreeing with the on-disk truth.</p>
 */
public class VaultInventory implements StorageCell {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Aurelium/Vault");

    public static final String UUID_TAG = "aurelium_uuid";
    public static final String SEED_TAG = "aurelium_pack";
    /** Nested-vault chain length INCLUDING this vault (a lone vault is 1). Capped at the config value. */
    public static final String NEST_TAG = "aurelium_nest";

    /** Maximum vault-nesting depth, read from the server config (default 5). */
    public static int maxNest() {
        try {
            return Math.max(1, com.muuxa.aurelium.AureliumConfig.MAX_VAULT_NEST.get());
        } catch (IllegalStateException notLoaded) {
            return 5; // config not yet loaded (e.g. very early access): fall back to the default
        }
    }

    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final ItemStack stack;
    private final ISaveProvider saveProvider;
    private Map<AEKey, BigInteger> cachedAmounts;
    private VaultWorldData cachedWorld;
    private UUID cachedId;
    private long cachedRevision = -1L;

    VaultInventory(ItemStack stack, ISaveProvider saveProvider) {
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

    /**
     * The item no longer caches any summary: counts are read live from the vault file over the
     * network (see the preview page). This keeps the item to a single UUID tag.
     */
    public static Summary cachedSummary(ItemStack item) {
        return new Summary(BigInteger.ZERO, 0, BigInteger.ZERO, 0, 0, BigInteger.ZERO, false);
    }

    public record Summary(BigInteger total, int types, BigInteger items,
                          int itemTypes, int fluidTypes, BigInteger usedBytes, boolean known) {
        public int otherTypes() { return Math.max(0, types - itemTypes - fluidTypes); }
    }

    public static UUID existingId(ItemStack item) {
        if (item.isEmpty() || !(item.getItem() instanceof StarVaultItem)) return null;
        CompoundTag data = tag(item);
        return data.hasUUID(UUID_TAG) ? data.getUUID(UUID_TAG) : null;
    }

    public static void ensureId(ItemStack item) {
        if (!(item.getItem() instanceof StarVaultItem)) return;
        CompoundTag data = tag(item);
        if (!data.hasUUID(UUID_TAG)) {
            data.putUUID(UUID_TAG, UUID.randomUUID());
            tag(item, data);
        }
    }

    /** Force a specific vault UUID onto an item stack (used by the tree importer). */
    public static void setId(ItemStack item, UUID id) {
        if (!(item.getItem() instanceof StarVaultItem) || id == null) return;
        CompoundTag data = tag(item);
        data.putUUID(UUID_TAG, id);
        tag(item, data);
    }

    /** Public setter for the nesting marker (used by the tree importer). */
    public static void setNestPublic(ItemStack item, int nest) {
        if (item.getItem() instanceof StarVaultItem) setNest(item, nest);
    }

    /** Force this vault to seed/load its contents now (used for nested packs). */
    public void forceSeed() {
        snapshot();
    }

    /**
     * A fresh vault item stack with a default starting charge, used for nested vaults built
     * from a {@code vault:} reference (they should be usable, not empty).
     */
    public static ItemStack chargedVaultStack() {
        return com.muuxa.aurelium.registry.AureliumItems.STAR_VAULT.get().newChargedStack();
    }

    /** Applies a pack's recorded AE charge to a freshly built vault stack. */
    public static void applyPackPower(ItemStack stack, double power) {
        if (stack.getItem() instanceof com.muuxa.aurelium.storage.StarVaultItem vaultItem) {
            vaultItem.setStoredPower(stack, power);
        }
    }

    private static MinecraftServer server() { return ServerLifecycleHooks.getCurrentServer(); }

    /** The nested-vault chain length recorded on a vault item (a lone vault = 1). */
    public static int nestOf(ItemStack item) {
        CompoundTag data = tag(item);
        return data.contains(NEST_TAG) ? Math.max(1, data.getInt(NEST_TAG)) : 1;
    }

    private static void setNest(ItemStack item, int nest) {
        CompoundTag data = tag(item);
        data.putInt(NEST_TAG, Math.max(1, Math.min(maxNest(), nest)));
        tag(item, data);
    }

    /**
     * Whether this vault may store {@code key}. Ordinary items/fluids always may. Other storage
     * cells may too (e.g. a portable cell), except ones that cannot be nested. A nested vault is
     * The allowed depth is {@link #maxNest()} (from the server config).
     */
    private boolean accepts(AEKey key) {
        if (key == null) return false;
        if (key instanceof AEItemKey itemKey) {
            ItemStack inStack = itemKey.toStack();
            // Another vault: allow only if nesting it here keeps the chain within the cap.
            if (inStack.getItem() instanceof StarVaultItem) {
                int innerNest = nestOf(inStack);
                int outerNest = nestOf(this.stack);
                return Math.max(outerNest, innerNest + 1) <= maxNest();
            }
            // Infinite cells AND infinite disks are storable: they are self-contained (their
            // contents live in their own world data, never on the item) and carry no nested
            // vault, so they never deepen the chain. Always allow them.
            if (inStack.getItem() instanceof InfiniteCellItem) return true;
            if (inStack.getItem() instanceof InfiniteDiskItem) return true;
            // Any other storage cell that refuses to be nested (e.g. creative cells) is rejected,
            // mirroring AE2's BasicCellInventory.innerInsert.
            StorageCell nested = StorageCells.getCellInventory(inStack, null);
            if (nested != null && !nested.canFitInsideCell()) return false;
        }
        return true;
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

    /** Seed only on the server; invalid pack entries fail without clearing the pending marker. */
    private void seed(VaultWorldData world, UUID id, HolderLookup.Provider registries) {
        // Placeholder vault (produced by an exported recipe): build the tree's contents here.
        CompoundTag placeholderNbt = tag(stack);
        if (placeholderNbt.contains("aurelium_tree") && !world.contains(id)) {
            String treeId = placeholderNbt.getString("aurelium_tree");
            if (com.muuxa.aurelium.api.AureliumVaultTree.buildInto(treeId, id, world, registries)) {
                cachedWorld = world;
                cachedId = id;
                cachedRevision = world.revision(id);
                cachedAmounts = world.liveMap(id, registries);
                CompoundTag cleaned = tag(stack);
                cleaned.remove("aurelium_tree");
                cleaned.remove("aurelium_tree_name");
                tag(stack, cleaned);
                if (saveProvider != null) saveProvider.saveChanges();
            }
            return;
        }

        CompoundTag nbt = tag(stack);
        if (!nbt.contains(SEED_TAG) || world.contains(id)) return;
        PackSpec pack = AureliumPacks.get(nbt.getString(SEED_TAG));
        if (pack == null) return;
        Map<AEKey, BigInteger> resolved = new HashMap<>();
        int deepestNest = 1;
        for (var entry : pack.items().entrySet()) {
            String token = entry.getKey();
            // Nested vault reference: build a real vault item holding the referenced pack's contents.
            if (token.startsWith(KeyCodec.VAULT_PREFIX)) {
                String innerPackId = token.substring(KeyCodec.VAULT_PREFIX.length()).trim();
                PackSpec innerPack = AureliumPacks.get(innerPackId);
                // A missing nested pack must not discard everything else in this vault; skip that
                // one entry and keep seeding the rest so contents are never lost.
                if (innerPack == null) {
                    LOGGER.warn("AURELIUM：嵌套宝匣包 '{}' 未注册，已跳过该条目（其余内容继续装载）", innerPackId);
                    continue;
                }
                ItemStack innerStack = chargedVaultStack();
                ensureId(innerStack);
                // Restore the nested vault's own name and charge from its pack. Without this the
                // child came back with the generic display name and a flat starting charge.
                if (innerPack.title() != null && !innerPack.title().isBlank()) {
                    innerStack.set(DataComponents.CUSTOM_NAME,
                            Component.literal(innerPack.title()));
                }
                applyPackPower(innerStack, innerPack.power());
                // Mark the inner vault with the pack id so it seeds when read; then materialise it now.
                CompoundTag innerNbt = tag(innerStack);
                innerNbt.putString(SEED_TAG, innerPackId);
                tag(innerStack, innerNbt);
                VaultInventory innerInv = new VaultInventory(innerStack, null);
                innerInv.forceSeed();   // recursive; builds that level's own nested vaults too
                int innerNest = nestOf(innerStack);
                innerNest = Math.max(innerNest, 2);
                setNest(innerStack, innerNest);
                deepestNest = Math.max(deepestNest, innerNest + 1);
                AEKey innerKey = AEItemKey.of(innerStack);
                if (innerKey == null) {
                    LOGGER.warn("AURELIUM：嵌套宝匣 '{}' 无法编码为 AE 键，已跳过", innerPackId);
                    continue;
                }
                resolved.merge(innerKey, entry.getValue(), BigInteger::add);
                continue;
            }
            // Any AE2 key type (item / item-with-NBT / fluid / mod key) resolves here.
            AEKey key = KeyCodec.decode(token, registries);
            if (key == null || !accepts(key)) {
                LOGGER.warn("AURELIUM：内容包 '{}' 的条目无法解析或不被接受，已跳过：{}",
                        pack.id(), token);
                continue;
            }
            resolved.merge(key, entry.getValue(), BigInteger::add);
        }
        if (resolved.isEmpty()) return;
        world.update(id, resolved, registries);
        if (deepestNest > 1) setNest(stack, Math.min(maxNest(), deepestNest));
        cachedWorld = world;
        cachedId = id;
        cachedRevision = world.revision(id);
        cachedAmounts = resolved;
        nbt = tag(stack);
        nbt.remove(SEED_TAG);
        tag(stack, nbt);
        if (saveProvider != null) saveProvider.saveChanges();
    }

    /**
     * The live content map for this cell (never a copy). {@link VaultWorldData#credit}/
     * {@link VaultWorldData#debit} update it in place, so a cached reference stays valid across
     * transfers — that is what removes the per-operation O(types) copy from the hot path.
     */
    private Map<AEKey, BigInteger> snapshot() {
        MinecraftServer server = server();
        // World files must only be touched on the server main thread.
        if (server == null || !server.isSameThread()) return Map.of();
        UUID id = uuid(true);
        VaultWorldData world = VaultWorldData.get(server);
        HolderLookup.Provider registries = server.registryAccess();
        seed(world, id, registries);
        if (cachedAmounts == null || cachedWorld != world || !id.equals(cachedId)) {
            cachedWorld = world;
            cachedId = id;
        }
        cachedRevision = world.revision(id);
        cachedAmounts = world.liveMap(id, registries);
        return cachedAmounts;
    }

    @Override
    public CellState getStatus() { return snapshot().isEmpty() ? CellState.EMPTY : CellState.NOT_EMPTY; }

    @Override
    public double getIdleDrain() { return 0.1; }

    @Override
    public boolean canFitInsideCell() { return false; }

    @Override
    public void persist() { /* each write flushes straight to the per-UUID file */ }

    @Override
    public long insert(AEKey key, long count, Actionable action, IActionSource source) {
        if (count <= 0 || !accepts(key)) return 0L;
        MinecraftServer server = server();
        if (server == null || !server.isSameThread()) return 0L;
        if (action == Actionable.SIMULATE) return count;
        return creditExact(key, BigInteger.valueOf(count)).longValue();
    }

    @Override
    public long extract(AEKey key, long count, Actionable action, IActionSource source) {
        if (key == null || count <= 0) return 0L;
        MinecraftServer server = server();
        if (server == null || !server.isSameThread()) return 0L;
        if (action == Actionable.SIMULATE) {
            return snapshot().getOrDefault(key, BigInteger.ZERO)
                    .min(BigInteger.valueOf(count)).longValue();
        }
        return debitExact(key, BigInteger.valueOf(count)).longValue();
    }

    // --- BigInteger core, shared by the plain long path and the NeoEcoAE bridge. ---

    /** True when this cell may store the given key (guards against nested vaults). */
    public final boolean acceptsForBridge(AEKey key) { return accepts(key); }

    /** Snapshot of the live content map; empty when read off the server thread. */
    public final Map<AEKey, BigInteger> exactSnapshot() { return snapshotCopy(); }

    /**
     * A defensive copy of the live content map. Used by callers that iterate (e.g. the exact
     * amount bridges), so an in-flight transfer cannot cause a concurrent-modification error.
     */
    private Map<AEKey, BigInteger> snapshotCopy() {
        return new HashMap<>(snapshot());
    }

    /** Credit an exact BigInteger amount to one key; returns the amount accepted. */
    public final BigInteger creditExact(AEKey key, BigInteger amount) {
        if (amount == null || amount.signum() <= 0 || !accepts(key)) return BigInteger.ZERO;
        MinecraftServer server = server();
        if (server == null || !server.isSameThread()) return BigInteger.ZERO;
        UUID id = uuid(true);
        VaultWorldData world = VaultWorldData.get(server);
        HolderLookup.Provider registries = server.registryAccess();
        BigInteger accepted = world.credit(id, key, amount, registries);
        // If we just took in another vault, grow our own nesting marker so a 4th level is refused.
        bumpNestFor(key, accepted);
        cachedRevision = world.revision(id);
        return accepted;
    }

    /** After accepting a nested vault, record our chain length so the cap is enforced. */
    private void bumpNestFor(AEKey key, BigInteger accepted) {
        if (accepted == null || accepted.signum() <= 0) return;
        if (!(key instanceof AEItemKey itemKey) || !(itemKey.getItem() instanceof StarVaultItem)) return;
        int innerNest = nestOf(itemKey.toStack());
        int outerNest = Math.max(nestOf(this.stack), innerNest + 1);
        if (outerNest != nestOf(this.stack)) setNest(this.stack, outerNest);
    }

    /** Debit up to {@code requested} from one key; returns the amount actually taken. */
    protected final BigInteger debitExact(AEKey key, BigInteger requested) {
        if (key == null || requested == null || requested.signum() <= 0) return BigInteger.ZERO;
        MinecraftServer server = server();
        if (server == null || !server.isSameThread()) return BigInteger.ZERO;
        UUID id = uuid(true);
        VaultWorldData world = VaultWorldData.get(server);
        HolderLookup.Provider registries = server.registryAccess();
        BigInteger taken = world.debit(id, key, requested, registries);
        cachedRevision = world.revision(id);
        return taken;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        for (var entry : snapshot().entrySet()) {
            if (entry.getValue().signum() > 0) {
                long existing = out.get(entry.getKey());
                if (existing < Long.MAX_VALUE) {
                    long offered = entry.getValue().min(LONG_MAX).longValue();
                    if (offered >= Long.MAX_VALUE - existing) out.set(entry.getKey(), Long.MAX_VALUE);
                    else out.add(entry.getKey(), offered);
                }
            }
        }
    }

    @Override
    public Component getDescription() { return stack.getHoverName(); }
}
