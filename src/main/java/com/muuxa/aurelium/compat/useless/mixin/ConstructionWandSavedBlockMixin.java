package com.muuxa.aurelium.compat.useless.mixin;

import com.muuxa.aurelium.storage.BlockAdvanceIOPort;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The wand's undo restores the saved block without consuming the item it would have dropped, so
 * restoring an advanced IO port after it was popped would duplicate it. Skip that restore; the
 * dropped port item is already with the player.
 */
@Mixin(targets = "com.sorrowmist.useless.compat.constructionwand.ConstructionWandLogic$SavedBlock")
public abstract class ConstructionWandSavedBlockMixin {

    @Shadow
    @Final
    private BlockState beforeState;

    @Inject(method = "restore", at = @At("HEAD"), cancellable = true)
    private void aurelium$skipAdvanceIOPortRestore(ServerLevel level, CallbackInfo ci) {
        if (beforeState != null && beforeState.getBlock() instanceof BlockAdvanceIOPort) {
            ci.cancel();
        }
    }
}
