package com.Xiaoniu2333.mlky.machinecode.fabric;

import com.Xiaoniu2333.mlky.machinecode.common.HardwareFingerprint;
import com.Xiaoniu2333.mlky.machinecode.common.Protocol;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric client entrypoint.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>At client startup, read/validate/hash the hardware identifier. A failure shows the
 *       support popup and crashes the client.</li>
 *   <li>When a server is joined, re-read the identifier asynchronously. Minecraft's login and
 *       world-loading flow is never blocked by this work.</li>
 *   <li>After the probe finishes, send the report on {@code machinecode:main}. The channel is
 *       only used when the server (through Velocity) advertised it, so the mod does not leak the
 *       fingerprint to unrelated servers.</li>
 * </ol>
 */
public final class MachineCodeClient implements ClientModInitializer {
    public static final String MOD_ID = "machinecode";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final int MAX_CHANNEL_ATTEMPTS = 10;
    private static final long CHANNEL_RETRY_MILLIS = 500L;

    private static final ExecutorService PROBE_EXECUTOR =
            Executors.newSingleThreadExecutor(daemonFactory("MachineCode-Probe"));
    private static final ScheduledExecutorService RETRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("MachineCode-Retry"));
    private static final Set<ClientPlayNetworkHandler> STARTED_CONNECTIONS =
            ConcurrentHashMap.newKeySet();

    private volatile HardwareFingerprint.CapturedFingerprint cached;

    @Override
    public void onInitializeClient() {
        PayloadTypeRegistry.playC2S().register(MachineCodePayload.ID, MachineCodePayload.CODEC);

        try {
            this.cached = HardwareFingerprint.capture();
            LOGGER.info("MachineCode initial fingerprint ready: platform={}, os={} {} ({})",
                    this.cached.platform(),
                    this.cached.osName(),
                    this.cached.osVersion(),
                    this.cached.architecture());
        } catch (final Throwable throwable) {
            // Required behaviour: no fallback identifier. Show the native dialog and crash.
            ClientCrashHandler.failAndCrash("client startup", throwable, LOGGER);
        }

        ClientPlayConnectionEvents.JOIN.register(this::onJoin);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                STARTED_CONNECTIONS.remove(handler));

        LOGGER.info("MachineCode Fabric client initialized ({}).", modVersion());
    }

    private void onJoin(final ClientPlayNetworkHandler handler,
                        final net.fabricmc.fabric.api.networking.v1.PacketSender sender,
                        final MinecraftClient client) {
        if (!STARTED_CONNECTIONS.add(handler)) {
            return;
        }

        // Never perform a WMI/subprocess call on the render or network thread.
        PROBE_EXECUTOR.execute(() -> {
            try {
                final HardwareFingerprint.CapturedFingerprint fresh =
                        HardwareFingerprint.capture();
                this.cached = fresh;
                LOGGER.info("MachineCode re-acquired on join: platform={}, os={} ({})",
                        fresh.platform(), fresh.osName(), fresh.architecture());
                client.execute(() -> sendWithRetry(handler, client, fresh, 0));
            } catch (final Throwable throwable) {
                client.execute(() ->
                        ClientCrashHandler.failAndCrash("server join", throwable, LOGGER));
            }
        });
    }

    private void sendWithRetry(final ClientPlayNetworkHandler handler,
                               final MinecraftClient client,
                               final HardwareFingerprint.CapturedFingerprint fingerprint,
                               final int attempt) {
        if (client.getNetworkHandler() != handler || client.player == null) {
            return;
        }

        if (!ClientPlayNetworking.canSend(MachineCodePayload.ID)) {
            if (attempt >= MAX_CHANNEL_ATTEMPTS) {
                LOGGER.warn("MachineCode channel was not advertised by this server; "
                        + "no fingerprint was sent.");
                return;
            }
            RETRY_EXECUTOR.schedule(
                    () -> client.execute(() ->
                            sendWithRetry(handler, client, fingerprint, attempt + 1)),
                    CHANNEL_RETRY_MILLIS,
                    TimeUnit.MILLISECONDS);
            return;
        }

        try {
            final Protocol.ClientReport report =
                    Protocol.ClientReport.from(fingerprint, modVersion());
            ClientPlayNetworking.send(new MachineCodePayload(report.encode()));
            LOGGER.info("Sent MachineCode report for platform {} to the server.",
                    report.platform());
        } catch (final RuntimeException exception) {
            // A network/protocol failure is not a fingerprint failure and must not crash the game.
            LOGGER.error("Unable to send the MachineCode report", exception);
        }
    }

    private static String modVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("1.0.0");
    }

    private static ThreadFactory daemonFactory(final String prefix) {
        final AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            final Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
