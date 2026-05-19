package com.acs;

/**
 * Platform-agnostic interface for locking/unlocking keyboard and mouse input.
 * Each platform (Windows, Linux) provides its own implementation.
 */
public interface InputLocker {

    /**
     * Lock the keyboard so no keystrokes reach other applications.
     * The backdoor key combo (Ctrl+Alt+Shift+U) must still be detected
     * inside the hook before blocking, so the user can emergency-unlock.
     */
    void lockKeyboard();

    /**
     * Unlock the keyboard, restoring normal input.
     */
    void unlockKeyboard();

    /**
     * Lock the mouse by confining it and/or blocking mouse events.
     */
    void lockMouse();

    /**
     * Unlock the mouse, restoring normal movement and clicks.
     */
    void unlockMouse();

    /**
     * @return true if the keyboard is currently locked.
     */
    boolean isKeyboardLocked();

    /**
     * @return true if the mouse is currently locked.
     */
    boolean isMouseLocked();

    /**
     * Clean up all native resources. Called on shutdown.
     */
    void cleanup();
}
