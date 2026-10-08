package com.muuxa.aurelium.storage;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-cell persistence: <b>one NBT file per vault</b>, so a single disk can always be found and
 * recovered by hand.
 *
 * <p>Files live in {@code <world>/data/Aurelium/} and are named
 * {@code vault_cells_<full-uuid>.nbt}. The vault item itself only carries this UUID, so the file
 * can be located from the item alone.</p>
 *
 * <p>The map is a write-through cache: reads load lazily from disk, writes flush immediately.
 * That keeps a freshly written disk on disk even if the server stops abruptly.</p>
 */
public final class VaultWorldData {
    /** Folder under the world root; the {@code data/} prefix mirrors vanilla data storage. */
    private static final String DIR = "data/Aurelium";
    private static final String PREFIX = "vault_cells_";
    private static final String SUFFIX = ".nbt";

    private static final Map<MinecraftServer, VaultWorldData> INSTANCES = new ConcurrentHashMap<>();

    private final Path dir;
    private final Map<UUID, Map<AEKey, BigInteger>> content = new HashMap<>();
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Map<UUID, Boolean> loaded = new HashMap<>();
    private final Map<UUID, List<Map.Entry<AEKey, BigInteger>>> sortedCache = new HashMap<>();
    private final Map<UUID, PageStats> statsCache = new HashMap<>();
    /** Cells changed since the last flush; only these get written on the next save. */
    private final java.util.Set<UUID> dirty = new java.util.HashSet<>();

    private record PageStats(BigInteger usedBytes, int itemTypes, int fluidTypes) {}

    private VaultWorldData(Path dir) {
        this.dir = dir;
    }

