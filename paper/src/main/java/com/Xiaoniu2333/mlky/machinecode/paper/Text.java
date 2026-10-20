package com.Xiaoniu2333.mlky.machinecode.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Small Adventure helper. Configuration values use {@code &}-style colour codes.
 */
public final class Text {
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private Text() {
    }

    public static Component parse(final String message) {
        return LEGACY.deserialize(message == null ? "" : message);
    }

    public static Component prefixed(final PluginConfig config, final String message) {
        return parse(config.prefix() + message);
    }

    public static String legacy(final String message) {
        return message == null ? "" : message;
    }
}
