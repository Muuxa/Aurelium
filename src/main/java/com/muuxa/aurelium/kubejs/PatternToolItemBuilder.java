package com.muuxa.aurelium.kubejs;

import com.muuxa.aurelium.pattern.PatternToolData;
import com.muuxa.aurelium.pattern.PatternToolItem;
import dev.latvian.mods.kubejs.item.ItemBuilder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

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
 *          .toolName('明星剪刀')
 *          .copyTool(false)
 *          .patterns([Item.of('ae2:processing_pattern'), Item.of('useless_mod:omniversal_pattern')])
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
    /** Live pattern stacks supplied by an API/script registration; takes precedence over base64. */
    private List<ItemStack> patternStacks;
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

    /** Tool display name. Equivalent to KubeJS's displayName, exposed for Java-facing callers. */
    public PatternToolItemBuilder toolName(String name) {
        String value = name == null ? "" : name;
        this.name(stack -> Component.literal(value));
        return this;
    }

    /** Pattern stacks to place in the tool's default payload. */
    public PatternToolItemBuilder patterns(Object patterns) {
        this.patternStacks = copyStacks(patterns);
        return this;
    }

    /** Alias with a clearer name for scripts. */
    public PatternToolItemBuilder patternStacks(Object patterns) {
        return patterns(patterns);
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
        CompoundTag payload = null;
        if (patternStacks != null && !patternStacks.isEmpty()) {
            payload = PatternToolData.payloadTagFromStacks(patternStacks, RegistryAccess.EMPTY);
            if (payload == null) {
                throw new IllegalArgumentException("Pattern tool payload is too large or cannot be encoded");
            }
        } else {
            payload = PatternToolData.payloadTag(toolPayload);
        }
        if (payload != null) data.merge(payload);
        PatternToolData.putReplaceMode(data, replaceMode);
        if (!data.isEmpty()) {
            this.component(DataComponents.CUSTOM_DATA, CustomData.of(data));
        }
        return new PatternToolItem(this.createItemProperties(), copyTool);
    }

    private static List<ItemStack> copyStacks(Object raw) {
        List<ItemStack> out = new ArrayList<>();
        if (raw instanceof ItemStack stack) {
            if (!stack.isEmpty()) out.add(stack.copy());
            return out;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (value instanceof ItemStack stack && !stack.isEmpty()) {
                    out.add(stack.copy());
                }
            }
            return out;
        }
        if (raw instanceof Object[] array) {
            for (Object value : array) {
                if (value instanceof ItemStack stack && !stack.isEmpty()) {
                    out.add(stack.copy());
                }
            }
        }
        return out;
    }
}
