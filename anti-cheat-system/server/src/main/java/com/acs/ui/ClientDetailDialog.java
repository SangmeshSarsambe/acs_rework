package com.acs.ui;
import com.acs.*;   // pulls in ClientHandler, ConnectionManager, StreamManager etc.

import javax.swing.*;
//import javax.swing.border.*;
import java.awt.*;
//import java.awt.event.*;

/**
 * Modal-less dialog that opens when you click a ClientCard.
 * Shows client info and controls for that one client.
 *
 * ── Adding a new feature ────────────────────────────────────────────────────
 *   1. Add a button in buildActionPanel()
 *   2. In its ActionListener: call handler.sendMessage("YOUR_COMMAND")
 *      or open a new dialog: new YourFeatureDialog(this, handler).setVisible(true)
 *   That is literally all that is needed on the server side.
 * ────────────────────────────────────────────────────────────────────────────
 */
public class ClientDetailDialog extends JDialog {

    // ── Palette ───────────────────────────────────────────────────────────────
    private static final Color BG          = new Color(12,  17,  30);
    private static final Color PANEL_BG    = new Color(18,  25,  42);
    private static final Color BORDER_C    = new Color(35,  50,  85);
    private static final Color ACCENT      = new Color(0,  200, 255);
    private static final Color TEXT        = new Color(220, 230, 255);
    private static final Color TEXT_DIM    = new Color(110, 135, 175);
    private static final Color COLOR_GREEN = new Color(0,  210, 110);
    private static final Color COLOR_RED   = new Color(255,  68, 102);
    private static final Color COLOR_CYAN  = new Color(0,  200, 255);

    // ── State ─────────────────────────────────────────────────────────────────
    private final ClientHandler handler;
    private final String        serverIp;
    private final int           serverPort;
    private final ClientCard    card;       // back-reference to update its icon

    private JLabel  streamStatusLabel;
    private JButton startBtn;
    private JButton stopBtn;

    // ─────────────────────────────────────────────────────────────────────────

    public ClientDetailDialog(Window parent,
                               ClientHandler handler,
                               String serverIp,
                               int serverPort,
                               ClientCard card) {

        // MODELESS — you can open multiple client windows simultaneously
        super(parent, "Client — " + handler.getClientId(), ModalityType.MODELESS);

        this.handler    = handler;
        this.serverIp   = serverIp;
        this.serverPort = serverPort;
        this.card       = card;

        setSize(620, 370);
        setType(Window.Type.UTILITY);
        setResizable(false);
        setLocationRelativeTo(parent);

        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(0, 0));

        add(buildHeader(),      BorderLayout.NORTH);
        add(buildInfoPanel(),   BorderLayout.CENTER);
        add(buildFooter(),      BorderLayout.SOUTH);

