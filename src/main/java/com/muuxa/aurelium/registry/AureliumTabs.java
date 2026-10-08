package com.muuxa.aurelium.registry;

import com.muuxa.aurelium.Aurelium;
import com.muuxa.aurelium.api.AureliumInfinite;
import com.muuxa.aurelium.api.AureliumInfiniteCellAPI;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's own creative tab, so AURELIUM's items are not buried inside the vanilla
 * "Tools &amp; Utilities" page.
 *
 * <p>Entries are listed in a fixed order: star vault, blank infinite cell, bundled all-concrete
 * preset, infinite disk, the two pattern tools, then the IO port. Any additional infinite kinds
 * declared via KubeJS follow as ready-made cells — best-effort only, since a declaration holds
 * nothing but strings, so an unknown or unregistered item id is skipped at display time.</p>
 */
public final class AureliumTabs {
    private static final Logger LOGGER = LoggerFactory.getLogger("Aurelium/Tabs");

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Aurelium.ID);

    /** Main AURELIUM tab; translation key {@code itemGroup.aurelium}. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.aurelium"))
                    .icon(() -> new ItemStack(AureliumItems.INFINITE_CELL.get()))
                    .displayItems((CreativeModeTab.ItemDisplayParameters parameters,
                                   CreativeModeTab.Output output) -> {
                        // Vanilla AE2 portable cells in creative are charged; mirror that.
                        output.accept(AureliumItems.STAR_VAULT.get().newChargedStack());
                        output.accept(new ItemStack(AureliumItems.INFINITE_CELL.get()));
                        // Bundled preset: the all-concrete infinite cell, with its own item id.
                        output.accept(new ItemStack(AureliumItems.INFINITE_CONCRETE.get()));
                        // Permanent infinite disk (drive-mounted).
                        output.accept(new ItemStack(AureliumItems.INFINITE_DISK.get()));
                        // Pattern tools: move / duplicate a container's pattern stock.
                        output.accept(new ItemStack(AureliumItems.PATTERN_CUT_TOOL.get()));
                        output.accept(new ItemStack(AureliumItems.PATTERN_COPY_TOOL.get()));
                        // Advanced IO Port (pure AE2, always available).
                        output.accept(new ItemStack(
                                com.muuxa.aurelium.registry.AureliumIOPorts.ADVANCE_IO_PORT_ITEM.get()));
                        // Any extra KubeJS-declared kinds become ready-made generic cells too.
                        for (String kindId : AureliumInfinite.all().keySet()) {
                            if (kindId.equals(AureliumInfinite.CONCRETE_ID)) continue;
                            try {
                                output.accept(AureliumInfiniteCellAPI.create(kindId));
                            } catch (RuntimeException failure) {
                                // A malformed declaration must not break the whole tab — but it must
                                // not vanish silently either: that is exactly how "registered a kind
                                // but nothing shows up" looks. Log the real reason.
                                LOGGER.warn("创造栏跳过无限元件种类 '{}'：{}（用 /aurelium infinite {} 复查）",
                                        kindId, failure.toString(), kindId);
                            }
                        }
                    })
                    .build());

    private AureliumTabs() {}

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}