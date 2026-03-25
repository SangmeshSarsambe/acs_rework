package com.acs;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SimpleClient {

    private static Socket           socket;
    private static BufferedReader   reader;
    private static BufferedWriter   writer;
    private static String           serverIp;
    private static int              serverPort;
    private static volatile boolean isConnected      = false;
    private static volatile boolean isRunning        = true;
    private static volatile boolean wasKicked        = false;
    private static volatile boolean disconnectCalled = false;
    private static JmDNS            jmdns;

    private static final String SERVICE_TYPE = "_acs._tcp.local.";

    // ── Message queue ─────────────────────────────────────────────────────────
    private static final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();

    // ── File sender ───────────────────────────────────────────────────────────
    private static final FileSender fileSender = new FileSender(sendQueue);

    // ── Client state ──────────────────────────────────────────────────────────
    private static final Path CLIENT_STATE = Paths.get("client_logs", "client_state.properties");

    // ── Streaming ─────────────────────────────────────────────────────────────
    private static volatile Process ffmpegProcess = null;
    private static final Object     streamLock    = new Object();

    // ── Activity monitoring ───────────────────────────────────────────────────
    private static volatile ActivityMonitor activityMonitor = null;

    // ── Keylogging ────────────────────────────────────────────────────────────
    private static volatile KeylogMonitor keylogMonitor = null;

    // ── USB device monitoring ─────────────────────────────────────────────────
    private static volatile DeviceMonitor deviceMonitor = null;

    // ── JmDNS listener ────────────────────────────────────────────────────────
    private static final ServiceListener serviceListener = new ServiceListener() {
        @Override public void serviceAdded(ServiceEvent e) {
            jmdns.requestServiceInfo(e.getType(), e.getName(), true);
        }
        @Override public void serviceRemoved(ServiceEvent e) {}
        @Override public void serviceResolved(ServiceEvent e) {
            if (isConnected) return;
            ServiceInfo info = e.getInfo();
            serverIp   = info.getHostAddresses()[0];
            serverPort = info.getPort();
            System.out.println("[Discovery] Server found → " + serverIp + ":" + serverPort);
            connectToServer();
            if (isConnected) {
                startConnectionThreads();
                fileSender.resume(); // resume sending from saved offsets
            } else {
                System.out.println("[Discovery] Connect failed — waiting for server to be ready…");
                rediscover();
            }
        }
    };

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        // Prevent multiple client instances on the same machine
        SingleInstance.init("acs-client", null);

        try {
            System.out.println("╔════════════════════════════════════════╗");
            System.out.println("║     Anti Cheat System Client Started   ║");
            System.out.println("╚════════════════════════════════════════╝\n");

            // ── Handle client state (kicked / crash recovery) ─────────────────
            initClientState();

            InetAddress localAddress = IPAddressUtil.getActualIPAddress();
            System.out.println("[Client] Client IP: " + localAddress.getHostAddress() + "\n");

            // ── Start FileSender — reads .pending files and pushes to sendQueue
            fileSender.start();
            fileSender.pause(); // paused until connected

            jmdns = createJmDNS(localAddress);
            System.out.println("[Client] Searching for server via mDNS…\n");
            jmdns.addServiceListener(SERVICE_TYPE, serviceListener);

            Thread.sleep(3000);

            if (!isConnected) {
                System.out.println("[Client] Could not discover server. Exiting.");
                jmdns.close();
                System.exit(1);
            }

            synchronized (SimpleClient.class) {
                while (isRunning) SimpleClient.class.wait();
            }

        } catch (Exception e) {
            System.err.println("[Client] Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            disconnect();
        }
    }

    // ── Connection threads ────────────────────────────────────────────────────

    private static void startConnectionThreads() {
        //sendQueue.clear();
        startSenderThread();

        Thread listenerThread = new Thread(SimpleClient::listenForMessages);
        listenerThread.setName("ServerListener");
        listenerThread.start();

        Thread heartbeatThread = new Thread(SimpleClient::sendHeartbeat);
        heartbeatThread.setName("Heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    // ── Sender thread ─────────────────────────────────────────────────────────

    private static void startSenderThread() {
        Thread sender = new Thread(() -> {
            System.out.println("[SenderThread] Started");
            while (isRunning && isConnected) {
                try {
                    String msg = sendQueue.take();
                    if (writer != null) {
                        writer.write(msg + "\n");
                        writer.flush();
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
                    System.err.println("[SenderThread] Write error: " + e.getMessage());
                    isConnected = false;
                    break;
                }
            }
            System.out.println("[SenderThread] Exiting");
        });
        sender.setName("SenderThread");
        sender.setDaemon(true);
        sender.start();
    }

    // ── Connection ────────────────────────────────────────────────────────────

    private static void connectToServer() {
        try {
            socket           = new Socket(serverIp, serverPort);
            reader           = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer           = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            isConnected      = true;
            wasKicked        = false;
            disconnectCalled = false;
            System.out.println("[Connection] Connected → " + serverIp + ":" + serverPort);

            String welcome = reader.readLine();
            if (welcome != null) System.out.println("[Server] " + welcome);

        } catch (IOException e) {
            System.err.println("[Connection] Failed: " + e.getMessage());
            isConnected = false;
        }
    }

    // ── Message listener ──────────────────────────────────────────────────────

    private static void listenForMessages() {
        try {
            String message;
            while (isConnected && isRunning && (message = reader.readLine()) != null) {

                if (message.startsWith("START_STREAM")) {
                    handleStartStream();

                } else if (message.equals("STOP_STREAM")) {
                    System.out.println("[Stream] STOP_STREAM received");
                    stopStreaming();

                } else if (message.equals("START_ACTIVITY")) {
                    System.out.println("[Activity] START_ACTIVITY received");
                    handleStartActivity();

                } else if (message.equals("STOP_ACTIVITY")) {
                    System.out.println("[Activity] STOP_ACTIVITY received");
                    handleStopActivity();

                } else if (message.equals("START_KEYLOG")) {
                    System.out.println("[Keylog] START_KEYLOG received");
                    handleStartKeylog();

                } else if (message.equals("STOP_KEYLOG")) {
                    System.out.println("[Keylog] STOP_KEYLOG received");
                    handleStopKeylog();

                } else if (message.equals("START_USB")) {
                    System.out.println("[USB] START_USB received");
                    handleStartUsb();

                } else if (message.equals("STOP_USB")) {
                    System.out.println("[USB] STOP_USB received");
                    handleStopUsb();

                } else if (message.equals("KICKED")) {
                    System.out.println("[Client] Kicked by server");
                    wasKicked = true;
                    saveClientState(true);      // persist wasKicked=true
                    ClientLogger.cleanup();     // delete all .pending and .offset files
                    disconnect();
                    return;

                } else if (message.equals("HEARTBEAT_ACK") || message.startsWith("ACK:")) {
                    // silent

                } else if (message.startsWith("MSG:")) {
                    System.out.println("[Server Message] " + message.substring(4).trim());

                } else if (message.startsWith("ERROR:")) {
                    System.out.println("[Server Error] " + message);

                } else {
                    System.out.println("[Server] " + message);
                }
            }
        } catch (IOException e) {
            if (isRunning && isConnected) {
                System.err.println("[Connection] Lost: " + e.getMessage());
            }
        } finally {
            isConnected = false;
            stopStreaming();
            // handleStopActivity();
            // handleStopKeylog();
            // handleStopUsb(); // always stop USB monitor on disconnect

            if (!wasKicked && isRunning) {
                rediscover();
            }
        }
    }

    // ── Rediscovery ───────────────────────────────────────────────────────────

    private static void rediscover() {
        System.out.println("[Client] Connection lost — searching for server again…");

        fileSender.pause(); // stop sending until reconnected

        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}

        socket     = null;
        reader     = null;
        writer     = null;
        serverIp   = null;
        serverPort = 0;
        // NOTE: sendQueue is NOT cleared — FileSender is paused and will resume
        // from saved offsets after reconnect. No events lost.

        try { jmdns.removeServiceListener(SERVICE_TYPE, serviceListener); }
        catch (Exception ignored) {}

        System.out.println("[Client] Waiting for server to come back online…");
        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}

        try {
            jmdns.addServiceListener(SERVICE_TYPE, serviceListener);
        } catch (Exception e) {
            System.out.println("[Discovery] Listener re-register failed: " + e.getMessage());
        }
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private static void handleStartStream() {
        String ip   = socket.getInetAddress().getHostAddress();
        int    port = socket.getPort();
        System.out.println("[Stream] START_STREAM → streaming to " + ip + ":" + port);
        new Thread(() -> startStreaming(ip, port), "StreamStarter").start();
    }

    private static void startStreaming(String ip, int port) {
        synchronized (streamLock) {
            if (ffmpegProcess != null && ffmpegProcess.isAlive()) return;
            ffmpegProcess = FFmpegCommandBuilder.startStream(ip, port);
        }
    }

    private static void stopStreaming() {
        synchronized (streamLock) {
            if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
                ffmpegProcess.destroy();
                try { ffmpegProcess.waitFor(); } catch (InterruptedException ignored) {}
                ffmpegProcess = null;
                System.out.println("[Stream] FFmpeg stopped");
            }
        }
    }

    // ── Activity monitoring ───────────────────────────────────────────────────

    private static void handleStartActivity() {
        if (activityMonitor != null && activityMonitor.isRunning()) return;
        activityMonitor = new ActivityMonitor();
        activityMonitor.start();
    }

    private static void handleStopActivity() {
        if (activityMonitor != null) {
            activityMonitor.stop();
            activityMonitor = null;
        }
    }

    // ── Keylogging ────────────────────────────────────────────────────────────

    private static void handleStartKeylog() {
        if (keylogMonitor != null && keylogMonitor.isRunning()) return;
        keylogMonitor = new KeylogMonitor();
        keylogMonitor.start();
    }

    private static void handleStopKeylog() {
        if (keylogMonitor != null) {
            keylogMonitor.stop();
            keylogMonitor = null;
        }
    }

    // ── USB device monitoring ─────────────────────────────────────────────────

    private static void handleStartUsb() {
        if (deviceMonitor != null && deviceMonitor.isRunning()) return;
        deviceMonitor = new DeviceMonitor();
        deviceMonitor.start();
    }

    private static void handleStopUsb() {
        if (deviceMonitor != null) {
            deviceMonitor.stop();
            deviceMonitor = null;
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    private static void sendHeartbeat() {
        try {
            while (isConnected && isRunning) {
                Thread.sleep(10000);
                if (isConnected) sendQueue.offer("HEARTBEAT");
            }
        } catch (InterruptedException e) { /* exit */ }
    }

    // ── Client state ──────────────────────────────────────────────────────────

    /**
     * On startup: read client_state.properties.
     * If wasKicked=true → cleanup pending files, reset flag.
     * If wasKicked=false → FileSender will resume from saved offsets (crash recovery).
     * If file missing → fresh start.
     */
    private static void initClientState() {
        try {
            if (!Files.exists(CLIENT_STATE)) {
                saveClientState(false); // fresh start
                System.out.println("[Client] Fresh start — no previous state");
                return;
            }

            String content = Files.readString(CLIENT_STATE).trim();
            boolean wasKickedPrev = content.contains("wasKicked=true");

            if (wasKickedPrev) {
                System.out.println("[Client] Previous session ended by kick — cleaning up");
                ClientLogger.cleanup();
                saveClientState(false);
            } else {
                System.out.println("[Client] Resuming from previous state — FileSender will replay unsent events");
            }
        } catch (IOException e) {
            System.err.println("[Client] State read error: " + e.getMessage());
        }
    }

    private static void saveClientState(boolean wasKicked) {
        try {
            Files.createDirectories(CLIENT_STATE.getParent());
            Files.writeString(CLIENT_STATE,
                    "wasKicked=" + wasKicked + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[Client] State save error: " + e.getMessage());
        }
    }

    // ── Full disconnect / exit ────────────────────────────────────────────────

    private static void disconnect() {
        if (disconnectCalled) return;
        disconnectCalled = true;
        isRunning        = false;
        isConnected      = false;

        fileSender.stop();
        stopStreaming();
        handleStopActivity();
        handleStopKeylog();
        handleStopUsb();

        try {
            sendQueue.offer("DISCONNECT");
            Thread.sleep(100);
        } catch (InterruptedException ignored) {}

        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null && !socket.isClosed()) socket.close();
            if (jmdns  != null) jmdns.close();
        } catch (IOException e) { e.printStackTrace(); }

        synchronized (SimpleClient.class) { SimpleClient.class.notifyAll(); }
        System.out.println("[Client] Disconnected");
        System.exit(0);
    }

    // ── JmDNS creation with fallback ─────────────────────────────────────────

    private static JmDNS createJmDNS(InetAddress preferred) {
        try {
            JmDNS j = JmDNS.create(preferred);
            System.out.println("[JmDNS] Using interface: " + preferred.getHostAddress());
            return j;
        } catch (Exception e) {
            System.out.println("[JmDNS] Preferred address failed ("
                    + preferred.getHostAddress() + "): " + e.getMessage());
        }

        try {
            java.util.Enumeration<java.net.NetworkInterface> ifaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                java.util.Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress() || addr.equals(preferred)) continue;
                    if (!(addr instanceof java.net.Inet4Address)) continue;
                    try {
                        JmDNS j = JmDNS.create(addr);
                        System.out.println("[JmDNS] Fallback: "
                                + iface.getDisplayName() + " / " + addr.getHostAddress());
                        return j;
                    } catch (Exception ex) {
                        System.out.println("[JmDNS] Failed: " + addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[JmDNS] Interface enumeration failed: " + e.getMessage());
        }

        try {
            System.out.println("[JmDNS] Falling back to default JmDNS.create()");
            return JmDNS.create();
        } catch (Exception e) {
            throw new RuntimeException("[JmDNS] All attempts failed: " + e.getMessage(), e);
        }
    }
}