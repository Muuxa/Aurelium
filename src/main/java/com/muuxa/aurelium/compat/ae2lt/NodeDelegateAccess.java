package com.muuxa.aurelium.compat.ae2lt;

import appeng.api.networking.IManagedGridNode;

/**
 * Implemented on {@code AENetworkedBlockEntity} by the gated AE2LT mixin. It lets the OI-port
 * frequency shim replace its throw-away constructor node with the port's real managed node.
 */
public interface NodeDelegateAccess {
    void aurelium$setNodeDelegate(IManagedGridNode node);
}
