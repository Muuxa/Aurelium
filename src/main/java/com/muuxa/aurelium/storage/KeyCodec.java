package com.muuxa.aurelium.storage;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;

/**
 * Converts an {@link AEKey} to/from the compact token used in KubeJS pack entries and in
 * {@code /aurelium export} output.
 *
 * <p>Three shapes are supported, so every AE2 key type round-trips — not just items:</p>
 * <ul>
 *   <li>item: {@code minecraft:diamond} (bare, unchanged for backward compatibility)</li>
 *   <li>fluid: {@code fluid:minecraft:water}</li>
 *   <li>anything else (mod keys, item stacks with components): {@code key:<SNBT>}, produced by
 *       {@link AEKey#toTagGeneric(HolderLookup.Provider)} and read back with
 *       {@link AEKey#fromTagGeneric(HolderLookup.Provider, CompoundTag)}.</li>
 * </ul>
 *
 * <p>The ambiguous-looking bare form is safe to keep: a valid {@code namespace:path} has exactly
 * one colon, so an item can never be mistaken for {@code fluid:...}/{@code key:{...}}.</p>
 */
public final class KeyCodec {
    public static final String FLUID_PREFIX = "fluid:";
    public static final String KEY_PREFIX = "key:";
    /** Prefix for a nested-vault reference inside a pack: {@code vault:<packId>}. */
    public static final String VAULT_PREFIX = "vault:";

    /**
     * the item id of AURELIUM's pattern tools. Their payload is a gzip byte array; in SNBT a byte
     * array costs 3–4 characters per byte, which for a ten-thousand-pattern tool would balloon an
     * export line into hundreds of kilobytes. Both directions therefore convert that one field to
     * Base64 (4 characters per 3 bytes): still a single self-contained {@code key:{...}} token,
     * roughly 60% smaller, and nothing else in the pipeline needs to know.
     */
    private static final String TOOL_ITEM_ID = "aurelium:pattern_cut_tool";
    private static final String TOOL_ITEM_ID_2 = "aurelium:pattern_copy_tool";
    private static final String PAYLOAD_KEY = "aurelium_patterns_gz";
    /** The pre-compression format: a plain list of encoded patterns (read on demand). */
    private static final String LEGACY_KEY = "aurelium_patterns";
    /** Marks a Base64-converted payload inside the SNBT so decode can restore the byte array. */
    private static final String PAYLOAD_B64_KEY = "aurelium_patterns_gz_b64";

    /** The per-tool paste-mode flag (see {@code PatternToolData}). */
    private static final String REPLACE_KEY = "aurelium_pattern_replace";

    /**
     * Export-time request: whether tools written into a pack token should carry the "replace
     * same-primary-output patterns" flag. Set by {@code VaultExporter} around its encode calls
     * (which run on the server thread), so a single flag is enough — no other caller touches it.
     */
    private static volatile boolean stampToolReplace;

    /** Marks tools encoded from now on with the given paste mode (export only). */
    public static void setStampToolReplace(boolean replace) {
        stampToolReplace = replace;
    }

    private KeyCodec() {}

    /** Syntax check for a pack entry token (no registries needed). */
    public static boolean isValidToken(String token) {
        if (token == null || token.isBlank()) return false;
        if (token.startsWith(VAULT_PREFIX)) {
            // A nested vault reference: the rest is another pack id.
            return ResourceLocation.tryParse(token.substring(VAULT_PREFIX.length()).trim()) != null;
        }
        if (token.startsWith(KEY_PREFIX)) {
            try {
                TagParser.parseTag(token.substring(KEY_PREFIX.length()).trim());
                return true;
            } catch (Exception malformed) {
                return false;
            }
        }
        if (token.startsWith(FLUID_PREFIX)) {
            return ResourceLocation.tryParse(token.substring(FLUID_PREFIX.length()).trim()) != null;
        }
        return ResourceLocation.tryParse(token) != null;
    }

