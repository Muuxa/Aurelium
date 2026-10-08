package com.muuxa.aurelium.client.mixin;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.client.gui.me.common.Repo;
import appeng.menu.me.common.GridInventoryEntry;
import com.muuxa.aurelium.storage.StarVaultItem;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Keeps every nested keepsake box (a {@link StarVaultItem}) at the <b>very top</b> of AE2 terminal
 * lists (the portable terminal opened by right-clicking a vault, and any other ME terminal), no
 * matter which sort order / direction is selected.
 *
 * <p>AE2 sorts the visible rows in {@link Repo#updateView()}; this mixin runs right after that and
 * does one more <b>stable</b> pass over the row list, moving vault items to the front and leaving
 * the relative order of everything else untouched. {@code List.sort} is stable, so ordinary items
 * keep whatever order AE2 (or the player's sort setting) gave them.</p>
 */
@Mixin(Repo.class)
public abstract class RepoVaultFirstMixin {

    @Shadow
    @Final
    private ArrayList<GridInventoryEntry> view;

    @Inject(method = "updateView", at = @At("TAIL"))
    private void aurelium$vaultsFirst(CallbackInfo ci) {
        ArrayList<GridInventoryEntry> rows = this.view;
        if (rows == null || rows.size() < 2) return;
        // Stable partition: vaults to the front, everything else keeps its relative order.
        boolean any = false;
        for (GridInventoryEntry e : rows) {
            if (isVault(e)) { any = true; break; }
        }
        if (!any) return;
        ArrayList<GridInventoryEntry> sorted = new ArrayList<>(rows.size());
        for (GridInventoryEntry e : rows) {
            if (isVault(e)) sorted.add(e);
        }
        for (GridInventoryEntry e : rows) {
            if (!isVault(e)) sorted.add(e);
        }
        rows.clear();
        rows.addAll(sorted);
    }

    private static boolean isVault(GridInventoryEntry entry) {
        if (entry == null) return false;
        AEKey what = entry.getWhat();
        return what instanceof AEItemKey itemKey && itemKey.getItem() instanceof StarVaultItem;
    }
}