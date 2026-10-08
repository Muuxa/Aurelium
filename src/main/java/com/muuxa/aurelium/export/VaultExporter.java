package com.muuxa.aurelium.export;

import appeng.api.ids.AEComponents;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.muuxa.aurelium.storage.KeyCodec;
import com.muuxa.aurelium.storage.VaultInventory;
import com.muuxa.aurelium.storage.VaultWorldData;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Exports one vault cell's stored contents (read from world SavedData) into two files:
 * a human-readable listing and a ready-to-paste KubeJS snippet for
 * {@code AureliumPacks.register(...)}.
 *
 * <p>Amounts are written as plain decimal strings, so values beyond {@code long} round-trip
 * exactly. <b>Every AE2 key type can be exported and re-imported</b>: items as
 * {@code namespace:item}, fluids as {@code fluid:namespace:fluid}, and anything else as
 * {@code key:{...SNBT...}} (see {@link KeyCodec}).</p>
 *
 * <p>Runs on the server main thread (called from a command), which is required because
 * {@link VaultWorldData} must only be touched there.</p>
 */
public final class VaultExporter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Pattern SAFE_NAME = Pattern.compile("[^a-zA-Z0-9_.-]");
    private static final String EXPORT_SUBDIR = "export";

    private VaultExporter() {}

    /** Where exports are written: {@code <world>/data/Aurelium/export/}. */
    public static Path exportDirectory(java.nio.file.Path cellDirectory) {
        return cellDirectory.resolve(EXPORT_SUBDIR);
    }

    public record ExportResult(Path data, Path recipe, Path analysis, Path tools, String sourceHand,
                               UUID vaultId, int itemTypes, int fluidTypes, int otherTypes,
                               BigInteger totalAmount) {
        public int totalTypes() { return itemTypes + fluidTypes + otherTypes; }
    }

    /**
     * Extra per-vault metadata captured from the held stack, so the generated script reproduces
     * the vault as it actually is rather than a hard-coded default.
     *
     * @param displayName the vault's custom name ({@code null} = keep the item's default name)
     * @param power       the vault's current AE charge (used for {@code .vaultPower(...)})
     */
    public record VaultMeta(String displayName, double power) {
        public static final VaultMeta DEFAULT = new VaultMeta(null, 10_000.0);
    }

    /** Read the vault's custom name (only when the player renamed it) and its current AE charge. */
    public static VaultMeta readMeta(ItemStack stack) {
        String name = null;
        Component custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
        if (custom != null) {
            String s = custom.getString();
            if (s != null && !s.isBlank()) name = s;
        }
        Double power = stack.get(AEComponents.STORED_ENERGY);
        double ae = power == null ? 10_000.0 : power;
        return new VaultMeta(name, ae);
    }

    /**
     * Export the vault held in the player's offhand (falling back to the main hand when the
     * offhand is not a vault). {@code nameHint} is sanitised into the file name when given.
     */
    public static ExportResult exportHand(ServerPlayer player, String nameHint) throws IOException {
        return exportHand(player, nameHint, false);
    }

    /**
     * As {@link #exportHand(ServerPlayer, String)}, recording the requested replace mode for any
     * pattern tool found inside the vault: {@code true} makes the generated script stamp the tool
     * with "replace same-primary-output patterns on paste".
     */
    public static ExportResult exportHand(ServerPlayer player, String nameHint,
                                          boolean replaceToolPatterns) throws IOException {
        if (player == null) throw new IllegalStateException("No player context");
        // A held pattern tool gets its OWN export: a standalone register-only script, no recipe.
        // The vault check comes first so a player holding a tool in one hand and a vault in the
        // other still exports the vault (the pre-existing behaviour).
        ItemStack offhand = player.getOffhandItem();
        ItemStack mainhand = player.getMainHandItem();
        boolean hasVault = VaultInventory.existingId(offhand) != null
                || VaultInventory.existingId(mainhand) != null;
        if (!hasVault) {
            if (offhand.getItem() instanceof com.muuxa.aurelium.pattern.PatternToolItem) {
                return exportTool(player, offhand, "offhand", nameHint, replaceToolPatterns);
            }
            if (mainhand.getItem() instanceof com.muuxa.aurelium.pattern.PatternToolItem) {
                return exportTool(player, mainhand, "mainhand", nameHint, replaceToolPatterns);
            }
        }
        String hand;
        ItemStack stack = player.getOffhandItem();
        if (VaultInventory.existingId(stack) != null) {
            hand = "offhand";
        } else {
            stack = player.getMainHandItem();
            if (VaultInventory.existingId(stack) == null) {
                throw new IllegalStateException("副手（或主手）没有宝匣元件");
            }
            hand = "mainhand";
        }
        UUID id = VaultInventory.existingId(stack);
        if (player.getServer() == null) throw new IllegalStateException("Server not available");
        Map<AEKey, BigInteger> amounts = VaultWorldData.get(player.getServer())
                .snapshot(id, player.getServer().registryAccess());
        // Capture the vault's real name + charge so the script reproduces it faithfully.
        VaultMeta meta = readMeta(stack);
        return export(id, amounts, hand, nameHint, meta, player.getServer().registryAccess(),
                VaultWorldData.cellDirectory(player.getServer()), player.getServer(),
                replaceToolPatterns);
    }

    /**
     * Exports one held pattern tool to its own standalone script: <b>register only</b> (no recipe),
     * keeping the tool's exact payload and paste mode.
     *
     * <p>{@code nameHint} is the tool's name and may be Chinese — the generated item's id uses a
     * derived slug (Minecraft ids are ASCII-only) while its display name is the given text, so the
     * item shows up in game under the player's own name.</p>
     *
     * @param replaceToolPatterns {@code true} forces replace mode on in the export; {@code false}
     *                            keeps whatever flag the tool itself carries
     */
    public static ExportResult exportTool(ServerPlayer player, ItemStack tool, String hand,
                                          String nameHint, boolean replaceToolPatterns)
            throws IOException {
        if (!(tool.getItem() instanceof com.muuxa.aurelium.pattern.PatternToolItem toolItem)) {
            throw new IllegalStateException("手持的不是样板工具");
        }
        String name = nameHint == null || nameHint.isBlank()
                ? tool.getHoverName().getString()
                : nameHint.trim();
        String base64 = com.muuxa.aurelium.pattern.PatternToolData.toBase64Payload(tool);
        // Same rule as the vault path: an explicit `true` stamps replace mode; `false` preserves
        // the tool's own flag (so re-exporting an already-replacing tool never silently disables it).
        boolean replace = replaceToolPatterns
                || com.muuxa.aurelium.pattern.PatternToolData.isReplaceMode(tool);

        Path dir = exportDirectory(VaultWorldData.cellDirectory(player.getServer()));
        Files.createDirectories(dir);
        String base = "tool_" + buildToolBaseName(name);
        Path data = dir.resolve(base + ".js");
        deleteStale(dir, base);
        Files.writeString(data, renderSingleToolScript(name, toolItem.isCopyOnly(), base64, replace),
                StandardCharsets.UTF_8);
        return new ExportResult(data, data, data, data, hand, null, 1, 0, 0, BigInteger.ONE);
    }

    /** Builds a filesystem-safe base name for a tool export. */
    private static String buildToolBaseName(String name) {
        String cleaned = SAFE_NAME.matcher(name).replaceAll("_").replaceAll("^_+|_+$", "");
        if (cleaned.isEmpty()) cleaned = "tool";
        // Keep the readable part short and append a stable hash so two different CJK names never
        // overwrite each other's files.
        if (cleaned.length() > 24) cleaned = cleaned.substring(0, 24);
        return cleaned + "_" + Integer.toHexString(name.hashCode() & 0x7fffffff);
    }

    /** The register-only script for one exported tool. */
    private static String renderSingleToolScript(String name, boolean copyTool, String base64,
                                                 boolean replace) {
        StringBuilder sb = new StringBuilder();
        sb.append("// 由 AURELIUM /aurelium export 生成 —— 样板工具注册（仅注册，无配方）\n");
        sb.append("// 名字：").append(name).append('\n');
        sb.append("// 导出时间: ").append(LocalDateTime.now()).append('\n');
        sb.append("//\n");
        sb.append("// 注：MC 的物品 id 只允许小写字母/数字/_.-，中文无法进 id；因此物品 id 由\n");
        sb.append("//     名字推导，而游戏内显示的名称就是上面这行名字。\n");
        sb.append("// 直接放进 kubejs/startup_scripts/ 即可。\n");
        sb.append('\n');
        sb.append("StartupEvents.registry('item', event => {\n");
        sb.append("  event.create('aurelium:").append(singleToolId(name))
                .append("', 'aurelium:pattern_tool')\n");
        sb.append("       .displayName(\"").append(escapeForKubeJs(name)).append("\")\n");
        sb.append("       .copyTool(").append(copyTool).append(")\n");
        sb.append("       .toolPayload(\"").append(escapeForKubeJs(base64 == null ? "" : base64))
                .append("\")\n");
        sb.append("       .replaceMode(").append(replace).append(");\n");
        sb.append("});\n");
        return sb.toString();
    }

    /**
     * The item id for a single-tool export. ASCII text is slugged; a name with no usable ASCII
     * falls back to a stable hash, so the same name always maps to the same id.
     */
    private static String singleToolId(String name) {
        String slug = SAFE_NAME.matcher(name.trim().toLowerCase(java.util.Locale.ROOT)).replaceAll("_");
        slug = slug.replaceAll("^_+|_+$", "");
        if (slug.length() > 20) slug = slug.substring(0, 20);
        String suffix = Integer.toHexString(name.hashCode() & 0x7fffffff);
        return slug.isEmpty() ? "export_tool_" + suffix : "export_tool_" + slug + "_" + suffix;
    }

    /** Back-compat convenience: export with default metadata (name unset, 10000 AE). */
    public static ExportResult export(UUID id, Map<AEKey, BigInteger> amounts,
                                      String sourceHand, String nameHint,
                                      HolderLookup.Provider registries,
                                      Path cellDirectory,
                                      net.minecraft.server.MinecraftServer server) throws IOException {
        return export(id, amounts, sourceHand, nameHint, VaultMeta.DEFAULT, registries, cellDirectory, server);
    }

    /** Core export from an already-read snapshot; kept separate so it stays testable. */
    public static ExportResult export(UUID id, Map<AEKey, BigInteger> amounts,
                                      String sourceHand, String nameHint, VaultMeta meta,
                                      HolderLookup.Provider registries,
                                      Path cellDirectory,
                                      net.minecraft.server.MinecraftServer server) throws IOException {
        return export(id, amounts, sourceHand, nameHint, meta, registries, cellDirectory, server, false);
    }

    /** Core export; {@code replaceToolPatterns} stamps exported tools with their paste mode. */
    public static ExportResult export(UUID id, Map<AEKey, BigInteger> amounts,
                                      String sourceHand, String nameHint, VaultMeta meta,
                                      HolderLookup.Provider registries,
                                      Path cellDirectory,
                                      net.minecraft.server.MinecraftServer server,
                                      boolean replaceToolPatterns) throws IOException {
        // Expand nested vaults so the export lists EVERYTHING, each entry tagged with its
        // nesting level (L0 = this vault, L1 = a vault inside it, …). The nested vault item
        // itself is listed too (flagged), so the structure is preserved.
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        visited.add(id);
        java.util.List<UUID> expanded = new java.util.ArrayList<>();
        int[] maxDepth = {0};
        List<Row> rows = new ArrayList<>();
        // Tools encoded during this export are stamped with the requested paste mode (the flag is
        // read by KeyCodec while it builds each item token); cleared again in the finally block so
        // no later encode call is affected.
        com.muuxa.aurelium.storage.KeyCodec.setStampToolReplace(replaceToolPatterns);
        try {
            collectRows(amounts, registries, server, visited, 0, "", rows, expanded, maxDepth);
        } finally {
            com.muuxa.aurelium.storage.KeyCodec.setStampToolReplace(false);
        }

        rows.sort(Comparator
                .comparingInt((Row r) -> r.depth)
                .thenComparing(Comparator.comparing((Row r) -> r.amount).reversed())
                .thenComparing(r -> r.sortKey));

        int itemTypes = 0, fluidTypes = 0, otherTypes = 0;
        BigInteger total = BigInteger.ZERO;
        for (Row row : rows) {
            total = total.add(row.amount);
            switch (row.kind) {
                case ITEM -> itemTypes++;
                case FLUID -> fluidTypes++;
                case OTHER -> otherTypes++;
            }
        }

        String base = buildBaseName(id, nameHint);
        Path dir = exportDirectory(cellDirectory);
        Files.createDirectories(dir);
        // 清掉同一份导出的旧文件（避免重复导出越积越多；只清本轮 base 前缀的三种）。
        deleteStale(dir, base);
        // Pattern tools inside the vault are exported to their OWN script (register-only, no
        // recipe), so the two export kinds stay separate and a tool can be rebuilt on its own.
        List<Row> tools = new ArrayList<>();
        for (Row row : rows) {
            if (row.tool) tools.add(row);
        }
        // 主要是宝匣导出：① 数据（startup）② 配方（server）③ 分析（人读）④ 工具（startup）
        Path data = dir.resolve(base + ".js");
        Path recipe = dir.resolve(base + ".recipe.js");
        Path analysis = dir.resolve(base + ".txt");
        Files.writeString(data, renderKubeJs(id, rows, maxDepth[0], exportName(nameHint), meta,
                        replaceToolPatterns),
                StandardCharsets.UTF_8);
        Files.writeString(recipe, renderRecipe(exportName(nameHint)), StandardCharsets.UTF_8);
        Files.writeString(analysis,
                renderAnalysis(id, rows, itemTypes, fluidTypes, otherTypes, total, expanded, maxDepth[0], meta),
                StandardCharsets.UTF_8);
        Path toolsFile = null;
        if (!tools.isEmpty()) {
            toolsFile = dir.resolve(base + ".tools.js");
            Files.writeString(toolsFile, renderToolScript(tools, nameHint), StandardCharsets.UTF_8);
        }
        return new ExportResult(data, recipe, analysis, toolsFile, sourceHand, id,
                itemTypes, fluidTypes, otherTypes, total);
    }

    /**
     * The standalone script for pattern tools found inside the vault (<b>register only</b>, no
     * recipe): each tool keeps its full payload, its paste mode, and gets a readable id derived
     * from its own name — the item's on-screen name is whatever the player called it.
     *
     * <p>Minecraft ids only accept {@code [a-z0-9_.-]}, so a Chinese name cannot go into the item
     * id itself; the name is carried by {@code .displayName(...)} (which is what the player
     * actually sees) while the id uses a transliteration when possible and a stable hash suffix
     * when it is not. Two different names therefore never collide, and re-exporting the same tool
     * reproduces the same id.</p>
     */
    private static String renderToolScript(List<Row> tools, String nameHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("// 由 AURELIUM /aurelium export 生成 —— 样板工具注册（仅注册，无配方）\n");
        sb.append("// 导出时间: ").append(LocalDateTime.now()).append('\n');
        sb.append("// 每个工具保留：完整样板内容、替换模式、以及它自己的名字。\n");
        sb.append("//\n");
        sb.append("// 注：MC 的物品 id 只允许小写字母/数字/_.-，中文无法进 id；因此这里\n");
        sb.append("//     用「名字转写 + 短哈希」生成唯一 id，而物品的游戏内名称就是原文名字。\n");
        sb.append("// 直接放进 kubejs/startup_scripts/ 即可。\n");
        sb.append('\n');
        sb.append("StartupEvents.registry('item', event => {\n");
        java.util.Set<String> usedIds = new java.util.HashSet<>();
        for (Row row : tools) {
            String name = toolDisplayName(row);
            String id = uniqueToolId(name, usedIds);
            sb.append("  // 名字：").append(name).append('\n');
            sb.append("  event.create('aurelium:").append(id)
                    .append("', 'aurelium:pattern_tool')\n");
            sb.append("       .displayName(\"").append(escapeForKubeJs(name)).append("\")\n");
            sb.append("       .copyTool(").append(toolIsCopy(row)).append(")\n");
            sb.append("       .toolPayload(\"").append(escapeForKubeJs(row.toolPayload))
                    .append("\")\n");
            sb.append("       .replaceMode(").append(row.toolReplace).append(");\n");
        }
        sb.append("});\n");
        return sb.toString();
    }

    /** The player-facing name of an exported tool row (its own custom name when set). */
    private static String toolDisplayName(Row row) {
        return row.toolName == null || row.toolName.isBlank() ? "导出的样板工具" : row.toolName;
    }

    /** True when the row is the copy tool (vs the cut tool). */
    private static boolean toolIsCopy(Row row) {
        return row.toolCopy;
    }

    /** A unique, id-safe slug for a tool name; falls back to a stable hash for CJK-only names. */
    private static String uniqueToolId(String name, java.util.Set<String> used) {
        String slug = exportName(name);
        if (slug.equals("default")) slug = "tool";
        String id = "export_tool_" + slug;
        if (used.add(id)) return id;
        int n = 2;
        while (!used.add(id + "_" + n)) n++;
        return id + "_" + n;
    }

    /**
     * The companion crafting recipe, written to its own file because it belongs to
     * {@code server_scripts/} while the pack registration belongs to {@code startup_scripts/}.
     * Default shape: 8 dirt ring + 1 cobblestone in the centre.
     */
    private static String renderRecipe(String name) {
        String itemId = "kubejs:export_" + name;
        StringBuilder sb = new StringBuilder();
        sb.append("// 由 AURELIUM /aurelium export 生成 —— 配套配方\n");
        sb.append("// 物品 id: ").append(itemId).append('\n');
        sb.append("//\n");
        sb.append("// ⚠️ 本文件必须放在  kubejs/server_scripts/  下（配方属于服务器脚本）。\n");
        sb.append("//    放错到 startup_scripts/ 会「既不执行、也不报错」。\n");
        sb.append("//\n");
        sb.append("// 配方：8 个泥土（围一圈）+ 1 个圆石（正中） → 该宝匣\n");
        sb.append("//     D D D\n");
        sb.append("//     D C D\n");
        sb.append("//     D D D\n");
        sb.append('\n');
        sb.append("ServerEvents.recipes(event => {\n");
        sb.append("  event.shaped(\n");
        sb.append("    Item.of('").append(itemId).append("'),\n");
        sb.append("    [\n");
        sb.append("      'DDD',\n");
        sb.append("      'DCD',\n");
        sb.append("      'DDD'\n");
        sb.append("    ],\n");
        sb.append("    {\n");
        sb.append("      D: 'minecraft:dirt',\n");
        sb.append("      C: 'minecraft:cobblestone'\n");
        sb.append("    }\n");
        sb.append("  )\n");
        sb.append("})\n");
        return sb.toString();
    }

    /** The human-readable analysis file (中文清单 + 统计 + 名字/电量)。 */
    private static String renderAnalysis(UUID id, List<Row> rows,
                                         int itemTypes, int fluidTypes, int otherTypes, BigInteger total,
                                         List<UUID> expanded, int maxDepth, VaultMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("AURELIUM 宝匣分析\n");
        sb.append("=================\n");
        sb.append("uuid      : ").append(id).append('\n');
        sb.append("导出时间  : ").append(LocalDateTime.now()).append('\n');
        sb.append("宝匣名字  : ").append(meta.displayName() == null ? "(默认名)" : meta.displayName()).append('\n');
        sb.append("宝匣电量  : ").append(formatPower(meta.power())).append(" AE\n");
        sb.append("套娃层数  : ").append(maxDepth).append("（L0 = 本宝匣）\n");
        if (!expanded.isEmpty()) {
            sb.append("已展开套娃: ").append(expanded.size()).append(" 个内部宝匣\n");
            for (UUID inner : expanded) sb.append("            - ").append(inner).append('\n');
        }
        sb.append("类型合计  : ").append(itemTypes + fluidTypes + otherTypes)
                .append("（物品 ").append(itemTypes)
                .append(" / 流体 ").append(fluidTypes)
                .append(" / 其他 ").append(otherTypes).append("）\n");
        sb.append("数量合计  : ").append(total).append('\n');
        sb.append('\n');
        sb.append("—— 内容明细（[宝匣/Ln] = 该行本身是嵌套宝匣）——\n");
        for (Row row : rows) {
            sb.append(row.depthPrefix()).append(row.analysableLine()).append('\n');
        }
        return sb.toString();
    }

    /** Render a double as an integer when it has no fractional part, else as-is. */
    private static String formatPower(double power) {
        if (power == Math.floor(power) && !Double.isInfinite(power)) return String.valueOf((long) power);
        return String.valueOf(power);
    }

    /** Remove this export's previous files (keeps the folder from growing on re-exports). */
    private static void deleteStale(Path dir, String base) {
        for (String ext : new String[] { ".js", ".recipe.js", ".txt", ".analysis.txt", ".data.js",
                ".tools.js" }) {
            try {
                Files.deleteIfExists(dir.resolve(base + ext));
            } catch (IOException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    /** The sanitised export name used in item/pack ids; {@code default} when none was given. */
    public static String exportName(String nameHint) {
        if (nameHint == null || nameHint.isBlank()) return "default";
        String cleaned = SAFE_NAME.matcher(nameHint.trim()).replaceAll("_");
        cleaned = cleaned.replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "default" : cleaned.toLowerCase(java.util.Locale.ROOT);
    }

    /** Hard cap on recursive expansion, independent of the config, to avoid pathological data. */
    private static final int MAX_EXPORT_DEPTH = 32;

    /**
     * Collect every entry of {@code amounts}, recursing into nested vaults. Each entry carries
     * its nesting level ({@code depth}). A nested vault item is itself listed (with {@code vault}
     * = true) AND its contents are appended at {@code depth + 1}. {@code visited} prevents cycles;
     * {@code expanded} records the opened vault UUIDs; {@code maxDepth[0]} tracks the deepest level.
     */
    private static void collectRows(Map<AEKey, BigInteger> amounts,
                                    HolderLookup.Provider registries,
                                    net.minecraft.server.MinecraftServer server,
                                    java.util.Set<UUID> visited, int depth, String ownPath,
                                    List<Row> out, List<UUID> expanded, int[] maxDepth) {
        for (var e : amounts.entrySet()) {
            AEKey key = e.getKey();
            BigInteger amount = e.getValue();
            if (key == null || amount == null || amount.signum() <= 0) continue;
            boolean isVault = server != null && key instanceof AEItemKey itemKey
                    && itemKey.getItem() instanceof com.muuxa.aurelium.storage.StarVaultItem;
            maxDepth[0] = Math.max(maxDepth[0], depth);
            String childPath = null;
            UUID inner = null;
            if (isVault) {
                inner = VaultInventory.existingId(((AEItemKey) key).toStack());
                // Only assign a child path when the nested vault is actually resolvable; otherwise
                // the generated pack would reference a bogus "…_null" id.
                if (inner != null) childPath = nextChildPath(ownPath, out);
            }
            Row row = Row.of(key, amount, registries, depth, isVault, ownPath, childPath);
            if (row != null) out.add(row);
            if (isVault && depth < MAX_EXPORT_DEPTH && inner != null && visited.add(inner)) {
                expanded.add(inner);
                // Recurse into the child vault, tagging its entries with ITS OWN path.
                collectRows(VaultWorldData.get(server).snapshot(inner, registries),
                        registries, server, visited, depth + 1, childPath, out, expanded, maxDepth);
            }
        }
    }

    /**
     * Next sibling path under {@code parent}. Empty parent (root) yields "1"; a parent "1"
     * yields "1_1", "1_2", … based on how many child vaults that parent already has.
     */
    private static String nextChildPath(String parent, List<Row> soFar) {
        int n = 1;
        for (Row r : soFar) {
            if (r.vault && parent.equals(r.ownPath)) n++;
        }
        return parent.isEmpty() ? String.valueOf(n) : parent + "_" + n;
    }

    private static String buildBaseName(UUID id, String nameHint) {
        String stamp = LocalDateTime.now().format(STAMP);
        String id8 = id.toString().substring(0, 8);
        if (nameHint != null && !nameHint.isBlank()) {
            String cleaned = SAFE_NAME.matcher(nameHint.trim()).replaceAll("_");
            if (!cleaned.isEmpty()) return "vault_" + cleaned;
        }
        return "vault_" + id8 + "_" + stamp;
    }

    private static String renderKubeJs(UUID id, List<Row> rows, int maxDepth, String name,
                                       VaultMeta meta, boolean replaceToolPatterns) {
        // Item/pack ids: root = "export_<name>" (no number); children = "export_<name>_<n>".
        String rootItem = "export_" + name;
        String rootDisplay = meta.displayName() != null ? meta.displayName() : ("导出的宝匣 " + name);
        StringBuilder sb = new StringBuilder();
        sb.append("// 由 AURELIUM /aurelium export 生成\n");
        sb.append("// vault uuid: ").append(id).append('\n');
        sb.append("// 导出时间: ").append(LocalDateTime.now()).append('\n');
        sb.append("// 套娃层数: ").append(maxDepth).append('\n');
        sb.append("// 样板工具替换模式: ").append(replaceToolPatterns ? "true（粘贴时替换相同主产物）" : "false（相同主产物不粘贴）").append('\n');
        sb.append("// 本脚本注册一个专用宝匣物品（独立 id）及其内容包（含套娃）。\n");
        sb.append("// 物品 id: ").append(rootItem).append("（子包物品为 ").append(rootItem).append("_1、_2 …）\n");
        sb.append("// 取得物品：Item.of('").append(rootItem).append("')\n");
        sb.append("// 名字/电量已按导出时的真实值写入；内层宝匣沿用同一电量。\n");
        sb.append("// 直接放进 kubejs/startup_scripts/ 即可。\n");
        sb.append('\n');
        sb.append("// ① 注册物品（每个宝匣一个独立物品；根不带数字，子包从 _1 开始）\n");
        sb.append("StartupEvents.registry('item', event => {\n");
        // The root item binds the root pack; each child pack also gets its own item.
        for (String path : orderedPaths(rows)) {
            String itemName = itemNameFor(path, name);
            String packId = packIdFor(path, name);
            // Each level keeps its OWN name: the root uses the held vault's name, a nested vault
            // uses the name its item carried (falling back to the root name plus the path).
            String display = path.isEmpty() ? rootDisplay : vaultNameFor(path, rows, rootDisplay);
            sb.append("  event.create('").append(itemName).append("', 'aurelium:star_vault')\n");
            sb.append("       .vaultPack(\"").append(packId).append("\")\n");
            sb.append("       .vaultPower(").append(formatPower(meta.power())).append(")\n");
            sb.append("       .displayName(\"").append(escapeForKubeJs(display)).append("\");\n");
        }
        sb.append("});\n");
        sb.append('\n');
        sb.append("// ② 注册内容包（每个宝匣一个；父包用 \"vault:<子包id>\" 向下套娃）\n");
        for (String path : orderedPaths(rows)) {
            emitPack(sb, path, rows, name, meta, rootDisplay);
        }
        sb.append('\n');
        // A pattern tool's replace flag lives in its own item NBT, and the export writes that NBT
        // verbatim into the item token — so the exported tool carries its paste mode with it, no
        // extra script statement needed (see stampToolReplace below).
        sb.append("// ── 层级清单（仅供参考，不是脚本内容）─────────────────────────\n");
        for (Row row : rows) {
            sb.append("// ").append(row.depthPrefix()).append(row.analysableLine()).append('\n');
        }
        return sb.toString();
    }

    /**
     * The display name to use for the vault at {@code path}. The root keeps the held vault's name;
     * a nested vault reuses the name its own item carried (captured while rows were collected),
     * falling back to "<root name> <path>" when the item had no custom name.
     */
    private static String vaultNameFor(String path, List<Row> rows, String rootDisplay) {
        if (path.isEmpty()) return rootDisplay;
        for (Row row : rows) {
            if (row.vault && path.equals(row.childPath) && row.vaultName != null
                    && !row.vaultName.isBlank()) {
                return row.vaultName;
            }
        }
        return rootDisplay + " " + path;
    }

    /** All pack paths, root ("") first, then children in first-seen order. */
    private static java.util.LinkedHashSet<String> orderedPaths(List<Row> rows) {
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        paths.add("");
        for (Row row : rows) if (row.ownPath != null) paths.add(row.ownPath);
        return paths;
    }

    /** Item id for a path: root = {@code export_<name>}; child = {@code export_<name>_<path>}. */
    private static String itemNameFor(String path, String name) {
        return path.isEmpty() ? "export_" + name : "export_" + name + "_" + path;
    }

    /** Pack id for a path (namespaced): root = {@code aurelium:export_<name>}; child adds {@code _<path>}. */
    private static String packIdFor(String path, String name) {
        return "aurelium:" + itemNameFor(path, name);
    }

    /** Emit one {@code AureliumPacks.register({...})} holding exactly this path's own entries. */
    private static void emitPack(StringBuilder sb, String path, List<Row> rows, String name,
                                 VaultMeta meta, String rootDisplay) {
        sb.append("AureliumPacks.register({\n");
        sb.append("  id:   \"").append(packIdFor(path, name)).append("\",\n");
        // The pack carries its vault's own name and charge: a nested vault referenced as
        // { packId: ... } has no other place to store them, so without this the child vault
        // came back with the generic name and a flat 10000 AE.
        String packName = path.isEmpty() ? rootDisplay : vaultNameFor(path, rows, rootDisplay);
        sb.append("  name: \"").append(escapeForKubeJs(packName)).append("\",\n");
        sb.append("  power: ").append(formatPower(meta.power())).append(",\n");
        sb.append("  items: [\n");
        for (Row row : rows) {
            if (!path.equals(row.ownPath)) continue;
            if (row.tool) {
                // A pattern tool stored in the vault is part of its contents and must survive the
                // round-trip. Its full payload is already Base64-encoded inside the token, so the
                // real item token restores the tool exactly. (Writing only a comment here used to
                // drop the tool and produced an invalid empty pack when a sub-vault held only
                // tools.) The companion .tools.js still registers a standalone named item.
                sb.append("    { itemId: \"").append(escapeForKubeJs(row.token)).append("\", count: ")
                        .append(row.amount).append(" },\n");
                continue;
            }
            if (row.vault) {
                // A nested vault: reference its own pack, carrying the stack count.
                String childId = row.childPath == null ? null : packIdFor(row.childPath, name);
                if (childId == null) {
                    sb.append("    // ⚠ 未能解析嵌套宝匣的子包（原物品缺少 UUID），已跳过\n");
                    continue;
                }
                sb.append("    { packId: \"").append(childId).append("\", count: ")
                        .append(row.amount).append(" },\n");
            } else {
                sb.append("    { itemId: \"").append(escapeForKubeJs(row.token)).append("\", count: ")
                        .append(row.amount).append(" },\n");
            }
        }
        sb.append("  ]\n");
        sb.append("});\n\n");
    }

    /** Escape only the double quote so a key:{} SNBT string stays a valid JS string literal. */
    private static String escapeForKubeJs(String line) {
        return line.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private enum Kind { ITEM, FLUID, OTHER }

    private static final class Row {
        final Kind kind;
        final BigInteger amount;
        final String token;      // the bare KeyCodec token (no "Nx " prefix)
        final String entryLine;
        final String sortKey;
        final int depth;         // nesting level: 0 = this vault, 1 = a vault inside it, …
        final boolean vault;     // true when this entry is itself a nested vault item
        /** true when this entry is one of AURELIUM's pattern tools (exported to its own script). */
        final boolean tool;
        /** For tool rows: the tool's own custom name (may be {@code null}). */
        final String toolName;
        /** For tool rows: {@code true} = copy tool, {@code false} = cut tool. */
        final boolean toolCopy;
        /** For tool rows: the gzip payload as Base64 (empty when the tool holds nothing). */
        final String toolPayload;
        /** For tool rows: the tool's replace-on-paste flag. */
        final boolean toolReplace;
        /** For vault rows: the vault item's own display name (root and children alike). */
        final String vaultName;
        final String ownPath;    // the pack path this entry belongs to
        final String childPath;  // for vault rows: the pack path of the nested vault (else null)

        private Row(Kind kind, BigInteger amount, String token, String entryLine, String sortKey,
                    int depth, boolean vault, boolean tool, String toolName, boolean toolCopy,
                    String toolPayload, boolean toolReplace, String vaultName, String ownPath,
                    String childPath) {
            this.kind = kind;
            this.amount = amount;
            this.token = token;
            this.entryLine = entryLine;
            this.sortKey = sortKey;
            this.depth = depth;
            this.vault = vault;
            this.tool = tool;
            this.toolName = toolName;
            this.toolCopy = toolCopy;
            this.toolPayload = toolPayload;
            this.toolReplace = toolReplace;
            this.vaultName = vaultName;
            this.ownPath = ownPath;
            this.childPath = childPath;
        }

        String entryLine() { return entryLine; }

        /**
         * The human-readable listing's view of this entry. A pattern tool carries an embedded
         * payload of tens of kilobytes, which would produce an unreadable (and unwieldy) line in
         * the analysis file — so long tokens are abbreviated there. The data script keeps the
         * complete token; this truncation is display-only.
         */
        String analysableLine() {
            final int cap = 200;
            if (token.length() <= cap) return entryLine;
            return amount + "x " + token.substring(0, cap) + "… (共 " + token.length() + " 字符，完整数据见 .js)";
        }

        /** Indentation + optional vault flag for this row's nesting level. */
        String depthPrefix() {
            String indent = "  ".repeat(Math.max(0, depth));
            return vault ? indent + "└[宝匣/L" + depth + "] " : indent + "[L" + depth + "] ";
        }

        String kindLabel() {
            return switch (kind) {
                case ITEM -> "物品";
                case FLUID -> "流体";
                case OTHER -> "其他";
            };
        }

        /** Returns {@code null} when the key cannot be encoded (unrepresentable type). */
        static Row of(AEKey key, BigInteger amount, HolderLookup.Provider registries, int depth,
                      boolean vault, String ownPath, String childPath) {
            String token = KeyCodec.encode(key, registries);
            if (token == null) return null;
            Kind kind = key instanceof AEItemKey ? Kind.ITEM
                    : key instanceof appeng.api.stacks.AEFluidKey ? Kind.FLUID : Kind.OTHER;
            // AURELIUM's pattern tools export to their own standalone script (register-only, no
            // recipe) instead of riding along inside the vault's pack registration.
            String toolName = null;
            boolean toolCopy = false;
            String toolPayload = null;
            boolean toolReplace = false;
            String vaultName = null;
            boolean tool = false;
            if (key instanceof AEItemKey itemKey
                    && itemKey.getItem() instanceof com.muuxa.aurelium.pattern.PatternToolItem) {
                tool = true;
                ItemStack toolStack = itemKey.toStack();
                toolName = toolStack.getHoverName().getString();
                toolCopy = itemKey.getItem() instanceof com.muuxa.aurelium.pattern.PatternToolItem toolItem
                        && toolItem.isCopyOnly();
                String base64 = com.muuxa.aurelium.pattern.PatternToolData.toBase64Payload(toolStack);
                toolPayload = base64 == null ? "" : base64;
                toolReplace = com.muuxa.aurelium.pattern.PatternToolData.isReplaceMode(toolStack);
            }
            if (vault && key instanceof AEItemKey vaultKey) {
                // A vault's own name is captured for every level (root and nested alike) so the
                // generated script restores each box under the name the player gave it.
                vaultName = vaultKey.toStack().getHoverName().getString();
            }
            String entry = amount + "x " + token;
            return new Row(kind, amount, token, entry, kind.ordinal() + ":" + depth + ":" + token,
                    depth, vault, tool, toolName, toolCopy, toolPayload, toolReplace, vaultName,
                    ownPath, childPath);
        }
    }
}
