package com.acs;

import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side persistent event logger.
 *
 * Instead of monitors pushing directly to sendQueue, they call
 * ClientLogger.write(type, data) which appends to a local .pending file.
 * FileSender then reads those files and pushes to sendQueue.
 *
 * This ensures no events are lost during disconnects — FileSender resumes
 * from its saved offset on reconnect.
 *
 * File structure:
 *   client_logs/
 *   ├── activity.pending
 *   ├── keylog.pending
 *   ├── usb.pending
 *   ├── activity.offset
 *   ├── keylog.offset
 *   └── usb.offset
 *
 * Adding a new type (e.g. "screenshot"):
 *   Just call ClientLogger.write("screenshot", data) — file created automatically.
 */
public class ClientLogger {

    static final Path LOG_DIR = Paths.get("client_logs");

    // One lock object per type — monitors don't block each other
    private static final Map<String, Object> locks = new ConcurrentHashMap<>();

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Appends one event line to <type>.pending file.
     * Called by ActivityMonitor, KeylogMonitor, DeviceMonitor instead of sendQueue.
     */
    public static void write(String type, String data) {
        Object lock = locks.computeIfAbsent(type, k -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(LOG_DIR);
                Path file = pendingFile(type);
                try (BufferedWriter w = new BufferedWriter(
                        new FileWriter(file.toFile(), true))) {
                    w.write(data);
                    w.newLine();
                }
            } catch (IOException e) {
                System.err.println("[ClientLogger] Write error [" + type + "]: " + e.getMessage());
            }
        }
    }

    // ── Cleanup (called on KICKED) ────────────────────────────────────────────

    /**
     * Deletes all .pending and .offset files.
     * Called when client receives KICKED — no point resuming, server ended this session.
     */
    public static void cleanup() {
        try {
            if (!Files.exists(LOG_DIR)) return;
            try (var stream = Files.list(LOG_DIR)) {
                stream.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".pending") || name.endsWith(".offset");
                }).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException e) {
                        System.err.println("[ClientLogger] Delete error: " + e.getMessage());
                    }
                });
            }
            System.out.println("[ClientLogger] Cleaned up all pending files");
        } catch (IOException e) {
            System.err.println("[ClientLogger] Cleanup error: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static Path pendingFile(String type) {
        return LOG_DIR.resolve(type + ".pending");
    }

    static Path offsetFile(String type) {
        return LOG_DIR.resolve(type + ".offset");
    }

    /**
     * Reads saved offset for a type. Returns 0 if file doesn't exist (fresh start).
     */
    static long readOffset(String type) {
        try {
            Path f = offsetFile(type);
            if (!Files.exists(f)) return 0;
            return Long.parseLong(Files.readString(f).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Saves offset for a type.
     */
    static void saveOffset(String type, long offset) {
        try {
            Files.createDirectories(LOG_DIR);
            Files.writeString(offsetFile(type), String.valueOf(offset),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[ClientLogger] Offset save error [" + type + "]: " + e.getMessage());
        }
    }

    /**
     * Truncates .pending file and resets offset to 0.
     * Called by FileSender when all lines have been sent.
     */
    static void truncate(String type) {
        Object lock = locks.computeIfAbsent(type, k -> new Object());
        synchronized (lock) {
            try {
                Path f = pendingFile(type);
                if (Files.exists(f)) {
                    Files.writeString(f, "",
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);
                }
                saveOffset(type, 0);
                System.out.println("[ClientLogger] Truncated [" + type + "]");
            } catch (IOException e) {
                System.err.println("[ClientLogger] Truncate error [" + type + "]: " + e.getMessage());
            }
        }
    }
}