    /** Encode a key into a pack token; returns {@code null} if it cannot be represented. */
    public static String encode(AEKey key, HolderLookup.Provider registries) {
        if (key instanceof AEItemKey itemKey) {
            // A plain item is written as its bare id; an item that carries components (NBT)
            // MUST round-trip through SNBT, otherwise the components would be silently lost.
            if (itemKey.hasComponents()) {
                CompoundTag tag = itemKey.toTagGeneric(registries);
                packToolPayload(tag);
                return KEY_PREFIX + toSnbt(tag);
            }
            return BuiltInRegistries.ITEM.getKey(itemKey.getItem()).toString();
        }
        if (key instanceof AEFluidKey fluidKey) {
            return FLUID_PREFIX + BuiltInRegistries.FLUID.getKey(fluidKey.getFluid());
        }
        try {
            return KEY_PREFIX + toSnbt(key.toTagGeneric(registries));
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    /**
     * Serialise a tag to <b>SNBT</b> (Stringified NBT) — Minecraft's canonical textual tag
     * format, <b>not</b> JSON. Do not "fix" this into JSON: SNBT carries type hints
     * ({@code 1b}, {@code 1L}, {@code #t:"ae2:i"}) and typed arrays that JSON cannot represent,
     * so converting would lose information. Read back with {@link TagParser#parseTag(String)}.
     */
    private static String toSnbt(CompoundTag tag) {
        return tag.toString();
    }

    /** Resolve a pack token to a key using live registries; returns {@code null} on any failure. */
    public static AEKey decode(String token, HolderLookup.Provider registries) {
        if (token == null) return null;
        String trimmed = token.trim();
        if (trimmed.startsWith(KEY_PREFIX)) {
            try {
                CompoundTag tag = TagParser.parseTag(trimmed.substring(KEY_PREFIX.length()).trim());
                unpackToolPayload(tag);
                return AEKey.fromTagGeneric(registries, tag);
            } catch (Exception malformed) {
                return null;
            }
        }
        if (trimmed.startsWith(FLUID_PREFIX)) {
            ResourceLocation id = ResourceLocation.tryParse(trimmed.substring(FLUID_PREFIX.length()).trim());
            if (id == null || !BuiltInRegistries.FLUID.containsKey(id)) return null;
            return AEFluidKey.of(BuiltInRegistries.FLUID.get(id));
        }
        ResourceLocation id = ResourceLocation.tryParse(trimmed);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return null;
        return AEItemKey.of(BuiltInRegistries.ITEM.get(id));
    }

    /**
     * Converts a pattern tool's gzip byte array to Base64 in-place (see {@link #PAYLOAD_B64_KEY}).
     * Only touches the two AURELIUM tool items, so every other item — including third-party
     * items that happen to carry their own byte arrays — keeps the exact wire format.
     */
    private static void packToolPayload(CompoundTag tag) {
        if (!isToolTag(tag)) return;
        // Record the requested paste mode on the exported tool: the item token carries the tool's
        // own data verbatim, so the flag travels with the script and decides, at paste time in the
        // receiving world, whether a matching primary output is replaced or skipped.
        if (stampToolReplace) {
            findCustomData(tag).putBoolean(REPLACE_KEY, true);
        }
        CompoundTag holder = findPayloadHolder(tag, PAYLOAD_KEY, net.minecraft.nbt.Tag.TAG_BYTE_ARRAY);
        if (holder != null) {
            byte[] payload = holder.getByteArray(PAYLOAD_KEY);
            if (payload.length == 0) return;
            // Base64 costs 1.33 chars/byte vs the SNBT byte array's ~3.6 — always a clear win.
            holder.remove(PAYLOAD_KEY);
            holder.putString(PAYLOAD_B64_KEY, java.util.Base64.getEncoder().encodeToString(payload));
            return;
        }
        // Legacy tool (uncompressed list): compress it now so the export stays compact. This only
        // affects the exported copy — the original item is untouched.
        CompoundTag legacyHolder = findPayloadHolder(tag, LEGACY_KEY, net.minecraft.nbt.Tag.TAG_LIST);
        if (legacyHolder == null) return;
        net.minecraft.nbt.ListTag list = legacyHolder.getList(LEGACY_KEY, net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (list.isEmpty()) return;
        byte[] compressed = compressList(list);
        if (compressed == null || compressed.length == 0) return;
        legacyHolder.remove(LEGACY_KEY);
        legacyHolder.putString(PAYLOAD_B64_KEY, java.util.Base64.getEncoder().encodeToString(compressed));
    }

    /** Gzip-compresses a pattern ListTag the same way {@code PatternToolData} stores it. */
    private static byte[] compressList(net.minecraft.nbt.ListTag list) {
        try {
            CompoundTag wrapper = new CompoundTag();
            wrapper.put("l", list);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.DataOutputStream out = new java.io.DataOutputStream(
                    new java.util.zip.GZIPOutputStream(bytes))) {
                net.minecraft.nbt.NbtIo.write(wrapper, out);
            }
            return bytes.toByteArray();
        } catch (Exception failure) {
            return null;
        }
    }

    /**
     * Restores the gzip byte array that {@link #packToolPayload} converted, so the resulting item
     * is byte-for-byte what the tool originally carried. A malformed Base64 body is dropped here
     * (the item then arrives without a payload) rather than aborting the whole import.
     */
    private static void unpackToolPayload(CompoundTag tag) {
        if (!isToolTag(tag)) return;
        CompoundTag holder = findPayloadHolder(tag, PAYLOAD_B64_KEY, net.minecraft.nbt.Tag.TAG_STRING);
        if (holder == null) return;
        String encoded = holder.getString(PAYLOAD_B64_KEY);
        holder.remove(PAYLOAD_B64_KEY);
        try {
            byte[] payload = java.util.Base64.getDecoder().decode(encoded);
            if (payload.length > 0) {
                holder.put(PAYLOAD_KEY, new net.minecraft.nbt.ByteArrayTag(payload));
            }
        } catch (IllegalArgumentException malformed) {
            // leave the payload absent; the tool simply imports empty rather than garbage
        }
    }

    /** True when the SNBT carries one of AURELIUM's two pattern tool items. */
    private static boolean isToolTag(CompoundTag tag) {
        String id = tag.getString("id");
        return TOOL_ITEM_ID.equals(id) || TOOL_ITEM_ID_2.equals(id);
    }

    /**
     * The tag that actually holds the pattern payload, searched depth-first. The surrounding
     * layout differs between serialisation paths ({@code components} → {@code custom_data} in the
     * current format, a legacy {@code tag} object, or an add-on's own wrapper), so rather than
     * guessing the path this walks the tree for the payload key itself. It only ever searches
     * inside tags that already carry a pattern tool's id, so no other item's data is touched.
     */
    private static CompoundTag findPayloadHolder(CompoundTag tag, String key, int valueType) {
        if (tag.contains(key, valueType)) return tag;
        for (String child : tag.getAllKeys()) {
            if (tag.get(child) instanceof CompoundTag nested) {
                CompoundTag found = findPayloadHolder(nested, key, valueType);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The {@code minecraft:custom_data} compound of a serialised tool, created in place when
     * missing. Used only while stamping the export-time replace flag.
     */
    private static CompoundTag findCustomData(CompoundTag tag) {
        CompoundTag components = tag.getCompound("components");
        CompoundTag existing = components.getCompound("minecraft:custom_data");
        if (!existing.isEmpty()) return existing;
        CompoundTag custom = new CompoundTag();
        components.put("minecraft:custom_data", custom);
        tag.put("components", components);
        return custom;
    }
}
