package com.muuxa.aurelium.compat.neoecoae.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.SupplierStorage;
import cn.dancingsnow.neoecoae.api.storage.ECOBigIntegerStorage;
import cn.dancingsnow.neoecoae.crafting.amount.ExactAmount;
import cn.dancingsnow.neoecoae.crafting.display.terminal.ExactAmountSource;
import com.muuxa.aurelium.compat.neoecoae.VaultExactBridge;
import com.muuxa.aurelium.storage.VaultInventory;
import java.math.BigInteger;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The portable terminal mounts its cell through a {@code SupplierStorage}, which is a
 * different wrapper than {@code DelegatingMEInventory}. Teach that wrapper the exact
 * channel too, unwrapping its lazy supplier to the vault underneath.
 */
@Mixin(SupplierStorage.class)
public abstract class SupplierStorageMixin implements ECOBigIntegerStorage, ExactAmountSource {
    @Shadow
    @Final
    private Supplier<MEStorage> supplier;

    @Override
    public BigInteger insertBigInteger(AEKey what, BigInteger amount, Actionable mode, IActionSource source) {
        MEStorage delegate = this.supplier.get();
        VaultInventory vault = VaultExactBridge.vaultOf(delegate);
        if (vault != null) return VaultExactBridge.insertBigInteger(vault, what, amount, mode, source);
        com.muuxa.aurelium.storage.InfiniteCellInventory infinite = VaultExactBridge.infiniteOf(delegate);
        if (infinite != null) return VaultExactBridge.insertInfinite(infinite, what, amount, mode, source);
        return BigInteger.ZERO;
    }

    @Override
    public void neoecoae$visitExactAmounts(BiConsumer<AEKey, ExactAmount> visitor) {
        MEStorage delegate = this.supplier.get();
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