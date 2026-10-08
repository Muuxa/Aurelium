package com.muuxa.aurelium.client;

import com.muuxa.aurelium.storage.InfiniteCellItem;
import com.muuxa.aurelium.storage.InfiniteDiskItem;
import com.muuxa.aurelium.storage.StarVaultItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/**
 * Exact GUI hover border, not a texture overlay and not the inventory slot outline.
 *
 * <p>Applies to every AURELIUM cell — the star vault, the infinite cell AND the infinite disk —
 * so hovering any of them in an inventory shows the same rose-pink border.</p>
 */
public final class PinkTooltip {
    private PinkTooltip() {}

    @SubscribeEvent
    public static void color(RenderTooltipEvent.Color event) {
        if (!(event.getItemStack().getItem() instanceof StarVaultItem)
                && !(event.getItemStack().getItem() instanceof InfiniteCellItem)
                && !(event.getItemStack().getItem() instanceof InfiniteDiskItem)) return;
        event.setBackgroundStart(0xF025172E);
        event.setBackgroundEnd(0xF0331D39);
        event.setBorderStart(0xFFFFD0EB);
        event.setBorderEnd(0xFFCF70AA);
    }
}