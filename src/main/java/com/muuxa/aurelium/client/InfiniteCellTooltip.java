package com.muuxa.aurelium.client;

import com.mojang.datafixers.util.Either;
import com.muuxa.aurelium.api.AureliumInfinite;
import com.muuxa.aurelium.api.InfiniteKindSpec;
import com.muuxa.aurelium.storage.InfiniteCellInventory;
import com.muuxa.aurelium.storage.InfiniteCellItem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/**
 * Adds the pink flavour text and the "icon + ∞" row to infinite cell tooltips.
 *
 * <p>Uses {@link RenderTooltipEvent.GatherComponents} (not {@code ItemTooltipEvent}) because
 * a custom {@link TooltipComponent} — the icon row — can only be injected while the tooltip
 * parts are being gathered.</p>
 *
 * <p>Registered manually from {@link AureliumClient} on the game event bus.</p>
 */
public final class InfiniteCellTooltip {
    private InfiniteCellTooltip() {}

    @SubscribeEvent
    public static void onGather(RenderTooltipEvent.GatherComponents event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof InfiniteCellItem)) return;

        String kind = InfiniteCellInventory.kindId(stack);
        InfiniteKindSpec spec = kind == null ? null : AureliumInfinite.get(kind);

        // Colour-cycle the item name when the kind asks for it (spec.shimmer()); this works
        // whether or not the item carries a custom name. When shimmer is off, the name is
        // left exactly as the game produced it.
        String title = (spec != null && spec.hasCustomTitle())
                ? spec.title()
                : stack.getHoverName().getString();
        boolean wantShimmer = spec != null && spec.shimmer();
        if (wantShimmer && !event.getTooltipElements().isEmpty()) {
            var first = event.getTooltipElements().get(0);
            if (first.left().isPresent()) {
                event.getTooltipElements().set(0,
                        com.mojang.datafixers.util.Either.left(
                                InfiniteTooltip.shimmer(title, System.currentTimeMillis())));
            }
        }

        if (spec == null) {
            event.getTooltipElements().add(Either.left(
                    Component.literal("§8✧ 尚未指定无限内容（等待这份偏爱被轻轻写下）")));
            return;
        }

        // describe == false: show only the title (already shimmered above); add nothing.
        if (!spec.describe()) return;

        event.getTooltipElements().add(Either.left(Component.literal("§d✧ 无限种类：")
                .append(Component.literal(spec.typeCount() + " 种").withStyle(ChatFormatting.WHITE))));
        event.getTooltipElements().add(Either.left(Component.literal("§7可无限取出：")));
        // The icon row: each item drawn with ∞ at its bottom-right corner.
        event.getTooltipElements().add(Either.right(new InfiniteIconsTooltip(spec.items())));
        InfiniteTooltip.appendItemText(event.getTooltipElements());
    }
}