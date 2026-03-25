package com.acs;

import java.util.function.Consumer;

/**
 * Interface for platform-specific activity logging implementations.
 *
 * startLogging() now accepts a Consumer<String> callback — instead of printing
 * to stdout, implementations call  onActivity.accept(data)  for every event.
 * This makes it trivial to route data anywhere (queue, file, UI, etc.)
 * without touching the logger implementations themselves.
 */
public interface IActivityLogger {

    /**
     * Starts the activity logging loop.
     * Runs until stopLogging() is called from another thread.
     *
     * @param onActivity called for every new activity event with the formatted string
     * @throws Exception if the logger cannot initialise
     */
    void startLogging(Consumer<String> onActivity) throws Exception;

    /**
     * Signals the logging loop to stop.
     * Thread-safe — safe to call from any thread.
     */
    void stopLogging();

    /**
     * Human-readable platform name for logging/UI purposes.
     */
    String getPlatformName();
}
