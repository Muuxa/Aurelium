package com.muuxa.aurelium.client;

import appeng.api.client.StorageCellModels;
import com.muuxa.aurelium.registry.AureliumItems;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Registers the "cell in a drive" models for AURELIUM cells.
 *
 * <p>AE2 renders a cell inside a drive using a dedicated chassis model looked up by item
 * (not the item's own inventory model). Without this, a mounted cell would show AE2's
 * default drive-cell look. We register ours via {@code StorageCellModels}; AE2 loads the
 * model files itself through its drive model's dependencies.</p>
 *
 * <p>Registered manually from {@link AureliumClient} on the mod event bus, because the
 * annotation form that selects the mod bus is deprecated for removal in this NeoForge.</p>
 */
public final class AureliumCellModels {
    private AureliumCellModels() {}

    public static final ResourceLocation VAULT_DRIVE_MODEL =
            ResourceLocation.fromNamespaceAndPath("aurelium", "block/drive/cells/vault_cell");
    public static final ResourceLocation INFINITE_DRIVE_MODEL =
            ResourceLocation.fromNamespaceAndPath("aurelium", "block/drive/cells/infinite_cell");

    /**
     * Model registration events fire on every model (re)load, but
     * {@code StorageCellModels.registerModel} throws if an item is registered twice.
     * Guard so resource-pack reloads do not crash.
     */
    private static boolean registered;

    /**
     * Tell AE2 which chassis model each cell item uses in a drive. Runs on the mod bus and
     * must happen before the drive model is baked; this event fires at the right time.
     */
    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        // AE2 feeds StorageCellModels.models() into its own model-baking step, so registering the
        // ids here is enough and no RegisterAdditional call is needed.
        if (registered) return;
        StorageCellModels.registerModel(AureliumItems.STAR_VAULT.get(), VAULT_DRIVE_MODEL);
        StorageCellModels.registerModel(AureliumItems.INFINITE_CELL.get(), INFINITE_DRIVE_MODEL);
        StorageCellModels.registerModel(AureliumItems.INFINITE_CONCRETE.get(), INFINITE_DRIVE_MODEL);
        registered = true;
    }
}