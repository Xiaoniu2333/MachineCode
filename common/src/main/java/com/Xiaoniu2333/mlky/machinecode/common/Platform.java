package com.Xiaoniu2333.mlky.machinecode.common;

import java.util.Locale;
import java.util.Optional;

/**
 * The desktop platform a client is running on.
 *
 * <p>The name is transmitted to the server and must stay stable across releases.</p>
 */
public enum Platform {
    WINDOWS("Windows"),
    LINUX("Linux"),
    MACOS("macOS");

    private final String displayName;

    Platform(final String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }

    /**
     * Resolves the current platform from {@code os.name}.
     *
     * @throws MachineCodeException when the platform is not supported
     */
    public static Platform current() throws MachineCodeException {
        final String osName = System.getProperty("os.name", "unknown");
        final String normalized = osName.toLowerCase(Locale.ROOT);

        if (normalized.contains("win")) {
            return WINDOWS;
        }
        if (normalized.contains("mac") || normalized.contains("darwin")) {
            return MACOS;
        }
        if (normalized.contains("linux") || normalized.contains("unix")) {
            return LINUX;
        }

        throw new MachineCodeException("Unsupported operating system: " + osName);
    }

    /**
     * Parses a platform sent over the wire.
     */
    public static Optional<Platform> fromWireName(final String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Platform.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public String wireName() {
        return this.name();
    }
}
