package com.acs;

import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Utility class for getting the actual IP address (not loopback/virtual/VPN).
 *
 * Detection order:
 *   1. Socket-based (connects to 8.8.8.8 — most reliable on internet-connected machines)
 *   2. Scored interface enumeration (skips VPN / virtual / Hamachi / VMware adapters)
 *   3. Localhost last resort
 */
public class IPAddressUtil {

    // ── Keywords that indicate a virtual / VPN / unwanted adapter ─────────────
    private static final String[] SKIP_NAMES = {
    "hamachi", "vpn", "virtual", "vmware", "vbox", "virtualbox",
    "hyper-v", "hyperv", "docker", "loopback", "pseudo", "tunnel",
    "tap", "tun", "isatap", "teredo", "6to4", "bluetooth",
    "virbr", "br-"    // ← add these for Linux KVM/bridge interfaces
};

    // ── Keywords that indicate a real physical / wireless adapter ─────────────
    private static final String[] PREFER_NAMES = {
    "ethernet", "eth", "en", "wlan", "wlp",   // ← add wlp
    "wi-fi", "wifi", "wireless",
    "local area connection", "realtek", "intel", "broadcom", "qualcomm"
};

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the best available local IPv4 address.
     */
    public static InetAddress getActualIPAddress() throws Exception {

        // ── Method 1: Socket-based (fastest, most accurate) ───────────────────
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("8.8.8.8", 80), 2000);
            String ip = socket.getLocalAddress().getHostAddress();
            System.out.println("[IPUtil] Socket-based detection → " + ip);
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            System.out.println("[IPUtil] Socket method failed: " + e.getMessage());
            System.out.println("[IPUtil] Falling back to interface scoring…");
        }

        // ── Method 2: Scored interface enumeration ────────────────────────────
        return getIPFromNetworkInterfaces();
    }

    /**
     * Scores every network interface and returns the IP of the best one.
     *
     * Scoring:
     *   +10  name matches a known physical/wireless adapter keyword
     *   -100 name matches a known virtual/VPN adapter keyword
     *   +5   address is in 192.168.x.x range  (typical home/office LAN)
     *   +4   address is in 10.x.x.x range      (corporate LAN)
     *   +3   address is in 172.16–31.x.x range (private)
     *   -50  address is in 169.254.x.x range   (APIPA — no DHCP, useless)
     */
    private static InetAddress getIPFromNetworkInterfaces() throws Exception {

        // Collect all candidates with scores
        List<ScoredAddress> candidates = new ArrayList<>();

        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        while (ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();

            // Hard skip — loopback or down
            if (iface.isLoopback() || !iface.isUp()) continue;

            String displayName = iface.getDisplayName().toLowerCase();
            String ifaceName   = iface.getName().toLowerCase();
            String combined    = displayName + " " + ifaceName;

            // Score based on adapter name
            int nameScore = 0;
            for (String bad : SKIP_NAMES) {
                if (combined.contains(bad)) {
                    nameScore -= 100;
                    System.out.println("[IPUtil] Penalising virtual/VPN adapter: " + iface.getDisplayName());
                    break;
                }
            }
            for (String good : PREFER_NAMES) {
                if (combined.contains(good)) {
                    nameScore += 10;
                    break;
                }
            }

            Enumeration<InetAddress> addrs = iface.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress addr = addrs.nextElement();

                // IPv4 only, no loopback
                if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;

                byte[] b = addr.getAddress();
                int first  = b[0] & 0xFF;
                int second = b[1] & 0xFF;

                int addrScore = nameScore;

                // Penalise APIPA (169.254.x.x) heavily — means no real network
                if (first == 169 && second == 254) {
                    addrScore -= 50;
                } else if (first == 192 && second == 168) {
                    addrScore += 5;   // most common home/office LAN
                } else if (first == 10) {
                    addrScore += 4;   // corporate LAN
                } else if (first == 172 && second >= 16 && second <= 31) {
                    addrScore += 3;   // private range
                }

                System.out.printf("[IPUtil] Candidate: %-40s ip: %-16s score: %d%n",
                        iface.getDisplayName(), addr.getHostAddress(), addrScore);

                candidates.add(new ScoredAddress(addr, addrScore));
            }
        }

        if (candidates.isEmpty()) {
            System.out.println("[IPUtil] No candidates found — falling back to localhost");
            return InetAddress.getLocalHost();
        }

        // Pick highest score
        candidates.sort((a, b) -> b.score - a.score);
        InetAddress best = candidates.get(0).address;
        System.out.println("[IPUtil] Best interface selected → " + best.getHostAddress());
        return best;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static String getActualIPAddressString() throws Exception {
        return getActualIPAddress().getHostAddress();
    }

    public static boolean isValidIPAddress(String ipString) {
        try {
            InetAddress.getByName(ipString);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public static String getHostnameFromIP(InetAddress ipAddress) {
        return ipAddress.getHostName();
    }

    // ── Inner record ──────────────────────────────────────────────────────────

    private static class ScoredAddress {
        final InetAddress address;
        final int         score;
        ScoredAddress(InetAddress a, int s) { address = a; score = s; }
    }
}