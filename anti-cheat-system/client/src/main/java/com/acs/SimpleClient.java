package com.acs;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Scanner;

public class SimpleClient {
    private static Socket socket;
    private static BufferedReader reader;
    private static BufferedWriter writer;
    private static String serverIp;
    private static int serverPort;
    private static boolean isConnected = false;
    private static boolean isRunning = true;
    private static JmDNS jmdns;

    public static void main(String[] args) throws Exception {
        try {
            System.out.println("╔════════════════════════════════════════╗");
            System.out.println("║     Anti Cheat System Client Started   ║");
            System.out.println("╚════════════════════════════════════════╝\n");

            // Get actual client IP address (not loopback)
            InetAddress localAddress = IPAddressUtil.getActualIPAddress();
            System.out.println("[Client] Client IP: " + localAddress.getHostAddress() + "\n");
            
            jmdns = JmDNS.create(localAddress);

            String serviceType = "_acs._tcp.local.";

            System.out.println("[Client] Searching for SimpleServer via mDNS...\n");

            // Service listener for mDNS discovery
            jmdns.addServiceListener(serviceType, new ServiceListener() {

                @Override
                public void serviceAdded(ServiceEvent event) {
                    System.out.println("[Discovery] Service added: " + event.getName());
                    jmdns.requestServiceInfo(event.getType(), event.getName(), true);
                }

                @Override
                public void serviceRemoved(ServiceEvent event) {
                    System.out.println("[Discovery] Service removed: " + event.getName());
                    if (isConnected) {
                        disconnect();
                    }
                }

                @Override
                public void serviceResolved(ServiceEvent event) {
                    ServiceInfo info = event.getInfo();
                    serverIp = info.getHostAddresses()[0];
                    serverPort = info.getPort();

                    System.out.println("\n╔════════════════════════════════════════╗");
                    System.out.println("║       Server Discovered!               ║");
                    System.out.println("╠════════════════════════════════════════╣");
                    System.out.println("║ Name : " + String.format("%-30s", info.getName()) + "║");
                    System.out.println("║ IP   : " + String.format("%-30s", serverIp) + "║");
                    System.out.println("║ Port : " + String.format("%-30s", serverPort) + "║");
                    System.out.println("║ Desc : " + String.format("%-30s", info.getNiceTextString()) + "║");
                    System.out.println("╚════════════════════════════════════════╝\n");

                    // Attempt to connect
                    connectToServer();
                }
            });

            // Wait for discovery
            Thread.sleep(15000);

            if (!isConnected) {
                System.out.println("[Client] Could not discover server. Exiting.");
                jmdns.close();
                System.exit(1);
            }

            // Thread to listen for incoming messages from server
            Thread listenerThread = new Thread(SimpleClient::listenForMessages);
            listenerThread.setName("ServerListener");
            listenerThread.start();

            // Thread to send periodic heartbeat
            Thread heartbeatThread = new Thread(SimpleClient::sendHeartbeat);
            heartbeatThread.setName("Heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();

            // Interactive console
            interactiveConsole();

        } catch (Exception e) {
            System.err.println("[Client] Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            disconnect();
        }
    }

    /**
     * Connect to the discovered server via TCP
     * 
     * Note: Uses serverIp (discovered via mDNS), NOT client's own IP
     * The client connects TO the server, so it needs the server's IP address
     */
    private static void connectToServer() {
        try {
            // Connect to SERVER's IP:PORT (discovered via mDNS)
            socket = new Socket(serverIp, serverPort);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            isConnected = true;

            System.out.println("[Connection] Connected to server: " + serverIp + ":" + serverPort + "\n");
            
            // Listen for welcome message
            String welcomeMsg = reader.readLine();
            if (welcomeMsg != null) {
                System.out.println("[Server] " + welcomeMsg);
            }

        } catch (IOException e) {
            System.err.println("[Connection] Failed to connect to server: " + e.getMessage());
            isConnected = false;
        }
    }

    /**
     * Listen for messages from the server
     */
    private static void listenForMessages() {
        try {
            String message;
            while (isConnected && isRunning && (message = reader.readLine()) != null) {
                if (message.startsWith("MSG:")) {
                    System.out.println("\n[Server Message] " + message.substring(4).trim());
                    System.out.print("client> ");
                } else if (message.equals("HEARTBEAT_ACK")) {
                    // Silent heartbeat acknowledgment
                } else if (message.startsWith("ACK:")) {
                    System.out.println("\n[Server] " + message);
                    System.out.print("client> ");
                } else if (message.startsWith("ERROR:")) {
                    System.out.println("\n[Server Error] " + message);
                    System.out.print("client> ");
                } else {
                    System.out.println("\n[Server] " + message);
                    System.out.print("client> ");
                }
            }
        } catch (IOException e) {
            if (isRunning && isConnected) {
                System.err.println("\n[Connection] Lost connection to server: " + e.getMessage());
            }
        } finally {
            isConnected = false;
        }
    }

    /**
     * Send periodic heartbeat to keep connection alive
     */
    private static void sendHeartbeat() {
        try {
            while (isConnected && isRunning) {
                Thread.sleep(10000); // Send heartbeat every 10 seconds
                if (isConnected && writer != null) {
                    writer.write("HEARTBEAT\n");
                    writer.flush();
                }
            }
        } catch (InterruptedException | IOException e) {
            // Ignore
        }
    }

    /**
     * Interactive console for user input
     */
    private static void interactiveConsole() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║        Client Console - Type 'help' for commands   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝\n");

        while (isRunning && isConnected) {
            try {
                System.out.print("client> ");
                if (!scanner.hasNextLine()) break;

                String input = scanner.nextLine().trim();

                if (input.isEmpty()) continue;

                processCommand(input);

            } catch (Exception e) {
                System.err.println("[Console] Error: " + e.getMessage());
            }
        }

        scanner.close();
    }

    /**
     * Process console commands
     */
    private static void processCommand(String command) {
        String[] parts = command.split(" ", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "help":
                printHelp();
                break;

            case "send":
            case "msg":
                if (args.isEmpty()) {
                    System.out.println("Usage: send <message>");
                    System.out.println("Example: send Hello Server");
                } else {
                    sendMessageToServer(args);
                }
                break;

            case "ping":
                sendMessageToServer("PING");
                break;

            case "status":
                printStatus();
                break;

            case "disconnect":
            case "exit":
            case "quit":
                System.out.println("[Client] Disconnecting...");
                isRunning = false;
                disconnect();
                break;

            case "clear":
                clearScreen();
                break;

            default:
                // Treat as message
                sendMessageToServer(command);
        }
    }

    /**
     * Send message to server
     */
    private static void sendMessageToServer(String message) {
        if (!isConnected) {
            System.out.println("[Client] Not connected to server.");
            return;
        }

        try {
            writer.write("MSG: " + message + "\n");
            writer.flush();
            System.out.println("[Sent] " + message);
        } catch (IOException e) {
            System.err.println("[Client] Error sending message: " + e.getMessage());
            isConnected = false;
        }
    }

    /**
     * Print status
     */
    private static void printStatus() {
        System.out.println("\n╔═══════════════════════════════════════╗");
        System.out.println("║        Client Status                  ║");
        System.out.println("╠═══════════════════════════════════════╣");
        System.out.println("║ Server IP: " + String.format("%-25s", serverIp) + "║");
        System.out.println("║ Port: " + String.format("%-30s", serverPort) + "║");
        System.out.println("║ Connected: " + String.format("%-25s", isConnected ? "YES" : "NO") + "║");
        System.out.println("╚═══════════════════════════════════════╝\n");
    }

    /**
     * Print help menu
     */
    private static void printHelp() {
        System.out.println("\n╔════════════════════════════════════════════════════╗");
        System.out.println("║              Available Commands                   ║");
        System.out.println("╠════════════════════════════════════════════════════╣");
        System.out.println("║ help                 - Show this help menu         ║");
        System.out.println("║ send <message>       - Send message to server      ║");
        System.out.println("║ <message>            - Send message (no prefix)    ║");
        System.out.println("║ ping                 - Ping the server             ║");
        System.out.println("║ status               - Show connection status      ║");
        System.out.println("║ clear                - Clear console               ║");
        System.out.println("║ disconnect / exit    - Disconnect and exit         ║");
        System.out.println("╚════════════════════════════════════════════════════╝\n");
    }

    /**
     * Clear console
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
     * Disconnect from server
     */
    private static void disconnect() {
        isRunning = false;
        isConnected = false;
        try {
            if (writer != null) {
                writer.write("DISCONNECT\n");
                writer.flush();
            }
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null && !socket.isClosed()) socket.close();
            if (jmdns != null) jmdns.close();

            System.out.println("[Client] Disconnected successfully");
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.exit(0);
    }
}