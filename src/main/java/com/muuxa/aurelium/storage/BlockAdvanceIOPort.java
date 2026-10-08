package com.muuxa.aurelium.storage;

import appeng.block.storage.IOPortBlock;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.IOPortMenu;
import appeng.menu.locator.MenuLocators;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Block for the AURELIUM Advanced IO Port. Extends AE2's own {@link IOPortBlock} (which supplies
 * its own metal properties) - no ExtendedAE needed. The GUI is AE2's standard IO port menu, since
 * our block entity is an {@code IOPortBlockEntity}.
 */
public class BlockAdvanceIOPort extends IOPortBlock {

    public BlockAdvanceIOPort() {
        super();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        var be = level.getBlockEntity(pos);
        if (be instanceof TileAdvanceIOPort port) {
            if (!level.isClientSide) {
                MenuOpener.open(IOPortMenu.TYPE, player, MenuLocators.forBlockEntity(port));
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useWithoutItem(state, level, pos, player, hit);
    }
}