package com.muuxa.aurelium.api;

import com.muuxa.aurelium.export.VaultExporter;
import com.muuxa.aurelium.storage.VaultWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * KubeJS-facing facade for exporting a held vault. Server scripts can pass the event's
 * {@code player} (a {@code ServerPlayer}) and optionally a file-name hint.
 *
 * <pre>
 *   PlayerEvents.tick(event =&gt; {
 *     if (event.player.isCrouching() &amp;&amp; event.player.mainHandItem.id === 'aurelium:star_vault') {
 *       AureliumExport.held(event.player, 'my_vault');
 *     }
 *   });
 * </pre>
 *
 * <p>Returns {@code [data, recipe, analysis, tools]} — the four written paths; {@code tools} is
 * empty for a plain vault export and points at the register-only tool script when the vault (or
 * the held item) carried pattern tools.</p>
 */
public final class AureliumExport {
    private AureliumExport() {}

    /** Export the vault in {@code player}'s offhand (falling back to main hand). */
    public static String[] held(ServerPlayer player, String nameHint) {
        try {
            VaultExporter.ExportResult result = VaultExporter.exportHand(player, nameHint);
            return new String[] {
                    result.data().toString(),
                    result.recipe().toString(),
                    result.analysis().toString(),
                    result.tools() == null ? "" : result.tools().toString()
            };
        } catch (Exception failure) {
            throw new IllegalStateException("AURELIUM 导出失败: " + failure.getMessage(), failure);
        }
    }

    /** Directory that exports are written to ({@code <world>/data/Aurelium/export/}). */
    public static String directory() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return "";
        return VaultExporter.exportDirectory(VaultWorldData.cellDirectory(server)).toString();
    }
}
