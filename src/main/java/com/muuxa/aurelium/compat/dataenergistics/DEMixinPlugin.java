package com.muuxa.aurelium.compat.dataenergistics;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gates AURELIUM's Data_Energistics read bridge behind DE's presence, so a world
 * without DE never loads classes referencing {@code ExactExtractableStorage}.
 * The {@code requiredMods} guard in {@code neoforge.mods.toml} is the first line of
 * defence; this plugin is the second.
 */
public final class DEMixinPlugin implements IMixinConfigPlugin {
    private static final String DE = "com/fish_dan_/data_energistics/ae2/grid/ExactExtractableStorage.class";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return getClass().getClassLoader().getResource(DE) != null;
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