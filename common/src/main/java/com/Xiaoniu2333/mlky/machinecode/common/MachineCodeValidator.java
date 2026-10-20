package com.Xiaoniu2333.mlky.machinecode.common;

import java.util.Locale;
import java.util.Set;

/**
 * Validation for raw hardware identifiers and for the hashed value sent to servers.
 *
 * <p>This class deliberately contains a separate, easy-to-audit validation stage. A BIOS/OEM
 * field can be present but useless (for example an empty placeholder or a line of zeroes), so
 * merely reading a value is not enough: a value is accepted only when it looks like a real
 * identifier. This is the "判断机器码获取是否正确" part of the mod.</p>
 */
public final class MachineCodeValidator {
    /** A SHA-256 value rendered as lower-case hexadecimal. */
    public static final int MACHINE_CODE_LENGTH = 64;

    private static final Set<String> PLACEHOLDERS = Set.of(
            "",
            "0",
            "00000000",
            "0000000000000000",
            "default",
            "default string",
            "invalid",
            "n/a",
            "na",
            "none",
            "not applicable",
            "not specified",
            "oem",
            "o.e.m.",
            "serial number",
            "system serial number",
            "to be filled by o.e.m",
            "to be filled by o.e.m.",
            "to be filled by oem",
            "unknown",
            "unset"
    );

    private MachineCodeValidator() {
    }

    /**
     * Normalizes a value before validation and hashing.
     *
     * <p>Values are trimmed, internal whitespace runs are collapsed, and the result is
     * upper-cased. Upper-casing avoids a different machine code when a firmware reports the
     * same UUID in a different case after a reboot.</p>
     */
    public static String normalize(final String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\uFEFF", "")
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    /**
     * Returns {@code null} when the raw value is usable, otherwise a short reason.
     *
     * <p>No raw value is included in the reason so it is safe to log.</p>
     */
    public static String validationError(final String raw) {
        final String value = normalize(raw);
        if (value.isEmpty()) {
            return "the hardware returned an empty identifier";
        }
        if (PLACEHOLDERS.contains(value.toLowerCase(Locale.ROOT))) {
            return "the hardware returned a placeholder identifier";
        }
        if (value.length() < 4) {
            return "the hardware identifier is too short";
        }
        if (value.length() > 256) {
            return "the hardware identifier is unexpectedly long";
        }
        if (!value.matches(".*[A-Z0-9].*")) {
            return "the hardware identifier has no letters or digits";
        }
        if (value.chars().distinct().count() < 2) {
            return "the hardware identifier is a repeated character";
        }
        if (value.matches("[0\\-_. ]+")) {
            return "the hardware identifier contains only zeroes";
        }
        if (value.contains("FILLED BY O.E.M") || value.contains("FILLED BY OEM")) {
            return "the hardware returned an OEM placeholder";
        }
        return null;
    }

    public static boolean isValid(final String raw) {
        return validationError(raw) == null;
    }

    /**
     * Returns whether the given value is a well-formed hashed machine code.
     */
    public static boolean isMachineCode(final String value) {
        return value != null
                && value.length() == MACHINE_CODE_LENGTH
                && value.chars().allMatch(character ->
                        (character >= '0' && character <= '9')
                                || (character >= 'a' && character <= 'f'));
    }
}
