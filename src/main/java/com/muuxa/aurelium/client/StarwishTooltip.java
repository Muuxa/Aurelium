package com.muuxa.aurelium.client;

import com.muuxa.aurelium.storage.StarVaultItem;
import com.muuxa.aurelium.storage.VaultBytes;
import com.muuxa.aurelium.storage.VaultInventory;
import com.muuxa.aurelium.network.VaultPreviewRequest;
import com.muuxa.aurelium.network.VaultPreviewPage;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.math.BigInteger;
import java.util.UUID;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import java.util.List;

/** Client only: cached rose palette and server-synchronised summary, never scan world SavedData. */
public final class StarwishTooltip {
    private StarwishTooltip() {}

    private static final int[] PINK = {0xFF8FC6, 0xFFD2EB, 0xDFA5FB, 0xFFEEF9};
    private static boolean shiftHeld;
    private static long shiftStart;
    private static long lastTooltipMs;
    private static int lastPhase = -1;
    private static Language lastLanguage;
    private static String extendedFirst = "";
    private static String extendedSecond = "";
    // Rebuild title and lore only when the palette phase advances.
    private static MutableComponent coloredTitle = Component.empty();
    private static MutableComponent coloredLore = Component.empty();
    private static String lastFirst = "";
    private static String lastSecond = "";
    private static Component revealedFirst = Component.empty();
    private static Component revealedSecond = Component.empty();
    private static Object lastStackData;
    private static String lastStats = "";
    private static Component statsLine = Component.empty();
    private static UUID visibleId;
    private static UUID lastStatsId;
    private static int visibleTypes = -1;
    private static VaultPreviewPage preview;
    private static int pageStart;
    private static int requestedOffset = -1;
    private static long lastRequestMs;
    private static long lastPreviewMs;
    private static long lastSummaryMs;
    private static long lastRemoteSummaryMs;
    private static String remoteStatsKey = "";
    private static long lastHoverMs;
    private static int tooltipX, tooltipY, tooltipWidth, tooltipHeight;
    private static long lastLocatedMs;
    private static final int PAGE_SIZE = 10;
    private static final long PREVIEW_REFRESH_MS = 1000L;

