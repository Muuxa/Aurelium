package com.muuxa.aurelium.compat;

import net.neoforged.fml.ModList;

/**
 * Lightweight "is any AURELIUM-related add-on installed?" probe, used to decide whether the
 * infinite disk's "special types" row is meaningful. Pure {@link ModList} lookups, no class
 * references to the add-ons, so it is always safe to call.
 */
public final class AureliumCompat {

    /** Mod ids of the add-ons whose keys count as "special" (non item/fluid). */
    private static final String[] ADDONS = {
            "ae2lt",              // lightning
            "appmek",             // Mekanism chemicals (Applied Mekanistics)
            "mekanism",
            "data_energistics",
            "neoecoae",
            "extendedae",
            "extendedae_plus",
    };

    private AureliumCompat() {
    }

    public static boolean hasAnyAddon() {
        ModList list = ModList.get();
        if (list == null) return false;
        for (String id : ADDONS) {
            if (list.isLoaded(id)) return true;
        }
        return false;
    }

    /** True when ExtendedAE is present (the Advanced Extended IO Port needs it). */
    public static boolean hasExtendedAE() {
        ModList list = ModList.get();
        return list != null && list.isLoaded("extendedae");
    }
}