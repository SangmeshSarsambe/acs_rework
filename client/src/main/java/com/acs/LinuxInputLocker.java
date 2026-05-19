package com.acs;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Linux implementation of InputLocker using xinput to disable/enable devices.
 *
 * Keyboard & mouse are disabled via `xinput disable` (X11 only, not Wayland).
 *
 * Backdoor key combo (Ctrl+Alt+Shift+U) IS supported on Linux by reading raw
 * key events from /dev/input/eventX — the kernel input device. This works even
 * when xinput has disabled the keyboard at the X11 level, because /dev/input
 * operates below X11.
 *
 * Requirement: read access to /dev/input/event* files.
 * → Either run as root, or add user to 'input' group:
 * sudo usermod -aG input $(whoami)
 */
public class LinuxInputLocker implements InputLocker {

    private final AtomicBoolean keyboardLocked = new AtomicBoolean(false);
    private final AtomicBoolean mouseLocked = new AtomicBoolean(false);

    private List<String> keyboardDeviceIds = new ArrayList<>();
    private List<String> mouseDeviceIds = new ArrayList<>();

    // ── Hotplug monitor ──────────────────────────────────────────────────
    private volatile Thread hotplugThread;
    private final AtomicBoolean hotplugRunning = new AtomicBoolean(false);

    // ── Backdoor key monitor ─────────────────────────────────────────────
    private final Runnable backdoorCallback;
    private volatile Thread backdoorThread;
    private final AtomicBoolean backdoorRunning = new AtomicBoolean(false);
    private volatile FileInputStream backdoorStream; // closed to unblock read()
    private String keyboardEventDevice; // e.g., /dev/input/event3

    // Linux key codes (from linux/input-event-codes.h)
    private static final short EV_KEY = 1;
    private static final short KEY_U = 22;
    private static final short KEY_LEFTCTRL = 29;
    private static final short KEY_RIGHTCTRL = 97;
    private static final short KEY_LEFTSHIFT = 42;
    private static final short KEY_RIGHTSHIFT = 54;
    private static final short KEY_LEFTALT = 56;
    private static final short KEY_RIGHTALT = 100;

    // input_event struct size on 64-bit Linux:
    // struct timeval (16 bytes) + __u16 type (2) + __u16 code (2) + __s32 value (4)
    // = 24
    private static final int INPUT_EVENT_SIZE = 24;

    /**
     * @param backdoorCallback called when Ctrl+Alt+Shift+U is detected during lock
     */
    public LinuxInputLocker(Runnable backdoorCallback) {
        this.backdoorCallback = backdoorCallback;

        // Discover X11 device IDs (for xinput disable/enable)
        keyboardDeviceIds = discoverDevices("keyboard");
        mouseDeviceIds = discoverDevices("pointer");

        // Discover the kernel input device file (for backdoor key monitoring)
        keyboardEventDevice = discoverKeyboardEventDevice();

        System.out.println("[Linux] Keyboard xinput IDs: " + keyboardDeviceIds);
        System.out.println("[Linux] Mouse xinput IDs:    " + mouseDeviceIds);
        System.out.println("[Linux] Keyboard event dev:  " + keyboardEventDevice);
    }

    // ═══════════════════════════════════════════════════════════════════
    // KEYBOARD
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void lockKeyboard() {
        if (keyboardLocked.getAndSet(true))
            return;

        // Start backdoor monitor BEFORE disabling xinput — opens the device file
        startBackdoorMonitor();

        System.out.println("[Linux] Disabling keyboard devices via xinput...");
        for (String id : keyboardDeviceIds) {
            runXinput("disable", id);
        }
    }

    @Override
    public void unlockKeyboard() {
        if (!keyboardLocked.getAndSet(false))
            return;
        System.out.println("[Linux] Enabling keyboard devices via xinput...");
        for (String id : keyboardDeviceIds) {
            runXinput("enable", id);
        }
        stopBackdoorMonitor();
    }

