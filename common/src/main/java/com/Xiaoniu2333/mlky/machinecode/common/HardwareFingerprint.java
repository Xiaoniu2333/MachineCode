package com.Xiaoniu2333.mlky.machinecode.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a stable hardware identifier without requiring administrator privileges.
 *
 * <p>Windows uses the WMI property {@code Win32_BIOS.SerialNumber}. Linux reads the DMI
 * identifiers (or the machine-id as a last resort), and macOS reads the IOPlatform UUID or the
 * hardware serial from {@code ioreg}/{@code system_profiler}. The raw identifier is never sent to
 * a server: it is validated and hashed with SHA-256.</p>
 *
 * <p>All subprocesses have a timeout. A failed probe is reported as an exception with no raw
 * serial value in its message.</p>
 */
public final class HardwareFingerprint {
    private static final String HASH_PREFIX = "MachineCode|v1|";
    // Keep the worst case (three Windows probes) comfortably inside the server's 30 s grace.
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(6);
    private static final int MAX_COMMAND_OUTPUT = 64 * 1024;

    private static final Pattern MAC_PLATFORM_UUID = Pattern.compile(
            "\"IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern MAC_SERIAL = Pattern.compile(
            "\"IOPlatformSerialNumber\"\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern SYSTEM_PROFILER_SERIAL = Pattern.compile(
            "(?m)^\\s*Serial Number \\(System\\):\\s*(.+?)\\s*$");
    private static final Pattern REG_SERIAL = Pattern.compile(
            "(?im)^\\s*SystemSerialNumber\\s+REG_SZ\\s+(.+?)\\s*$");

    private HardwareFingerprint() {
    }

    /**
     * Captures and validates the local hardware fingerprint.
     *
     * @throws MachineCodeException if no usable identifier can be obtained
     */
    public static CapturedFingerprint capture() throws MachineCodeException {
        final Platform platform = Platform.current();
        final String raw = switch (platform) {
            case WINDOWS -> readWindowsBiosSerial();
            case LINUX -> readLinuxIdentifier();
            case MACOS -> readMacIdentifier();
        };

        final String error = MachineCodeValidator.validationError(raw);
        if (error != null) {
            throw new MachineCodeException("Machine code validation failed: " + error);
        }

        final String normalized = MachineCodeValidator.normalize(raw);
        return new CapturedFingerprint(
                computeMachineCode(platform, normalized),
                platform,
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown")
        );
    }

