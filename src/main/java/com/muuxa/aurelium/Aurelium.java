package com.muuxa.aurelium;

import appeng.api.storage.StorageCells;
import appeng.api.upgrades.Upgrades;
import appeng.core.definitions.AEItems;
import com.muuxa.aurelium.api.AureliumInfinite;
import com.muuxa.aurelium.registry.AureliumItems;
import com.muuxa.aurelium.registry.AureliumTabs;
import com.muuxa.aurelium.registry.AureliumIOPorts;
import com.muuxa.aurelium.network.VaultPreviewPage;
import com.muuxa.aurelium.network.VaultPreviewRequest;
import com.muuxa.aurelium.storage.InfiniteCellHandler;
import com.muuxa.aurelium.storage.VaultCellHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/** Registers AURELIUM's items, creative tab, server config, AE2 cell handlers and network payloads. */
@Mod(Aurelium.ID)
public final class Aurelium {
    public static final String ID = "aurelium";

    public Aurelium(IEventBus modBus, ModContainer container) {
        AureliumItems.register(modBus);
        AureliumTabs.register(modBus);
        // Server config (vault nesting cap, pattern cut limit).
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, AureliumConfig.SPEC);
        StorageCells.addCellHandler(new VaultCellHandler());
        StorageCells.addCellHandler(new InfiniteCellHandler());
        StorageCells.addCellHandler(new com.muuxa.aurelium.storage.InfiniteDiskCellHandler());
        // Bundled preset: the "all concrete" infinite cell.
        AureliumInfinite.registerDefaults();
        // AURELIUM's own IO port: pure AE2, no ExtendedAE needed.
        AureliumIOPorts.register(modBus);
        modBus.addListener(this::onCommonSetup);
        modBus.addListener(this::registerCapabilities);
        modBus.addListener(this::registerPayloads);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(VaultPreviewRequest.TYPE, VaultPreviewRequest.STREAM_CODEC,
                VaultPreviewRequest::handle);
        registrar.playToClient(VaultPreviewPage.TYPE, VaultPreviewPage.STREAM_CODEC,
                VaultPreviewPage::handle);
        // The infinite disk's contents live server-side, so its tooltip summary is fetched the same
        // way as the vault's preview.
        registrar.playToServer(com.muuxa.aurelium.network.DiskSummaryRequest.TYPE,
                com.muuxa.aurelium.network.DiskSummaryRequest.STREAM_CODEC,
                com.muuxa.aurelium.network.DiskSummaryRequest::handle);
        registrar.playToClient(com.muuxa.aurelium.network.DiskSummaryPage.TYPE,
                com.muuxa.aurelium.network.DiskSummaryPage.STREAM_CODEC,
                com.muuxa.aurelium.network.DiskSummaryPage::handle);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // Register FE input for every StarVaultItem, not just the built-in id, so vaults that
        // KubeJS created at registry time (exported vaults with their own id) can charge too.
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (item instanceof com.muuxa.aurelium.storage.StarVaultItem vaultItem) {
                event.registerItem(Capabilities.EnergyStorage.ITEM,
                        (stack, context) -> vaultItem.feInput(stack), item);
            }
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        // AE2 upgrade association is per-item; inheritance alone does not allow cards.
        // The normal energy card is recognized by AE2. Third-party large cards need
        // their own integration to register themselves for this upgradable item.
        event.enqueueWork(() -> {
            Upgrades.add(AEItems.ENERGY_CARD, AureliumItems.STAR_VAULT.get(), 2);
            // Link our IO port BE type to its item so AE2 can resolve its display name.
            AureliumIOPorts.registerBlockEntityItems();
        });
    }
}