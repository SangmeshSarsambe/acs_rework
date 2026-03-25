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

        setPreferredSize(new Dimension(155, 200));
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

    // ── Reset icon ────────────────────────────────────────────────────────────

    /** Bottom-right corner — 20x20 hit area, with padding from edge */
    private java.awt.Rectangle getResetIconBounds() {
        int w = getWidth(), h = getHeight();
        return new java.awt.Rectangle(w - 32, h - 32, 20, 20);
    }

    private void doResetAlerts() {
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

        int w = getWidth(), h = getHeight();

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

        // IP
        String[] parts   = handler.getClientId().split(":", 2);
        String   ip      = parts[0];
        String   portTxt = parts.length > 1 ? ":" + parts[1] : "";

        g2.setFont(new Font("Consolas", Font.BOLD, 11));
        g2.setColor(disconnected ? TEXT_DISCONN : TEXT_PRIMARY);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(ip, (w - fm.stringWidth(ip)) / 2, 129);

        g2.setFont(new Font("Consolas", Font.PLAIN, 10));
        g2.setColor(TEXT_DISCONN);
        fm = g2.getFontMetrics();
        g2.drawString(portTxt, (w - fm.stringWidth(portTxt)) / 2, 142);

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

        int w = getWidth();
        int h = getHeight();
        int alpha = Math.round(alertBlinkAlpha);

        // ── Keyword alert — aggressive red border blink ───────────────────────
        if (keywordAlert) {
            // Thick red border that fully blinks on/off
            g2.setColor(new Color(255, 50, 80, alpha));
            g2.setStroke(new BasicStroke(3.5f));
            g2.drawRoundRect(2, 2, w - 4, h - 4, 14, 14);

            // Inner glow
            g2.setColor(new Color(255, 50, 80, Math.min(alpha / 3, 80)));
            g2.setStroke(new BasicStroke(6f));
            g2.drawRoundRect(3, 3, w - 6, h - 6, 12, 12);

            // Small ⚠ top right corner — always visible
            g2.setFont(new Font("Consolas", Font.BOLD, 12));
            g2.setColor(new Color(255, 80, 100, Math.max(alpha, 120)));
            g2.drawString("⚠", w - 22, 18);
        }

        // ── USB alert — pendrive symbol inside monitor screen ─────────────────
        if (usbAlert) {
            drawUsbSymbol(g2, w / 2, 65, alpha);
        }

        // ── Reset icon — yellow ↺ bottom-right, always fully visible ─────────
        java.awt.Rectangle r = getResetIconBounds();
        // Background — brighter when hovered
        g2.setColor(resetIconHovered
                ? new Color(80, 65, 10, 230)
                : new Color(40, 35, 10, 200));
        g2.fillOval(r.x, r.y, r.width, r.height);
        // Border — brighter when hovered
        g2.setColor(resetIconHovered
                ? new Color(255, 220, 80, 255)
                : new Color(255, 200, 50, 220));
        g2.setStroke(new BasicStroke(resetIconHovered ? 1.8f : 1.2f));
        g2.drawOval(r.x, r.y, r.width, r.height);
        // ↺ symbol — brighter when hovered
        g2.setFont(new Font("Dialog", Font.BOLD, 13));
        g2.setColor(resetIconHovered
                ? new Color(255, 230, 100)
                : new Color(255, 200, 50));
        FontMetrics rfm = g2.getFontMetrics();
        String icon = "↺";
        g2.drawString(icon,
                r.x + (r.width  - rfm.stringWidth(icon)) / 2,
                r.y + (r.height + rfm.getAscent() - rfm.getDescent()) / 2);

        g2.dispose();
    }

    /**
     * Draws a recognizable pendrive/USB symbol centered at (cx, cy).
     * Blinks with the alert alpha.
     */
    private void drawUsbSymbol(Graphics2D g2, int cx, int cy, int alpha) {
        // Fluorescent green — eye-catching, distinct from monitoring yellow
        Color usb     = new Color(57,  255, 20, alpha);
        Color usbDim  = new Color(57,  255, 20, Math.min(alpha / 2, 120));
        Color usbFill = new Color(57,  255, 20, Math.min(alpha / 3, 80));

        // Monitor screen is sw=72 sh=48 centered at (cx, cy)
        // Everything must fit within that — keep it compact
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        // USB drive body — small enough to fit with label below
        int bw = 20, bh = 11;
        int bx = cx - bw / 2 - 5, by = cy - 10;
        g2.setColor(usbFill);
        g2.fillRoundRect(bx, by, bw, bh, 3, 3);
        g2.setColor(usb);
        g2.drawRoundRect(bx, by, bw, bh, 3, 3);

        // Connector to the right
        int cw = 7, ch = 6;
        int connX = bx + bw, connY = by + (bh - ch) / 2;
        g2.setColor(usbFill);
        g2.fillRect(connX, connY, cw, ch);
        g2.setColor(usb);
        g2.drawRect(connX, connY, cw, ch);

        // Notch lines on connector
        g2.setColor(usbDim);
        g2.drawLine(connX + 2, connY + 1, connX + 2, connY + ch - 1);
        g2.drawLine(connX + 4, connY + 1, connX + 4, connY + ch - 1);

        // USB fork symbol on body
        int fx = bx + 7, fy = by + bh / 2;
        g2.setColor(usb);
        g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine(fx, fy - 3, fx, fy + 3);
        g2.drawLine(fx, fy - 3, fx - 3, fy - 1);
        g2.fillOval(fx - 5, fy - 2, 3, 3);
        g2.drawLine(fx, fy - 3, fx + 3, fy - 1);
        g2.drawRect(fx + 2, fy - 2, 3, 2);

        // "USB DETECTED" label below — fits inside screen bottom
        g2.setFont(new Font("Consolas", Font.BOLD, 7));
        g2.setColor(new Color(57, 255, 20, Math.max(alpha, 140)));
        FontMetrics fm = g2.getFontMetrics();
        String label = "USB DETECTED";
        g2.drawString(label, cx - fm.stringWidth(label) / 2, by + bh + 12);
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
        g2.setColor(disconnected ? new Color(50, 30, 30)
                  : streaming    ? new Color(0,   230, 120)
                  : monitoring   ? new Color(200, 160,   0)
                  :                new Color(0,   120,  60));
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
}