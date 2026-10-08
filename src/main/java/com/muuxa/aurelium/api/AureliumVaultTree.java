package com.muuxa.aurelium.api;

import com.muuxa.aurelium.storage.KeyCodec;
import com.muuxa.aurelium.storage.VaultInventory;
import com.muuxa.aurelium.storage.VaultWorldData;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KubeJS bridge that rebuilds a <b>nested vault tree</b> from an exported script.
 *
 * <p>Each node is {@code { items: ["64x minecraft:diamond", ...], vaults: [ { amount, contents:{...} } ] }}.
 * Each vault node becomes a real vault item (with its own new UUID) whose stored contents are
 * written to world data, and which is itself placed into its parent's contents. So the exported
 * structure round-trips into a genuine vault-inside-vault arrangement.</p>
 *
 * <p><b>Timing.</b> Building needs a running server. If called during {@code startup_scripts}
 * (no server yet) the tree is recorded and built automatically once the server has started;
 * retrieve it later with {@link #get(String)}. If called while the server runs (server script,
 * player event) it is built immediately and the resulting stack is returned.</p>
 */
public final class AureliumVaultTree {
    private AureliumVaultTree() {}

    /** id → normalized tree spec (pure Java Map/List/String), for deferred building. */
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>();
    /** id → the built root vault stack (built either immediately or on server start). */
    private static final Map<String, ItemStack> BUILT = new LinkedHashMap<>();

    private record Pending(String name, Map<String, Object> tree) {}

