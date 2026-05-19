package com.acs;

/**
 * Client-side wrapper that owns the logger thread and routes all activity
 * data through ClientLogger (persistent .pending file) instead of directly
 * to sendQueue. FileSender picks it up from there and sends to server.
 *
 * Usage:
 *   ActivityMonitor monitor = new ActivityMonitor();
 *   monitor.start();   // called when server sends START_ACTIVITY
 *   monitor.stop();    // called when server sends STOP_ACTIVITY
 */
public class ActivityMonitor {

    private final IActivityLogger   logger;
    private volatile Thread         loggerThread = null;
    private volatile boolean        running      = false;

    public ActivityMonitor() {
        this.logger = createLogger();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void start() {
        if (running) {
            System.out.println("[ActivityMonitor] Already running — ignoring duplicate START");
            return;
        }

        running = true;
        loggerThread = new Thread(() -> {
            System.out.println("[ActivityMonitor] Starting logger: " + logger.getPlatformName());
            try {
                // Write to persistent file — FileSender handles sending to server
                logger.startLogging(data -> ClientLogger.write("activity", data));
            } catch (Exception e) {
                System.err.println("[ActivityMonitor] Logger error: " + e.getMessage());
            } finally {
                running = false;
                System.out.println("[ActivityMonitor] Logger stopped");
            }
        });

        loggerThread.setName("ActivityLogger");
        loggerThread.setDaemon(true);
        loggerThread.start();
    }

    public void stop() {
        if (!running) return;
        running = false;
        logger.stopLogging();
        if (loggerThread != null) loggerThread.interrupt();
        System.out.println("[ActivityMonitor] Stop requested");
    }

    public boolean isRunning() { return running; }

    // ── Platform detection ────────────────────────────────────────────────────

    private static IActivityLogger createLogger() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new WindowsActivityLogger();
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            return new LinuxActivityLogger();
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }
    }
}
