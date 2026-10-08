package com.muuxa.aurelium.compat.tnt;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * "Is this one of AURELIUM's items?" — answered from the live registry rather than a hard-coded
 * list, so anything added later (a new mod version, or KubeJS-created vault / infinite-cell
 * items such as {@code kubejs:starlight_cell}) is recognised with no code change.
 *
 * <p>Two signals are used:</p>
 * <ol>
 *   <li>the item id lives in AURELIUM's namespace ({@code aurelium:*}); or</li>
 *   <li>the item class is one of AURELIUM's own types — which covers KubeJS items that were
 *       created under a different namespace but are built from AURELIUM's builders.</li>
 * </ol>
 */
public final class AureliumItemsHelper {
    /** AURELIUM's own item classes. KubeJS copies are instances of these. */
    private static final String[] OWN_CLASSES = {
            "com.muuxa.aurelium.storage.StarVaultItem",
            "com.muuxa.aurelium.storage.InfiniteCellItem",
            "com.muuxa.aurelium.storage.InfiniteDiskItem",
    };

    private AureliumItemsHelper() {}

    /** True when this stack is an AURELIUM item (by namespace or by class). */
    public static boolean isAureliumItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return isAureliumItem(stack.getItem());
    }

    /** True when this item is an AURELIUM item (by namespace or by class). */
    public static boolean isAureliumItem(Item item) {
        if (item == null) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id != null && "aurelium".equals(id.getNamespace())) return true;
        Class<?> c = item.getClass();
        for (String name : OWN_CLASSES) {
            if (isInstanceOf(c, name)) return true;
        }
        return false;
    }

    /** Name-based check, so this helper never needs to import the classes it looks for. */
    private static boolean isInstanceOf(Class<?> type, String className) {
        for (Class<?> t = type; t != null; t = t.getSuperclass()) {
            if (t.getName().equals(className)) return true;
        }
        return false;
    }
}