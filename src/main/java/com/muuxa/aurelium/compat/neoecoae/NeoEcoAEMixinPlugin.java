package com.muuxa.aurelium.compat.neoecoae;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates every AURELIUM↔NeoEcoAE mixin behind NeoEcoAE's actual presence, so a world
 * without NeoEcoAE never tries to load classes that reference its types. The
 * {@code requiredMods} guard in {@code neoforge.mods.toml} is the first line of
 * defence; this plugin is the second.
 */
public final class NeoEcoAEMixinPlugin implements IMixinConfigPlugin {
    private static final String NEOECOAE = "cn/dancingsnow/neoecoae/api/storage/ECOBigIntegerStorage.class";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return present(NEOECOAE);
    }

    private boolean present(String resource) {
        return getClass().getClassLoader().getResource(resource) != null;
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