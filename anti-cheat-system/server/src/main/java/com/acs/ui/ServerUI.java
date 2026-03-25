package com.acs.ui;

import com.acs.*;
import com.acs.ui.KeywordDialog;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Main server dashboard.
 *
 * Cards stay visible when clients disconnect — showing DISCONNECTED state
 * with timestamp. Operator can click the card to view history or remove it.
 * If the same IP reconnects, the card updates back to CONNECTED automatically.
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
    private JButton startMonitorBtn;
    private JButton stopMonitorBtn;

    // cardMap keyed by clientId (ip:port) for connected,
    // and by ip for disconnected (since port changes on reconnect)
    private final Map<String, ClientCard> cardMap = new HashMap<>();

    private ClientDetailDialog currentDialog = null;

    // ─────────────────────────────────────────────────────────────────────────

    public ServerUI(String serverIp, int serverPort, boolean restoredMonitoring) {
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

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { confirmShutdown(); }
        });

        ConnectionManager.setListener(this);

        // Apply monitoring state
        applyMonitoringState(ActivityState.isMonitoring());

        if (restoredMonitoring) {
            setStatus("⚠ Session restored after crash — monitoring is active");
        }

        StreamManager.setOnStreamChanged(() ->
                SwingUtilities.invokeLater(this::refreshStreamingStates));

        // Wire alert callback — fires on EDT via invokeLater
        AlertManager.setOnAlert((clientIp, alertType) ->
                SwingUtilities.invokeLater(() -> handleAlert(clientIp, alertType)));
    }

    // ── Single dialog management ──────────────────────────────────────────────

    public void openClientDialog(ClientHandler handler, ClientCard card) {
        if (currentDialog != null && currentDialog.isVisible()) {
            currentDialog.closeQuietly();
        }
        currentDialog = new ClientDetailDialog(this, handler, serverIp, serverPort, card);
        currentDialog.setVisible(true);
    }

    public void onDialogClosed() {
        currentDialog = null;
    }

    // ── Card state ────────────────────────────────────────────────────────────

    public void refreshStreamingStates() {
        ClientHandler activeClient = StreamManager.getActiveStreamClient();
        for (Map.Entry<String, ClientCard> entry : cardMap.entrySet()) {
            ClientHandler h = ConnectionManager.getClient(entry.getKey());
            boolean isActive = h != null && h == activeClient;
            entry.getValue().setStreaming(isActive);
        }
        if (currentDialog != null && currentDialog.isVisible()) {
            boolean isStreaming = StreamManager.getActiveStreamClient() != null
                    && currentDialog.getClientId().equals(
                    StreamManager.getActiveStreamClient().getClientId());
            currentDialog.updateStreamingState(isStreaming);
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(PANEL_BG);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_C),
                BorderFactory.createEmptyBorder(0, 20, 0, 20)));
        header.setPreferredSize(new Dimension(0, 68));

        JPanel brand = new JPanel();
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setBackground(PANEL_BG);
        JLabel logo = new JLabel("⬡ ACS");
        logo.setFont(new Font("Consolas", Font.BOLD, 22));
        logo.setForeground(ACCENT);
        JLabel tagline = new JLabel("Anti Cheat System");
        tagline.setFont(new Font("Consolas", Font.PLAIN, 11));
        tagline.setForeground(TEXT_DIM);
        brand.add(logo);
        brand.add(Box.createVerticalStrut(2));
        brand.add(tagline);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setBackground(PANEL_BG);
        left.add(brand);

        JPanel centre = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        centre.setBackground(PANEL_BG);
        centre.add(infoChip("SERVER IP", serverIp));
        centre.add(infoChip("PORT",      String.valueOf(serverPort)));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setBackground(PANEL_BG);
        clientCountLabel = new JLabel("0 CLIENTS");
        clientCountLabel.setFont(new Font("Consolas", Font.BOLD, 14));
        clientCountLabel.setForeground(TEXT_DIM);
        right.add(clientCountLabel);

        header.add(left,   BorderLayout.WEST);
        header.add(centre, BorderLayout.CENTER);
        header.add(right,  BorderLayout.EAST);
        return header;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        bar.setBackground(new Color(14, 19, 34));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_C));

        startMonitorBtn = toolbarButton("▶  Start Monitoring", COLOR_GREEN);
        stopMonitorBtn  = toolbarButton("⏹  Stop Monitoring",  COLOR_RED);
        startMonitorBtn.addActionListener(e -> doStartMonitoringAll());
        stopMonitorBtn.addActionListener (e -> doStopMonitoringAll());
        bar.add(startMonitorBtn);
        bar.add(stopMonitorBtn);

        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 22));
        sep.setForeground(BORDER_C);
        bar.add(sep);

        JButton disconnectAllBtn = toolbarButton("⊗  Disconnect All", new Color(255, 160, 0));
        disconnectAllBtn.addActionListener(e -> {
            int r = JOptionPane.showConfirmDialog(this,
                    "Disconnect all clients?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) {
                if (ActivityState.isMonitoring()) doStopMonitoringAll();
                ConnectionManager.disconnectAll();
                ConnectionManager.getDisconnectedClients().keySet()
                        .forEach(ConnectionManager::removeDisconnectedClient);
                setStatus("All clients disconnected");
            }
        });
        bar.add(disconnectAllBtn);

        JSeparator sep2 = new JSeparator(JSeparator.VERTICAL);
        sep2.setPreferredSize(new Dimension(1, 22));
        sep2.setForeground(BORDER_C);
        bar.add(sep2);

        JButton keywordsBtn = toolbarButton("⚙  Keywords", new Color(0, 200, 255));
        keywordsBtn.addActionListener(e -> new KeywordDialog(this).setVisible(true));
        bar.add(keywordsBtn);

        return bar;
    }

    private JPanel buildCenter() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG);
        wrapper.add(buildToolbar(),          BorderLayout.NORTH);
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

    // ── Monitoring actions ────────────────────────────────────────────────────

    private void doStartMonitoringAll() {
        if (ConnectionManager.getClientCount() == 0) {
            setStatus("No clients connected"); return;
        }
        ConnectionManager.broadcastMessage("START_ACTIVITY");
        ConnectionManager.broadcastMessage("START_KEYLOG");
        ConnectionManager.broadcastMessage("START_USB");
        ActivityState.setMonitoring(true);
        applyMonitoringState(true);
        for (ClientCard card : cardMap.values()) {
            if (!card.isDisconnected()) card.setMonitoring(true);
        }
        SessionManager.startSession();
        setStatus("Activity monitoring started — session: " + SessionManager.getSessionStart());
    }

    private void doStopMonitoringAll() {
        ConnectionManager.broadcastMessage("STOP_ACTIVITY");
        ConnectionManager.broadcastMessage("STOP_KEYLOG");
        ConnectionManager.broadcastMessage("STOP_USB");
        ActivityState.setMonitoring(false);
        applyMonitoringState(false);

        // Clear all cards — monitoring dot, alerts
        for (ClientCard card : cardMap.values()) {
            card.setMonitoring(false);
            card.setKeywordAlert(false);
            card.setUsbAlert(false);
        }

        // Clear in-memory logs + alerts for all clients
        for (ClientHandler h : ConnectionManager.getAllClientsForDisplay().values()) {
            h.clearActivityLog();
            h.clearKeylogLog();
            h.clearUsbLog();
            AlertManager.resetAlerts(h.getClientIp());
        }

        // Notify open dialog to clear its feeds
        if (currentDialog != null && currentDialog.isVisible()) {
            currentDialog.clearFeeds();
        }

        SessionManager.stopSession();
        setStatus("Activity monitoring stopped — logs saved");
    }

    private void applyMonitoringState(boolean active) {
        startMonitorBtn.setEnabled(!active);
        stopMonitorBtn.setEnabled(active);
    }

    // ── Alert handling ────────────────────────────────────────────────────────

    private void handleAlert(String clientIp, String alertType) {
        for (Map.Entry<String, ClientCard> entry : cardMap.entrySet()) {
            if (entry.getValue().getHandler().getClientIp().equals(clientIp)) {
                ClientCard card = entry.getValue();
                if ("KEYWORD".equals(alertType)) {
                    card.setKeywordAlert(true);
                    setStatus("⚠ Keyword alert: " + clientIp);
                } else if ("USB".equals(alertType)) {
                    card.setUsbAlert(true);
                    setStatus("● USB alert: " + clientIp);
                }
                return;
            }
        }
    }

    /**
     * Called from ClientDetailDialog when operator clicks ↺ Reset Alerts.
     * Clears alert visuals on the card for that specific client.
     */
    public void resetCardAlerts(String clientIp) {
        for (Map.Entry<String, ClientCard> entry : cardMap.entrySet()) {
            if (entry.getValue().getHandler().getClientIp().equals(clientIp)) {
                entry.getValue().setKeywordAlert(false);
                entry.getValue().setUsbAlert(false);
                setStatus("Alerts reset: " + clientIp);
                return;
            }
        }
    }

    // ── Grid management ───────────────────────────────────────────────────────

    private void refreshGrid() {
        clientGrid.removeAll();
        cardMap.clear();

        Map<String, ClientHandler> allClients =
                ConnectionManager.getAllClientsForDisplay();

        if (allClients.isEmpty()) {
            showEmptyState();
        } else {
            if (!(clientGrid.getLayout() instanceof WrapLayout)) {
                clientGrid.setLayout(new WrapLayout(FlowLayout.LEFT, 18, 18));
            }
            ClientHandler activeStream = StreamManager.getActiveStreamClient();
            boolean       monitoring   = ActivityState.isMonitoring();

            for (Map.Entry<String, ClientHandler> entry : allClients.entrySet()) {
                ClientHandler handler = entry.getValue();
                ClientCard    card    = new ClientCard(
                        handler, serverIp, serverPort, this);

                if (handler.isDisconnected()) {
                    card.setDisconnected(true);
                } else {
                    card.setStreaming(handler == activeStream);
                    card.setMonitoring(monitoring);
                }

                // Restore alert state from AlertManager
                card.setKeywordAlert(AlertManager.hasKeywordAlert(handler.getClientIp()));
                card.setUsbAlert(AlertManager.hasUsbAlert(handler.getClientIp()));

                cardMap.put(entry.getKey(), card);
                clientGrid.add(card);
            }
        }

        updateClientCountLabel();
        clientGrid.revalidate();
        clientGrid.repaint();
    }

    private void updateClientCountLabel() {
        int connected    = ConnectionManager.getClientCount();
        int disconnected = ConnectionManager.getDisconnectedClients().size();
        String label = connected + " CONNECTED";
        if (disconnected > 0) label += "  |  " + disconnected + " DISCONNECTED";
        clientCountLabel.setText(label);
        clientCountLabel.setForeground(connected > 0 ? COLOR_GREEN : TEXT_DIM);
    }

    private void showEmptyState() {
        clientGrid.setLayout(new GridBagLayout());
        JLabel empty = new JLabel("Waiting for client connections…");
        empty.setFont(new Font("Consolas", Font.PLAIN, 14));
        empty.setForeground(new Color(60, 80, 120));
        clientGrid.add(empty);
    }

    // ── ConnectionListener ────────────────────────────────────────────────────

    @Override
    public void onClientConnected(String clientId) {
        SwingUtilities.invokeLater(() -> {
            refreshGrid();
            setStatus("Client connected: " + clientId);
            if (ActivityState.isMonitoring()) {
                ClientHandler h = ConnectionManager.getClient(clientId);
                if (h != null) {
                    h.sendMessage("START_ACTIVITY");
                    h.sendMessage("START_KEYLOG");
                    h.sendMessage("START_USB");
                }
            }
        });
    }

    @Override
    public void onClientDisconnected(String clientId) {
        SwingUtilities.invokeLater(() -> {
            // Update card to disconnected state — don't remove it
            ClientCard card = cardMap.get(clientId);
            if (card != null) {
                card.setDisconnected(true);
            }
            // Close dialog quietly if it was open for this client
            if (currentDialog != null && currentDialog.isVisible()
                    && currentDialog.getClientId().equals(clientId)) {
                currentDialog.updateDisconnectedState(
                        ConnectionManager.getDisconnectedClients()
                                .get(card != null ? card.getHandler().getClientIp() : ""));
            }
            updateClientCountLabel();
            setStatus("Client disconnected: " + clientId);
        });
    }

    @Override
    public void onDisconnectedClientRemoved(String clientIp) {
        SwingUtilities.invokeLater(() -> {
            // Find and remove the card for this IP
            String keyToRemove = null;
            for (Map.Entry<String, ClientCard> entry : cardMap.entrySet()) {
                if (entry.getValue().getHandler().getClientIp().equals(clientIp)) {
                    keyToRemove = entry.getKey();
                    break;
                }
            }
            if (keyToRemove != null) {
                ClientCard card = cardMap.remove(keyToRemove);
                clientGrid.remove(card);
                clientGrid.revalidate();
                clientGrid.repaint();
            }
            updateClientCountLabel();
            setStatus("Removed disconnected client: " + clientIp);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public void setStatus(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText("● " + message));
    }

    private void confirmShutdown() {
        int r = JOptionPane.showConfirmDialog(this,
                "Shutdown the ACS server?\nAll clients will be disconnected.",
                "Confirm Shutdown", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION) System.exit(0);
    }

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

    private static void applyDarkDefaults() {
        System.setProperty("awt.useSystemAAFontSettings", "lcd");
        System.setProperty("swing.aatext", "true");
        UIManager.put("OptionPane.background",        PANEL_BG);
        UIManager.put("Panel.background",             PANEL_BG);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Button.background",            new Color(28, 38, 65));
        UIManager.put("Button.foreground",            TEXT);
        UIManager.put("Button.border",                BorderFactory.createLineBorder(BORDER_C));
        UIManager.put("ScrollBar.background",         BG);
        UIManager.put("ScrollBar.thumb",              BORDER_C);
        UIManager.put("ScrollBar.track",              BG);
    }
}