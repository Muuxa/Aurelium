package com.muuxa.aurelium.kubejs;

import com.muuxa.aurelium.storage.StarVaultItem;
import com.muuxa.aurelium.storage.VaultInventory;
import dev.latvian.mods.kubejs.item.ItemBuilder;
import appeng.api.ids.AEComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.CustomData;

/**
 * KubeJS item builder for a <b>dedicated vault item</b> bound to a pack (which may hold a
 * nested vault chain). Lets an exported script declare its own item id, instead of sharing
 * the generic {@code aurelium:star_vault}.
 *
 * <pre>
 *   StartupEvents.registry('item', event =&gt; {
 *     event.create('mypack:my_vault', 'aurelium:star_vault')
 *          .vaultPack('mypack:my_vault_pack')
 *          .vaultPower(10000);   // optional; default 10000 AE
 *   });
 * </pre>
 *
 * <p>The item's default components carry the pack id ({@link VaultInventory#SEED_TAG}) and an
 * initial charge ({@code AEComponents.STORED_ENERGY}). The actual contents (including any
 * {@code vault:} nesting) are built when the cell first loads on the server.</p>
 */
public class VaultItemBuilder extends ItemBuilder {
    /** Default starting charge for a KubeJS-created vault, in AE. */
    public static final double DEFAULT_POWER = 10_000.0;

    /** Pack id this vault item seeds from; written to the stack's CustomData at creation time. */
    private String vaultPack;
    /** Initial AE charge; clamped to [0, Integer.MAX_VALUE]. */
    private double vaultPower = DEFAULT_POWER;

    public VaultItemBuilder(ResourceLocation id) {
        super(id);
        this.maxStackSize = 1;
        this.fireResistant = true;
        this.rarity = net.minecraft.world.item.Rarity.EPIC;
        // Reuse the built-in vault model. Use a model GENERATOR (not parentModel): KubeJS's
        // parentModel path unconditionally writes "layer0":"", which would override the parent
        // model's layers and show the purple/black missing-model placeholder.
        this.modelGenerator = model -> model.parent(
                ResourceLocation.fromNamespaceAndPath("aurelium", "item/star_vault"));
    }

    /** Bind this vault item to a registered pack id (see {@code AureliumPacks.register}). */
    public VaultItemBuilder vaultPack(String packId) {
        this.vaultPack = packId;
        return this;
    }

    /**
     * Set the starting AE charge (default {@value #DEFAULT_POWER}). Values are clamped to
     * {@code [0, Integer.MAX_VALUE]} to match AE2's finite cell battery.
     */
    public VaultItemBuilder vaultPower(double ae) {
        this.vaultPower = Math.max(0.0, Math.min((double) Integer.MAX_VALUE, ae));
        return this;
    }

    @Override
    public Item createObject() {
        CompoundTag tag = new CompoundTag();
        if (vaultPack != null) tag.putString(VaultInventory.SEED_TAG, vaultPack);
        this.component(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        // Initial charge, stored on the item type's default components so a fresh stack has it.
        this.component(AEComponents.STORED_ENERGY, vaultPower);
        return new StarVaultItem(this.createItemProperties());
    }
}