    // ═══════════════════════════════════════════════════════════════════
    // MOUSE
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void lockMouse() {
        if (mouseLocked.getAndSet(true))
            return;
        System.out.println("[Linux] Disabling mouse/pointer devices via xinput...");
        for (String id : mouseDeviceIds) {
            runXinput("disable", id);
        }
        startHotplugMonitor();
    }

    @Override
    public void unlockMouse() {
        if (!mouseLocked.getAndSet(false))
            return;
        System.out.println("[Linux] Enabling mouse/pointer devices via xinput...");
        stopHotplugMonitor();
        for (String id : mouseDeviceIds) {
            runXinput("enable", id);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // STATUS & CLEANUP
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public boolean isKeyboardLocked() {
        return keyboardLocked.get();
    }

    @Override
    public boolean isMouseLocked() {
        return mouseLocked.get();
    }

    @Override
    public void cleanup() {
        System.out.println("[Linux] Cleanup: force-enabling all devices...");
        stopBackdoorMonitor();
        stopHotplugMonitor();
        for (String id : keyboardDeviceIds) {
            runXinput("enable", id);
        }
        for (String id : mouseDeviceIds) {
            runXinput("enable", id);
        }
        keyboardLocked.set(false);
        mouseLocked.set(false);
    }

    // ═══════════════════════════════════════════════════════════════════
    // BACKDOOR KEY MONITOR — reads /dev/input/eventX at kernel level
    // ═══════════════════════════════════════════════════════════════════

    private void startBackdoorMonitor() {
        if (keyboardEventDevice == null) {
            System.err.println("[Linux] ╔══════════════════════════════════════════════════════════╗");
            System.err.println("[Linux] ║  No keyboard event device found — backdoor key DISABLED ║");
            System.err.println("[Linux] ║  Escape: server unlock, or Ctrl+Alt+F2 → kill process   ║");
            System.err.println("[Linux] ╚══════════════════════════════════════════════════════════╝");
            return;
        }

        // Pre-check: can we read the device?
        if (!new File(keyboardEventDevice).canRead()) {
            System.err.println("[Linux] ╔══════════════════════════════════════════════════════════════╗");
            System.err.println("[Linux] ║  Cannot read " + keyboardEventDevice + " — backdoor DISABLED  ║");
            System.err.println("[Linux] ║  FIX: sudo usermod -aG input $(whoami)  (then re-login)      ║");
            System.err.println("[Linux] ║  Escape: server unlock, or Ctrl+Alt+F2 → kill process        ║");
            System.err.println("[Linux] ╚══════════════════════════════════════════════════════════════╝");
            return;
        }

        if (backdoorRunning.getAndSet(true))
            return;

        backdoorThread = new Thread(() -> {
            System.out.println("[Linux] Backdoor monitor started on " + keyboardEventDevice);
            System.out.println("[Linux] Press Ctrl+Alt+Shift+U to emergency-unlock");

            boolean ctrlHeld = false, altHeld = false, shiftHeld = false;

            try {
                backdoorStream = new FileInputStream(keyboardEventDevice);
                byte[] buf = new byte[INPUT_EVENT_SIZE];

                while (backdoorRunning.get()) {
                    int total = 0;
                    // Read exactly INPUT_EVENT_SIZE bytes (may need multiple reads)
                    while (total < INPUT_EVENT_SIZE) {
                        int n = backdoorStream.read(buf, total, INPUT_EVENT_SIZE - total);
                        if (n <= 0) {
                            backdoorRunning.set(false);
                            break;
                        }
                        total += n;
                    }
                    if (total != INPUT_EVENT_SIZE)
                        break;

                    // Parse input_event struct (little-endian on x86/ARM)
                    ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
                    bb.position(16); // skip struct timeval (16 bytes on 64-bit)
                    short type = bb.getShort();
                    short code = bb.getShort();
                    int value = bb.getInt(); // 1=press, 0=release, 2=repeat

                    if (type != EV_KEY)
                        continue;

                    // Track modifier states
                    if (code == KEY_LEFTCTRL || code == KEY_RIGHTCTRL) {
                        ctrlHeld = (value != 0); // pressed or repeat → held
                    } else if (code == KEY_LEFTALT || code == KEY_RIGHTALT) {
                        altHeld = (value != 0);
                    } else if (code == KEY_LEFTSHIFT || code == KEY_RIGHTSHIFT) {
                        shiftHeld = (value != 0);
                    } else if (code == KEY_U && value == 1 && ctrlHeld && altHeld && shiftHeld) {
                        System.out.println("[Linux] *** BACKDOOR COMBO DETECTED (Ctrl+Alt+Shift+U) ***");
                        ctrlHeld = false;
                        altHeld = false;
                        shiftHeld = false;
                        // Trigger on separate thread — don't block the reader
                        new Thread(() -> backdoorCallback.run(), "BackdoorTrigger").start();
                    }
                }
            } catch (IOException e) {
                if (backdoorRunning.get()) {
                    System.err.println("[Linux] Backdoor monitor error: " + e.getMessage());
                    System.err.println("[Linux] Ensure the user has read access to " + keyboardEventDevice);
                    System.err.println("[Linux] Fix: sudo usermod -aG input $(whoami)  (then re-login)");
                }
            }

            System.out.println("[Linux] Backdoor monitor stopped");
        }, "LinuxBackdoorMonitor");
        backdoorThread.setDaemon(true);
        backdoorThread.start();
    }

    private void stopBackdoorMonitor() {
        backdoorRunning.set(false);
        // Close the stream to unblock the read() call (interrupt doesn't work on
        // FileInputStream)
        if (backdoorStream != null) {
            try {
                backdoorStream.close();
            } catch (IOException ignored) {
            }
            backdoorStream = null;
        }
        if (backdoorThread != null) {
            try {
                backdoorThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            backdoorThread = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HOTPLUG MONITOR — disables newly connected mice during lock
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Monitors udevadm for newly connected pointer/mouse devices.
     * When a new device appears while locked, discovers its xinput ID
     * and immediately disables it.
     * Uses the same udevadm monitor approach as DeviceDetector.
     */
    private void startHotplugMonitor() {
        if (hotplugRunning.getAndSet(true))
            return;

        hotplugThread = new Thread(() -> {
            System.out.println("[Linux] Hotplug monitor started — will block newly connected mice");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "udevadm", "monitor", "--kernel",
                        "--subsystem-match=input");
                pb.redirectErrorStream(true);
                Process p = pb.start();

                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while (hotplugRunning.get() && (line = br.readLine()) != null) {
                        // New input device added
                        if (line.contains(" add ") && mouseLocked.get()) {
                            Thread.sleep(1000); // give xinput more time to register

                            // Re-discover ALL pointer devices and disable all of them
                            // This covers both existing and newly added ones
                            List<String> allMouseIds = discoverDevices("pointer");
                            for (String id : allMouseIds) {
                                runXinput("disable", id); // idempotent — safe to re-disable
                                if (!mouseDeviceIds.contains(id)) {
                                    mouseDeviceIds.add(id);
                                }
                            }
                        }
                    }
                }
                p.destroy();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (hotplugRunning.get()) {
                    System.err.println("[Linux] Hotplug monitor error: " + e.getMessage());
                }
            }
            System.out.println("[Linux] Hotplug monitor stopped");
        }, "LinuxHotplugMonitor");
        hotplugThread.setDaemon(true);
        hotplugThread.start();
    }

    private void stopHotplugMonitor() {
        hotplugRunning.set(false);
        if (hotplugThread != null) {
            hotplugThread.interrupt();
            try {
                hotplugThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            hotplugThread = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DEVICE DISCOVERY
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Discovers X11 input device IDs by type using xinput list.
     * Only picks "slave" devices (not master devices like "Virtual core keyboard").
     * Skips power/video/sleep/lid devices that can cause X11 BadAccess errors.
     */
    private List<String> discoverDevices(String type) {
        List<String> ids = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("xinput", "list").start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                String lower = line.toLowerCase();
                if (lower.contains("slave") && lower.contains(type)) {
                    // Skip virtual/power/video devices that cause BadAccess
                    if (lower.contains("virtual")
                            || lower.contains("power button")
                            || lower.contains("video bus")
                            || lower.contains("sleep button")
                            || lower.contains("lid switch")) {
                        continue;
                    }
                    int idIdx = lower.indexOf("id=");
                    if (idIdx >= 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = idIdx + 3; i < line.length(); i++) {
                            char c = line.charAt(i);
                            if (Character.isDigit(c))
                                sb.append(c);
                            else
                                break;
                        }
                        if (sb.length() > 0)
                            ids.add(sb.toString());
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            System.err.println("[Linux] Could not list xinput devices: " + e.getMessage());
        }
        return ids;
    }

    /**
     * Discovers the /dev/input/eventX device for the physical keyboard.
     * Parses /proc/bus/input/devices — looks for entries with "kbd" in handlers.
     * Falls back to /dev/input/by-path/*-kbd symlinks.
     */
    private String discoverKeyboardEventDevice() {
        // Method 1: Parse /proc/bus/input/devices
        try {
            String content = Files.readString(Path.of("/proc/bus/input/devices"));
            String[] blocks = content.split("\n\n");

            for (String block : blocks) {
                String lower = block.toLowerCase();
                // Skip power buttons, video bus, etc — only real keyboards
                if (lower.contains("power button") || lower.contains("video bus")
                        || lower.contains("sleep button") || lower.contains("lid switch")) {
                    continue;
                }
                // Must have "kbd" in handlers to be a real keyboard
                if (!lower.contains("kbd"))
                    continue;

                for (String line : block.split("\n")) {
                    if (line.startsWith("H: Handlers=") || line.startsWith("H:Handlers=")) {
                        String handlers = line.substring(line.indexOf('=') + 1);
                        for (String handler : handlers.trim().split("\\s+")) {
                            if (handler.startsWith("event")) {
                                String path = "/dev/input/" + handler;
                                if (new File(path).exists()) {
                                    System.out.println("[Linux] Found keyboard event device: " + path
                                            + " (from /proc/bus/input/devices)");
                                    return path;
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[Linux] Could not read /proc/bus/input/devices: " + e.getMessage());
        }

        // Method 2: Fallback — check /dev/input/by-path/*-kbd symlinks
        try {
            File byPath = new File("/dev/input/by-path");
            if (byPath.isDirectory()) {
                File[] files = byPath.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().endsWith("-kbd")) {
                            String resolved = f.getCanonicalPath();
                            if (new File(resolved).exists()) {
                                System.out.println("[Linux] Found keyboard event device: " + resolved
                                        + " (from /dev/input/by-path)");
                                return resolved;
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }

        System.err.println("[Linux] WARNING: Could not find keyboard event device");
        return null;
    }

    /**
     * Runs xinput enable/disable with error handling.
     * Captures stderr to log X11 errors without crashing.
     */
    private void runXinput(String action, String deviceId) {
        try {
            ProcessBuilder pb = new ProcessBuilder("xinput", action, deviceId);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) {
                    System.out.println("[xinput " + action + " " + deviceId + "] " + line);
                }
            }
            int exit = p.waitFor();
            if (exit != 0) {
                System.err.println("[Linux] xinput " + action + " " + deviceId
                        + " exited with code " + exit);
            }
        } catch (Exception e) {
            System.err.println("[Linux] xinput " + action + " " + deviceId
                    + " failed: " + e.getMessage());
        }
    }
}