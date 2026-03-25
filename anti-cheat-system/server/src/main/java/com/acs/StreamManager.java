package com.acs;

/**
 * Owns the server-side VLC process and the START/STOP_STREAM protocol.
 *
 * Only ONE stream is allowed at a time.
 * Starting a new stream automatically stops the previous one cleanly.
 *
 * Fires onStreamChanged callback whenever the active client changes so
 * ServerUI can immediately update all card states — guaranteed, regardless
 * of which code path triggered the stream change.
 */
public class StreamManager {

    private static volatile Process       vlcProcess         = null;
    private static volatile ClientHandler activeStreamClient = null;
    private static final    Object        lock               = new Object();

    // ── Stream-change callback ────────────────────────────────────────────────
    private static volatile Runnable onStreamChanged = null;

    public static void setOnStreamChanged(Runnable callback) {
        onStreamChanged = callback;
    }

    private static void notifyStreamChanged() {
        if (onStreamChanged != null) onStreamChanged.run();
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts a stream for newClient.
     * If a different client is already streaming it is stopped first.
     * Fires onStreamChanged after activeStreamClient is updated.
     */
    public static void startStream(String serverIp, int serverPort, ClientHandler newClient) {
        synchronized (lock) {
            // Stop previous client's FFmpeg if it is a different client
            if (activeStreamClient != null && activeStreamClient != newClient) {
                System.out.println("[StreamManager] Stopping previous stream for: "
                        + activeStreamClient.getClientId());
                activeStreamClient.sendMessage("STOP_STREAM");
            }
            stopVlc();
            activeStreamClient = newClient;
            launchVlc(serverIp, serverPort);
        }

        notifyStreamChanged();
        newClient.sendMessage("START_STREAM");
        System.out.println("[StreamManager] ▶ Stream started for " + newClient.getClientId());
    }

    /**
     * Stops the stream for the given client and kills VLC.
     */
    public static void stopStream(ClientHandler handler) {
        synchronized (lock) {
            handler.sendMessage("STOP_STREAM");
            stopVlc();
            if (activeStreamClient == handler) activeStreamClient = null;
        }
        notifyStreamChanged();
        System.out.println("[StreamManager] ⏹ Stream stopped for " + handler.getClientId());
    }

    /**
     * Kills VLC without sending anything to any client.
     * Called on server shutdown.
     */
    public static void stopVlc() {
        synchronized (lock) {
            if (vlcProcess != null && vlcProcess.isAlive()) {
                vlcProcess.destroy();
                try { vlcProcess.waitFor(); } catch (InterruptedException ignored) {}
                vlcProcess = null;
                System.out.println("[StreamManager] VLC stopped");
            }
        }
    }

    public static boolean      isVlcRunning()          { return vlcProcess != null && vlcProcess.isAlive(); }
    public static ClientHandler getActiveStreamClient() { return activeStreamClient; }

    // ─────────────────────────────────────────────────────────────────────────

    private static void launchVlc(String ip, int port) {
        try {
            String javaCmd   = ProcessHandle.current().info().command().orElse("java");
            String classpath = System.getProperty("java.class.path");

            System.out.println("[StreamManager] Launching VLC → " + ip + ":" + port);

            ProcessBuilder pb = new ProcessBuilder(
                    javaCmd, "-cp", classpath,
                    "com.acs.VlcNetworkPlayer",
                    ip, String.valueOf(port));
            pb.inheritIO();
            vlcProcess = pb.start();

            System.out.println("[StreamManager] ✅ VLC launched (alive: "
                    + vlcProcess.isAlive() + ")");

            // ── Watchdog ──────────────────────────────────────────────────────
            // Blocks until the VLC JVM process exits for ANY reason:
            //   - Stop button clicked inside VLC window
            //   - VLC window X button clicked
            //   - Process killed externally
            // When it fires: sends STOP_STREAM to client + notifies UI.
            final Process       watchedProcess = vlcProcess;
            final ClientHandler watchedClient  = activeStreamClient;

            Thread watchdog = new Thread(() -> {
                try {
                    int exitCode = watchedProcess.waitFor();
                    System.out.println("[Watchdog] VLC process exited (code "
                            + exitCode + ") for " + watchedClient.getClientId());
                } catch (InterruptedException e2) {
                    System.out.println("[Watchdog] Interrupted");
                    return;
                }

                // Only clean up if this watchdog's client is still the active one.
                // Guards against double-cleanup when stopStream() was called first.
                synchronized (lock) {
                    if (activeStreamClient == watchedClient) {
                        watchedClient.sendMessage("STOP_STREAM");
                        vlcProcess         = null;
                        activeStreamClient = null;
                    }
                }

                // Always fire UI update — cards reset, buttons reset
                notifyStreamChanged();

            }, "VlcWatchdog-" + watchedClient.getClientId());

            watchdog.setDaemon(true);
            watchdog.start();

        } catch (Exception e) {
            System.err.println("[StreamManager] ❌ VLC launch failed: " + e.getMessage());
            e.printStackTrace();
            vlcProcess = null;
        }
    }
}