package com.muuxa.aurelium.registry;

import com.muuxa.aurelium.Aurelium;
import com.muuxa.aurelium.storage.InfiniteCellInventory;
import com.muuxa.aurelium.storage.InfiniteCellItem;
import com.muuxa.aurelium.storage.StarVaultItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item registry: the star vault, the infinite cell / infinite disk and the two pattern tools. */
public final class AureliumItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Aurelium.ID);

    public static final DeferredItem<StarVaultItem> STAR_VAULT = ITEMS.register("star_vault",
            () -> new StarVaultItem(new Item.Properties().stacksTo(1).fireResistant().rarity(Rarity.EPIC)));

    /** Generic / blank infinite cell (no kind stamped); used as a fallback and by the creative tab. */
    public static final DeferredItem<InfiniteCellItem> INFINITE_CELL = ITEMS.register("infinite_cell",
            () -> new InfiniteCellItem(new Item.Properties().stacksTo(1).fireResistant().rarity(Rarity.RARE)));

    /** The bundled "all concrete" preset, with its own id and a pre-stamped kind. */
    public static final DeferredItem<InfiniteCellItem> INFINITE_CONCRETE = ITEMS.register("infinite_concrete",
            () -> new InfiniteCellItem(new Item.Properties().stacksTo(1).fireResistant().rarity(Rarity.RARE)
                    .component(DataComponents.CUSTOM_DATA, CustomData.of(kindTag("aurelium:concrete")))));

    /** Permanent (drive-mounted) infinite disk; BigInteger per key (beyond long), summary-only tooltip. */
    public static final DeferredItem<com.muuxa.aurelium.storage.InfiniteDiskItem> INFINITE_DISK =
            ITEMS.register("infinite_disk",
                    () -> new com.muuxa.aurelium.storage.InfiniteDiskItem(
                            new Item.Properties().stacksTo(1).fireResistant().rarity(Rarity.EPIC)));

    /** Cuts patterns out of a container (bounded by the server config; sneak = cut, click = paste). */
    public static final DeferredItem<com.muuxa.aurelium.pattern.PatternToolItem> PATTERN_CUT_TOOL =
            ITEMS.register("pattern_cut_tool",
                    () -> new com.muuxa.aurelium.pattern.PatternToolItem(
                            new Item.Properties().stacksTo(1).fireResistant().rarity(Rarity.RARE), false));

    /** Copies a container's patterns (unlimited; pasting never consumes the stored batch). */
    public static final DeferredItem<com.muuxa.aurelium.pattern.PatternToolItem> PATTERN_COPY_TOOL =
            ITEMS.register("pattern_copy_tool",
                    () -> new com.muuxa.aurelium.pattern.PatternToolItem(
                            new Item.Properties().stacksTo(1).fireResistant().rarity(Rarity.RARE), true));

    private AureliumItems() {}

    private static CompoundTag kindTag(String kindId) {
        CompoundTag tag = new CompoundTag();
        tag.putString(InfiniteCellInventory.KIND_TAG, kindId);
        return tag;
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}