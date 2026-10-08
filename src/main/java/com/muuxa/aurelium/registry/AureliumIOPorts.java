package com.muuxa.aurelium.registry;

import com.muuxa.aurelium.Aurelium;
import com.muuxa.aurelium.storage.BlockAdvanceIOPort;
import com.muuxa.aurelium.storage.TileAdvanceIOPort;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registration for the AURELIUM Advanced IO Port. Pure AE2 (no ExtendedAE): it extends AE2's own IO
 * port, so it is registered unconditionally alongside the rest of AURELIUM's content.
 */
public final class AureliumIOPorts {

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Aurelium.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Aurelium.ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Aurelium.ID);

    public static final DeferredBlock<BlockAdvanceIOPort> ADVANCE_IO_PORT = BLOCKS.register("advance_io_port",
            () -> new BlockAdvanceIOPort());

    public static final DeferredItem<BlockItem> ADVANCE_IO_PORT_ITEM = ITEMS.register("advance_io_port",
            () -> new BlockItem(ADVANCE_IO_PORT.get(), new Item.Properties().rarity(Rarity.RARE)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TileAdvanceIOPort>> ADVANCE_IO_PORT_BE =
            BLOCK_ENTITIES.register("advance_io_port", () -> {
                // Bridge NeoForge's 2-arg (BlockPos, BlockState) supplier to our 3-arg constructor
                // (which super() requires the BlockEntityType), the same trick AE2 uses.
                java.util.concurrent.atomic.AtomicReference<BlockEntityType<TileAdvanceIOPort>> holder =
                        new java.util.concurrent.atomic.AtomicReference<>();
                BlockEntityType<TileAdvanceIOPort> type = BlockEntityType.Builder.of(
                        (pos, state) -> new TileAdvanceIOPort(holder.get(), pos, state),
                        ADVANCE_IO_PORT.get()).build(null);
                holder.set(type);
                // AEBaseEntityBlock.newBlockEntity() needs this set, otherwise it NPEs on placement.
                // Our block extends AE2's IOPortBlock, whose generic is IOPortBlockEntity, so the
                // (variance-incompatible) types are bridged with checked casts - safe at runtime
                // because AE2 only stores these and calls BlockEntityType#create.
                @SuppressWarnings("unchecked")
                Class<appeng.blockentity.storage.IOPortBlockEntity> beClass =
                        (Class<appeng.blockentity.storage.IOPortBlockEntity>) (Class<?>) TileAdvanceIOPort.class;
                @SuppressWarnings("unchecked")
                BlockEntityType<appeng.blockentity.storage.IOPortBlockEntity> beType =
                        (BlockEntityType<appeng.blockentity.storage.IOPortBlockEntity>) (BlockEntityType<?>) type;
                ADVANCE_IO_PORT.get().setBlockEntity(beClass, beType, null, null);
                return type;
            });

    /**
     * Link the block entity type to its item so AE2's GUI title reflects our name. Deferred to
     * common setup because both DeferredRegisters must have finished by then (doing it inline in
     * the BE registry lambda can run before the item registry is populated).
     */
    public static void registerBlockEntityItems() {
        appeng.blockentity.AEBaseBlockEntity.registerBlockEntityItem(ADVANCE_IO_PORT_BE.get(),
                ADVANCE_IO_PORT_ITEM.get());
    }

    private AureliumIOPorts() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}