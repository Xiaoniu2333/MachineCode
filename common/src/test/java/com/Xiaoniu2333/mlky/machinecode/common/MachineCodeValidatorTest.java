package com.Xiaoniu2333.mlky.machinecode.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MachineCodeValidatorTest {
    @Test
    void acceptsARealisticBiosSerial() {
        assertTrue(MachineCodeValidator.isValid("SN1234567890"));
        assertTrue(MachineCodeValidator.isValid("4C4C4544-0037-3010-8052-B6C04F4E4E32"));
        assertTrue(MachineCodeValidator.isValid("  abcdef123456  "));
    }

    @Test
    void rejectsPlaceholdersAndUselessValues() {
        assertFalse(MachineCodeValidator.isValid(null));
        assertFalse(MachineCodeValidator.isValid(""));
        assertFalse(MachineCodeValidator.isValid("   "));
        assertFalse(MachineCodeValidator.isValid("To be filled by O.E.M."));
        assertFalse(MachineCodeValidator.isValid("Default string"));
        assertFalse(MachineCodeValidator.isValid("00000000-0000-0000-0000-000000000000"));
        assertFalse(MachineCodeValidator.isValid("0000000000000000"));
        assertFalse(MachineCodeValidator.isValid("AAAA"));
        assertFalse(MachineCodeValidator.isValid("!!!!"));
        assertFalse(MachineCodeValidator.isValid("abc"));
        assertFalse(MachineCodeValidator.isValid("unknown"));
    }

    @Test
    void normalizationIsStableAndCaseInsensitive() {
        assertEquals("ABC DEF", MachineCodeValidator.normalize("  abc   def "));
        assertEquals(
                HardwareFingerprint.computeMachineCode(Platform.WINDOWS, "ABC123"),
                HardwareFingerprint.computeMachineCode(Platform.WINDOWS, "abc123"));
    }

    @Test
    void hashChangesWithPlatformAndValue() {
        final String windows = HardwareFingerprint.computeMachineCode(Platform.WINDOWS, "SERIAL");
        final String linux = HardwareFingerprint.computeMachineCode(Platform.LINUX, "SERIAL");
        final String other = HardwareFingerprint.computeMachineCode(Platform.WINDOWS, "SERIAL2");
        assertTrue(MachineCodeValidator.isMachineCode(windows));
        assertFalse(windows.equals(linux));
        assertFalse(windows.equals(other));
        assertFalse(windows.contains("SERIAL"));
    }

    @Test
    void validationErrorIsDescriptive() {
        assertNotNull(MachineCodeValidator.validationError(""));
        assertTrue(MachineCodeValidator.validationError("Default string").contains("placeholder"));
    }

    @Test
    void hashHasAKnownStableValue() {
        // This locks the hashing format so a future change cannot silently invalidate bans.
        assertEquals(
                "ccfacb3f5721db6ed374d46296d72b3f28a28bb1b74921b57f9388244fee8cbc",
                HardwareFingerprint.computeMachineCode(Platform.WINDOWS, "TEST-SERIAL"));
    }
}
