package com.acs.ui;
import com.acs.*;   // pulls in ClientHandler, ConnectionManager, StreamManager etc.

import javax.swing.*;
//import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Main server dashboard window.
 *
 * Implements ConnectionListener so it auto-updates whenever a client
 * connects or disconnects — no polling, no manual refresh needed.
 *
 * ── Adding a new server-wide action ─────────────────────────────────────────
 *   Add a button to buildToolbar() and call whatever you need.
 *   E.g.: "Broadcast" button → ConnectionManager.broadcastMessage(...)
 * ────────────────────────────────────────────────────────────────────────────
 */
public class ServerUI extends JFrame implements ConnectionListener {

    // ── Palette ───────────────────────────────────────────────────────────────
    static final Color BG          = new Color(10,  14,  26);
    static final Color PANEL_BG    = new Color(16,  22,  38);
    static final Color BORDER_C    = new Color(32,  46,  80);
    static final Color ACCENT      = new Color(0,  200, 255);
    static final Color TEXT        = new Color(220, 230, 255);
    static final Color TEXT_DIM    = new Color(110, 135, 175);
    static final Color COLOR_GREEN = new Color(0,  210, 110);
    static final Color COLOR_RED   = new Color(255,  68, 102);

    // ── State ─────────────────────────────────────────────────────────────────
    private final String serverIp;
    private final int    serverPort;

    private JPanel  clientGrid;
    private JLabel  clientCountLabel;
    private JLabel  statusLabel;

    // Tracks which card belongs to which clientId so we can remove/update them
    private final Map<String, ClientCard> cardMap = new HashMap<>();

    // ─────────────────────────────────────────────────────────────────────────

