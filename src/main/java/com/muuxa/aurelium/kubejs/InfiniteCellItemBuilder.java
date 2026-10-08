package com.muuxa.aurelium.kubejs;

import com.muuxa.aurelium.storage.InfiniteCellInventory;
import com.muuxa.aurelium.storage.InfiniteCellItem;
import dev.latvian.mods.kubejs.generator.KubeAssetGenerator;
import dev.latvian.mods.kubejs.item.ItemBuilder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;

/**
 * KubeJS item builder for a custom-ID infinite cell.
 *
 * <p>Usage (kubejs/startup_scripts/):</p>
 * <pre>
 *   StartupEvents.registry('item', event =&gt; {
 *     event.create('aurelium:infinite_concrete', 'aurelium:infinite_cell')
 *          .infiniteKind('aurelium:concrete')
 *          .displayName('无限混凝土');
 *   });
 * </pre>
 *
 * <p>The built item is a {@link InfiniteCellItem} whose <b>default</b> component carries only
 * the kind id ({@link InfiniteCellInventory#KIND_TAG}); the set of infinite contents lives in
 * the kind registry, never on the stack. Two items that declare the same kind behave
 * identically but keep their own ids, names and textures.</p>
 */
public class InfiniteCellItemBuilder extends ItemBuilder {
    /**
     * Texture used when the script does <b>not</b> declare one. Vanilla/KubeJS would otherwise
     * point {@code layer0} at {@code <this item id>} (e.g. {@code kubejs:item/starlight_cell}),
     * which usually does not exist → the infamous black/purple "missing texture" checker.
     * Pointing it at AURELIUM's bundled pink base gives every unnamed cell a sane default look.
     */
    private static final String DEFAULT_CELL_TEXTURE = "aurelium:item/infinite_pink_base";

    /** Kind id this item serves; written to the stack's CustomData at item-creation time. */
    private String kindId;

    public InfiniteCellItemBuilder(ResourceLocation id) {
        super(id);
        this.maxStackSize = 1;
        this.fireResistant = true;
        // Scripts usually write .fireResistant() themselves; setting the field directly also
        // covers cells registered without it, so every AURELIUM item is fire/explosion proof.
        this.baseTexture = DEFAULT_CELL_TEXTURE;
    }

    /** Declare which registered infinite kind this item serves (required). */
    public InfiniteCellItemBuilder infiniteKind(String kindId) {
        this.kindId = kindId;
        return this;
    }

    /** 与 {@link #infiniteKind(String)} 等价，名字更短，推荐在脚本里用。 */
    public InfiniteCellItemBuilder kind(String kindId) {
        return infiniteKind(kindId);
    }

    /**
     * 脚本没写 {@code .texture(...)} / {@code .model(...)} 时，不要生成"指向不存在贴图"的模型，
     * 而是直接继承 AURELIUM 内置的粉色无限元件模型（自带 4 层与流动动画）。
     *
     * <p>KubeJS 默认会把 {@code layer0} 指向 {@code <物品id>}（如 {@code kubejs:item/starlight_cell}），
     * 脚本通常不提供这张 PNG，于是游戏里就是黑紫格。这里把它改成继承内置模型，作为"默认外观"。</p>
     */
    @Override
    protected void generateItemModels(KubeAssetGenerator generator) {
        // 只在脚本「什么都没指定」时才套默认外观：
        //   textures 为空 且 parentModel 为空 → 说明既没给贴图也没给模型。
        // 任意一个非空都说明脚本有自己的外观诉求，交由 KubeJS 原逻辑处理。
        if (this.textures.isEmpty() && this.parentModel == null) {
            ResourceLocation builtin = ResourceLocation.tryParse("aurelium:item/infinite_cell");
            if (builtin != null) {
                generator.itemModel(this.id, model -> model.parent(builtin));
                return;
            }
        }
        super.generateItemModels(generator);
    }

    @Override
    public Item createObject() {
        // Only the kind id is stamped onto the item; contents stay in the kind registry.
        CompoundTag tag = new CompoundTag();
        if (kindId != null) tag.putString(InfiniteCellInventory.KIND_TAG, kindId);
        this.component(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return new InfiniteCellItem(this.createItemProperties());
    }
}