package com.muuxa.aurelium.pattern;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The pattern stash of a pattern tool, kept <b>in the item's own NBT</b>
 * (inside the vanilla {@code minecraft:custom_data} component), <b>gzip-compressed</b>.
 *
 * <p>Deliberately no UUID indirection: the stored stacks — with every component intact — travel
 * with the item, so a tool can be put in a chest, traded, or carried across dimensions without
 * any world-side bookkeeping.</p>
 *
 * <p><b>Why compression is mandatory, not a nicety:</b> an item's NBT is shipped to the client
 * inside packets (e.g. {@code container_set_slot}) and the client refuses any single NBT that
 * exceeds 2 MiB ({@code NbtAccounter.create(2097152L)} in {@code FriendlyByteBuf#readNbt}).
 * Exceeding it throws mid-decode and drops the connection — the crash observed in practice.
 * Encoded patterns are extremely repetitive SNBT, so gzip shrinks them by roughly 10×, which both
 * raises the practical capacity well past the configured cut limit and keeps every individual
 * packet comfortably under the wire limit.</p>
 *
 * <p>Layout inside {@code custom_data}:
 * <ul>
 *   <li>{@link #KEY_COMPRESSED} — {@code byte[]}, the gzipped {@code ListTag} of item stacks
 *       (the format written from now on);</li>
 *   <li>{@link #KEY} — the legacy uncompressed {@code ListTag}, still read so tools created by
 *       an earlier build are never lost; the next write re-encodes them compressed.</li>
 * </ul>
 */
public final class PatternToolData {

    /** Legacy (uncompressed) list key — read-only for backward compatibility. */
    private static final String KEY = "aurelium_patterns";
    /** Current (gzipped byte[]) key. */
    private static final String KEY_COMPRESSED = "aurelium_patterns_gz";
    /**
     * Stored pattern count, kept next to the payload so tooltips can read it without inflating
     * the gzip data every render frame.
     */
    private static final String KEY_COUNT = "aurelium_patterns_n";

    /**
     * Per-tool behaviour flag: when {@code true}, pasting replaces an existing pattern that shares
     * a primary output with one being pasted (the old pattern is handed back to the tool/player);
     * when {@code false} (the default) patterns with a matching primary output are simply skipped.
     */
    private static final String KEY_REPLACE = "aurelium_pattern_replace";

    /**
     * Safety budget for the compressed payload. The client's per-NBT ceiling is 2 MiB; being
     * conservative leaves room for the rest of the item's components and the surrounding packet.
     * {@link #compressedSize} is checked before every cut so an oversized tool can never be built.
     */
    public static final int MAX_COMPRESSED_BYTES = 1_500_000;

    private PatternToolData() {
    }

    /** Reads the stored patterns (full item stacks, copies). Never {@code null}. */
    public static List<ItemStack> load(ItemStack tool, HolderLookup.Provider registries) {
        CompoundTag tag = tagOf(tool);
        ListTag list = readList(tag);
        List<ItemStack> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(registries, list.getCompound(i));
            if (!stack.isEmpty()) out.add(stack);
        }
        return out;
    }

    /**
     * Stored pattern count without decompressing (client-safe, no registry access). Falls back to
     * inflating legacy payloads that predate {@link #KEY_COUNT}.
     */
    public static int count(ItemStack tool) {
        CompoundTag tag = tagOf(tool);
        if (tag.contains(KEY_COUNT, Tag.TAG_INT)) return tag.getInt(KEY_COUNT);
        if (tag.contains(KEY_COMPRESSED, Tag.TAG_BYTE_ARRAY)) {
            return readList(tag).size();
        }
        return tag.getList(KEY, Tag.TAG_COMPOUND).size();
    }

    /**
     * Writes the patterns back, gzip-compressed. An empty list removes the component entirely.
     * If the compressed payload does not fit {@link #MAX_COMPRESSED_BYTES}, the write is refused
     * (returns {@code false}) and the previous contents stay untouched.
     */
    public static boolean save(ItemStack tool, List<ItemStack> patterns, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ItemStack stack : patterns) {
            if (stack == null || stack.isEmpty()) continue;
            list.add(stack.save(registries, new CompoundTag()));
        }
        CompoundTag tag = tagOf(tool);
        if (list.isEmpty()) {
            tag.remove(KEY);
            tag.remove(KEY_COMPRESSED);
            tag.remove(KEY_COUNT);
        } else {
            byte[] compressed = compress(list);
            if (compressed == null || compressed.length > MAX_COMPRESSED_BYTES) return false;
            tag.remove(KEY); // drop any legacy uncompressed copy
            tag.put(KEY_COMPRESSED, new ByteArrayTag(compressed));
            tag.putInt(KEY_COUNT, list.size());
        }
        applyTag(tool, tag);
        return true;
    }

    /**
     * The size the given batch <b>would</b> occupy once stored compressed, or {@code -1} when it
     * cannot be encoded. Used to pre-check a cut so the tool never grows past the budget.
     */
    public static int compressedSize(List<ItemStack> patterns, HolderLookup.Provider registries) {
        byte[] compressed = compress(encode(patterns, registries));
        return compressed == null ? -1 : compressed.length;
    }

    /**
     * The length of the longest prefix of {@code candidate} that still fits
     * {@link #MAX_COMPRESSED_BYTES} once compressed. {@code atLeast} is the (already stored, known
     * to fit) head length; the result is never smaller than that, so previously assembled data is
     * preserved.
     *
     * <p>The batch is serialised <b>once</b>; the binary search then only re-compresses truncated
     * prefixes of that prepared {@link ListTag}. (Serialising item stacks is the expensive part —
     * doing it per probe turned a thousand-pattern cut into thousands of stack encodings on the
     * server thread.)</p>
     *
     * @deprecated superseded by {@link #prepareCut}, which also hands the compressed payload to
     *         the caller so it is never computed twice.
     */
    @Deprecated
    public static int fittingPrefix(List<ItemStack> candidate, int atLeast,
                                    HolderLookup.Provider registries) {
        ListTag encoded = encode(candidate, registries);
        int low = Math.min(atLeast, candidate.size());
        int high = candidate.size();
        if (high > low) {
            int full = compressedLength(encoded);
            if (full >= 0 && full <= MAX_COMPRESSED_BYTES) return high;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                int size = compressedLength(truncate(encoded, mid));
                if (size >= 0 && size <= MAX_COMPRESSED_BYTES) low = mid;
                else high = mid - 1;
            }
        }
        // Defensive walk-back: gzip output is not strictly monotonic across block boundaries.
        while (low > atLeast) {
            int size = compressedLength(truncate(encoded, low));
            if (size >= 0 && size <= MAX_COMPRESSED_BYTES) break;
            low--;
        }
        return low;
    }

    /**
     * A cut plan: the first {@code count} stacks of the candidate batch, already serialised and
     * gzip-compressed. {@link #savePrepared} writes this payload verbatim, so a large cut pays for
     * <b>one</b> encoding pass and <b>one</b> compression pass in total — the previous flow
     * re-encoded and re-compressed the whole batch a second time when saving, which showed up as a
     * one or two frame stutter on a full super-assembler matrix.
     */
    public record Prepared(int count, byte[] compressed) {
    }

    /**
     * Plans the largest prefix of {@code candidate} that fits {@link #MAX_COMPRESSED_BYTES} once
     * serialised and compressed, never dropping below the {@code atLeast} entries already on the
     * tool.
     *
     * <p>Fast path (the batch fits): one encode + one compress. Only a genuinely oversized batch
     * pays for the binary-search probes, and each probe re-compresses a truncated prefix of the
     * already-encoded tag — stacks are never serialised twice.</p>
     */
    public static Prepared prepareCut(List<ItemStack> candidate, int atLeast,
                                      HolderLookup.Provider registries) {
        List<ItemStack> clean = new ArrayList<>(candidate.size());
        for (ItemStack stack : candidate) {
            if (stack != null && !stack.isEmpty()) clean.add(stack);
        }
        ListTag encoded = encode(clean, registries);
        int cap = clean.size();
        int floor = Math.min(Math.max(atLeast, 0), cap);

        byte[] full = compress(encoded);
        if (full != null && full.length <= MAX_COMPRESSED_BYTES) {
            return new Prepared(cap, full);
        }
        byte[] best = null;
        int bestCount = -1;
        int lo = floor;
        int hi = cap;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            byte[] probe = compress(truncate(encoded, mid));
            if (probe != null && probe.length <= MAX_COMPRESSED_BYTES) {
                lo = mid;
                if (mid > bestCount) {
                    best = probe; // every accepted candidate was verified by this exact compression
                    bestCount = mid;
                }
            } else {
                hi = mid - 1;
            }
        }
        if (best == null) {
            byte[] baseline = compress(truncate(encoded, floor));
            boolean fits = baseline != null && baseline.length <= MAX_COMPRESSED_BYTES;
            return new Prepared(floor, fits ? baseline : null);
        }
        return new Prepared(bestCount, best);
    }

    /**
     * Writes a plan produced by {@link #prepareCut} straight onto the item — no re-encoding, no
     * re-compression. Returns {@code false} (leaving the item untouched) when the plan carries no
     * payload, which happens only when even the data already on the tool cannot be re-encoded.
     */
    public static boolean savePrepared(ItemStack tool, Prepared prepared) {
        if (prepared == null || prepared.compressed() == null) return false;
        if (prepared.compressed().length > MAX_COMPRESSED_BYTES) return false;
        CompoundTag tag = tagOf(tool);
        tag.remove(KEY);
        tag.put(KEY_COMPRESSED, new ByteArrayTag(prepared.compressed()));
        tag.putInt(KEY_COUNT, prepared.count());
        applyTag(tool, tag);
        return true;
    }

    /** Serialises the given stacks into a fresh {@link ListTag} (empty entries skipped). */
    public static ListTag encode(List<ItemStack> patterns, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ItemStack stack : patterns) {
            if (stack == null || stack.isEmpty()) continue;
            list.add(stack.save(registries, new CompoundTag()));
        }
        return list;
    }

    // --- tag-based fast paths --------------------------------------------------------------
    //
    // A tool holding thousands of patterns is the normal case, and every interaction used to
    // round-trip the WHOLE payload through item deserialisation (ItemStack.parseOptional) and
    // re-serialisation (stack.save), twice per click. The helpers below let the tool operate on
    // the stored ListTag directly: entries that stay in the tool are never parsed, never
    // re-encoded and never re-compressed — only genuinely new stacks are encoded, and the payload
    // is compressed once.

    /** The stored payload as a tag list — decompression only, no per-entry item parsing. */
    public static ListTag storedTags(ItemStack tool) {
        return readList(tagOf(tool));
    }

    /**
     * Writes a tag list back, compressed and budget-checked. An empty list clears the payload.
     *
     * @return true when the payload was stored (or cleared)
     */
    public static boolean saveTags(ItemStack tool, ListTag tags) {
        if (tags == null || tags.isEmpty()) {
            CompoundTag tag = tagOf(tool);
            tag.remove(KEY);
            tag.remove(KEY_COMPRESSED);
            tag.remove(KEY_COUNT);
            applyTag(tool, tag);
            return true;
        }
        byte[] compressed = compress(tags);
        if (compressed == null || compressed.length > MAX_COMPRESSED_BYTES) return false;
        writePayload(tool, tags.size(), compressed);
        return true;
    }

    /**
     * Appends freshly gathered stacks to the payload <b>without</b> decoding or re-encoding the
     * entries already stored. Only the additions are serialised; the combined tag is compressed
     * once, with the same binary-search budget fallback as {@link #prepareCut}.
     *
     * @return how many additions were accepted, or {@code -1} when even the currently stored
     *         payload could not be written back (the caller then keeps the tool untouched)
     */
    public static int appendToStored(ItemStack tool, List<ItemStack> additions,
                                     HolderLookup.Provider registries) {
        ListTag existing = storedTags(tool);
        ListTag added = encode(additions, registries);
        if (added.isEmpty()) return 0;

        ListTag combined = new ListTag();
        for (int i = 0; i < existing.size(); i++) combined.add(existing.get(i));
        for (int i = 0; i < added.size(); i++) combined.add(added.get(i));

        byte[] full = compress(combined);
        if (full != null && full.length <= MAX_COMPRESSED_BYTES) {
            writePayload(tool, combined.size(), full);
            return added.size();
        }
        // Oversized: keep the longest prefix that fits, never dropping below what was already
        // stored (unknown additions are the ones trimmed back).
        int floor = existing.size();
        int lo = floor;
        int hi = combined.size();
        byte[] bestBytes = null;
        int bestCount = -1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            byte[] probe = compress(firstN(combined, mid));
            if (probe != null && probe.length <= MAX_COMPRESSED_BYTES) {
                lo = mid;
                if (mid > bestCount) {
                    bestBytes = probe;
                    bestCount = mid;
                }
            } else {
                hi = mid - 1;
            }
        }
        if (bestCount < floor) {
            // The stored payload alone already exceeds the budget (legacy data): verify a plain
            // rewrite so callers can tell "nothing changed" from "write failed".
            byte[] baseline = compress(firstN(combined, floor));
            if (baseline == null || baseline.length > MAX_COMPRESSED_BYTES) return -1;
            writePayload(tool, floor, baseline);
            return 0;
        }
        writePayload(tool, bestCount, bestBytes);
        return bestCount - floor;
    }

    /** The first {@code n} entries as a new list (entries shared, not copied). */
    private static ListTag firstN(ListTag source, int n) {
        if (n >= source.size()) return source;
        ListTag out = new ListTag();
        for (int i = 0; i < n; i++) out.add(source.get(i));
        return out;
    }

    private static void writePayload(ItemStack tool, int count, byte[] compressed) {
        CompoundTag tag = tagOf(tool);
        tag.remove(KEY);
        tag.put(KEY_COMPRESSED, new ByteArrayTag(compressed));
        tag.putInt(KEY_COUNT, count);
        applyTag(tool, tag);
    }

    /** A view of the first {@code size} entries; the underlying tags are shared, not copied. */
    private static ListTag truncate(ListTag encoded, int size) {
        if (size >= encoded.size()) return encoded;
        ListTag out = new ListTag();
        for (int i = 0; i < size; i++) out.add(encoded.get(i));
        return out;
    }

    private static int compressedLength(ListTag encoded) {
        byte[] compressed = compress(encoded);
        return compressed == null ? -1 : compressed.length;
    }

    /** Whether the stack carries any stored patterns (either format). */
    public static boolean hasStored(ItemStack tool) {
        CompoundTag tag = tagOf(tool);
        return tag.contains(KEY_COMPRESSED, Tag.TAG_BYTE_ARRAY) || tag.contains(KEY, Tag.TAG_LIST);
    }

    /** Whether the stack still uses the legacy uncompressed format. */
    public static boolean isLegacyFormat(ItemStack tool) {
        CompoundTag tag = tagOf(tool);
        return !tag.contains(KEY_COMPRESSED, Tag.TAG_BYTE_ARRAY) && tag.contains(KEY, Tag.TAG_LIST);
    }

    /**
     * Rewrites the stored batch into the compressed format, trimming it from the tail when even
     * the compressed payload would exceed {@link #MAX_COMPRESSED_BYTES}.
     *
     * <p>Called when a player's data is loaded: an oversized (legacy) tool would otherwise crash
     * the client the instant the inventory is synced. Trimming happens only for batches that came
     * from an older build; data created from now on is checked before every cut and never grows
     * past the budget in the first place.</p>
     *
     * @return true when the item was rewritten (migrated or trimmed)
     */
    public static boolean shrinkToBudget(ItemStack tool, HolderLookup.Provider registries) {
        if (!hasStored(tool)) return false;
        List<ItemStack> stored = load(tool, registries);
        if (stored.isEmpty()) {
            return isLegacyFormat(tool) && save(tool, stored, registries);
        }
        int size = compressedSize(stored, registries);
        if (size >= 0 && size <= MAX_COMPRESSED_BYTES) {
            // Fits compressed: a legacy tool is migrated, a current one is already fine.
            if (isLegacyFormat(tool)) return save(tool, stored, registries);
            return false;
        }
        // Too large even compressed: keep the head of the batch (the part the player gathered
        // first) and log what had to be dropped, so nothing vanishes silently.
        int low = 0;
        int high = stored.size();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            int candidate = compressedSize(stored.subList(0, mid), registries);
            if (candidate >= 0 && candidate <= MAX_COMPRESSED_BYTES) low = mid;
            else high = mid - 1;
        }
        List<ItemStack> kept = new ArrayList<>(stored.subList(0, low));
        List<ItemStack> dropped = stored.subList(low, stored.size());
        LOGGER.warn("AURELIUM：样板工具的存量超出安全范围，已保留 {} 个；"
                        + "其余 {} 个因超过客户端 2 MiB 上限被移除（请尽快贴出并分批剪切）。",
                kept.size(), dropped.size());
        save(tool, kept, registries);
        return true;
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Aurelium/PatternTool");

    // --- export / import bridge ------------------------------------------------------------

    /**
     * The compressed payload as Base64 text, for the {@code /aurelium export} pipeline.
     *
     * <p>Why not let the generic {@code key:{...}} encoder handle it: SNBT writes a byte array as
     * {@code [B;31,-117,8,...]}, roughly <b>3–4 characters per byte</b>. A tool holding ten
     * thousand patterns carries tens of kilobytes of gzip data, which would balloon into hundreds
     * of kilobytes inside a single KubeJS string literal — beyond what a sane script file (and the
     * command output line) should carry. Base64 costs 4 characters per 3 bytes and stays on one
     * line, and {@link #fromBase64Payload} restores the exact bytes.</p>
     *
     * @return Base64 of the stored payload, or {@code null} when the tool holds no patterns
     */
    public static String toBase64Payload(ItemStack tool) {
        CompoundTag tag = tagOf(tool);
        byte[] payload = tag.contains(KEY_COMPRESSED, Tag.TAG_BYTE_ARRAY)
                ? tag.getByteArray(KEY_COMPRESSED) : null;
        if (payload == null || payload.length == 0) {
            // Legacy (uncompressed) tool: compress on the fly so the export is still complete.
            ListTag legacy = tag.getList(KEY, Tag.TAG_COMPOUND);
            if (legacy.isEmpty()) return null;
            payload = compress(legacy);
            if (payload == null) return null;
        }
        return java.util.Base64.getEncoder().encodeToString(payload);
    }

    /**
     * Writes a Base64 payload produced by {@link #toBase64Payload} back onto a freshly created
     * tool (the import side of the export pipeline). Rejects anything that is not a decodable gzip
     * stream, so a corrupted script cannot install garbage that would break the tooltip later.
     *
     * @return true when the payload was accepted and stored
     */
    public static boolean applyBase64Payload(ItemStack tool, String base64) {
        CompoundTag payload = payloadTag(base64);
        if (payload == null) return false;
        CompoundTag tag = tagOf(tool);
        tag.remove(KEY);
        tag.put(KEY_COMPRESSED, payload.get(KEY_COMPRESSED));
        tag.putInt(KEY_COUNT, payload.getInt(KEY_COUNT));
        applyTag(tool, tag);
        return true;
    }

    /**
     * Builds the custom-data payload for a tool from the Base64 text the exporter writes, or
     * {@code null} when the text is not a decodable gzip NBT stream (so a corrupt script cannot
     * install garbage that would break the tooltip later). Used by the KubeJS tool builder and by
     * {@link #applyBase64Payload}.
     */
    public static CompoundTag payloadTag(String base64) {
        if (base64 == null || base64.isBlank()) return null;
        byte[] payload;
        try {
            payload = java.util.Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (payload.length == 0 || payload.length > MAX_COMPRESSED_BYTES) return null;
        ListTag list = decompress(payload);
        if (list == null) return null;
        CompoundTag tag = new CompoundTag();
        tag.put(KEY_COMPRESSED, new ByteArrayTag(payload));
        tag.putInt(KEY_COUNT, list.size());
        return tag;
    }

    /** Records the replace-on-paste flag into a data tag (used by the KubeJS tool builder). */
    public static void putReplaceMode(CompoundTag tag, boolean replace) {
        if (replace) {
            tag.putBoolean(KEY_REPLACE, true);
        } else {
            tag.remove(KEY_REPLACE);
        }
    }

    /** The registry id of the omniversal-style marker used for export tokens of both tools. */
    public static final String EXPORT_MARKER = "aurelium:pattern_tool";

    /** Whether this tool replaces same-primary-output patterns on paste. Default {@code false}. */
    public static boolean isReplaceMode(ItemStack tool) {
        return tagOf(tool).getBoolean(KEY_REPLACE);
    }

    /** Sets the replace-on-paste flag (persisted in the tool's own data). */
    public static void setReplaceMode(ItemStack tool, boolean replace) {
        CompoundTag tag = tagOf(tool);
        if (replace) {
            tag.putBoolean(KEY_REPLACE, true);
        } else {
            tag.remove(KEY_REPLACE);
        }
        applyTag(tool, tag);
    }

    // --- internals -------------------------------------------------------------------------

    private static ListTag readList(CompoundTag tag) {
        byte[] compressed = tag.contains(KEY_COMPRESSED, Tag.TAG_BYTE_ARRAY)
                ? tag.getByteArray(KEY_COMPRESSED) : null;
        if (compressed != null && compressed.length > 0) {
            ListTag decompressed = decompress(compressed);
            if (decompressed != null) return decompressed;
            // Corrupt payload: fall back to the legacy key (if any) instead of losing everything.
        }
        return tag.getList(KEY, Tag.TAG_COMPOUND);
    }

    private static byte[] compress(ListTag list) {
        try {
            CompoundTag wrapper = new CompoundTag();
            wrapper.put("l", list);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                NbtIo.write(wrapper, out);
            }
            return bytes.toByteArray();
        } catch (Exception failure) {
            return null;
        }
    }

    private static ListTag decompress(byte[] compressed) {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(compressed)))) {
            CompoundTag wrapper = NbtIo.read(in, NbtAccounter.unlimitedHeap());
            Tag list = wrapper.get("l");
            return list instanceof ListTag lt ? lt : null;
        } catch (Exception failure) {
            return null;
        }
    }

    private static CompoundTag tagOf(ItemStack tool) {
        CustomData data = tool.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void applyTag(ItemStack tool, CompoundTag tag) {
        if (tag.isEmpty()) {
            tool.remove(DataComponents.CUSTOM_DATA);
        } else {
            tool.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }
}
