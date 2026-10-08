package com.muuxa.aurelium;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * AURELIUM's server config. Everything here is a SERVER option, so pack authors can tune
 * behaviour without recompiling.
 *
 * <ul>
 *   <li>{@code vault} - the keepsake-box (vault) nesting rules.</li>
 *   <li>{@code pattern_tool} - the pattern cut-tool ceiling.</li>
 * </ul>
 */
public final class AureliumConfig {
    private AureliumConfig() {}

    public static final ModConfigSpec SPEC;

    /** Maximum number of vaults that may be chained (vault inside vault …). 1 disables nesting. */
    public static final ModConfigSpec.IntValue MAX_VAULT_NEST;

    /** Upper bound on how many patterns one cut (sneak + right-click) may carry away. */
    public static final ModConfigSpec.IntValue PATTERN_CUT_LIMIT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        // --- Vault (keepsake box) settings. ---
        builder.comment("Aurelium")
                .push("vault");
        MAX_VAULT_NEST = builder
                .comment("宝匣可嵌套的最大层数（宝匣→宝匣→…）。",
                        "0 = 不允许宝匣放进宝匣,默认 5。",
                        "普通便携元件、无限元件与无限磁盘不计入此层数。")
                .translation("aurelium.configuration.max_vault_nest")
                .defineInRange("max_vault_nest", 5, 0, 64);
        builder.pop();

        // --- Pattern tools. ---
        builder.comment("样板工具").push("pattern_tool");
        PATTERN_CUT_LIMIT = builder
                .comment("剪切上限",
                        "样板剪切工具单次最多剪走多少个样板。",
                        "样板复制工具不受此上限影响（复制无上限）。",
                        "默认 10000。")
                .translation("aurelium.configuration.pattern_cut_limit")
                .defineInRange("cut_limit", 10000, 1, Integer.MAX_VALUE);
        builder.pop();

        SPEC = builder.build();
    }

    /** The cut ceiling; falls back to the shipped default while the config is not loaded yet. */
    public static int patternCutLimit() {
        try {
            return PATTERN_CUT_LIMIT.get();
        } catch (RuntimeException notLoadedYet) {
            return 10000;
        }
    }
}