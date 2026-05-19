package com.acs;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinDef.*;
import com.sun.jna.platform.win32.WinUser.*;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Windows implementation of InputLocker using JNA.
 *
 * Keyboard: WH_KEYBOARD_LL hook - returns non-zero LRESULT to block all keys.
 *           Backdoor combo (Ctrl+Alt+Shift+U) is detected BEFORE blocking.
 *
 * Mouse:    WH_MOUSE_LL hook - blocks all mouse events.
 *           ClipCursor confines cursor to a 1x1 pixel rect.
 *
 * Reliability:
 *   - Uses CountDownLatch to ensure hooks are INSTALLED before returning.
 *   - Watchdog thread monitors hook health and reinstalls if Windows silently
 *     removed them (happens when JVM GC stalls the hook callback beyond the
 *     LowLevelHooksTimeout window — typically 300ms).
 */
public class WindowsInputLocker implements InputLocker {

    // ── Extended User32 with extra methods we need ───────────────────────
    public interface ExtUser32 extends StdCallLibrary {
        ExtUser32 INSTANCE = Native.load("user32", ExtUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        // Cursor confinement
        boolean ClipCursor(WinDef.RECT rect);
        boolean GetCursorPos(WinDef.POINT lpPoint);

        // Hooks
        WinUser.HHOOK SetWindowsHookEx(int idHook, HOOKPROC lpfn, HINSTANCE hMod, int dwThreadId);
        boolean UnhookWindowsHookEx(WinUser.HHOOK hhk);
        LRESULT CallNextHookEx(WinUser.HHOOK hhk, int nCode, WPARAM wParam, LPARAM lParam);

        // Message loop
        int GetMessage(WinUser.MSG lpMsg, HWND hWnd, int wMsgFilterMin, int wMsgFilterMax);
        boolean TranslateMessage(WinUser.MSG lpMsg);
        LRESULT DispatchMessage(WinUser.MSG lpMsg);
        boolean PostThreadMessage(int idThread, int Msg, WPARAM wParam, LPARAM lParam);
    }

    // ── Constants ────────────────────────────────────────────────────────
    private static final int WH_KEYBOARD_LL = 13;
    private static final int WH_MOUSE_LL    = 14;
    private static final int WM_QUIT        = 0x0012;
    private static final int WM_KEYDOWN     = 0x0100;
    private static final int WM_KEYUP       = 0x0101;
    private static final int WM_SYSKEYDOWN  = 0x0104;
    private static final int WM_SYSKEYUP    = 0x0105;

    // Virtual key codes
    private static final int VK_U       = 0x55;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_SHIFT   = 0x10;
    private static final int VK_MENU    = 0x12; // Alt key

    // Watchdog checks hook health every 3 seconds
    private static final long WATCHDOG_INTERVAL_MS = 3000;

    // ── State ────────────────────────────────────────────────────────────
    private final AtomicBoolean keyboardLocked  = new AtomicBoolean(false);
    private final AtomicBoolean mouseLocked     = new AtomicBoolean(false);
    private final AtomicBoolean touchpadKilled  = new AtomicBoolean(false);

    // Modifier tracking — set inside the hook callback, used for backdoor detection.
    private volatile boolean ctrlHeld  = false;
    private volatile boolean altHeld   = false;
    private volatile boolean shiftHeld = false;

    private volatile WinUser.HHOOK keyboardHookHandle;
    private volatile WinUser.HHOOK mouseHookHandle;

    private volatile int keyboardHookThreadId;
    private volatile int mouseHookThreadId;

    private volatile Thread keyboardHookThread;
    private volatile Thread mouseHookThread;

    // MUST keep strong references to prevent GC of native callbacks
    private volatile WinUser.LowLevelKeyboardProc keyboardProc;
    private volatile WinUser.LowLevelMouseProc mouseProc;

    // Watchdog thread that monitors hook health
    private volatile Thread watchdogThread;
    private final AtomicBoolean watchdogRunning = new AtomicBoolean(false);

    // Timestamp of last hook callback — used by watchdog to detect dead hooks
    private volatile long lastKeyboardHookCallback = 0;
    private volatile long lastMouseHookCallback     = 0;

    private final Runnable backdoorCallback;

