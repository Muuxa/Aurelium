package com.muuxa.aurelium.client.mixin;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.core.localization.ButtonToolTips;
import appeng.core.localization.Tooltips;
import com.muuxa.aurelium.client.ClientInfiniteKeys;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Renders {@code ∞} instead of a huge number in AE2 terminals for keys served by an
 * AURELIUM infinite cell — both the slot label and the hover tooltip amount line.
 *
 * <p>Detection is deliberately narrow: a key counts as infinite only when its reported
 * amount is exactly {@link Long#MAX_VALUE} and its id is declared by a registered kind.
 * Ordinary cells therefore keep their normal display.</p>
 */
@Mixin(MEStorageScreen.class)
public abstract class MEStorageScreenInfinityMixin {
    @Redirect(
            method = "renderSlot",
            at = @At(value = "INVOKE",
                    target = "Lappeng/api/stacks/AEKey;formatAmount(JLappeng/api/stacks/AmountFormat;)Ljava/lang/String;"))
    private String aurelium$infiniteAmount(AEKey key, long amount, AmountFormat format) {
        if (amount >= com.muuxa.aurelium.storage.InfiniteCellInventory.REPORTED_AMOUNT
                && ClientInfiniteKeys.isInfinite(key)) {
            return "∞";
        }
        return key.formatAmount(amount, format);
    }

    @Redirect(
            method = "renderGridInventoryEntryTooltip",
            at = @At(value = "INVOKE",
                    target = "Lappeng/core/localization/Tooltips;getAmountTooltip(Lappeng/core/localization/ButtonToolTips;Lappeng/api/stacks/AEKey;J)Lnet/minecraft/network/chat/Component;"))
    private Component aurelium$infiniteTooltipAmount(ButtonToolTips base, AEKey what, long amount) {
        if (amount >= com.muuxa.aurelium.storage.InfiniteCellInventory.REPORTED_AMOUNT
                && ClientInfiniteKeys.isInfinite(what)) {
            return Component.literal("∞").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE);
        }
        return Tooltips.getAmountTooltip(base, what, amount);
    }
}