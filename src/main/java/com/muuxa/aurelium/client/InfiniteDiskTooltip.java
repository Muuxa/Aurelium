package com.muuxa.aurelium.client;

import com.mojang.datafixers.util.Either;
import com.muuxa.aurelium.network.DiskSummaryPage;
import com.muuxa.aurelium.network.DiskSummaryRequest;
import com.muuxa.aurelium.storage.InfiniteDiskInventory;
import com.muuxa.aurelium.storage.InfiniteDiskItem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Client-side handler for the infinite disk tooltip.
 *
 * <p>Two jobs:</p>
 * <ul>
 *   <li>Colour-cycle the item name (炫彩律动), like the infinite cell.</li>
 *   <li>Show the authoritative <b>server-side</b> summary. The disk's contents live in
 *       {@code disk_cells_<uuid>.nbt} on the server, so the client cannot read them directly — it
 *       asks the server with {@link DiskSummaryRequest} and renders the {@link DiskSummaryPage} it
 *       gets back (the same pattern the star vault uses).</li>
 * </ul>
 */
public final class InfiniteDiskTooltip {
    private InfiniteDiskTooltip() {}

    private static final long REFRESH_MS = 1000L;

    private static UUID visibleId;
    private static long lastRequestMs;
    private static Component totalLine = null;   // null = no answer yet
    private static Component typesLine = null;
    private static Component specialLine = null;
    private static String answerKey = "";

    /** Called by {@link DiskSummaryPage} when the server answers. */
    public static void receiveSummary(DiskSummaryPage page) {
        if (visibleId == null || !visibleId.equals(page.id())) return;
        String key = page.total() + ":" + page.types() + ":" + page.itemTypes()
                + ":" + page.fluidTypes() + ":" + page.otherTypes();
        if (key.equals(answerKey)) return;
        answerKey = key;
        totalLine = Component.translatable("item.aurelium.infinite_disk.amount", page.total(), "\u00a7d\u221e");
        typesLine = Component.translatable("item.aurelium.infinite_disk.types",
                page.types(), page.itemTypes(), page.fluidTypes());
        specialLine = com.muuxa.aurelium.compat.AureliumCompat.hasAnyAddon()
                ? Component.translatable("item.aurelium.infinite_disk.special", page.otherTypes())
                : null;
    }

    @SubscribeEvent
    public static void onGather(RenderTooltipEvent.GatherComponents event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof InfiniteDiskItem)) return;
        if (event.getTooltipElements().isEmpty()) return;
        var first = event.getTooltipElements().get(0);
        if (first.left().isPresent()) {
            event.getTooltipElements().set(0,
                    Either.left(InfiniteTooltip.shimmer(stack.getHoverName().getString(), System.currentTimeMillis())));
        }
    }

    /**
     * The disk item's own {@code appendHoverText} runs on the client and cannot see server world
     * data, so we replace its (always-empty) summary lines here with the server answer — and drive
     * the refresh while the disk is hovered.
     */
    @SubscribeEvent
    public static void addSummary(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof InfiniteDiskItem)) {
            if (visibleId != null) clear();
            return;
        }
        UUID id = InfiniteDiskInventory.existingId(stack);
        long now = System.currentTimeMillis();

        if (id == null) { clear(); return; }
        if (!id.equals(visibleId)) {
            visibleId = id;
            answerKey = "";
            totalLine = typesLine = specialLine = null;
            lastRequestMs = 0L;
        }
        // Ask the server for the fresh summary, at most once per second.
        if (Minecraft.getInstance().player != null && now - lastRequestMs >= REFRESH_MS) {
            lastRequestMs = now;
            PacketDistributor.sendToServer(new DiskSummaryRequest(id));
        }

        var lines = event.getToolTip();
        // Drop the client-side (empty) summary lines the item appended; we re-add the real ones.
        lines.removeIf(InfiniteDiskTooltip::isSummaryLine);
        int at = Math.min(1, lines.size());
        if (totalLine != null) {
            lines.add(at, totalLine);
            lines.add(at + 1, typesLine);
            if (specialLine != null) lines.add(at + 2, specialLine);
        } else {
            lines.add(at, Component.translatable("item.aurelium.infinite_disk.loading"));
        }
    }

    /** True for the two/three summary lines, matched by their (unique) prefixes. */
    private static boolean isSummaryLine(Component line) {
        String s = line.getString();
        return s.startsWith("\u00a7b\u2727 \u5df2\u5b58")   // "§b✧ 已存"
                || s.startsWith("\u00a77\u79cd\u7c7b")       // "§7种类"
                || s.startsWith("\u00a7d\u7279\u6b8a");      // "§d特殊"
    }

    private static void clear() {
        visibleId = null;
        answerKey = "";
        totalLine = typesLine = specialLine = null;
        lastRequestMs = 0L;
    }
}