package com.acs;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages keyword list and per-client alert flags.
 *
 * Two alert types:
 *   KEYWORD — triggered when activity data contains a flagged keyword
 *   USB     — triggered when any USB event is received
 *
 * Alert flags are per-client, sticky — only reset on kickAndDisconnect().
 * Keywords are persisted to keywords.txt — survive server restart.
 *
 * Matching: case-insensitive contains match.
 *   "chatgpt" matches "User opened chatgpt.com in Chrome"
 */
public class AlertManager {

    private static final Path KEYWORDS_FILE = Paths.get("keywords.txt");

    // Pre-built keywords — loaded on first launch if keywords.txt doesn't exist
    private static final List<String> DEFAULT_KEYWORDS = Arrays.asList(
        "chatgpt", "google", "youtube", "discord", "whatsapp", "telegram",
        "teamviewer", "anydesk", "chrome", "firefox", "edge", "opera",
        "cheatengine", "wemod", "cheathappens", "trainer", "hack",
        "notepad", "pastebin", "obs", "streamlabs"
    );

    // Current keyword set — case insensitive storage (stored lowercase)
    private static final Set<String> keywords =
            Collections.synchronizedSet(new LinkedHashSet<>());

    // Per-client alert flags — keyed by clientIp
    private static final Map<String, Boolean> keywordAlerts = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> usbAlerts     = new ConcurrentHashMap<>();

    // UI callback — fired when an alert is triggered
    // ServerUI sets this to update the card
    private static volatile java.util.function.BiConsumer<String, String> onAlert = null;

    static {
        loadKeywords();
    }

    // ── Keyword management ────────────────────────────────────────────────────

    public static void addKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return;
        keywords.add(keyword.trim().toLowerCase());
        saveKeywords();
    }

    public static void removeKeyword(String keyword) {
        keywords.remove(keyword.trim().toLowerCase());
        saveKeywords();
    }

    public static List<String> getKeywords() {
        synchronized (keywords) {
            return new ArrayList<>(keywords);
        }
    }

    // ── Alert checking ────────────────────────────────────────────────────────

    /**
     * Called by ClientHandler on every ACTIVITY: message.
     * Case-insensitive contains match against all keywords.
     */
    public static void checkActivity(String clientIp, String data) {
        String lower = data.toLowerCase();
        synchronized (keywords) {
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    setKeywordAlert(clientIp);
                    System.out.println("[AlertManager] Keyword match [" + keyword
                            + "] for " + clientIp);
                    return; // one match is enough
                }
            }
        }
    }

    /**
     * Called by ClientHandler on every USB: message.
     * Any USB event triggers the alert.
     */
    public static void checkUsb(String clientIp) {
        setUsbAlert(clientIp);
    }

    // ── Alert state ───────────────────────────────────────────────────────────

    private static void setKeywordAlert(String clientIp) {
        keywordAlerts.put(clientIp, true);
        if (onAlert != null) onAlert.accept(clientIp, "KEYWORD");
    }

    private static void setUsbAlert(String clientIp) {
        usbAlerts.put(clientIp, true);
        if (onAlert != null) onAlert.accept(clientIp, "USB");
    }

    public static boolean hasKeywordAlert(String clientIp) {
        return Boolean.TRUE.equals(keywordAlerts.get(clientIp));
    }

    public static boolean hasUsbAlert(String clientIp) {
        return Boolean.TRUE.equals(usbAlerts.get(clientIp));
    }

    /**
     * Resets all alerts for a client.
     * Called ONLY from kickAndDisconnect().
     */
    public static void resetAlerts(String clientIp) {
        keywordAlerts.remove(clientIp);
        usbAlerts.remove(clientIp);
        System.out.println("[AlertManager] Alerts reset for " + clientIp);
    }

    // ── UI callback ───────────────────────────────────────────────────────────

    public static void setOnAlert(java.util.function.BiConsumer<String, String> callback) {
        onAlert = callback;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private static void loadKeywords() {
        try {
            if (!Files.exists(KEYWORDS_FILE)) {
                // First launch — use defaults
                keywords.addAll(DEFAULT_KEYWORDS);
                saveKeywords();
                System.out.println("[AlertManager] Keywords initialized with defaults");
                return;
            }
            List<String> lines = Files.readAllLines(KEYWORDS_FILE);
            for (String line : lines) {
                String kw = line.trim().toLowerCase();
                if (!kw.isEmpty()) keywords.add(kw);
            }
            System.out.println("[AlertManager] Loaded " + keywords.size() + " keywords");
        } catch (IOException e) {
            System.err.println("[AlertManager] Load error: " + e.getMessage());
            keywords.addAll(DEFAULT_KEYWORDS);
        }
    }

    private static void saveKeywords() {
        try {
            synchronized (keywords) {
                Files.write(KEYWORDS_FILE, keywords,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[AlertManager] Save error: " + e.getMessage());
        }
    }
}
