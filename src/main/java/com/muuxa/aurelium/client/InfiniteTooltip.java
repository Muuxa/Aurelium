package com.muuxa.aurelium.client;

import com.mojang.datafixers.util.Either;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

import java.util.List;

/**
 * Client-side text helpers for infinite cells: the pink shimmer line and the shared
 * flavour block. Writes directly into a tooltip element list so it composes with the
 * custom icon-row component.
 */
public final class InfiniteTooltip {
    private InfiniteTooltip() {}

    private static final int[] PINK = {0xFF8FC6, 0xFFD2EB, 0xDFA5FB, 0xFFEEF9};

    /** Colour-cycling text for the "无限的星光" flourish. */
    public static MutableComponent shimmer(String text, long timeMs) {
        MutableComponent result = Component.empty();
        int phase = (int) ((timeMs / 300L) % PINK.length);
        for (int i = 0; i < text.length(); i++) {
            result.append(Component.literal(text.substring(i, i + 1))
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(PINK[(i + phase) % PINK.length]))));
        }
        return result;
    }

    /**
     * Append the flavour block shared by infinite cell items. Soft, pink, non-edgy,
     * matching the vault's tone.
     */
    public static void appendItemText(List<Either<FormattedText, TooltipComponent>> lines) {
        long timeMs = System.currentTimeMillis();
        lines.add(Either.left(Component.empty()));
        lines.add(Either.left(Component.literal("✿ 把无尽的温柔，轻轻收进这片粉色星河。")
                .withStyle(ChatFormatting.LIGHT_PURPLE)));
        lines.add(Either.left(Component.literal("§d✧ 被标记的物品取之不尽，")
                .append(Component.literal("送进来的会化作星光散开。").withStyle(ChatFormatting.GRAY))));
        lines.add(Either.left(shimmer("✧ 无限取出 · ∞ · 取之不尽 ✧", timeMs)));
        lines.add(Either.left(Component.literal("§8✧ 这份「无尽的偏爱」，自会悄悄认得你")
                .withStyle(ChatFormatting.DARK_GRAY)));
    }
}