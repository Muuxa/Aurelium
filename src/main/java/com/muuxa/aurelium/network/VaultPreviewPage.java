package com.muuxa.aurelium.network;
import com.muuxa.aurelium.client.StarwishTooltip;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Plain display strings only; capped page avoids copying full SavedData to an item. */
public record VaultPreviewPage(UUID id, int offset, int total, long revision,
                               String usedBytes, int itemTypes, int fluidTypes,
                               List<Entry> entries) implements CustomPacketPayload {
    public record Entry(String name, String type, String quantity) {}

    public static final Type<VaultPreviewPage> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("aurelium", "vault_preview_page"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VaultPreviewPage> STREAM_CODEC =
            StreamCodec.of((buffer, page) -> {
                buffer.writeUUID(page.id);
                buffer.writeVarInt(page.offset);
                buffer.writeVarInt(page.total);
                buffer.writeVarLong(page.revision);
                buffer.writeUtf(page.usedBytes, 1235);
                buffer.writeVarInt(page.itemTypes);
                buffer.writeVarInt(page.fluidTypes);
                buffer.writeVarInt(page.entries.size());
                for (Entry entry : page.entries) {
                    buffer.writeUtf(entry.name, 80);
                    buffer.writeUtf(entry.type, 100);
                    buffer.writeUtf(entry.quantity, 1235);
                }
            }, buffer -> {
                UUID id = buffer.readUUID();
                int offset = buffer.readVarInt();
                int total = buffer.readVarInt();
                long revision = buffer.readVarLong();
                String usedBytes = buffer.readUtf(1235);
                int itemTypes = buffer.readVarInt();
                int fluidTypes = buffer.readVarInt();
                int size = buffer.readVarInt();
                if (size < 0 || size > 10) throw new IllegalArgumentException("Invalid vault preview size");
                List<Entry> entries = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    entries.add(new Entry(buffer.readUtf(80), buffer.readUtf(100), buffer.readUtf(1235)));
                }
                return new VaultPreviewPage(id, offset, total, revision,
                        usedBytes, itemTypes, fluidTypes, List.copyOf(entries));
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(VaultPreviewPage page, IPayloadContext context) {
        context.enqueueWork(() -> StarwishTooltip.receivePage(page));
    }
}