package com.Xiaoniu2333.mlky.machinecode.neoforge;

import com.Xiaoniu2333.mlky.machinecode.common.HardwareFingerprint;
import com.Xiaoniu2333.mlky.machinecode.common.Protocol;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NeoForge 1.21.11 client entrypoint.
 *
 * <p>The mod is client-only. It registers the plugin-message payload, captures the fingerprint at
 * startup, re-captures asynchronously after login, and sends the report once the proxy channel is
 * visible. No Minecraft login/world-loading thread is blocked.</p>
 */
@Mod(value = MachineCodeNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class MachineCodeNeoForge {
    public static final String MOD_ID = "machinecode";
    public static final String VERSION = "1.0.0";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "MachineCode-NeoForge-Probe");
                thread.setDaemon(true);
                return thread;
            });

    private volatile HardwareFingerprint.CapturedFingerprint cached;

    public MachineCodeNeoForge(final IEventBus modEventBus) {
        try {
            this.cached = HardwareFingerprint.capture();
            LOGGER.info("MachineCode initial fingerprint ready: platform={}, os={} {} ({})",
                    this.cached.platform(), this.cached.osName(),
                    this.cached.osVersion(), this.cached.architecture());
        } catch (final Throwable throwable) {
            MachineCodeCrash.failAndCrash("client startup", throwable, LOGGER);
        }

        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.addListener(this::onLogin);
        LOGGER.info("MachineCode NeoForge client initialized ({}).", VERSION);
    }

    private void registerPayloads(final RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToServer(
                        MachineCodePayload.TYPE,
                        MachineCodePayload.STREAM_CODEC,
                        (payload, context) -> {
                            // The Velocity proxy consumes this channel before Paper sees it.
                        });
    }

    private void onLogin(final ClientPlayerNetworkEvent.LoggingIn event) {
        SCHEDULER.execute(() -> {
            try {
                final HardwareFingerprint.CapturedFingerprint fresh =
                        HardwareFingerprint.capture();
                this.cached = fresh;
                LOGGER.info("MachineCode re-acquired on join: platform={}, os={} ({})",
                        fresh.platform(), fresh.osName(), fresh.architecture());
                Minecraft.getInstance().execute(() -> sendReport(fresh));
            } catch (final Throwable throwable) {
                Minecraft.getInstance().execute(() ->
                        MachineCodeCrash.failAndCrash("server join", throwable, LOGGER));
            }
        });
    }

    private void sendReport(final HardwareFingerprint.CapturedFingerprint fingerprint) {
        final Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null || client.player == null) {
            return;
        }

        try {
            final Protocol.ClientReport report =
                    Protocol.ClientReport.from(fingerprint, VERSION);
            ClientPacketDistributor.sendToServer(new MachineCodePayload(report.encode()));
            LOGGER.info("Sent MachineCode report for platform {} to the server.", report.platform());
        } catch (final RuntimeException exception) {
            LOGGER.error("Unable to send the MachineCode report", exception);
        }
    }
}
