package com.muuxa.aurelium.network;

import com.muuxa.aurelium.client.InfiniteDiskTooltip;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server -> client: the authoritative summary of one infinite disk (total stored, type counts).
 * The fields are plain/display-ready so nothing large ever crosses the wire.
 */
public record DiskSummaryPage(UUID id, long revision, String total,
                              int types, int itemTypes, int fluidTypes, int otherTypes)
        implements CustomPacketPayload {

    public static final Type<DiskSummaryPage> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("aurelium", "disk_summary_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DiskSummaryPage> STREAM_CODEC =
            StreamCodec.of((buffer, page) -> {
                buffer.writeUUID(page.id);
                buffer.writeVarLong(page.revision);
                buffer.writeUtf(page.total, 64);
                buffer.writeVarInt(page.types);
                buffer.writeVarInt(page.itemTypes);
                buffer.writeVarInt(page.fluidTypes);
                buffer.writeVarInt(page.otherTypes);
            }, buffer -> new DiskSummaryPage(
                    buffer.readUUID(), buffer.readVarLong(), buffer.readUtf(64),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(DiskSummaryPage page, IPayloadContext context) {
        context.enqueueWork(() -> InfiniteDiskTooltip.receiveSummary(page));
    }
}