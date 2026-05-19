package com.acs.ui;

import com.acs.*;
import com.acs.ui.KeywordDialog;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.net.NetworkInterface;
import java.util.*;
import java.util.List;

/**
 * Main server dashboard.
 *
 * Cards stay visible when clients disconnect — showing DISCONNECTED state
 * with timestamp. Operator can click the card to view history or remove it.
 * If the same IP reconnects, the card updates back to CONNECTED automatically.
 */
public class ServerUI extends JFrame implements ConnectionListener {

    // ── Palette ───────────────────────────────────────────────────────────────
    static final Color BG = new Color(10, 14, 26);
    static final Color PANEL_BG = new Color(16, 22, 38);
    static final Color BORDER_C = new Color(32, 46, 80);
    static final Color ACCENT = new Color(0, 200, 255);
    static final Color TEXT = new Color(220, 230, 255);
    static final Color TEXT_DIM = new Color(110, 135, 175);
    static final Color COLOR_GREEN = new Color(0, 210, 110);
    static final Color COLOR_RED = new Color(255, 68, 102);

    // ── Scaling helpers ───────────────────────────────────────────────────────
    static int s(int v) {
        return UIScale.scale(v);
    }

    static float sf(float v) {
        return UIScale.scaleF(v);
    }

    static Font sf(String n, int style, int size) {
        return UIScale.font(n, style, size);
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final String serverIp;
    private final int serverPort;

    private JPanel clientGrid;
    private JLabel clientCountLabel;
    private JLabel statusLabel;
    private JButton monitorToggle;
    private JButton lockAllBtn;
    private JButton unlockAllBtn;
    private JPanel networkBanner;
    private JComboBox<String> scaleCombo;

    // cardMap keyed by clientId (ip:port) for connected,
    // and by ip for disconnected (since port changes on reconnect)
    private final Map<String, ClientCard> cardMap = new HashMap<>();

    private ClientDetailDialog currentDialog = null;

    // ─────────────────────────────────────────────────────────────────────────

    public ServerUI(String serverIp, int serverPort, boolean restoredMonitoring) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;

        applyDarkDefaults();

        setTitle("ACS — Anti Cheat System Server");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(s(1050), s(680));
        setMinimumSize(new Dimension(s(720), s(500)));
        setLocationRelativeTo(null);

        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(0, 0));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmShutdown();
            }
        });

        ConnectionManager.setListener(this);

        // Apply monitoring state
        applyMonitoringState(ActivityState.isMonitoring());

        if (restoredMonitoring) {
            setStatus("⚠ Session restored after crash — monitoring is active");
        }

        StreamManager.setOnStreamChanged(() -> SwingUtilities.invokeLater(this::refreshStreamingStates));

        // Wire alert callback — fires on EDT via invokeLater
        AlertManager.setOnAlert(
                (clientIp, alertType) -> SwingUtilities.invokeLater(() -> handleAlert(clientIp, alertType)));

        // ── Scale change listener — rebuild entire UI instantly ───────────────
        UIScale.addScaleChangeListener(() -> SwingUtilities.invokeLater(this::rebuildUI));
    }

    // ── Rebuild UI on scale change ────────────────────────────────────────────

    private void rebuildUI() {
        if (currentDialog != null && currentDialog.isVisible()) {
            currentDialog.closeQuietly();
        }
        getContentPane().removeAll();
        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        applyMonitoringState(ActivityState.isMonitoring());
        refreshGrid();
        setMinimumSize(new Dimension(s(720), s(500)));
        revalidate();
        repaint();
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
                BorderFactory.createEmptyBorder(0, s(20), 0, s(20))));
        header.setPreferredSize(new Dimension(0, s(68)));

        JPanel brand = new JPanel();
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setBackground(PANEL_BG);
        JLabel logo = new JLabel("⬡ ACS");
        logo.setFont(sf("Consolas", Font.BOLD, 22));
        logo.setForeground(ACCENT);
        JLabel tagline = new JLabel("Anti Cheat System");
        tagline.setFont(sf("Consolas", Font.PLAIN, 11));
        tagline.setForeground(TEXT_DIM);
        brand.add(logo);
        brand.add(Box.createVerticalStrut(s(2)));
        brand.add(tagline);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setBackground(PANEL_BG);
        left.add(brand);

        JPanel centre = new JPanel(new FlowLayout(FlowLayout.CENTER, s(10), 0));
        centre.setBackground(PANEL_BG);
        centre.add(infoChip("SERVER IP", serverIp));
        centre.add(infoChip("PORT", String.valueOf(serverPort)));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setBackground(PANEL_BG);
        clientCountLabel = new JLabel("0 CLIENTS");
        clientCountLabel.setFont(sf("Consolas", Font.BOLD, 14));
        clientCountLabel.setForeground(TEXT_DIM);
        right.add(clientCountLabel);

        header.add(left, BorderLayout.WEST);
        header.add(centre, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, s(10), s(6)));
        bar.setBackground(new Color(14, 19, 34));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_C));

        monitorToggle = toolbarToggle(
                "▶  Start Monitoring", COLOR_GREEN, new Color(0, 28, 14),
                "⏹  Stop Monitoring", COLOR_RED, new Color(38, 10, 15),
                "Start activity monitoring for all clients",
                "Stop monitoring and save session logs");
        monitorToggle.addActionListener(e -> {
            Boolean active = (Boolean) monitorToggle.getClientProperty("toggled");
            if (Boolean.TRUE.equals(active)) {
                doStopMonitoringAll();
            } else {
                doStartMonitoringAll();
            }
        });
        bar.add(monitorToggle);

        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(1, s(22)));
        sep.setForeground(BORDER_C);
        bar.add(sep);

        JButton disconnectAllBtn = toolbarButton("⊗  Disconnect All", new Color(255, 160, 0));
        disconnectAllBtn.setToolTipText("Disconnect all connected clients");
        disconnectAllBtn.addActionListener(e -> {
            int r = JOptionPane.showConfirmDialog(this,
                    "Disconnect all clients?", "Confirm", JOptionPane.YES_NO_OPTION);
            if (r == JOptionPane.YES_OPTION) {
                if (ActivityState.isMonitoring())
                    doStopMonitoringAll();
                ConnectionManager.disconnectAll();
                ConnectionManager.getDisconnectedClients().keySet()
                        .forEach(ConnectionManager::removeDisconnectedClient);
                setStatus("All clients disconnected");
            }
        });
        bar.add(disconnectAllBtn);

        JSeparator sep2 = new JSeparator(JSeparator.VERTICAL);
        sep2.setPreferredSize(new Dimension(1, s(22)));
        sep2.setForeground(BORDER_C);
        bar.add(sep2);

        JButton keywordsBtn = toolbarButton("⚙  Keywords", new Color(0, 200, 255));
        keywordsBtn.setToolTipText("Manage alert keywords");
        keywordsBtn.addActionListener(e -> new KeywordDialog(this).setVisible(true));
        bar.add(keywordsBtn);

        JSeparator sep3 = new JSeparator(JSeparator.VERTICAL);
        sep3.setPreferredSize(new Dimension(1, s(22)));
        sep3.setForeground(BORDER_C);
        bar.add(sep3);

        lockAllBtn = toolbarButton("🔒  Lock All", new Color(255, 68, 102));
        lockAllBtn.setBackground(new Color(38, 10, 15));
        lockAllBtn.setToolTipText("Lock keyboard & mouse on all clients");
        lockAllBtn.addActionListener(e -> doLockAll());
        lockAllBtn.setEnabled(false); // only during monitoring
        bar.add(lockAllBtn);

        unlockAllBtn = toolbarButton("🔓  Unlock All", COLOR_GREEN);
        unlockAllBtn.setBackground(new Color(0, 28, 14));
        unlockAllBtn.setToolTipText("Unlock keyboard & mouse on all clients");
        unlockAllBtn.addActionListener(e -> doUnlockAll());
        unlockAllBtn.setEnabled(false); // only during monitoring
        bar.add(unlockAllBtn);

        JSeparator sep4 = new JSeparator(JSeparator.VERTICAL);
        sep4.setPreferredSize(new Dimension(1, s(22)));
        sep4.setForeground(BORDER_C);
        bar.add(sep4);

        // ── Scale selector ────────────────────────────────────────────────────
        scaleCombo = new JComboBox<>(UIScale.PRESET_LABELS);
        scaleCombo.setSelectedIndex(UIScale.getCurrentPresetIndex());
        scaleCombo.setFont(sf("Consolas", Font.BOLD, 10));
        scaleCombo.setForeground(TEXT);
        scaleCombo.setBackground(new Color(22, 30, 52));
        scaleCombo.setToolTipText("UI Scale");
        scaleCombo.setFocusable(false);
        scaleCombo.setPreferredSize(new Dimension(s(72), scaleCombo.getPreferredSize().height));
        scaleCombo.addActionListener(e -> {
            int idx = scaleCombo.getSelectedIndex();
            if (idx >= 0 && idx < UIScale.PRESETS.length) {
                UIScale.setScale(UIScale.PRESETS[idx]);
            }
        });
        JLabel scaleIcon = new JLabel("🔍");
        scaleIcon.setFont(sf("Dialog", Font.PLAIN, 11));
        scaleIcon.setForeground(TEXT_DIM);
        bar.add(scaleIcon);
        bar.add(scaleCombo);

        return bar;
    }

    private JPanel buildCenter() {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG);

        // Top section: toolbar + network banner stacked vertically
        JPanel topStack = new JPanel();
        topStack.setLayout(new BoxLayout(topStack, BoxLayout.Y_AXIS));
        topStack.setBackground(BG);
        topStack.add(buildToolbar());
        topStack.add(buildNetworkBanner());

        wrapper.add(topStack, BorderLayout.NORTH);
        wrapper.add(buildClientScrollArea(), BorderLayout.CENTER);
        return wrapper;
    }

    private JPanel buildNetworkBanner() {
        networkBanner = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
        networkBanner.setBackground(new Color(180, 20, 0));
        networkBanner.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(255, 60, 40)));

        JLabel icon = new JLabel("⚠  ");
        icon.setFont(new Font("Dialog", Font.BOLD, 13));
        icon.setForeground(new Color(255, 220, 80));

        JLabel text = new JLabel("SERVER NETWORK DISCONNECTED — all client connections lost");
        text.setFont(new Font("Consolas", Font.BOLD, 12));
        text.setForeground(Color.WHITE);

        networkBanner.add(icon);
        networkBanner.add(text);
        networkBanner.setVisible(false); // hidden by default
        return networkBanner;
    }

    private JScrollPane buildClientScrollArea() {
        clientGrid = new JPanel();
        clientGrid.setLayout(new BoxLayout(clientGrid, BoxLayout.Y_AXIS));
        clientGrid.setBackground(BG);
        clientGrid.setBorder(BorderFactory.createEmptyBorder(s(16), s(16), s(16), s(16)));
        showEmptyState();

        JScrollPane scroll = new JScrollPane(clientGrid);
        scroll.setBackground(BG);
        scroll.getViewport().setBackground(BG);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.getVerticalScrollBar().setBackground(BG);
        scroll.getViewport().addChangeListener(e -> clientGrid.revalidate());
        return scroll;
    }

    private JPanel buildFooter() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(10, 14, 24));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_C),
                BorderFactory.createEmptyBorder(0, 14, 0, 14)));
        bar.setPreferredSize(new Dimension(0, s(28)));

        statusLabel = new JLabel("● Server running");
        statusLabel.setFont(sf("Consolas", Font.PLAIN, 11));
        statusLabel.setForeground(COLOR_GREEN);

        JLabel ver = new JLabel("ACS v1.0  |  © 2025 Sangmesh Sarsambe");
        ver.setFont(sf("Consolas", Font.PLAIN, 11));
        ver.setForeground(TEXT_DIM);

        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(ver, BorderLayout.EAST);
        return bar;
    }

    // ── Monitoring actions ────────────────────────────────────────────────────

    private void doStartMonitoringAll() {
        if (ConnectionManager.getClientCount() == 0) {
            setStatus("No clients connected");
            return;
        }
        ConnectionManager.broadcastMessage("START_ACTIVITY");
        ConnectionManager.broadcastMessage("START_KEYLOG");
        ConnectionManager.broadcastMessage("START_USB");
        ActivityState.setMonitoring(true);
        applyMonitoringState(true);
        for (ClientCard card : cardMap.values()) {
            if (!card.isDisconnected())
                card.setMonitoring(true);
        }
        SessionManager.startSession();
        setStatus("Activity monitoring started — session: " + SessionManager.getSessionStart());
    }

    private void doStopMonitoringAll() {
        ConnectionManager.broadcastMessage("STOP_ACTIVITY");
        ConnectionManager.broadcastMessage("STOP_KEYLOG");
        ConnectionManager.broadcastMessage("STOP_USB");
        // Safety: unlock all clients when monitoring stops
        ConnectionManager.broadcastMessage("UNLOCK_INPUT");
        ActivityState.setMonitoring(false);
        applyMonitoringState(false);

        // Clear all cards — monitoring dot, alerts, lock state
        for (ClientCard card : cardMap.values()) {
            card.setMonitoring(false);
            card.setKeywordAlert(false);
            card.setUsbAlert(false);
            card.setInputLocked(false);
        }

        // Merge and finalize session FIRST — while alert flags are still available
        SessionManager.stopSession();

        // THEN clear in-memory logs + alerts (after merge has read them)
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

        setStatus("Activity monitoring stopped — logs saved");
    }

    private void applyMonitoringState(boolean active) {
        setToolbarToggleState(monitorToggle, active);
        lockAllBtn.setEnabled(active);
        unlockAllBtn.setEnabled(active);
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

    // ── Lock All / Unlock All ──────────────────────────────────────────────

    private void doLockAll() {
        if (!ActivityState.isMonitoring()) {
            setStatus("⚠ Start monitoring first before locking");
            return;
        }
        if (ConnectionManager.getClientCount() == 0) {
            setStatus("No clients connected");
            return;
        }
        for (ClientHandler h : ConnectionManager.getAllClients().values()) {
            if (h.isClientAlive() && !h.isInputLocked()) {
                h.sendLockInput();
            }
        }
        setStatus("🔒 Lock command sent to all clients");
    }

    private void doUnlockAll() {
        if (!ActivityState.isMonitoring()) {
            setStatus("⚠ Start monitoring first before unlocking");
            return;
        }
        if (ConnectionManager.getClientCount() == 0) {
            setStatus("No clients connected");
            return;
        }
        for (ClientHandler h : ConnectionManager.getAllClients().values()) {
            if (h.isClientAlive() && h.isInputLocked()) {
                h.sendUnlockInput();
            }
        }
        setStatus("🔓 Unlock command sent to all clients");
    }

    /**
     * Natural sort comparator — handles numeric suffixes correctly.
     * "b106-2" < "b106-10" < "b106-20"
     * Pure alphabetic sort would give "b106-10" < "b106-2" which is wrong.
     */
    private static int naturalCompare(String a, String b) {
        if (a == null)
            a = "";
        if (b == null)
            b = "";
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                // Extract full number from both
                int ni = i, nj = j;
                while (ni < a.length() && Character.isDigit(a.charAt(ni)))
                    ni++;
                while (nj < b.length() && Character.isDigit(b.charAt(nj)))
                    nj++;
                int numA = Integer.parseInt(a.substring(i, ni));
                int numB = Integer.parseInt(b.substring(j, nj));
                if (numA != numB)
                    return Integer.compare(numA, numB);
                i = ni;
                j = nj;
            } else {
                // ── FIXED: Character has no compareToIgnoreCase — use toLowerCase + compare
                int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0)
                    return cmp;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    // ── Grid management ───────────────────────────────────────────────────────

    private void refreshGrid() {
        clientGrid.removeAll();
        cardMap.clear();

        Map<String, ClientHandler> allClients = ConnectionManager.getAllClientsForDisplay();

        if (allClients.isEmpty()) {
            showEmptyState();
        } else {
            clientGrid.setLayout(new BoxLayout(clientGrid, BoxLayout.Y_AXIS));

            ClientHandler activeStream = StreamManager.getActiveStreamClient();
            boolean monitoring = ActivityState.isMonitoring();

            // Build cards and group by lab prefix
            Map<String, List<ClientCard>> labGroups = new TreeMap<>();

            for (Map.Entry<String, ClientHandler> entry : allClients.entrySet()) {
                ClientHandler handler = entry.getValue();
                ClientCard card = new ClientCard(
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

                // Restore lock state
                card.setInputLocked(handler.isInputLocked());

                cardMap.put(entry.getKey(), card);

                // Group by lab prefix
                String labKey = extractLabPrefix(handler.getHostname());
                labGroups.computeIfAbsent(labKey, k -> new ArrayList<>()).add(card);
            }

            // Sort each group's cards using natural sort — b106-2 before b106-10
            for (List<ClientCard> cards : labGroups.values()) {
                cards.sort((a, b) -> {
                    String ha = a.getHandler().getHostname();
                    String hb = b.getHandler().getHostname();
                    if (ha == null)
                        ha = a.getHandler().getClientIp();
                    if (hb == null)
                        hb = b.getHandler().getClientIp();
                    return naturalCompare(ha, hb);
                });
            }

            // Add groups in sorted order — "Others" last, natural sort for lab names
            List<String> sortedKeys = new ArrayList<>(labGroups.keySet());
            sortedKeys.sort((a, b) -> {
                if ("Others".equals(a))
                    return 1;
                if ("Others".equals(b))
                    return -1;
                return naturalCompare(a, b);
            });

            for (String labKey : sortedKeys) {
                List<ClientCard> cards = labGroups.get(labKey);
                clientGrid.add(createLabGroupPanel(labKey, cards));
                clientGrid.add(Box.createRigidArea(new Dimension(0, s(12))));
            }
        }

        updateClientCountLabel();
        clientGrid.revalidate();
        clientGrid.repaint();
    }

    /**
     * Extracts the lab prefix from a hostname.
     * "b106-2" → "B106"
     * "b202-pc" → "B202"
     * "mypc" → "Others" (no hyphen)
     */
    private String extractLabPrefix(String hostname) {
        if (hostname == null || hostname.isEmpty())
            return "Others";
        int lastDash = hostname.lastIndexOf('-');
        if (lastDash <= 0)
            return "Others";
        return hostname.substring(0, lastDash).toUpperCase();
    }

    /**
     * Creates a styled group panel for a lab.
     * Rounded border, title bar with lab name and count, WrapLayout for cards.
     */
    private JPanel createLabGroupPanel(String labName, List<ClientCard> cards) {
        JPanel group = new JPanel(new BorderLayout(0, 0));
        group.setBackground(new Color(14, 19, 34));
        group.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_C, 1, true),
                BorderFactory.createEmptyBorder(0, 0, s(8), 0)));
        group.setAlignmentX(Component.LEFT_ALIGNMENT);

        // ── Title bar ──────────────────────
        JPanel titleBar = new JPanel(new BorderLayout());
        titleBar.setBackground(new Color(20, 28, 48));
        int scrollBarWidth = UIManager.getInt("ScrollBar.width");
        if (scrollBarWidth <= 0)
            scrollBarWidth = 12;
        titleBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_C),
                BorderFactory.createEmptyBorder(s(6), s(14), s(6), s(14) + scrollBarWidth)));

        // Lab icon + name
        boolean isOthers = "Others".equals(labName);
        String icon = isOthers ? "📋" : "🖥";
        Color titleColor = isOthers ? TEXT_DIM : ACCENT;

        JLabel title = new JLabel(icon + "  " + (isOthers ? "Others" : "Lab " + labName));
        title.setFont(sf("Consolas", Font.BOLD, 13));
        title.setForeground(titleColor);

        JLabel countBadge = new JLabel(cards.size() + (cards.size() == 1 ? " client" : " clients"));
        countBadge.setFont(sf("Consolas", Font.PLAIN, 11));
        countBadge.setForeground(TEXT_DIM);

        titleBar.add(title, BorderLayout.WEST);
        titleBar.add(countBadge, BorderLayout.EAST);
        group.add(titleBar, BorderLayout.NORTH);

        // ── Card area ──────────────────────
        JPanel cardArea = new JPanel(new WrapLayout(FlowLayout.LEFT, s(14), s(14))) {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                // Walk up to JScrollPane viewport for correct available width —
                // getParent() alone returns the group panel which is unconstrained
                Container p = getParent();
                while (p != null && !(p instanceof JScrollPane))
                    p = p.getParent();
                if (p instanceof JScrollPane) {
                    d.width = ((JScrollPane) p).getViewport().getWidth();
                } else if (getParent() != null) {
                    d.width = getParent().getWidth();
                }
                return d;
            }
        };
        cardArea.setBackground(new Color(14, 19, 34));
        cardArea.setBorder(BorderFactory.createEmptyBorder(s(10), s(10), s(4), s(10)));
        cardArea.setAlignmentX(Component.LEFT_ALIGNMENT);

        for (ClientCard card : cards) {
            cardArea.add(card);
        }

        group.add(cardArea, BorderLayout.CENTER);
        return group;
    }

    private void updateClientCountLabel() {
        int connected = ConnectionManager.getClientCount();
        int disconnected = ConnectionManager.getDisconnectedClients().size();
        String label = connected + " CONNECTED";
        if (disconnected > 0)
            label += "  |  " + disconnected + " DISCONNECTED";
        clientCountLabel.setText(label);
        clientCountLabel.setFont(sf("Consolas", Font.BOLD, 14));
        clientCountLabel.setForeground(connected > 0 ? COLOR_GREEN : TEXT_DIM);
    }

    private void showEmptyState() {
        clientGrid.setLayout(new GridBagLayout());
        JLabel empty = new JLabel("Waiting for client connections…");
        empty.setFont(sf("Consolas", Font.PLAIN, 14));
        empty.setForeground(new Color(60, 80, 120));
        clientGrid.add(empty);
    }

    // ── ConnectionListener ────────────────────────────────────────────────────

    @Override
    public void onClientConnected(String clientId) {
        SwingUtilities.invokeLater(() -> {
            // Network is back if a client can connect — hide banner
            if (networkBanner.isVisible()) {
                networkBanner.setVisible(false);
                setStatus("Network restored — client reconnected");
            }
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

            // Smart network check — only when a client disconnects
            if (!isServerNetworkUp()) {
                networkBanner.setVisible(true);
                setStatus("⚠ Server network lost — clients may reconnect when restored");
            }
        });
    }

    @Override
    public void onDisconnectedClientRemoved(String clientIp) {
        SwingUtilities.invokeLater(() -> {
            // Find and remove the card, then rebuild the grid to fix group layout
            String keyToRemove = null;
            for (Map.Entry<String, ClientCard> entry : cardMap.entrySet()) {
                if (entry.getValue().getHandler().getClientIp().equals(clientIp)) {
                    keyToRemove = entry.getKey();
                    break;
                }
            }
            if (keyToRemove != null) {
                cardMap.remove(keyToRemove);
                refreshGrid(); // rebuild groups
            }
            updateClientCountLabel();
            setStatus("Removed disconnected client: " + clientIp);
        });
    }

    @Override
    public void onClientInfoUpdated(String clientId) {
        SwingUtilities.invokeLater(() -> {
            // Hostname changed — rebuild the grid so the card moves to the correct group
            refreshGrid();
        });
    }

    @Override
    public void onClientLockStateChanged(String clientId, boolean locked) {
        SwingUtilities.invokeLater(() -> {
            ClientCard card = cardMap.get(clientId);
            if (card != null) {
                card.setInputLocked(locked);
            }
            // Get handler to resolve hostname for status message
            ClientHandler h = ConnectionManager.getClient(clientId);
            String displayName = (h != null && h.getHostname() != null) ? h.getHostname() : clientId;
            if (locked) {
                setStatus("🔒 " + displayName + " — INPUT LOCKED");
            } else {
                setStatus("🔓 " + displayName + " — INPUT UNLOCKED");
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Checks if the server has at least one non-loopback network interface
     * that is up and has an IPv4 address. Called only on client disconnect —
     * no background polling.
     */
    private boolean isServerNetworkUp() {
        try {
            var ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp())
                    continue;
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address)
                        return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public void setStatus(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText("● " + message));
    }

    private void confirmShutdown() {
        int r = JOptionPane.showConfirmDialog(this,
                "Shutdown the ACS server?\nAll clients will be disconnected.",
                "Confirm Shutdown", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (r == JOptionPane.YES_OPTION)
            System.exit(0);
    }

    private JPanel infoChip(String label, String value) {
        JPanel chip = new JPanel(new GridLayout(2, 1, 0, 1));
        chip.setBackground(new Color(24, 34, 60));
        chip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_C),
                BorderFactory.createEmptyBorder(s(4), s(12), s(4), s(12))));
        JLabel l = new JLabel(label);
        l.setFont(sf("Consolas", Font.PLAIN, 8));
        l.setForeground(TEXT_DIM);
        JLabel v = new JLabel(value);
        v.setFont(sf("Consolas", Font.BOLD, 12));
        v.setForeground(TEXT);
        chip.add(l);
        chip.add(v);
        return chip;
    }

    private JButton toolbarButton(String text, Color fg) {
        JButton btn = new JButton(text);
        btn.setFont(sf("Consolas", Font.BOLD, 11));
        btn.setForeground(fg);
        btn.setBackground(new Color(22, 30, 52));
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(fg.darker()),
                BorderFactory.createEmptyBorder(s(4), s(12), s(4), s(12))));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JButton toolbarToggle(String offText, Color offFg, Color offBg,
            String onText, Color onFg, Color onBg,
            String offTooltip, String onTooltip) {
        JButton btn = new JButton(offText);
        btn.setFont(sf("Consolas", Font.BOLD, 11));
        btn.setForeground(offFg);
        btn.setBackground(offBg);
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(offFg.darker()),
                BorderFactory.createEmptyBorder(s(4), s(12), s(4), s(12))));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText(offTooltip);

        btn.putClientProperty("toggled", false);
        btn.putClientProperty("offText", offText);
        btn.putClientProperty("onText", onText);
        btn.putClientProperty("offFg", offFg);
        btn.putClientProperty("onFg", onFg);
        btn.putClientProperty("offBg", offBg);
        btn.putClientProperty("onBg", onBg);
        btn.putClientProperty("offTooltip", offTooltip);
        btn.putClientProperty("onTooltip", onTooltip);

        return btn;
    }

    private void setToolbarToggleState(JButton btn, boolean on) {
        btn.putClientProperty("toggled", on);
        String text = (String) btn.getClientProperty(on ? "onText" : "offText");
        Color fg = (Color) btn.getClientProperty(on ? "onFg" : "offFg");
        Color bg = (Color) btn.getClientProperty(on ? "onBg" : "offBg");
        String tooltip = (String) btn.getClientProperty(on ? "onTooltip" : "offTooltip");
        btn.setText(text);
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(fg.darker()),
                BorderFactory.createEmptyBorder(s(4), s(12), s(4), s(12))));
        btn.setToolTipText(tooltip);
    }

    private static void applyDarkDefaults() {
        System.setProperty("awt.useSystemAAFontSettings", "lcd");
        System.setProperty("swing.aatext", "true");
        UIManager.put("OptionPane.background", PANEL_BG);
        UIManager.put("Panel.background", PANEL_BG);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Button.background", new Color(28, 38, 65));
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.border", BorderFactory.createLineBorder(BORDER_C));
        UIManager.put("ScrollBar.background", BG);
        UIManager.put("ScrollBar.thumb", BORDER_C);
        UIManager.put("ScrollBar.track", BG);
    }
}