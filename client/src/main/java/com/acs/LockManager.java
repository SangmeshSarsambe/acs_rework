package com.acs;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates keyboard & mouse locking with safety mechanisms:
 *  - Thread-safe lock/unlock operations
 *  - Callback for UI updates and backdoor notifications
 *  - JVM shutdown hook for emergency unlock
 *
 * No auto-reset timer — lock persists until explicit unlock, kick, or backdoor key.
 */
public class LockManager {

    public interface LockStateListener {
        void onLockStateChanged(boolean locked);
    }

    /** Called when the backdoor key combo unlocks the system */
    public interface BackdoorListener {
        void onBackdoorUnlock();
    }

    private final InputLocker locker;
    private final AtomicBoolean locked = new AtomicBoolean(false);
    private LockStateListener listener;
    private BackdoorListener backdoorListener;

    public LockManager() {
        // Pick the right platform implementation
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            this.locker = new WindowsInputLocker(this::onBackdoorTriggered);
        } else if (os.contains("nux") || os.contains("nix")) {
            this.locker = new LinuxInputLocker(this::onBackdoorTriggered);
        } else {
            throw new UnsupportedOperationException(
                "Unsupported OS: " + System.getProperty("os.name")
                + ". Only Windows and Linux are supported.");
        }

        // Safety: always unlock on JVM shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[LockManager] Shutdown hook - unlocking everything");
            forceUnlock();
        }));
    }

    public void setListener(LockStateListener listener) {
        this.listener = listener;
    }

    public void setBackdoorListener(BackdoorListener backdoorListener) {
        this.backdoorListener = backdoorListener;
    }

    /**
     * Lock both keyboard and mouse.
     * Non-blocking — hook threads start in background and return immediately.
     * Idempotent — calling lock() when already locked is a no-op.
     */
    public synchronized void lock() {
        if (locked.get()) {
            System.out.println("[LockManager] Already locked, ignoring.");
            return;
        }

        System.out.println("[LockManager] === LOCKING keyboard & mouse ===");
        System.out.println("[LockManager] Backdoor: press Ctrl+Alt+Shift+U to unlock");

        locked.set(true);
        locker.lockKeyboard();
        locker.lockMouse();

        if (listener != null) {
            listener.onLockStateChanged(true);
        }
    }

    /**
     * Unlock both keyboard and mouse.
     * Idempotent — calling unlock() when already unlocked is a no-op.
     */
    public synchronized void unlock() {
        if (!locked.get()) {
            System.out.println("[LockManager] Already unlocked, ignoring.");
            return;
        }

        System.out.println("[LockManager] === UNLOCKING keyboard & mouse ===");

        locked.set(false);
        locker.unlockKeyboard();
        locker.unlockMouse();

        if (listener != null) {
            listener.onLockStateChanged(false);
        }
    }

    /**
     * Force unlock without checking state (used in shutdown hook / kick).
     */
    public void forceUnlock() {
        locked.set(false);
        try {
            locker.unlockKeyboard();
            locker.unlockMouse();
            locker.cleanup();
        } catch (Exception e) {
            System.err.println("[LockManager] Error during force unlock: " + e.getMessage());
        }
    }

    public boolean isLocked() {
        return locked.get();
    }

    /**
     * Called by WindowsInputLocker / LinuxInputLocker when the backdoor key combo is detected.
     */
    private void onBackdoorTriggered() {
        System.out.println("[LockManager] *** BACKDOOR KEY DETECTED (Ctrl+Alt+Shift+U) ***");
        unlock();
        // Notify the backdoor listener (e.g. client sends notification to server)
        if (backdoorListener != null) {
            backdoorListener.onBackdoorUnlock();
        }
    }
}
