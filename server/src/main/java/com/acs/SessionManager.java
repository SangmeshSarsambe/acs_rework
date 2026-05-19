package com.acs;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages activity + keylog + usb (and future log types) logging sessions.
 *
 * Session lifecycle:
 *   startSession()  → called when "Start Monitoring" clicked
 *   stopSession()   → called when "Stop Monitoring" clicked
 *
 * Folder structure:
 *   logs/
 *   └── session_14-03-2025_14-30-00/
 *       └── 192.168.1.5/
 *           ├── activity.txt
 *           ├── keylog.txt
 *           ├── usb.txt
 *           └── session.txt  ← merged on kick / stop monitoring
 *
 * Merge policy:
 *   - Client crash / unexpected disconnect → NO merge. Writers stay open.
 *   - Explicit kick → mergeClient() called immediately.
 *   - Stop Monitoring / server shutdown → stopSession() merges all.
 *
 * Server crash recovery:
 *   - saveState() called on every lifecycle change
 *   - loadState() called on server startup
 *   - restoreSession() called if crash detected — resumes same session folder
 *   - Writers reopen in append mode on first write — no data lost
 *
 * Adding a new log type (e.g. "screenshot"):
 *   Just call SessionManager.write("screenshot", clientIp, data) — fully automatic.
 */
public class SessionManager {

    private static final DateTimeFormatter SESSION_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy_HH-mm-ss");
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private static volatile Path    sessionDir    = null;
    private static volatile String  sessionStart  = null;
    private static volatile boolean sessionActive = false;

    // State file — persisted across JVM restarts
    private static final Path STATE_FILE = Paths.get("logs", "server_state.properties");

    /**
     * Generic writer map: logType → (clientIp → BufferedWriter)
     * Writers stay open across client disconnects for reconnect continuity.
     */
    private static final Map<String, Map<String, BufferedWriter>> writers =
            new ConcurrentHashMap<>();

    /**
     * Maps clientIp → resolved folder name (hostname_ip or ip).
     * Once a folder is created for a client, the name is locked for the session
     * even if the hostname arrives later.
     */
    private static final Map<String, String> clientFolderMap = new ConcurrentHashMap<>();

    // ── Session lifecycle ─────────────────────────────────────────────────────

    public static synchronized void startSession() {
        if (sessionActive) {
            System.out.println("[SessionManager] Session already active");
            return;
        }

        sessionStart = LocalDateTime.now().format(DISPLAY_FMT);
        String folderName = "session_" + LocalDateTime.now().format(SESSION_FMT);

        try {
            sessionDir    = Paths.get("logs", folderName);
            Files.createDirectories(sessionDir);
            sessionActive = true;
            saveState(false); // cleanShutdown=false until proven clean
            System.out.println("[SessionManager] Session started: " + sessionDir);
        } catch (IOException e) {
            System.err.println("[SessionManager] Failed to create session folder: " + e.getMessage());
        }
    }

    public static synchronized void stopSession() {
        if (!sessionActive) return;

        sessionActive = false;
        String sessionEnd = LocalDateTime.now().format(DISPLAY_FMT);

        System.out.println("[SessionManager] Stopping session — merging files…");

        Set<String> allClientIps = new HashSet<>();
        for (Map<String, BufferedWriter> typeMap : writers.values()) {
            allClientIps.addAll(typeMap.keySet());
        }

        for (String clientIp : allClientIps) {
            for (Map<String, BufferedWriter> typeMap : writers.values()) {
                BufferedWriter w = typeMap.remove(clientIp);
                if (w != null) try { w.close(); } catch (IOException ignored) {}
            }
            mergeAndFinalize(clientIp, sessionEnd);
        }

        writers.clear();
        clientFolderMap.clear();

        // Save state — not clean yet, SimpleServer.shutdown() sets cleanShutdown=true
        saveState(false);

        System.out.println("[SessionManager] Session ended: " + sessionDir);
        sessionDir   = null;
        sessionStart = null;
    }

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Appends one line to the client's <logType>.txt file.
     *   SessionManager.write("activity", clientIp, data)
     *   SessionManager.write("keylog",   clientIp, data)
     *   SessionManager.write("usb",      clientIp, data)
     */
    public static void write(String logType, String clientIp, String data) {
        if (!sessionActive || sessionDir == null) return;

        BufferedWriter w = getWriter(logType, clientIp);
        if (w == null) return;

        try {
            w.write(data);
            w.newLine();
            w.flush();
        } catch (IOException e) {
            System.err.println("[SessionManager] Write error [" + logType + "] for "
                    + clientIp + ": " + e.getMessage());
        }
    }

