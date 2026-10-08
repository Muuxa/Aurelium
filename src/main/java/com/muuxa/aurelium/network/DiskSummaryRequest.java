package com.muuxa.aurelium.network;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.muuxa.aurelium.storage.InfiniteDiskInventory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;

/**
 * Client -> server: "what does my infinite disk with this UUID currently hold?".
 *
 * <p>A disk's contents live in server-side world data ({@code disk_cells_<uuid>.nbt}), so the client
 * tooltip can never read them directly. The tooltip asks the server (this packet) and the server
 * answers with a small {@link DiskSummaryPage} — exactly like the star vault does.</p>
 */
public record DiskSummaryRequest(UUID id) implements CustomPacketPayload {

    public static final Type<DiskSummaryRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("aurelium", "disk_summary_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DiskSummaryRequest> STREAM_CODEC =
            StreamCodec.of((buffer, request) -> buffer.writeUUID(request.id),
                    buffer -> new DiskSummaryRequest(buffer.readUUID()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(DiskSummaryRequest request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // Only answer for a disk the player actually holds / has open.
            if (!holds(player, request.id())) return;
            var server = player.getServer();
            if (server == null || request.id() == null) return;

            var world = com.muuxa.aurelium.storage.InfiniteDiskWorldData.get(server);
            var registries = server.registryAccess();
            Map<AEKey, BigInteger> contents = world.liveMap(request.id(), registries);

            BigInteger total = BigInteger.ZERO;
            int items = 0, fluids = 0, others = 0;
            for (Map.Entry<AEKey, BigInteger> e : contents.entrySet()) {
                BigInteger amount = e.getValue();
                if (amount == null || amount.signum() <= 0) continue;
                total = total.add(amount);
                AEKey key = e.getKey();
                if (key instanceof AEItemKey) items++;
                else if (key instanceof AEFluidKey) fluids++;
                else others++;
            }
            PacketDistributor.sendToPlayer(player, new DiskSummaryPage(
                    request.id(), world.revision(request.id()), formatTotal(total),
                    items + fluids + others, items, fluids, others));
        });
    }

    private static boolean holds(ServerPlayer player, UUID id) {
        if (id == null) return false;
        for (ItemStack stack : player.getInventory().items) {
            if (id.equals(InfiniteDiskInventory.existingId(stack))) return true;
        }
        if (id.equals(InfiniteDiskInventory.existingId(player.getOffhandItem()))) return true;
        for (var slot : player.containerMenu.slots) {
            if (id.equals(InfiniteDiskInventory.existingId(slot.getItem()))) return true;
        }
        return false;
    }

    /** Bounded, human-friendly render so a colossal total never bloats the packet. */
    private static String formatTotal(BigInteger value) {
        if (value == null || value.signum() <= 0) return "0";
        String digits = value.toString();
        if (digits.length() <= 32) return digits;
        return digits.substring(0, 1) + "." + digits.substring(1, 4) + "e+" + (digits.length() - 1);
    }
}