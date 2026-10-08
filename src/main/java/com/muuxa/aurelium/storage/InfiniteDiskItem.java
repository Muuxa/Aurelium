package com.muuxa.aurelium.storage;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * AURELIUM infinite disk: a drive-mounted storage cell backed by {@link InfiniteDiskInventory}.
 *
 * <p>The stored amounts live in server-side world data ({@code disk_cells_<uuid>.nbt}), so the
 * total/type summary is fetched over the network while the disk is hovered and drawn by the client
 * {@code InfiniteDiskTooltip} — never computed here, because this method also runs on the client
 * where the contents are not available. Only the flavour lines belong on the item.</p>
 */
public class InfiniteDiskItem extends Item {

    public InfiniteDiskItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, lines, flag);
        // Colour comes from the §-codes on the language strings; never also apply withStyle(), or the
        // style layer overrides the inline colour.
        lines.add(Component.literal("§d✿ 把无尽的温柔，轻轻收进这片粉色星河。"));
        lines.add(Component.literal("§d✧ 送入磁盘的一切，都会被温柔收好。"));
    }
}