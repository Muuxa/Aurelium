package com.muuxa.aurelium.kubejs;

import com.muuxa.aurelium.api.AureliumCellAPI;
import com.muuxa.aurelium.api.AureliumExport;
import com.muuxa.aurelium.api.AureliumPacks;
import com.muuxa.aurelium.api.AureliumInfinite;
import com.muuxa.aurelium.api.AureliumInfiniteCellAPI;
import com.muuxa.aurelium.api.InfiniteKindBuilder;
import com.muuxa.aurelium.api.InfiniteKindSpec;
import com.muuxa.aurelium.api.AureliumVaultTree;
import dev.latvian.mods.kubejs.plugin.ClassFilter;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.registry.BuilderTypeRegistry;
import dev.latvian.mods.kubejs.script.BindingRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

/** Discovered via kubejs.plugins.txt; class only loaded if KubeJS is installed. */
public final class AureliumKubeJSPlugin implements KubeJSPlugin {
    @Override
    public void registerClasses(ClassFilter filter) {
        filter.allow(AureliumPacks.class);
        filter.allow(AureliumCellAPI.class);
        filter.allow(AureliumExport.class);
        filter.allow(AureliumInfinite.class);
        filter.allow(InfiniteKindBuilder.class);
        filter.allow(InfiniteKindSpec.class);
        filter.allow(AureliumInfiniteCellAPI.class);
        filter.allow(AureliumVaultTree.class);
        filter.allow(InfiniteCellItemBuilder.class);
        filter.allow(VaultItemBuilder.class);
    }

    @Override
    public void registerBindings(BindingRegistry bindings) {
        bindings.add("AureliumPacks", AureliumPacks.class);
        bindings.add("AureliumCell", AureliumCellAPI.class);
        bindings.add("AureliumExport", AureliumExport.class);
        bindings.add("AureliumInfinite", AureliumInfinite.class);
        bindings.add("AureliumInfiniteCell", AureliumInfiniteCellAPI.class);
        bindings.add("AureliumVaultTree", AureliumVaultTree.class);
    }

    @Override
    public void registerBuilderTypes(BuilderTypeRegistry registry) {
        // Lets scripts create custom-ID infinite cells in startup_scripts:
        //   event.create('my_cell', 'aurelium:infinite_cell').infiniteKind('mykind');
        // Uses the ResourceLocation overload (the String one is deprecated for removal).
        registry.of(Registries.ITEM, callback -> callback.add(
                ResourceLocation.fromNamespaceAndPath("aurelium", "infinite_cell"),
                InfiniteCellItemBuilder.class,
                InfiniteCellItemBuilder::new));
        // Dedicated vault items: create('<your id>', 'aurelium:star_vault').vaultPack('<pack>')
        registry.of(Registries.ITEM, callback -> callback.add(
                ResourceLocation.fromNamespaceAndPath("aurelium", "star_vault"),
                VaultItemBuilder.class,
                VaultItemBuilder::new));
        // Restored pattern tools: create('<your id>', 'aurelium:pattern_tool').toolPayload('<b64>')
        // (written by /aurelium export; the builder stamps payload + replace mode as defaults).
        registry.of(Registries.ITEM, callback -> callback.add(
                ResourceLocation.fromNamespaceAndPath("aurelium", "pattern_tool"),
                PatternToolItemBuilder.class,
                PatternToolItemBuilder::new));
    }
}
