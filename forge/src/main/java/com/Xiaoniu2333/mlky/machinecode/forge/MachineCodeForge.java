package com.Xiaoniu2333.mlky.machinecode.forge;

import com.Xiaoniu2333.mlky.machinecode.common.HardwareFingerprint;
import com.Xiaoniu2333.mlky.machinecode.common.Protocol;
import com.mojang.logging.LogUtils;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;
import org.slf4j.Logger;

/**
 * Forge 1.21.11 client entrypoint.
 *
 * <p>Forge's 1.21.11 networking API is SimpleChannel based. The channel is marked optional so the
 * client can connect to a Velocity proxy that does not negotiate Forge mod channels; the report is
 * sent as an ordinary {@code machinecode:main} custom payload and consumed by the proxy plugin.</p>
 */
@Mod(MachineCodeForge.MOD_ID)
public final class MachineCodeForge {
    public static final String MOD_ID = "machinecode";
    public static final String VERSION = "1.0.0";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "MachineCode-Forge-Probe");
                thread.setDaemon(true);
                return thread;
            });

    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(MOD_ID, "main"))
            .networkProtocolVersion(Protocol.VERSION)
            .optional()
            .simpleChannel()
            .play()
            .serverbound()
            .addMain(
                    MachineCodePayload.class,
                    MachineCodePayload.STREAM_CODEC,
                    (payload, context) -> {
                        // The Velocity proxy consumes this channel before Paper sees it.
                    })
            .build();

    private volatile HardwareFingerprint.CapturedFingerprint cached;

    public MachineCodeForge(final FMLJavaModLoadingContext context) {
        try {
            this.cached = HardwareFingerprint.capture();
            LOGGER.info("MachineCode initial fingerprint ready: platform={}, os={} {} ({})",
                    this.cached.platform(), this.cached.osName(),
                    this.cached.osVersion(), this.cached.architecture());
        } catch (final Throwable throwable) {
            MachineCodeCrash.failAndCrash("client startup", throwable, LOGGER);
        }

        ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(this::onLogin);
        LOGGER.info("MachineCode Forge client initialized ({}).", VERSION);
    }

    private void onLogin(final ClientPlayerNetworkEvent.LoggingIn event) {
        final Connection connection = event.getConnection();
        SCHEDULER.execute(() -> {
            try {
                final HardwareFingerprint.CapturedFingerprint fresh =
                        HardwareFingerprint.capture();
                this.cached = fresh;
                LOGGER.info("MachineCode re-acquired on join: platform={}, os={} ({})",
                        fresh.platform(), fresh.osName(), fresh.architecture());
                Minecraft.getInstance().execute(() -> sendReport(connection, fresh));
            } catch (final Throwable throwable) {
                Minecraft.getInstance().execute(() ->
                        MachineCodeCrash.failAndCrash("server join", throwable, LOGGER));
            }
        });
    }

    private void sendReport(final Connection connection,
                            final HardwareFingerprint.CapturedFingerprint fingerprint) {
        if (connection == null || !connection.isConnected()
                || Minecraft.getInstance().getConnection() == null) {
            return;
        }

        try {
            final Protocol.ClientReport report =
                    Protocol.ClientReport.from(fingerprint, VERSION);
            CHANNEL.send(new MachineCodePayload(report.encode()), connection);
            LOGGER.info("Sent MachineCode report for platform {} to the server.", report.platform());
        } catch (final RuntimeException exception) {
            LOGGER.error("Unable to send the MachineCode report", exception);
        }
    }
}
