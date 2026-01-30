package com.acs;

import java.io.*;
import java.net.Socket;

public class ClientHandler extends Thread {
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private String clientId;
    private boolean isRunning = true;
    private long lastHeartbeat;
    private static final long HEARTBEAT_TIMEOUT = 30000; // 30 seconds
    private static final long HEARTBEAT_CHECK_INTERVAL = 5000; // Check every 5 seconds

    public ClientHandler(Socket socket) {
        this.socket = socket;
        this.lastHeartbeat = System.currentTimeMillis();
        this.clientId = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        
        try {
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            System.out.println("[Handler] Client connected: " + clientId);
            
            // Add client to connection manager
            ConnectionManager.addClient(clientId, this);
            
            // Send welcome message
            sendMessage("WELCOME: Connected to Anti Cheat System");

            // Start heartbeat monitoring thread
            Thread heartbeatMonitor = new Thread(this::monitorHeartbeat);
            heartbeatMonitor.setDaemon(true);
            heartbeatMonitor.start();

            // Listen for messages from client
            String message;
            while (isRunning && (message = reader.readLine()) != null) {
                handleMessage(message);
            }
        } catch (IOException e) {
            if (isRunning) {
                System.out.println("[Handler] Error reading from " + clientId + ": " + e.getMessage());
            }
        } finally {
            disconnect();
        }
    }

    /**
     * Monitor heartbeat and disconnect if timeout
     */
    private void monitorHeartbeat() {
        while (isRunning && socket != null && !socket.isClosed()) {
            try {
                long timeSinceHeartbeat = System.currentTimeMillis() - lastHeartbeat;
                
                if (timeSinceHeartbeat > HEARTBEAT_TIMEOUT) {
                    System.out.println("[Handler] Heartbeat timeout for " + clientId + 
                                     ". No heartbeat for " + (timeSinceHeartbeat / 1000) + " seconds");
                    disconnect();
                    break;
                }
                
                Thread.sleep(HEARTBEAT_CHECK_INTERVAL);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void handleMessage(String message) {
        if (message.equals("HEARTBEAT")) {
            lastHeartbeat = System.currentTimeMillis();
            System.out.println("[Handler] Heartbeat from " + clientId);
            sendMessage("HEARTBEAT_ACK");
        } else if (message.startsWith("MSG:")) {
            String content = message.substring(4).trim();
            System.out.println("[Handler] Message from " + clientId + ": " + content);
            sendMessage("ACK: Message received");
        } else if (message.equals("DISCONNECT")) {
            System.out.println("[Handler] Client requested disconnect: " + clientId);
            disconnect();
        } else if (message.equals("PING")) {
            sendMessage("PONG");
        } else {
            System.out.println("[Handler] Unknown message from " + clientId + ": " + message);
            sendMessage("ERROR: Unknown command");
        }
    }

    public void sendMessage(String message) {
        try {
            if (writer != null && !socket.isClosed()) {
                writer.write(message + "\n");
                writer.flush();
                System.out.println("[Handler] Sent to " + clientId + ": " + message);
            }
        } catch (IOException e) {
            System.out.println("[Handler] Error sending to " + clientId + ": " + e.getMessage());
            disconnect();
        }
    }

    public void disconnect() {
        if (!isRunning) return;
        
        isRunning = false;
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null && !socket.isClosed()) socket.close();
            System.out.println("[Handler] Disconnected: " + clientId);
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        // Remove from connection manager
        ConnectionManager.removeClient(clientId);
    }

    public String getClientInfo() {
        long timeSinceHeartbeat = (System.currentTimeMillis() - lastHeartbeat) / 1000;
        return clientId + " (last heartbeat: " + timeSinceHeartbeat + "s ago)";
    }

    public String getClientId() {
        return clientId;
    }

    /**
     * Check if the client connection is alive and active
     * (Different from Thread.isAlive() which checks thread status)
     */
    public boolean isClientAlive() {
        return isRunning && socket != null && socket.isConnected() && !socket.isClosed();
    }
}