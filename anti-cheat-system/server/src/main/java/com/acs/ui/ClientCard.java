package com.acs.ui;
import com.acs.*;   // pulls in ClientHandler, ConnectionManager, StreamManager etc.

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * A clickable card that represents one connected client.
 * Draws a monitor icon using Graphics2D — no external images needed.
 *
 * To add more info to the card (e.g. ping, OS) just add more fields
 * and paint them inside paintComponent().
 */
public class ClientCard extends JPanel {

    // ── Palette ──────────────────────────────────────────────────────────────
    private static final Color BG_NORMAL    = new Color(19,  25,  41);
    private static final Color BG_HOVER     = new Color(26,  36,  62);
    private static final Color BORDER_IDLE  = new Color(40,  55,  90);
    private static final Color BORDER_HOVER = new Color(0,  200, 255);
    private static final Color BORDER_STREAM= new Color(0,  255, 136);
    private static final Color TEXT_PRIMARY = new Color(220, 230, 255);
    private static final Color TEXT_DIM     = new Color(110, 130, 170);
    private static final Color COLOR_CONNECTED  = new Color(0,  210, 110);
    private static final Color COLOR_STREAMING  = new Color(0,  200, 255);
    private static final Color SCREEN_IDLE      = new Color(18,  28,  52);
    private static final Color SCREEN_STREAM    = new Color(0,   35,  55);

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean hovered   = false;
    private boolean streaming = false;

    private final ClientHandler handler;

    // ─────────────────────────────────────────────────────────────────────────

    public ClientCard(ClientHandler handler, String serverIp, int serverPort, ServerUI parent) {
        this.handler = handler;

        setPreferredSize(new Dimension(155, 185));
        setOpaque(false); // we paint everything ourselves
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
            @Override public void mouseExited (MouseEvent e) { hovered = false; repaint(); }

            @Override
            public void mouseClicked(MouseEvent e) {
                Window owner = SwingUtilities.getWindowAncestor(ClientCard.this);
                ClientDetailDialog dialog = new ClientDetailDialog(
                        owner, handler, serverIp, serverPort, ClientCard.this);
                dialog.setVisible(true);
            }
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called by ClientDetailDialog when stream starts/stops. */
    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
        repaint();
    }

    public boolean isStreaming() { return streaming; }

    public ClientHandler getHandler() { return handler; }

    // ── Painting ──────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,        RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,   RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();

        // Card background
        g2.setColor(hovered ? BG_HOVER : BG_NORMAL);
        g2.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);

        // Card border
        Color border = streaming ? BORDER_STREAM : (hovered ? BORDER_HOVER : BORDER_IDLE);
        g2.setColor(border);
        g2.setStroke(new BasicStroke(streaming ? 2f : 1.5f));
        g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);

        // Monitor icon centred in upper 2/3
        drawMonitor(g2, w / 2, 68);

        // Status dot just below monitor base
        Color dotColor = streaming ? COLOR_STREAMING : COLOR_CONNECTED;
        g2.setColor(dotColor);
        g2.fillOval(w / 2 - 4, 108, 8, 8);

        // IP line
        String[] parts  = handler.getClientId().split(":", 2);
        String   ip     = parts[0];
        String   portTxt = parts.length > 1 ? ":" + parts[1] : "";

        g2.setFont(new Font("Consolas", Font.BOLD, 11));
        g2.setColor(TEXT_PRIMARY);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(ip, (w - fm.stringWidth(ip)) / 2, 132);

        g2.setFont(new Font("Consolas", Font.PLAIN, 10));
        g2.setColor(TEXT_DIM);
        fm = g2.getFontMetrics();
        g2.drawString(portTxt, (w - fm.stringWidth(portTxt)) / 2, 146);

        // Status badge
        g2.setFont(new Font("Consolas", Font.BOLD, 9));
        if (streaming) {
            g2.setColor(COLOR_STREAMING);
            String badge = "● STREAMING";
            fm = g2.getFontMetrics();
            g2.drawString(badge, (w - fm.stringWidth(badge)) / 2, 166);
        } else {
            g2.setColor(COLOR_CONNECTED);
            String badge = "● CONNECTED";
            fm = g2.getFontMetrics();
            g2.drawString(badge, (w - fm.stringWidth(badge)) / 2, 166);
        }

        g2.dispose();
    }

    /**
     * Draws a classic CRT-style monitor centered at (cx, cy).
     * All shapes are relative — easy to rescale.
     */
    private void drawMonitor(Graphics2D g2, int cx, int cy) {
        int sw = 72, sh = 50;          // screen/bezel dimensions
        int bx = cx - sw / 2 - 5;     // bezel origin x
        int by = cy - sh / 2 - 5;     // bezel origin y
        int bw = sw + 10, bh = sh + 10;

        // Bezel (outer shell)
        g2.setColor(new Color(38, 52, 85));
        g2.fillRoundRect(bx, by, bw, bh, 8, 8);

        // Screen glass
        g2.setColor(streaming ? SCREEN_STREAM : SCREEN_IDLE);
        g2.fillRect(cx - sw / 2, cy - sh / 2, sw, sh);

        // Screen content
        if (streaming) {
            // Fake waveform / audio bars to suggest live video
            g2.setColor(new Color(0, 180, 255, 160));
            int[] heights = {10, 18, 12, 22, 16, 8, 20, 14};
            int bw2 = 7, gap = 1;
            int totalW = heights.length * (bw2 + gap) - gap;
            int startX = cx - totalW / 2;
            int baseY  = cy + sh / 2 - 6;
            for (int i = 0; i < heights.length; i++) {
                int barH = heights[i];
                g2.fillRect(startX + i * (bw2 + gap), baseY - barH, bw2, barH);
            }
            // Scan line glow
            g2.setColor(new Color(0, 200, 255, 30));
            g2.fillRect(cx - sw / 2, cy - 4, sw, 3);
        } else {
            // Idle: dim glow in centre
            g2.setColor(new Color(28, 44, 80));
            g2.fillRect(cx - sw / 2 + 4, cy - sh / 2 + 4, sw - 8, sh - 8);
            // Power dot
            g2.setColor(new Color(40, 60, 100));
            g2.fillOval(cx - 3, cy - 3, 6, 6);
        }

        // Screen border
        g2.setColor(new Color(55, 75, 120));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRect(cx - sw / 2, cy - sh / 2, sw, sh);

        // Bezel border
        g2.setColor(new Color(50, 70, 110));
        g2.drawRoundRect(bx, by, bw, bh, 8, 8);

        // Power LED on bezel bottom-right
        g2.setColor(streaming ? new Color(0, 230, 120) : new Color(0, 120, 60));
        g2.fillOval(bx + bw - 11, by + bh - 9, 5, 5);

        // Neck
        int neckTop = by + bh;
        g2.setColor(new Color(38, 52, 85));
        g2.fillRect(cx - 5, neckTop, 10, 10);

        // Base
        g2.setColor(new Color(32, 45, 75));
        g2.fillRoundRect(cx - 22, neckTop + 10, 44, 9, 5, 5);
        g2.setColor(new Color(50, 70, 110));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(cx - 22, neckTop + 10, 44, 9, 5, 5);
    }
}