    /** The manager for this server; one instance per server process. */
    public static VaultWorldData get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server,
                s -> new VaultWorldData(s.getWorldPath(LevelResource.ROOT).resolve(DIR)));
    }

    /** Directory that holds every vault cell file. */
    public static Path cellDirectory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DIR);
    }

    /** The backing file for one vault UUID. */
    public Path fileFor(UUID uuid) {
        return dir.resolve(PREFIX + uuid + SUFFIX);
    }

    private void ensureLoaded(UUID uuid, HolderLookup.Provider registries) {
        if (loaded.containsKey(uuid)) return;
        loaded.put(uuid, Boolean.TRUE);
        Path file = fileFor(uuid);
        if (!Files.isRegularFile(file)) return;
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Map<AEKey, BigInteger> stacks = new HashMap<>();
            ListTag records = root.getList("stacks", Tag.TAG_COMPOUND);
            for (int j = 0; j < records.size(); j++) {
                CompoundTag entry = records.getCompound(j);
                try {
                    AEKey key = AEKey.fromTagGeneric(registries, entry.getCompound("key"));
                    byte[] bytes = entry.getByteArray("quantity");
                    BigInteger count = bytes.length == 0 ? BigInteger.ZERO : new BigInteger(bytes);
                    if (key != null && count.signum() > 0) stacks.merge(key, count, BigInteger::add);
                } catch (RuntimeException ignored) { /* ignore a corrupt record */ }
            }
            content.put(uuid, stacks);
        } catch (IOException | RuntimeException loadFailure) {
            // A missing/unreadable file leaves the cell empty rather than crashing the server.
        }
    }

    private void write(UUID uuid, HolderLookup.Provider registries) {
        Map<AEKey, BigInteger> stacks = content.getOrDefault(uuid, Map.of());
        // 空宝匣不该留下数据文件：与其写一个空 NBT，不如把文件删掉。
        // 这样「刚从 JEI 拿出来 / 被取空的宝匣」就不会留下孤儿 vault_cells_<uuid>.nbt。
        if (stacks.isEmpty()) {
            deleteFile(uuid);
            content.remove(uuid);
            loaded.remove(uuid);
            revisions.remove(uuid);
            return;
        }
        CompoundTag root = new CompoundTag();
        root.putUUID("uuid", uuid);
        ListTag records = new ListTag();
        for (var stored : stacks.entrySet()) {
            if (stored.getValue() == null || stored.getValue().signum() <= 0) continue;
            CompoundTag record = new CompoundTag();
            record.put("key", stored.getKey().toTagGeneric(registries));
            record.putByteArray("quantity", stored.getValue().toByteArray());
            records.add(record);
        }
        root.put("stacks", records);
        try {
            Files.createDirectories(dir);
            NbtIo.writeCompressed(root, fileFor(uuid));
        } catch (IOException writeFailure) {
            // Persistence is best-effort; the in-memory map still serves the running session.
        }
    }

    public boolean contains(UUID uuid) {
        return content.containsKey(uuid) || Files.isRegularFile(fileFor(uuid));
    }

    /**
     * Delete a cell's backing file (and forget every cached view of it). Called when a cell
     * becomes empty, so an emptied / never-used vault leaves no orphan {@code .nbt} behind.
     * Best-effort: a failure to delete is non-fatal, the in-memory state is cleared regardless.
     */
    public void deleteFile(UUID uuid) {
        try {
            Files.deleteIfExists(fileFor(uuid));
        } catch (IOException deleteFailure) {
            // Persistence is best-effort; ignore and keep going.
        }
    }

    /**
     * Forgets a cell entirely: deletes its file and drops every cached entry. Use when a vault
     * is destroyed / no longer referenced (e.g. a crafting recipe consumed it).
     *
     * @return {@code true} when a file actually existed and was removed
     */
    public boolean destroy(UUID uuid) {
        boolean existed = Files.isRegularFile(fileFor(uuid));
        deleteFile(uuid);
        content.remove(uuid);
        loaded.remove(uuid);
        revisions.remove(uuid);
        sortedCache.remove(uuid);
        statsCache.remove(uuid);
        dirty.remove(uuid);
        return existed;
    }

    public long revision(UUID uuid) { return revisions.getOrDefault(uuid, 0L); }

    /** Bounded, sorted page. Send only these entries over the wire. */
    public Page page(UUID uuid, int offset, int limit, HolderLookup.Provider registries) {
        ensureLoaded(uuid, registries);
        List<Map.Entry<AEKey, BigInteger>> sorted = sortedCache.get(uuid);
        if (sorted == null) {
            Map<AEKey, BigInteger> stacks = content.get(uuid);
            if (stacks == null || stacks.isEmpty())
                return new Page(0, List.of(), BigInteger.ZERO, 0, 0);
            sorted = new ArrayList<>(stacks.size());
            for (var e : stacks.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue().signum() > 0) {
                    sorted.add(Map.entry(e.getKey(), e.getValue()));
                }
            }
            BigInteger used = BigInteger.ZERO;
            int items = 0;
            int fluids = 0;
            for (var e : sorted) {
                used = used.add(VaultBytes.forKey(e.getValue(), e.getKey().getAmountPerByte()));
                if (e.getKey() instanceof AEItemKey) items++;
                else if (e.getKey() instanceof AEFluidKey) fluids++;
            }
            statsCache.put(uuid, new PageStats(used, items, fluids));
            sorted.sort((a, b) -> {
                int quantity = b.getValue().compareTo(a.getValue());
                if (quantity != 0) return quantity;
                int type = a.getKey().getType().getId().toString()
                        .compareTo(b.getKey().getType().getId().toString());
                return type != 0 ? type : a.getKey().toString().compareTo(b.getKey().toString());
            });
            sortedCache.put(uuid, sorted);
        }
        int from = Math.min(Math.max(0, offset), sorted.size());
        int to = Math.min(sorted.size(), from + Math.min(Math.max(0, limit), 12));
        PageStats stats = statsCache.getOrDefault(uuid, new PageStats(BigInteger.ZERO, 0, 0));
        return new Page(sorted.size(), List.copyOf(sorted.subList(from, to)),
                stats.usedBytes(), stats.itemTypes(), stats.fluidTypes());
    }

    public record Page(int total, List<Map.Entry<AEKey, BigInteger>> entries,
                       BigInteger usedBytes, int itemTypes, int fluidTypes) {}

    public Map<AEKey, BigInteger> snapshot(UUID uuid, HolderLookup.Provider registries) {
        ensureLoaded(uuid, registries);
        return new HashMap<>(content.getOrDefault(uuid, Map.of()));
    }

    public void update(UUID uuid, Map<AEKey, BigInteger> stacks, HolderLookup.Provider registries) {
        content.put(uuid, new HashMap<>(stacks));
        sortedCache.remove(uuid);
        statsCache.remove(uuid);
        revisions.merge(uuid, 1L, Long::sum);
        // A one-off seed is rare; write it immediately so the file exists right away.
        write(uuid, registries);
    }

    /**
     * Change one entry only. This is the hot path used by every AE2 insert/extract, so it
     * updates memory and marks the cell dirty instead of rewriting the whole NBT file on every
     * call. Dirty cells are flushed on the world save (see {@link #flush}).
     */
    public void putAmount(UUID uuid, AEKey key, BigInteger amount, HolderLookup.Provider registries) {
        ensureLoaded(uuid, registries);
        Map<AEKey, BigInteger> stacks = content.computeIfAbsent(uuid, ignored -> new HashMap<>());
        if (amount.signum() <= 0) stacks.remove(key);
        else stacks.put(key, amount);
        sortedCache.remove(uuid);
        statsCache.remove(uuid);
        revisions.merge(uuid, 1L, Long::sum);
        dirty.add(uuid);
    }

    /**
     * In-place credit of one key, returning the amount accepted. Unlike routing through
     * {@link #snapshot} this never copies the whole per-cell map, so it is safe on the hot
     * insert path.
     */
    public BigInteger credit(UUID uuid, AEKey key, BigInteger amount, HolderLookup.Provider registries) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        Map<AEKey, BigInteger> stacks = mutable(uuid, registries);
        BigInteger previous = stacks.getOrDefault(key, BigInteger.ZERO);
        stacks.put(key, previous.add(amount));
        markDirty(uuid);
        return amount;
    }

    /**
     * In-place debit of up to {@code requested} from one key, returning the amount actually
     * taken. No map copy; safe on the hot extract path.
     */
    public BigInteger debit(UUID uuid, AEKey key, BigInteger requested, HolderLookup.Provider registries) {
        if (requested == null || requested.signum() <= 0) return BigInteger.ZERO;
        Map<AEKey, BigInteger> stacks = mutable(uuid, registries);
        BigInteger existing = stacks.getOrDefault(key, BigInteger.ZERO);
        BigInteger taken = existing.min(requested);
        if (taken.signum() == 0) return BigInteger.ZERO;
        BigInteger remaining = existing.subtract(taken);
        if (remaining.signum() <= 0) stacks.remove(key);
        else stacks.put(key, remaining);
        markDirty(uuid);
        return taken;
    }

    /** Live, mutable map for a cell (loads from disk on first touch). */
    private Map<AEKey, BigInteger> mutable(UUID uuid, HolderLookup.Provider registries) {
        ensureLoaded(uuid, registries);
        return content.computeIfAbsent(uuid, ignored -> new HashMap<>());
    }

    /**
     * The live per-cell map, updated in place by {@link #credit}/{@link #debit}. Callers must
     * treat it as read-only; it is exposed so a mounted cell can cache a reference and avoid
     * copying the whole map on every read. Returns an empty map when there is no cell.
     */
    public Map<AEKey, BigInteger> liveMap(UUID uuid, HolderLookup.Provider registries) {
        ensureLoaded(uuid, registries);
        return content.getOrDefault(uuid, Map.of());
    }

    private void markDirty(UUID uuid) {
        sortedCache.remove(uuid);
        statsCache.remove(uuid);
        revisions.merge(uuid, 1L, Long::sum);
        dirty.add(uuid);
    }

    /** Write every cell changed since the last flush; safe to call often (no-op when clean). */
    public void flush(HolderLookup.Provider registries) {
        if (dirty.isEmpty()) return;
        for (UUID uuid : new ArrayList<>(dirty)) {
            write(uuid, registries);
        }
        dirty.clear();
    }

    /** Write every loaded cell, regardless of dirty state (used on server stop). */
    public void flushAll(HolderLookup.Provider registries) {
        for (UUID uuid : new ArrayList<>(content.keySet())) {
            write(uuid, registries);
        }
        dirty.clear();
    }

    /** True when any cell has unwritten changes. */
    public boolean isDirty() { return !dirty.isEmpty(); }
}