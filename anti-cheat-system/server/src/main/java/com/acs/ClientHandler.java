package com.acs;

import java.io.*;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class ClientHandler extends Thread {

    private Socket           socket;
    private BufferedReader   reader;
    private BufferedWriter   writer;
    private String           clientId;
    private String           clientIp;
    private volatile boolean isRunning = true;
    private long             lastHeartbeat;

    private volatile String  disconnectTimestamp = null;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy_HH-mm-ss HH:mm:ss");

    private static final long HEARTBEAT_TIMEOUT        = 30000;
    private static final long HEARTBEAT_CHECK_INTERVAL = 5000;

    // ── Send queue ────────────────────────────────────────────────────────────
    private final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();

    // ── Activity log ──────────────────────────────────────────────────────────
    private final List<String> activityLog = Collections.synchronizedList(new ArrayList<>());
    private volatile Consumer<String> activityListener = null;

    // ── Keylog ────────────────────────────────────────────────────────────────
    private final List<String> keylogLog = Collections.synchronizedList(new ArrayList<>());
    private volatile Consumer<String> keylogListener = null;

    // ── USB ───────────────────────────────────────────────────────────────────
    private final List<String> usbLog = Collections.synchronizedList(new ArrayList<>());
    private volatile Consumer<String> usbListener = null;

    // ─────────────────────────────────────────────────────────────────────────

    public ClientHandler(Socket socket) {
        this.socket        = socket;
        this.lastHeartbeat = System.currentTimeMillis();
        this.clientIp      = socket.getInetAddress().getHostAddress();
        this.clientId      = clientIp + ":" + socket.getPort();
        try {
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void run() {
        try {
            System.out.println("[Handler] Client connected: " + clientId);
            ConnectionManager.addClient(clientId, this);

            startSenderThread();
            sendMessage("WELCOME: Connected to Anti Cheat System");

            Thread hbMonitor = new Thread(this::monitorHeartbeat);
            hbMonitor.setName("HeartbeatMonitor-" + clientId);
            hbMonitor.setDaemon(true);
            hbMonitor.start();

            String message;
            while (isRunning && (message = reader.readLine()) != null) {
                handleMessage(message);
            }
        } catch (IOException e) {
            if (isRunning) System.out.println("[Handler] Read error " + clientId
                    + ": " + e.getMessage());
        } finally {
            disconnect();
        }
    }

    // ── Sender thread ─────────────────────────────────────────────────────────

    private void startSenderThread() {
        Thread sender = new Thread(() -> {
            while (isRunning) {
                try {
                    String msg = sendQueue.take();
                    if (writer != null && !socket.isClosed()) {
                        writer.write(msg + "\n");
                        writer.flush();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    System.out.println("[SenderThread] Write error " + clientId);
                    disconnect();
                    break;
                }
            }
        });
        sender.setName("ClientSender-" + clientId);
        sender.setDaemon(true);
        sender.start();
    }

    // ── Message handling ──────────────────────────────────────────────────────

    private void handleMessage(String message) {
        if (message.equals("HEARTBEAT")) {
            lastHeartbeat = System.currentTimeMillis();
            sendMessage("HEARTBEAT_ACK");

        } else if (message.startsWith("ACTIVITY:")) {
            String data = message.substring(9);

            activityLog.add(data);
            SessionManager.write("activity", clientIp, data);
            AlertManager.checkActivity(clientIp, data); // keyword alert check

            if (activityListener != null) activityListener.accept(data);
            else System.out.println("[Activity][" + clientId + "] " + data);

        } else if (message.startsWith("KEYLOG:")) {
            String data = message.substring(7);

            keylogLog.add(data);
            SessionManager.write("keylog", clientIp, data);

            if (keylogListener != null) keylogListener.accept(data);
            else System.out.println("[Keylog][" + clientId + "] " + data);

        } else if (message.startsWith("USB:")) {
            String data = message.substring(4);

            usbLog.add(data);
            SessionManager.write("usb", clientIp, data);
            AlertManager.checkUsb(clientIp); // USB alert

            if (usbListener != null) usbListener.accept(data);
            else System.out.println("[USB][" + clientId + "] " + data);

        } else if (message.startsWith("MSG:")) {
            System.out.println("[Handler] Msg from " + clientId
                    + ": " + message.substring(4).trim());

        } else if (message.equals("DISCONNECT")) {
            System.out.println("[Handler] Client requested disconnect: " + clientId);
            disconnect();

        } else if (message.equals("PING")) {
            sendMessage("PONG");

        } else {
            System.out.println("[Handler] Unknown from " + clientId + ": " + message);
            sendMessage("ERROR: Unknown command");
        }
    }

    // ── Heartbeat monitor ─────────────────────────────────────────────────────

    private void monitorHeartbeat() {
        while (isRunning && socket != null && !socket.isClosed()) {
            try {
                long elapsed = System.currentTimeMillis() - lastHeartbeat;
                if (elapsed > HEARTBEAT_TIMEOUT) {
                    System.out.println("[Handler] Heartbeat timeout: " + clientId);
                    disconnect();
                    break;
                }
                Thread.sleep(HEARTBEAT_CHECK_INTERVAL);
            } catch (InterruptedException e) { break; }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void sendMessage(String message) {
        if (isRunning) sendQueue.offer(message);
    }

    // Activity
    public void setActivityListener(Consumer<String> listener) { this.activityListener = listener; }
    public void clearActivityListener()                        { this.activityListener = null; }
    public List<String> getActivityLog() { return new ArrayList<>(activityLog); }
    public void clearActivityLog()       { activityLog.clear(); }

    // Keylog
    public void setKeylogListener(Consumer<String> listener) { this.keylogListener = listener; }
    public void clearKeylogListener()                        { this.keylogListener = null; }
    public List<String> getKeylogLog() { return new ArrayList<>(keylogLog); }
    public void clearKeylogLog()       { keylogLog.clear(); }

    // USB
    public void setUsbListener(Consumer<String> listener) { this.usbListener = listener; }
    public void clearUsbListener()                        { this.usbListener = null; }
    public List<String> getUsbLog() { return new ArrayList<>(usbLog); }
    public void clearUsbLog()       { usbLog.clear(); }

    public void restoreUsbLog(List<String> previousLog) {
        usbLog.clear();
        usbLog.addAll(previousLog);
    }

    public void restoreActivityLog(List<String> previousLog) {
        activityLog.clear();
        activityLog.addAll(previousLog);
    }

    public void restoreKeylogLog(List<String> previousLog) {
        keylogLog.clear();
        keylogLog.addAll(previousLog);
    }

    // ── Disconnect ────────────────────────────────────────────────────────────

    /**
     * Unexpected disconnect (crash, network drop, heartbeat timeout).
     * Does NOT merge session files — logs stay open so that when the client
     * reconnects, data continues appending to the same activity/keylog files.
     * Merging only happens on explicit kick, server shutdown, or Stop Monitoring.
     */
    public void disconnect() {
        if (!isRunning) return;
        isRunning = false;
        disconnectTimestamp = LocalDateTime.now().format(TS_FMT);

        try {
            if (socket != null && !socket.isClosed()) socket.close();
            if (reader  != null) reader.close();
            if (writer  != null) writer.close();
        } catch (IOException e) { e.printStackTrace(); }

        System.out.println("[Handler] Disconnected: " + clientId
                + " at " + disconnectTimestamp);

        // NOTE: No SessionManager.mergeClient() here intentionally.
        // If the client crashed and reconnects, the same activity/keylog files
        // stay open and data just continues. Merge happens only on:
        //   - kickAndDisconnect()  (manual kick)
        //   - SessionManager.stopSession()  (Stop Monitoring or server shutdown)

        ConnectionManager.markDisconnected(clientId, this);
    }

    /**
     * Explicit kick by the operator.
     * Merges session files immediately — this client is intentionally removed.
     */
    public void kickAndDisconnect() {
        if (!isRunning) return;
        try {
            if (writer != null && !socket.isClosed()) {
                writer.write("KICKED\n");
                writer.flush();
            }
        } catch (IOException ignored) {}

        // Reset alerts — clean slate on kick
        AlertManager.resetAlerts(clientIp);

        // Merge before socket closes
        if (SessionManager.isSessionActive()) {
            SessionManager.mergeClient(clientIp);
        }

        disconnect();
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String  getClientId()            { return clientId; }
    public String  getClientIp()            { return clientIp; }
    public String  getDisconnectTimestamp() { return disconnectTimestamp; }
    public boolean isDisconnected()         { return disconnectTimestamp != null; }
    public String  getServerIp()            { return socket.getLocalAddress().getHostAddress(); }
    public int     getServerPort()          { return socket.getLocalPort(); }

    public String getClientInfo() {
        long secs = (System.currentTimeMillis() - lastHeartbeat) / 1000;
        return clientId + " (last heartbeat: " + secs + "s ago)";
    }

    public boolean isClientAlive() {
        return isRunning && socket != null
                && socket.isConnected() && !socket.isClosed();
    }
}