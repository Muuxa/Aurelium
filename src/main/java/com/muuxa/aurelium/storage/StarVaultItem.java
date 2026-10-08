package com.muuxa.aurelium.storage;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.PowerUnit;
import appeng.api.ids.AEComponents;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.upgrades.Upgrades;
import appeng.items.contents.CellConfig;
import appeng.items.tools.powered.AbstractPortableCell;
import appeng.util.ConfigInventory;
import appeng.menu.me.common.MEStorageMenu;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** AE2 portable terminal backed by a multi-key world SavedData cell. */
public final class StarVaultItem extends AbstractPortableCell {
    public static final double MAX_AE_POWER = (double) Integer.MAX_VALUE;
    // Creative and KubeJS copies start usable, but their starting charge is NOT the capacity.
    public static final double STARTING_AE_POWER = 10_000.0;
    // AE2's Charger uses this rate but also limits transfers by supplied energy
    // and remaining battery space. Do not impose an additional low rate here;
    // ordinary energy cards still multiply the reported rate.
    private static final double BASE_AE_CHARGE_RATE = MAX_AE_POWER;

    public StarVaultItem(Item.Properties properties) {
        // IBasicCellItem forces the portable menu to show only one key type.
        // AbstractPortableCell retains the menu and AE2 energy mechanics.
        // The colour is the tint AE2 applies when the cell is placed as a block; use the
        // cell's rose-pink theme (sampled from the item textures) so it matches everywhere.
        super(MEStorageMenu.PORTABLE_ITEM_CELL_TYPE, properties, 0xD97FB4);
    }

    @Override
    public ConfigInventory getConfigInventory(ItemStack stack) {
        // Unlike the basic portable cell, filters must accept every AE2 key type.
        return CellConfig.create(stack);
    }

    @Override
    public FuzzyMode getFuzzyMode(ItemStack stack) {
        return stack.getOrDefault(AEComponents.STORAGE_CELL_FUZZY_MODE, FuzzyMode.IGNORE_ALL);
    }

    @Override
    public void setFuzzyMode(ItemStack stack, FuzzyMode mode) {
        stack.set(AEComponents.STORAGE_CELL_FUZZY_MODE, mode);
    }

    @Override
    public ResourceLocation getRecipeId() {
        return ResourceLocation.fromNamespaceAndPath("aurelium", "star_vault");
    }

    @Override
    public double getChargeRate(ItemStack stack) {
        return BASE_AE_CHARGE_RATE
                * (1.0 + Upgrades.getEnergyCardMultiplier(getUpgrades(stack)));
    }

    @Override
    public double getAEMaxPower(ItemStack stack) { return MAX_AE_POWER; }

    @Override
    public double getAECurrentPower(ItemStack stack) {
        // An old cell may still carry an oversized AE2 stored-energy component.
        // Cap the effective charge without treating the battery as infinite.
        double stored = super.getAECurrentPower(stack);
        return Double.isFinite(stored) ? Math.max(0.0, Math.min(stored, MAX_AE_POWER)) : 0.0;
    }

    @Override
    public void onUpgradesChanged(ItemStack stack, IUpgradeInventory upgrades) {
        // Cards still improve charge rate but never increase finite AE capacity.
        // Normalize any legacy overcharged stack when its upgrades are touched.
        if (super.getAECurrentPower(stack) > MAX_AE_POWER) {
            setAECurrentPower(stack, MAX_AE_POWER);
        }
    }

    public ItemStack newChargedStack() {
        ItemStack stack = new ItemStack(this);
        injectAEPower(stack, STARTING_AE_POWER, Actionable.MODULATE);
        return stack;
    }

    /**
     * Overwrites the stored AE charge. Used when materialising a vault from an exported pack so
     * the restored item carries the charge it had at export time.
     */
    public void setStoredPower(ItemStack stack, double ae) {
        double clamped = Double.isFinite(ae) ? Math.max(0.0, Math.min(ae, MAX_AE_POWER)) : 0.0;
        setAECurrentPower(stack, clamped);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.aurelium.star_vault");
    }

    /** External FE input capability, using AE2's configured FE / AE exchange rate. */
    public IEnergyStorage feInput(ItemStack stack) {
        return new IEnergyStorage() {
            private double aePerFe() { return PowerUnit.FE.convertTo(PowerUnit.AE, 1.0); }

            private int feFloor(double ae) {
                double ratio = aePerFe();
                if (!(ratio > 0.0) || !Double.isFinite(ratio)) return 0;
                double fe = Math.floor(Math.max(0.0, ae) / ratio);
                return fe >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) fe;
            }

            private int visibleFe(double ae) {
                double ratio = aePerFe();
                if (!(ratio > 0.0) || !Double.isFinite(ratio)) return 0;
                if (MAX_AE_POWER / ratio <= Integer.MAX_VALUE) return feFloor(ae);
                // IEnergyStorage exposes int counters. If the *FE-equivalent*
                // maximum is larger, scale the displayed counters, otherwise
                // chargers think the cell is full long before it reaches max AE.
                if (ae >= MAX_AE_POWER) return Integer.MAX_VALUE;
                double fraction = Math.max(0.0, ae) / MAX_AE_POWER;
                return (int) Math.floor(fraction * Integer.MAX_VALUE);
            }

            @Override
            public int receiveEnergy(int requested, boolean simulate) {
                double ratio = aePerFe();
                if (requested <= 0 || !(ratio > 0.0) || !Double.isFinite(ratio)) return 0;
                double free = Math.max(0.0, MAX_AE_POWER - getAECurrentPower(stack));
                // The source's requested FE, finite remaining AE space and the int FE
                // capability signature are the only bounds here: no artificial
                // per-call throughput cap imposed by AURELIUM.
                int accepted = Math.min(requested, feFloor(free));
                if (accepted <= 0) return 0;
                if (!simulate) {
                    // AE2 returns unused AE; never round FE up when close to full.
                    double unused = injectAEPower(stack, accepted * ratio, Actionable.MODULATE);
                    if (unused > 0) return Math.max(0, accepted - (int) Math.ceil(unused / ratio));
                }
                return accepted;
            }

            @Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
            @Override public int getEnergyStored() { return visibleFe(getAECurrentPower(stack)); }
            @Override public int getMaxEnergyStored() { return visibleFe(MAX_AE_POWER); }
            @Override public boolean canExtract() { return false; }
            @Override public boolean canReceive() { return true; }
        };
    }
}
