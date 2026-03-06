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
    private static final long HEARTBEAT_TIMEOUT = 30000;
    private static final long HEARTBEAT_CHECK_INTERVAL = 5000;

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

            ConnectionManager.addClient(clientId, this);

            sendMessage("WELCOME: Connected to Anti Cheat System");

            Thread heartbeatMonitor = new Thread(this::monitorHeartbeat);
            heartbeatMonitor.setDaemon(true);
            heartbeatMonitor.start();

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
            sendHeartbeatAck("HEARTBEAT_ACK");
        } else if (message.startsWith("MSG:")) {
            String content = message.substring(4).trim();
            System.out.println("[Handler] Message from " + clientId + ": " + content);
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

    public void sendHeartbeatAck(String message) {
        try {
            if (writer != null && !socket.isClosed()) {
                writer.write(message + "\n");
                writer.flush();
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
        // Close socket FIRST to unblock readLine() immediately
        if (socket != null && !socket.isClosed()) socket.close();
        if (reader != null) reader.close();
        if (writer != null) writer.close();
    } catch (IOException e) {
        e.printStackTrace();
    }

    System.out.println("[Handler] Disconnected: " + clientId);
    ConnectionManager.removeClient(clientId);
}

    public String getClientInfo() {
        long timeSinceHeartbeat = (System.currentTimeMillis() - lastHeartbeat) / 1000;
        return clientId + " (last heartbeat: " + timeSinceHeartbeat + "s ago)";
    }

    public String getClientId() {
        return clientId;
    }

    public boolean isClientAlive() {
        return isRunning && socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Server's own IP address on this socket connection.
     * This is the IP the client connected to — same IP both VLC and FFmpeg use.
     */
    public String getServerIp() {
        return socket.getLocalAddress().getHostAddress();
    }

    /**
     * Server-side port of this specific socket connection.
     * The client reads this same port from its own socket.getPort().
     * VLC listens on this port; FFmpeg streams to this port.
     */
    public int getServerPort() {
        return socket.getLocalPort();
    }
}