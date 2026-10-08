package com.muuxa.aurelium.storage;

import appeng.api.storage.cells.ICellHandler;
import appeng.api.storage.cells.ISaveProvider;
import appeng.api.storage.cells.StorageCell;
import net.minecraft.world.item.ItemStack;

/** AE2 adapter for infinite cells. Registration happens in the mod constructor. */
public final class InfiniteCellHandler implements ICellHandler {
    @Override
    public boolean isCell(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof InfiniteCellItem;
    }

    @Override
    public StorageCell getCellInventory(ItemStack stack, ISaveProvider saveProvider) {
        return isCell(stack) ? new InfiniteCellInventory(stack, saveProvider) : null;
    }
}