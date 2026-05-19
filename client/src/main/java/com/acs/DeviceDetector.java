package com.acs;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Cross-platform removable device detector.
 *
 * Windows: uses WM_DEVICECHANGE via a hidden JNA window
 * Linux:   uses udevadm monitor via a background process
 *
 * Usage:
 *   DeviceDetector detector = new DeviceDetector(data -> sendQueue.offer("USB:" + data));
 *   detector.start();  // blocks on detection loop — run on its own thread
 *   detector.stop();   // signals the loop to exit
 */
public class DeviceDetector {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy_HH-mm-ss HH:mm:ss");

    private final Consumer<String> onDevice;
    private volatile boolean       running = false;

    // Linux: hold process ref so stop() can kill it
    private volatile Process udevProcess = null;

    // Windows: hold hwnd so stop() can post WM_QUIT
    private volatile WinDef.HWND hwnd = null;

    public DeviceDetector(Consumer<String> onDevice) {
        this.onDevice = onDevice;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts the detection loop. Blocks until stop() is called.
     * Run this on a dedicated thread (DeviceMonitor does this).
     */
    public void start() throws Exception {
        running = true;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            runWindows();
        } else {
            runLinux();
        }
    }

    public void stop() {
        running = false;

        // Windows: post WM_QUIT to unblock the GetMessage loop
        if (hwnd != null) {
            User32.INSTANCE.PostMessage(hwnd, WinUser.WM_QUIT, null, null);
            hwnd = null;
        }

        // Linux: kill the udevadm process to unblock readLine()
        if (udevProcess != null) {
            udevProcess.destroy();
            udevProcess = null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String timestamp() {
        return "[" + LocalDateTime.now().format(TIME_FMT) + "] ";
    }

    private void emit(String data) {
        onDevice.accept(timestamp() + data);
    }

    // ── Windows ───────────────────────────────────────────────────────────────

    private static final int WM_DEVICECHANGE          = 0x0219;
    private static final int DBT_DEVICEARRIVAL        = 0x8000;
    private static final int DBT_DEVICEREMOVECOMPLETE = 0x8004;
    private static final int DBT_DEVTYP_VOLUME        = 0x0002;
    private static final int DRIVE_REMOVABLE          = 2;
    private static final int DRIVE_CDROM              = 5;
    private static final int DBT_DEVTYP_DEVICEINTERFACE  = 0x0005;
    private static final int DEVICE_NOTIFY_WINDOW_HANDLE = 0x0000;

    private static final byte[] GUID_DEVINTERFACE_WPD = {
        (byte)0x78, (byte)0x78, (byte)0xC2, (byte)0x6A,
        (byte)0xFA, (byte)0xA6, (byte)0x55, (byte)0x41,
        (byte)0xBA, (byte)0x85, (byte)0xF9, (byte)0x8F,
        (byte)0x49, (byte)0x1D, (byte)0x4F, (byte)0x33
    };

    public static class DEV_BROADCAST_VOLUME extends Structure {
        public int   dbcv_size;
        public int   dbcv_devicetype;
        public int   dbcv_reserved;
        public int   dbcv_unitmask;
        public short dbcv_flags;

        public DEV_BROADCAST_VOLUME(Pointer p) { super(p); }

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList(
                    "dbcv_size", "dbcv_devicetype", "dbcv_reserved",
                    "dbcv_unitmask", "dbcv_flags");
        }
    }

    public static class DEV_BROADCAST_DEVICEINTERFACE extends Structure {
        public int    dbcc_size;
        public int    dbcc_devicetype;
        public int    dbcc_reserved;
        public byte[] dbcc_classguid = new byte[16];
        public char[] dbcc_name      = new char[1];

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.Arrays.asList(
                    "dbcc_size", "dbcc_devicetype", "dbcc_reserved",
                    "dbcc_classguid", "dbcc_name");
        }
    }

