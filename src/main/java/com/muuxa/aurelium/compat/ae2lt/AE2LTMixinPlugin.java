package com.muuxa.aurelium.compat.ae2lt;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Second line of defence beside {@code requiredMods=["ae2lt"]}: if the config is ever loaded
 * without AE2 Lightning Tech present, the mixin (which references its types) is skipped instead
 * of failing class loading.
 */
public final class AE2LTMixinPlugin implements IMixinConfigPlugin {
    private static final String AE2LT = "com/moakiee/ae2lt/api/frequency/FrequencyBindingHost.class";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return getClass().getClassLoader().getResource(AE2LT) != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {
    }

    @Override
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
    }
}
