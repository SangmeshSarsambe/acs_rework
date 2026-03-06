package com.acs;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ConnectionManager {

    private static final Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
    private static final Object lock = new Object();

    // ── UI Listener ───────────────────────────────────────────────────────────
    // ServerUI registers itself here so it gets notified on every connect/disconnect.
    // To add a second listener later: change this to a List<ConnectionListener>.
    private static volatile ConnectionListener uiListener = null;

    public static void setListener(ConnectionListener listener) {
        uiListener = listener;
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Add a new client to the connection pool.
     * Fires onClientConnected on the registered listener (if any).
     */
    public static void addClient(String clientId, ClientHandler handler) {
        synchronized (lock) {
            connectedClients.put(clientId, handler);
            System.out.println("[ConnectionManager] Client added: " + clientId);
            System.out.println("[ConnectionManager] Total clients: " + connectedClients.size());
        }
        // Notify UI outside the lock so the UI refresh cannot deadlock
        if (uiListener != null) uiListener.onClientConnected(clientId);
    }

    /**
     * Remove a client from the connection pool.
     * Fires onClientDisconnected on the registered listener (if any).
     */
    public static void removeClient(String clientId) {
        synchronized (lock) {
            connectedClients.remove(clientId);
            System.out.println("[ConnectionManager] Client removed: " + clientId);
            System.out.println("[ConnectionManager] Total clients: " + connectedClients.size());
        }
        // Notify UI outside the lock
        if (uiListener != null) uiListener.onClientDisconnected(clientId);
    }

    // ── Everything below is unchanged from original ───────────────────────────

    public static ClientHandler getClient(String clientId) {
        return connectedClients.get(clientId);
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
                if (handler.isClientAlive()) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    public static Map<String, ClientHandler> getAllClients() {
        return new HashMap<>(connectedClients);
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
            if (connectedClients.isEmpty()) {
                System.out.println("\n[ConnectionManager] No clients connected");
                return;
            }
            System.out.println("\n========================================");
            System.out.println("Connected Clients (" + connectedClients.size() + "):");
            System.out.println("========================================");
            int index = 1;
            for (String clientId : connectedClients.keySet()) {
                ClientHandler handler = connectedClients.get(clientId);
                System.out.println(index + ". " + handler.getClientInfo());
                index++;
            }
            System.out.println("========================================\n");
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

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(handlers.size(), 20));
        for (ClientHandler handler : handlers) {
            executor.submit(() -> {
                try { handler.disconnect(); }
                catch (Exception e) {
                    System.out.println("[ConnectionManager] Error disconnecting: " + e.getMessage());
                }
            });
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                System.out.println("[ConnectionManager] Force-closed remaining connections");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("[ConnectionManager] All clients disconnected");
    }
}
