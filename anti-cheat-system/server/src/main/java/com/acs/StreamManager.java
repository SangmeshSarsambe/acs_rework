package com.acs;

/**
 * Owns the server-side VLC process and the START/STOP_STREAM protocol.
 *
 * Extracted from SimpleServer so any UI component (ClientDetailDialog, etc.)
 * can call startStream / stopStream without touching SimpleServer directly.
 *
 * Adding a new stream-type feature later = add a method here + call it from
 * the relevant dialog.  Nothing else needs changing.
 */
public class StreamManager {

    private static volatile Process vlcProcess = null;
    private static final Object lock = new Object();

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Launches VLC on the server then tells the client to start FFmpeg.
     * Stops any existing VLC first (so clicking Start twice is safe).
     */
    public static void startStream(String serverIp, int serverPort, ClientHandler handler) {
        synchronized (lock) {
            stopVlc(); // kill any previous session first
            launchVlc(serverIp, serverPort);
        }
        handler.sendMessage("START_STREAM");
        System.out.println("[StreamManager] ▶ Stream started for " + handler.getClientId());
    }

    /**
     * Tells the client to stop FFmpeg then kills VLC on the server.
     */
    public static void stopStream(ClientHandler handler) {
        handler.sendMessage("STOP_STREAM");
        stopVlc();
        System.out.println("[StreamManager] ⏹ Stream stopped for " + handler.getClientId());
    }

    /**
     * Kills VLC without sending anything to the client.
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

    public static boolean isVlcRunning() {
        return vlcProcess != null && vlcProcess.isAlive();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private static void launchVlc(String ip, int port) {
        try {
            String javaCmd  = ProcessHandle.current().info().command().orElse("java");
            String classpath = System.getProperty("java.class.path");

            System.out.println("[StreamManager] Launching VLC → " + ip + ":" + port);
            System.out.println("[StreamManager] java   : " + javaCmd);
            System.out.println("[StreamManager] cp     : " + classpath);

            ProcessBuilder pb = new ProcessBuilder(
                    javaCmd,
                    "-cp", classpath,
                    "com.acs.VlcNetworkPlayer",
                    ip,
                    String.valueOf(port)
            );
            pb.inheritIO();
            vlcProcess = pb.start();

            System.out.println("[StreamManager] ✅ VLC launched (alive: " + vlcProcess.isAlive() + ")");

        } catch (Exception e) {
            System.err.println("[StreamManager] ❌ VLC launch failed: " + e.getMessage());
            e.printStackTrace();
            vlcProcess = null;
        }
    }
}
