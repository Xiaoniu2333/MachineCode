package com.Xiaoniu2333.mlky.machinecode.paper;

import com.Xiaoniu2333.mlky.machinecode.common.Platform;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * In-memory snapshot used by PlaceholderAPI.
 *
 * <p>PAPI evaluates placeholders on the main thread and may do so many times per tick. SQLite
 * must never be queried there, so the plugin loads one snapshot on startup and updates the cache
 * on join, report and IP changes.</p>
 */
final class PlaceholderCache {
    private final MachineCodePlugin plugin;
    private final Map<UUID, CachedPlayer> byUuid = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    PlaceholderCache(final MachineCodePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Asynchronously seeds the cache from SQLite. PAPI requests that arrive before this finishes
     * simply return empty/false values; the main thread is never blocked.
     */
    void load() {
        this.plugin.database(() -> new Loaded(
                        this.plugin.repository().findAllLatest(),
                        this.plugin.repository().findAllIps()))
                .thenAccept(loaded -> {
                    for (final CachedPlayer cached : this.byUuid.values()) {
                        if (cached.received()) {
                            loaded.records().removeIf(record ->
                                    record.playerId().equals(cached.playerId()));
                        }
                    }
                    for (final MachineCodeRepository.PlayerRecord record : loaded.records()) {
                        final String ip = loaded.ips().getOrDefault(record.playerId(), "");
                        this.byUuid.put(record.playerId(), new CachedPlayer(
                                record.playerName(),
                                record.playerId(),
                                record.machineCode(),
                                record.platform(),
                                ip
                        ));
                        this.byName.put(record.playerName().toLowerCase(Locale.ROOT),
                                record.playerId());
                    }
                })
                .exceptionally(exception -> {
                    this.plugin.getLogger().warning(
                            "Unable to seed the PlaceholderAPI cache: " + exception.getMessage());
                    return null;
                });
    }

    void updateIdentity(final UUID playerId, final String playerName, final String ip) {
        this.byUuid.compute(playerId, (id, old) -> new CachedPlayer(
                playerName,
                playerId,
                old == null ? null : old.machineCode(),
                old == null ? null : old.platform(),
                ip == null ? "" : ip
        ));
        this.byName.put(playerName.toLowerCase(Locale.ROOT), playerId);
    }

    void updateReport(final UUID playerId, final String playerName, final String ip,
                      final String machineCode, final Platform platform) {
        this.byUuid.put(playerId, new CachedPlayer(
                playerName, playerId, machineCode, platform, ip == null ? "" : ip));
        this.byName.put(playerName.toLowerCase(Locale.ROOT), playerId);
    }

    /**
     * Resolves the current player or an explicitly named player.
     *
     * @param context    the player PAPI is evaluating the placeholder for
     * @param targetName optional player ID suffix
     */
    Value resolve(final OfflinePlayer context, final String targetName) {
        if (targetName != null && !targetName.isBlank()) {
            final Player online = Bukkit.getPlayerExact(targetName);
            if (online != null) {
                return this.resolveOnline(online);
            }
            final UUID id = this.byName.get(targetName.toLowerCase(Locale.ROOT));
            final CachedPlayer cached = id == null ? null : this.byUuid.get(id);
            return cached == null ? Value.empty(targetName, null) : cached.toValue();
        }

        if (context == null) {
            return Value.empty("", null);
        }

        final Player online = Bukkit.getPlayer(context.getUniqueId());
        if (online != null) {
            return this.resolveOnline(online);
        }
        final CachedPlayer cached = this.byUuid.get(context.getUniqueId());
        return cached == null
                ? Value.empty(context.getName() == null ? "" : context.getName(),
                        context.getUniqueId())
                : cached.toValue();
    }

    private Value resolveOnline(final Player player) {
        final Optional<MachineCodePlugin.ReportedPlayer> report =
                this.plugin.currentReport(player.getUniqueId());
        final String ip = player.getAddress() == null
                || player.getAddress().getAddress() == null
                ? "" : player.getAddress().getAddress().getHostAddress();
        if (report.isEmpty()) {
            return new Value(player.getName(), player.getUniqueId(), "", null, ip, false);
        }
        final MachineCodePlugin.ReportedPlayer current = report.get();
        return new Value(
                player.getName(),
                player.getUniqueId(),
                current.machineCode(),
                current.platform(),
                ip,
                true
        );
    }

    record Value(
            String playerName,
            UUID playerId,
            String machineCode,
            Platform platform,
            String ip,
            boolean received
    ) {
        static Value empty(final String name, final UUID id) {
            return new Value(name, id, "", null, "", false);
        }

        String platformText() {
            return platform == null ? "" : platform.displayName();
        }
    }

    private record CachedPlayer(
            String playerName,
            UUID playerId,
            String machineCode,
            Platform platform,
            String ip
    ) {
        boolean received() {
            return machineCode != null && !machineCode.isBlank();
        }

        Value toValue() {
            return new Value(playerName, playerId, machineCode, platform, ip, received());
        }
    }

    private record Loaded(
            List<MachineCodeRepository.PlayerRecord> records,
            Map<UUID, String> ips
    ) {
        Loaded {
            Objects.requireNonNull(records, "records");
            Objects.requireNonNull(ips, "ips");
        }
    }
}
