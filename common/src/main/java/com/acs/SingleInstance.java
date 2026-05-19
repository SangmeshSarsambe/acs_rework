package com.acs;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/**
 * Ensures only one instance of the application runs at a time.
 *
 * Windows + Linux: uses a lock file in the system temp directory.
 * The OS automatically releases the lock when the process dies — no stale locks.
 *
 * For server (has UI): if a second instance tries to start, it writes a signal
 * file and exits. The existing instance's watcher thread sees the signal and
 * brings its window to the front.
 *
 * Usage:
 *   // Client — no window, just prevent duplicate
 *   SingleInstance.init("acs-client", null);
 *
 *   // Server — bring existing window to front on duplicate
 *   SingleInstance.init("acs-server", serverJFrame);
 */
public class SingleInstance {

    private static FileChannel channel;
    private static FileLock    lock;
    private static Thread      watcherThread;

    private static volatile javax.swing.JFrame registeredWindow = null;

    /**
     * Called after the JFrame is created to register it for bring-to-front.
     * The signal watcher will use this window reference.
     */
    public static void setWindow(javax.swing.JFrame window) {
        registeredWindow = window;
        // Start watcher now that we have the window
        startSignalWatcher(signalFilePath("acs-server"), window);
    }

    /**
     * Call once at the very top of main().
     *
     * @param appName  unique app name used for lock/signal file names
     * @param window   JFrame to bring to front (null for console apps)
     */
    private static volatile String storedAppName = null;

    public static void init(String appName, javax.swing.JFrame window) {
        storedAppName   = appName;
        Path lockFile   = lockFilePath(appName);
        Path signalFile = signalFilePath(appName);

        try {
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            lock = channel.tryLock();

            if (lock == null) {
                // Another instance is running
                alreadyRunning(appName, signalFile, window);
                return;
            }

            // We are the first instance — if we have a window, watch for signals
            if (window != null) {
                startSignalWatcher(signalFile, window);
            }

            // Clean up lock on JVM exit
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                releaseLock();
                try { Files.deleteIfExists(lockFile);   } catch (IOException ignored) {}
                try { Files.deleteIfExists(signalFile); } catch (IOException ignored) {}
            }));

        } catch (IOException e) {
            System.err.println("[SingleInstance] Lock error: " + e.getMessage());
        }
    }

    // ── Already running ───────────────────────────────────────────────────────

    private static void alreadyRunning(String appName, Path signalFile,
                                        javax.swing.JFrame window) {
        System.out.println("[" + appName + "] Already running.");

        // Only write signal file for apps that have a window (server)
        // Client is console — no window to bring to front
        if (appName.contains("server")) {
            try {
                Files.writeString(signalFile, "focus",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                System.out.println("[" + appName + "] Signalled existing instance to focus.");
            } catch (IOException e) {
                System.err.println("[SingleInstance] Signal write error: " + e.getMessage());
            }
            System.exit(0);
        } else {
            System.out.println("[" + appName + "] Only one instance allowed. Exiting.");
            ErrorDialog.fatalAndExit("ACS Client Already Running",
                    "Another instance of the ACS Client is already running.\nOnly one instance is allowed.");
        }
    }

    // ── Signal watcher ────────────────────────────────────────────────────────

    /**
     * Polls for the signal file every 500ms.
     * When found: brings window to front, deletes signal file.
     */
    private static void startSignalWatcher(Path signalFile, javax.swing.JFrame window) {
        watcherThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    if (Files.exists(signalFile)) {
                        Files.deleteIfExists(signalFile);
                        // Bring window to front on EDT
                        javax.swing.SwingUtilities.invokeLater(() -> {
                            if (window.getState() == java.awt.Frame.ICONIFIED) {
                                window.setState(java.awt.Frame.NORMAL);
                            }
                            window.setVisible(true);
                            window.toFront();
                            window.requestFocus();
                            System.out.println("[SingleInstance] Brought to front.");
                        });
                    }
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    // Silently continue
                }
            }
        }, "SingleInstanceWatcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void releaseLock() {
        try {
            if (lock    != null) lock.release();
            if (channel != null) channel.close();
        } catch (IOException ignored) {}
    }

    private static Path lockFilePath(String appName) {
        return Paths.get(System.getProperty("java.io.tmpdir"), appName + ".lock");
    }

    private static Path signalFilePath(String appName) {
        return Paths.get(System.getProperty("java.io.tmpdir"), appName + ".signal");
    }
}