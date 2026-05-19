package com.acs;

import javax.swing.*;

/**
 * Shows critical error messages as GUI popups when the app runs without a visible
 * terminal. In debug mode errors go to the console as usual.
 *
 * Call {@link #setDebug(boolean)} early in main() to configure.
 */
public final class ErrorDialog {

    private static volatile boolean debug = false;

    private ErrorDialog() {}

    /** Set to true to send errors to console instead of popups. */
    public static void setDebug(boolean debugMode) {
        debug = debugMode;
    }

    /**
     * Show a blocking error dialog, then exit. Use for fatal errors.
     *
     * Always shows a GUI dialog — even in debug mode — because System.exit(1)
     * would close the terminal before the user could read a console-only message.
     * In debug mode the error is ALSO printed to stderr for logging.
     */
    public static void fatalAndExit(String title, String message) {
        if (debug) {
            System.err.println("[FATAL] " + title + " — " + message);
        }
        // Always show dialog so the user can read the error before the process dies
        showDialog(title, message, JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }

    /**
     * Show a blocking warning dialog (non-fatal). Execution continues after user clicks OK.
     */
    public static void warn(String title, String message) {
        if (debug) {
            System.err.println("[WARN] " + title + " — " + message);
            return;
        }
        showDialog(title, message, JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Show a blocking info dialog. Execution continues after user clicks OK.
     */
    public static void info(String title, String message) {
        if (debug) {
            System.out.println("[INFO] " + title + " — " + message);
            return;
        }
        showDialog(title, message, JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static void showDialog(String title, String message, int messageType) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        if (SwingUtilities.isEventDispatchThread()) {
            JOptionPane.showMessageDialog(null, message, title, messageType);
        } else {
            try {
                SwingUtilities.invokeAndWait(() ->
                        JOptionPane.showMessageDialog(null, message, title, messageType));
            } catch (Exception e) {
                // Last resort — might be suppressed, but nothing else we can do
                System.err.println("[ErrorDialog] Failed to show popup: " + e.getMessage());
                System.err.println("[ErrorDialog] Original: " + title + " — " + message);
            }
        }
    }
}