    public static void receivePage(VaultPreviewPage page) {
        if (visibleId != null && visibleId.equals(page.id()) && requestedOffset == page.offset()) {
            int lastPage = lastPageStart(page.total());
            String nextStats = page.usedBytes() + ":" + page.itemTypes() + ":"
                    + page.fluidTypes() + ":" + page.total();
            if (page.total() == 0) {
                statsLine = Component.empty();
                lastStats = "empty";
                remoteStatsKey = "empty";
            } else if (!nextStats.equals(remoteStatsKey)) {
                // usedBytes is already a bounded, server-formatted display string.
                statsLine = Component.translatable("item.aurelium.star_vault.stats",
                        page.usedBytes(), page.itemTypes(),
                        page.fluidTypes(), Math.max(0, page.total() - page.itemTypes() - page.fluidTypes()));
                remoteStatsKey = nextStats;
            }
            lastRemoteSummaryMs = System.currentTimeMillis();
            if (pageStart > lastPage) {
                pageStart = lastPage;
                requestedOffset = -1;
                preview = null;
            } else {
                preview = page;
            }
            lastPreviewMs = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    public static void locate(RenderTooltipEvent.Pre event) {
        if (!(event.getItemStack().getItem() instanceof StarVaultItem)) return;
        tooltipX = event.getX();
        tooltipY = event.getY();
        tooltipWidth = 320;
        tooltipHeight = Math.min(210, event.getScreenHeight() - event.getY());
        lastLocatedMs = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void scroll(ScreenEvent.MouseScrolled.Pre event) {
        if (System.currentTimeMillis() - lastLocatedMs > 400L || !Screen.hasShiftDown()
                || preview == null || visibleId == null) return;
        // Vanilla places the tooltip ~12 px to the RIGHT of the hovered item.
        // Include that small gap so scrolling while the cursor stays on the
        // actual inventory slot works (moving over the tooltip loses hover).
        if (event.getMouseX() < tooltipX - 32 || event.getMouseX() > tooltipX + tooltipWidth
                || event.getMouseY() < tooltipY - 20 || event.getMouseY() > tooltipY + tooltipHeight) return;
        int move = event.getScrollDeltaY() > 0 ? -PAGE_SIZE
                : event.getScrollDeltaY() < 0 ? PAGE_SIZE : 0;
        if (move == 0) return;
        int next = (int) Math.max(0L, Math.min((long) lastPageStart(preview.total()),
                (long) pageStart + move));
        if (next == pageStart) return;
        pageStart = next;
        // Do not show any row from the previous page while waiting for the next page.
        event.setCanceled(true);
    }

    private static boolean visibleIdEquals(ItemStack stack) {
        UUID id = VaultInventory.existingId(stack);
        return id != null && id.equals(visibleId);
    }

    private static int lastPageStart(int total) {
        return total <= 0 ? 0 : ((total - 1) / PAGE_SIZE) * PAGE_SIZE;
    }

    @SubscribeEvent
    public static void addLore(ItemTooltipEvent event) {
        if (!(event.getItemStack().getItem() instanceof StarVaultItem)) {
            shiftHeld = false;
            return;
        }
        long now = System.currentTimeMillis();
        UUID hoveredId = VaultInventory.existingId(event.getItemStack());
        if (!java.util.Objects.equals(hoveredId, lastStatsId)) {
            // Never reuse another vault's cached statistics while switching hover.
            lastStatsId = hoveredId;
            lastStackData = null;
            lastStats = "";
            statsLine = Component.empty();
            remoteStatsKey = "";
            lastRemoteSummaryMs = 0L;
        }
        boolean shift = Screen.hasShiftDown();
        if (shift && (!shiftHeld || now - lastTooltipMs > 350L)) shiftStart = now;
        shiftHeld = shift;
        lastTooltipMs = now;
        List<Component> lines = event.getToolTip();

        // Stable colored fragments between updates instead of hundreds of components per frame.
        int phase = (int) ((now / 350L) % PINK.length);
        Language language = Language.getInstance();
        if (phase != lastPhase || language != lastLanguage) {
            String title = Component.translatable("item.aurelium.star_vault").getString();
            String lore = Component.translatable("item.aurelium.star_vault.lore1").getString();
            coloredTitle = wave(title, phase, true);
            coloredLore = wave(lore, phase, false);
            if (language != lastLanguage) {
                extendedFirst = Component.translatable("item.aurelium.star_vault.lore4").getString();
                extendedSecond = Component.translatable("item.aurelium.star_vault.lore5").getString();
                lastFirst = "";
                lastSecond = "";
            }
            lastPhase = phase;
            lastLanguage = language;
        }
        if (!lines.isEmpty() && event.getItemStack().get(DataComponents.CUSTOM_NAME) == null) {
            lines.set(0, coloredTitle);
        }
        lines.add(Component.empty());
        lines.add(coloredLore);
        lines.add(Component.translatable("item.aurelium.star_vault.lore2"));
        lines.add(Component.translatable("item.aurelium.star_vault.lore3"));

        Object stackData = event.getItemStack().get(DataComponents.CUSTOM_DATA);
        if (stackData != lastStackData || lastStats.isEmpty()
                || now - lastSummaryMs >= PREVIEW_REFRESH_MS) {
            VaultInventory.Summary stats = VaultInventory.cachedSummary(event.getItemStack());
            String next = stats.known() && stats.types() > 0
                    ? stats.usedBytes() + ":" + stats.itemTypes() + ":" + stats.fluidTypes()
                        + ":" + stats.otherTypes() : "empty";
            if (!next.equals(lastStats)) {
                lastStats = next;
                // Local item data is a fallback. A fresh server page has the
                // authoritative count for cells mounted in a drive.
                if (now - lastRemoteSummaryMs > PREVIEW_REFRESH_MS * 2
                        || !visibleIdEquals(event.getItemStack())) {
                    statsLine = stats.known() && stats.types() > 0
                            ? Component.translatable("item.aurelium.star_vault.stats",
                                    VaultBytes.format(stats.usedBytes()), stats.itemTypes(),
                                    stats.fluidTypes(), stats.otherTypes())
                            : Component.empty();
                }
            }
            lastStackData = stackData;
            lastSummaryMs = now;
        }
        int statsPosition = lines.size();
        appendPreview(event.getItemStack(), lines, shift, now);
        boolean serverSummary = visibleIdEquals(event.getItemStack())
                && now - lastRemoteSummaryMs <= PREVIEW_REFRESH_MS * 2;
        if ((serverSummary && !remoteStatsKey.equals("empty")
                || !serverSummary && !lastStats.equals("empty"))
                && !statsLine.equals(Component.empty())) {
            lines.add(statsPosition, statsLine);
        }
        if (!shift) {
            lines.add(Component.translatable("item.aurelium.star_vault.hint"));
            return;
        }
        String first = extendedFirst;
        String second = extendedSecond;
        int shown = (int) Math.min(Math.max(0L, now - shiftStart) / 65L, first.length() + second.length());
        String firstPart = first.substring(0, Math.min(shown, first.length()));
        if (!firstPart.equals(lastFirst)) {
            revealedFirst = Component.literal(firstPart).withStyle(Style.EMPTY.withColor(0xFFD6EB));
            lastFirst = firstPart;
        }
        if (!firstPart.isEmpty()) lines.add(revealedFirst);
        if (shown > first.length()) {
            String secondPart = second.substring(0, shown - first.length());
            if (!secondPart.equals(lastSecond)) {
                revealedSecond = Component.literal(secondPart).withStyle(Style.EMPTY.withColor(0xFFF1FC));
                lastSecond = secondPart;
            }
            lines.add(revealedSecond);
        }
    }
    private static void appendPreview(ItemStack stack, List<Component> lines, boolean shift, long now) {
        UUID id = VaultInventory.existingId(stack);
        if (id == null || Minecraft.getInstance().player == null) return;
        boolean returning = now - lastHoverMs > 1200L;
        lastHoverMs = now;
        VaultInventory.Summary summary = VaultInventory.cachedSummary(stack);
        // Even an apparently empty item may be mounted in a drive and changed on
        // the server; a cached 0 / 0 must not suppress periodic refreshes.
        boolean owned = false;
        for (ItemStack ownedStack : Minecraft.getInstance().player.getInventory().items) {
            if (id.equals(VaultInventory.existingId(ownedStack))) { owned = true; break; }
        }
        if (!owned && id.equals(VaultInventory.existingId(Minecraft.getInstance().player.getOffhandItem()))) {
            owned = true;
        }
        if (!owned && Minecraft.getInstance().player.containerMenu != null) {
            for (var slot : Minecraft.getInstance().player.containerMenu.slots) {
                if (id.equals(VaultInventory.existingId(slot.getItem()))) { owned = true; break; }
            }
        }
        if (!owned) return;
        if (returning || !id.equals(visibleId)) {
            visibleId = id;
            visibleTypes = summary.known() ? summary.types() : -1;
            remoteStatsKey = "";
            lastRemoteSummaryMs = 0L;
            statsLine = Component.empty();
            preview = null;
            pageStart = 0;
            requestedOffset = -1;
        } else if (summary.known() && summary.types() != visibleTypes) {
            visibleTypes = summary.types();
            // The server page may already be newer than the item CustomData.
            // Keep the selected page until the authoritative page arrives.
        }
        int offset = shift ? pageStart : 0;
        boolean changedPage = requestedOffset != offset;
        boolean refreshDue = now - lastPreviewMs >= PREVIEW_REFRESH_MS;
        if ((changedPage && now - lastRequestMs >= 100L)
                || (!changedPage && refreshDue && now - lastRequestMs >= PREVIEW_REFRESH_MS)) {
            requestedOffset = offset;
            lastRequestMs = now;
            PacketDistributor.sendToServer(new VaultPreviewRequest(id, offset));
        }
        VaultPreviewPage page = preview;
        if (page == null || page.offset() != offset) {
            if (shift) lines.add(Component.translatable("item.aurelium.star_vault.loading"));
            return;
        }
        if (page.total() <= 0) return;
        lines.add(Component.translatable("item.aurelium.star_vault.contents").withStyle(Style.EMPTY.withColor(0xFFB6D9)));
        int count = shift ? PAGE_SIZE : Math.min(5, page.entries().size());
        for (int i = 0; i < Math.min(count, page.entries().size()); i++) {
            VaultPreviewPage.Entry row = page.entries().get(i);
            String amount;
            if (row.quantity().equals("infinity") || row.quantity().equals("large")) {
                // A vault count at or past 10^26 (long + 8 digits) is displayed as ∞.
                // This is a display shorthand only; the vault is not a real infinite source.
                lines.add(Component.literal("  ").append(Component.translatable("item.aurelium.star_vault.infinity"))
                        .append(Component.literal(" × " + row.name()))
                        .withStyle(Style.EMPTY.withColor(0xFFB6D9)));
                continue;
            }
            try { amount = group(new BigInteger(row.quantity())); }
            catch (NumberFormatException badPacket) { amount = "?"; }
            lines.add(Component.literal("  " + amount + " × " + row.name())
                    .withStyle(Style.EMPTY.withColor(0xFFE4BD)));
        }
        if (shift) {
            int end = offset + page.entries().size();
            lines.add(Component.translatable("item.aurelium.star_vault.page",
                    offset + 1, end, page.total()).withStyle(Style.EMPTY.withColor(0xBDA3C5)));
        } else if (page.total() > 5) {
            lines.add(Component.translatable("item.aurelium.star_vault.more",
                    page.total() - 5).withStyle(Style.EMPTY.withColor(0xBDA3C5)));
        }
    }

    private static String group(BigInteger amount) {
        String text = amount.toString();
        if (text.length() > 150) return text.substring(0, 32) + "… (" + text.length() + " digits)";
        StringBuilder result = new StringBuilder(text.length() + text.length() / 3);
        for (int i = 0; i < text.length(); i++) {
            if (i > 0 && (text.length() - i) % 3 == 0) result.append(',');
            result.append(text.charAt(i));
        }
        return result.toString();
    }

    private static MutableComponent wave(String text, int phase, boolean bold) {
        MutableComponent result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            result.append(Component.literal(text.substring(i, i + 1))
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(PINK[(i + phase) % PINK.length]))
                            .withBold(bold)));
        }
        return result;
    }
}