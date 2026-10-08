package com.muuxa.aurelium.client;

import com.muuxa.aurelium.Aurelium;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Dedicated-client entry point; leaves server free of GUI classes. */
@Mod(value = Aurelium.ID, dist = Dist.CLIENT)
public final class AureliumClient {
    public AureliumClient(ModContainer container, IEventBus modBus) {
        // Game-bus tooltip hооk (vault + infinite cell decoration).
        NeoForge.EVENT_BUS.register(PinkTooltip.class);
        NeoForge.EVENT_BUS.register(StarwishTooltip.class);
        NeoForge.EVENT_BUS.register(InfiniteCellTooltip.class);
        NeoForge.EVENT_BUS.register(InfiniteDiskTooltip.class);

        // Mod-bus listeners (manual registration avoids the deprecated @EventBusSubscriber bus=).
        modBus.addListener(AureliumClient::registerTooltipFactories);
        modBus.addListener(AureliumCellModels::onRegisterAdditional);
        // Per-kind custom appearance for the infinite cell (KubeJS-declared model/texture).
        modBus.addListener(AureliumInfiniteModels::onRegisterAdditional);
        modBus.addListener(AureliumInfiniteModels::onModifyBaking);
    }

    private static void registerTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(InfiniteIconsTooltip.class, ClientInfiniteIconsTooltip::new);
    }

}
