package com.Xiaoniu2333.mlky.machinecode.paper;

import com.Xiaoniu2333.mlky.machinecode.common.MachineCodeValidator;
import com.Xiaoniu2333.mlky.machinecode.common.Platform;
import com.Xiaoniu2333.mlky.machinecode.common.Protocol;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Paper/Bukkit backend for MachineCode.
 *
 * <p>The backend never blocks the login thread. A player joins normally, the client mod sends its
 * machine code through Velocity, and only then is the ban state evaluated. If enforcement is
 * enabled, a missing report causes a kick after the configured grace period.</p>
 */
public final class MachineCodePlugin extends JavaPlugin {
    private final Map<UUID, PendingJoin> pendingJoins = new ConcurrentHashMap<>();
    private final Map<UUID, ReportedPlayer> currentReports = new ConcurrentHashMap<>();

    private PluginConfig pluginConfig;
    private MachineCodeRepository repository;
    private ExecutorService databaseExecutor;
    private PlaceholderCache placeholderCache;
    private MachineCodePlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(this, getConfig());

        this.databaseExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread thread = new Thread(runnable,
                        "MachineCode-SQLite-" + this.counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });

        try {
            this.repository = new MachineCodeRepository(this.pluginConfig.databaseFile());
            this.repository.open();
        } catch (final RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Unable to initialise the MachineCode database", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.placeholderCache = new PlaceholderCache(this);
        this.placeholderCache.load();

        getServer().getMessenger().registerIncomingPluginChannel(
                this, Protocol.CHANNEL, (channel, player, message) -> handlePluginMessage(player, message));
        getServer().getMessenger().registerOutgoingPluginChannel(this, Protocol.CHANNEL);

        final MachineCodeCommand command = new MachineCodeCommand(this);
        if (getCommand("machinecode") == null) {
            getLogger().severe("Command machinecode is missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("machinecode").setExecutor(command);
        getCommand("machinecode").setTabCompleter(command);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                this.placeholderExpansion = new MachineCodePlaceholderExpansion(this);
                if (this.placeholderExpansion.register()) {
                    getLogger().info("Registered PlaceholderAPI expansion 'machinecode'.");
                } else {
                    getLogger().warning("PlaceholderAPI expansion 'machinecode' was already registered.");
                }
            } catch (final RuntimeException exception) {
                getLogger().log(Level.WARNING,
                        "Unable to register the PlaceholderAPI expansion", exception);
            }
        }

        getLogger().info("MachineCode backend enabled; database=" + this.pluginConfig.databaseFile());
        if (this.pluginConfig.enforcementEnabled()) {
            getLogger().info("Enforcement is enabled with a "
                    + this.pluginConfig.graceSeconds() + " second grace period.");
        } else {
            getLogger().info("Enforcement is disabled; reports are still stored and bans still apply.");
        }
    }

    @Override
    public void onDisable() {
        for (final PendingJoin pending : this.pendingJoins.values()) {
            cancel(pending.timeoutTask);
            cancel(pending.reminderTask);
        }
        this.pendingJoins.clear();
        this.currentReports.clear();
        if (this.placeholderExpansion != null) {
            this.placeholderExpansion.unregister();
            this.placeholderExpansion = null;
        }

        if (this.repository != null) {
            this.repository.close();
        }
        if (this.databaseExecutor != null) {
            this.databaseExecutor.shutdownNow();
        }
        try {
            getServer().getMessenger().unregisterIncomingPluginChannel(this, Protocol.CHANNEL);
            getServer().getMessenger().unregisterOutgoingPluginChannel(this, Protocol.CHANNEL);
        } catch (final IllegalStateException ignored) {
            // The messenger may already be unavailable during a server shutdown.
        }
    }

    PluginConfig pluginConfig() {
        return this.pluginConfig;
    }

    MachineCodeRepository repository() {
        return this.repository;
    }

    PlaceholderCache placeholderCache() {
        return this.placeholderCache;
    }

    Optional<ReportedPlayer> currentReport(final UUID playerId) {
        return Optional.ofNullable(this.currentReports.get(playerId));
    }

    /**
     * Runs a database operation on the single SQLite thread.
     */
    <T> CompletableFuture<T> database(final Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, this.databaseExecutor);
    }

    void runSync(final Runnable runnable) {
        if (!isEnabled()) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            runnable.run();
        } else {
            Bukkit.getScheduler().runTask(this, runnable);
        }
    }

    void send(final org.bukkit.command.CommandSender sender,
              final String configuredMessage,
              final String... placeholders) {
        final String formatted = this.pluginConfig.format(configuredMessage, placeholders);
        this.runSync(() -> sender.sendMessage(Text.parse(formatted)));
    }

    Optional<String> currentCode(final UUID playerId) {
        return Optional.ofNullable(this.currentReports.get(playerId))
                .map(ReportedPlayer::machineCode);
    }

    Optional<Platform> currentPlatform(final UUID playerId) {
        return Optional.ofNullable(this.currentReports.get(playerId))
                .map(ReportedPlayer::platform);
    }

    void playerJoined(final Player player) {
        final UUID playerId = player.getUniqueId();
        final String playerName = player.getName();
        final String ip = addressOf(player);

        final PendingJoin previous = this.pendingJoins.remove(playerId);
        if (previous != null) {
            cancel(previous.timeoutTask);
            cancel(previous.reminderTask);
        }

        final PendingJoin pending = new PendingJoin(playerId, playerName);
        this.pendingJoins.put(playerId, pending);
        this.placeholderCache.updateIdentity(playerId, playerName, ip);

        this.database(() -> {
            this.repository.saveIp(playerId, playerName, ip);
            return null;
        }).exceptionally(exception -> {
            getLogger().log(Level.WARNING,
                    "Unable to persist the IP address of " + playerName, exception);
            return null;
        });

        if (this.pluginConfig.enforcementEnabled()) {
            final long delayTicks = Math.max(0L, this.pluginConfig.graceSeconds() * 20L);
            if (delayTicks == 0L) {
                pending.timeoutTask = Bukkit.getScheduler().runTask(this,
                        () -> checkTimeout(playerId, pending));
            } else {
                pending.timeoutTask = Bukkit.getScheduler().runTaskLater(this,
                        () -> checkTimeout(playerId, pending), delayTicks);
            }
            return;
        }

        // Enforcement is off: remind the player periodically instead of kicking them.
        scheduleReminder(playerId, pending);
    }

    void playerQuit(final UUID playerId) {
        final PendingJoin pending = this.pendingJoins.remove(playerId);
        if (pending != null) {
            cancel(pending.timeoutTask);
            cancel(pending.reminderTask);
        }
        this.currentReports.remove(playerId);
    }

    private void scheduleReminder(final UUID playerId, final PendingJoin pending) {
        if (!this.pluginConfig.missingCodeNotifyEnabled() || pending.received) {
            return;
        }
        final long intervalTicks = Math.max(1L,
                this.pluginConfig.missingCodeNotifyIntervalSeconds() * 20L);
        pending.reminderTask = Bukkit.getScheduler().runTaskTimer(this,
                () -> remindMissingCode(playerId, pending),
                intervalTicks,
                intervalTicks);
    }

    private void remindMissingCode(final UUID playerId, final PendingJoin pending) {
        if (pending.received || this.pendingJoins.get(playerId) != pending) {
            cancel(pending.reminderTask);
            return;
        }
        if (this.pluginConfig.enforcementEnabled()
                || !this.pluginConfig.missingCodeNotifyEnabled()) {
            cancel(pending.reminderTask);
            return;
        }
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            cancel(pending.reminderTask);
            return;
        }
        player.sendMessage(Text.parse(this.pluginConfig.missingCodeNotifyMessage()));
    }

    private void checkTimeout(final UUID playerId, final PendingJoin pending) {
        if (pending.received || this.pendingJoins.get(playerId) != pending) {
            return;
        }
        final Player player = Bukkit.getPlayer(playerId);
        this.pendingJoins.remove(playerId, pending);
        if (player == null || !player.isOnline()) {
            return;
        }

        final String message = this.pluginConfig.format(
                this.pluginConfig.timeoutKickMessage(),
                "player", player.getName(),
                "seconds", Integer.toString(this.pluginConfig.graceSeconds()));
        this.runSync(() -> player.kick(Text.parse(message)));
        getLogger().info("Kicked " + player.getName()
                + ": no machine code received within the grace period.");
    }

    private void handlePluginMessage(final Player player, final byte[] message) {
        if (!player.isOnline()) {
            return;
        }
        try {
            final Protocol.ForwardedReport forwarded = Protocol.decodeForwarded(message);
            if (!forwarded.playerId().equals(player.getUniqueId())) {
                getLogger().warning("Ignored a MachineCode report whose proxy UUID did not match "
                        + player.getName());
                return;
            }

            final Protocol.ClientReport report = forwarded.report();
            if (!MachineCodeValidator.isMachineCode(report.machineCode())) {
                getLogger().warning("Ignored a report with an invalid machine code from "
                        + player.getName());
                return;
            }

            final PendingJoin pending = this.pendingJoins.get(player.getUniqueId());
            if (pending != null) {
                pending.received = true;
                pending.machineCode = report.machineCode();
                cancel(pending.timeoutTask);
                cancel(pending.reminderTask);
            }
            this.currentReports.put(player.getUniqueId(), new ReportedPlayer(
                    report.machineCode(), report.platform(), report.osName(),
                    report.osVersion(), report.architecture(), report.modVersion()));

            final UUID playerId = player.getUniqueId();
            final String playerName = player.getName();
            final String ip = addressOf(player);
            this.placeholderCache.updateReport(
                    playerId, playerName, ip, report.machineCode(), report.platform());

            this.database(() -> {
                this.repository.saveIp(playerId, playerName, ip);
                return this.repository.saveReport(
                        playerId, playerName, report.platform(), report.machineCode());
            }).whenComplete((banned, exception) -> {
                if (exception != null) {
                    getLogger().log(Level.SEVERE,
                            "Unable to store the machine code of " + playerName, exception);
                    return;
                }
                if (Boolean.TRUE.equals(banned)) {
                    this.runSync(() -> kickBanned(playerId, report.machineCode()));
                }
            });
        } catch (final Protocol.ProtocolException exception) {
            getLogger().warning("Rejected malformed MachineCode payload from "
                    + player.getName() + ": " + exception.getMessage());
        } catch (final RuntimeException exception) {
            getLogger().log(Level.WARNING,
                    "Error while handling a MachineCode payload from " + player.getName(),
                    exception);
        }
    }

    void kickBanned(final UUID playerId, final String machineCode) {
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        final String message = this.pluginConfig.format(
                this.pluginConfig.bannedKickMessage(),
                "player", player.getName(),
                "machine_code", machineCode,
                "reason", "机器码封禁");
        player.kick(Text.parse(message));
        getLogger().info("Kicked " + player.getName() + " for banned machine code "
                + machineCode.substring(0, 8) + "...");
    }

    void reloadPluginConfig() {
        reloadConfig();
        this.pluginConfig = new PluginConfig(this, getConfig());

        // Apply the new reminder settings to players who are already online.
        for (final PendingJoin pending : this.pendingJoins.values()) {
            if (pending.received) {
                continue;
            }
            if (this.pluginConfig.enforcementEnabled()
                    || !this.pluginConfig.missingCodeNotifyEnabled()) {
                cancel(pending.reminderTask);
                pending.reminderTask = null;
            } else if (pending.reminderTask == null || pending.reminderTask.isCancelled()) {
                scheduleReminder(pending.playerId, pending);
            }
        }
    }

    private static String addressOf(final Player player) {
        if (player.getAddress() == null || player.getAddress().getAddress() == null) {
            return "unknown";
        }
        return player.getAddress().getAddress().getHostAddress();
    }

    private static void cancel(final BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private static final class PendingJoin {
        private final UUID playerId;
        private final String playerName;
        private volatile boolean received;
        private volatile String machineCode;
        private BukkitTask timeoutTask;
        private BukkitTask reminderTask;

        private PendingJoin(final UUID playerId, final String playerName) {
            this.playerId = playerId;
            this.playerName = playerName;
        }
    }

    record ReportedPlayer(
            String machineCode,
            Platform platform,
            String osName,
            String osVersion,
            String architecture,
            String modVersion
    ) {
    }
}
