package com.acs.ui;

import com.acs.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/**
 * A clickable card representing one connected or disconnected client.
 *
 * Three visual states:
 *   CONNECTED    — green dot, normal border
 *   STREAMING    — green blinking dot, green border
 *   MONITORING   — yellow dot (independent of streaming)
 *   DISCONNECTED — grey border, grey badge, timestamp shown, dots dim
 */
public class ClientCard extends JPanel {

    // ── Palette ───────────────────────────────────────────────────────────────
    private static final Color BG_NORMAL        = new Color(19,  25,  41);
    private static final Color BG_HOVER         = new Color(26,  36,  62);
    private static final Color BG_DISCONNECTED  = new Color(16,  18,  26);
    private static final Color BORDER_IDLE      = new Color(40,  55,  90);
    private static final Color BORDER_HOVER     = new Color(0,  200, 255);
    private static final Color BORDER_STREAM    = new Color(0,  255, 136);
    private static final Color BORDER_DISCONN   = new Color(60,  60,  80);
    private static final Color TEXT_PRIMARY     = new Color(220, 230, 255);
    private static final Color TEXT_DIM         = new Color(110, 130, 170);
    private static final Color TEXT_DISCONN     = new Color(80,   85, 110);
    private static final Color COLOR_CONNECTED  = new Color(0,  210, 110);
    private static final Color COLOR_STREAMING  = new Color(0,  230, 110);
    private static final Color COLOR_MONITORING = new Color(255, 200,  50);
    private static final Color COLOR_DISCONN    = new Color(80,   85, 110);
    private static final Color SCREEN_IDLE      = new Color(18,  28,  52);
    private static final Color SCREEN_STREAM    = new Color(0,   35,  55);
    private static final Color SCREEN_DISCONN   = new Color(12,  14,  22);

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean hovered      = false;
    private boolean streaming    = false;
    private boolean monitoring   = false;
    private boolean disconnected = false;
    private boolean inputLocked  = false;

    // ── Alert state ───────────────────────────────────────────────────────────
    private boolean keywordAlert = false;
    private boolean usbAlert     = false;
    private boolean resetIconHovered = false;

    private float   blinkAlpha     = 255f;
    private boolean blinkDirection = false;
    private Timer   blinkTimer;

    // Independent alert blink timers
    private float   alertBlinkAlpha     = 255f;
    private boolean alertBlinkDirection = false;
    private Timer   alertBlinkTimer;

    private final ClientHandler handler;
    private final ServerUI       parent;

    // ─────────────────────────────────────────────────────────────────────────

