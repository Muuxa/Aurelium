package com.muuxa.aurelium.storage;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-disk persistence: one NBT file per infinite disk UUID, mirroring the vault's design.
 *
 * <p>Files live in {@code <world>/data/Aurelium/} as {@code disk_cells_<uuid>.nbt}, so the item
 * itself only ever carries a UUID (plus the nest marker) — never any stored amounts. Amounts are
 * {@link BigInteger}, so a single key can far exceed {@code long}.</p>
 */
public final class InfiniteDiskWorldData {

    private static final String DIR = "data/Aurelium";
    private static final String PREFIX = "disk_cells_";
    private static final String SUFFIX = ".nbt";

    private static final Map<MinecraftServer, InfiniteDiskWorldData> INSTANCES = new ConcurrentHashMap<>();

    private final Path dir;
    private final Map<UUID, Map<AEKey, BigInteger>> content = new HashMap<>();
    private final Map<UUID, Long> revisions = new HashMap<>();
    private final Map<UUID, Boolean> loaded = new HashMap<>();
    /**
     * Disks changed since the last {@link #flushDirty} call. Credit/debit only mark the disk dirty
     * and update the in-memory map; the actual (compressed) NBT write is deferred to a periodic
     * flush so a bulk transfer of hundreds of keys per tick no longer pays a disk write per key
     * per tick. A disk pending a write is also flushed on server stop (see {@link #flushAll}).
     */
    private final Set<UUID> dirty = new HashSet<>();

    private InfiniteDiskWorldData(Path dir) {
        this.dir = dir;
    }

    public static InfiniteDiskWorldData get(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server,
                s -> new InfiniteDiskWorldData(s.getWorldPath(LevelResource.ROOT).resolve(DIR)));
    }

    public Path fileFor(UUID uuid) {
        return dir.resolve(PREFIX + uuid + SUFFIX);
    }

    public boolean contains(UUID uuid) {
        return content.containsKey(uuid) || Files.isRegularFile(fileFor(uuid));
    }

    public long revision(UUID uuid) {
        return revisions.getOrDefault(uuid, 0L);
    }

    private void ensureLoaded(UUID uuid, HolderLookup.Provider reg) {
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
                    AEKey key = AEKey.fromTagGeneric(reg, entry.getCompound("key"));
                    byte[] bytes = entry.getByteArray("quantity");
                    BigInteger count = bytes.length == 0 ? BigInteger.ZERO : new BigInteger(bytes);
                    if (key != null && count.signum() > 0) stacks.merge(key, count, BigInteger::add);
                } catch (RuntimeException ignored) {
                }
            }
            content.put(uuid, stacks);
        } catch (IOException | RuntimeException ignored) {
            // unreadable/missing file leaves the disk empty
        }
    }

    private void write(UUID uuid, HolderLookup.Provider reg) {
        Map<AEKey, BigInteger> stacks = content.getOrDefault(uuid, Map.of());
        CompoundTag root = new CompoundTag();
        root.putUUID("uuid", uuid);
        ListTag records = new ListTag();
        for (var e : stacks.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() <= 0) continue;
            CompoundTag record = new CompoundTag();
            record.put("key", e.getKey().toTagGeneric(reg));
            record.putByteArray("quantity", e.getValue().toByteArray());
            records.add(record);
        }
        root.put("stacks", records);
        try {
            Files.createDirectories(dir);
            NbtIo.writeCompressed(root, fileFor(uuid));
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private Map<AEKey, BigInteger> mutable(UUID uuid, HolderLookup.Provider reg) {
        ensureLoaded(uuid, reg);
        return content.computeIfAbsent(uuid, ignored -> new HashMap<>());
    }

    /** Live per-key map (read-only for callers). */
    public Map<AEKey, BigInteger> liveMap(UUID uuid, HolderLookup.Provider reg) {
        ensureLoaded(uuid, reg);
        return content.getOrDefault(uuid, Map.of());
    }

    /** Credit one key; returns the amount accepted. */
    public BigInteger credit(UUID uuid, AEKey key, BigInteger amount, HolderLookup.Provider reg) {
        if (amount == null || amount.signum() <= 0) return BigInteger.ZERO;
        Map<AEKey, BigInteger> stacks = mutable(uuid, reg);
        stacks.merge(key, amount, BigInteger::add);
        revisions.merge(uuid, 1L, Long::sum);
        // Defer the compressed NBT write to the periodic flush: a bulk transfer of hundreds of
        // keys per tick must not pay one disk write per key per tick.
        dirty.add(uuid);
        return amount;
    }

    /** Debit up to {@code requested}; returns amount actually taken. */
    public BigInteger debit(UUID uuid, AEKey key, BigInteger requested, HolderLookup.Provider reg) {
        if (requested == null || requested.signum() <= 0) return BigInteger.ZERO;
        Map<AEKey, BigInteger> stacks = mutable(uuid, reg);
        BigInteger existing = stacks.getOrDefault(key, BigInteger.ZERO);
        BigInteger taken = existing.min(requested);
        if (taken.signum() == 0) return BigInteger.ZERO;
        BigInteger remaining = existing.subtract(taken);
        if (remaining.signum() <= 0) stacks.remove(key);
        else stacks.put(key, remaining);
        revisions.merge(uuid, 1L, Long::sum);
        dirty.add(uuid);
        return taken;
    }

    /** Force-write all loaded disks (used on server stop). */
    public void flushAll(HolderLookup.Provider reg) {
        for (UUID uuid : new ArrayList<>(content.keySet())) {
            write(uuid, reg);
        }
        dirty.clear();
    }

    /**
     * Write every disk changed since the last flush. Called once per server tick (see the mod's
     * tick hook), so a burst of credit/debit calls in a single tick collapses into one file write.
     */
    public void flushDirty(HolderLookup.Provider reg) {
        if (dirty.isEmpty()) return;
        for (UUID uuid : new ArrayList<>(dirty)) {
            write(uuid, reg);
        }
        dirty.clear();
    }
}