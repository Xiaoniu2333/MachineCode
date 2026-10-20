package com.Xiaoniu2333.mlky.machinecode.velocity;

import com.Xiaoniu2333.mlky.machinecode.common.Protocol;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * Velocity's only job: own the {@code machinecode:main} plugin-message channel and relay a
 * client's report to the player's current backend with the proxy-side UUID attached.
 *
 * <p>There are intentionally no commands on Velocity. All storage, queries and enforcement live
 * on Paper.</p>
 */
@Plugin(
        id = "machinecode",
        name = "MachineCode",
        version = "1.0.0",
        description = "Velocity channel bridge for the MachineCode Paper plugin",
        authors = {"Xiaoniu2333"}
)
public final class MachineCodeVelocityPlugin {
    private static final ChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from(Protocol.CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;

    @Inject
    public MachineCodeVelocityPlugin(final ProxyServer proxy, final Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(final ProxyInitializeEvent event) {
        this.proxy.getChannelRegistrar().register(CHANNEL);
        this.logger.info("MachineCode Velocity bridge registered channel {}", Protocol.CHANNEL);
    }

    @Subscribe
    public void onPluginMessage(final PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }

        // Consume the packet even when malformed so it is not accidentally forwarded twice.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        if (!(event.getSource() instanceof Player player)) {
            // Backend-originated messages on this channel are not part of the protocol.
            return;
        }

        final byte[] data = event.getData();
        if (data.length < 2 || data.length > Protocol.MAX_PAYLOAD_BYTES) {
            this.logger.warn("Dropped a malformed MachineCode payload from {}", player.getUsername());
            return;
        }
        if (data[0] != Protocol.VERSION) {
            this.logger.warn("Dropped an unsupported MachineCode protocol version {} from {}",
                    data[0], player.getUsername());
            return;
        }

        final Optional<ServerConnection> server = player.getCurrentServer();
        if (server.isEmpty()) {
            this.logger.debug("Ignoring MachineCode payload from {} without a backend",
                    player.getUsername());
            return;
        }

        final byte[] framed = Protocol.frameForBackend(player.getUniqueId(), data);
        if (!server.get().sendPluginMessage(CHANNEL, framed)) {
            this.logger.warn("Could not forward MachineCode payload for {} to backend {}",
                    player.getUsername(), server.get().getServerInfo().getName());
            return;
        }
        this.logger.debug("Forwarded MachineCode payload for {} ({} bytes)",
                player.getUsername(), framed.length);
    }
}
