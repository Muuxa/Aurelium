package com.muuxa.aurelium.export;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.muuxa.aurelium.Aurelium;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Path;

/**
 * Registers AURELIUM's commands:
 * <ul>
 *   <li>{@code /aurelium export [name]} — writes printable files for the held vault.</li>

 * </ul>
 *
 * <p>Auto-registered on NeoForge's game event bus via {@code @EventBusSubscriber} (the
 * no-{@code bus} form, which is not deprecated).</p>
 */
@EventBusSubscriber(modid = Aurelium.ID)
public final class AureliumCommands {
    private AureliumCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("aurelium")
                .then(Commands.literal("export")
                        .executes(ctx -> run(ctx.getSource(), "", false))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "name"), false))
                                .then(Commands.argument("replace",
                                                com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                        .executes(ctx -> run(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name"),
                                                com.mojang.brigadier.arguments.BoolArgumentType
                                                        .getBool(ctx, "replace"))))))

                .then(Commands.literal("infinite")
                        .executes(ctx -> listInfinite(ctx.getSource(), null))
                        .then(Commands.argument("kind", StringArgumentType.string())
                                .executes(ctx -> listInfinite(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "kind"))))));
    }

    /**
     * {@code /aurelium infinite [kind]} — the one-stop diagnosis for "I registered a kind but
     * nothing happened". For every registered kind it prints:
     * <ul>
     *   <li>whether the <b>backing item</b> actually exists in the registry (a missing item is
     *       the usual cause of a silently empty creative tab);</li>
     *   <li>for every declared token, whether it resolves to a live {@link appeng.api.stacks.AEKey}
     *       <b>right now</b> (a typo such as {@code appflux:flux} instead of
     *       {@code key:{"#t":"appflux:flux",type:"FE"}} shows up here).</li>
     * </ul>
     */
    private static int listInfinite(CommandSourceStack source, String onlyKind) {
        var server = source.getServer();
        var registries = server == null ? null : server.registryAccess();
        var all = com.muuxa.aurelium.api.AureliumInfinite.all();

        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "Aurelium：还没有任何无限元件种类被登记。请确认脚本放在 "
                            + "kubejs/startup_scripts/ 下，并【重启游戏】（startup 脚本 /reload 不生效）。")
                    .withStyle(net.minecraft.ChatFormatting.RED), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal(
                "Aurelium：已登记 " + all.size() + " 个无限元件种类。").withStyle(net.minecraft.ChatFormatting.AQUA),
                false);

        int shown = 0;
        for (var spec : all.values()) {
            if (onlyKind != null && !onlyKind.equals(spec.id())) continue;
            shown++;

            var itemLoc = net.minecraft.resources.ResourceLocation.tryParse(spec.itemId());
            boolean itemOk = itemLoc != null
                    && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(itemLoc);

            source.sendSuccess(() -> Component.literal(
                    "• " + spec.id() + "  →  物品 " + spec.itemId()
                            + (itemOk ? " ✓已注册" : " ✗物品不存在（忘了 StartupEvents.registry?）"))
                    .withStyle(itemOk ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED),
                    false);

            if (spec.hasCustomTitle()) {
                source.sendSuccess(() -> Component.literal("    名字：" + spec.title())
                        .withStyle(net.minecraft.ChatFormatting.GRAY), false);
            }

            int ok = 0;
            for (String token : spec.items()) {
                var key = resolve(registries, token);
                boolean good = key != null;
                if (good) ok++;
                String detail = good
                        ? "✓ " + key.getType().getId() + "  " + key.getDisplayName().getString()
                        : "✗ 解析不了（物品没装？token 写错？流体少了 fluid: 前缀？）";
                source.sendSuccess(() -> Component.literal("    " + token + "   " + detail)
                        .withStyle(good ? net.minecraft.ChatFormatting.DARK_GREEN
                                        : net.minecraft.ChatFormatting.GOLD), false);
            }
            // `ok` is mutated in the loop, so it cannot be captured by the lambda below:
            // snapshot it (and the total) into effectively-final locals first.
            final int okCount = ok;
            final int totalCount = spec.items().size();
            source.sendSuccess(() -> Component.literal(
                    "    小计：" + okCount + "/" + totalCount + " 项可解析")
                    .withStyle(net.minecraft.ChatFormatting.GRAY), false);
        }

        if (shown == 0) {
            source.sendFailure(Component.literal("Aurelium：没有名为 '" + onlyKind + "' 的无限元件种类。"));
            return 0;
        }
        return 1;
    }

    /** Resolve a declaration token with live registries; {@code null} when it is not resolvable. */
    private static appeng.api.stacks.AEKey resolve(net.minecraft.core.HolderLookup.Provider registries,
                                                  String token) {
        if (registries == null) return null;
        try {
            return com.muuxa.aurelium.storage.KeyCodec.decode(token, registries);
        } catch (RuntimeException unsupported) {
            return null;
        }
    }


    private static int run(CommandSourceStack source, String name, boolean replaceToolPatterns) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception noPlayer) {
            source.sendFailure(Component.literal("该命令需要玩家执行（读取手持宝匣）。"));
            return 0;
        }
        try {
            VaultExporter.ExportResult result =
                    VaultExporter.exportHand(player, name, replaceToolPatterns);
            Path data = result.data();
            Path recipe = result.recipe();
            Path analysis = result.analysis();
            Path tools = result.tools();
            boolean toolOnly = result.vaultId() == null;
            if (toolOnly) {
                // A held pattern tool exports as one register-only script (no vault, no recipe).
                source.sendSuccess(() -> Component.literal(
                        "已导出样板工具（" + result.sourceHand() + "）。"), false);
                source.sendSuccess(() -> Component.literal("脚本：" + data + "（放 startup_scripts/）")
                        .withStyle(net.minecraft.ChatFormatting.GRAY), false);
                source.sendSuccess(() -> Component.literal(
                        "文件位于存档 data/Aurelium/export/ 下。").withStyle(net.minecraft.ChatFormatting.GRAY), false);
                return 1;
            }
            source.sendSuccess(() -> Component.literal(
                    "已导出 " + result.sourceHand() + " 宝匣：" + result.totalTypes() + " 种，合计 "
                            + result.totalAmount()), false);
            source.sendSuccess(() -> Component.literal("① 数据：" + data)
                    .withStyle(net.minecraft.ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal("② 配方：" + recipe + "（放 server_scripts/）")
                    .withStyle(net.minecraft.ChatFormatting.GRAY), false);
            source.sendSuccess(() -> Component.literal("③ 分析：" + analysis)
                    .withStyle(net.minecraft.ChatFormatting.GRAY), false);
            if (tools != null) {
                source.sendSuccess(() -> Component.literal(
                        "④ 样板工具：" + tools + "（仅注册，放 startup_scripts/）")
                        .withStyle(net.minecraft.ChatFormatting.GRAY), false);
            }
            source.sendSuccess(() -> Component.literal(
                    "文件位于存档 data/Aurelium/export/ 下。").withStyle(net.minecraft.ChatFormatting.GRAY), false);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("导出失败：" + failure.getMessage()));
            return 0;
        }
    }
}
