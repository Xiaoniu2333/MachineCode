package com.Xiaoniu2333.mlky.machinecode.fabric;

import com.Xiaoniu2333.mlky.machinecode.common.Protocol;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Fabric payload wrapper for the raw MachineCode bytes.
 *
 * <p>Velocity consumes this channel before a backend sees it, so the payload never needs a
 * server-side Fabric codec. The codec below only serializes the byte array.</p>
 */
public record MachineCodePayload(byte[] data) implements CustomPayload {
    public static final CustomPayload.Id<MachineCodePayload> ID =
            new CustomPayload.Id<>(Identifier.of(MachineCodeClient.MOD_ID, "main"));

    public static final PacketCodec<RegistryByteBuf, MachineCodePayload> CODEC =
            PacketCodec.of(
                    // Do not use writeByteArray/readByteArray here: those add a VarInt length
                    // prefix inside the plugin-message body. Velocity must see the protocol
                    // version as byte 0, so the raw report bytes are written verbatim.
                    (payload, buffer) -> buffer.writeBytes(payload.data()),
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
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