    interface User32Extra extends com.sun.jna.win32.StdCallLibrary {
        User32Extra INSTANCE = Native.load("user32", User32Extra.class,
                java.util.Collections.singletonMap(
                        com.sun.jna.Library.OPTION_FUNCTION_MAPPER,
                        (com.sun.jna.FunctionMapper) (lib, method) ->
                                method.getName().equals("RegisterDeviceNotification")
                                        ? "RegisterDeviceNotificationW"
                                        : method.getName()));
        Pointer RegisterDeviceNotification(Pointer hRecipient,
                Pointer NotificationFilter, int Flags);
    }

    private void runWindows() throws Exception {
        String className = "ACS_DeviceDetector";
        WinDef.HMODULE hInstance = Kernel32.INSTANCE.GetModuleHandle(null);

        java.util.Map<String, String> connectedDevices =
                new java.util.concurrent.ConcurrentHashMap<>();

        WinUser.WindowProc wndProc = (h, uMsg, wParam, lParam) -> {
            if (uMsg == WM_DEVICECHANGE) {
                int event = wParam.intValue();
                if (event == DBT_DEVICEARRIVAL || event == DBT_DEVICEREMOVECOMPLETE) {
                    if (lParam != null && lParam.longValue() != 0) {
                        Pointer p = new Pointer(lParam.longValue());
                        int devType = p.getInt(4);

                        if (devType == DBT_DEVTYP_VOLUME) {
                            DEV_BROADCAST_VOLUME vol = new DEV_BROADCAST_VOLUME(p);
                            vol.read();
                            String drives = maskToDrives(vol.dbcv_unitmask);
                            for (String drive : drives.split(",")) {
                                drive = drive.trim();
                                if (drive.isEmpty()) continue;
                                int driveType = Kernel32.INSTANCE.GetDriveType(drive + "\\");
                                String typeStr = driveType == DRIVE_REMOVABLE ? "REMOVABLE"
                                              : driveType == DRIVE_CDROM      ? "CD/DVD"
                                              :                                  "OTHER";
                                if (event == DBT_DEVICEARRIVAL) {
                                    emit("[CONNECTED] Drive: " + drive
                                            + " Type: " + typeStr
                                            + " Label: " + getDriveLabel(drive));
                                } else {
                                    emit("[DISCONNECTED] Drive: " + drive);
                                }
                            }

                        } else if (devType == DBT_DEVTYP_DEVICEINTERFACE) {
                            String devPath = p.getWideString(12);

                            if (devPath.toUpperCase().contains("{53F56307") ||
                                devPath.toUpperCase().contains("{4D36E967")) {
                                return User32.INSTANCE.DefWindowProc(h, uMsg, wParam, lParam);
                            }

                            String key = devPath.toUpperCase();

                            if (event == DBT_DEVICEARRIVAL) {
                                String vid = extractVid(devPath);
                                String pid = extractPid(devPath);
                                String friendlyName = getFriendlyName(vid, pid);
                                if (friendlyName == null) {
                                    return User32.INSTANCE.DefWindowProc(h, uMsg, wParam, lParam);
                                }
                                connectedDevices.put(key, friendlyName);
                                emit("[CONNECTED] USB Device: " + friendlyName);
                            } else {
                                String storedName = connectedDevices.remove(key);
                                if (storedName != null) {
                                    emit("[DISCONNECTED] USB Device: " + storedName);
                                }
                            }
                        }
                    }
                }
            }
            return User32.INSTANCE.DefWindowProc(h, uMsg, wParam, lParam);
        };

        WinUser.WNDCLASSEX wndClass = new WinUser.WNDCLASSEX();
        wndClass.hInstance     = hInstance;
        wndClass.lpszClassName = className;
        wndClass.lpfnWndProc   = wndProc;
        User32.INSTANCE.RegisterClassEx(wndClass);

        hwnd = User32.INSTANCE.CreateWindowEx(
                0, className, "ACS Device Monitor",
                0, 0, 0, 0, 0,
                null, null, hInstance, null);

        if (hwnd == null) {
            System.err.println("[DeviceDetector] Failed to create window: "
                    + Kernel32.INSTANCE.GetLastError());
            return;
        }

        // Register for USB device interface notifications (phones, cameras etc.)
        DEV_BROADCAST_DEVICEINTERFACE dbi = new DEV_BROADCAST_DEVICEINTERFACE();
        dbi.dbcc_devicetype = DBT_DEVTYP_DEVICEINTERFACE;
        System.arraycopy(GUID_DEVINTERFACE_WPD, 0, dbi.dbcc_classguid, 0, 16);
        dbi.dbcc_size = dbi.size();
        dbi.write();
        Pointer hNotify = User32Extra.INSTANCE.RegisterDeviceNotification(
                hwnd.getPointer(), dbi.getPointer(), DEVICE_NOTIFY_WINDOW_HANDLE);
        if (hNotify == null) {
            System.out.println("[DeviceDetector] RegisterDeviceNotification failed: "
                    + Kernel32.INSTANCE.GetLastError()
                    + " — phone detection may not work");
        }

        System.out.println("[DeviceDetector] Windows — listening for device events");

        // Message pump — blocks until stop() posts WM_QUIT
        WinUser.MSG msg = new WinUser.MSG();
        while (running && User32.INSTANCE.GetMessage(msg, null, 0, 0) != 0) {
            User32.INSTANCE.TranslateMessage(msg);
            User32.INSTANCE.DispatchMessage(msg);
        }
    }

