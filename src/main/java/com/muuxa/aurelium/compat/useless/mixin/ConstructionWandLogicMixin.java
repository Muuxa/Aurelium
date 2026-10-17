package com.muuxa.aurelium.compat.useless.mixin;

import com.muuxa.aurelium.storage.BlockAdvanceIOPort;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Useless Mod's construction wand removes blocks with
 * {@code destroyBlock(pos, false, player)} — "false" means no loot table is consulted, so the
 * advanced IO port used to vanish. The wand has its own undo history, but destroyed blocks carry
 * no refund there, so the port has to be popped explicitly.
 */
@Mixin(targets = "com.sorrowmist.useless.compat.constructionwand.ConstructionWandLogic")
public abstract class ConstructionWandLogicMixin {

    @Redirect(method = "destroy", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;)Z"))
    private static boolean aurelium$dropAdvanceIOPort(ServerLevel level, BlockPos pos,
                                                      boolean dropBlock, Entity entity) {
        BlockState before = level.getBlockState(pos);
        boolean removed = level.destroyBlock(pos, dropBlock, entity);
        if (removed && before.getBlock() instanceof BlockAdvanceIOPort) {
            Block.popResource(level, pos, new ItemStack(before.getBlock()));
        }
        return removed;
    }
}
