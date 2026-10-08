package com.muuxa.aurelium.kubejs;

import com.muuxa.aurelium.pattern.PatternToolData;
import com.muuxa.aurelium.pattern.PatternToolItem;
import dev.latvian.mods.kubejs.item.ItemBuilder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;

/**
 * KubeJS item builder for a <b>pattern tool restored from an export</b>.
 *
 * <p>Each exported tool gets its own item id (derived from its name): the payload, the tool kind
 * and the replace-on-paste flag all live in that item's <b>default</b> components, so every stack
 * of the id is a fully working tool. The exporter writes the matching script; nothing here is
 * meant to be called by hand, but the shape is:</p>
 *
 * <pre>
 *   StartupEvents.registry('item', event =&gt; {
 *     event.create('aurelium:export_tool_star_scissors', 'aurelium:pattern_tool')
 *          .displayName('明星剪刀')
 *          .copyTool(false)
 *          .toolPayload('H4sIAAAAAAAA…')
 *          .replaceMode(false);
 *   });
 * </pre>
 *
 * <p>No recipe is generated: the tool is a restore of something the player already owned, not a
 * new craftable item.</p>
 */
public class PatternToolItemBuilder extends ItemBuilder {
    /** Base64 gzip payload written by the exporter; {@code null} for an empty tool. */
    private String toolPayload;
    /** {@code true} builds the copy tool, {@code false} the cut tool. */
    private boolean copyTool;
    /** Replace same-primary-output patterns when pasting (see {@link PatternToolData}). */
    private boolean replaceMode;

    public PatternToolItemBuilder(ResourceLocation id) {
        super(id);
        this.maxStackSize = 1;
        this.fireResistant = true;
        this.rarity = net.minecraft.world.item.Rarity.RARE;
        // Default appearance: inherit AURELIUM's own tool model instead of pointing layer0 at a
        // texture that does not exist (which would render the purple/black missing-model cube).
        this.modelGenerator = model -> model.parent(ResourceLocation.fromNamespaceAndPath(
                "aurelium", copyTool ? "item/pattern_copy_tool" : "item/pattern_cut_tool"));
    }

    /** The stored patterns as the Base64 gzip payload produced by {@code /aurelium export}. */
    public PatternToolItemBuilder toolPayload(String base64) {
        this.toolPayload = base64;
        return this;
    }

    /** {@code true} = copy tool, {@code false} = cut tool. */
    public PatternToolItemBuilder copyTool(boolean copy) {
        this.copyTool = copy;
        return this;
    }

    /** Whether pasting replaces patterns with the same primary output. */
    public PatternToolItemBuilder replaceMode(boolean replace) {
        this.replaceMode = replace;
        return this;
    }

    @Override
    public Item createObject() {
        CompoundTag data = new CompoundTag();
        CompoundTag payload = PatternToolData.payloadTag(toolPayload);
        if (payload != null) data.merge(payload);
        PatternToolData.putReplaceMode(data, replaceMode);
        if (!data.isEmpty()) {
            this.component(DataComponents.CUSTOM_DATA, CustomData.of(data));
        }
        return new PatternToolItem(this.createItemProperties(), copyTool);
    }
}
