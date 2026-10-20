package com.Xiaoniu2333.mlky.machinecode.forge;

import com.Xiaoniu2333.mlky.machinecode.common.Protocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Forge simple-channel message. The report bytes are copied verbatim, without a nested length.
 */
public record MachineCodePayload(byte[] data) {
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
}
