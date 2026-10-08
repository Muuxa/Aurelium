package com.muuxa.aurelium.compat.neoecoae.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.DelegatingMEInventory;
import cn.dancingsnow.neoecoae.api.storage.ECOBigIntegerStorage;
import cn.dancingsnow.neoecoae.crafting.amount.ExactAmount;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountSource;
import com.muuxa.aurelium.compat.neoecoae.VaultExactBridge;
import com.muuxa.aurelium.storage.VaultInventory;
import java.math.BigInteger;
import java.util.function.BiConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Makes every AE2 inventory wrapper that can hold a vault participate in NeoEcoAE's
 * exact-BigInteger channel. {@code DriveWatcher} (drives) and storage-bus/chest handlers
 * all extend {@code DelegatingMEInventory}, so hooking the base class covers them at once.
 *
 * <p>Only active on delegated storages: the methods unwrap to a {@link VaultInventory}
 * (keyed by {@code VaultInventory}'s identity) and otherwise report nothing, so other
 * inventories keep their ordinary long behaviour untouched.</p>
 */
@Mixin(DelegatingMEInventory.class)
public abstract class DelegatingMEInventoryMixin implements ECOBigIntegerStorage, ExactAmountSource {
    @Shadow
    protected abstract MEStorage getDelegate();

    @Override
    public BigInteger insertBigInteger(AEKey what, BigInteger amount, Actionable mode, IActionSource source) {
        MEStorage delegate = this.getDelegate();
        VaultInventory vault = VaultExactBridge.vaultOf(delegate);
        if (vault != null) return VaultExactBridge.insertBigInteger(vault, what, amount, mode, source);
        com.muuxa.aurelium.storage.InfiniteCellInventory infinite = VaultExactBridge.infiniteOf(delegate);
        if (infinite != null) return VaultExactBridge.insertInfinite(infinite, what, amount, mode, source);
        return BigInteger.ZERO;
    }

    @Override
    public void neoecoae$visitExactAmounts(BiConsumer<AEKey, ExactAmount> visitor) {
        MEStorage delegate = this.getDelegate();
        VaultInventory vault = VaultExactBridge.vaultOf(delegate);
        if (vault != null) {
            VaultExactBridge.visitExactAmounts(vault, visitor);
            return;
        }
        // An infinite cell reports its keys as unbounded so NeoEcoAE's creative filter skips it.
        com.muuxa.aurelium.storage.InfiniteCellInventory infinite = VaultExactBridge.infiniteOf(delegate);
        if (infinite != null) VaultExactBridge.visitInfiniteAmounts(infinite, visitor);
    }
}