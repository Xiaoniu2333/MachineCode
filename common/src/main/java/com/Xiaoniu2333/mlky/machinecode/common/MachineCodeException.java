package com.Xiaoniu2333.mlky.machinecode.common;

/**
 * Raised when a fingerprint cannot be read or validated.
 *
 * <p>The message is safe to write to logs. It must never contain a raw hardware serial.</p>
 */
public class MachineCodeException extends Exception {
    public MachineCodeException(final String message) {
        super(message);
    }

    public MachineCodeException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
