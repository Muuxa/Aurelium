package com.muuxa.aurelium.storage;

import com.muuxa.aurelium.Aurelium;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives periodic and shutdown persistence for vault cells and infinite disks.
 *
 * <p>Cell changes only mark a UUID dirty (see {@link VaultWorldData#putAmount} and
 * {@link InfiniteDiskWorldData#credit}); this handler flushes them on a timer so an abrupt stop
 * loses at most a few seconds, and flushes everything on server stop so a clean shutdown never
 * loses data. Doing it here — instead of on every insert/extract — is what keeps the write path
 * from rewriting a whole NBT file per item transfer.</p>
 */
@EventBusSubscriber(modid = Aurelium.ID)
public final class VaultPersistence {
    private VaultPersistence() {}

    /** Flush at most this often; one second is plenty for a handwriting-friendly file. */
    private static final int FLUSH_INTERVAL_TICKS = 20;
    private static int tickCounter;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < FLUSH_INTERVAL_TICKS) return;
        tickCounter = 0;
        MinecraftServer server = event.getServer();
        VaultWorldData.get(server).flush(server.registryAccess());
        // Infinite disks follow the same dirty/deferred pattern: their credit/debit only marks the
        // disk, and this flush collapses a tick's worth of transfers into one file write each.
        InfiniteDiskWorldData.get(server).flushDirty(server.registryAccess());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Build any vault trees that scripts registered during startup_scripts (no server yet).
        com.muuxa.aurelium.api.AureliumVaultTree.flushPending();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        VaultWorldData.get(server).flushAll(server.registryAccess());
        // Never lose a disk's last seconds of transfers on a clean shutdown.
        InfiniteDiskWorldData.get(server).flushAll(server.registryAccess());
    }
}