package com.muuxa.aurelium.network;

import com.muuxa.aurelium.storage.VaultBytes;
import com.muuxa.aurelium.storage.VaultInventory;
import com.muuxa.aurelium.storage.VaultWorldData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Only serves a bounded page for a cell actually held by the requesting player. */
public record VaultPreviewRequest(UUID id, int offset) implements CustomPacketPayload {
    /**
     * Amounts at or above this are shown as the symbol {@code ∞} in the preview row.
     * It is 10^26, i.e. eight more decimal digits than {@link Long#MAX_VALUE} (which has
     * 19 digits), so an ordinary {@code long}-range count is still printed in full while
     * truly astronomical values collapse to ∞.
     *
     * <p>Note: this is only a <b>display</b> choice — the vault is NOT a real infinite
     * source; the count beyond 10^26 is still tracked exactly in BigInteger. Kept as a
     * BigInteger because the threshold exceeds any primitive.</p>
     */
    private static final BigInteger INFINITY_THRESHOLD = BigInteger.TEN.pow(26);
    /** Row token meaning "render the ∞ symbol". */
    private static final String INFINITY_TOKEN = "infinity";

    public static final Type<VaultPreviewRequest> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("aurelium", "vault_preview_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VaultPreviewRequest> STREAM_CODEC =
            StreamCodec.of((buffer, request) -> {
                buffer.writeUUID(request.id);
                buffer.writeVarInt(request.offset);
            }, buffer -> new VaultPreviewRequest(buffer.readUUID(), buffer.readVarInt()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(VaultPreviewRequest request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            // Reject guessed UUIDs belonging to cells outside this inventory.
            boolean held = false;
            for (ItemStack stack : player.getInventory().items) {
                if (request.id.equals(VaultInventory.existingId(stack))) { held = true; break; }
            }
            if (!held && request.id.equals(VaultInventory.existingId(player.getOffhandItem()))) held = true;
            if (!held) {
                for (var slot : player.containerMenu.slots) {
                    if (request.id.equals(VaultInventory.existingId(slot.getItem()))) {
                        held = true;
                        break;
                    }
                }
            }
            if (!held || request.offset < 0) return;
            VaultWorldData world = VaultWorldData.get(player.getServer());
            VaultWorldData.Page page = world.page(request.id, request.offset, 10,
                    player.getServer().registryAccess());
            List<VaultPreviewPage.Entry> rows = new ArrayList<>(page.entries().size());
            for (var entry : page.entries()) {
                BigInteger amount = entry.getValue();
                if (amount.signum() <= 0) continue;
                String name = truncate(entry.getKey().getDisplayName().getString(), 80);
                String type = entry.getKey().getType().getId().toString();
                // Amounts >= 10^26 (eight digits past Long.MAX_VALUE) collapse to ∞.
                // Comparing BigIntegers is O(1)-ish and never lays out a huge string.
                String quantity = amount.compareTo(INFINITY_THRESHOLD) >= 0
                        ? INFINITY_TOKEN
                        : amount.toString();
                rows.add(new VaultPreviewPage.Entry(name, type, quantity));
            }
            PacketDistributor.sendToPlayer(player,
                    new VaultPreviewPage(request.id, request.offset, page.total(),
                            world.revision(request.id), VaultBytes.format(page.usedBytes()),
                            page.itemTypes(), page.fluidTypes(), rows));
        });
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }
}