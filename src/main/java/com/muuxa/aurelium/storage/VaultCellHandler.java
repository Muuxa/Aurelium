package com.muuxa.aurelium.storage;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.world.item.ItemStack;

/** AE2 adapter. Registration takes place in the mod constructor. */
public final class VaultCellHandler implements ICellHandler {
    @Override
    public boolean isCell(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof StarVaultItem;
    }

    @Override
    public StorageCell getCellInventory(ItemStack stack, ISaveProvider saveProvider) {
        return isCell(stack) ? new VaultInventory(stack, saveProvider) : null;
    }
}