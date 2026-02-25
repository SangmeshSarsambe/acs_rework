package com.acs;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.swing.*;
import java.awt.*;

public class WindowTrackerUI extends JFrame {

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private JTextArea logArea;
    private JButton startButton;
    private JButton stopButton;
    private JButton clearButton;
    private JLabel statusLabel;

    private Thread monitorThread;
    private volatile boolean isRunning = false;

    public WindowTrackerUI() {
        setTitle("Active Window Monitor");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        initComponents();
    }

    private void initComponents() {

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        /* ===================== TOP PANEL ===================== */
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        startButton = createButton("Start Monitoring", new Color(46, 204, 113));
        startButton.addActionListener(e -> startMonitoring());

        stopButton = createButton("Stop Monitoring", new Color(231, 76, 60));
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopMonitoring());

        clearButton = createButton("Clear Log", new Color(52, 73, 94));
        clearButton.addActionListener(e -> logArea.setText(""));

        statusLabel = new JLabel("Status: Ready");
        statusLabel.setFont(new Font("Arial", Font.BOLD, 12));

        topPanel.add(startButton);
        topPanel.add(stopButton);
        topPanel.add(clearButton);
        topPanel.add(Box.createHorizontalStrut(20));
        topPanel.add(statusLabel);

        /* ===================== CENTER PANEL ===================== */
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBackground(Color.BLACK);
        logArea.setForeground(new Color(0, 255, 0));

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        /* ===================== BOTTOM PANEL ===================== */
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel infoLabel = new JLabel("Monitoring active windows every 1 second");
        infoLabel.setFont(new Font("Arial", Font.ITALIC, 10));
        infoLabel.setForeground(Color.GRAY);
        bottomPanel.add(infoLabel);

        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        add(mainPanel);
    }

    /* ===================== BUTTON FACTORY ===================== */
    private JButton createButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(true);
        return btn;
    }

    /* ===================== MONITOR CONTROL ===================== */
    private void startMonitoring() {
        if (isRunning) return;

        isRunning = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        statusLabel.setText("Status: Monitoring...");
        statusLabel.setForeground(new Color(46, 204, 113));

        monitorThread = new Thread(this::monitorActiveWindows);
        monitorThread.setDaemon(true);
        monitorThread.start();

        appendLog("=== Monitoring Started ===\n");
    }

    private void stopMonitoring() {
        isRunning = false;
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        statusLabel.setText("Status: Stopped");
        statusLabel.setForeground(new Color(231, 76, 60));

        appendLog("=== Monitoring Stopped ===\n\n");
    }

    /* ===================== CORE LOGIC ===================== */
    private void monitorActiveWindows() {
        String lastOutput = "";

        while (isRunning) {
            try {
                WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();

                char[] title = new char[512];
                User32.INSTANCE.GetWindowText(hwnd, title, 512);

                IntByReference pid = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);

                WinNT.HANDLE process = Kernel32.INSTANCE.OpenProcess(
                        WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ,
                        false,
                        pid.getValue()
                );

                char[] exePath = new char[1024];
                Psapi.INSTANCE.GetModuleFileNameExW(process, null, exePath, 1024);
                Kernel32.INSTANCE.CloseHandle(process);

                String output = "App: " + Native.toString(exePath) +
                        " | Window: " + Native.toString(title);

                if (!output.equals(lastOutput)) {
                    String time = LocalDateTime.now().format(TIME_FORMATTER);
                    appendLog("[" + time + "] " + output + "\n");
                    lastOutput = output;
                }

                Thread.sleep(1000);

            } catch (Exception e) {
                appendLog("[ERROR] " + e.getMessage() + "\n");
            }
        }
    }

    private void appendLog(String text) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(text);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    /* ===================== MAIN ===================== */
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new WindowTrackerUI().setVisible(true));
    }
}
