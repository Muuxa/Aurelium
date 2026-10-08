package com.muuxa.aurelium.compat.dataenergistics.mixin;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.DelegatingMEInventory;
import com.fish_dan_.data_energistics.ae2.grid.ExactExtractableStorage;
import com.fish_dan_.data_energistics.ae2.grid.UnlimitedExtractableStorage;
import com.muuxa.aurelium.compat.dataenergistics.DEExactBridge;
import java.math.BigInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Lets Data_Energistics' exact accounting read a vault mounted behind any AE2 inventory
 * wrapper, and marks an infinite cell as an <b>infinite source</b> so DE's finite-transfer
 * planner skips it (the same way DE skips creative cells).
 *
 * <p>For non-vault wrappers {@link #exactAvailable} reproduces DE's own
 * {@code getAvailableStacks} + {@code extract(SIMULATE)} fallback (on the wrapper, so partition
 * filters still apply), keeping network-wide totals unchanged for everything except the vault.</p>
 */
@Mixin(DelegatingMEInventory.class)
public abstract class DelegatingMEInventoryDEMixin implements ExactExtractableStorage, UnlimitedExtractableStorage {
    @Shadow
    protected abstract MEStorage getDelegate();

    @Override
    public BigInteger exactAvailable(AEKey key, IActionSource source) {
        return DEExactBridge.exactAvailable((MEStorage) this, this.getDelegate(), key, source);
    }

    @Override
    public boolean supportsUnlimitedExtraction(AEKey key, IActionSource source) {
        return DEExactBridge.supportsUnlimitedExtraction(this.getDelegate(), key, source);
    }
}