    /**
     * Computes the public machine code. Kept public so validation tests and support tooling can
     * reproduce a value from a known raw identifier.
     */
    public static String computeMachineCode(final Platform platform, final String normalizedRaw) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(normalizedRaw, "normalizedRaw");
        final String canonical = MachineCodeValidator.normalize(normalizedRaw);
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(
                    (HASH_PREFIX + platform.wireName() + "|" + canonical)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String readWindowsBiosSerial() throws MachineCodeException {
        final List<String> failures = new ArrayList<>();

        // This is the preferred path and works as a normal user. Get-CimInstance queries WMI
        // through the CIM/WMI service; no elevation or "runas" is attempted.
        final String powershell = runCommand(List.of(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-Command",
                "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                        + "$s=(Get-CimInstance -ClassName Win32_BIOS -ErrorAction Stop).SerialNumber; "
                        + "if ($null -ne $s -and $s.Trim().Length -gt 0) { 'MC_SERIAL=' + $s } "
                        + "else { exit 2 }"
        ), "PowerShell Win32_BIOS");
        final String fromPowerShell = valueAfterEquals(powershell, "MC_SERIAL");
        if (MachineCodeValidator.isValid(fromPowerShell)) {
            return fromPowerShell;
        }
        failures.add("PowerShell Win32_BIOS");

        // Windows 11 removes WMIC from a default installation, but some managed images retain it.
        final String wmic = runCommand(List.of(
                "wmic.exe", "bios", "get", "serialnumber", "/value"
        ), "WMIC Win32_BIOS");
        final String fromWmic = valueAfterEquals(wmic, "SerialNumber");
        if (fromWmic != null && MachineCodeValidator.isValid(fromWmic)) {
            return fromWmic;
        }
        failures.add("WMIC Win32_BIOS");

        // Registry fallback for systems where the WMI service is unavailable.
        final String registry = runCommand(List.of(
                "reg.exe", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\BIOS",
                "/v", "SystemSerialNumber"
        ), "BIOS registry");
        if (registry != null) {
            final Matcher matcher = REG_SERIAL.matcher(registry);
            if (matcher.find() && MachineCodeValidator.isValid(matcher.group(1))) {
                return matcher.group(1).trim();
            }
        }
        failures.add("BIOS registry");

        throw new MachineCodeException(
                "Unable to read a valid Win32_BIOS.SerialNumber; attempted: "
                        + String.join(", ", failures));
    }

    private static String readLinuxIdentifier() throws MachineCodeException {
        final List<Path> candidates = List.of(
                Path.of("/sys/class/dmi/id/product_uuid"),
                Path.of("/sys/class/dmi/id/board_serial"),
                Path.of("/sys/class/dmi/id/product_serial"),
                Path.of("/etc/machine-id"),
                Path.of("/var/lib/dbus/machine-id")
        );

        for (final Path candidate : candidates) {
            final String value = readSmallTextFile(candidate);
            if (MachineCodeValidator.isValid(value)) {
                return value;
            }
        }

        // dmidecode normally needs root. It is only a fallback and the user explicitly does not
        // want us to require administrator mode, so failure here is non-fatal.
        final String dmidecode = runCommand(List.of(
                "dmidecode", "-s", "system-uuid"
        ), "dmidecode");
        final String fromDmidecode = firstValidLine(dmidecode);
        if (fromDmidecode != null) {
            return fromDmidecode;
        }

        throw new MachineCodeException(
                "Unable to read a valid DMI/product UUID or machine-id on Linux");
    }

    private static String readMacIdentifier() throws MachineCodeException {
        final String ioreg = runCommand(List.of(
                "/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice"
        ), "ioreg");
        final String ioPlatform = firstGroup(ioreg, MAC_PLATFORM_UUID);
        if (MachineCodeValidator.isValid(ioPlatform)) {
            return ioPlatform;
        }
        final String serial = firstGroup(ioreg, MAC_SERIAL);
        if (MachineCodeValidator.isValid(serial)) {
            return serial;
        }

        final String systemProfiler = runCommand(List.of(
                "/usr/sbin/system_profiler", "SPHardwareDataType"
        ), "system_profiler");
        final String profilerSerial = firstGroup(systemProfiler, SYSTEM_PROFILER_SERIAL);
        if (MachineCodeValidator.isValid(profilerSerial)) {
            return profilerSerial;
        }

        final String kernUuid = runCommand(List.of(
                "/usr/sbin/sysctl", "-n", "kern.uuid"
        ), "sysctl kern.uuid");
        final String fromKern = firstValidLine(kernUuid);
        if (fromKern != null) {
            return fromKern;
        }

        throw new MachineCodeException(
                "Unable to read IOPlatformUUID or the hardware serial on macOS");
    }

    private static String firstValidLine(final String output) {
        if (output == null) {
            return null;
        }
        for (final String line : output.split("\\R")) {
            final String value = line.trim();
            if (MachineCodeValidator.isValid(value)) {
                return value;
            }
        }
        return null;
    }

    private static String valueAfterEquals(final String output, final String key) {
        if (output == null) {
            return null;
        }
        for (final String line : output.split("\\R")) {
            final int equals = line.indexOf('=');
            if (equals > 0 && line.substring(0, equals).trim().equalsIgnoreCase(key)) {
                return line.substring(equals + 1).trim();
            }
        }
        return null;
    }

    private static String firstGroup(final String output, final Pattern pattern) {
        if (output == null) {
            return null;
        }
        final Matcher matcher = pattern.matcher(output);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String readSmallTextFile(final Path path) {
        try {
            if (!Files.isReadable(path)) {
                return null;
            }
            final String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            return value.length() <= 512 ? value : null;
        } catch (final IOException exception) {
            return null;
        }
    }

    /**
     * Runs a bounded probe. An optional leading slash is removed from the executable when the
     * process is started through an absolute path that does not exist on the current OS.
     */
    private static String runCommand(final List<String> command, final String label) {
        Process process = null;
        try {
            final ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();

            final Process running = process;
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final Thread reader = Thread.ofVirtual().name("machinecode-probe-" + label).start(() -> {
                try (InputStream input = running.getInputStream()) {
                    final byte[] buffer = new byte[4096];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (output.size() < MAX_COMMAND_OUTPUT) {
                            output.write(buffer, 0, Math.min(read, MAX_COMMAND_OUTPUT - output.size()));
                        }
                    }
                } catch (final IOException ignored) {
                    // The process may be destroyed after a timeout; that is handled by the caller.
                }
            });

            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            reader.join(2000);
            return output.toString(StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            return null;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    /**
     * A validated snapshot. The raw serial is intentionally not retained here.
     */
    public record CapturedFingerprint(
            String machineCode,
            Platform platform,
            String osName,
            String osVersion,
            String architecture
    ) {
        public CapturedFingerprint {
            if (!MachineCodeValidator.isMachineCode(machineCode)) {
                throw new IllegalArgumentException("Invalid hashed machine code");
            }
            Objects.requireNonNull(platform, "platform");
            osName = clean(osName);
            osVersion = clean(osVersion);
            architecture = clean(architecture);
        }

        private static String clean(final String value) {
            return value == null ? "unknown" : value.trim().replaceAll("\\s+", " ");
        }

        public String displayPlatform() {
            return this.platform.displayName() + " / " + this.osName + " " + this.osVersion
                    + " (" + this.architecture + ")";
        }

        @Override
        public String toString() {
            return "CapturedFingerprint[machineCode=" + machineCode.substring(0, 8)
                    + "..., platform=" + platform + ", os=" + osName + ", arch=" + architecture + "]";
        }
    }
}
