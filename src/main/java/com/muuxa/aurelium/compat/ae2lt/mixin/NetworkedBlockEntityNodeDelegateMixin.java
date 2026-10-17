package com.muuxa.aurelium.compat.ae2lt.mixin;

import appeng.api.networking.IManagedGridNode;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import com.muuxa.aurelium.compat.ae2lt.NodeDelegateAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Makes the private final {@code mainNode} field writable for the frequency shim.
 *
 * <p>The AE2LT shim must report the OI port's real node, but {@code getMainNode()} is final and
 * the node cannot be reassigned during the constructor (an already-created AE2 node rejects
 * {@code setTagName}/{@code setVisualRepresentation}). The shim therefore constructs a normal
 * throw-away node and replaces the field afterwards; only that shim ever calls this setter.</p>
 */
@Mixin(AENetworkedBlockEntity.class)
public abstract class NetworkedBlockEntityNodeDelegateMixin implements NodeDelegateAccess {

    @Shadow
    @Final
    @Mutable
    private IManagedGridNode mainNode;

    @Override
    public void aurelium$setNodeDelegate(IManagedGridNode node) {
        this.mainNode = node;
    }
}
