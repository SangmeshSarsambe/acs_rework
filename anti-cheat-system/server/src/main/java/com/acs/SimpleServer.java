package com.acs;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.*;
import java.net.*;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Scanner;

public class SimpleServer {
    private static JmDNS jmdns;
    private static ServerSocket serverSocket;
    private static volatile boolean isRunning = true;  // volatile for thread visibility
    private static InetAddress localAddress;
    
    // Shutdown lock for coordinating shutdown
    private static final Object shutdownLock = new Object();
    private static volatile boolean shutdownInitiated = false;  // volatile for thread visibility

    public static void main(String[] args) {
        try {
            // Get actual IP address (not loopback)
            localAddress = IPAddressUtil.getActualIPAddress();

            // Create JmDNS instance
            jmdns = JmDNS.create(localAddress);

            // Service details
            String serviceType = "_acs._tcp.local.";
            String serviceName = "SimpleServer";
            int port = 6000;

            ServiceInfo serviceInfo = ServiceInfo.create(
                    serviceType,
                    serviceName,
                    port,
                    "Anti Cheat System Server"
            );

            // Register service with mDNS
            jmdns.registerService(serviceInfo);

            System.out.println("╔════════════════════════════════════════╗");
            System.out.println("║   Anti Cheat System Server Started     ║");
            System.out.println("╠════════════════════════════════════════╣");
            System.out.println("║ IP   : " + String.format("%-30s", localAddress.getHostAddress()) + "║");
            System.out.println("║ Port : " + String.format("%-30s", port) + "║");
            System.out.println("║ Service: " + String.format("%-28s", serviceName) + "║");
            System.out.println("╚════════════════════════════════════════╝\n");

            // Create server socket
            serverSocket = new ServerSocket(port);
            System.out.println("[Server] Waiting for client connections...\n");

            // Shutdown hook - only for Ctrl+C / kill signals
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!shutdownInitiated) {
                    System.out.println("\n[Server] Shutdown signal received (Ctrl+C)");
                    shutdown();
                }
            }));

            // Thread to accept client connections
            Thread acceptThread = new Thread(SimpleServer::acceptConnections);
            acceptThread.setName("ConnectionAcceptor");
            acceptThread.setDaemon(false);  // Keep JVM alive
            acceptThread.start();

            // Thread for interactive console
            Thread consoleThread = new Thread(SimpleServer::interactiveConsole);
            consoleThread.setName("InteractiveConsole");
            consoleThread.setDaemon(false);  // Keep JVM alive
            consoleThread.start();

            // Keep server alive until shutdown is requested
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
            // Ensure cleanup happens
            if (!shutdownInitiated) {
                shutdown();
            }
        }
    }

    /**
     * Accept incoming client connections
     */
    private static void acceptConnections() {
        System.out.println("[AcceptThread] Started");
        while (isRunning && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                
                // Handle each client in a separate thread
                ClientHandler handler = new ClientHandler(clientSocket);
                Thread clientThread = new Thread(handler);
                clientThread.setName("ClientHandler-" + clientSocket.getInetAddress().getHostAddress());
                clientThread.setDaemon(true);  // Don't keep JVM alive for client handlers
                clientThread.start();
                
            } catch (SocketException e) {
                if (isRunning) {
                    System.err.println("[Server] Socket error: " + e.getMessage());
                }
                // Socket closed during shutdown - exit gracefully
                break;
            } catch (IOException e) {
                if (isRunning) {
                    System.err.println("[Server] Error accepting connection: " + e.getMessage());
                }
            }
        }
        System.out.println("[AcceptThread] Exiting");
    }

    /**
     * Interactive console to manage server and send messages
     */
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
                if (shouldExit) {
                    break;  // Exit the console loop
                }
                
            } catch (Exception e) {
                System.err.println("[Console] Error: " + e.getMessage());
                if (!isRunning) break;
            }
        }
        
        System.out.println("[Console] Console thread exiting");
        scanner.close();
    }

    /**
     * Process console commands
     * @return true if server should shutdown
     */
    private static boolean processCommand(String command) {
        String[] parts = command.split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "help":
                printHelp();
                break;

            case "list":
            case "clients":
                ConnectionManager.displayAllClients();
                break;

            case "send":
                if (args.isEmpty()) {
                    System.out.println("Usage: send <client_ip:port> <message>");
                    System.out.println("Example: send 192.168.1.100:54321 Hello Client");
                } else {
                    String[] sendParts = args.split(" ", 2);
                    if (sendParts.length < 2) {
                        System.out.println("Usage: send <client_ip:port> <message>");
                    } else {
                        String clientId = sendParts[0];
                        String message = sendParts[1];
                        sendMessageToClient(clientId, message);
                    }
                }
                break;

            case "broadcast":
                if (args.isEmpty()) {
                    System.out.println("Usage: broadcast <message>");
                } else {
                    broadcastToAllClients(args);
                }
                break;

            case "count":
                int count = ConnectionManager.getClientCount();
                System.out.println("Currently connected clients: " + count);
                break;

            case "disconnect":
                if (args.isEmpty()) {
                    System.out.println("Usage: disconnect <client_ip:port>");
                } else {
                    disconnectClient(args);
                }
                break;

            case "status":
                printServerStatus();
                break;

            case "clear":
                clearScreen();
                break;

            case "exit":
            case "quit":
            case "shutdown":
                System.out.println("[Server] Initiating shutdown...");
                shutdown();
                return true;  // Signal to exit console loop

            default:
                System.out.println("Unknown command: " + cmd + ". Type 'help' for available commands.");
        }
        
        return false;  // Continue running
    }

    /**
     * Send message to a specific client
     */
    private static void sendMessageToClient(String clientId, String message) {
        boolean success = ConnectionManager.sendMessageToClient(clientId, "MSG: " + message);
        if (success) {
            System.out.println("[Server] Message sent to " + clientId);
        } else {
            System.out.println("[Server] Failed to send message. Client " + clientId + " not found or not connected.");
            System.out.println("[Server] Available clients:");
            for (String ip : ConnectionManager.getConnectedClientIPs()) {
                System.out.println("  - " + ip);
            }
        }
    }

    /**
     * Broadcast message to all connected clients
     */
    private static void broadcastToAllClients(String message) {
        int clientCount = ConnectionManager.getClientCount();
        if (clientCount == 0) {
            System.out.println("[Server] No clients connected.");
        } else {
            ConnectionManager.broadcastMessage("MSG: " + message);
            System.out.println("[Server] Broadcast sent to " + clientCount + " client(s)");
        }
    }

    /**
     * Disconnect a specific client
     */
    private static void disconnectClient(String clientId) {
        ClientHandler handler = ConnectionManager.getClient(clientId);
        if (handler != null) {
            handler.disconnect();
            System.out.println("[Server] Client " + clientId + " disconnected.");
        } else {
            System.out.println("[Server] Client " + clientId + " not found.");
        }
    }

    /**
     * Print server status
     */
    private static void printServerStatus() {
        System.out.println("\n╔═══════════════════════════════════════╗");
        System.out.println("║        Server Status                  ║");
        System.out.println("╠═══════════════════════════════════════╣");
        System.out.println("║ IP Address: " + String.format("%-24s", localAddress.getHostAddress()) + "║");
        System.out.println("║ Port: " + String.format("%-29s", "6000") + "║");
        System.out.println("║ Connected Clients: " + String.format("%-20s", ConnectionManager.getClientCount()) + "║");
        System.out.println("║ Server Status: " + String.format("%-23s", isRunning ? "RUNNING" : "STOPPED") + "║");
        System.out.println("╚═══════════════════════════════════════╝\n");
    }

    /**
     * Print help menu
     */
    private static void printHelp() {
        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║                    Available Commands                       ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.println("║ help                     - Show this help menu              ║");
        System.out.println("║ list / clients           - List all connected clients      ║");
        System.out.println("║ count                    - Show number of clients          ║");
        System.out.println("║ status                   - Show server status              ║");
        System.out.println("║ send <ip:port> <msg>     - Send message to client          ║");
        System.out.println("║ broadcast <message>      - Broadcast to all clients        ║");
        System.out.println("║ disconnect <ip:port>     - Disconnect specific client      ║");
        System.out.println("║ clear                    - Clear console                   ║");
        System.out.println("║ exit / shutdown          - Shutdown server                 ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");
    }

    /**
     * Clear console screen
     */
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

    /**
     * Shutdown server gracefully
     */
    private static void shutdown() {
        synchronized (shutdownLock) {
            if (shutdownInitiated) {
                System.out.println("[Server] Shutdown already in progress...");
                return;  // Already shutting down
            }
            shutdownInitiated = true;
            isRunning = false;
        }

        System.out.println("[Server] Shutting down...");

        try {
            // Disconnect all clients
            System.out.println("[Server] Disconnecting all clients...");
            ConnectionManager.disconnectAll();
            
            // Close server socket (this will unblock accept())
            if (serverSocket != null && !serverSocket.isClosed()) {
                System.out.println("[Server] Closing server socket...");
                serverSocket.close();
            }
            
            // Unregister mDNS service
            if (jmdns != null) {
                System.out.println("[Server] Unregistering mDNS service...");
                jmdns.unregisterAllServices();
                jmdns.close();
            }
            
            System.out.println("[Server] Server shutdown complete");
            
        } catch (Exception e) {
            System.err.println("[Server] Error during shutdown: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Wake up the main thread to exit
            synchronized (shutdownLock) {
                shutdownLock.notifyAll();
            }
        }
    }
}