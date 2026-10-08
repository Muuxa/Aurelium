package com.muuxa.aurelium.compat.dataenergistics.mixin;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.SupplierStorage;
import com.fish_dan_.data_energistics.ae2.grid.ExactExtractableStorage;
import com.fish_dan_.data_energistics.ae2.grid.UnlimitedExtractableStorage;
import com.muuxa.aurelium.compat.dataenergistics.DEExactBridge;
import java.math.BigInteger;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * The portable terminal mounts its cell through a {@code SupplierStorage}, a different wrapper
 * than {@code DelegatingMEInventory}. Teach that wrapper the same Data_Energistics channel so a
 * vault exposes exact amounts and an infinite cell is recognised as an infinite source.
 */
@Mixin(SupplierStorage.class)
public abstract class SupplierStorageDEMixin implements ExactExtractableStorage, UnlimitedExtractableStorage {
    @Shadow
    @Final
    private Supplier<MEStorage> supplier;

    @Override
    public BigInteger exactAvailable(AEKey key, IActionSource source) {
        return DEExactBridge.exactAvailable((MEStorage) this, this.supplier.get(), key, source);
    }

    @Override
    public boolean supportsUnlimitedExtraction(AEKey key, IActionSource source) {
        return DEExactBridge.supportsUnlimitedExtraction(this.supplier.get(), key, source);
    }
}