    public ServerUI(String serverIp, int serverPort) {
        this.serverIp   = serverIp;
        this.serverPort = serverPort;

        applyDarkDefaults();

        setTitle("ACS — Anti Cheat System Server");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1050, 680);
        setMinimumSize(new Dimension(720, 500));
        setLocationRelativeTo(null);

        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(0, 0));

        add(buildHeader(),  BorderLayout.NORTH);
        add(buildCenter(),  BorderLayout.CENTER);
        add(buildFooter(),  BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmShutdown();
            }
        });

        // Register as listener — ConnectionManager will call us on every connect/disconnect
        ConnectionManager.setListener(this);
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(PANEL_BG);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_C),
                BorderFactory.createEmptyBorder(0, 20, 0, 20)));
        header.setPreferredSize(new Dimension(0, 68));

        // Left: branding
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setBackground(PANEL_BG);

        JLabel logo = new JLabel("⬡ ACS");
        logo.setFont(new Font("Consolas", Font.BOLD, 22));
        logo.setForeground(ACCENT);

        JLabel tagline = new JLabel("  Anti Cheat System");
        tagline.setFont(new Font("Consolas", Font.PLAIN, 11));
        tagline.setForeground(TEXT_DIM);

        JPanel brand = new JPanel();
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setBackground(PANEL_BG);
        brand.add(logo);
        brand.add(tagline);

        left.add(brand);

        // Centre: server info chips
        JPanel centre = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        centre.setBackground(PANEL_BG);
        centre.add(infoChip("SERVER IP",   serverIp));
        centre.add(infoChip("PORT",        String.valueOf(serverPort)));

        // Right: live client count
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setBackground(PANEL_BG);

        clientCountLabel = new JLabel("0 CLIENTS");
        clientCountLabel.setFont(new Font("Consolas", Font.BOLD, 14));
        clientCountLabel.setForeground(COLOR_GREEN);
        right.add(clientCountLabel);

        header.add(left,   BorderLayout.WEST);
        header.add(centre, BorderLayout.CENTER);
        header.add(right,  BorderLayout.EAST);
        return header;
    }

    /** Optional toolbar row — add server-wide buttons here */
    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        bar.setBackground(new Color(14, 19, 34));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_C));

        JButton disconnectAllBtn = toolbarButton("⊗  Disconnect All", COLOR_RED);
        disconnectAllBtn.addActionListener(e -> {
            int r = JOptionPane.showConfirmDialog(this,
                    "Disconnect all clients?", "Confirm",
                    JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) {
                ConnectionManager.disconnectAll();
                setStatus("All clients disconnected");
            }
        });

        bar.add(disconnectAllBtn);
        return bar;
    }

    private JPanel buildCenter() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG);
        wrapper.add(buildToolbar(), BorderLayout.NORTH);
        wrapper.add(buildClientScrollArea(), BorderLayout.CENTER);
        return wrapper;
    }

    private JScrollPane buildClientScrollArea() {
        clientGrid = new JPanel(new WrapLayout(FlowLayout.LEFT, 18, 18));
        clientGrid.setBackground(BG);
        clientGrid.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        showEmptyState();

        JScrollPane scroll = new JScrollPane(clientGrid);
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        // Hide default scrollbar UI border
        scroll.getVerticalScrollBar().setBackground(BG);
        return scroll;
    }

    private JPanel buildFooter() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(10, 14, 24));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_C),
                BorderFactory.createEmptyBorder(0, 14, 0, 14)));
        bar.setPreferredSize(new Dimension(0, 28));

        statusLabel = new JLabel("● Server running");
        statusLabel.setFont(new Font("Consolas", Font.PLAIN, 11));
        statusLabel.setForeground(COLOR_GREEN);

        JLabel ver = new JLabel("ACS v1.0");
        ver.setFont(new Font("Consolas", Font.PLAIN, 11));
        ver.setForeground(TEXT_DIM);

        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(ver,         BorderLayout.EAST);
        return bar;
    }

    // ── Grid management ───────────────────────────────────────────────────────

    /**
     * Full rebuild of the card grid from the current ConnectionManager state.
     * Must only be called on the EDT (always dispatch via SwingUtilities.invokeLater).
     */
    private void refreshGrid() {
        clientGrid.removeAll();
        cardMap.clear();

        Map<String, ClientHandler> clients = ConnectionManager.getAllClients();

        if (clients.isEmpty()) {
            showEmptyState();
        } else {
            for (Map.Entry<String, ClientHandler> entry : clients.entrySet()) {
                ClientCard card = new ClientCard(entry.getValue(), serverIp, serverPort, this);
                cardMap.put(entry.getKey(), card);
                clientGrid.add(card);
            }
        }

        int count = clients.size();
        clientCountLabel.setText(count + (count == 1 ? " CLIENT" : " CLIENTS"));
        clientCountLabel.setForeground(count > 0 ? COLOR_GREEN : TEXT_DIM);

        clientGrid.revalidate();
        clientGrid.repaint();
    }

    private void showEmptyState() {
        clientGrid.setLayout(new GridBagLayout()); // centre the label
        JLabel empty = new JLabel("Waiting for client connections…");
        empty.setFont(new Font("Consolas", Font.PLAIN, 14));
        empty.setForeground(new Color(60, 80, 120));
        clientGrid.add(empty);
    }

    // ── ConnectionListener callbacks ──────────────────────────────────────────

    @Override
    public void onClientConnected(String clientId) {
        SwingUtilities.invokeLater(() -> {
            // Switch grid back to WrapLayout if it was showing the empty state
            if (!(clientGrid.getLayout() instanceof WrapLayout)) {
                clientGrid.setLayout(new WrapLayout(FlowLayout.LEFT, 18, 18));
            }
            refreshGrid();
            setStatus("Client connected: " + clientId);
        });
    }

    @Override
    public void onClientDisconnected(String clientId) {
        SwingUtilities.invokeLater(() -> {
            refreshGrid();
            setStatus("Client disconnected: " + clientId);
        });
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    public void setStatus(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText("● " + message));
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private void confirmShutdown() {
        int r = JOptionPane.showConfirmDialog(
                this,
                "Shutdown the ACS server?\nAll clients will be disconnected.",
                "Confirm Shutdown",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) {
            System.exit(0); // triggers the shutdown hook registered in SimpleServer
        }
    }

    // ── Widget builders ───────────────────────────────────────────────────────

    private JPanel infoChip(String label, String value) {
        JPanel chip = new JPanel(new GridLayout(2, 1, 0, 1));
        chip.setBackground(new Color(24, 34, 60));
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_C),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));

        JLabel l = new JLabel(label);
        l.setFont(new Font("Consolas", Font.PLAIN, 8));
        l.setForeground(TEXT_DIM);

        JLabel v = new JLabel(value);
        v.setFont(new Font("Consolas", Font.BOLD, 12));
        v.setForeground(TEXT);

        chip.add(l);
        chip.add(v);
        return chip;
    }

    private JButton toolbarButton(String text, Color fg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Consolas", Font.PLAIN, 11));
        btn.setForeground(fg);
        btn.setBackground(new Color(22, 30, 52));
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(fg.darker()),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    // ── UIManager dark defaults ───────────────────────────────────────────────

    private static void applyDarkDefaults() {
        UIManager.put("OptionPane.background",          PANEL_BG);
        UIManager.put("Panel.background",               PANEL_BG);
        UIManager.put("OptionPane.messageForeground",   TEXT);
        UIManager.put("Button.background",              new Color(28, 38, 65));
        UIManager.put("Button.foreground",              TEXT);
        UIManager.put("Button.border",                  BorderFactory.createLineBorder(BORDER_C));
        UIManager.put("ScrollBar.background",           BG);
        UIManager.put("ScrollBar.thumb",                BORDER_C);
        UIManager.put("ScrollBar.track",                BG);
    }
}
