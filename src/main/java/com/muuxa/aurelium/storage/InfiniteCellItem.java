package com.muuxa.aurelium.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The infinite cell item. Behaviour lives in {@link InfiniteCellInventory}; this class
 * only supplies the item id and name. Tooltip rendering is done client-side by an event
 * handler so the server side stays free of GUI code.
 *
 * <p><b>It intentionally does NOT implement {@code ICellWorkbenchItem} / {@code IBasicCellItem}
 * / {@code ICellWorkbenchItem#getConfigInventory}.</b> That means AE2's Cell Workbench shows
 * the cell as non-editable, so a player cannot hand-mark items as infinite with a workbench.
 * The set of infinite items is declared only through the API/KubeJS
 * ({@code AureliumInfinite.register(...)}), as required.</p>
 *
 * <p>Name resolution order: an explicit {@code CUSTOM_NAME} component (set by the API or an
 * anvil) wins; otherwise a custom title declared for the cell's kind; otherwise the item's
 * default translatable name. This keeps the bundled concrete preset distinguishable in the
 * creative tab without stamping an unwanted custom name on every infinite cell.</p>
 */
public final class InfiniteCellItem extends Item {
    public InfiniteCellItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        // 1) An explicit custom name (API-created presets, anvil renames) always wins.
        Component custom = stack.get(DataComponents.CUSTOM_NAME);
        if (custom != null) return custom;
        // 2) Otherwise honour a kind-declared title.
        String kind = InfiniteCellInventory.kindId(stack);
        var spec = kind == null ? null : com.muuxa.aurelium.api.AureliumInfinite.get(kind);
        if (spec != null && spec.hasCustomTitle()) return Component.literal(spec.title());
        // 3) Fall back to the item's default translatable name.
        return super.getName(stack);
    }
}