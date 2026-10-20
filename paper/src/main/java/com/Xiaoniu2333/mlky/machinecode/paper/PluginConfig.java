package com.Xiaoniu2333.mlky.machinecode.paper;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Typed view of config.yml. Missing or malformed values fall back to safe defaults.
 */
public final class PluginConfig {
    private final boolean enforcementEnabled;
    private final int graceSeconds;
    private final String timeoutKickMessage;
    private final String bannedKickMessage;
    private final boolean missingCodeNotifyEnabled;
    private final int missingCodeNotifyIntervalSeconds;
    private final String missingCodeNotifyMessage;
    private final boolean showBanLineWhenNotBanned;
    private final Path databaseFile;

    private final String prefix;
    private final String noPermission;
    private final String playersOnly;
    private final String usage;
    private final String playerNotFound;
    private final String recordNotFound;
    private final String waitingForReport;
    private final String banAdded;
    private final String banAddedWithoutRecord;
    private final String unbanDone;
    private final String notBanned;
    private final String reloaded;
    private final String databaseError;

    private final String queryPlayerLine;
    private final String queryIpLine;
    private final String queryCodeLine;
    private final String queryPlatformLine;
    private final String queryBanLine;
    private final String queryBanYes;
    private final String queryBanNo;
    private final String queryUnknown;

    private final String lookupCodeLine;
    private final String lookupPlatformLine;
    private final String lookupPlayersLine;
    private final String lookupNoPlayers;

    public PluginConfig(final JavaPlugin plugin, final FileConfiguration config) {
        this.enforcementEnabled = config.getBoolean("enforcement.enabled", true);
        this.graceSeconds = clamp(config.getInt("enforcement.grace-seconds", 30), 0, 300);
        this.timeoutKickMessage = string(config,
                "enforcement.timeout-kick-message",
                "&c未在 30 秒内接收到你的机器码。\n"
                        + "&7请安装并启用 MachineCode 客户端 Mod 后重新连接。");
        this.bannedKickMessage = string(config,
                "enforcement.banned-kick-message",
                "&c你已被封禁。\n&7机器码: &f%machine_code%\n&7原因: &f%reason%");
        this.missingCodeNotifyEnabled = config.getBoolean("notify-missing-code.enabled", true);
        this.missingCodeNotifyIntervalSeconds = clamp(
                config.getInt("notify-missing-code.interval-seconds", 60), 1, 3600);
        this.missingCodeNotifyMessage = string(config,
                "notify-missing-code.message", "&e请使用官方客户端进行游玩");
        this.showBanLineWhenNotBanned = config.getBoolean(
                "query.show-ban-line-when-not-banned", false);

        final String configuredDatabase = string(config, "database.file", "machinecode.db");
        final Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path database = Path.of(configuredDatabase);
        if (!database.isAbsolute()) {
            database = dataFolder.resolve(configuredDatabase);
        }
        this.databaseFile = database.normalize();

        this.prefix = string(config, "messages.prefix", "&8[&bMachineCode&8] &r");
        this.noPermission = string(config, "messages.no-permission", "&c你没有权限执行此操作。");
        this.playersOnly = string(config, "messages.players-only", "&c该指令只能由玩家执行。");
        this.usage = string(config, "messages.usage",
                "&e用法: &f/mcode [玩家名] &7| &f/mcode <机器码> &7| "
                        + "&f/mcode ban <机器码> &7| &f/mcode unban <机器码>");
        this.playerNotFound = string(config, "messages.player-not-found",
                "&c未找到玩家 &f%player% &c的记录。");
        this.recordNotFound = string(config, "messages.record-not-found",
                "&c没有找到该机器码的记录: &f%machine_code%");
        this.waitingForReport = string(config, "messages.waiting-for-report",
                "&7等待中");
        this.banAdded = string(config, "messages.ban-added",
                "&a已封禁机器码 &f%machine_code% &7(影响 %count% 条记录)。");
        this.banAddedWithoutRecord = string(config, "messages.ban-added-without-record",
                "&a已封禁机器码 &f%machine_code% &7(当前没有历史记录，新登录将被拦截)。");
        this.unbanDone = string(config, "messages.unban-done",
                "&a已解除机器码封禁 &f%machine_code% &7(更新 %count% 条记录)。");
        this.notBanned = string(config, "messages.not-banned",
                "&e机器码 &f%machine_code% &e当前未被封禁。");
        this.reloaded = string(config, "messages.reloaded", "&a配置已重新加载。");
        this.databaseError = string(config, "messages.database-error",
                "&c数据库操作失败，请查看服务器控制台。");

        this.queryPlayerLine = string(config, "query.player-line", "&e玩家ID: &f%player%");
        this.queryIpLine = string(config, "query.ip-line", "&e玩家IP: &f%ip%");
        this.queryCodeLine = string(config, "query.machine-code-line",
                "&e机器码: &f%machine_code%");
        this.queryPlatformLine = string(config, "query.platform-line",
                "&e系统平台: &f%platform%");
        this.queryBanLine = string(config, "query.ban-line", "&e封禁信息: &f%status%");
        this.queryBanYes = string(config, "query.ban-status-yes", "&c已封禁");
        this.queryBanNo = string(config, "query.ban-status-no", "&a正常");
        this.queryUnknown = string(config, "query.unknown-value", "&7未获取");

        this.lookupCodeLine = string(config, "lookup.machine-code-line",
                "&e机器码: &f%machine_code%");
        this.lookupPlatformLine = string(config, "lookup.platform-line",
                "&e系统平台: &f%platform%");
        this.lookupPlayersLine = string(config, "lookup.players-line",
                "&e登录过的游戏ID: &f%players%");
        this.lookupNoPlayers = string(config, "lookup.no-players", "&7无记录");
    }

