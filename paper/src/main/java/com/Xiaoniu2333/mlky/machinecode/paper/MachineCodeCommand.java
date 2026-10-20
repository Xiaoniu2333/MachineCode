package com.Xiaoniu2333.mlky.machinecode.paper;

import com.Xiaoniu2333.mlky.machinecode.common.MachineCodeValidator;
import com.Xiaoniu2333.mlky.machinecode.common.Platform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * The single core command. Velocity deliberately has no command.
 *
 * <pre>
 * /mcode [player]       query a player's current report (five lines)
 * /mcode &lt;machineCode&gt;  reverse lookup (three lines)
 * /mcode ban &lt;code&gt;    ban a machine code
 * /mcode unban &lt;code&gt;  remove a machine-code ban
 * /mcode reload         reload config.yml
 * </pre>
 */
final class MachineCodeCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("ban", "unban", "reload", "help");

    private final MachineCodePlugin plugin;

    MachineCodeCommand(final MachineCodePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                this.plugin.send(sender, this.plugin.pluginConfig().playersOnly());
                return true;
            }
            if (!has(sender, "machinecode.query")) {
                return true;
            }
            this.queryPlayer(sender, player.getName());
            return true;
        }

        final String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "ban" -> {
                if (!has(sender, "machinecode.ban")) {
                    return true;
                }
                if (args.length < 2) {
                    this.plugin.send(sender, this.plugin.pluginConfig().usage());
                    return true;
                }
                this.ban(sender, args[1]);
                return true;
            }
            case "unban" -> {
                if (!has(sender, "machinecode.ban")) {
                    return true;
                }
                if (args.length < 2) {
                    this.plugin.send(sender, this.plugin.pluginConfig().usage());
                    return true;
                }
                this.unban(sender, args[1]);
                return true;
            }
            case "reload" -> {
                if (!has(sender, "machinecode.reload")) {
                    return true;
                }
                this.plugin.reloadPluginConfig();
                this.plugin.send(sender, this.plugin.pluginConfig().reloaded());
                return true;
            }
            case "help" -> {
                this.plugin.send(sender, this.plugin.pluginConfig().usage());
                return true;
            }
            default -> {
                if (args.length > 1) {
                    this.plugin.send(sender, this.plugin.pluginConfig().usage());
                    return true;
                }
                final String input = args[0];
                if (looksLikeMachineCode(input)) {
                    if (!has(sender, "machinecode.lookup")) {
                        return true;
                    }
                    this.lookupMachineCode(sender, normalizeCode(input));
                } else {
                    if (!has(sender, "machinecode.query")) {
                        return true;
                    }
                    this.queryPlayer(sender, input);
                }
                return true;
            }
        }
    }

    private void queryPlayer(final CommandSender sender, final String playerName) {
        final Player online = Bukkit.getPlayerExact(playerName);
        final UUID onlineId = online == null ? null : online.getUniqueId();
        final String onlineIp = online == null ? null : addressOf(online);

        this.plugin.database(() -> {
            final Optional<MachineCodeRepository.PlayerRecord> stored =
                    this.plugin.repository().findLatestByPlayerName(playerName);
            if (stored.isEmpty() && onlineId == null) {
                return null;
            }

            final UUID playerId = stored.map(MachineCodeRepository.PlayerRecord::playerId)
                    .orElse(onlineId);
            final String resolvedName = stored.map(MachineCodeRepository.PlayerRecord::playerName)
                    .orElse(playerName);
            final String ip = onlineIp != null
                    ? onlineIp
                    : this.plugin.repository().findIp(playerId).orElse("unknown");

            final Optional<String> liveCode = this.plugin.currentCode(playerId);
            if (onlineId != null && liveCode.isEmpty()) {
                // The player is online but this session has not reported yet. Do not show a
                // previous session's hardware as if it were current.
                return new PlayerView(
                        resolvedName,
                        playerId,
                        ip,
                        null,
                        this.plugin.pluginConfig().waitingForReport(),
                        false
                );
            }

            final String machineCode = liveCode
                    .orElse(stored.map(MachineCodeRepository.PlayerRecord::machineCode).orElse(null));
            final boolean banned = machineCode != null && this.plugin.repository().isBanned(machineCode);
            final Platform platform = this.plugin.currentPlatform(playerId)
                    .orElse(stored.map(MachineCodeRepository.PlayerRecord::platform).orElse(null));
            final String platformText = platform == null
                    ? this.plugin.pluginConfig().queryUnknown()
                    : platform.displayName();
            return new PlayerView(resolvedName, playerId, ip, machineCode, platformText, banned);
        }).whenComplete((view, throwable) -> {
            if (throwable != null) {
                reportDatabaseFailure(sender, throwable);
                return;
            }
            if (view == null) {
                this.plugin.send(sender, this.plugin.pluginConfig().playerNotFound(),
                        "player", playerName);
                return;
            }
            this.sendPlayerView(sender, view);
        });
    }

    private void sendPlayerView(final CommandSender sender, final PlayerView view) {
        final PluginConfig config = this.plugin.pluginConfig();
        final List<String> lines = new ArrayList<>(5);
        lines.add(config.format(config.queryPlayerLine(), "player", view.playerName()));
        lines.add(config.format(config.queryIpLine(), "ip", view.ip()));
        lines.add(config.format(config.queryCodeLine(),
                "machine_code", view.machineCode() == null
                        ? config.queryUnknown()
                        : view.machineCode()));
        lines.add(config.format(config.queryPlatformLine(), "platform", view.platform()));

        if (view.banned()) {
            lines.add(config.format(config.queryBanLine(),
                    "status", config.queryBanYes()));
        } else if (config.showBanLineWhenNotBanned()) {
            lines.add(config.format(config.queryBanLine(),
                    "status", config.queryBanNo()));
        }

        this.sendLines(sender, lines);
    }

    private void lookupMachineCode(final CommandSender sender, final String machineCode) {
        this.plugin.database(() ->
                        this.plugin.repository().findByMachineCode(machineCode))
                .whenComplete((lookup, throwable) -> {
                    if (throwable != null) {
                        reportDatabaseFailure(sender, throwable);
                        return;
                    }
                    if (lookup.isEmpty()) {
                        this.plugin.send(sender, this.plugin.pluginConfig().recordNotFound(),
                                "machine_code", machineCode);
                        return;
                    }

                    final PluginConfig config = this.plugin.pluginConfig();
                    final MachineCodeRepository.MachineLookup result = lookup.get();
                    final String players = result.playerNames().isEmpty()
                            ? config.lookupNoPlayers()
                            : String.join(", ", result.playerNames());
                    final String platform = result.platform() == null
                            ? config.queryUnknown()
                            : result.platform().displayName();

                    this.sendLines(sender, List.of(
                            config.format(config.lookupCodeLine(),
                                    "machine_code", result.machineCode()),
                            config.format(config.lookupPlatformLine(),
                                    "platform", platform),
                            config.format(config.lookupPlayersLine(),
                                    "players", players)
                    ));
                });
    }

    private void ban(final CommandSender sender, final String input) {
        if (looksLikeMachineCode(input)) {
            final String code = normalizeCode(input);
            this.plugin.database(() -> this.plugin.repository().ban(code, sender.getName()))
                    .whenComplete((count, throwable) -> {
                        if (throwable != null) {
                            reportDatabaseFailure(sender, throwable);
                            return;
                        }
                        final int affected = count == null ? 0 : count;
                        this.plugin.getLogger().info("Banned machine code "
                                + code.substring(0, 8) + "... by " + sender.getName()
                                + " (" + affected + " rows updated)");
                        if (affected == 0) {
                            this.plugin.send(sender, this.plugin.pluginConfig().banAddedWithoutRecord(),
                                    "machine_code", code);
                        } else {
                            this.plugin.send(sender, this.plugin.pluginConfig().banAdded(),
                                    "machine_code", code,
                                    "count", Integer.toString(affected));
                        }
                        this.kickMatchingOnline(code);
                    });
            return;
        }

        // Convenience: /mcode ban <player> resolves the player's current/last machine code.
        final Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            final Optional<String> code = this.plugin.currentCode(online.getUniqueId());
            if (code.isPresent()) {
                this.ban(sender, code.get());
                return;
            }
        }

        this.plugin.database(() ->
                        this.plugin.repository().findLatestByPlayerName(input))
                .whenComplete((record, throwable) -> {
                    if (throwable != null) {
                        reportDatabaseFailure(sender, throwable);
                        return;
                    }
                    if (record.isEmpty()) {
                        this.plugin.send(sender, this.plugin.pluginConfig().playerNotFound(),
                                "player", input);
                        return;
                    }
                    this.ban(sender, record.get().machineCode());
                });
    }

    private void unban(final CommandSender sender, final String input) {
        if (!looksLikeMachineCode(input)) {
            this.plugin.send(sender, this.plugin.pluginConfig().recordNotFound(),
                    "machine_code", input);
            return;
        }
        final String code = normalizeCode(input);
        this.plugin.database(() -> this.plugin.repository().unban(code))
                .whenComplete((count, throwable) -> {
                    if (throwable != null) {
                        reportDatabaseFailure(sender, throwable);
                        return;
                    }
                    final int affected = count == null ? 0 : count;
                    if (affected == 0) {
                        this.plugin.send(sender, this.plugin.pluginConfig().notBanned(),
                                "machine_code", code);
                    } else {
                        this.plugin.send(sender, this.plugin.pluginConfig().unbanDone(),
                                "machine_code", code,
                                "count", Integer.toString(affected));
                    }
                });
    }

    private void kickMatchingOnline(final String machineCode) {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (this.plugin.currentCode(player.getUniqueId())
                    .filter(machineCode::equals)
                    .isPresent()) {
                this.plugin.runSync(() ->
                        this.plugin.kickBanned(player.getUniqueId(), machineCode));
            }
        }
    }

    private void sendLines(final CommandSender sender, final List<String> lines) {
        this.plugin.runSync(() -> {
            for (final String line : lines) {
                sender.sendMessage(Text.parse(line));
            }
        });
    }

    private void reportDatabaseFailure(final CommandSender sender, final Throwable throwable) {
        final Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        this.plugin.getLogger().severe("MachineCode database error: " + cause.getMessage());
        cause.printStackTrace();
        this.plugin.send(sender, this.plugin.pluginConfig().databaseError());
    }

    private boolean has(final CommandSender sender, final String permission) {
        // Console/RCON represents the server owner and must always be able to administer bans.
        if (sender instanceof org.bukkit.command.ConsoleCommandSender) {
            return true;
        }
        if (sender.hasPermission(permission)) {
            return true;
        }
        this.plugin.send(sender, this.plugin.pluginConfig().noPermission());
        return false;
    }

    private static boolean looksLikeMachineCode(final String input) {
        return input != null && MachineCodeValidator.isMachineCode(input.toLowerCase(Locale.ROOT));
    }

    private static String normalizeCode(final String input) {
        return input.toLowerCase(Locale.ROOT);
    }

    private static String addressOf(final Player player) {
        if (player.getAddress() == null || player.getAddress().getAddress() == null) {
            return "unknown";
        }
        return player.getAddress().getAddress().getHostAddress();
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command,
                                      final String alias, final String[] args) {
        if (args.length == 1) {
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            final List<String> completions = new ArrayList<>();
            if (sender.hasPermission("machinecode.ban")) {
                completions.add("ban");
                completions.add("unban");
            }
            if (sender.hasPermission("machinecode.reload")) {
                completions.add("reload");
            }
            if (sender.hasPermission("machinecode.query")) {
                for (final Player player : Bukkit.getOnlinePlayers()) {
                    completions.add(player.getName());
                }
            }
            return completions.stream()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        if (args.length == 2
                && (args[0].equalsIgnoreCase("ban") || args[0].equalsIgnoreCase("unban"))
                && sender.hasPermission("machinecode.ban")) {
            final String prefix = args[1].toLowerCase(Locale.ROOT);
            return Arrays.stream(Bukkit.getOnlinePlayers().toArray(Player[]::new))
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }

    private record PlayerView(
            String playerName,
            UUID playerId,
            String ip,
            String machineCode,
            String platform,
            boolean banned
    ) {
    }
}
