package com.acs;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionManager {
    private static final Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
    private static final Object lock = new Object();

    /**
     * Add a new client to the connection pool
     */
    public static void addClient(String clientId, ClientHandler handler) {
        synchronized (lock) {
            connectedClients.put(clientId, handler);
            System.out.println("[ConnectionManager] Client added: " + clientId);
            System.out.println("[ConnectionManager] Total clients: " + connectedClients.size());
        }
    }

    /**
     * Remove a client from the connection pool
     */
    public static void removeClient(String clientId) {
        synchronized (lock) {
            connectedClients.remove(clientId);
            System.out.println("[ConnectionManager] Client removed: " + clientId);
            System.out.println("[ConnectionManager] Total clients: " + connectedClients.size());
        }
    }

    /**
     * Get a specific client handler by ID
     */
    public static ClientHandler getClient(String clientId) {
        return connectedClients.get(clientId);
    }

    /**
     * Send message to a specific client
     */
    public static boolean sendMessageToClient(String clientId, String message) {
        ClientHandler handler = getClient(clientId);
        if (handler != null && handler.isClientAlive()) {
            handler.sendMessage(message);
            return true;
        }
        return false;
    }

    /**
     * Broadcast message to all connected clients
     */
    public static void broadcastMessage(String message) {
        synchronized (lock) {
            for (ClientHandler handler : connectedClients.values()) {
                if (handler.isClientAlive()) {
                    handler.sendMessage(message);
                }
            }
        }
    }

    /**
     * Get all connected clients
     */
    public static Map<String, ClientHandler> getAllClients() {
        return new HashMap<>(connectedClients);
    }

    /**
     * Get list of connected client IPs
     */
    public static List<String> getConnectedClientIPs() {
        synchronized (lock) {
            return new ArrayList<>(connectedClients.keySet());
        }
    }

    /**
     * Get number of connected clients
     */
    public static int getClientCount() {
        return connectedClients.size();
    }

    /**
     * Check if a client is connected
     */
    public static boolean isClientConnected(String clientId) {
        ClientHandler handler = getClient(clientId);
        return handler != null && handler.isClientAlive();
    }

    /**
     * Display all connected clients with status
     */
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

    /**
     * Disconnect all clients
     */
    public static void disconnectAll() {
        synchronized (lock) {
            for (ClientHandler handler : connectedClients.values()) {
                handler.disconnect();
            }
            connectedClients.clear();
        }
    }
}
