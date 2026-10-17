package com.muuxa.aurelium.compat.ae2lt.mixin;

import appeng.blockentity.grid.AENetworkedBlockEntity;
import com.moakiee.ae2lt.api.frequency.FrequencyBindingAccess;
import com.moakiee.ae2lt.api.frequency.FrequencyBindingHost;
import com.moakiee.ae2lt.grid.FrequencyBindingHelper;
import com.muuxa.aurelium.compat.ae2lt.AE2LTFrequencyHooks;
import com.muuxa.aurelium.compat.ae2lt.AE2LTFrequencyHostShim;
import com.muuxa.aurelium.storage.TileAdvanceIOPort;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.IGridNodeListener;

/**
 * Makes AURELIUM's OI port a native AE2 Lightning Tech frequency host.
 *
 * <p>AE2LT decides whether a block can be linked by {@code be instanceof FrequencyBindingHost},
 * so a proxy or a wrapper can never work — the interface has to be on the block entity class
 * itself. This mixin is only applied when AE2LT is present (see {@code requiredMods} and
 * {@link com.muuxa.aurelium.compat.ae2lt.AE2LTMixinPlugin}).</p>
 *
 * <p>The lifecycle calls are forwarded through {@link AE2LTFrequencyHooks} so the port class
 * itself stays free of AE2LT references and keeps working when the mod is absent.</p>
 */
@Mixin(TileAdvanceIOPort.class)
public abstract class TileAdvanceIOPortFrequencyMixin implements FrequencyBindingHost {

    @Unique
    private FrequencyBindingHelper aurelium$frequencyBinding;

    @Unique
    private AE2LTFrequencyHostShim aurelium$hostShim;

    @Unique
    private AE2LTFrequencyHooks.Binding aurelium$bindingAdapter;

    @Unique
    private FrequencyBindingHelper aurelium$binding() {
        if (aurelium$frequencyBinding == null) {
            aurelium$frequencyBinding = new FrequencyBindingHelper(this);
        }
        return aurelium$frequencyBinding;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void aurelium$attachFrequencyBinding(CallbackInfo ci) {
        this.aurelium$bindingAdapter = new AE2LTFrequencyHooks.Binding() {
                    @Override
                    public void tick() {
                        aurelium$binding().serverTick();
                    }

                    @Override
                    public void onReady() {
                        aurelium$binding().onReady();
                    }

                    @Override
                    public void setRemoved() {
                        aurelium$binding().setRemoved();
                    }

                    @Override
                    public void clearRemoved() {
                        aurelium$binding().clearRemoved();
                    }

                    @Override
                    public void onChunkUnloaded() {
                        aurelium$binding().onChunkUnloaded();
                    }

                    @Override
                    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
                        aurelium$binding().onMainNodeStateChanged(reason);
                    }

                    @Override
                    public void save(CompoundTag tag) {
                        aurelium$binding().save(tag);
                    }

                    @Override
                    public void load(CompoundTag tag) {
                        aurelium$binding().load(tag);
                    }
                };
        AE2LTFrequencyHooks.attach((TileAdvanceIOPort) (Object) this, this.aurelium$bindingAdapter);
    }

    @Override
    public AENetworkedBlockEntity getFrequencyBindingBlockEntity() {
        if (aurelium$hostShim == null) {
            aurelium$hostShim = AE2LTFrequencyHostShim.create((TileAdvanceIOPort) (Object) this);
        }
        return aurelium$hostShim;
    }

    @Override
    public void saveFrequencyBindingChanges() {
        ((TileAdvanceIOPort) (Object) this).saveChanges();
    }

    @Override
    public void markFrequencyBindingForUpdate() {
        ((TileAdvanceIOPort) (Object) this).markForUpdate();
    }

    @Override
    public FrequencyBindingAccess getFrequencyBindingAccess() {
        return aurelium$binding();
    }
}
