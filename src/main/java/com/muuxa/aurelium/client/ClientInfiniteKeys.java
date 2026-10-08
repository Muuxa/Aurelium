package com.muuxa.aurelium.client;

import appeng.api.stacks.AEKey;
import com.muuxa.aurelium.api.AureliumInfinite;
import com.muuxa.aurelium.api.InfiniteKindSpec;
import com.muuxa.aurelium.storage.KeyCodec;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.HashSet;
import java.util.Set;

/**
 * Client-side lookup: "is this key declared infinite by any registered kind?".
 *
 * <p>Safe to run on the client because KubeJS <b>startup</b> scripts populate
 * {@link AureliumInfinite} on both sides. The token set is cached and invalidated when the
 * number of registered kinds changes (i.e. after a script reload).</p>
 *
 * <p>Comparison is done in {@link KeyCodec} <b>token</b> form, which round-trips <b>every</b>
 * AEKey type (items, fluids, and generic {@code key:<SNBT>} keys such as Mekanism chemicals), so
 * this reports correctly for non-item keys too. {@code AEKey#toTagGeneric} needs registries, which
 * exist on both the integrated client and a remote client, so encoding is available here.</p>
 */
public final class ClientInfiniteKeys {
    private ClientInfiniteKeys() {}

    private static Set<String> cachedTokens = Set.of();
    private static int cachedKindCount = -1;

    private static Set<String> tokens() {
        int count = AureliumInfinite.all().size();
        if (count != cachedKindCount) {
            Set<String> ids = new HashSet<>();
            for (InfiniteKindSpec spec : AureliumInfinite.all().values()) {
                ids.addAll(spec.items());
            }
            cachedTokens = Set.copyOf(ids);
            cachedKindCount = count;
        }
        return cachedTokens;
    }

    public static boolean isInfinite(AEKey key) {
        if (key == null) return false;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return false;
        try {
            String token = KeyCodec.encode(key, server.registryAccess());
            return token != null && tokens().contains(token);
        } catch (RuntimeException unsupported) {
            return false;
        }
    }
}