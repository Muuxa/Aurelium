package com.muuxa.aurelium.storage;

import appeng.api.config.Actionable;
import appeng.api.config.OperationMode;
import appeng.api.config.Settings;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEKey;
import appeng.api.storage.StorageCells;
import appeng.api.storage.cells.StorageCell;
import appeng.blockentity.storage.IOPortBlockEntity;
import appeng.me.helpers.MachineSource;
import appeng.util.inv.AppEngInternalInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.Map;

/**
 * AURELIUM Advanced IO Port: a standalone AE2 IO port that runs <b>every tick</b> (tick rate 1/1
 * instead of AE2's 1..5) and moves up to {@link CHUNKS_PER_KEY} {@code Long.MAX}-sized chunks per key
 * per tick in every direction.
 *
 * <p>Direction follows AE2's operation-mode button exactly (same semantics as the vanilla port's
 * {@code transferContents}):</p>
 * <ul>
 *   <li><b>EMPTY (default)</b> — the cell is the <b>source</b>: take everything OUT of the cell and
 *       insert it into the network. This is the vanilla "empty the cell" behaviour.</li>
 *   <li><b>FILL</b> — the cell is the <b>destination</b>: take items OUT of the network and put them
 *       into the cell.</li>
 * </ul>
 *
 * <p>Every cell type is handled in both directions: {@link InfiniteDiskInventory} (BigInteger, beyond
 * long), {@link InfiniteCellInventory} (infinite source) and ordinary AE2 cells such as the star
 * vault (long path).</p>
 *
 * <p><b>No ExtendedAE dependency.</b> It extends AE2's own {@link IOPortBlockEntity} and reaches the
 * private input-cell inventory by reflection, so only AE2 is required.</p>
 */
public class TileAdvanceIOPort extends IOPortBlockEntity {

    /**
     * For every key, the port loops this many {@code Long.MAX_VALUE}-sized chunks per tick (20/s).
     * One chunk is the most a single AE2 {@code MEStorage} call can carry, so {@code 256} chunks =
     * up to {@code 256 * Long.MAX_VALUE} per key per tick regardless of the cell type. Applied to
     * disk export/import, the infinite cell source, and ordinary cells (which include the star
     * vault).
     */
    public static final int CHUNKS_PER_KEY = 256;

    /** Keys the long path may visit per pass. */
    public static final int KEYS_PER_PASS = 256;

    /** A single chunk: the largest amount one AE2 storage call can express. */
    private static final long CHUNK = Long.MAX_VALUE;

    private static final Field INPUT_CELLS_FIELD = resolveInputCellsField();

    private final AppEngInternalInventory cells;

    /**
     * Re-entrancy guard for the "network -> cell" direction: the drive holding our own cell is itself
     * a network storage, so reading the network's available stacks can call back into the cell we are
     * about to fill. Guard against that self-recursion.
     */
    private boolean importingFromNetwork;

    public TileAdvanceIOPort(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.cells = readInputCells();
    }