        // Sync button state with card
        applyStreamingState(card.isStreaming());
    }

    // ── UI sections ───────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(PANEL_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_C),
                BorderFactory.createEmptyBorder(14, 22, 14, 22)));

        JLabel title = new JLabel("⬡ " + handler.getClientId());
        title.setFont(new Font("Consolas", Font.BOLD, 15));
        title.setForeground(ACCENT);

        JLabel sub = new JLabel("Client Control Panel");
        sub.setFont(new Font("Consolas", Font.PLAIN, 10));
        sub.setForeground(TEXT_DIM);

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBackground(PANEL_BG);
        left.add(title);
        left.add(Box.createVerticalStrut(3));
        left.add(sub);

        panel.add(left, BorderLayout.WEST);
        return panel;
    }

    private JPanel buildInfoPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 24, 12, 24));

        // ── Info rows ──────────────────────────────────────────────────────
        String[] idParts = handler.getClientId().split(":", 2);
        panel.add(infoRow("IP Address",   idParts[0]));
        panel.add(Box.createVerticalStrut(8));
        panel.add(infoRow("Port",         idParts.length > 1 ? idParts[1] : "—"));
        panel.add(Box.createVerticalStrut(8));
        panel.add(infoRow("Connection",   handler.isClientAlive() ? "Active" : "Disconnected"));
        panel.add(Box.createVerticalStrut(8));

        // ── Stream status row ──────────────────────────────────────────────
        JPanel statusRow = horizontalRow();
        JLabel statusKey = dimLabel("Stream Status");

        streamStatusLabel = new JLabel("● Idle");
        streamStatusLabel.setFont(new Font("Consolas", Font.BOLD, 11));
        streamStatusLabel.setForeground(COLOR_GREEN);

        statusRow.add(statusKey,          BorderLayout.WEST);
        statusRow.add(streamStatusLabel,  BorderLayout.EAST);
        panel.add(statusRow);

        panel.add(Box.createVerticalStrut(22));

        // ── Action buttons ─────────────────────────────────────────────────
        panel.add(buildActionPanel());

        return panel;
    }

    /**
     * All stream (and future) action buttons live here.
     * Adding a new button = add it to this GridLayout and wire it up.
     */
    private JPanel buildActionPanel() {
    JPanel panel = new JPanel(new GridLayout(1, 3, 12, 0));  // ← 2 to 3
    panel.setBackground(BG);
    panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

    startBtn = actionButton("▶  Start Stream", COLOR_GREEN, new Color(0, 28, 14));
    stopBtn  = actionButton("⏹  Stop Stream",  COLOR_RED,   new Color(38, 10, 15));
    JButton disconnectBtn = actionButton("⊗  Disconnect", new Color(255, 160, 0), new Color(38, 25, 0));

    startBtn.addActionListener(e -> doStartStream());
    stopBtn.addActionListener (e -> doStopStream());
    disconnectBtn.addActionListener(e -> doDisconnect());  // ← add this

    panel.add(startBtn);
    panel.add(stopBtn);
    panel.add(disconnectBtn);  // ← add this
    return panel;
}

    private JPanel buildFooter() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 8));
        panel.setBackground(PANEL_BG);
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_C));

        JButton closeBtn = new JButton("Close");
        closeBtn.setFont(new Font("Consolas", Font.PLAIN, 11));
        closeBtn.setForeground(TEXT_DIM);
        closeBtn.setBackground(new Color(28, 38, 65));
        closeBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_C),
                BorderFactory.createEmptyBorder(5, 18, 5, 18)));
        closeBtn.setFocusPainted(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> dispose());

        panel.add(closeBtn);
        return panel;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void doStartStream() {
        if (!handler.isClientAlive()) {
            showClientGone();
            return;
        }
        StreamManager.startStream(serverIp, serverPort, handler);
        applyStreamingState(true);
        card.setStreaming(true);
    }

    private void doStopStream() {
        if (handler.isClientAlive()) {
            StreamManager.stopStream(handler);
        } else {
            // Client gone — still kill VLC on server side
            StreamManager.stopVlc();
        }
        applyStreamingState(false);
        card.setStreaming(false);
    }

    private void doDisconnect() {
    int r = JOptionPane.showConfirmDialog(
            this,
            "Disconnect client " + handler.getClientId() + "?",
            "Confirm Disconnect",
            JOptionPane.YES_NO_OPTION);
    if (r == JOptionPane.YES_OPTION) {
        StreamManager.stopStream(handler);   // stop stream first if running
        handler.disconnect();                // then kick the client
        dispose();                           // close this dialog
    }
}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyStreamingState(boolean streaming) {
        startBtn.setEnabled(!streaming);
        stopBtn.setEnabled(streaming);
        if (streaming) {
            streamStatusLabel.setText("● Streaming");
            streamStatusLabel.setForeground(COLOR_CYAN);
        } else {
            streamStatusLabel.setText("● Idle");
            streamStatusLabel.setForeground(COLOR_GREEN);
        }
    }

    private void showClientGone() {
        JOptionPane.showMessageDialog(
                this,
                "Client " + handler.getClientId() + " is no longer connected.",
                "Client Disconnected",
                JOptionPane.WARNING_MESSAGE);
        dispose();
    }

    // ── Widget builders ───────────────────────────────────────────────────────

    private JPanel infoRow(String key, String value) {
        JPanel row = horizontalRow();
        row.add(dimLabel(key),   BorderLayout.WEST);
        JLabel v = new JLabel(value);
        v.setFont(new Font("Consolas", Font.BOLD, 11));
        v.setForeground(TEXT);
        row.add(v, BorderLayout.EAST);
        return row;
    }

    private JPanel horizontalRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(BG);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        return row;
    }

    private JLabel dimLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Consolas", Font.PLAIN, 11));
        l.setForeground(TEXT_DIM);
        return l;
    }

    private JButton actionButton(String text, Color fg, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Consolas", Font.BOLD, 12));
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(fg.darker(), 1),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }
}