    public ClientCard(ClientHandler handler, String serverIp, int serverPort, ServerUI parent) {
        this.handler      = handler;
        this.parent       = parent;
        this.monitoring   = ActivityState.isMonitoring();
        this.disconnected = handler.isDisconnected();

        setPreferredSize(UIScale.dim(155, 200));
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        blinkTimer = new Timer(50, e -> {
            if (blinkDirection) {
                blinkAlpha += 12f;
                if (blinkAlpha >= 255f) { blinkAlpha = 255f; blinkDirection = false; }
            } else {
                blinkAlpha -= 12f;
                if (blinkAlpha <= 60f)  { blinkAlpha = 60f;  blinkDirection = true;  }
            }
            repaint();
        });

        // Alert blink — aggressive fast pulse
        alertBlinkTimer = new Timer(25, e -> {
            if (alertBlinkDirection) {
                alertBlinkAlpha += 25f;
                if (alertBlinkAlpha >= 255f) { alertBlinkAlpha = 255f; alertBlinkDirection = false; }
            } else {
                alertBlinkAlpha -= 25f;
                if (alertBlinkAlpha <= 0f) { alertBlinkAlpha = 0f; alertBlinkDirection = true; }
            }
            repaint();
        });

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
            @Override public void mouseExited (MouseEvent e) {
                hovered = false;
                resetIconHovered = false;
                repaint();
            }
            @Override public void mouseClicked(MouseEvent e) {
                if ((keywordAlert || usbAlert) && getResetIconBounds().contains(e.getPoint())) {
                    doResetAlerts();
                } else {
                    parent.openClientDialog(handler, ClientCard.this);
                }
            }
        });

        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                boolean over = (keywordAlert || usbAlert)
                        && getResetIconBounds().contains(e.getPoint());
                if (over != resetIconHovered) {
                    resetIconHovered = over;
                    repaint();
                }
            }
        });

        // Register for tooltip support — appear immediately, custom font
        ToolTipManager.sharedInstance().registerComponent(this);
        ToolTipManager.sharedInstance().setInitialDelay(0);
        ToolTipManager.sharedInstance().setReshowDelay(0);
        UIManager.put("ToolTip.font", new Font("Consolas", Font.PLAIN, 11));
        UIManager.put("ToolTip.background", new Color(22, 30, 52));
        UIManager.put("ToolTip.foreground", new Color(220, 230, 255));
        UIManager.put("ToolTip.border", BorderFactory.createLineBorder(new Color(35, 50, 85)));
    }

    // ── Tooltip ───────────────────────────────────────────────────────────────

    @Override
    public String getToolTipText(MouseEvent e) {
        if ((keywordAlert || usbAlert) && getResetIconBounds().contains(e.getPoint())) {
            return "<html>Reset Alerts<br>Clears keyword and USB alert flags for this client</html>";
        }
        return null;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
        if (streaming && !disconnected) {
            blinkAlpha = 255f;
            blinkTimer.start();
        } else {
            blinkTimer.stop();
            blinkAlpha = 255f;
        }
        repaint();
    }

    public void setMonitoring(boolean monitoring)   { this.monitoring   = monitoring;   repaint(); }

    public void setKeywordAlert(boolean alert) {
        this.keywordAlert = alert;
        updateAlertTimer();
        repaint();
    }

    public void setUsbAlert(boolean alert) {
        this.usbAlert = alert;
        updateAlertTimer();
        repaint();
    }

    private void updateAlertTimer() {
        if ((keywordAlert || usbAlert)) {
            if (!alertBlinkTimer.isRunning()) {
                alertBlinkAlpha = 255f;
                alertBlinkTimer.start();
            }
        } else {
            alertBlinkTimer.stop();
            alertBlinkAlpha = 255f;
        }
    }

    public void setDisconnected(boolean disconnected) {
        this.disconnected = disconnected;
        if (disconnected) {
            blinkTimer.stop();
            alertBlinkTimer.stop(); // stop animation — alerts persist but don't blink on disconnected cards
            blinkAlpha      = 255f;
            alertBlinkAlpha = 255f;
            streaming       = false;
            monitoring      = false;
        }
        repaint();
    }

    public boolean isStreaming()      { return streaming;     }
    public boolean isMonitoring()     { return monitoring;    }
    public boolean isDisconnected()   { return disconnected;  }
    public boolean hasKeywordAlert()  { return keywordAlert;  }
    public boolean hasUsbAlert()      { return usbAlert;      }
    public ClientHandler getHandler() { return handler;       }

    public void setInputLocked(boolean locked) {
        this.inputLocked = locked;
        repaint();
    }
    public boolean isInputLocked()    { return inputLocked; }

    // ── Reset icon ────────────────────────────────────────────────────────────

    /** Bottom-right corner — 20x20 hit area, with padding from edge.
     *  Returns bounds in DEVICE pixels (for mouse hit testing). */
    private java.awt.Rectangle getResetIconBounds() {
        float sc = UIScale.getScale();
        int bw = Math.round(getWidth() / sc), bh = Math.round(getHeight() / sc);
        // Logical bounds scaled to device pixels
        return new java.awt.Rectangle(
                Math.round((bw - 32) * sc), Math.round((bh - 32) * sc),
                Math.round(20 * sc), Math.round(20 * sc));
    }

    private void doResetAlerts() {
        String hostname = handler.getHostname() != null ? handler.getHostname() : handler.getClientIp();

        // Step 1: Yes/No confirmation
        int choice = javax.swing.JOptionPane.showConfirmDialog(
                this,
                "Reset all alerts for " + hostname + "?\n"
                + "This will clear CHEATING / USB flags for this client.\n"
                + "The violation will NOT appear in the session log.",
                "⚠ Confirm Alert Reset",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);

        if (choice != javax.swing.JOptionPane.YES_OPTION) return;

        // Step 2: Type "confirm" to proceed
        String input = javax.swing.JOptionPane.showInputDialog(
                this,
                "Type  'confirm'  to reset alerts:",
                "⚠ Final Confirmation",
                javax.swing.JOptionPane.WARNING_MESSAGE);

        if (input == null || !input.trim().equalsIgnoreCase("confirm")) {
            parent.setStatus("Alert reset cancelled");
            return;
        }

        AlertManager.resetAlerts(handler.getClientIp());
        // resetCardAlerts handles the visual update — no need to call set methods directly
        parent.resetCardAlerts(handler.getClientIp());
    }

    // ── Painting ──────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        float sc = UIScale.getScale();
        g2.scale(sc, sc);
        int w = Math.round(getWidth() / sc), h = Math.round(getHeight() / sc);

        // Background
        g2.setColor(disconnected ? BG_DISCONNECTED : (hovered ? BG_HOVER : BG_NORMAL));
        g2.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);

        // Border
        Color border = disconnected ? BORDER_DISCONN
                     : streaming    ? BORDER_STREAM
                     : hovered      ? BORDER_HOVER
                     :                BORDER_IDLE;
        g2.setColor(border);
        g2.setStroke(new BasicStroke(streaming && !disconnected ? 2f : 1.5f));
        g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);

        drawMonitor(g2, w / 2, 65);

        // Dots
        int dotY = 106;
        if (disconnected) {
            // Both dots dim when disconnected
            g2.setColor(new Color(40, 42, 55));
            g2.fillOval(w / 2 - 16, dotY, 9, 9);
            g2.fillOval(w / 2 + 7,  dotY, 9, 9);
        } else {
            // Streaming dot — blinking
            if (streaming) {
                int alpha = Math.round(blinkAlpha);
                g2.setColor(new Color(COLOR_STREAMING.getRed(),
                        COLOR_STREAMING.getGreen(), COLOR_STREAMING.getBlue(), alpha));
            } else {
                g2.setColor(new Color(40, 55, 80));
            }
            g2.fillOval(w / 2 - 16, dotY, 9, 9);
            // Monitoring dot
            g2.setColor(monitoring ? COLOR_MONITORING : new Color(40, 55, 80));
            g2.fillOval(w / 2 + 7, dotY, 9, 9);
        }

        // Hostname + IP
        String hostname = handler.getHostname();
        String ip       = handler.getClientIp();
        String displayName = hostname != null ? hostname : ip;

        g2.setFont(new Font("Consolas", Font.BOLD, 11));
        g2.setColor(disconnected ? TEXT_DISCONN : TEXT_PRIMARY);
        FontMetrics fm = g2.getFontMetrics();
        // Truncate if too wide — use logical width w (not device getWidth())
        String truncated = displayName;
        while (fm.stringWidth(truncated) > w - 16 && truncated.length() > 3) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        if (!truncated.equals(displayName)) truncated += "…";
        g2.drawString(truncated, (w - fm.stringWidth(truncated)) / 2, 129);

        g2.setFont(new Font("Consolas", Font.PLAIN, 10));
        g2.setColor(TEXT_DISCONN);
        fm = g2.getFontMetrics();
        g2.drawString(ip, (w - fm.stringWidth(ip)) / 2, 142);

        // Status badges
        g2.setFont(new Font("Consolas", Font.BOLD, 9));

        if (disconnected) {
            // Row 1: DISCONNECTED
            g2.setColor(COLOR_DISCONN);
            String b = "● DISCONNECTED";
            fm = g2.getFontMetrics();
            g2.drawString(b, (w - fm.stringWidth(b)) / 2, 158);

            // Row 2: disconnect timestamp
            String ts = handler.getDisconnectTimestamp();
            if (ts != null) {
                // Show time only (HH:mm:ss) to fit card width
                String timeOnly = ts.length() > 10 ? ts.substring(11) : ts;
                g2.setFont(new Font("Consolas", Font.PLAIN, 8));
                g2.setColor(new Color(65, 68, 90));
                fm = g2.getFontMetrics();
                g2.drawString(timeOnly, (w - fm.stringWidth(timeOnly)) / 2, 172);
            }
        } else {
            // Row 1: streaming or connected
            if (streaming) {
                g2.setColor(COLOR_STREAMING);
                String b = "● STREAMING";
                fm = g2.getFontMetrics();
                g2.drawString(b, (w - fm.stringWidth(b)) / 2, 158);
            } else {
                g2.setColor(COLOR_CONNECTED);
                String b = "● CONNECTED";
                fm = g2.getFontMetrics();
                g2.drawString(b, (w - fm.stringWidth(b)) / 2, 158);
            }
            // Row 2: monitoring
            if (monitoring) {
                g2.setColor(COLOR_MONITORING);
                String b = "● MONITORING";
                fm = g2.getFontMetrics();
                g2.drawString(b, (w - fm.stringWidth(b)) / 2, 173);
            }
            // Row 3: locked
            if (inputLocked) {
                g2.setColor(new Color(255, 68, 102));
                String b = "🔒 LOCKED";
                fm = g2.getFontMetrics();
                int lockY = monitoring ? 186 : 173;
                g2.drawString(b, (w - fm.stringWidth(b)) / 2, lockY);
            }
        }

        g2.dispose();
    }

    // ── Alert overlay ─────────────────────────────────────────────────────────

    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        if (!keywordAlert && !usbAlert) return;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

        float sc = UIScale.getScale();
        g2.scale(sc, sc);
        int w = Math.round(getWidth() / sc);
        int h = Math.round(getHeight() / sc);
        int alpha = Math.round(alertBlinkAlpha);

        // ── Keyword alert — red border blink (always drawn for keyword) ───────
        if (keywordAlert) {
            g2.setColor(new Color(255, 50, 80, alpha));
            g2.setStroke(new BasicStroke(3.5f));
            g2.drawRoundRect(2, 2, w - 4, h - 4, 14, 14);

            g2.setColor(new Color(255, 50, 80, Math.min(alpha / 3, 80)));
            g2.setStroke(new BasicStroke(6f));
            g2.drawRoundRect(3, 3, w - 6, h - 6, 12, 12);
        }

        // ── Alert strip banners — stacked in the center of the card ───────────
        int bannerH = 20;
        int gap     = 2;
        boolean both = keywordAlert && usbAlert;

        // Calculate Y positions: if both, stack them centered; if one, center it
        int totalH = both ? (bannerH * 2 + gap) : bannerH;
        int startY = h / 2 - totalH / 2 - 3;

        int cheatingY = startY;
        int usbY      = both ? (startY + bannerH + gap) : startY;

        if (keywordAlert) {
            drawAlertBanner(g2, w, cheatingY, bannerH, alpha,
                    new Color(200, 0, 20),  new Color(255, 40, 50),   // red gradient
                    new Color(255, 80, 80),                            // edge color
                    "\u26a0 CHEATING DETECTED");
        }

        if (usbAlert) {
            drawAlertBanner(g2, w, usbY, bannerH, alpha,
                    new Color(220, 100, 0), new Color(255, 140, 0),   // orange gradient
                    new Color(255, 200, 0),                            // edge color
                    "\u26a0 USB DETECTED");
        }

        // ── Reset icon — yellow ↺ bottom-right, always fully visible ─────────
        // Convert device-pixel bounds back to logical coords for drawing
        int riX = Math.round(getResetIconBounds().x / sc);
        int riY = Math.round(getResetIconBounds().y / sc);
        int riW = 20, riH = 20;
        java.awt.Rectangle r = new java.awt.Rectangle(riX, riY, riW, riH);
        g2.setColor(resetIconHovered
                ? new Color(80, 65, 10, 230)
                : new Color(40, 35, 10, 200));
        g2.fillOval(r.x, r.y, r.width, r.height);
        g2.setColor(resetIconHovered
                ? new Color(255, 220, 80, 255)
                : new Color(255, 200, 50, 220));
        g2.setStroke(new BasicStroke(resetIconHovered ? 1.8f : 1.2f));
        g2.drawOval(r.x, r.y, r.width, r.height);
        g2.setFont(new Font("Dialog", Font.BOLD, 13));
        g2.setColor(resetIconHovered
                ? new Color(255, 230, 100)
                : new Color(255, 200, 50));
        FontMetrics rfm = g2.getFontMetrics();
        String icon = "\u21ba";
        g2.drawString(icon,
                r.x + (r.width  - rfm.stringWidth(icon)) / 2,
                r.y + (r.height + rfm.getAscent() - rfm.getDescent()) / 2);

        g2.dispose();
    }

    /**
     * Draws a single full-width alert banner strip at the given Y position.
     * Used for both "CHEATING DETECTED" and "USB DETECTED" with different colors.
     */
    private void drawAlertBanner(Graphics2D g2, int w, int y, int h, int alpha,
                                  Color gradLeft, Color gradRight, Color edgeColor,
                                  String label) {
        // Banner background — gradient that stays mostly opaque
        int bgAlpha = Math.max(alpha, 170);
        GradientPaint gp = new GradientPaint(
                0, y, new Color(gradLeft.getRed(), gradLeft.getGreen(), gradLeft.getBlue(), bgAlpha),
                w, y, new Color(gradRight.getRed(), gradRight.getGreen(), gradRight.getBlue(), bgAlpha));
        g2.setPaint(gp);
        g2.fillRect(0, y, w, h);

        // Top/bottom edge lines
        g2.setColor(new Color(edgeColor.getRed(), edgeColor.getGreen(), edgeColor.getBlue(),
                Math.min(alpha, 220)));
        g2.setStroke(new BasicStroke(1.2f));
        g2.drawLine(0, y, w, y);
        g2.drawLine(0, y + h, w, y + h);

        // Text — white bold, always high visibility
        g2.setFont(new Font("Consolas", Font.BOLD, 9));
        g2.setColor(new Color(255, 255, 255, Math.max(alpha, 200)));
        FontMetrics fm = g2.getFontMetrics();
        int tx = (w - fm.stringWidth(label)) / 2;
        int ty = y + (h + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(label, tx, ty);
    }

    private void drawMonitor(Graphics2D g2, int cx, int cy) {
        int sw = 72, sh = 48;
        int bx = cx - sw / 2 - 5, by = cy - sh / 2 - 5;
        int bw = sw + 10,          bh = sh + 10;

        g2.setColor(disconnected ? new Color(25, 28, 40) : new Color(38, 52, 85));
        g2.fillRoundRect(bx, by, bw, bh, 8, 8);

        Color screenColor = disconnected ? SCREEN_DISCONN
                          : streaming    ? SCREEN_STREAM
                          :                SCREEN_IDLE;
        g2.setColor(screenColor);
        g2.fillRect(cx - sw / 2, cy - sh / 2, sw, sh);

        if (!disconnected) {
            if (streaming) {
                g2.setColor(new Color(0, 180, 255, 160));
                int[] heights = {10, 18, 12, 22, 16, 8, 20, 14};
                int bw2 = 7, gap = 1;
                int totalW = heights.length * (bw2 + gap) - gap;
                int startX = cx - totalW / 2;
                int baseY  = cy + sh / 2 - 5;
                for (int i = 0; i < heights.length; i++)
                    g2.fillRect(startX + i * (bw2 + gap), baseY - heights[i], bw2, heights[i]);
                g2.setColor(new Color(0, 200, 255, 30));
                g2.fillRect(cx - sw / 2, cy - 4, sw, 3);
            } else if (monitoring) {
                g2.setColor(new Color(255, 200, 50, 55));
                g2.fillRect(cx - sw / 2 + 4, cy - sh / 2 + 4, sw - 8, sh - 8);
                g2.setColor(new Color(255, 200, 50, 120));
                g2.fillRect(cx - sw / 2 + 6, cy - 3, sw - 12, 3);
                g2.fillRect(cx - sw / 2 + 6, cy + 4, (sw - 12) / 2, 2);
            } else {
                g2.setColor(new Color(28, 44, 80));
                g2.fillRect(cx - sw / 2 + 4, cy - sh / 2 + 4, sw - 8, sh - 8);
                g2.setColor(new Color(40, 60, 100));
                g2.fillOval(cx - 3, cy - 3, 6, 6);
            }

            // Lock overlay on monitor screen
            if (inputLocked) {
                g2.setColor(new Color(255, 40, 60, 35));
                g2.fillRect(cx - sw / 2, cy - sh / 2, sw, sh);
                // Draw padlock icon centered in monitor
                drawPadlock(g2, cx, cy);
            }
        } else {
            // Disconnected screen — X mark
            g2.setColor(new Color(55, 58, 75));
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int m = 10;
            g2.drawLine(cx - sw/2 + m, cy - sh/2 + m, cx + sw/2 - m, cy + sh/2 - m);
            g2.drawLine(cx + sw/2 - m, cy - sh/2 + m, cx - sw/2 + m, cy + sh/2 - m);
        }

        g2.setColor(disconnected ? new Color(35, 38, 52) : new Color(55, 75, 120));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRect(cx - sw / 2, cy - sh / 2, sw, sh);
        g2.setColor(disconnected ? new Color(35, 38, 52) : new Color(50, 70, 110));
        g2.drawRoundRect(bx, by, bw, bh, 8, 8);

        // Power LED
        Color ledColor;
        if (disconnected)   ledColor = new Color(50, 30, 30);
        else if (inputLocked) ledColor = new Color(255, 68, 102);  // red when locked
        else if (streaming) ledColor = new Color(0, 230, 120);
        else if (monitoring) ledColor = new Color(200, 160, 0);
        else                ledColor = new Color(0, 120, 60);
        g2.setColor(ledColor);
        g2.fillOval(bx + bw - 11, by + bh - 9, 5, 5);

        int neckTop = by + bh;
        g2.setColor(disconnected ? new Color(25, 28, 40) : new Color(38, 52, 85));
        g2.fillRect(cx - 5, neckTop, 10, 10);
        g2.setColor(disconnected ? new Color(22, 24, 34) : new Color(32, 45, 75));
        g2.fillRoundRect(cx - 22, neckTop + 10, 44, 9, 5, 5);
        g2.setColor(disconnected ? new Color(35, 38, 52) : new Color(50, 70, 110));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(cx - 22, neckTop + 10, 44, 9, 5, 5);
    }

    /**
     * Draws a small padlock icon centered at (cx, cy) inside the monitor screen.
     */
    private void drawPadlock(Graphics2D g2, int cx, int cy) {
        Color lockColor = new Color(255, 68, 102, 200);
        g2.setColor(lockColor);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // Lock body = filled rect
        int bw = 14, bh = 10;
        int bx = cx - bw / 2, by = cy - 1;
        g2.fillRoundRect(bx, by, bw, bh, 3, 3);

        // Shackle = arc above body
        g2.setColor(new Color(255, 68, 102, 220));
        g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawArc(cx - 5, by - 9, 10, 12, 0, 180);

        // Keyhole = small dark circle + slit
        g2.setColor(new Color(20, 10, 15));
        g2.fillOval(cx - 2, by + 3, 4, 4);
    }
}