    /**
     * Writes a reconnect marker to all open log files for this client.
     * Called by ConnectionManager when the same IP reconnects after a crash.
     */
    public static void writeReconnectMarker(String clientIp) {
        if (!sessionActive || sessionDir == null) return;

        String marker = "── Reconnected at " + LocalDateTime.now().format(DISPLAY_FMT) + " ──";

        for (Map.Entry<String, Map<String, BufferedWriter>> entry : writers.entrySet()) {
            BufferedWriter w = entry.getValue().get(clientIp);
            if (w != null) {
                try {
                    w.newLine();
                    w.write(marker);
                    w.newLine();
                    w.newLine();
                    w.flush();
                } catch (IOException e) {
                    System.err.println("[SessionManager] Reconnect marker error ["
                            + entry.getKey() + "] for " + clientIp);
                }
            } else {
                // Writer not open yet — this is a server-crash reconnect.
                // Writer will be created fresh in append mode on next write.
                // Write marker directly to the file if it exists on disk.
                writeMarkerToDisk(entry.getKey(), clientIp, marker);
            }
        }

        // Also handle types that have no writer yet (server crash recovery)
        // Check disk for existing log files for this client
        if (sessionDir != null) {
            Path clientDir = resolveClientFolder(clientIp);
            if (Files.exists(clientDir)) {
                try (var stream = Files.list(clientDir)) {
                    stream.filter(p -> p.toString().endsWith(".txt")
                                    && !p.getFileName().toString().equals("session.txt"))
                          .forEach(logFile -> {
                              String type = logFile.getFileName().toString().replace(".txt", "");
                              // Only write if no writer open yet for this type
                              Map<String, BufferedWriter> typeMap = writers.get(type);
                              if (typeMap == null || !typeMap.containsKey(clientIp)) {
                                  writeMarkerToDisk(type, clientIp, marker);
                              }
                          });
                } catch (IOException ignored) {}
            }
        }
    }

    private static void writeMarkerToDisk(String logType, String clientIp, String marker) {
        if (sessionDir == null) return;
        Path logFile = resolveClientFolder(clientIp).resolve(logType + ".txt");
        if (!Files.exists(logFile)) return;
        try (BufferedWriter w = new BufferedWriter(new FileWriter(logFile.toFile(), true))) {
            w.newLine();
            w.write(marker);
            w.newLine();
            w.newLine();
            w.flush();
        } catch (IOException e) {
            System.err.println("[SessionManager] Disk marker error [" + logType + "] for " + clientIp);
        }
    }

    // ── Merge on explicit kick ────────────────────────────────────────────────

    /**
     * Merges all log files for a single client immediately.
     * Called ONLY on explicit kick — NOT on unexpected disconnect.
     */
    public static void mergeClient(String clientIp) {
        if (!sessionActive || sessionDir == null) return;

        String sessionEnd = LocalDateTime.now().format(DISPLAY_FMT);

        for (Map<String, BufferedWriter> typeMap : writers.values()) {
            BufferedWriter w = typeMap.remove(clientIp);
            if (w != null) try { w.close(); } catch (IOException ignored) {}
        }

        mergeAndFinalize(clientIp, sessionEnd);
        System.out.println("[SessionManager] Client merged on kick: " + clientIp);
    }

    // ── Server crash recovery ─────────────────────────────────────────────────

