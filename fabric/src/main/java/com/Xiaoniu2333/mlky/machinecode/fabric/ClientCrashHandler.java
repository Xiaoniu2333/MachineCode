package com.Xiaoniu2333.mlky.machinecode.fabric;

import java.awt.GraphicsEnvironment;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;

/**
 * Shows the required native popup and then terminates the client with a crash report.
 *
 * <p>The popup text is intentionally fixed because it is part of the server operator's support
 * procedure. Technical details go to the log and the Minecraft crash report, not the dialog.</p>
 */
final class ClientCrashHandler {
    static final String POPUP_TEXT =
            "请使用管理员模式启动\n如仍然异常，请发送错误报告";

    private ClientCrashHandler() {
    }

    static void failAndCrash(final String stage, final Throwable cause,
                             final Logger logger) {
        logger.error("MachineCode fingerprint failure during {}", stage, cause);
        showPopup();
        throw new MachineCodeClientCrash(stage, cause);
    }

    private static void showPopup() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> JOptionPane.showMessageDialog(
                    null,
                    POPUP_TEXT,
                    "MachineCode",
                    JOptionPane.ERROR_MESSAGE
            ));
        } catch (final Exception exception) {
            // A failed dialog must not prevent the crash; the log still contains the cause.
        }
    }

    static final class MachineCodeClientCrash extends RuntimeException {
        MachineCodeClientCrash(final String stage, final Throwable cause) {
            super("MachineCode could not obtain a valid machine code during " + stage, cause);
        }
    }
}
