package com.acs;

/**
 * Main entry point for the Activity Logger application.
 * Detects the operating system and launches the appropriate platform-specific logger.
 */
public class ActivityLoggerLauncher {

    public static void main(String[] args) {
        try {
           IActivityLogger logger = createLogger();
            
            System.out.println("Starting Activity Logger for " + logger.getPlatformName());
            System.out.println("Press Ctrl+C to stop logging");
            System.out.println("----------------------------------------");
            
            logger.startLogging();
            
        } catch (UnsupportedOperationException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Fatal error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * Detects the operating system and returns the appropriate logger implementation
     * 
     * @return ActivityLogger instance for the current platform
     * @throws UnsupportedOperationException if the OS is not supported
     */
    private static IActivityLogger createLogger() {
        String os = System.getProperty("os.name").toLowerCase();
        
        if (os.contains("win")) {
            return new WindowsActivityLogger();
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            return new LinuxActivityLogger();
        } else if (os.contains("mac")) {
            throw new UnsupportedOperationException(
                "macOS is not currently supported. Only Windows and Linux are supported.");
        } else {
            throw new UnsupportedOperationException(
                "Unsupported operating system: " + os + ". Only Windows and Linux are supported.");
        }
    }
    
    /**
     * Gets the current operating system name
     * 
     * @return OS name as detected by Java
     */
    public static String getOperatingSystem() {
        return System.getProperty("os.name");
    }
}