package com.acs;

import com.acs.ui.ServerUI;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import javax.swing.*;
import java.io.*;
import java.net.*;

public class SimpleServer {

    private static JmDNS          jmdns;
    private static ServerSocket   serverSocket;
    private static volatile boolean isRunning = true;
    private static InetAddress     localAddress;

    private static final Object  shutdownLock      = new Object();
    private static volatile boolean shutdownInitiated = false;

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        try {
            localAddress = IPAddressUtil.getActualIPAddress();

            serverSocket = new ServerSocket(0);
            int actualPort = serverSocket.getLocalPort();

            // ── mDNS registration ─────────────────────────────────────────
            jmdns = JmDNS.create(localAddress);
            ServiceInfo serviceInfo = ServiceInfo.create(
                    "_acs._tcp.local.", "SimpleServer", actualPort,
                    "Anti Cheat System Server");
            jmdns.registerService(serviceInfo);

            System.out.println("[Server] Started — " + localAddress.getHostAddress() + ":" + actualPort);

            // ── Shutdown hook (Ctrl-C / System.exit) ──────────────────────
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!shutdownInitiated) {
                    System.out.println("\n[Server] Shutdown signal received");
                    shutdown();
                }
            }));

            // ── Launch Swing UI ───────────────────────────────────────────
            final String ip   = localAddress.getHostAddress();
            final int    port = actualPort;

            SwingUtilities.invokeLater(() -> {
                ServerUI ui = new ServerUI(ip, port);
                ui.setVisible(true);
            });

            // ── Accept connections (background thread) ────────────────────
            Thread acceptThread = new Thread(SimpleServer::acceptConnections);
            acceptThread.setName("ConnectionAcceptor");
            acceptThread.setDaemon(false);
            acceptThread.start();

            // ── Park the main thread until shutdown ───────────────────────
            synchronized (shutdownLock) {
                while (isRunning) {
                    try { shutdownLock.wait(); }
                    catch (InterruptedException e) { break; }
                }
            }

            System.out.println("[Server] Main thread exiting");

        } catch (Exception e) {
            System.err.println("[Server] Fatal error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (!shutdownInitiated) shutdown();
        }
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private static void acceptConnections() {
        System.out.println("[AcceptThread] Waiting for clients…");
        while (isRunning && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                Thread t = new Thread(handler);
                t.setName("ClientHandler-" + clientSocket.getInetAddress().getHostAddress());
                t.setDaemon(true);
                t.start();
            } catch (SocketException e) {
                if (isRunning) System.err.println("[Server] Socket error: " + e.getMessage());
                break;
            } catch (IOException e) {
                if (isRunning) System.err.println("[Server] Accept error: " + e.getMessage());
            }
        }
        System.out.println("[AcceptThread] Exiting");
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private static void shutdown() {
        synchronized (shutdownLock) {
            if (shutdownInitiated) return;
            shutdownInitiated = true;
            isRunning         = false;
        }

        System.out.println("[Server] Shutting down…");

        try {
            // Stop any active stream
            ConnectionManager.broadcastMessage("STOP_STREAM");
            StreamManager.stopVlc();

            // Disconnect all clients
            ConnectionManager.disconnectAll();

            // Close server socket
            if (serverSocket != null && !serverSocket.isClosed())
                serverSocket.close();

            // Unregister mDNS
            if (jmdns != null) {
                jmdns.unregisterAllServices();
                jmdns.close();
            }

            System.out.println("[Server] Shutdown complete");

        } catch (Exception e) {
            System.err.println("[Server] Error during shutdown: " + e.getMessage());
        } finally {
            synchronized (shutdownLock) { shutdownLock.notifyAll(); }
        }
    }
}
