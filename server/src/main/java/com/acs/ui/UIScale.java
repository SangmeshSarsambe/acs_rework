package com.acs.ui;

import java.awt.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Global UI scaling utility.
 *
 * Provides a single scale factor that proportionally affects all UI elements:
 * text, icons, cards, indicators, panels, padding, spacing, and buttons.
 *
 * Presets: 75%, 100%, 125%, 150%, 175%, 200%
 *
 * Scale is persisted to ui_scale.properties and applied instantly
 * without restarting the application.
 */
public class UIScale {

    private static float scaleFactor = 1.0f;
    private static final Path SCALE_FILE = Paths.get("ui_scale.properties");
    private static final List<Runnable> listeners = new ArrayList<>();

    public static final float[]  PRESETS       = {0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
    public static final String[] PRESET_LABELS = {"75%", "100%", "125%", "150%", "175%", "200%"};

    static {
        load();
    }

    // ── Scale accessors ──────────────────────────────────────────────────────

    public static float getScale() { return scaleFactor; }

    /** Scales an integer pixel value by the current factor. */
    public static int scale(int base) {
        return Math.round(base * scaleFactor);
    }

    /** Scales a float value by the current factor. */
    public static float scaleF(float base) {
        return base * scaleFactor;
    }

    /** Returns a scaled Dimension. */
    public static Dimension dim(int baseW, int baseH) {
        return new Dimension(scale(baseW), scale(baseH));
    }

    /** Returns a Font with scaled size. */
    public static Font font(String name, int style, int baseSize) {
        return new Font(name, style, scale(baseSize));
    }

    // ── Scale change ─────────────────────────────────────────────────────────

    public static void setScale(float factor) {
        if (Math.abs(factor - scaleFactor) < 0.001f) return;
        scaleFactor = Math.max(0.5f, Math.min(3.0f, factor));
        save();
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    public static void addScaleChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeScaleChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Returns the preset index closest to the current scale factor. */
    public static int getCurrentPresetIndex() {
        int closest = 1;
        float minDiff = Float.MAX_VALUE;
        for (int i = 0; i < PRESETS.length; i++) {
            float diff = Math.abs(PRESETS[i] - scaleFactor);
            if (diff < minDiff) { minDiff = diff; closest = i; }
        }
        return closest;
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private static void load() {
        try {
            if (Files.exists(SCALE_FILE)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(SCALE_FILE)) {
                    props.load(in);
                }
                scaleFactor = Float.parseFloat(
                        props.getProperty("scaleFactor", "1.0"));
                scaleFactor = Math.max(0.5f, Math.min(3.0f, scaleFactor));
            }
        } catch (Exception e) {
            scaleFactor = 1.0f;
        }
    }

    private static void save() {
        try {
            Properties props = new Properties();
            props.setProperty("scaleFactor", String.valueOf(scaleFactor));
            try (OutputStream out = Files.newOutputStream(SCALE_FILE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                props.store(out, "ACS UI Scale — managed by server");
            }
        } catch (IOException e) {
            System.err.println("[UIScale] Save error: " + e.getMessage());
        }
    }
}
