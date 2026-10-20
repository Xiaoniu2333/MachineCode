package com.Xiaoniu2333.mlky.machinecode.forge;

import java.awt.GraphicsEnvironment;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;

/**
 * Required native popup + hard crash when a machine code cannot be obtained.
 */
final class MachineCodeCrash {
    static final String POPUP_TEXT =
            "请使用管理员模式启动\n如仍然异常，请发送错误报告";

    private MachineCodeCrash() {
    }

    static void failAndCrash(final String stage, final Throwable cause,
                             final Logger logger) {
        logger.error("MachineCode fingerprint failure during {}", stage, cause);
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(
                        null,
                        POPUP_TEXT,
                        "MachineCode",
                        JOptionPane.ERROR_MESSAGE
                ));
            } catch (final Exception ignored) {
                // The crash below is the important part; the log also has the cause.
            }
        }
        throw new MachineCodeCrashException(stage, cause);
    }

    static final class MachineCodeCrashException extends RuntimeException {
        MachineCodeCrashException(final String stage, final Throwable cause) {
            super("MachineCode could not obtain a valid machine code during " + stage, cause);
        }
    }
}