    /**
     * @param backdoorCallback called when Ctrl+Alt+Shift+U is detected during lock
     */
    public WindowsInputLocker(Runnable backdoorCallback) {
        this.backdoorCallback = backdoorCallback;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  KEYBOARD LOCK
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void lockKeyboard() {
        if (keyboardLocked.getAndSet(true)) return;
        installKeyboardHook();
        startWatchdog();
    }

    /**
     * Installs the keyboard hook and WAITS until it's confirmed installed.
     * Returns true if hook was successfully installed, false otherwise.
     */
    private boolean installKeyboardHook() {
        // Teardown any existing hook thread first
        teardownKeyboardHook();

        CountDownLatch hookReady = new CountDownLatch(1);

        // Create the hook callback
        keyboardProc = new WinUser.LowLevelKeyboardProc() {
            @Override
            public LRESULT callback(int nCode, WPARAM wParam, WinUser.KBDLLHOOKSTRUCT lParam) {
                lastKeyboardHookCallback = System.currentTimeMillis();

                if (nCode >= 0 && keyboardLocked.get()) {
                    int msgType = wParam.intValue();
                    int vk = lParam.vkCode;
                    boolean isDown = (msgType == WM_KEYDOWN || msgType == WM_SYSKEYDOWN);

                    // Track modifier states manually — hook sees every key before blocking
                    if (vk == VK_CONTROL || vk == 0xA2 || vk == 0xA3) { // VK_LCONTROL, VK_RCONTROL
                        ctrlHeld = isDown;
                    } else if (vk == VK_MENU || vk == 0xA4 || vk == 0xA5) { // VK_LMENU, VK_RMENU
                        altHeld = isDown;
                    } else if (vk == VK_SHIFT || vk == 0xA0 || vk == 0xA1) { // VK_LSHIFT, VK_RSHIFT
                        shiftHeld = isDown;
                    }

                    // Check for backdoor combo: Ctrl+Alt+Shift+U
                    if (isDown && vk == VK_U && ctrlHeld && altHeld && shiftHeld) {
                        System.out.println("[KB-Hook] *** BACKDOOR COMBO DETECTED ***");
                        ctrlHeld = false;
                        altHeld  = false;
                        shiftHeld = false;
                        new Thread(() -> backdoorCallback.run(), "BackdoorTrigger").start();
                        return ExtUser32.INSTANCE.CallNextHookEx(keyboardHookHandle, nCode, wParam,
                                new LPARAM(Pointer.nativeValue(lParam.getPointer())));
                    }

                    // BLOCK: return non-zero to swallow the key event
                    return new LRESULT(1);
                }

                // Not locked or nCode < 0: pass through
                return ExtUser32.INSTANCE.CallNextHookEx(keyboardHookHandle, nCode, wParam,
                        new LPARAM(Pointer.nativeValue(lParam.getPointer())));
            }
        };

        // Install hook on a dedicated thread with a message loop
        Thread hookThread = new Thread(() -> {
            keyboardHookThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
            HINSTANCE hMod = Kernel32.INSTANCE.GetModuleHandle(null);

            keyboardHookHandle = ExtUser32.INSTANCE.SetWindowsHookEx(
                    WH_KEYBOARD_LL, keyboardProc, hMod, 0);

            if (keyboardHookHandle == null) {
                int err = Kernel32.INSTANCE.GetLastError();
                System.err.println("[KB-Hook] FAILED to install! Error code: " + err);
                keyboardLocked.set(false);
                hookReady.countDown(); // signal failure
                return;
            }
            System.out.println("[KB-Hook] Installed successfully (thread " + keyboardHookThreadId + ")");
            lastKeyboardHookCallback = System.currentTimeMillis();
            hookReady.countDown(); // signal success — hook is live

            // Message loop - required for low-level hooks to work
            WinUser.MSG msg = new WinUser.MSG();
            int ret;
            while ((ret = ExtUser32.INSTANCE.GetMessage(msg, null, 0, 0)) != 0) {
                if (ret == -1) break; // error
                ExtUser32.INSTANCE.TranslateMessage(msg);
                ExtUser32.INSTANCE.DispatchMessage(msg);
            }

            // Cleanup
            if (keyboardHookHandle != null) {
                ExtUser32.INSTANCE.UnhookWindowsHookEx(keyboardHookHandle);
                keyboardHookHandle = null;
            }
            System.out.println("[KB-Hook] Removed and thread exiting");
        }, "KB-HookThread");
        hookThread.setDaemon(true);
        hookThread.start();
        keyboardHookThread = hookThread;

        // WAIT until the hook is confirmed installed (up to 5 seconds)
        try {
            boolean installed = hookReady.await(5, TimeUnit.SECONDS);
            if (!installed) {
                System.err.println("[KB-Hook] Timed out waiting for hook installation!");
                return false;
            }
            return keyboardHookHandle != null;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** Tears down the keyboard hook thread cleanly. */
    private void teardownKeyboardHook() {
        if (keyboardHookThreadId != 0) {
            ExtUser32.INSTANCE.PostThreadMessage(keyboardHookThreadId, WM_QUIT,
                    new WPARAM(0), new LPARAM(0));
            keyboardHookThreadId = 0;
        }
        Thread t = keyboardHookThread;
        if (t != null && t.isAlive()) {
            try { t.join(2000); } catch (InterruptedException ignored) {}
        }
        keyboardHookThread = null;
        keyboardHookHandle = null;
    }

    @Override
    public void unlockKeyboard() {
        if (!keyboardLocked.getAndSet(false)) return;
        System.out.println("[KB-Hook] Unlocking keyboard...");
        stopWatchdog();
        teardownKeyboardHook();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  MOUSE LOCK
    // ═══════════════════════════════════════════════════════════════════

    @Override
    public void lockMouse() {
        if (mouseLocked.getAndSet(true)) return;
        disableTouchpad(); // block precision touchpad gestures
        clipCursor();
        installMouseHook();
        startWatchdog();
    }

    /** Clips the cursor to a 1x1 pixel rect at the current position. */
    private void clipCursor() {
        WinDef.POINT pt = new WinDef.POINT();
        ExtUser32.INSTANCE.GetCursorPos(pt);
        System.out.println("[Mouse] Capturing cursor at (" + pt.x + ", " + pt.y + ")");

        WinDef.RECT clipRect = new WinDef.RECT();
        clipRect.left   = pt.x;
        clipRect.top    = pt.y;
        clipRect.right  = pt.x + 1;
        clipRect.bottom = pt.y + 1;
        ExtUser32.INSTANCE.ClipCursor(clipRect);
        System.out.println("[Mouse] ClipCursor set to 1x1 pixel");
    }

    /**
     * Installs the mouse hook and WAITS until it's confirmed installed.
     */
    private boolean installMouseHook() {
        teardownMouseHook();

        CountDownLatch hookReady = new CountDownLatch(1);

        mouseProc = new WinUser.LowLevelMouseProc() {
            @Override
            public LRESULT callback(int nCode, WPARAM wParam, WinUser.MSLLHOOKSTRUCT lParam) {
                lastMouseHookCallback = System.currentTimeMillis();

                if (nCode >= 0 && mouseLocked.get()) {
                    // BLOCK: return non-zero to swallow all mouse events
                    return new LRESULT(1);
                }
                return ExtUser32.INSTANCE.CallNextHookEx(mouseHookHandle, nCode, wParam,
                        new LPARAM(Pointer.nativeValue(lParam.getPointer())));
            }
        };

        Thread hookThread = new Thread(() -> {
            mouseHookThreadId = Kernel32.INSTANCE.GetCurrentThreadId();
            HINSTANCE hMod = Kernel32.INSTANCE.GetModuleHandle(null);

            mouseHookHandle = ExtUser32.INSTANCE.SetWindowsHookEx(
                    WH_MOUSE_LL, mouseProc, hMod, 0);

            if (mouseHookHandle == null) {
                int err = Kernel32.INSTANCE.GetLastError();
                System.err.println("[Mouse-Hook] FAILED to install! Error code: " + err);
                mouseLocked.set(false);
                hookReady.countDown();
                return;
            }
            System.out.println("[Mouse-Hook] Installed successfully (thread " + mouseHookThreadId + ")");
            lastMouseHookCallback = System.currentTimeMillis();
            hookReady.countDown();

            WinUser.MSG msg = new WinUser.MSG();
            int ret;
            while ((ret = ExtUser32.INSTANCE.GetMessage(msg, null, 0, 0)) != 0) {
                if (ret == -1) break;
                ExtUser32.INSTANCE.TranslateMessage(msg);
                ExtUser32.INSTANCE.DispatchMessage(msg);
            }

            if (mouseHookHandle != null) {
                ExtUser32.INSTANCE.UnhookWindowsHookEx(mouseHookHandle);
                mouseHookHandle = null;
            }
            System.out.println("[Mouse-Hook] Removed and thread exiting");
        }, "Mouse-HookThread");
        hookThread.setDaemon(true);
        hookThread.start();
        mouseHookThread = hookThread;

        try {
            boolean installed = hookReady.await(5, TimeUnit.SECONDS);
            if (!installed) {
                System.err.println("[Mouse-Hook] Timed out waiting for hook installation!");
                return false;
            }
            return mouseHookHandle != null;
        } catch (InterruptedException e) {
            return false;
        }
    }

    /** Tears down the mouse hook thread cleanly. */
    private void teardownMouseHook() {
        if (mouseHookThreadId != 0) {
            ExtUser32.INSTANCE.PostThreadMessage(mouseHookThreadId, WM_QUIT,
                    new WPARAM(0), new LPARAM(0));
            mouseHookThreadId = 0;
        }
        Thread t = mouseHookThread;
        if (t != null && t.isAlive()) {
            try { t.join(2000); } catch (InterruptedException ignored) {}
        }
        mouseHookThread = null;
        mouseHookHandle = null;
    }

    @Override
    public void unlockMouse() {
        if (!mouseLocked.getAndSet(false)) return;
        System.out.println("[Mouse] Unlocking mouse...");
        enableTouchpad();
        stopWatchdog();

        // Release cursor clip
        ExtUser32.INSTANCE.ClipCursor(null);
        System.out.println("[Mouse] ClipCursor released");

        teardownMouseHook();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  WATCHDOG — monitors hook health and reinstalls if needed
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Starts a watchdog thread that periodically checks whether the hooks
     * are still alive. Windows can silently remove low-level hooks if the
     * callback doesn't return within the LowLevelHooksTimeout window
     * (default 300ms–5000ms depending on Windows version). This typically
     * happens during JVM GC pauses. The watchdog detects this by checking
     * if the hook thread has exited or if the hook handle has gone null,
     * and reinstalls the hooks.
     */
    private void startWatchdog() {
        if (watchdogRunning.getAndSet(true)) return; // already running

        watchdogThread = new Thread(() -> {
            System.out.println("[Watchdog] Hook health monitor started");
            while (watchdogRunning.get()) {
                try { Thread.sleep(WATCHDOG_INTERVAL_MS); } catch (InterruptedException e) { break; }
                if (!watchdogRunning.get()) break;

                // Check keyboard hook health
                if (keyboardLocked.get()) {
                    boolean kbDead = (keyboardHookHandle == null)
                            || (keyboardHookThread == null || !keyboardHookThread.isAlive());
                    if (kbDead) {
                        System.out.println("[Watchdog] Keyboard hook DEAD — reinstalling...");
                        if (installKeyboardHook()) {
                            System.out.println("[Watchdog] Keyboard hook reinstalled successfully");
                        } else {
                            System.err.println("[Watchdog] Keyboard hook reinstall FAILED");
                        }
                    }
                }

                // Check mouse hook health
                if (mouseLocked.get()) {
                    boolean mouseDead = (mouseHookHandle == null)
                            || (mouseHookThread == null || !mouseHookThread.isAlive());
                    if (mouseDead) {
                        System.out.println("[Watchdog] Mouse hook DEAD — reinstalling...");
                        // Re-clip cursor too
                        clipCursor();
                        if (installMouseHook()) {
                            System.out.println("[Watchdog] Mouse hook reinstalled successfully");
                        } else {
                            System.err.println("[Watchdog] Mouse hook reinstall FAILED");
                        }
                    }
                }
            }
            System.out.println("[Watchdog] Hook health monitor stopped");
        }, "HookWatchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    private void stopWatchdog() {
        // Only stop if BOTH keyboard and mouse are unlocked
        if (keyboardLocked.get() || mouseLocked.get()) return;

        watchdogRunning.set(false);
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            try { watchdogThread.join(2000); } catch (InterruptedException ignored) {}
            watchdogThread = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  STATUS & CLEANUP
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
        watchdogRunning.set(false);
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            try { watchdogThread.join(1000); } catch (InterruptedException ignored) {}
            watchdogThread = null;
        }
        unlockKeyboard();
        unlockMouse();
        enableTouchpad();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  TOUCHPAD GESTURE BLOCKING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Disables precision touchpad devices via PowerShell.
     * Requires admin privileges. Fails silently if not elevated.
     */
    private void disableTouchpad() {
        if (touchpadKilled.getAndSet(true)) return;
        new Thread(() -> {
            try {
                String cmd = "Get-PnpDevice -Class 'Mouse','HIDClass' -Status 'OK' "
                        + "| Where-Object { $_.FriendlyName -match 'touch.?pad' } "
                        + "| Disable-PnpDevice -Confirm:$false -ErrorAction SilentlyContinue";
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command", cmd)
                        .redirectErrorStream(true).start();
                p.waitFor();
                System.out.println("[Touchpad] Precision touchpad disabled (gesture blocking active)");
            } catch (Exception e) {
                System.out.println("[Touchpad] Could not disable touchpad: " + e.getMessage());
                System.out.println("[Touchpad] Run as admin to block touchpad gestures");
                touchpadKilled.set(false);
            }
        }, "TouchpadDisable").start();
    }

    private void enableTouchpad() {
        if (!touchpadKilled.getAndSet(false)) return;
        new Thread(() -> {
            try {
                String cmd = "Get-PnpDevice -Class 'Mouse','HIDClass' "
                        + "| Where-Object { $_.FriendlyName -match 'touch.?pad' } "
                        + "| Enable-PnpDevice -Confirm:$false -ErrorAction SilentlyContinue";
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command", cmd)
                        .redirectErrorStream(true).start();
                p.waitFor();
                System.out.println("[Touchpad] Precision touchpad re-enabled");
            } catch (Exception e) {
                System.out.println("[Touchpad] Could not re-enable touchpad: " + e.getMessage());
            }
        }, "TouchpadEnable").start();
    }
}
