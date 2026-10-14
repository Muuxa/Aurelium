package com.muuxa.aurelium.client;

import com.mojang.datafixers.util.Either;
import com.muuxa.aurelium.api.AureliumInfinite;
import com.muuxa.aurelium.api.InfiniteKindSpec;
import com.muuxa.aurelium.storage.InfiniteCellInventory;
import com.muuxa.aurelium.storage.InfiniteCellItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.List;

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

    private static final int PAGE_COLUMNS = 10;
    private static final int PAGE_ROWS = 10;
    private static final int PAGE_SIZE = PAGE_COLUMNS * PAGE_ROWS;

    private static String visibleKind;
    private static int pageStart;
    private static int tooltipX, tooltipY, tooltipWidth, tooltipHeight;
    private static long lastLocatedMs;

    @SubscribeEvent
    public static void locate(RenderTooltipEvent.Pre event) {
        if (!(event.getItemStack().getItem() instanceof InfiniteCellItem)) return;
        tooltipX = event.getX();
        tooltipY = event.getY();
        tooltipWidth = PAGE_COLUMNS * 18 + 16;
        tooltipHeight = PAGE_ROWS * 18 + 48;
        lastLocatedMs = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void scroll(ScreenEvent.MouseScrolled.Pre event) {
        if (System.currentTimeMillis() - lastLocatedMs > 400L || !Screen.hasShiftDown()
                || visibleKind == null) return;
        if (event.getMouseX() < tooltipX - 32 || event.getMouseX() > tooltipX + tooltipWidth
                || event.getMouseY() < tooltipY - 20 || event.getMouseY() > tooltipY + tooltipHeight) return;
        int move = event.getScrollDeltaY() > 0 ? -PAGE_SIZE
                : event.getScrollDeltaY() < 0 ? PAGE_SIZE : 0;
        if (move == 0) return;
        int next = Math.max(0, pageStart + move);
        if (next == pageStart) return;
        pageStart = next;
        event.setCanceled(true);
    }

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
            visibleKind = null;
            pageStart = 0;
            event.getTooltipElements().add(Either.left(
                    Component.literal("§8✧ 尚未指定无限内容（等待这份偏爱被轻轻写下）")));
            return;
        }

        // describe == false: show only the title (already shimmered above); add nothing.
        if (!spec.describe()) return;

        event.getTooltipElements().add(Either.left(Component.literal("§d✧ 无限种类：")
                .append(Component.literal(spec.typeCount() + " 种").withStyle(ChatFormatting.WHITE))));
        event.getTooltipElements().add(Either.left(Component.literal("§7可无限取出：")));
        List<String> items = spec.items();
        if (!java.util.Objects.equals(visibleKind, kind)) {
            visibleKind = kind;
            pageStart = 0;
        }
        int lastPage = lastPageStart(items.size());
        if (pageStart > lastPage) pageStart = lastPage;
        int end = Math.min(items.size(), pageStart + PAGE_SIZE);
        event.getTooltipElements().add(Either.right(
                new InfiniteIconsTooltip(items.subList(pageStart, end))));
        if (items.size() > PAGE_SIZE) {
            event.getTooltipElements().add(Either.left(
                    Component.translatable("item.aurelium.infinite_cell.page",
                            pageStart + 1, end, items.size())));
        }
        InfiniteTooltip.appendItemText(event.getTooltipElements());
    }

    private static int lastPageStart(int total) {
        return total <= 0 ? 0 : ((total - 1) / PAGE_SIZE) * PAGE_SIZE;
    }
}
