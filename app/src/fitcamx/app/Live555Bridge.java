package fitcamx.app;

import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Bridges Android's active wlan0 IPv4 address directly into live555's exported
 * ReceivingInterfaceAddr and SendingInterfaceAddr globals inside libvlc.so.
 *
 * Without this override, live555's MediaSubsession::initiate() invokes ourIPAddress(),
 * which opens a UDP multicast socket to 228.67.43.91:15947 with a 5.0-second select()
 * timeout on every SDP track — even when --rtsp-tcp is enabled. On SELinux-enabled Gen5W
 * firmwares (such as ecohmcse_us Ioniq 6 builds) transitioning out of SoftAP Mode 2,
 * multicast loopback on wlan0 is dropped, stalling RTSP setup for 10-15 seconds per URL
 * and causing subsession initialization to fail silently.
 */
public final class Live555Bridge {
    private static final String TAG = "Live555Bridge";

    private static volatile boolean loadAttempted = false;
    private static volatile boolean nativeReady = false;
    private static volatile int lastAppliedNetOrderIp = 0;

    private Live555Bridge() {}

    private static native int nativeSetInterfaceAddr(int ipv4NetOrder);
    private static native int nativeGetInterfaceAddr();

    private static synchronized boolean ensureNativeLoaded() {
        if (loadAttempted) {
            return nativeReady;
        }
        loadAttempted = true;
        try {
            try {
                System.loadLibrary("c++_shared");
            } catch (Throwable ignored) {}
            System.loadLibrary("vlc");
            System.loadLibrary("fitcamx_netfix");
            nativeReady = true;
        } catch (Throwable t) {
            nativeReady = false;
            Log.w(TAG, "libfitcamx_netfix unavailable: " + t.getMessage());
        }
        return nativeReady;
    }

    /**
     * Pure helper: converts a 4-byte big-endian IPv4 byte array (from Inet4Address.getAddress())
     * into a little-endian host int matching in_addr_t / DhcpInfo.ipAddress (e.g. 192.168.1.33 -> 0x2101a8c0).
     * Returns 0 for null, malformed, 0.0.0.0, or 127.0.0.0/8 loopback addresses.
     */
    public static int ipv4BytesToNetOrderInt(byte[] octets) {
        if (octets == null || octets.length != 4) {
            return 0;
        }
        int b0 = octets[0] & 0xFF;
        int b1 = octets[1] & 0xFF;
        int b2 = octets[2] & 0xFF;
        int b3 = octets[3] & 0xFF;
        if (b0 == 0 || b0 == 127 || (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255)) {
            return 0;
        }
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    /**
     * Pure helper: parses a dotted-quad IPv4 string ("192.168.1.33") into network-byte-order int (0x2101a8c0).
     * Returns 0 if invalid, wildcard (0.0.0.0), broadcast, or loopback (127.x.x.x).
     */
    public static int ipv4StringToNetOrderInt(String ip) {
        if (ip == null || ip.isEmpty()) {
            return 0;
        }
        String trimmed = ip.trim();
        String[] parts = trimmed.split("\\.");
        if (parts.length != 4) {
            return 0;
        }
        byte[] octets = new byte[4];
        try {
            for (int i = 0; i < 4; i++) {
                int val = Integer.parseInt(parts[i]);
                if (val < 0 || val > 255) {
                    return 0;
                }
                octets[i] = (byte) val;
            }
        } catch (NumberFormatException e) {
            return 0;
        }
        return ipv4BytesToNetOrderInt(octets);
    }

    /**
     * Pure helper: formats a network-byte-order int (0x2101a8c0) into dotted-quad ("192.168.1.33").
     */
    public static String netOrderIntToIpv4String(int netOrderIp) {
        return (netOrderIp & 0xFF) + "."
                + ((netOrderIp >>> 8) & 0xFF) + "."
                + ((netOrderIp >>> 16) & 0xFF) + "."
                + ((netOrderIp >>> 24) & 0xFF);
    }

    /**
     * Queries the kernel's live IPv4 address on wlan0 (falling back to any up wlan* interface).
     */
    public static int resolveActiveWlanIpv4NetOrder() {
        try {
            NetworkInterface wlan0 = NetworkInterface.getByName("wlan0");
            int addr = extractFirstValidIpv4(wlan0);
            if (addr != 0) {
                return addr;
            }
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            if (nifs != null) {
                while (nifs.hasMoreElements()) {
                    NetworkInterface nif = nifs.nextElement();
                    if (nif == null) continue;
                    String name = nif.getName();
                    if (name != null && name.startsWith("wlan") && nif.isUp() && !nif.isLoopback()) {
                        addr = extractFirstValidIpv4(nif);
                        if (addr != 0) {
                            return addr;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int extractFirstValidIpv4(NetworkInterface nif) {
        if (nif == null) return 0;
        try {
            Enumeration<InetAddress> addrs = nif.getInetAddresses();
            if (addrs == null) return 0;
            while (addrs.hasMoreElements()) {
                InetAddress ia = addrs.nextElement();
                if (ia instanceof Inet4Address && !ia.isLoopbackAddress()) {
                    int candidate = ipv4BytesToNetOrderInt(ia.getAddress());
                    if (candidate != 0) {
                        return candidate;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /**
     * Applies a known dashcam client IPv4 address (or live wlan0 IPv4) to live555's globals.
     */
    public static void bindInterfaceIp(String dottedIpv4) {
        int parsed = ipv4StringToNetOrderInt(dottedIpv4);
        if (parsed == 0) {
            parsed = resolveActiveWlanIpv4NetOrder();
        }
        applyNetOrderIp(parsed);
    }

    /**
     * Refreshes live555's interface binding from live wlan0 state right before opening an RTSP stream.
     * Clears any stale IP to INADDR_ANY (0) if wlan0 no longer holds the address so bind() never fails.
     */
    public static void refreshFromWlan0BeforeRtsp() {
        int liveWlanIp = resolveActiveWlanIpv4NetOrder();
        applyNetOrderIp(liveWlanIp);
    }

    /**
     * Resets live555's interface globals back to INADDR_ANY (0) upon Wi-Fi disconnect or exit.
     */
    public static void clearInterfaceBinding() {
        applyNetOrderIp(0);
    }

    private static synchronized void applyNetOrderIp(int netOrderIp) {
        if (!ensureNativeLoaded()) {
            return;
        }
        try {
            int applied = nativeSetInterfaceAddr(netOrderIp);
            if (applied != lastAppliedNetOrderIp) {
                lastAppliedNetOrderIp = applied;
                if (applied != 0) {
                    DebugLogger.log("VLC_NET", "Bound live555 RTSP interface to wlan0 IP "
                            + netOrderIntToIpv4String(applied)
                            + " (0x" + Integer.toHexString(applied) + ")");
                } else {
                    DebugLogger.log("VLC_NET", "Cleared live555 RTSP interface override (INADDR_ANY)");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyNetOrderIp error: " + t.getMessage());
        }
    }
}
