package fitcamx.app;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

public class DebugLogger {
    private static final String TAG = "FitcamxDebug";
    private static final int MAX_MEMORY_LOGS = 2500;
    private static final ThreadLocal<SimpleDateFormat> TIME_FMT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        }
    };

    private static final LinkedList<String> logBuffer = new LinkedList<String>();
    private static volatile String appVersion = "0.7.0";

    public static String getAppVersion() {
        return appVersion;
    }

    public static synchronized void init(Context context) {
        // Reclaim internal /data flash space from legacy versions without writing to eMMC
        if (context != null) {
            try {
                android.content.pm.PackageManager pm = context.getPackageManager();
                if (pm != null) {
                    android.content.pm.PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
                    if (pi != null && pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                        appVersion = pi.versionName.trim();
                    }
                }
            } catch (Throwable ignored) {}

            try {
                File dir = context.getFilesDir();
                if (dir != null) {
                    File legacyRuntime = new File(dir, "fitcamx_runtime.log");
                    if (legacyRuntime.exists()) {
                        legacyRuntime.delete();
                    }
                    File legacyBackup = new File(dir, "fitcamx_debug_log.txt");
                    if (legacyBackup.exists()) {
                        legacyBackup.delete();
                    }
                }
            } catch (Exception ignored) {}
        }
        log("LOGGER", "DebugLogger initialized in RAM-only mode (App: fitcamx-gen5w v" + appVersion + ")");
    }

    public static void log(String tag, String message) {
        String ts = TIME_FMT.get().format(new Date());
        String entry = "[" + ts + "] [" + tag + "] " + message;
        Log.i(tag, message);

        synchronized (logBuffer) {
            logBuffer.add(entry);
            while (logBuffer.size() > MAX_MEMORY_LOGS) {
                logBuffer.removeFirst();
            }
        }
    }

    public static void log(String tag, String message, Throwable t) {
        log(tag, message + " -> Exception: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        for (StackTraceElement elem : t.getStackTrace()) {
            log(tag, "    at " + elem.toString());
        }
    }

    public static List<String> getLogs() {
        synchronized (logBuffer) {
            return new ArrayList<String>(logBuffer);
        }
    }

    public static String getLogsAsString() {
        synchronized (logBuffer) {
            StringBuilder sb = new StringBuilder();
            for (String line : logBuffer) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Active network diagnostics: scan candidate IPs and test raw TCP sockets and HTTP responses.
     */
    public static void runActiveNetworkDiagnostics(Context context, String cameraIp, String gatewayIp) {
        log("DIAG", "=== Starting Automated Dashcam Network Probe ===");

        List<String> targetIps = new ArrayList<String>();
        if (cameraIp != null && !cameraIp.isEmpty() && !cameraIp.equals("0.0.0.0") && !targetIps.contains(cameraIp)) {
            targetIps.add(cameraIp);
        }
        if (gatewayIp != null && !gatewayIp.isEmpty() && !gatewayIp.equals("0.0.0.0") && !targetIps.contains(gatewayIp)) {
            targetIps.add(gatewayIp);
        }
        if (!targetIps.contains("192.168.1.254")) targetIps.add("192.168.1.254");

        boolean foundActiveCamera = false;
        for (String ip : targetIps) {
            log("DIAG", "--- Probing Host: " + ip + " ---");
            boolean port80Open = testSocket(ip, 80, 500);
            boolean port554Open = testSocket(ip, 554, 500);
            boolean port3333Open = testSocket(ip, 3333, 500);

            if (port80Open || port554Open || port3333Open) {
                foundActiveCamera = true;
                // Read-only HTTP probes only (never mutate liveview or playback state)
                if (port80Open) {
                    probeHttp(ip, 80, "/");
                    probeHttp(ip, 80, "/?custom=1&cmd=3016");
                    probeHttp(ip, 80, "/?custom=1&cmd=3504");
                }
                if (port3333Open) {
                    probeHttp(ip, 3333, "/?custom=1&cmd=3504");
                }
                break; // Camera found and verified! No need to probe dead fallback subnets.
            }
        }

        // Only probe legacy alternative subnets if primary host completely failed to respond
        if (!foundActiveCamera) {
            String[] fallbacks = new String[] { "192.168.0.1", "192.168.1.1", "192.168.42.1" };
            for (String fallbackIp : fallbacks) {
                if (targetIps.contains(fallbackIp)) continue;
                log("DIAG", "--- Fallback Probe: " + fallbackIp + " ---");
                if (testSocket(fallbackIp, 80, 400)) {
                    probeHttp(fallbackIp, 80, "/");
                    probeHttp(fallbackIp, 80, "/?custom=1&cmd=3504");
                    break;
                }
            }
        }

        log("DIAG", "=== Automated Dashcam Network Probe Completed ===");
    }

    private static boolean testSocket(String ip, int port, int timeoutMs) {
        Socket socket = null;
        long start = System.currentTimeMillis();
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            long latency = System.currentTimeMillis() - start;
            log("DIAG_PORT", "[OPEN] " + ip + ":" + port + " connected in " + latency + "ms");
            return true;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log("DIAG_PORT", "[CLOSED] " + ip + ":" + port + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ") after " + latency + "ms");
            return false;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void probeHttp(String ip, int port, String path) {
        String urlStr = "http://" + ip + (port == 80 ? "" : ":" + port) + path;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1800);
            conn.setReadTimeout(1800);

            int code = conn.getResponseCode();
            String contentType = conn.getContentType();
            int len = conn.getContentLength();

            StringBuilder body = new StringBuilder();
            try {
                InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is), 512);
                    String line;
                    while ((line = br.readLine()) != null && body.length() < 300) {
                        body.append(line).append(" ");
                    }
                    br.close();
                }
            } catch (Exception ignored) {}

            log("DIAG_HTTP", "[HTTP " + code + "] " + urlStr + " (type=" + contentType + ", len=" + len + ") Body: " + body.toString().trim());
        } catch (Exception e) {
            log("DIAG_HTTP", "[FAIL] " + urlStr + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Gather comprehensive system, network, and environment details.
     */
    public static String dumpSystemDiagnostics(Context context) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sb.append("================================================================================\n");
        sb.append("                       FITCAMX GEN5W DEBUG REPORT                               \n");
        sb.append("================================================================================\n");
        sb.append("Timestamp:       ").append(dateFmt.format(new Date())).append("\n");
        sb.append("App Version:     ").append(appVersion).append(" (Dalvik DEX KitKat API 19)\n");
        sb.append("Device Model:    ").append(Build.MODEL).append(" (").append(Build.PRODUCT).append(")\n");
        sb.append("Manufacturer:    ").append(Build.MANUFACTURER).append("\n");
        sb.append("Hardware / Board:").append(Build.HARDWARE).append(" / ").append(Build.BOARD).append("\n");
        sb.append("Android Release: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Fingerprint:     ").append(Build.FINGERPRINT).append("\n");

        Runtime rt = Runtime.getRuntime();
        sb.append("App Memory:      Free=").append(rt.freeMemory() / 1024).append("KB | Total=").append(rt.totalMemory() / 1024).append("KB | Max=").append(rt.maxMemory() / 1024).append("KB\n");
        sb.append("\n");

        // 1. Wi-Fi & DHCP Status
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append("1. WI-FI & DHCP INFORMATION\n");
        sb.append("--------------------------------------------------------------------------------\n");
        if (context != null) {
            try {
                WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    sb.append("Wi-Fi Enabled:   ").append(wm.isWifiEnabled()).append(" (State=").append(wm.getWifiState()).append(")\n");
                    WifiInfo wi = wm.getConnectionInfo();
                    if (wi != null) {
                        sb.append("Connected SSID:  ").append(wi.getSSID()).append("\n");
                        sb.append("BSSID (MAC):     ").append(wi.getBSSID()).append("\n");
                        sb.append("Supplicant State:").append(wi.getSupplicantState()).append("\n");
                        sb.append("RSSI:            ").append(wi.getRssi()).append(" dBm\n");
                        sb.append("Link Speed:      ").append(wi.getLinkSpeed()).append(" Mbps\n");
                        sb.append("Local IP (int):  ").append(formatIp(wi.getIpAddress())).append(" (0x").append(Integer.toHexString(wi.getIpAddress())).append(")\n");
                    }

                    DhcpInfo dhcp = wm.getDhcpInfo();
                    if (dhcp != null) {
                        sb.append("DHCP Assigned IP:").append(formatIp(dhcp.ipAddress)).append("\n");
                        sb.append("DHCP Gateway:    ").append(formatIp(dhcp.gateway)).append("\n");
                        sb.append("DHCP Netmask:    ").append(formatIp(dhcp.netmask)).append("\n");
                        sb.append("DHCP Server:     ").append(formatIp(dhcp.serverAddress)).append("\n");
                        sb.append("DHCP DNS1:       ").append(formatIp(dhcp.dns1)).append("\n");
                        sb.append("DHCP DNS2:       ").append(formatIp(dhcp.dns2)).append("\n");
                        sb.append("DHCP Lease Time: ").append(dhcp.leaseDuration).append(" seconds\n");
                    }

                    try {
                        java.lang.reflect.Method isApEnabled = wm.getClass().getMethod("isWifiApEnabled");
                        sb.append("SoftAP Enabled:  ").append(isApEnabled.invoke(wm)).append("\n");
                    } catch (Exception ignored) {}
                    try {
                        java.lang.reflect.Method getApMode = wm.getClass().getMethod("getWifiApMode");
                        Object m = getApMode.invoke(wm);
                        sb.append("SoftAP Mode:     ").append(m).append(" (2=Connectivity/Projection, 1=Hotspot)\n");
                    } catch (Exception ignored) {}
                    try {
                        java.lang.reflect.Method getApConfig = wm.getClass().getMethod("getWifiApConfiguration");
                        android.net.wifi.WifiConfiguration conf = (android.net.wifi.WifiConfiguration) getApConfig.invoke(wm);
                        if (conf != null) {
                            sb.append("SoftAP SSID:     ").append(conf.SSID).append("\n");
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                sb.append("Error querying WifiManager: ").append(e.getMessage()).append("\n");
            }
        }

        // 2. Network Interfaces
        sb.append("\n--------------------------------------------------------------------------------\n");
        sb.append("2. NETWORK INTERFACES (JAVA API)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface nif = interfaces.nextElement();
                    sb.append(nif.getName()).append(" (").append(nif.getDisplayName()).append("): ");
                    sb.append("Up=").append(nif.isUp()).append(", Loopback=").append(nif.isLoopback()).append(", MTU=").append(nif.getMTU()).append("\n");
                    Enumeration<InetAddress> addrs = nif.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        sb.append("   Address: ").append(addr.getHostAddress()).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            sb.append("Error querying NetworkInterface: ").append(e.getMessage()).append("\n");
        }

        // 3. Linux Kernel Routing Table
        sb.append("\n--------------------------------------------------------------------------------\n");
        sb.append("3. LINUX ROUTING TABLE (/proc/net/route)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        readFileToBuffer(sb, "/proc/net/route");

        // 4. Linux ARP Table
        sb.append("\n--------------------------------------------------------------------------------\n");
        sb.append("4. LINUX ARP CACHE (/proc/net/arp)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        readFileToBuffer(sb, "/proc/net/arp");

        // 5. Mount Points
        sb.append("\n--------------------------------------------------------------------------------\n");
        sb.append("5. MOUNTED FILESYSTEMS (/proc/mounts)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        readFileToBuffer(sb, "/proc/mounts");

        // 6. USB Mount Probing
        sb.append("\n--------------------------------------------------------------------------------\n");
        sb.append("6. DETECTED USB STORAGE CANDIDATES\n");
        sb.append("--------------------------------------------------------------------------------\n");
        List<File> candidates = findUsbMountDirectories();
        for (File f : candidates) {
            sb.append(f.getAbsolutePath()).append(" -> exists=").append(f.exists()).append(", canWrite=").append(f.canWrite()).append(", free=").append(f.getFreeSpace() / (1024 * 1024)).append("MB\n");
        }

        // 7. Media Codecs (OpenMAX / System Decoders)
        sb.append("\n--------------------------------------------------------------------------------\n");
        sb.append("7. SYSTEM MEDIA CODECS & DECODERS (MediaCodecList)\n");
        sb.append("--------------------------------------------------------------------------------\n");
        try {
            int codecCount = android.media.MediaCodecList.getCodecCount();
            sb.append("Total registered codecs: ").append(codecCount).append("\n");
            for (int i = 0; i < codecCount; i++) {
                android.media.MediaCodecInfo info = android.media.MediaCodecList.getCodecInfoAt(i);
                String role = info.isEncoder() ? "[ENCODER]" : "[DECODER]";
                sb.append(String.format("  #%02d %-10s %-36s Types: ", i, role, info.getName()));
                String[] types = info.getSupportedTypes();
                for (int t = 0; t < types.length; t++) {
                    sb.append(types[t]);
                    if (t < types.length - 1) sb.append(", ");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            sb.append("Error querying MediaCodecList: ").append(e.getMessage()).append("\n");
        }

        // 8. Session Execution Log
        sb.append("\n================================================================================\n");
        sb.append("                       SESSION EXECUTION LOGS                                   \n");
        sb.append("================================================================================\n");
        sb.append(getLogsAsString());

        // 9. Android System Logcat (Recent 300 lines for LibVLC, SurfaceFlinger, and Stagefright)
        sb.append("\n================================================================================\n");
        sb.append("                       ANDROID SYSTEM LOGCAT (RECENT)                           \n");
        sb.append("================================================================================\n");
        Process p = null;
        BufferedReader lbr = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-t", "300"});
            lbr = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String lLine;
            while ((lLine = lbr.readLine()) != null) {
                sb.append(lLine).append("\n");
            }
        } catch (Throwable t) {
            sb.append("Failed capturing system logcat: ").append(t.getMessage()).append("\n");
        } finally {
            if (lbr != null) {
                try { lbr.close(); } catch (Exception ignored) {}
            }
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
            }
        }

        sb.append("\n=============================== END OF REPORT =================================\n");

        return sb.toString();
    }

    private static void readFileToBuffer(StringBuilder sb, String path) {
        File f = new File(path);
        if (!f.exists()) {
            sb.append(path).append(" does not exist.\n");
            return;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            sb.append("Failed reading ").append(path).append(": ").append(e.getMessage()).append("\n");
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Extracts a physical USB slot alias key (e.g. "usb0", "usb1", "udisk0") so FUSE/bind aliases
     * like /storage/usb0, /mnt/media_rw/usb0, and /mnt/usb0 collapse to a single physical drive.
     */
    private static String extractUsbSlotKey(String path) {
        if (path == null) return "";
        String norm = path.replace('\\', '/').toLowerCase(Locale.US);
        while (norm.endsWith("/") && norm.length() > 1) {
            norm = norm.substring(0, norm.length() - 1);
        }
        int slash = norm.lastIndexOf('/');
        String leaf = (slash >= 0) ? norm.substring(slash + 1) : norm;
        if (leaf.startsWith("usb") || leaf.startsWith("udisk") || leaf.startsWith("sda") || leaf.startsWith("sdb")) {
            return "slot:" + leaf;
        }
        return norm;
    }

    private static void addUniqueUsbCandidate(List<File> results, java.util.Set<String> seenKeys, File candidate) {
        if (candidate == null || !candidate.exists() || !candidate.isDirectory()) return;
        String canon;
        try {
            canon = candidate.getCanonicalPath();
        } catch (Exception e) {
            canon = candidate.getAbsolutePath();
        }
        String slotKey = extractUsbSlotKey(candidate.getAbsolutePath());
        if (seenKeys.contains(canon)) return;
        // Only collapse FUSE/media_rw slot aliases if the preferred earlier candidate is also writable
        if (slotKey.startsWith("slot:") && seenKeys.contains(slotKey)) {
            return;
        }
        seenKeys.add(canon);
        if (slotKey.startsWith("slot:") && candidate.canWrite()) {
            seenKeys.add(slotKey);
        }
        results.add(candidate);
    }

    private static boolean isLikelyExternalUsbMount(String device, String mountPoint, String fsType) {
        if (mountPoint == null || mountPoint.isEmpty()) return false;
        String mp = mountPoint.toLowerCase(Locale.US);
        if (mp.contains("emulated") || mp.contains("/self") || mp.startsWith("/data")
                || mp.startsWith("/system") || mp.startsWith("/firmware") || mp.startsWith("/persist")
                || mp.startsWith("/cache") || mp.equals("/mnt/sdcard") || mp.equals("/storage/sdcard0")) {
            return false;
        }
        if (mp.contains("usb") || mp.contains("udisk") || mp.contains("usbotg") || mp.contains("ext_sd")) {
            return true;
        }
        String dev = (device != null) ? device.toLowerCase(Locale.US) : "";
        boolean usbBlockDev = dev.contains("/vold/") || dev.startsWith("/dev/block/sd") || dev.contains("usb");
        boolean removableFs = "vfat".equals(fsType) || "exfat".equals(fsType)
                || "texfat".equals(fsType) || "ntfs".equals(fsType) || "fuseblk".equals(fsType);
        return (mp.contains("media_rw") || usbBlockDev) && removableFs;
    }

    /**
     * Find all mounted USB drives (strictly external USB media, ignoring internal/system storage,
     * and deduplicating /storage/usbN vs /mnt/media_rw/usbN aliases).
     */
    public static List<File> findUsbMountDirectories() {
        List<File> results = new ArrayList<File>();
        java.util.Set<String> seenKeys = new java.util.HashSet<String>();

        // 1. Check standard public Android Automotive / Gen5W USB mount points FIRST
        // (/storage/usb0 matches hmgapid and mod/svc/screenshot.sh directly)
        String[] standardPaths = new String[] {
            "/storage/usb0",
            "/storage/usb1",
            "/storage/usb2",
            "/mnt/media_rw/usb0",
            "/mnt/media_rw/usb1",
            "/mnt/usb0",
            "/mnt/usb1",
            "/storage/usbotg",
            "/storage/udisk",
            "/storage/udisk0",
            "/storage/udisk1",
            "/mnt/udisk",
            "/storage/ext_sd"
        };
        for (String p : standardPaths) {
            addUniqueUsbCandidate(results, seenKeys, new File(p));
        }

        // 2. Parse /proc/mounts for any non-standard external USB mount paths
        File mounts = new File("/proc/mounts");
        if (mounts.exists()) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(mounts));
                String line;
                while ((line = br.readLine()) != null) {
                    String[] tokens = line.split("\\s+");
                    if (tokens.length >= 3) {
                        String device = tokens[0];
                        String mountPoint = tokens[1];
                        String fsType = tokens[2];
                        if (isLikelyExternalUsbMount(device, mountPoint, fsType)) {
                            addUniqueUsbCandidate(results, seenKeys, new File(mountPoint));
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (br != null) {
                    try { br.close(); } catch (Exception ignored) {}
                }
            }
        }

        return results;
    }

    public static class ExportResult {
        public final boolean success;
        public final List<File> writtenFiles = new ArrayList<File>();
        public final String summary;

        public ExportResult(boolean success, List<File> writtenFiles, String summary) {
            this.success = success;
            if (writtenFiles != null) {
                this.writtenFiles.addAll(writtenFiles);
            }
            this.summary = summary;
        }
    }

    /**
     * Export full debug report and logs to attached USB drive.
     * Only executes when a writable USB drive is detected.
     */
    public static synchronized ExportResult exportToUsb(Context context) {
        List<File> candidates = findUsbMountDirectories();
        List<File> writableCandidates = new ArrayList<File>();
        for (File c : candidates) {
            if (c.exists() && c.canWrite()) {
                writableCandidates.add(c);
            }
        }

        if (writableCandidates.isEmpty()) {
            log("EXPORT", "USB Export aborted: No writable USB flash drive detected.");
            return new ExportResult(false, null, "No writable USB flash drive detected. Please insert a USB flash drive into the vehicle's USB port.");
        }

        String report = dumpSystemDiagnostics(context);
        List<File> savedFiles = new ArrayList<File>();
        String dateSuffix = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

        log("EXPORT", "Exporting debug log across " + writableCandidates.size() + " writable USB mount points...");

        for (File baseDir : writableCandidates) {
            // Path 1: Root of USB drive - fitcamx_debug_log.txt
            File f1 = new File(baseDir, "fitcamx_debug_log.txt");
            if (writeFile(f1, report)) {
                savedFiles.add(f1);
                log("EXPORT", "Successfully wrote: " + f1.getAbsolutePath() + " (" + f1.length() + " bytes)");
            }

            // Path 2: Root of USB drive with timestamp - fitcamx_log_<date>.txt
            File f2 = new File(baseDir, "fitcamx_log_" + dateSuffix + ".txt");
            if (writeFile(f2, report)) {
                savedFiles.add(f2);
            }

            // Path 3: DashcamFootage folder
            File subDir = new File(baseDir, UsbDownloader.FOLDER_NAME);
            if (!subDir.exists()) {
                subDir.mkdirs();
            }
            if (subDir.exists() && subDir.canWrite()) {
                File f3 = new File(subDir, "fitcamx_debug_log.txt");
                if (writeFile(f3, report)) {
                    savedFiles.add(f3);
                    log("EXPORT", "Successfully wrote: " + f3.getAbsolutePath());
                }
            }
        }

        if (!savedFiles.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            msg.append("Successfully exported ").append(report.length() / 1024).append(" KB debug log to:\n");
            for (File sf : savedFiles) {
                msg.append(" \u2022 ").append(sf.getAbsolutePath()).append("\n");
            }
            log("EXPORT", "USB Export SUCCESS. " + savedFiles.size() + " files created.");
            return new ExportResult(true, savedFiles, msg.toString().trim());
        } else {
            String err = "Failed writing log files to detected USB drive.";
            log("EXPORT", "USB Export FAILED: " + err);
            return new ExportResult(false, null, err);
        }
    }

    private static boolean writeFile(File dest, String content) {
        FileOutputStream fos = null;
        OutputStreamWriter writer = null;
        try {
            fos = new FileOutputStream(dest, false);
            writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write(content);
            writer.flush();
            fos.getFD().sync();
            return true;
        } catch (Exception e) {
            log("EXPORT", "Failed writing to " + dest.getAbsolutePath() + ": " + e.getMessage());
            return false;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) {}
            }
            if (fos != null) {
                try { fos.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static String formatIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }
}
