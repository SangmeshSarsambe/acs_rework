package com.acs;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.*;
import java.net.*;
import java.util.Scanner;

public class SimpleServer {
    private static JmDNS jmdns;
    private static ServerSocket serverSocket;
    private static volatile boolean isRunning = true;
    private static InetAddress localAddress;

    private static final Object shutdownLock = new Object();
    private static volatile boolean shutdownInitiated = false;

    // ── Server-side VlcNetworkPlayer process ──────────────────────────────────
    private static volatile Process vlcPlayerProcess = null;
    private static final Object vlcLock = new Object();

    public static void main(String[] args) {
        try {
            localAddress = IPAddressUtil.getActualIPAddress();

            serverSocket = new ServerSocket(0);
            int actualPort = serverSocket.getLocalPort();

            jmdns = JmDNS.create(localAddress);

            String serviceType = "_acs._tcp.local.";
            String serviceName = "SimpleServer";

            ServiceInfo serviceInfo = ServiceInfo.create(
                    serviceType, serviceName, actualPort, "Anti Cheat System Server");

            jmdns.registerService(serviceInfo);

            System.out.println("╔════════════════════════════════════════╗");
            System.out.println("║   Anti Cheat System Server Started     ║");
            System.out.println("╠════════════════════════════════════════╣");
            System.out.println("║ IP   : " + String.format("%-30s", localAddress.getHostAddress()) + "║");
            System.out.println("║ Port : " + String.format("%-30s", actualPort)                   + "║");
            System.out.println("║ Service: " + String.format("%-28s", serviceName)                + "║");
            System.out.println("╚════════════════════════════════════════╝\n");

            System.out.println("[Server] Waiting for client connections...\n");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!shutdownInitiated) {
                    System.out.println("\n[Server] Shutdown signal received (Ctrl+C)");
                    shutdown();
                }
            }));

            Thread acceptThread = new Thread(SimpleServer::acceptConnections);
            acceptThread.setName("ConnectionAcceptor");
            acceptThread.setDaemon(false);
            acceptThread.start();

            Thread consoleThread = new Thread(SimpleServer::interactiveConsole);
            consoleThread.setName("InteractiveConsole");
            consoleThread.setDaemon(false);
            consoleThread.start();

            synchronized (shutdownLock) {
                while (isRunning) {
                    try {
                        shutdownLock.wait();
                    } catch (InterruptedException e) {
                        System.out.println("[Server] Main thread interrupted");
                        break;
                    }
                }
            }

            System.out.println("[Server] Main thread exiting...");

        } catch (Exception e) {
            System.err.println("[Server] Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (!shutdownInitiated) shutdown();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accept connections
    // ─────────────────────────────────────────────────────────────────────────

    private static void acceptConnections() {
        System.out.println("[AcceptThread] Started");
        while (isRunning && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                Thread clientThread  = new Thread(handler);
                clientThread.setName("ClientHandler-" + clientSocket.getInetAddress().getHostAddress());
                clientThread.setDaemon(true);
                clientThread.start();
            } catch (SocketException e) {
                if (isRunning) System.err.println("[Server] Socket error: " + e.getMessage());
                break;
            } catch (IOException e) {
                if (isRunning) System.err.println("[Server] Error accepting connection: " + e.getMessage());
            }
        }
        System.out.println("[AcceptThread] Exiting");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VlcNetworkPlayer process management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Launches VlcNetworkPlayer as a separate JVM process.
     *
     * Uses the jar/class location of VlcNetworkPlayer.class itself to build
     * the classpath — this works whether running from an IDE, a fat-jar, or
     * plain compiled classes, and avoids ClassNotFoundException.
     */
    private static void launchVlcPlayer(String ip, int port) {
        synchronized (vlcLock) {
            stopVlcPlayer();

            try {
                // Use the exact java binary that launched this JVM
                String javaCmd = ProcessHandle.current()
                        .info().command().orElse("java");

                // java.class.path already has every jar/directory the JVM was
                // started with — this is always correct regardless of whether
                // we are running from an IDE, a fat-jar, or plain classes.
                String classpath = System.getProperty("java.class.path");

                System.out.println("[Server] java   : " + javaCmd);
                System.out.println("[Server] cp     : " + classpath);
                System.out.println("[Server] target : " + ip + ":" + port);

                ProcessBuilder pb = new ProcessBuilder(
                        javaCmd,
                        "-cp", classpath,
                        "com.acs.VlcNetworkPlayer",
                        ip,
                        String.valueOf(port)
                );

                pb.inheritIO();
                vlcPlayerProcess = pb.start();
                System.out.println("[Server] ✅ VlcNetworkPlayer launched (alive: "
                        + vlcPlayerProcess.isAlive() + ")");

            } catch (Exception e) {
                System.err.println("[Server] ❌ Failed to launch VlcNetworkPlayer: " + e.getMessage());
                e.printStackTrace();
                vlcPlayerProcess = null;
            }
        }
    }

    /** Kills the VlcNetworkPlayer process if it is running. */
    private static void stopVlcPlayer() {
        synchronized (vlcLock) {
            if (vlcPlayerProcess != null && vlcPlayerProcess.isAlive()) {
                System.out.println("[Server] Stopping VlcNetworkPlayer...");
                vlcPlayerProcess.destroy();
                try { vlcPlayerProcess.waitFor(); } catch (InterruptedException ignored) {}
                vlcPlayerProcess = null;
                System.out.println("[Server] ✅ VlcNetworkPlayer stopped");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Interactive console
    // ─────────────────────────────────────────────────────────────────────────

    private static void interactiveConsole() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║        Server Console - Type 'help' for commands    ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");

        while (isRunning) {
            try {
                System.out.print("server> ");
                if (!scanner.hasNextLine()) {
                    System.out.println("[Console] No more input available");
                    break;
                }

                String input = scanner.nextLine().trim();
                if (input.isEmpty()) continue;

                boolean shouldExit = processCommand(input);
                if (shouldExit) break;

            } catch (Exception e) {
                System.err.println("[Console] Error: " + e.getMessage());
                if (!isRunning) break;
            }
        }

        System.out.println("[Console] Console thread exiting");
        scanner.close();
    }

    private static boolean processCommand(String command) {
        String[] parts = command.split(" ", 2);
        String cmd  = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {

            case "help"            -> printHelp();
            case "list", "clients" -> ConnectionManager.displayAllClients();
            case "count"           -> System.out.println("Currently connected clients: " + ConnectionManager.getClientCount());
            case "status"          -> printServerStatus();
            case "clear"           -> clearScreen();

            case "send" -> {
                if (args.isEmpty()) {
                    System.out.println("Usage: send <client_ip:port> <message>");
                } else {
                    String[] sendParts = args.split(" ", 2);
                    if (sendParts.length < 2) System.out.println("Usage: send <client_ip:port> <message>");
                    else sendMessageToClient(sendParts[0], sendParts[1]);
                }
            }

            case "broadcast" -> {
                if (args.isEmpty()) System.out.println("Usage: broadcast <message>");
                else broadcastToAllClients(args);
            }

            case "disconnect" -> {
                if (args.isEmpty()) System.out.println("Usage: disconnect <client_ip:port>");
                else disconnectClient(args);
            }

            // ── START STREAM ──────────────────────────────────────────────────
            // Usage: startstream <client_ip:port>
            //
            // 1. Look up the ClientHandler for the given client
            // 2. Read server's ip:port FROM THAT SOCKET
            //    (handler.getServerIp() / handler.getServerPort())
            // 3. Launch VlcNetworkPlayer with that ip:port
            //    → listens on udp://@serverIp:serverPort
            // 4. Send "START_STREAM" to that client only
            //    → client reads socket.getInetAddress() + socket.getPort()
            //      (same server ip:port) → passes to FFmpegCommandBuilder
            //      → FFmpeg streams to udp://serverIp:serverPort
            case "startstream" -> {
                if (args.isEmpty()) {
                    System.out.println("Usage: startstream <client_ip:port>");
                    System.out.println("Example: startstream 192.168.1.5:54321");
                    break;
                }

                ClientHandler handler = ConnectionManager.getClient(args);
                if (handler == null || !handler.isClientAlive()) {
                    System.out.println("[Server] Client not found or not connected: " + args);
                    System.out.println("[Server] Connected clients:");
                    for (String id : ConnectionManager.getConnectedClientIPs()) {
                        System.out.println("  - " + id);
                    }
                    break;
                }

                String serverIp   = localAddress.getHostAddress();
                int    serverPort = serverSocket.getLocalPort();

                System.out.println("[Server] Starting stream for: " + args
                        + " | server endpoint: " + serverIp + ":" + serverPort);

                // Launch VLC player on server side
                launchVlcPlayer(serverIp, serverPort);

                // Tell the client to start FFmpeg
                handler.sendMessage("START_STREAM");
                System.out.println("[Server] ▶ START_STREAM sent to: " + args);
            }

            // ── STOP STREAM ───────────────────────────────────────────────────
            // Usage: stopstream <client_ip:port>
            case "stopstream" -> {
                if (args.isEmpty()) {
                    System.out.println("Usage: stopstream <client_ip:port>");
                    break;
                }

                ClientHandler handler = ConnectionManager.getClient(args);
                if (handler == null || !handler.isClientAlive()) {
                    System.out.println("[Server] Client not found or not connected: " + args);
                    break;
                }

                handler.sendMessage("STOP_STREAM");
                System.out.println("[Server] ⏹ STOP_STREAM sent to: " + args);

                stopVlcPlayer();
            }

            case "exit", "quit", "shutdown" -> {
                System.out.println("[Server] Initiating shutdown...");
                shutdown();
                return true;
            }

            default -> System.out.println("Unknown command: " + cmd + ". Type 'help' for available commands.");
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void sendMessageToClient(String clientId, String message) {
        boolean success = ConnectionManager.sendMessageToClient(clientId, "MSG: " + message);
        if (success) {
            System.out.println("[Server] Message sent to " + clientId);
        } else {
            System.out.println("[Server] Client " + clientId + " not found. Connected clients:");
            for (String ip : ConnectionManager.getConnectedClientIPs()) {
                System.out.println("  - " + ip);
            }
        }
    }

    private static void broadcastToAllClients(String message) {
        int count = ConnectionManager.getClientCount();
        if (count == 0) {
            System.out.println("[Server] No clients connected.");
        } else {
            ConnectionManager.broadcastMessage("MSG: " + message);
            System.out.println("[Server] Broadcast sent to " + count + " client(s)");
        }
    }

    private static void disconnectClient(String clientId) {
        ClientHandler handler = ConnectionManager.getClient(clientId);
        if (handler != null) {
            handler.disconnect();
            System.out.println("[Server] Client " + clientId + " disconnected.");
        } else {
            System.out.println("[Server] Client " + clientId + " not found.");
        }
    }

    private static void printServerStatus() {
        System.out.println("\n╔═══════════════════════════════════════╗");
        System.out.println("║        Server Status                  ║");
        System.out.println("╠═══════════════════════════════════════╣");
        System.out.println("║ IP Address: "        + String.format("%-24s", localAddress.getHostAddress())      + "║");
        System.out.println("║ TCP Port: "          + String.format("%-26s", serverSocket.getLocalPort())        + "║");
        System.out.println("║ Connected Clients: " + String.format("%-20s", ConnectionManager.getClientCount()) + "║");
        System.out.println("║ VLC Running: "       + String.format("%-23s",
                (vlcPlayerProcess != null && vlcPlayerProcess.isAlive()) ? "YES" : "NO")                        + "║");
        System.out.println("║ Server Status: "     + String.format("%-23s", isRunning ? "RUNNING" : "STOPPED") + "║");
        System.out.println("╚═══════════════════════════════════════╝\n");
    }

    private static void printHelp() {
        System.out.println("\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║                       Available Commands                          ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.println("║ help                          - Show this help menu               ║");
        System.out.println("║ list / clients                - List all connected clients        ║");
        System.out.println("║ count                         - Show number of clients            ║");
        System.out.println("║ status                        - Show server status                ║");
        System.out.println("║ send <ip:port> <msg>          - Send message to one client        ║");
        System.out.println("║ broadcast <message>           - Broadcast text to all clients     ║");
        System.out.println("║ disconnect <ip:port>          - Disconnect a specific client      ║");
        System.out.println("║ startstream <ip:port>         - Start stream for that client      ║");
        System.out.println("║ stopstream <ip:port>          - Stop stream for that client       ║");
        System.out.println("║ clear                         - Clear console                     ║");
        System.out.println("║ exit / shutdown               - Shutdown server                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝\n");
    }

    private static void clearScreen() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {
            System.out.println("[Console] Error clearing screen");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shutdown
    // ─────────────────────────────────────────────────────────────────────────

    private static void shutdown() {
        synchronized (shutdownLock) {
            if (shutdownInitiated) {
                System.out.println("[Server] Shutdown already in progress...");
                return;
            }
            shutdownInitiated = true;
            isRunning         = false;
        }

        System.out.println("[Server] Shutting down...");

        try {
            System.out.println("[Server] Sending STOP_STREAM to all clients...");
            ConnectionManager.broadcastMessage("STOP_STREAM");
            stopVlcPlayer();

            System.out.println("[Server] Disconnecting all clients...");
            ConnectionManager.disconnectAll();

            if (serverSocket != null && !serverSocket.isClosed()) {
                System.out.println("[Server] Closing server socket...");
                serverSocket.close();
            }

            if (jmdns != null) {
                System.out.println("[Server] Unregistering mDNS service...");
                jmdns.unregisterAllServices();
                jmdns.close();
            }

            System.out.println("[Server] Shutdown complete");

        } catch (Exception e) {
            System.err.println("[Server] Error during shutdown: " + e.getMessage());
            e.printStackTrace();
        } finally {
            synchronized (shutdownLock) {
                shutdownLock.notifyAll();
            }
        }
    }
}