package com.acs;

import com.acs.ui.ServerUI;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import javax.swing.*;
import java.io.*;
import java.net.*;

public class SimpleServer {

    private static JmDNS             jmdns;
    private static ServerSocket      serverSocket;
    private static volatile boolean  isRunning = true;
    private static InetAddress       localAddress;

    private static final Object      shutdownLock      = new Object();
    private static volatile boolean  shutdownInitiated = false;

    // ── Crash recovery state ──────────────────────────────────────────────────
    // Set during startup if crash detected — passed to ServerUI so it starts
    // with the correct button state (Stop Monitoring enabled, Start disabled)
    private static volatile boolean restoredMonitoring = false;

    public static volatile boolean DEBUG = false;

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        // ── Debug mode check — pass --debug to enable console output ──────────
        for (String arg : args) {
            if (arg.equals("--debug")) { DEBUG = true; break; }
        }

        if (!DEBUG) {
            // Suppress all console output in normal mode — UI only
            PrintStream nullStream = new PrintStream(OutputStream.nullOutputStream());
            System.setOut(nullStream);
            System.setErr(nullStream);
        }
        ErrorDialog.setDebug(DEBUG);

        System.setProperty("awt.useSystemAAFontSettings", "lcd");
        System.setProperty("swing.aatext", "true");

        // Prevent multiple server instances — bring existing window to front
        SingleInstance.init("acs-server", null);

        // Verify VLC is installed — fail fast before the exam starts
        checkVlcAvailable();