    private static Field resolveInputCellsField() {
        try {
            Field f = IOPortBlockEntity.class.getDeclaredField("inputCells");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private AppEngInternalInventory readInputCells() {
        if (INPUT_CELLS_FIELD == null) return null;
        try {
            return (AppEngInternalInventory) INPUT_CELLS_FIELD.get(this);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Always tick every game tick (1..1), never sleeping like the vanilla 1..5 IO port. */
    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 1, false);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        if (!this.getMainNode().isActive()) {
            return TickRateModulation.IDLE;
        }
        IGrid grid = this.getMainNode().getGrid();
        if (grid == null) {
            return TickRateModulation.IDLE;
        }
        if (this.cells == null) {
            // Reflection unavailable: fall back to AE2's own behaviour.
            return super.tickingRequest(node, ticksSinceLastCall);
        }
        // AE2 semantics: EMPTY = cell -> network (source is the cell), anything else (FILL) = the
        // network is the source and items are pushed into the cell.
        boolean toNetwork = this.getConfigManager().getSetting(Settings.OPERATION_MODE) != OperationMode.FILL;
        var networkInv = grid.getStorageService().getInventory();
        IActionSource src = new MachineSource(this);

        if (this.importingFromNetwork) {
            return TickRateModulation.IDLE;
        }
        if (!toNetwork) {
            this.importingFromNetwork = true;
        }
        boolean moved = false;
        try {
            for (int x = 0; x < this.cells.size(); x++) {
                ItemStack stack = this.cells.getStackInSlot(x);
                StorageCell cell = StorageCells.getCellInventory(stack, null);
                if (cell == null) {
                    continue;
                }
                if (cell instanceof InfiniteDiskInventory disk) {
                    moved |= transferDisk(networkInv, disk, toNetwork, src);
                } else if (cell instanceof InfiniteCellInventory infinite) {
                    // An infinite cell is a pure source: only the "cell -> network" direction ever does
                    // anything (inserting into it just voids the items).
                    if (toNetwork) {
                        moved |= serveInfinite(networkInv, infinite, src);
                    }
                } else {
                    moved |= transferCell(networkInv, cell, toNetwork, src);
                }
            }
        } finally {
            this.importingFromNetwork = false;
        }
        // NEVER return SLEEP here. In AE2, SLEEP parks the device in the tick manager's sleeping set
        // (TickManagerService calls sleepDevice on SLEEP) and it then only wakes on an inventory
        // change — so one idle tick would stop the port until something touched its slots, which
        // showed up as "slow / the import direction does nothing". IDLE just keeps it ticking at its
        // configured max rate (1 = every tick).
        return moved ? TickRateModulation.URGENT : TickRateModulation.IDLE;
    }

    /**
     * Beyond-long transfer between a disk and the network, in the requested direction.
     *
     * @param toNetwork {@code true} = disk -> network (export), {@code false} = network -> disk (import)
     */
    private boolean transferDisk(appeng.api.storage.MEStorage networkInv, InfiniteDiskInventory disk,
                                 boolean toNetwork, IActionSource src) {
        BigInteger chunkMax = BigInteger.valueOf(CHUNK);
        boolean moved = false;

        if (toNetwork) {
            // Disk -> network: push every stored key out, beyond long, up to CHUNKS_PER_KEY chunks.
            for (Map.Entry<AEKey, BigInteger> e : disk.snapshotMap().entrySet()) {
                AEKey key = e.getKey();
                BigInteger amount = e.getValue();
                if (amount.signum() <= 0) continue;
                BigInteger taken = disk.extractBig(key, amount);
                if (taken.signum() <= 0) continue;
                BigInteger remaining = taken;
                for (int chunk = 0; chunk < CHUNKS_PER_KEY && remaining.signum() > 0; chunk++) {
                    long accepted = networkInv.insert(key, remaining.min(chunkMax).longValue(),
                            Actionable.MODULATE, src);
                    if (accepted <= 0L) break;
                    remaining = remaining.subtract(BigInteger.valueOf(accepted));
                }
                if (remaining.signum() > 0) {
                    disk.insertBig(key, remaining); // put back whatever the network refused
                } else {
                    moved = true;
                }
            }
            return moved;
        }

        // Network -> disk: read the network's live contents (not the possibly one-tick-stale cache)
        // and drain each key into the disk. The network layer only speaks long, so we loop
        // Long.MAX-sized chunks up to CHUNKS_PER_KEY times per key.
        var counter = new appeng.api.stacks.KeyCounter();
        networkInv.getAvailableStacks(counter);
        for (Map.Entry<AEKey, Long> e : counter) {
            AEKey key = e.getKey();
            if (e.getValue() <= 0L) continue;
            for (int chunk = 0; chunk < CHUNKS_PER_KEY; chunk++) {
                long drained = networkInv.extract(key, CHUNK, Actionable.MODULATE, src);
                if (drained <= 0L) break;
                disk.insertBig(key, BigInteger.valueOf(drained));
                moved = true;
                if (drained < CHUNK) break; // the source is exhausted
            }
        }
        return moved;
    }

    /** Infinite source: push a long's worth per key, looping up to CHUNKS_PER_KEY times each tick. */
    private boolean serveInfinite(appeng.api.storage.MEStorage networkInv, InfiniteCellInventory infinite,
                                  IActionSource src) {
        boolean moved = false;
        for (AEKey key : infinite.infiniteKeys()) {
            for (int chunk = 0; chunk < CHUNKS_PER_KEY; chunk++) {
                if (networkInv.insert(key, CHUNK, Actionable.MODULATE, src) <= 0L) break;
                moved = true;
            }
        }
        return moved;
    }

    /**
     * Ordinary cell (including the star vault), both directions. Each visited key moves up to
     * {@code CHUNKS_PER_KEY * Long.MAX_VALUE} per tick (uncapped by speed cards). AE2's own
     * {@code MEStorage.insert/extract} only take a {@code long}, so a single call can never exceed
     * one chunk; looping is what raises the ceiling.
     */
    private boolean transferCell(appeng.api.storage.MEStorage networkInv, StorageCell cell,
                                 boolean toNetwork, IActionSource src) {
        int visited = 0;
        boolean moved = false;

        if (toNetwork) {
            // Cell -> network.
            for (Map.Entry<AEKey, Long> entry : cell.getAvailableStacks()) {
                if (visited >= KEYS_PER_PASS) break;
                AEKey key = entry.getKey();
                if (entry.getValue() <= 0L) continue;
                boolean any = false;
                for (int chunk = 0; chunk < CHUNKS_PER_KEY; chunk++) {
                    long taken = cell.extract(key, CHUNK, Actionable.MODULATE, src);
                    if (taken <= 0L) break;
                    long accepted = networkInv.insert(key, taken, Actionable.MODULATE, src);
                    if (accepted < taken) {
                        cell.insert(key, taken - accepted, Actionable.MODULATE, src); // give back the rest
                    }
                    if (accepted <= 0L) break;
                    any = true;
                    if (accepted < CHUNK) break; // the cell is exhausted
                }
                moved |= any;
                visited++;
            }
            return moved;
        }

        // Network -> cell.
        var counter = new appeng.api.stacks.KeyCounter();
        networkInv.getAvailableStacks(counter);
        for (Map.Entry<AEKey, Long> entry : counter) {
            if (visited >= KEYS_PER_PASS) break;
            AEKey key = entry.getKey();
            if (entry.getValue() <= 0L) continue;
            boolean any = false;
            for (int chunk = 0; chunk < CHUNKS_PER_KEY; chunk++) {
                long drained = networkInv.extract(key, CHUNK, Actionable.MODULATE, src);
                if (drained <= 0L) break;
                long accepted = cell.insert(key, drained, Actionable.MODULATE, src);
                if (accepted < drained) {
                    networkInv.insert(key, drained - accepted, Actionable.MODULATE, src); // give back the rest
                }
                if (accepted <= 0L) break;
                any = true;
                if (drained < CHUNK) break; // the source is exhausted
            }
            moved |= any;
            visited++;
        }
        return moved;
    }
}