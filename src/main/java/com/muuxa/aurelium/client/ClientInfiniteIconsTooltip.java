package com.muuxa.aurelium.client;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.muuxa.aurelium.storage.KeyCodec;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders {@link InfiniteIconsTooltip}: a row of item icons, each with {@code ∞} at the
 * icon's bottom-right corner — the look asked for (an icon labelled ∞, not an item name).
 *
 * <p>Only {@code getHeight}/{@code getWidth}/{@code renderImage} are overridden;
 * {@code renderText} is left as the interface default (we draw no text line).</p>
 *
 * <p>Every AEKey type is handled: items render as themselves (including items carrying
 * components, via {@code key:<SNBT>}), fluids as their bucket, and any other key (e.g. a mod
 * chemical with no item form) as a neutral placeholder so it is still counted in the row.</p>
 */
public final class ClientInfiniteIconsTooltip implements ClientTooltipComponent {
    private static final int SPACING = 18;
    private final List<ItemStack> icons;

    public ClientInfiniteIconsTooltip(InfiniteIconsTooltip data) {
        List<ItemStack> resolved = new ArrayList<>();
        for (String id : data.itemIds()) {
            ItemStack icon = resolveIcon(id);
            if (icon != null && !icon.isEmpty()) resolved.add(icon);
        }
        this.icons = resolved;
    }

    private static ItemStack resolveIcon(String token) {
        if (token == null) return null;
        // Decode through the shared codec so ALL key types resolve (item / item-with-components /
        // fluid / generic key). Needs registries, which exist on both client kinds.
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            try {
                AEKey key = KeyCodec.decode(token, server.registryAccess());
                if (key instanceof AEItemKey itemKey) {
                    return itemKey.toStack();
                }
                if (key instanceof AEFluidKey fluidKey) {
                    var bucket = fluidKey.getFluid().getBucket();
                    return new ItemStack(bucket == Items.AIR ? Items.WATER_BUCKET : bucket);
                }
                if (key != null) {
                    // Exotic key (e.g. a mod chemical) with no item form: neutral placeholder.
                    return new ItemStack(Items.STRUCTURE_VOID);
                }
            } catch (RuntimeException ignored) {
                // fall through to the plain-id path below
            }
        }
        if (token.startsWith("fluid:") || token.startsWith("key:")) return null;
        ResourceLocation loc = ResourceLocation.tryParse(token);
        if (loc == null || !BuiltInRegistries.ITEM.containsKey(loc)) return null;
        return new ItemStack(BuiltInRegistries.ITEM.get(loc));
    }

    @Override
    public int getHeight() {
        return SPACING;
    }

    @Override
    public int getWidth(Font font) {
        return Math.max(0, icons.size()) * SPACING;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        int cx = x + 1;
        for (ItemStack icon : icons) {
            graphics.renderItem(icon, cx, y + 1);
            // "∞" at the icon's bottom-right, exactly like a stack-size label.
            graphics.renderItemDecorations(font, icon, cx, y + 1, "∞");
            cx += SPACING;
        }
    }
}