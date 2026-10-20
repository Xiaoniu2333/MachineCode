package com.Xiaoniu2333.mlky.machinecode.paper;

import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI expansion.
 *
 * <p>Placeholders (evaluated for the player supplied by PAPI):</p>
 * <ul>
 *   <li>{@code %machinecode_machinecode%} - hashed machine code</li>
 *   <li>{@code %machinecode_platform%} - Windows/Linux/macOS</li>
 *   <li>{@code %machinecode_ip%} - last/current IP</li>
 *   <li>{@code %machinecode_received%} - True / False</li>
 *   <li>{@code %machinecode_received_cn%} - 是 / 否</li>
 * </ul>
 *
 * <p>Every placeholder also accepts a player ID suffix, for example
 * {@code %machinecode_machinecode_Steve%} or {@code %machinecode_received_cn_Steve%}.</p>
 */
public final class MachineCodePlaceholderExpansion extends PlaceholderExpansion {
    private static final String IDENTIFIER = "machinecode";

    private final MachineCodePlugin plugin;

    MachineCodePlaceholderExpansion(final MachineCodePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public @NotNull String getAuthor() {
        return "Xiaoniu2333";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        // Keep the expansion registered across PlaceholderAPI reloads.
        return true;
    }

    @Override
    public String onRequest(final OfflinePlayer player, final String params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        try {
            final Parsed parsed = parse(params);
            if (parsed == null) {
                return "";
            }
            final PlaceholderCache.Value value = this.plugin.placeholderCache()
                    .resolve(player, parsed.playerName());
            return switch (parsed.key()) {
                case "machinecode", "code" ->
                        value.machineCode() == null ? "" : value.machineCode();
                case "platform" -> value.platformText();
                case "ip" -> value.ip() == null ? "" : value.ip();
                case "received" -> value.received() ? "True" : "False";
                case "received_cn" -> value.received() ? "是" : "否";
                default -> "";
            };
        } catch (final RuntimeException exception) {
            this.plugin.getLogger().warning(
                    "PlaceholderAPI request failed for params '" + params + "': "
                            + exception.getMessage());
            return "";
        }
    }

    private static Parsed parse(final String params) {
        final String lower = params.toLowerCase(Locale.ROOT);

        if (isKey(lower)) {
            return new Parsed(lower, null);
        }
        if (lower.startsWith("machinecode_")) {
            return suffix(params, "machinecode_", "machinecode");
        }
        if (lower.startsWith("code_")) {
            return suffix(params, "code_", "code");
        }
        if (lower.startsWith("received_cn_")) {
            return suffix(params, "received_cn_", "received_cn");
        }
        if (lower.startsWith("received_")) {
            return suffix(params, "received_", "received");
        }
        if (lower.startsWith("platform_")) {
            return suffix(params, "platform_", "platform");
        }
        if (lower.startsWith("ip_")) {
            return suffix(params, "ip_", "ip");
        }
        return null;
    }

    private static boolean isKey(final String lower) {
        return lower.equals("machinecode")
                || lower.equals("code")
                || lower.equals("platform")
                || lower.equals("ip")
                || lower.equals("received")
                || lower.equals("received_cn");
    }

    private static Parsed suffix(final String params, final String prefix, final String key) {
        final String target = params.substring(prefix.length());
        return target.isBlank() ? null : new Parsed(key, target);
    }

    private record Parsed(String key, String playerName) {
    }
}
