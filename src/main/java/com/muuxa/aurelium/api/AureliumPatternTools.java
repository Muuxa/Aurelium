package com.muuxa.aurelium.api;

import com.muuxa.aurelium.pattern.PatternToolData;
import com.muuxa.aurelium.pattern.PatternToolItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;

/** Public helpers for external pattern-tool registrations and runtime stack creation. */
public final class AureliumPatternTools {
    private AureliumPatternTools() {}

    /** Java-facing item creation for external mods. Use from a {@code DeferredRegister.Items} supplier. */
    public static Builder item(Item.Properties properties, boolean copyTool) {
        return new Builder(properties, copyTool);
    }

    /** Base64 gzip payload for a list of encoded pattern stacks. */
    public static String payloadBase64(Object patterns) {
        return payloadBase64(patterns, RegistryAccess.EMPTY);
    }

    /** As {@link #payloadBase64(Object)}, using an explicit registry provider when available. */
    public static String payloadBase64(Object patterns, HolderLookup.Provider registries) {
        List<ItemStack> stacks = copyStacks(patterns);
        return PatternToolData.toBase64Payload(stacks, registries);
    }

    /** Applies a tool name, stored patterns and replace mode to an existing pattern-tool stack. */
    public static boolean apply(ItemStack tool, String toolName, Object patterns, boolean replaceMode) {
        return apply(tool, toolName, patterns, replaceMode, RegistryAccess.EMPTY);
    }

    /** As {@link #apply(ItemStack, String, Object, boolean)}, using an explicit registry provider. */
    public static boolean apply(ItemStack tool, String toolName, Object patterns, boolean replaceMode,
                                HolderLookup.Provider registries) {
        if (tool == null || tool.isEmpty() || !(tool.getItem() instanceof PatternToolItem)) return false;
        List<ItemStack> stacks = copyStacks(patterns);
        if (!stacks.isEmpty() && !PatternToolData.save(tool, stacks, registries)) return false;
        if (toolName != null && !toolName.isBlank()) {
            tool.set(DataComponents.CUSTOM_NAME, Component.literal(toolName));
        }
        PatternToolData.setReplaceMode(tool, replaceMode);
        return true;
    }

    /** Reads the patterns stored on a tool. */
    public static List<ItemStack> storedPatterns(ItemStack tool) {
        return storedPatterns(tool, RegistryAccess.EMPTY);
    }

    /** As {@link #storedPatterns(ItemStack)}, using an explicit registry provider. */
    public static List<ItemStack> storedPatterns(ItemStack tool, HolderLookup.Provider registries) {
        if (tool == null || tool.isEmpty() || !(tool.getItem() instanceof PatternToolItem)) return List.of();
        return List.copyOf(PatternToolData.load(tool, registries));
    }

    private static List<ItemStack> copyStacks(Object raw) {
        List<ItemStack> out = new ArrayList<>();
        if (raw instanceof ItemStack stack) {
            if (!stack.isEmpty()) out.add(stack.copy());
            return out;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (value instanceof ItemStack stack && !stack.isEmpty()) out.add(stack.copy());
            }
            return out;
        }
        if (raw instanceof Object[] array) {
            for (Object value : array) {
                if (value instanceof ItemStack stack && !stack.isEmpty()) out.add(stack.copy());
            }
        }
        return out;
    }

    public static final class Builder {
        private final Item.Properties properties;
        private final boolean copyTool;
        private String toolName;
        private Object patterns;
        private String base64Payload;
        private boolean replaceMode;
        private HolderLookup.Provider registries = RegistryAccess.EMPTY;

        private Builder(Item.Properties properties, boolean copyTool) {
            this.properties = properties;
            this.copyTool = copyTool;
        }

        public Builder toolName(String name) {
            this.toolName = name;
            return this;
        }

        /** Accepts an {@link ItemStack}, an array, or an {@link Iterable} of encoded patterns. */
        public Builder patterns(Object patterns) {
            this.patterns = patterns;
            return this;
        }

        /** As {@link #patterns(Object)}, with a registry provider for full pattern serialisation. */
        public Builder patterns(Object patterns, HolderLookup.Provider registries) {
            this.patterns = patterns;
            this.registries = registries == null ? RegistryAccess.EMPTY : registries;
            return this;
        }

        /** Base64 payload produced by {@link #payloadBase64(Object)} or the exporter. */
        public Builder base64Payload(String base64) {
            this.base64Payload = base64;
            return this;
        }

        public Builder replaceMode(boolean replace) {
            this.replaceMode = replace;
            return this;
        }

        public PatternToolItem build() {
            CompoundTag data = new CompoundTag();
            CompoundTag payload = null;
            List<ItemStack> stacks = copyStacks(patterns);
            if (!stacks.isEmpty()) {
                payload = PatternToolData.payloadTagFromStacks(stacks, registries);
                if (payload == null) {
                    throw new IllegalArgumentException("Pattern tool payload is too large or cannot be encoded");
                }
            } else if (base64Payload != null && !base64Payload.isBlank()) {
                payload = PatternToolData.payloadTag(base64Payload);
            }
            if (payload != null) data.merge(payload);
            PatternToolData.putReplaceMode(data, replaceMode);
            if (!data.isEmpty()) {
                properties.component(DataComponents.CUSTOM_DATA, CustomData.of(data));
            }
            if (toolName != null && !toolName.isBlank()) {
                properties.component(DataComponents.CUSTOM_NAME, Component.literal(toolName));
            }
            return new PatternToolItem(properties, copyTool);
        }
    }
}
