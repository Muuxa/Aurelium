package com.muuxa.aurelium.client;

import com.muuxa.aurelium.api.AureliumInfinite;
import com.muuxa.aurelium.api.InfiniteKindSpec;
import com.muuxa.aurelium.storage.InfiniteCellInventory;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Wraps the infinite cell's inventory model so each stack renders the look declared by its
 * kind: a custom model id if declared, otherwise the built-in pink model.
 *
 * <p>A kind may name a {@code model} (preferred) and/or a {@code texture}. Both are treated
 * as <b>baked model ids</b>: the resource pack provides a model JSON (which may of course
 * reference the texture). This keeps rendering on the standard model pipeline instead of
 * synthesising geometry at runtime. Texture is only consulted when no model is given.</p>
 *
 * <p>Resolution is per-stack at render time, keyed by the stack's kind NBT.</p>
 */
public final class InfiniteCellBakedModel extends BakedModelWrapper<BakedModel> {
    private final Map<ModelResourceLocation, BakedModel> baked;

    public InfiniteCellBakedModel(BakedModel original, Map<ModelResourceLocation, BakedModel> baked) {
        super(original);
        this.baked = baked;
    }

    @Override
    public ItemOverrides getOverrides() {
        return new InfiniteCellOverrides(originalModel, baked);
    }

    /** Overrides resolving the model to draw for a given infinite-cell stack. */
    private static final class InfiniteCellOverrides extends ItemOverrides {
        private final BakedModel fallback;
        private final Map<ModelResourceLocation, BakedModel> baked;

        InfiniteCellOverrides(BakedModel fallback, Map<ModelResourceLocation, BakedModel> baked) {
            this.fallback = fallback;
            this.baked = baked;
        }

        @Override
        public BakedModel resolve(BakedModel original, ItemStack stack,
                                  @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
            String kind = InfiniteCellInventory.kindId(stack);
            if (kind == null) return fallback;
            InfiniteKindSpec spec = AureliumInfinite.get(kind);
            if (spec == null) return fallback;
            // model wins; texture is a fallback alias, both are baked model ids.
            String look = spec.model() != null ? spec.model() : spec.texture();
            if (look == null) return fallback;
            ResourceLocation id = ResourceLocation.tryParse(look);
            if (id == null) return fallback;
            BakedModel custom = baked.get(ModelResourceLocation.standalone(id));
            return custom != null ? custom : fallback;
        }
    }
}