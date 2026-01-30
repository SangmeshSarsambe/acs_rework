package com.acs;

import java.net.*;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Utility class for getting the actual IP address (not loopback)
 * 
 * Usage:
 *   InetAddress ip = IPAddressUtil.getActualIPAddress();
 *   String ipString = ip.getHostAddress();
 */
public class IPAddressUtil {

    /**
     * Get the actual IP address (not loopback) - works on Ubuntu and Windows
     * 
     * Tries multiple methods:
     * 1. Socket-based detection (most reliable) - connects to 8.8.8.8:80
     * 2. Network interface enumeration (fallback)
     * 3. Localhost (last resort)
     * 
     * @return InetAddress representing the actual IP address
     * @throws Exception if all methods fail
     */
    public static InetAddress getActualIPAddress() throws Exception {
        // Method 1: Try socket-based detection (most reliable)
        try (Socket socket = new Socket()) {
            // Add timeout of 2 seconds to prevent hanging
            socket.connect(new InetSocketAddress("8.8.8.8", 80), 2000);
            String ipAddress = socket.getLocalAddress().getHostAddress();
            System.out.println("[IPUtil] Using socket-based IP detection: " + ipAddress);
            return InetAddress.getByName(ipAddress);
        } catch (Exception e) {
            System.out.println("[IPUtil] Socket method failed: " + e.getMessage());
            System.out.println("[IPUtil] Trying network interface enumeration...");
            // Method 2: Fallback to network interface enumeration
            return getIPFromNetworkInterfaces();
        }
    }

    /**
     * Fallback method: Get IP by enumerating network interfaces
     * 
     * Iterates through all network interfaces and picks the first
     * non-loopback IPv4 address from an active interface
     * 
     * @return InetAddress representing the IP from network interface
     * @throws Exception if no valid interface found
     */
    private static InetAddress getIPFromNetworkInterfaces() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        
        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            
            // Skip loopback and inactive interfaces
            if (iface.isLoopback() || !iface.isUp()) {
                continue;
            }
            
            Enumeration<InetAddress> addresses = iface.getInetAddresses();
            while (addresses.hasMoreElements()) {
                InetAddress addr = addresses.nextElement();
                
                // Get IPv4 address only (not loopback)
                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                    String ipAddress = addr.getHostAddress();
                    System.out.println("[IPUtil] Using network interface: " + iface.getDisplayName() + " -> " + ipAddress);
                    return addr;
                }
            }
        }
        
        // Last resort fallback
        System.out.println("[IPUtil] Warning: Could not find non-loopback IP, using localhost");
        return InetAddress.getLocalHost();
    }

    /**
     * Get IP address as a String
     * 
     * @return IP address in dotted decimal notation (e.g., "192.168.1.100")
     * @throws Exception if IP detection fails
     */
    public static String getActualIPAddressString() throws Exception {
        return getActualIPAddress().getHostAddress();
    }

    /**
     * Validate if an IP address string is valid
     * 
     * @param ipString the IP address to validate
     * @return true if valid IP format
     */
    public static boolean isValidIPAddress(String ipString) {
        try {
            InetAddress.getByName(ipString);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Get hostname from IP address
     * 
     * @param ipAddress the IP address
     * @return hostname or IP if hostname cannot be resolved
     */
    public static String getHostnameFromIP(InetAddress ipAddress) {
        return ipAddress.getHostName();
    }
}