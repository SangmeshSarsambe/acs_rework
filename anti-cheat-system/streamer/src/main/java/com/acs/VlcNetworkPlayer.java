package com.acs;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

public class VlcNetworkPlayer {

    private JFrame frame;
    private MediaPlayerFactory factory;
    private EmbeddedMediaPlayer mediaPlayer;
    private Canvas canvas;

    // ── Singleton instance held by the server ─────────────────────────────────
    private static VlcNetworkPlayer instance = null;

    // ─────────────────────────────────────────────────────────────────────────
    // Programmatic API used by SimpleServer
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts the VLC player window receiving a UDP stream on ip:port.
     * Safe to call from any thread — Swing work is dispatched via invokeLater.
     * If a player is already open it is stopped first.
     */
    public static void launch(String ip, int port) {
        // Stop any existing player first
        stop();

        String vlcPath = detectVlcPath();
        System.out.println("[VLC] VLC path: " + vlcPath);
        System.setProperty("vlcj.lib",         vlcPath);
        System.setProperty("jna.library.path",  vlcPath);

        // udp://@ = bind/listen on this address
        String streamUrl = "udp://@" + ip + ":" + port;
        System.out.println("[VLC] Opening stream: " + streamUrl);

        SwingUtilities.invokeLater(() -> {
            instance = new VlcNetworkPlayer(ip, port);
            instance.start(streamUrl);
        });
    }

    /**
     * Stops the VLC player and disposes the window.
     * Safe to call from any thread.
     */
    public static void stop() {
        if (instance != null) {
            SwingUtilities.invokeLater(() -> {
                try {
                    if (instance.mediaPlayer != null) {
                        instance.mediaPlayer.controls().stop();
                        instance.mediaPlayer.release();
                    }
                    if (instance.factory != null) {
                        instance.factory.release();
                    }
                    if (instance.frame != null) {
                        instance.frame.dispose();
                    }
                    System.out.println("[VLC] ✅ Player stopped and window closed");
                } catch (Exception e) {
                    System.err.println("[VLC] Error stopping player: " + e.getMessage());
                } finally {
                    instance = null;
                }
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Instance construction
    // ─────────────────────────────────────────────────────────────────────────

    private VlcNetworkPlayer(String ip, int port) {
        frame = new JFrame("ACS Stream — " + ip + ":" + port);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1280, 720);

        canvas = new Canvas();
        canvas.setBackground(Color.BLACK);
        frame.add(canvas);

        factory     = new MediaPlayerFactory();
        mediaPlayer = factory.mediaPlayers().newEmbeddedMediaPlayer();
        mediaPlayer.videoSurface().set(factory.videoSurfaces().newVideoSurface(canvas));

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // User closed the window manually — clean up
                try {
                    mediaPlayer.release();
                    factory.release();
                } catch (Exception ex) {
                    System.err.println("[VLC] Error on window close: " + ex.getMessage());
                } finally {
                    instance = null;
                }
            }
        });
    }

    private void start(String streamUrl) {
        frame.setVisible(true);
        mediaPlayer.media().play(streamUrl, ":network-caching=100");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VLC path detection
    // ─────────────────────────────────────────────────────────────────────────

    private static String detectVlcPath() {
        File f = new File("vlc");
        if (f.exists() && f.isDirectory()) return f.getAbsolutePath();
        f = new File("../vlc");
        if (f.exists() && f.isDirectory()) return f.getAbsolutePath();
        f = new File("C:\\Program Files\\VideoLAN\\VLC");
        if (f.exists() && f.isDirectory()) return f.getAbsolutePath();
        f = new File("C:\\Program Files (x86)\\VideoLAN\\VLC");
        if (f.exists() && f.isDirectory()) return f.getAbsolutePath();
        return "C:\\Program Files\\VideoLAN\\VLC";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Standalone main — requires ip and port as arguments, no hardcoded values
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Usage: java VlcNetworkPlayer <ip> <port>
     * Both arguments are required — no defaults, no hardcoding.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java VlcNetworkPlayer <ip> <port>");
            System.err.println("Example: java VlcNetworkPlayer 192.168.1.10 54321");
            System.exit(1);
        }

        String streamIp  = args[0];
        int    streamPort;

        try {
            streamPort = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("[VLC] Invalid port: " + args[1]);
            System.exit(1);
            return;
        }

        System.out.println("[VLC] Launching player → udp://@" + streamIp + ":" + streamPort);
        launch(streamIp, streamPort);
    }
}