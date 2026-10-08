package com.muuxa.aurelium.client;

import com.muuxa.aurelium.api.AureliumInfinite;
import com.muuxa.aurelium.api.InfiniteKindSpec;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

import java.util.Map;

/**
 * Wires per-kind custom models/textures for the infinite cell.
 *
 * <p>KubeJS declares kinds at <b>startup</b>, which runs before client resources reload, so at
 * {@link ModelEvent.RegisterAdditional} we already know every custom model id and can ask the
 * model loader to bake it. {@link ModelEvent.ModifyBakingResult} then swaps the infinite cell's
 * inventory model for a wrapper whose overrides pick the right look per stack.</p>
 *
 * <p>Registered from {@link AureliumClient} on the mod event bus.</p>
 */
public final class AureliumInfiniteModels {
    private AureliumInfiniteModels() {}

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        for (InfiniteKindSpec spec : AureliumInfinite.all().values()) {
            // Register both model and texture ids: either may be the baked model to render.
            for (String look : new String[] { spec.model(), spec.texture() }) {
                if (look == null) continue;
                ResourceLocation id = ResourceLocation.tryParse(look);
                if (id != null) event.register(ModelResourceLocation.standalone(id));
            }
        }
    }

    @SubscribeEvent
    public static void onModifyBaking(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        // Wrap EVERY infinite cell item, not just the bundled "aurelium:infinite_cell".
        // KubeJS creates cells under their own ids (e.g. "kubejs:starlight_cell"); without this
        // their declared model/texture would never be applied (the wrapper is what resolves the
        // per-kind look at render time).
        for (net.minecraft.world.item.Item item
                : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (!(item instanceof com.muuxa.aurelium.storage.InfiniteCellItem)) continue;
            ResourceLocation itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
            ModelResourceLocation key = ModelResourceLocation.inventory(itemId);
            BakedModel original = models.get(key);
            if (original == null) continue;
            if (original instanceof InfiniteCellBakedModel) continue; // already wrapped
            models.put(key, new InfiniteCellBakedModel(original, models));
        }
    }
}
