package com.muuxa.aurelium.client;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

import java.util.List;

/**
 * Tooltip component carrying the item ids an infinite cell declares. Rendering draws one
 * item icon per id with {@code ∞} overlaid at the icon's bottom-right corner.
 *
 * <p>Holds plain id strings (not live ItemStacks) so it is trivially safe to build on the
 * client thread while collecting tooltip parts.</p>
 */
public record InfiniteIconsTooltip(List<String> itemIds) implements TooltipComponent {
    public InfiniteIconsTooltip {
        itemIds = List.copyOf(itemIds);
    }
}