    /**
     * Register a tree. {@code tree} is an object with keys {@code items} (array of strings) and
     * {@code vaults} (array of objects). Returns the root vault's item stack when a server is
     * available; otherwise returns {@code ItemStack.EMPTY} and builds later (see {@link #get}).
     */
    public static ItemStack register(String packId, String name, Object tree) {
        String id = (packId == null || ResourceLocation.tryParse(packId) == null)
                ? "aurelium:exported_anon" : packId;
        Map<String, Object> normalized = normalizeMap(tree);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            // startup_scripts: no server yet. Defer; built on server start.
            PENDING.put(id, new Pending(name, normalized));
            return ItemStack.EMPTY;
        }
        ItemStack root = buildNode(normalized, server.registryAccess(), 1);
        BUILT.put(id, root);
        TREE_SPECS.put(id, name == null ? id : name);
        return root;
    }

    /** The built root stack for a previously registered tree, or {@code ItemStack.EMPTY}. */
    public static ItemStack get(String packId) {
        return BUILT.getOrDefault(packId, ItemStack.EMPTY);
    }

    // ── Crafting-recipe support: a static placeholder produced by a recipe, swapped for the
    //    real (dynamically built) vault on craft. ─────────────────────────────────────────────

    /** NBT key marking a placeholder vault; its value is the tree id to build on craft. */
    public static final String PLACEHOLDER_TAG = "aurelium_tree";

    /**
     * Build a placeholder stack that a recipe may produce. It looks like a blank vault but carries
     * the tree id in {@link #PLACEHOLDER_TAG}; {@link #resolveCrafted} swaps it on craft.
     */
    public static ItemStack placeholder(String packId, String name) {
        ItemStack stack = new ItemStack(com.muuxa.aurelium.registry.AureliumItems.STAR_VAULT.get());
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString(PLACEHOLDER_TAG, packId == null ? "" : packId);
        if (name != null && !name.isBlank()) tag.putString("aurelium_tree_name", name);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(tag));
        return stack;
    }

    /** True when {@code stack} is a recipe placeholder for a tree. */
    public static boolean isPlaceholder(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains(PLACEHOLDER_TAG);
    }

    /**
     * If {@code crafted} is a tree placeholder, return the real vault (building it if needed);
     * otherwise return it unchanged. Call from a KubeJS {@code ItemEvents.crafted} handler.
     */
    public static ItemStack resolveCrafted(ItemStack crafted) {
        if (!isPlaceholder(crafted)) return crafted;
        var data = crafted.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        String id = data == null ? "" : data.copyTag().getString(PLACEHOLDER_TAG);
        ItemStack built = get(id);
        if (!built.isEmpty()) return built.copy();
        // Not built yet (e.g. server not started): build on demand now.
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        Pending pending = PENDING.get(id);
        if (server != null && pending != null) {
            try {
                ItemStack root = buildNode(pending.tree(), server.registryAccess(), 1);
                BUILT.put(id, root);
                TREE_SPECS.put(id, pending.name() == null ? id : pending.name());
                PENDING.remove(id);
                return root.copy();
            } catch (RuntimeException ignored) {
                // fall through to returning the placeholder unchanged
            }
        }
        return crafted;
    }

    /** Build any trees that were registered before the server existed. */
    public static synchronized void flushPending() {
        if (PENDING.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        for (var e : new LinkedHashMap<>(PENDING).entrySet()) {
            try {
                ItemStack root = buildNode(e.getValue().tree(), server.registryAccess(), 1);
                BUILT.put(e.getKey(), root);
                TREE_SPECS.put(e.getKey(), e.getValue().name() == null ? e.getKey() : e.getValue().name());
            } catch (RuntimeException ignored) {
                // A malformed tree must not break server start.
            }
            PENDING.remove(e.getKey());
        }
    }

    /** Just in case a data-driven reload wants the declared ids; kept tiny. */
    private static final Map<String, String> TREE_SPECS = new LinkedHashMap<>();
    public static Map<String, String> declared() { return Map.copyOf(TREE_SPECS); }

    /**
     * Build a registered tree's <b>contents</b> directly into {@code rootId} (used when a vault
     * item carries {@link #PLACEHOLDER_TAG}). Returns true on success.
     */
    public static synchronized boolean buildInto(String treeId, UUID rootId,
                                                 VaultWorldData world, HolderLookup.Provider registries) {
        Pending pending = PENDING.get(treeId);
        if (pending == null) return false;
        try {
            Map<appeng.api.stacks.AEKey, BigInteger> direct =
                    resolveContents(pending.tree(), registries, 1);
            world.update(rootId, direct, registries);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Resolve one tree node into an AEKey→amount map, building children as real vault items. */
    private static Map<appeng.api.stacks.AEKey, BigInteger> resolveContents(
            Map<String, Object> map, HolderLookup.Provider registries, int depth) {
        Map<appeng.api.stacks.AEKey, BigInteger> direct = new LinkedHashMap<>();
        Object itemsObj = map.get("items");
        if (itemsObj instanceof List<?> items) {
            for (Object raw : items) parseEntry(String.valueOf(raw), registries, direct);
        }
        Object vaultsObj = map.get("vaults");
        if (vaultsObj instanceof List<?> vaults) {
            for (Object v : vaults) {
                if (!(v instanceof Map<?, ?> vm)) continue;
                Map<String, Object> childMap = normalizeMap(vm.get("contents"));
                ItemStack child = buildNode(childMap, registries, depth + 1);
                appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(child);
                long amount = parseAmount(vm.get("amount"));
                if (key != null) direct.merge(key, BigInteger.valueOf(amount), BigInteger::add);
            }
        }
        return direct;
    }

    @SuppressWarnings("unchecked")
    private static ItemStack buildNode(Map<String, Object> map, HolderLookup.Provider registries, int depth) {
        // Resolve this node's own direct contents.
        Map<appeng.api.stacks.AEKey, BigInteger> direct = new LinkedHashMap<>();
        Object itemsObj = map.get("items");
        if (itemsObj instanceof List<?> items) {
            for (Object raw : items) {
                parseEntry(String.valueOf(raw), registries, direct);
            }
        }
        // Build child vaults first, then add them (as items) to this node's contents.
        Object vaultsObj = map.get("vaults");
        if (vaultsObj instanceof List<?> vaults) {
            for (Object v : vaults) {
                if (!(v instanceof Map<?, ?> vm)) continue;
                Map<String, Object> childMap = normalizeMap(vm.get("contents"));
                ItemStack child = buildNode(childMap, registries, depth + 1);
                VaultInventory.setNestPublic(child, depth + 1);
                appeng.api.stacks.AEItemKey key = appeng.api.stacks.AEItemKey.of(child);
                long amount = parseAmount(vm.get("amount"));
                if (key != null) direct.merge(key, BigInteger.valueOf(amount), BigInteger::add);
            }
        }
        // Materialise this node as a real vault item with a fresh UUID and its contents on disk.
        ItemStack stack = VaultInventory.chargedVaultStack();
        VaultInventory.ensureId(stack);
        VaultInventory.setNestPublic(stack, depth);
        UUID id = VaultInventory.existingId(stack);
        VaultWorldData world = VaultWorldData.get(server());
        world.update(id, direct, registries);
        return stack;
    }

    /** Normalize a possibly-Rhino object into a pure-Java Map (so it survives across phases). */
    private static Map<String, Object> normalizeMap(Object o) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), normalizeValue(e.getValue()));
            }
        }
        return out;
    }

    private static Object normalizeValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) out.put(String.valueOf(e.getKey()), normalizeValue(e.getValue()));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(normalizeValue(x));
            return out;
        }
        return v == null ? null : String.valueOf(v);
    }

    private static void parseEntry(String raw, HolderLookup.Provider registries,
                                   Map<appeng.api.stacks.AEKey, BigInteger> out) {
        String s = raw.trim();
        BigInteger amount = BigInteger.ONE;
        int x = s.indexOf('x');
        if (x > 0) {
            String head = s.substring(0, x).trim();
            if (!head.isEmpty() && head.chars().allMatch(Character::isDigit)) {
                amount = new BigInteger(head);
                s = s.substring(x + 1).trim();
            }
        }
        appeng.api.stacks.AEKey key = KeyCodec.decode(s, registries);
        if (key != null && amount.signum() > 0) out.merge(key, amount, BigInteger::add);
    }

    private static long parseAmount(Object amount) {
        if (amount == null) return 1L;
        try { return Long.parseLong(String.valueOf(amount)); }
        catch (NumberFormatException e) { return 1L; }
    }

    private static MinecraftServer server() { return ServerLifecycleHooks.getCurrentServer(); }
}