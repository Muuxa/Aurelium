package com.muuxa.aurelium.api;

import com.muuxa.aurelium.storage.InfiniteCellInventory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Public KubeJS bridge for infinite cells.
 *
 * <p>Each kind names its own backing item (see {@link AureliumInfinite#register}); there is no
 * shared default. The backing item must be {@code aurelium:infinite_cell}-typed and registered
 * before this is called (i.e. after the registry phase, e.g. in a server script).</p>
 */
public final class AureliumInfiniteCellAPI {
    private AureliumInfiniteCellAPI() {}

    /** The generic (blank) infinite cell item id; a convenience, not a default for kinds. */
    public static String itemId() { return "aurelium:infinite_cell"; }

    /**
     * Create an infinite cell stack for a previously registered kind, using that kind's
     * backing item id.
     *
     * @throws IllegalArgumentException when the kind is unknown or its item is not registered yet
     */
    public static ItemStack create(String kindId) {
        InfiniteKindSpec spec = AureliumInfinite.get(kindId);
        if (spec == null) {
            throw new IllegalArgumentException("Infinite kind not registered: " + kindId);
        }
        ResourceLocation id = ResourceLocation.tryParse(spec.itemId());
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalStateException(
                    "Infinite kind '" + kindId + "' names item '" + spec.itemId()
                            + "', which is not registered yet");
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        ItemStack cell = new ItemStack(item);
        InfiniteCellInventory.setKindId(cell, kindId);
        // Only set a custom name when the registration supplied one.
        if (spec.hasCustomTitle()) {
            cell.set(DataComponents.CUSTOM_NAME, Component.literal(spec.title()));
        }
        return cell;
    }

    /** The item id backing a kind (throws when unknown). */
    public static String itemIdFor(String kindId) {
        InfiniteKindSpec spec = AureliumInfinite.get(kindId);
        if (spec == null) throw new IllegalArgumentException("Infinite kind not registered: " + kindId);
        return spec.itemId();
    }

    /** NBT-only template (string), for recipes/JEI where no live stack is needed. */
    public static String templateTag(String kindId) {
        if (AureliumInfinite.get(kindId) == null) {
            throw new IllegalArgumentException("Infinite kind not registered: " + kindId);
        }
        return "{" + InfiniteCellInventory.KIND_TAG + ":\"" + kindId + "\"}";
    }

    /**
     * Set the kind on an existing infinite-cell stack (Java/API route, no KubeJS).
     * The stack must be an infinite cell item and the kind must be registered.
     */
    public static void setKind(ItemStack stack, String kindId) {
        if (stack == null || !(stack.getItem() instanceof com.muuxa.aurelium.storage.InfiniteCellItem)) {
            throw new IllegalArgumentException("Not an infinite cell item");
        }
        if (AureliumInfinite.get(kindId) == null) {
            throw new IllegalArgumentException("Infinite kind not registered: " + kindId);
        }
        InfiniteCellInventory.setKindId(stack, kindId);
    }

    /** Human-readable summary of a registered kind, for logging. */
    public static String describe(String kindId) {
        InfiniteKindSpec spec = AureliumInfinite.get(kindId);
        if (spec == null) throw new IllegalArgumentException("Infinite kind not registered: " + kindId);
        StringBuilder sb = new StringBuilder(spec.id())
                .append(" [item: ").append(spec.itemId()).append("] -> ")
                .append(spec.hasCustomTitle() ? spec.title() : "(default name)")
                .append(" (").append(spec.typeCount()).append(" items)");
        spec.items().forEach(id -> sb.append("\n  ").append(id));
        return sb.toString();
    }
}