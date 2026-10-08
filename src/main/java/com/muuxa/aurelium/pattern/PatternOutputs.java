package com.muuxa.aurelium.pattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Primary-output identity of encoded patterns, used to keep a container free of duplicates.
 *
 * <p>"Same primary output" means both patterns produce the same {@link AEKey} as their first
 * output — the convention every AE2 provider uses to label a pattern. Comparing keys (not full
 * stacks) is deliberate: two patterns can differ in inputs or secondary outputs while still being
 * alternatives for the same product, and those are exactly the duplicates this guards against.</p>
 */
public final class PatternOutputs {

    private PatternOutputs() {
    }

    /**
     * The primary-output key of an encoded pattern, or {@code null} when the stack is not a
     * decodable pattern (blank patterns, foreign items, malformed data).
     */
    public static AEKey primaryOutput(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty() || level == null) return null;
        try {
            IPatternDetails details = PatternDetailsHelper.decodePattern(stack, level);
            if (details == null) return null;
            GenericStack output = details.getPrimaryOutput();
            return output == null ? null : output.what();
        } catch (Throwable undecodable) {
            return null;
        }
    }

    /** True when both stacks are patterns with the same primary output. */
    public static boolean sameOutput(ItemStack a, ItemStack b, Level level) {
        AEKey keyA = primaryOutput(a, level);
        if (keyA == null) return false;
        AEKey keyB = primaryOutput(b, level);
        return keyA.equals(keyB);
    }
}
