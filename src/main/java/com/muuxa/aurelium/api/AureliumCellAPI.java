package com.muuxa.aurelium.api;

import com.muuxa.aurelium.registry.AureliumItems;
import com.muuxa.aurelium.storage.VaultInventory;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;

/** Public KubeJS bridge for constructing independent cell instances. */
public final class AureliumCellAPI {
    private AureliumCellAPI() {}

    public static String itemId() { return "aurelium:star_vault"; }

    /** A distinct UUID is allocated only when the player/server actually receives the stack. */
    public static ItemStack create(String packId) {
        if (packId == null || ResourceLocation.tryParse(packId) == null || AureliumPacks.get(packId) == null) {
            throw new IllegalArgumentException("Pack not registered: " + packId);
        }
        ItemStack cell = AureliumItems.STAR_VAULT.get().newChargedStack();
        CompoundTag pending = new CompoundTag();
        pending.putString(VaultInventory.SEED_TAG, packId);
        cell.set(DataComponents.CUSTOM_DATA, CustomData.of(pending));
        cell.set(DataComponents.CUSTOM_NAME, Component.literal(AureliumPacks.get(packId).title()));
        return cell;
    }

    /** For KubeJS display/recipes: NBT-only template, no UUID and no world-data lookup. */
    public static String templateTag(String packId) {
        if (AureliumPacks.get(packId) == null) throw new IllegalArgumentException("Pack not registered: " + packId);
        CompoundTag pending = new CompoundTag();
        pending.putString(VaultInventory.SEED_TAG, packId);
        return pending.toString();
    }

    /** One-line dump of a pack's declared entries, for script logging. */
    public static String describePack(String packId) {
        var spec = AureliumPacks.get(packId);
        if (spec == null) throw new IllegalArgumentException("Pack not registered: " + packId);
        StringBuilder sb = new StringBuilder(spec.id()).append(" (").append(spec.typeCount()).append(" types)");
        spec.items().forEach((id, amount) -> sb.append("\n  ").append(amount).append("x ").append(id));
        return sb.toString();
    }
}