        try {
            localAddress = IPAddressUtil.getActualIPAddress();

            // ── Check for crash recovery BEFORE launching UI ──────────────────
            SessionManager.ServerState state = SessionManager.loadState();

            if (state.isCrashRecovery()) {
                System.out.println("[Server] ⚠ Crash detected — restoring session: "
                        + state.sessionDir);
                SessionManager.restoreSession(state.sessionDir, state.sessionStart);
                ActivityState.setMonitoring(true);
                restoredMonitoring = true;
            } else {
                System.out.println("[Server] Clean start");
            }

            serverSocket = new ServerSocket(0);
            int actualPort = serverSocket.getLocalPort();

            // ── mDNS registration ─────────────────────────────────────────────
            jmdns = JmDNS.create(localAddress);
            ServiceInfo serviceInfo = ServiceInfo.create(
                    "_acs._tcp.local.", "SimpleServer", actualPort,
                    "Anti Cheat System Server");
            jmdns.registerService(serviceInfo);

            System.out.println("[Server] Started — "
                    + localAddress.getHostAddress() + ":" + actualPort);

            // ── Shutdown hook (Ctrl-C / System.exit) ──────────────────────────
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!shutdownInitiated) {
                    System.out.println("\n[Server] Shutdown signal received");
                    shutdown();
                }
            }));

            // ── Launch Swing UI ───────────────────────────────────────────────
            final String ip   = localAddress.getHostAddress();
            final int    port = actualPort;

            SwingUtilities.invokeLater(() -> {
                ServerUI ui = new ServerUI(ip, port, restoredMonitoring);
                ui.setVisible(true);
                SingleInstance.setWindow(ui);
            });

            // ── Accept connections (background thread) ────────────────────────
            Thread acceptThread = new Thread(SimpleServer::acceptConnections);
            acceptThread.setName("ConnectionAcceptor");
            acceptThread.setDaemon(false);
            acceptThread.start();

            // ── Park the main thread until shutdown ───────────────────────────
            synchronized (shutdownLock) {
                while (isRunning) {
                    try { shutdownLock.wait(); }
                    catch (InterruptedException e) { break; }
                }
            }

            System.out.println("[Server] Main thread exiting");

        } catch (Exception e) {
            System.err.println("[Server] Fatal error: " + e.getMessage());
            e.printStackTrace();
            ErrorDialog.fatalAndExit("Server Error",
                    "A fatal error occurred:\n" + e.getMessage());
        } finally {
            if (!shutdownInitiated) shutdown();
        }
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private static void acceptConnections() {
        System.out.println("[AcceptThread] Waiting for clients…");
        while (isRunning && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                Thread t = new Thread(handler);
                t.setName("ClientHandler-" + clientSocket.getInetAddress().getHostAddress());
                t.setDaemon(true);
                t.start();
            } catch (SocketException e) {
                if (isRunning) System.err.println("[Server] Socket error: " + e.getMessage());
                break;
            } catch (IOException e) {
                if (isRunning) System.err.println("[Server] Accept error: " + e.getMessage());
            }
        }
        System.out.println("[AcceptThread] Exiting");
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private static void shutdown() {
        synchronized (shutdownLock) {
            if (shutdownInitiated) return;
            shutdownInitiated = true;
            isRunning         = false;
        }

        System.out.println("[Server] Shutting down…");

        try {
            // Safety: unlock all clients before shutting down
            ConnectionManager.broadcastMessage("UNLOCK_INPUT");

            ConnectionManager.broadcastMessage("STOP_STREAM");
            StreamManager.stopVlc();

            // Stop monitoring if active — merges all client files
            if (ActivityState.isMonitoring()) {
                ConnectionManager.broadcastMessage("STOP_ACTIVITY");
                ConnectionManager.broadcastMessage("STOP_KEYLOG");
                ConnectionManager.broadcastMessage("STOP_USB");
                ActivityState.setMonitoring(false);
                SessionManager.stopSession();
            }

            // Kick all clients — merges any remaining
            ConnectionManager.disconnectAll();

            if (serverSocket != null && !serverSocket.isClosed())
                serverSocket.close();

            if (jmdns != null) {
                jmdns.unregisterAllServices();
                jmdns.close();
            }

            // ── Mark clean shutdown — LAST thing written ──────────────────────
            // This is the only place cleanShutdown=true is ever written.
            // If we crash before reaching here, cleanShutdown stays false
            // and next startup detects crash recovery correctly.
            SessionManager.saveState(true);

            System.out.println("[Server] Shutdown complete");

        } catch (Exception e) {
            System.err.println("[Server] Error during shutdown: " + e.getMessage());
        } finally {
            synchronized (shutdownLock) { shutdownLock.notifyAll(); }
        }
    }

    // ── VLC availability check ────────────────────────────────────────────────

    private static void checkVlcAvailable() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            // Windows: look for exact libvlc.dll in known directories
            String[] candidates = {
                "./vlc", "../vlc",
                "C:/Program Files/VideoLAN/VLC",
                "C:/Program Files (x86)/VideoLAN/VLC"
            };
            for (String path : candidates) {
                java.io.File dir = new java.io.File(path);
                if (dir.isDirectory() && new java.io.File(dir, "libvlc.dll").exists()) {
                    System.out.println("[Server] \u2714 VLC found: " + dir.getAbsolutePath());
                    return;
                }
            }
            ErrorDialog.fatalAndExit("VLC Not Found",
                    "VLC media player was not found.\n"
                  + "Please install VLC or place the vlc/ folder next to the server JAR.");
        } else {
            // Linux: look for any file starting with "libvlc" (matches libvlc.so, libvlc.so.5, etc.)
            String[] candidates = {
                "./vlc", "../vlc",
                "/usr/lib/x86_64-linux-gnu",
                "/usr/lib/aarch64-linux-gnu",
                "/usr/lib", "/usr/lib64",
                "/usr/local/lib", "/usr/lib/vlc"
            };
            for (String path : candidates) {
                java.io.File dir = new java.io.File(path);
                if (dir.isDirectory()) {
                    java.io.File[] matches = dir.listFiles((d, name) -> name.startsWith("libvlc"));
                    if (matches != null && matches.length > 0) {
                        System.out.println("[Server] \u2714 VLC found: " + dir.getAbsolutePath());
                        return;
                    }
                }
            }
            // Fallback: check if vlc binary exists (apt install vlc puts it in /usr/bin)
            if (new java.io.File("/usr/bin/vlc").exists()) {
                System.out.println("[Server] \u2714 VLC found: /usr/bin/vlc");
                return;
            }
            ErrorDialog.fatalAndExit("VLC Not Found",
                    "VLC libraries were not found.\n"
                  + "Install VLC with: sudo apt install vlc");
        }
    }
}