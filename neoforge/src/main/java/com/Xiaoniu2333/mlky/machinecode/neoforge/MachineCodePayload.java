package com.Xiaoniu2333.mlky.machinecode.neoforge;

import com.Xiaoniu2333.mlky.machinecode.common.Protocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * NeoForge payload wrapper. The byte array is written verbatim; no inner length prefix is added.
 */
public record MachineCodePayload(byte[] data) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<MachineCodePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(MachineCodeNeoForge.MOD_ID, "main"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MachineCodePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> buffer.writeBytes(payload.data()),
                    buffer -> {
                        final byte[] data = new byte[buffer.readableBytes()];
                        buffer.readBytes(data);
                        return new MachineCodePayload(data);
                    }
            );

    public MachineCodePayload {
        if (data == null || data.length == 0 || data.length > Protocol.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid MachineCode payload length");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
