package com.acs.ui;

import com.acs.AlertManager;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;

/**
 * Popup dialog for managing flagged keywords.
 * Opened from ServerUI toolbar via "⚙ Keywords" button.
 *
 * Features:
 *   - Scrollable list of current keywords, each with ✕ remove button
 *   - Text input + Add button at bottom
 *   - Pre-built keywords loaded on first launch (editable/removable)
 *   - All changes persisted immediately to keywords.txt
 */
public class KeywordDialog extends JFrame {

    // ── Palette (matches server dashboard) ───────────────────────────────────
    private static final Color BG        = new Color(12,  17,  30);
    private static final Color PANEL_BG  = new Color(18,  25,  42);
    private static final Color BORDER_C  = new Color(35,  50,  85);
    private static final Color ACCENT    = new Color(0,  200, 255);
    private static final Color TEXT      = new Color(220, 230, 255);
    private static final Color TEXT_DIM  = new Color(110, 135, 175);
    private static final Color COLOR_RED = new Color(255,  68, 102);
    private static final Color INPUT_BG  = new Color(22,  30,  52);

    private JPanel      keywordListPanel;
    private JTextField  inputField;
    private JScrollPane scrollPane;

    public KeywordDialog(Window parent) {
        super("⚙ Keyword Management");

        setSize(UIScale.scale(420), UIScale.scale(520));
        setMinimumSize(UIScale.dim(360, 400));
        setResizable(true);
        setLocationRelativeTo(parent);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(0, 0));

        add(buildHeader(),  BorderLayout.NORTH);
        add(buildList(),    BorderLayout.CENTER);
        add(buildInput(),   BorderLayout.SOUTH);

        refreshList();
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(PANEL_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_C),
                BorderFactory.createEmptyBorder(UIScale.scale(14), UIScale.scale(18), UIScale.scale(14), UIScale.scale(18))));

        JLabel title = new JLabel("⚙ Flagged Keywords");
        title.setFont(UIScale.font("Consolas", Font.BOLD, 15));
        title.setForeground(ACCENT);

        JLabel sub = new JLabel("Activity containing these words will trigger an alert");
        sub.setFont(UIScale.font("Consolas", Font.PLAIN, 10));
        sub.setForeground(TEXT_DIM);

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBackground(PANEL_BG);
        left.add(title);
        left.add(Box.createVerticalStrut(UIScale.scale(3)));
        left.add(sub);

        panel.add(left, BorderLayout.WEST);
        return panel;
    }

    private JScrollPane buildList() {
        keywordListPanel = new JPanel();
        keywordListPanel.setLayout(new BoxLayout(keywordListPanel, BoxLayout.Y_AXIS));
        keywordListPanel.setBackground(BG);
        keywordListPanel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(8), UIScale.scale(12), UIScale.scale(8), UIScale.scale(12)));

        scrollPane = new JScrollPane(keywordListPanel);
        scrollPane.setBackground(BG);
        scrollPane.getViewport().setBackground(BG);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setBackground(BG);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return scrollPane;
    }

    private JPanel buildInput() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(PANEL_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_C),
                BorderFactory.createEmptyBorder(UIScale.scale(12), UIScale.scale(14), UIScale.scale(12), UIScale.scale(14))));

        inputField = new JTextField();
        inputField.setBackground(INPUT_BG);
        inputField.setForeground(TEXT);
        inputField.setCaretColor(ACCENT);
        inputField.setFont(UIScale.font("Consolas", Font.PLAIN, 12));
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_C),
                BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(10), UIScale.scale(6), UIScale.scale(10))));
        inputField.setToolTipText("Enter keyword to add");

        // Add on Enter key
        inputField.addActionListener(e -> doAdd());

        JButton addBtn = new JButton("+ Add");
        addBtn.setFont(UIScale.font("Consolas", Font.BOLD, 12));
        addBtn.setForeground(ACCENT);
        addBtn.setBackground(INPUT_BG);
        addBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT.darker()),
                BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(14), UIScale.scale(6), UIScale.scale(14))));
        addBtn.setFocusPainted(false);
        addBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addBtn.addActionListener(e -> doAdd());

        panel.add(inputField, BorderLayout.CENTER);
        panel.add(addBtn,     BorderLayout.EAST);
        return panel;
    }

    // ── List management ───────────────────────────────────────────────────────

    private void refreshList() {
        keywordListPanel.removeAll();

        List<String> kws = AlertManager.getKeywords();

        if (kws.isEmpty()) {
            JLabel empty = new JLabel("No keywords added yet.");
            empty.setFont(UIScale.font("Consolas", Font.PLAIN, 12));
            empty.setForeground(TEXT_DIM);
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            keywordListPanel.add(Box.createVerticalStrut(UIScale.scale(8)));
            keywordListPanel.add(empty);
        } else {
            for (String kw : kws) {
                keywordListPanel.add(buildKeywordRow(kw));
                keywordListPanel.add(Box.createVerticalStrut(UIScale.scale(4)));
            }
        }

        keywordListPanel.revalidate();
        keywordListPanel.repaint();
    }

    private JPanel buildKeywordRow(String keyword) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setBackground(new Color(22, 30, 52));
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_C),
                BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(12), UIScale.scale(6), UIScale.scale(8))));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UIScale.scale(36)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(keyword);
        label.setFont(UIScale.font("Consolas", Font.PLAIN, 12));
        label.setForeground(TEXT);

        JButton removeBtn = new JButton("✕");
        removeBtn.setFont(UIScale.font("Consolas", Font.BOLD, 11));
        removeBtn.setForeground(COLOR_RED);
        removeBtn.setBackground(new Color(22, 30, 52));
        removeBtn.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 4));
        removeBtn.setFocusPainted(false);
        removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeBtn.setContentAreaFilled(false);
        removeBtn.addActionListener(e -> {
            AlertManager.removeKeyword(keyword);
            refreshList();
        });

        row.add(label,     BorderLayout.CENTER);
        row.add(removeBtn, BorderLayout.EAST);
        return row;
    }

    private void doAdd() {
        String kw = inputField.getText().trim();
        if (kw.isEmpty()) return;

        // Check duplicate
        if (AlertManager.getKeywords().contains(kw.toLowerCase())) {
            inputField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(COLOR_RED),
                    BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(10), UIScale.scale(6), UIScale.scale(10))));
            return;
        }

        AlertManager.addKeyword(kw);
        inputField.setText("");
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_C),
                BorderFactory.createEmptyBorder(UIScale.scale(6), UIScale.scale(10), UIScale.scale(6), UIScale.scale(10))));
        refreshList();

        // Scroll to bottom to show newly added keyword
        SwingUtilities.invokeLater(() -> {
            JScrollBar bar = scrollPane.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }
}