    // ── Linux ─────────────────────────────────────────────────────────────────

    private void runLinux() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "udevadm", "monitor",
                "--kernel",
                "--subsystem-match=block",
                "--subsystem-match=usb");
        pb.redirectErrorStream(true);
        udevProcess = pb.start();

        System.out.println("[DeviceDetector] Linux — listening via udevadm");

        java.util.Set<String> seenDevices  = new java.util.HashSet<>();
        java.util.Map<String, String> deviceNames = new java.util.HashMap<>();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(udevProcess.getInputStream()))) {

            String line;
            while (running && (line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                boolean isAdd    = line.contains(" add ");
                boolean isRemove = line.contains(" remove ");
                if (!isAdd && !isRemove) continue;

                if (line.contains("(block)")) {
                    String device  = extractDevice(line);
                    String devName = device.substring(device.lastIndexOf('/') + 1);
                    if (!devName.isEmpty() && Character.isDigit(devName.charAt(devName.length() - 1))) {
                        if (isAdd) {
                            emit("[CONNECTED] USB Drive: /dev/" + devName + getMountInfo(device));
                        } else {
                            emit("[DISCONNECTED] USB Drive: /dev/" + devName);
                        }
                    }

                } else if (line.contains("(usb)")) {
                    String device = extractDevice(line);
                    if (!device.contains(":")) continue;

                    String parentPath = device.replaceAll(":[0-9]+\\.[0-9]+$", "");
                    String desc       = getUsbDescription(device);

                    if (desc.toLowerCase().contains("xhci")             ||
                        desc.toLowerCase().contains("host controller")  ||
                        desc.toLowerCase().contains("ehci")             ||
                        desc.toLowerCase().contains("ohci")             ||
                        desc.toLowerCase().contains("uhci")) continue;

                    if (isAdd) {
                        if (seenDevices.add(parentPath)) {
                            if (isUsbMassStorage(parentPath)) continue;
                            deviceNames.put(parentPath, desc);
                            emit("[CONNECTED] USB Device: " + desc);
                        }
                    } else {
                        if (seenDevices.remove(parentPath)) {
                            String storedName = deviceNames.remove(parentPath);
                            String displayName = storedName != null ? storedName : desc;
                            if (!displayName.matches(".*:[0-9]+\\.[0-9]+.*")) {
                                emit("[DISCONNECTED] USB Device: " + displayName);
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Windows helpers ───────────────────────────────────────────────────────

    private static String maskToDrives(int mask) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 26; i++) {
            if ((mask & (1 << i)) != 0) {
                if (sb.length() > 0) sb.append(",");
                sb.append((char)('A' + i)).append(":");
            }
        }
        return sb.toString();
    }

    private static String getDriveLabel(String drive) {
        try {
            char[] label = new char[256];
            Kernel32.INSTANCE.GetVolumeInformation(
                    drive + "\\", label, label.length,
                    null, null, null, null, 0);
            String result = new String(label).trim().replace("\0", "");
            return result.isEmpty() ? "(no label)" : result;
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    private static String extractVid(String devPath) {
        try {
            int vidIdx = devPath.toUpperCase().indexOf("VID_");
            if (vidIdx >= 0) return devPath.substring(vidIdx + 4, vidIdx + 8);
        } catch (Exception ignored) {}
        return "";
    }

    private static String extractPid(String devPath) {
        try {
            int pidIdx = devPath.toUpperCase().indexOf("PID_");
            if (pidIdx >= 0) return devPath.substring(pidIdx + 4, pidIdx + 8);
        } catch (Exception ignored) {}
        return "";
    }

    private static String getFriendlyName(String vid, String pid) {
        if (vid.isEmpty()) return null;
        try {
            String query = "USB\\\\VID_" + vid + "&PID_" + pid + "%";
            Process p = new ProcessBuilder("wmic", "path", "Win32_PnPEntity",
                    "where", "DeviceID like '" + query + "'",
                    "get", "Name,PNPClass", "/value")
                    .redirectErrorStream(true).start();

            String name     = null;
            String pnpClass = null;

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("Name=")     && line.length() > 5)
                        name     = line.substring(5).trim();
                    if (line.startsWith("PNPClass=") && line.length() > 9)
                        pnpClass = line.substring(9).trim().toLowerCase();
                }
            }
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);

            if (pnpClass == null) return null;

            boolean isWanted =
                    pnpClass.contains("wpd")      ||
                    pnpClass.contains("android")  ||
                    pnpClass.contains("image")    ||
                    pnpClass.contains("media")    ||
                    pnpClass.contains("camera")   ||
                    pnpClass.contains("portable");

            if (!isWanted) return null;
            if (name != null && !name.isEmpty()) return name;

        } catch (Exception ignored) {}
        return "USB Device (VID:" + vid + " PID:" + pid + ")";
    }

    // ── Linux helpers ─────────────────────────────────────────────────────────

    private static boolean isUsbMassStorage(String parentPath) {
        try {
            java.io.File sysDir   = new java.io.File("/sys" + parentPath);
            java.io.File classFile = new java.io.File(sysDir, "bInterfaceClass");
            if (!classFile.exists()) {
                classFile = new java.io.File(sysDir.getParentFile(), "bDeviceClass");
            }
            if (classFile.exists()) {
                String val = Files.readString(classFile.toPath()).trim();
                return val.equals("08");
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String getUsbDescription(String devicePath) {
        try {
            String sysPath = "/sys" + devicePath;
            java.io.File dir = new java.io.File(sysPath);
            for (int i = 0; i < 3; i++) {
                dir = dir.getParentFile();
                if (dir == null) break;
                java.io.File vendor  = new java.io.File(dir, "manufacturer");
                java.io.File product = new java.io.File(dir, "product");
                if (vendor.exists() && product.exists()) {
                    String mfr  = Files.readString(vendor.toPath()).trim();
                    String prod = Files.readString(product.toPath()).trim();
                    return mfr + " " + prod;
                }
            }
        } catch (Exception ignored) {}
        return devicePath.substring(devicePath.lastIndexOf('/') + 1);
    }

    private static String extractDevice(String line) {
        try {
            String[] parts = line.split("\\s+");
            for (String part : parts) {
                if (part.startsWith("/devices/")) return part;
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private static String getMountInfo(String devicePath) {
        try {
            String devName = devicePath.substring(devicePath.lastIndexOf('/') + 1);
            String devFile = "/dev/" + devName;
            for (String line : Files.readAllLines(Paths.get("/proc/mounts"))) {
                if (line.startsWith(devFile + " ")) {
                    String[] parts = line.split("\\s+");
                    return " → Mounted at: " + parts[1];
                }
            }
        } catch (Exception ignored) {}
        return "";
    }
}