    private static String string(final FileConfiguration config, final String path,
                                 final String fallback) {
        final String value = config.getString(path);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int clamp(final int value, final int minimum, final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public boolean enforcementEnabled() {
        return this.enforcementEnabled;
    }

    public int graceSeconds() {
        return this.graceSeconds;
    }

    public String timeoutKickMessage() {
        return this.timeoutKickMessage;
    }

    public String bannedKickMessage() {
        return this.bannedKickMessage;
    }

    public boolean missingCodeNotifyEnabled() {
        return this.missingCodeNotifyEnabled;
    }

    public int missingCodeNotifyIntervalSeconds() {
        return this.missingCodeNotifyIntervalSeconds;
    }

    public String missingCodeNotifyMessage() {
        return this.missingCodeNotifyMessage;
    }

    public boolean showBanLineWhenNotBanned() {
        return this.showBanLineWhenNotBanned;
    }

    public Path databaseFile() {
        return this.databaseFile;
    }

    public String prefix() {
        return this.prefix;
    }

    public String noPermission() {
        return this.noPermission;
    }

    public String playersOnly() {
        return this.playersOnly;
    }

    public String usage() {
        return this.usage;
    }

    public String playerNotFound() {
        return this.playerNotFound;
    }

    public String recordNotFound() {
        return this.recordNotFound;
    }

    public String waitingForReport() {
        return this.waitingForReport;
    }

    public String banAdded() {
        return this.banAdded;
    }

    public String banAddedWithoutRecord() {
        return this.banAddedWithoutRecord;
    }

    public String unbanDone() {
        return this.unbanDone;
    }

    public String notBanned() {
        return this.notBanned;
    }

    public String reloaded() {
        return this.reloaded;
    }

    public String databaseError() {
        return this.databaseError;
    }

    public String queryPlayerLine() {
        return this.queryPlayerLine;
    }

    public String queryIpLine() {
        return this.queryIpLine;
    }

    public String queryCodeLine() {
        return this.queryCodeLine;
    }

    public String queryPlatformLine() {
        return this.queryPlatformLine;
    }

    public String queryBanLine() {
        return this.queryBanLine;
    }

    public String queryBanYes() {
        return this.queryBanYes;
    }

    public String queryBanNo() {
        return this.queryBanNo;
    }

    public String queryUnknown() {
        return this.queryUnknown;
    }

    public String lookupCodeLine() {
        return this.lookupCodeLine;
    }

    public String lookupPlatformLine() {
        return this.lookupPlatformLine;
    }

    public String lookupPlayersLine() {
        return this.lookupPlayersLine;
    }

    public String lookupNoPlayers() {
        return this.lookupNoPlayers;
    }

    public String format(final String template, final String... placeholders) {
        Objects.requireNonNull(template, "template");
        String result = template;
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            result = result.replace("%" + placeholders[index].toLowerCase(Locale.ROOT) + "%",
                    placeholders[index + 1] == null ? "" : placeholders[index + 1]);
        }
        return result;
    }
}
