package com.acs;

/**
 * Interface for platform-specific activity logging implementations
 */
public interface IActivityLogger {
    
    /**
     * Starts the activity logging loop
     * This method should run indefinitely, polling for active window changes
     * 
     * @throws Exception if there's an error initializing or running the logger
     */
    void startLogging() throws Exception;
    
    /**
     * Gets the name/description of the platform this logger supports
     * 
     * @return platform name (e.g., "Windows", "Linux")
     */
    String getPlatformName();
}