    /**
     * Reads server_state.properties on startup.
     * Returns ServerState — SimpleServer decides what to do.
     */
    public static ServerState loadState() {
        try {
            if (!Files.exists(STATE_FILE)) {
                System.out.println("[SessionManager] No state file — fresh start");
                return new ServerState(true, false, null, null);
            }

            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(STATE_FILE)) {
                props.load(in);
            }

            boolean cleanShutdown  = Boolean.parseBoolean(props.getProperty("cleanShutdown",  "true"));
            boolean sessionActiveP = Boolean.parseBoolean(props.getProperty("sessionActive",  "false"));
            String  sessionDirP    = props.getProperty("sessionDir",   "");
            String  sessionStartP  = props.getProperty("sessionStart", "");

            System.out.println("[SessionManager] State loaded — cleanShutdown=" + cleanShutdown
                    + " sessionActive=" + sessionActiveP);

            return new ServerState(cleanShutdown, sessionActiveP,
                    sessionDirP.isEmpty() ? null : sessionDirP,
                    sessionStartP.isEmpty() ? null : sessionStartP);

        } catch (IOException e) {
            System.err.println("[SessionManager] State load error: " + e.getMessage());
            return new ServerState(true, false, null, null);
        }
    }

    /**
     * Restores a crashed session — called by SimpleServer on crash recovery.
     * Writers reopen in append mode on first write automatically.
     */
    public static synchronized void restoreSession(String savedSessionDir, String savedSessionStart) {
        sessionDir    = Paths.get(savedSessionDir);
        sessionStart  = savedSessionStart;
        sessionActive = true;
        System.out.println("[SessionManager] Session restored: " + sessionDir);
    }

    /**
     * Saves state to disk on every lifecycle change.
     * @param cleanShutdown true only when called from SimpleServer.shutdown()
     *                      after everything has been cleanly stopped.
     */
    public static void saveState(boolean cleanShutdown) {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            Properties props = new Properties();
            props.setProperty("cleanShutdown", String.valueOf(cleanShutdown));
            props.setProperty("sessionActive", String.valueOf(sessionActive));
            props.setProperty("sessionDir",    sessionDir   != null ? sessionDir.toString() : "");
            props.setProperty("sessionStart",  sessionStart != null ? sessionStart          : "");
            try (OutputStream out = Files.newOutputStream(STATE_FILE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(out, "ACS Server State — do not edit manually");
            }
        } catch (IOException e) {
            System.err.println("[SessionManager] State save error: " + e.getMessage());
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static BufferedWriter getWriter(String logType, String clientIp) {
        Map<String, BufferedWriter> typeMap =
                writers.computeIfAbsent(logType, k -> new ConcurrentHashMap<>());

        return typeMap.computeIfAbsent(clientIp, ip -> {
            try {
                Path clientDir = clientDir(ip);
                Path logFile   = clientDir.resolve(logType + ".txt");
                boolean isNew  = !Files.exists(logFile) || Files.size(logFile) == 0;

                BufferedWriter w = new BufferedWriter(new FileWriter(logFile.toFile(), true));

                // Only write header if file is new — don't repeat on reconnect
                if (isNew) {
                    String hostname = resolveHostname(ip);
                    w.write("=== Session Start: " + sessionStart + " ==="); w.newLine();
                    if (hostname != null) {
                        w.write("=== Client: " + hostname + " (" + ip + ") ===");
                    } else {
                        w.write("=== Client: " + ip + " ===");
                    }
                    w.newLine();
                    w.newLine();
                    w.flush();
                }

                System.out.println("[SessionManager] Opened [" + logType + "] for " + ip
                        + " → " + logFile + (isNew ? " (new)" : " (append)"));
                return w;
            } catch (IOException e) {
                System.err.println("[SessionManager] Failed to open [" + logType
                        + "] writer for " + ip + ": " + e.getMessage());
                return null;
            }
        });
    }

    private static void mergeAndFinalize(String clientIp, String sessionEnd) {
        if (sessionDir == null) return;

        try {
            Path clientDir   = resolveClientFolder(clientIp);
            Path sessionFile = clientDir.resolve("session.txt");

            List<Path> logFiles = new ArrayList<>();
            for (String type : new String[]{"activity", "keylog", "usb"}) {
                Path f = clientDir.resolve(type + ".txt");
                if (Files.exists(f)) logFiles.add(f);
            }
            if (Files.exists(clientDir)) {
                try (var stream = Files.list(clientDir)) {
                    stream.filter(p -> p.toString().endsWith(".txt")
                                    && !p.getFileName().toString().equals("session.txt"))
                          .filter(p -> logFiles.stream().noneMatch(k -> k.equals(p)))
                          .sorted()
                          .forEach(logFiles::add);
                }
            }

            if (logFiles.isEmpty()) {
                System.out.println("[SessionManager] No log files for " + clientIp + " — skipping");
                return;
            }

            // ── Check alert state for this client ─────────────────────────────
            boolean hasKeyword = AlertManager.hasKeywordAlert(clientIp);
            boolean hasUsb     = AlertManager.hasUsbAlert(clientIp);
            boolean flagged    = hasKeyword || hasUsb;

            try (BufferedWriter out = new BufferedWriter(new FileWriter(sessionFile.toFile()))) {

                // ── Detection summary header ──────────────────────────────────
                if (flagged) {
                    out.write("╔══════════════════════════════════════════╗"); out.newLine();
                    out.write("║         ⚠ VIOLATIONS DETECTED ⚠         ║"); out.newLine();
                    out.write("╠══════════════════════════════════════════╣"); out.newLine();
                    if (hasKeyword) {
                        out.write("║  • KEYWORD MATCH DETECTED               ║"); out.newLine();
                    }
                    if (hasUsb) {
                        out.write("║  • USB DEVICE DETECTED                  ║"); out.newLine();
                    }
                    out.write("╚══════════════════════════════════════════╝"); out.newLine();
                } else {
                    out.write("✔ NO VIOLATIONS DETECTED"); out.newLine();
                }
                out.newLine();

                // ── Client info ───────────────────────────────────────────────
                String hostname = resolveHostname(clientIp);
                if (hostname != null) {
                    out.write("Client: " + hostname + " (" + clientIp + ")");
                } else {
                    out.write("Client: " + clientIp);
                }
                out.newLine();
                out.write("Session: " + sessionStart + " → " + sessionEnd);
                out.newLine();
                out.newLine();

                // ── Log sections ──────────────────────────────────────────────
                for (Path logFile : logFiles) {
                    String typeName = logFile.getFileName().toString()
                            .replace(".txt", "").toUpperCase();
                    String sectionHeader = typeName.equals("KEYLOG")
                            ? "=== KEYLOG ==="
                            : "=== " + typeName + " LOG ===";
                    out.write(sectionHeader); out.newLine();
                    out.newLine();
                    for (String line : Files.readAllLines(logFile)) {
                        out.write(line); out.newLine();
                    }
                    out.newLine();
                }
                out.write("=== Session End: " + sessionEnd + " ==="); out.newLine();
                out.flush();
            }

            if (Files.exists(sessionFile) && Files.size(sessionFile) > 0) {
                for (Path logFile : logFiles) Files.delete(logFile);
                System.out.println("[SessionManager] Merged and finalized: " + sessionFile);
            } else {
                System.err.println("[SessionManager] Merge verification failed for "
                        + clientIp + " — intermediate files kept");
            }

            // ── Rename folder with _FLAGGED suffix if violations detected ────
            if (flagged) {
                String currentName = clientDir.getFileName().toString();
                if (!currentName.endsWith("_FLAGGED")) {
                    Path flaggedDir = clientDir.resolveSibling(currentName + "_FLAGGED");
                    try {
                        Files.move(clientDir, flaggedDir);
                        // Update cache so future lookups still work
                        clientFolderMap.put(clientIp, flaggedDir.getFileName().toString());
                        System.out.println("[SessionManager] Flagged: " + flaggedDir.getFileName());
                    } catch (IOException e) {
                        System.err.println("[SessionManager] Could not rename folder to FLAGGED: "
                                + e.getMessage());
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("[SessionManager] Merge failed for "
                    + clientIp + ": " + e.getMessage());
        }
    }

    /**
     * Resolves the client folder path for a given IP.
     * Uses hostname_ip format if hostname is known, otherwise IP only.
     * Caches the folder name for the session to prevent duplicates.
     */
    private static Path clientDir(String clientIp) throws IOException {
        String folderName = clientFolderMap.computeIfAbsent(clientIp, ip -> {
            String hostname = resolveHostname(ip);
            return hostname != null ? hostname + "_" + ip : ip;
        });
        Path dir = sessionDir.resolve(folderName);
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Resolves existing client folder — checks cached name, then scans disk
     * for any folder ending with the IP (handles hostname_ip format).
     */
    private static Path resolveClientFolder(String clientIp) {
        // Check cache first
        String cachedName = clientFolderMap.get(clientIp);
        if (cachedName != null) {
            return sessionDir.resolve(cachedName);
        }
        // Scan disk for folder matching this IP (may be hostname_ip or just ip)
        if (sessionDir != null && Files.exists(sessionDir)) {
            try (var stream = Files.list(sessionDir)) {
                var match = stream.filter(Files::isDirectory)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return name.equals(clientIp) || name.endsWith("_" + clientIp);
                        })
                        .findFirst();
                if (match.isPresent()) return match.get();
            } catch (IOException ignored) {}
        }
        // Fallback — just use IP
        return sessionDir.resolve(clientIp);
    }

    /**
     * Looks up the hostname for a client IP from ConnectionManager.
     */
    private static String resolveHostname(String clientIp) {
        // Check connected clients
        for (ClientHandler h : ConnectionManager.getAllClients().values()) {
            if (h.getClientIp().equals(clientIp) && h.getHostname() != null) {
                return h.getHostname();
            }
        }
        // Check disconnected clients
        ClientHandler disc = ConnectionManager.getDisconnectedClient(clientIp);
        if (disc != null && disc.getHostname() != null) {
            return disc.getHostname();
        }
        return null;
    }

    // ── Per-client lock state persistence ──────────────────────────────────────

    /**
     * Persists the client's input lock state to disk.
     * File: logs/session_XXX/hostname_ip/lock_state.properties
     * Called by ClientHandler on every lock state change.
     */
    public static void writeLockState(String clientIp, boolean locked) {
        if (!sessionActive || sessionDir == null) return;

        Path clientDir = resolveClientFolder(clientIp);
        if (!Files.exists(clientDir)) {
            try { Files.createDirectories(clientDir); }
            catch (IOException e) { return; }
        }

        Path lockFile = clientDir.resolve("lock_state.properties");
        try {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("inputLocked", String.valueOf(locked));
            try (OutputStream out = Files.newOutputStream(lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(out, "ACS Input Lock State — managed by server");
            }
        } catch (IOException e) {
            System.err.println("[SessionManager] Lock state write error for "
                    + clientIp + ": " + e.getMessage());
        }
    }

    /**
     * Reads the client's persisted input lock state from disk (crash recovery).
     * Returns false if state file is missing or unreadable.
     */
    public static boolean readLockState(String clientIp) {
        if (sessionDir == null) return false;

        Path clientDir = resolveClientFolder(clientIp);
        Path lockFile = clientDir.resolve("lock_state.properties");
        if (!Files.exists(lockFile)) return false;

        try {
            java.util.Properties props = new java.util.Properties();
            try (InputStream in = Files.newInputStream(lockFile)) {
                props.load(in);
            }
            boolean locked = Boolean.parseBoolean(props.getProperty("inputLocked", "false"));
            System.out.println("[SessionManager] Read lock state for " + clientIp + ": " + locked);
            return locked;
        } catch (IOException e) {
            System.err.println("[SessionManager] Lock state read error for "
                    + clientIp + ": " + e.getMessage());
            return false;
        }
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public static boolean isSessionActive() { return sessionActive;         }
    public static String  getSessionStart()  { return sessionStart;          }
    public static Path    getSessionDir()    { return sessionDir;            }

    // ── ServerState ───────────────────────────────────────────────────────────

    public static class ServerState {
        public final boolean cleanShutdown;
        public final boolean sessionActive;
        public final String  sessionDir;
        public final String  sessionStart;

        public ServerState(boolean cleanShutdown, boolean sessionActive,
                           String sessionDir, String sessionStart) {
            this.cleanShutdown = cleanShutdown;
            this.sessionActive = sessionActive;
            this.sessionDir    = sessionDir;
            this.sessionStart  = sessionStart;
        }

        /** True if server crashed with an active session that needs restoring. */
        public boolean isCrashRecovery() {
            return !cleanShutdown && sessionActive
                    && sessionDir != null && !sessionDir.isEmpty();
        }
    }
}