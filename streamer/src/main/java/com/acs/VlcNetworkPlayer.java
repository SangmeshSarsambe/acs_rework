package com.acs;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurface;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Launches a vlcj-based stream viewer window.
 * Called as a separate JVM process by StreamManager.
 *
 * Window contains:
 *   - vlcj embedded video canvas (fills the window)
 *   - Stop button at the bottom — clicking it closes the window
 *     and kills this process, which StreamManager's watchdog detects
 *
 * Usage: java com.acs.VlcNetworkPlayer <serverIp> <port>
 */
public class VlcNetworkPlayer {

    // Colours matching the server dashboard palette
    private static final Color BG        = new Color(10,  14,  26);
    private static final Color BTN_BG    = new Color(38,  10,  15);
    private static final Color BTN_FG    = new Color(255,  68, 102);
    private static final Color TOOLBAR   = new Color(14,  19,  34);
    private static final Color BORDER_C  = new Color(32,  46,  80);

    // ── Scaling — reads the same file the server UIScale writes ───────────
    private static float scaleFactor = 1.0f;
    static {
        try {
            java.io.File f = new java.io.File("ui_scale.properties");
            if (f.exists()) {
                java.util.Properties p = new java.util.Properties();
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    p.load(in);
                }
                scaleFactor = Float.parseFloat(p.getProperty("scaleFactor", "1.0"));
                scaleFactor = Math.max(0.5f, Math.min(3.0f, scaleFactor));
            }
        } catch (Exception ignored) {}
    }
    private static int s(int base) { return Math.round(base * scaleFactor); }

    /**
     * Finds the VLC installation directory containing libvlc.
     * Checks bundled ./vlc/ folder first, then common system install paths.
     * Returns null if not found — vlcj will then try the system PATH.
     */
    private static String findVlcLibPath() {
        String os = System.getProperty("os.name").toLowerCase();

        String[] candidates;
        if (os.contains("win")) {
            candidates = new String[]{
                "./vlc",
                "../vlc",
                "C:/Program Files/VideoLAN/VLC",
                "C:/Program Files (x86)/VideoLAN/VLC"
            };
        } else {
            candidates = new String[]{
                "./vlc",
                "../vlc",
                "/usr/lib/x86_64-linux-gnu",
                "/usr/lib",
                "/usr/local/lib",
                "/usr/lib/vlc"
            };
        }

        for (String path : candidates) {
            java.io.File dir = new java.io.File(path);
            if (dir.exists() && dir.isDirectory()) {
                // Verify the actual lib is there
                String libName = os.contains("win") ? "libvlc.dll" : "libvlc.so";
                if (new java.io.File(dir, libName).exists()) {
                    return dir.getAbsolutePath();
                }
            }
        }
        return null;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: VlcNetworkPlayer <serverIp> <port>");
            System.exit(1);
        }

        String ip   = args[0];
        int    port = Integer.parseInt(args[1]);
        String url  = "udp://@" + ip + ":" + port;

        SwingUtilities.invokeLater(() -> launch(url, ip, port));
    }

    private static void launch(String url, String ip, int port) {
        // ── Tell vlcj where to find libvlc ────────────────────────────────────
        // vlcj needs libvlc.dll (Windows) / libvlc.so (Linux) from the VLC install.
        // We try known install paths — first one that exists wins.
        String vlcPath = findVlcLibPath();
        String pluginPath = null;
        if (vlcPath != null) {
            System.setProperty("jna.library.path", vlcPath);
            pluginPath = vlcPath + java.io.File.separator + "plugins";
            System.out.println("[VLC] Using VLC path: " + vlcPath);
        } else {
            System.out.println("[VLC] WARNING: VLC path not found — will try system PATH");
        }

        // ── Media player setup ────────────────────────────────────────────────
        MediaPlayerFactory factory;
        EmbeddedMediaPlayer player;
        
        try {
            if (pluginPath != null && new java.io.File(pluginPath).exists()) {
                factory = new MediaPlayerFactory("--plugin-path=" + pluginPath);
            } else {
                factory = new MediaPlayerFactory();
            }
            player = factory.mediaPlayers().newEmbeddedMediaPlayer();
        } catch (Throwable t) {
            t.printStackTrace();
            JOptionPane.showMessageDialog(null,
                    "Failed to initialize VLC Player.\n" +
                    "Error: " + t.getMessage() + "\n\n" +
                    "Tip: If you are using system VLC, it might be 32-bit while your Java is 64-bit.\n" +
                    "Please use the bundled local VLC folder for best compatibility.",
                    "VLC Initialization Error", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }

        // Canvas VLC renders into
        Canvas canvas = new Canvas();
        canvas.setBackground(Color.BLACK);

        VideoSurface surface = factory.videoSurfaces().newVideoSurface(canvas);
        player.videoSurface().set(surface);

        // ── Window ────────────────────────────────────────────────────────────
        JFrame frame = new JFrame("ACS Stream — " + ip + ":" + port);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(s(1280), s(750));
        frame.setMinimumSize(new Dimension(s(640), s(400)));
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(BG);
        frame.setLayout(new BorderLayout(0, 0));

        // ── Video area ────────────────────────────────────────────────────────
        JPanel videoPanel = new JPanel(new BorderLayout());
        videoPanel.setBackground(Color.BLACK);
        videoPanel.add(canvas, BorderLayout.CENTER);
        frame.add(videoPanel, BorderLayout.CENTER);

        // ── Bottom toolbar with Stop button ───────────────────────────────────
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, s(8)));
        toolbar.setBackground(TOOLBAR);
        toolbar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_C));
        toolbar.setPreferredSize(new Dimension(0, s(48)));

        JButton stopBtn = new JButton("⏹  Stop Stream");
        stopBtn.setFont(new Font("Consolas", Font.BOLD, s(12)));
        stopBtn.setForeground(BTN_FG);
        stopBtn.setBackground(BTN_BG);
        stopBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BTN_FG.darker(), 1),
                BorderFactory.createEmptyBorder(s(7), s(28), s(7), s(28))));
        stopBtn.setFocusPainted(false);
        stopBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Stop button and window X both do the same thing — clean shutdown
        Runnable shutdown = () -> {
            player.controls().stop();
            player.release();
            factory.release();
            frame.dispose();
            System.exit(0); // kills this JVM process → StreamManager watchdog fires
        };

        stopBtn.addActionListener(e -> shutdown.run());
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown.run(); }
        });

        toolbar.add(stopBtn);
        frame.add(toolbar, BorderLayout.SOUTH);

        // ── Show and play ─────────────────────────────────────────────────────
        frame.setVisible(true);

        // Small delay to let the canvas get a native peer before VLC attaches
        Timer startTimer = new Timer(300, e -> {
            player.media().play(url);
        });
        startTimer.setRepeats(false);
        startTimer.start();
    }
}