package com.muuxa.aurelium.pattern;

import com.muuxa.aurelium.Aurelium;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Keeps pattern tools within the client's per-item NBT ceiling.
 *
 * <p>An item's NBT travels to the client inside ordinary sync packets, and the client refuses any
 * single NBT above 2 MiB ({@code NbtAccounter.create(2097152L)} in {@code FriendlyByteBuf#readNbt})
 * — exceeding it throws during decode and drops the connection. Tools written by an earlier build
 * stored raw item NBT and could cross that line, which crashed the client the moment the inventory
 * was synced.</p>
 *
 * <p>{@link PlayerEvent.LoadFromFile} fires right after the player's data was read and
 * <b>before</b> {@code PlayerList#placeNewPlayer} calls {@code initInventoryMenu()} (the packet
 * that would crash), so this is the one hook that can rescue an already-oversized tool: the batch
 * is rewritten gzip-compressed, and when even that exceeds the budget the tool is trimmed to what
 * fits — the dropped patterns are logged, never silently discarded at random.</p>
 */
@EventBusSubscriber(modid = Aurelium.ID)
public final class PatternToolGuard {
    private PatternToolGuard() {
    }

    @SubscribeEvent
    public static void onLoadFromFile(PlayerEvent.LoadFromFile event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.player.Player player)) return;
        // Only the server side matters here; the client copy is what we are protecting.
        if (player.level().isClientSide) return;
        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!(stack.getItem() instanceof PatternToolItem)) continue;
            if (PatternToolData.shrinkToBudget(stack, player.registryAccess())) changed = true;
        }
        if (changed) {
            LOGGER.info("AURELIUM：已把样板工具的数据重写为压缩格式并把体积压回安全范围。");
        }
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Aurelium/PatternTool");
}
