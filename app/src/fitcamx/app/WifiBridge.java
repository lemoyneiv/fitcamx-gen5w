package fitcamx.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class WifiBridge {
    private static final String TAG = "FitcamxWifi";

    public static final String DEFAULT_PASSWORD = "12345678";
    // Match any SSID containing standard dashcam tokens (e.g. CAR_CAM_*, FITCAMX_*, JsKiaEV6FITCAMX, CARDV_*, etc.)
    private static final Pattern SSID_PATTERN = Pattern.compile("(?i).*(CAR_CAM|FITCAMX|CARDV|DASHCAM|NOVATEK).*");

    // Android AP state constants (hidden in API 19)
    public static final String WIFI_AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    public static final String EXTRA_WIFI_AP_STATE = "wifi_state";
    public static final int WIFI_AP_STATE_DISABLING = 10;
    public static final int WIFI_AP_STATE_DISABLED = 11;
    public static final int WIFI_AP_STATE_ENABLING = 12;
    public static final int WIFI_AP_STATE_ENABLED = 13;
    public static final int WIFI_AP_STATE_FAILED = 14;

    public static class ProbeResult {
        public final String ip;
        public final CameraClient.SocType socType;
        public final int httpPort;
        public final String message;

        public ProbeResult(String ip, CameraClient.SocType socType, int httpPort, String message) {
            this.ip = ip;
            this.socType = socType;
            this.httpPort = httpPort;
            this.message = message;
        }
    }

    private static final String PREFS_KNOWN_CAMERA = "fitcamx_known_camera";
    private static final String KEY_KNOWN_SSID = "ssid";
    private static final String KEY_KNOWN_BSSID = "bssid";
    private static final String KEY_KNOWN_IP = "ip";
    private static final String KEY_KNOWN_SOC = "soc";
    private static final String KEY_KNOWN_PORT = "port";

    private static final long AP_DISABLE_FALLBACK_MS = 3500L;
    private static final long ASSOC_WATCHDOG_MS = 5500L;
    private static final long FAST_CONNECT_FALLBACK_MS = 8500L;
    private static final int MAX_ASSOC_WATCHDOG_RETRIES = 3;

    public static final int SEC_WPA2_CCMP = 0;
    public static final int SEC_WPA_MIXED = 1;
    public static final int SEC_OPEN = 2;

    private final Context context;
    private final WifiManager wifiManager;
    private final Handler handler;
    private final ExecutorService executor;

    // Remembered installed dashcam identity (persisted strictly on change -> 0 flash writes on repeat launches)
    private String knownSsid = "";
    private String knownBssid = "";
    private String knownCameraIp = "";
    private String knownSocName = "";
    private int knownHttpPort = 80;

    // Previous state to restore on exit
    private boolean initialStateRecorded = false;
    private boolean initialApEnabled = false;
    private int initialApMode = -1; // 0=None, 1=Consumer Hotspot, 2=Connectivity Mode (Projection)
    private WifiConfiguration initialApConfig = null;
    private boolean initialWifiEnabled = false;
    private int initialNetworkId = -1;

    // Active session state
    private int connectedNetworkId = -1;
    private boolean isRegistered = false;
    private boolean isRestoring = false;
    private boolean isConnectingToTarget = false;
    private boolean isFastConnectingSaved = false;
    private String targetSsid = null;
    private long connectAttemptStartMs = 0L;
    private int assocRetryCount = 0;
    private int scanAttempts = 0;
    private int authErrorCount = 0;
    private volatile boolean isProbingCamera = false;
    private volatile boolean lastProbeVerified = false;
    private final List<ScanResult> lastScanResults = new ArrayList<ScanResult>();

    public interface Listener {
        void onStatusChanged(String message);
        void onConnected(String cameraIp, String ssid, CameraClient.SocType socType, int httpPort, String diagDetails);
        void onDisconnected();
        void onScanResultsUpdated(List<ScanResult> results);
    }

    private Listener listener;

    public WifiBridge(Context context) {
        this.context = context.getApplicationContext();
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newCachedThreadPool();
        loadKnownCamera();
    }

    private void loadKnownCamera() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_KNOWN_CAMERA, Context.MODE_PRIVATE);
            if (prefs != null) {
                knownSsid = cleanSsid(prefs.getString(KEY_KNOWN_SSID, ""));
                knownBssid = prefs.getString(KEY_KNOWN_BSSID, "");
                knownCameraIp = prefs.getString(KEY_KNOWN_IP, "");
                knownSocName = prefs.getString(KEY_KNOWN_SOC, "");
                knownHttpPort = prefs.getInt(KEY_KNOWN_PORT, 80);
                if (!knownSsid.isEmpty()) {
                    DebugLogger.log("WIFI_CACHE", "Loaded remembered dashcam: SSID=\"" + knownSsid
                            + "\" IP=" + knownCameraIp + " SoC=" + knownSocName + " Port=" + knownHttpPort);
                }
            }
        } catch (Exception e) {
            DebugLogger.log("WIFI_CACHE", "Could not read remembered dashcam cache: " + e.getMessage());
        }
    }

    private void rememberVerifiedCamera(String ssid, String bssid, String ip, CameraClient.SocType socType, int port) {
        String cleaned = cleanSsid(ssid);
        if (cleaned.isEmpty() || ip == null || ip.isEmpty()) return;
        String safeBssid = (bssid != null) ? bssid : "";
        String socName = (socType != null) ? socType.name() : CameraClient.SocType.NOVATEK.name();
        int safePort = (port > 0) ? port : 80;

        boolean changed = !cleaned.equals(knownSsid)
                || !ip.equals(knownCameraIp)
                || !socName.equals(knownSocName)
                || (safePort != knownHttpPort);

        knownSsid = cleaned;
        if (!safeBssid.isEmpty()) {
            knownBssid = safeBssid;
        }
        knownCameraIp = ip;
        knownSocName = socName;
        knownHttpPort = safePort;

        if (!changed) {
            // Zero flash writes when reconnecting to the same physically installed dashcam!
            return;
        }

        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_KNOWN_CAMERA, Context.MODE_PRIVATE);
            if (prefs != null) {
                prefs.edit()
                        .putString(KEY_KNOWN_SSID, knownSsid)
                        .putString(KEY_KNOWN_BSSID, knownBssid)
                        .putString(KEY_KNOWN_IP, knownCameraIp)
                        .putString(KEY_KNOWN_SOC, knownSocName)
                        .putInt(KEY_KNOWN_PORT, knownHttpPort)
                        .apply();
                DebugLogger.log("WIFI_CACHE", "Persisted new dashcam identity once: SSID=\"" + knownSsid
                        + "\" IP=" + knownCameraIp + " SoC=" + knownSocName);
            }
        } catch (Exception e) {
            DebugLogger.log("WIFI_CACHE", "Notice saving dashcam identity: " + e.getMessage());
        }
    }

    private void clearKnownCameraIfMatches(String ssid) {
        String cleaned = cleanSsid(ssid);
        if (cleaned.isEmpty() || !cleaned.equalsIgnoreCase(knownSsid)) return;
        knownSsid = "";
        knownBssid = "";
        knownCameraIp = "";
        knownSocName = "";
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_KNOWN_CAMERA, Context.MODE_PRIVATE);
            if (prefs != null) {
                prefs.edit().clear().apply();
            }
        } catch (Exception ignored) {}
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Start the state machine:
     * 1. Record original state (AP mode and STA)
     * 2. Disable AP if on
     * 3. Enable STA if off
     * 4. Fast-connect to remembered/saved dashcam profile, or scan & auto-connect
     */
    public void connectToDashcam() {
        isRestoring = false;
        isConnectingToTarget = false;
        isFastConnectingSaved = false;
        targetSsid = null;
        assocRetryCount = 0;
        scanAttempts = 0;
        authErrorCount = 0;
        stopWatchdogs();

        if (!initialStateRecorded) {
            recordInitialState();
            initialStateRecorded = true;
        }
        registerReceivers();

        boolean apCurrentlyEnabled = isWifiApEnabled();
        int currentApMode = getWifiApMode();
        if (apCurrentlyEnabled) {
            notifyStatus("Preparing Wi-Fi for dashcam...");
            if (currentApMode == 2 || initialApMode == 2) {
                DebugLogger.log("WIFI", "Temporarily disabling Connectivity AP (Mode 2) for dashcam link...");
                boolean ok = setWifiApEnabledForConnectivity(null, false);
                if (!ok) {
                    setWifiApEnabled(null, false);
                }
            } else {
                DebugLogger.log("WIFI", "Temporarily disabling In-Car Wi-Fi Hotspot (Mode 1)...");
                setWifiApEnabled(null, false);
            }
            // Receiver will wait for WIFI_AP_STATE_DISABLED then call enableStationMode(); arm safety fallback
            handler.removeCallbacks(apDisableFallbackRunnable);
            handler.postDelayed(apDisableFallbackRunnable, AP_DISABLE_FALLBACK_MS);
        } else {
            enableStationMode();
        }
    }

    private final Runnable apDisableFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRestoring) return;
            if (!isWifiApEnabled()) {
                DebugLogger.log("WIFI", "AP disable fallback timer triggered enableStationMode()");
                enableStationMode();
            }
        }
    };

    private void recordInitialState() {
        try {
            initialApEnabled = isWifiApEnabled();
            initialApMode = getWifiApMode();
            initialApConfig = getWifiApConfiguration();
            initialWifiEnabled = (wifiManager != null && wifiManager.isWifiEnabled());
            WifiInfo cur = (wifiManager != null) ? wifiManager.getConnectionInfo() : null;
            initialNetworkId = (cur != null) ? cur.getNetworkId() : -1;

            String apSsid = (initialApConfig != null && initialApConfig.SSID != null) ? initialApConfig.SSID : "null";
            String modeStr = (initialApMode == 2) ? "2 (Connectivity / Projection)" :
                             (initialApMode == 1 ? "1 (In-Car Consumer Hotspot)" : String.valueOf(initialApMode));

            DebugLogger.log("WIFI_STATE", "Recorded initial state: ApEnabled=" + initialApEnabled
                    + ", ApMode=" + modeStr + ", ApSSID=" + apSsid
                    + ", WifiEnabled=" + initialWifiEnabled + ", NetworkId=" + initialNetworkId);

            // Log available AP reflection methods on WifiManager for deep diagnostic tracking
            if (wifiManager != null) {
                StringBuilder sb = new StringBuilder();
                for (Method m : wifiManager.getClass().getMethods()) {
                    if (m.getName().toLowerCase().contains("ap")) {
                        sb.append(m.getName()).append("(");
                        Class<?>[] p = m.getParameterTypes();
                        for (int i = 0; i < p.length; i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(p[i].getSimpleName());
                        }
                        sb.append("); ");
                    }
                }
                DebugLogger.log("WIFI_METHODS", "Available AP methods on WifiManager: " + sb.toString());
            }
        } catch (Exception e) {
            DebugLogger.log("WIFI_STATE", "Could not record initial Wi-Fi state: " + e.getMessage());
        }
    }

    private void enableStationMode() {
        if (wifiManager == null) {
            notifyStatus("Wi-Fi Manager unavailable on device");
            return;
        }

        int state = wifiManager.getWifiState();
        Log.i(TAG, "enableStationMode current wifiState=" + state);

        if (state == WifiManager.WIFI_STATE_ENABLED) {
            checkCurrentOrStartScan();
        } else if (state == WifiManager.WIFI_STATE_ENABLING) {
            notifyStatus("Wi-Fi hardware initializing...");
        } else {
            notifyStatus("Enabling Wi-Fi hardware...");
            boolean ok = wifiManager.setWifiEnabled(true);
            if (!ok) {
                notifyStatus("Warning: setWifiEnabled returned false");
            }
        }
    }

    private void checkCurrentOrStartScan() {
        String preferredHint = (targetSsid != null && !targetSsid.isEmpty()) ? targetSsid : knownSsid;

        // 1. Check if already associated or holding an IP on a dashcam SSID / gateway
        WifiInfo currentInfo = wifiManager.getConnectionInfo();
        if (currentInfo != null && currentInfo.getSSID() != null) {
            String ssid = cleanSsid(currentInfo.getSSID());
            if (!ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid) && !"0x".equalsIgnoreCase(ssid)) {
                DhcpInfo dhcp = wifiManager.getDhcpInfo();
                SupplicantState supState = currentInfo.getSupplicantState();
                if (isLikelyDashcamConnection(ssid, dhcp, preferredHint)) {
                    if ((dhcp != null && dhcp.ipAddress != 0) || supState == SupplicantState.COMPLETED) {
                        notifyStatus("Already connected to " + ssid + ". Verifying camera...");
                        startDhcpAndCameraProbe(ssid);
                        return;
                    }
                }
            }
        }

        // 2. Fast-Path: Direct association to saved dashcam profile (skips scan wait & avoids wpa_supplicant collision)
        if (tryFastConnectSavedCamera()) {
            return;
        }

        // 3. First-time discovery: temporarily disable non-dashcam saved networks in RAM so wpa_supplicant
        //    does not auto-associate to background networks (e.g. home/office Wi-Fi) when scan results arrive.
        disableNonDashcamProfilesInRam();
        startScanLoop();
    }

    /**
     * Pure selection helper: chooses the remembered SSID first if saved, otherwise the first saved dashcam SSID.
     */
    public static String choosePreferredSavedSsid(List<String> configuredSsids, String rememberedSsid) {
        if (configuredSsids == null || configuredSsids.isEmpty()) return null;
        String cleanRemembered = cleanSsid(rememberedSsid);
        if (!cleanRemembered.isEmpty()) {
            for (String raw : configuredSsids) {
                String c = cleanSsid(raw);
                if (!c.isEmpty() && c.equalsIgnoreCase(cleanRemembered)) {
                    return c;
                }
            }
        }
        for (String raw : configuredSsids) {
            String c = cleanSsid(raw);
            if (!c.isEmpty() && isMatchingSsid(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Pure helper: if a scan completes during saved-camera fast-connect and the saved SSID is absent
     * while a different dashcam SSID is present, returns that alternate dashcam SSID immediately.
     */
    public static String findAlternateDashcamInScan(List<String> scannedSsids, String fastConnectSsid) {
        if (scannedSsids == null || scannedSsids.isEmpty()) return null;
        String target = cleanSsid(fastConnectSsid);
        String firstAlt = null;
        for (String raw : scannedSsids) {
            String s = cleanSsid(raw);
            if (s.isEmpty()) continue;
            if (!target.isEmpty() && s.equalsIgnoreCase(target)) {
                return null; // Saved camera IS in range! Do not switch away.
            }
            if (firstAlt == null && isMatchingSsid(s)) {
                firstAlt = s;
            }
        }
        return firstAlt;
    }

    /**
     * Pure helper: classifies AP security from ScanResult.capabilities so WPA2-CCMP dashcams
     * do not get unnecessary legacy WPA1/TKIP cipher negotiation.
     */
    public static int classifySecurityMode(String capabilities) {
        if (capabilities == null || capabilities.trim().isEmpty()) {
            return SEC_WPA_MIXED;
        }
        String upper = capabilities.toUpperCase();
        boolean hasWpa2 = upper.contains("WPA2") || upper.contains("RSN");
        boolean hasWpa1 = upper.contains("WPA-PSK") || upper.contains("[WPA-");
        boolean hasWep = upper.contains("WEP");
        if (!hasWpa2 && !hasWpa1 && !hasWep && !upper.contains("PSK")) {
            return SEC_OPEN;
        }
        if (hasWpa2 && upper.contains("CCMP") && !upper.contains("TKIP") && !hasWpa1) {
            return SEC_WPA2_CCMP;
        }
        return SEC_WPA_MIXED;
    }

    /**
     * Pure helper: orders candidate camera IPs with the remembered verified IP first when on the remembered SSID.
     */
    public static List<String> buildCandidateIps(String knownIp, boolean matchesKnownSsid, String gatewayIp, boolean ssidTrusted) {
        List<String> candList = new ArrayList<String>();
        if (matchesKnownSsid && knownIp != null && !knownIp.isEmpty() && !"0.0.0.0".equals(knownIp)) {
            candList.add(knownIp);
        }
        if (gatewayIp != null && !gatewayIp.isEmpty() && !"0.0.0.0".equals(gatewayIp) && !candList.contains(gatewayIp)) {
            candList.add(gatewayIp);
        }
        if (!candList.contains(CameraClient.DEFAULT_CAMERA_IP)) {
            candList.add(CameraClient.DEFAULT_CAMERA_IP);
        }
        if (ssidTrusted) {
            if (!candList.contains("192.168.0.1")) candList.add("192.168.0.1");
            if (!candList.contains("192.168.1.1")) candList.add("192.168.1.1");
            if (!candList.contains("192.72.1.1")) candList.add("192.72.1.1");
        }
        return candList;
    }

    private boolean tryFastConnectSavedCamera() {
        List<WifiConfiguration> configured = wifiManager.getConfiguredNetworks();
        if (configured == null || configured.isEmpty()) return false;

        List<String> rawSsids = new ArrayList<String>(configured.size());
        for (WifiConfiguration cfg : configured) {
            if (cfg != null && cfg.SSID != null) {
                rawSsids.add(cfg.SSID);
            }
        }
        String chosenSsid = choosePreferredSavedSsid(rawSsids, knownSsid);
        if (chosenSsid == null) return false;

        WifiConfiguration chosenCfg = null;
        for (WifiConfiguration cfg : configured) {
            if (cfg != null && cfg.SSID != null && cleanSsid(cfg.SSID).equalsIgnoreCase(chosenSsid)) {
                chosenCfg = cfg;
                break;
            }
        }
        if (chosenCfg == null || chosenCfg.networkId == -1) return false;

        stopScanLoop();
        stopWatchdogs();
        connectedNetworkId = chosenCfg.networkId;
        targetSsid = chosenSsid;
        isConnectingToTarget = true;
        isFastConnectingSaved = true;
        connectAttemptStartMs = SystemClock.elapsedRealtime();
        assocRetryCount = 0;

        WifiInfo cur = wifiManager.getConnectionInfo();
        SupplicantState supState = (cur != null) ? cur.getSupplicantState() : null;
        String curSsid = (cur != null) ? cleanSsid(cur.getSSID()) : "";
        boolean handshakeAlreadyActive = chosenSsid.equalsIgnoreCase(curSsid) && isHandshakeInFlight(supState);

        if (!handshakeAlreadyActive) {
            // Enable ONLY the saved dashcam profile (disables background networks in RAM; ZERO flash writes!)
            boolean ok = wifiManager.enableNetwork(chosenCfg.networkId, true);
            if (supState == SupplicantState.DISCONNECTED || supState == SupplicantState.INACTIVE
                    || supState == SupplicantState.DORMANT || supState == null) {
                wifiManager.reconnect();
            }
            DebugLogger.log("WIFI_FAST", "Direct reconnect to saved dashcam \"" + chosenSsid
                    + "\" (netId=" + chosenCfg.networkId + ", enableOk=" + ok + ", supState=" + supState + ")");
        } else {
            DebugLogger.log("WIFI_FAST", "wpa_supplicant already associating with saved dashcam \""
                    + chosenSsid + "\" (state=" + supState + "); preserving active handshake");
        }

        notifyStatus("Reconnecting to " + chosenSsid + "...");
        scheduleAssocWatchdog(ASSOC_WATCHDOG_MS);
        handler.postDelayed(fastConnectFallbackRunnable, FAST_CONNECT_FALLBACK_MS);
        return true;
    }

    private static boolean isHandshakeInFlight(SupplicantState state) {
        return state == SupplicantState.ASSOCIATING
                || state == SupplicantState.ASSOCIATED
                || state == SupplicantState.AUTHENTICATING
                || state == SupplicantState.FOUR_WAY_HANDSHAKE
                || state == SupplicantState.GROUP_HANDSHAKE
                || state == SupplicantState.COMPLETED;
    }

    private void disableNonDashcamProfilesInRam() {
        try {
            List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
            if (configs == null || configs.isEmpty()) return;
            boolean disabledAny = false;
            for (WifiConfiguration cfg : configs) {
                if (cfg == null) continue;
                String s = cleanSsid(cfg.SSID);
                boolean isDashcam = isMatchingSsid(s) || (!knownSsid.isEmpty() && s.equalsIgnoreCase(knownSsid));
                if (!isDashcam) {
                    wifiManager.disableNetwork(cfg.networkId);
                    disabledAny = true;
                }
            }
            if (disabledAny) {
                WifiInfo cur = wifiManager.getConnectionInfo();
                String curSsid = (cur != null) ? cleanSsid(cur.getSSID()) : "";
                if (!isMatchingSsid(curSsid) && (knownSsid.isEmpty() || !curSsid.equalsIgnoreCase(knownSsid))) {
                    wifiManager.disconnect();
                }
            }
        } catch (Exception ignored) {}
    }

    private void scheduleAssocWatchdog(long delayMs) {
        handler.removeCallbacks(assocWatchdogRunnable);
        handler.postDelayed(assocWatchdogRunnable, delayMs);
    }

    private void stopWatchdogs() {
        handler.removeCallbacks(apDisableFallbackRunnable);
        handler.removeCallbacks(assocWatchdogRunnable);
        handler.removeCallbacks(reconnectAfterSettleRunnable);
        handler.removeCallbacks(fastConnectFallbackRunnable);
    }

    private final Runnable fastConnectFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRestoring || isProbingCamera || !isConnectingToTarget || !isFastConnectingSaved) return;
            WifiInfo cur = (wifiManager != null) ? wifiManager.getConnectionInfo() : null;
            SupplicantState st = (cur != null) ? cur.getSupplicantState() : null;
            if (isHandshakeInFlight(st) && st != SupplicantState.ASSOCIATING) {
                return;
            }
            DebugLogger.log("WIFI_FAST", "Remembered dashcam \"" + targetSsid + "\" did not respond in "
                    + FAST_CONNECT_FALLBACK_MS + "ms; falling back to active Wi-Fi scan");
            isFastConnectingSaved = false;
            isConnectingToTarget = false;
            stopWatchdogs();
            startScanLoop();
        }
    };

    private final Runnable assocWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRestoring || isProbingCamera || !isConnectingToTarget || wifiManager == null) return;

            WifiInfo cur = wifiManager.getConnectionInfo();
            SupplicantState st = (cur != null) ? cur.getSupplicantState() : null;
            if (st == SupplicantState.COMPLETED) {
                String curSsid = cleanSsid(cur.getSSID());
                if (!curSsid.isEmpty()) {
                    startDhcpAndCameraProbe(curSsid);
                }
                return;
            }
            if (st == SupplicantState.FOUR_WAY_HANDSHAKE || st == SupplicantState.GROUP_HANDSHAKE) {
                scheduleAssocWatchdog(3500L);
                return;
            }

            if (assocRetryCount >= MAX_ASSOC_WATCHDOG_RETRIES) {
                DebugLogger.log("WIFI_RETRY", "Association retries exhausted for \"" + targetSsid + "\"; resuming scan loop");
                isConnectingToTarget = false;
                isFastConnectingSaved = false;
                startScanLoop();
                return;
            }

            assocRetryCount++;
            DebugLogger.log("WIFI_RETRY", "Nudging stalled association for \"" + targetSsid
                    + "\" (retry " + assocRetryCount + "/" + MAX_ASSOC_WATCHDOG_RETRIES + ", state=" + st + ")");

            if (st == SupplicantState.ASSOCIATING || st == SupplicantState.ASSOCIATED || st == SupplicantState.AUTHENTICATING) {
                // Cleanly disconnect and give bcmdhd 400ms to finish WLC_E_DISASSOC_IND before reconnecting
                wifiManager.disconnect();
                handler.removeCallbacks(reconnectAfterSettleRunnable);
                handler.postDelayed(reconnectAfterSettleRunnable, 400L);
            } else {
                reconnectAfterSettleRunnable.run();
            }
        }
    };

    private final Runnable reconnectAfterSettleRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRestoring || isProbingCamera || !isConnectingToTarget || wifiManager == null) return;
            if (connectedNetworkId != -1) {
                connectAttemptStartMs = SystemClock.elapsedRealtime();
                wifiManager.enableNetwork(connectedNetworkId, true);
                wifiManager.reconnect();
                scheduleAssocWatchdog(ASSOC_WATCHDOG_MS);
            } else {
                isConnectingToTarget = false;
                startScanLoop();
            }
        }
    };

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRestoring || isConnectingToTarget || isProbingCamera) return;

            int state = wifiManager.getWifiState();
            if (state != WifiManager.WIFI_STATE_ENABLED) {
                notifyStatus("Waiting for Wi-Fi to be ready (state=" + state + ")...");
                handler.postDelayed(scanRunnable, 2500);
                return;
            }

            scanAttempts++;
            boolean started = wifiManager.startScan();
            Log.d(TAG, "wifiManager.startScan() -> " + started + " (attempt " + scanAttempts + ")");

            if (!started) {
                notifyStatus("Wi-Fi scan busy, retrying... (attempt " + scanAttempts + ")");
            }

            // Schedule periodic scan retry
            handler.postDelayed(scanRunnable, 4000);
        }
    };

    public void triggerManualRescan() {
        stopWatchdogs();
        if (wifiManager != null && wifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED) {
            // Always request a fresh scan in background so visible network list stays current
            wifiManager.startScan();
        }

        // Only re-probe existing connection directly if SSID matches dashcam pattern or was already verified
        WifiInfo currentInfo = (wifiManager != null) ? wifiManager.getConnectionInfo() : null;
        if (currentInfo != null && currentInfo.getSSID() != null) {
            String ssid = cleanSsid(currentInfo.getSSID());
            DhcpInfo dhcp = wifiManager.getDhcpInfo();
            boolean explicitTarget = (targetSsid != null && cleanSsid(targetSsid).equalsIgnoreCase(ssid));
            if ((isMatchingSsid(ssid) || explicitTarget || lastProbeVerified) && dhcp != null && dhcp.ipAddress != 0) {
                notifyStatus("Re-checking camera connection on " + ssid + "...");
                startDhcpAndCameraProbe(ssid);
                return;
            }
        }

        authErrorCount = 0;
        assocRetryCount = 0;
        isFastConnectingSaved = false;
        isConnectingToTarget = false;
        isProbingCamera = false;
        startScanLoop();
    }

    private void startScanLoop() {
        handler.removeCallbacks(scanRunnable);
        handler.post(scanRunnable);
    }

    private void stopScanLoop() {
        handler.removeCallbacks(scanRunnable);
    }

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            if (WIFI_AP_STATE_CHANGED_ACTION.equals(action)) {
                int apState = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1);
                Log.d(TAG, "Broadcast: WIFI_AP_STATE_CHANGED -> " + apState);
                if (apState == WIFI_AP_STATE_DISABLED && !isRestoring) {
                    handler.removeCallbacks(apDisableFallbackRunnable);
                    enableStationMode();
                }
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                Log.d(TAG, "Broadcast: WIFI_STATE_CHANGED -> " + wifiState);
                if (wifiState == WifiManager.WIFI_STATE_ENABLED && !isRestoring) {
                    notifyStatus("Wi-Fi active. Connecting to dashcam...");
                    checkCurrentOrStartScan();
                } else if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                    if (!isRestoring) {
                        notifyStatus("Wi-Fi is currently disabled.");
                    }
                }
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                handleScanResults();
            } else if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)) {
                SupplicantState state = intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE);
                handleSupplicantState(state, intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1));
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo netInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (netInfo != null && netInfo.isConnected() && !isRestoring) {
                    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                    if (wifiInfo != null && wifiInfo.getSSID() != null) {
                        String ssid = cleanSsid(wifiInfo.getSSID());
                        DhcpInfo dhcp = wifiManager.getDhcpInfo();
                        String hint = (targetSsid != null && !targetSsid.isEmpty()) ? targetSsid : knownSsid;
                        if (isLikelyDashcamConnection(ssid, dhcp, hint)) {
                            startDhcpAndCameraProbe(ssid);
                        }
                    }
                }
            }
        }
    };

    private void handleScanResults() {
        List<ScanResult> results = wifiManager.getScanResults();
        lastScanResults.clear();
        if (results != null) {
            lastScanResults.addAll(results);
        }

        if (listener != null) {
            listener.onScanResultsUpdated(lastScanResults);
        }

        if (isRestoring || isProbingCamera) {
            return;
        }
        if (isConnectingToTarget) {
            if (isFastConnectingSaved && targetSsid != null && !lastScanResults.isEmpty()) {
                List<String> scannedNames = new ArrayList<String>(lastScanResults.size());
                boolean savedVisible = false;
                for (ScanResult r : lastScanResults) {
                    if (r.SSID != null) {
                        scannedNames.add(r.SSID);
                        if (cleanSsid(r.SSID).equalsIgnoreCase(cleanSsid(targetSsid))) {
                            savedVisible = true;
                        }
                    }
                }
                if (savedVisible) {
                    DebugLogger.log("WIFI_FAST", "Saved dashcam \"" + targetSsid + "\" confirmed in range; continuing direct association");
                    handler.removeCallbacks(fastConnectFallbackRunnable);
                    WifiInfo cur = wifiManager.getConnectionInfo();
                    SupplicantState st = (cur != null) ? cur.getSupplicantState() : null;
                    if (st == SupplicantState.DISCONNECTED || st == SupplicantState.INACTIVE) {
                        wifiManager.enableNetwork(connectedNetworkId, true);
                        wifiManager.reconnect();
                    }
                } else {
                    String altSsid = findAlternateDashcamInScan(scannedNames, targetSsid);
                    if (altSsid != null) {
                        ScanResult altScan = findScanResultBySsid(altSsid);
                        DebugLogger.log("WIFI_FAST", "Saved dashcam \"" + targetSsid + "\" absent, found \"" + altSsid + "\"; switching target");
                        stopWatchdogs();
                        isFastConnectingSaved = false;
                        targetSsid = altSsid;
                        assocRetryCount = 0;
                        notifyStatus("Found " + altSsid + ". Connecting...");
                        connectToSsid(altSsid, DEFAULT_PASSWORD, altScan != null ? altScan.capabilities : null, false);
                    }
                }
            }
            return;
        }
        WifiInfo curInfo = (wifiManager != null) ? wifiManager.getConnectionInfo() : null;
        if (curInfo != null && curInfo.getSSID() != null && lastProbeVerified) {
            String curSsid = cleanSsid(curInfo.getSSID());
            if (isMatchingSsid(curSsid) || (targetSsid != null && cleanSsid(targetSsid).equalsIgnoreCase(curSsid))) {
                return;
            }
        }

        if (lastScanResults.isEmpty()) {
            DebugLogger.log("WIFI_SCAN", "Scan #" + scanAttempts + ": 0 networks in range");
            notifyStatus("Scan #" + scanAttempts + ": 0 networks in range. Searching...");
            return;
        }

        // Build list of visible SSIDs for diagnostic feedback, preferring remembered dashcam if visible
        StringBuilder sb = new StringBuilder();
        ScanResult rememberedMatch = null;
        ScanResult firstDashcamMatch = null;

        for (int i = 0; i < lastScanResults.size(); i++) {
            ScanResult r = lastScanResults.get(i);
            String name = (r.SSID != null) ? r.SSID : "<Hidden>";
            DebugLogger.log("WIFI_SCAN", "Found SSID: \"" + name + "\" BSSID=" + r.BSSID + " Level=" + r.level + "dBm Freq=" + r.frequency + "MHz Caps=" + r.capabilities);
            if (i < 4) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(name);
            }
            if (!knownSsid.isEmpty() && rememberedMatch == null && r.SSID != null
                    && cleanSsid(r.SSID).equalsIgnoreCase(knownSsid)) {
                rememberedMatch = r;
            }
            if (firstDashcamMatch == null && isMatchingSsid(r.SSID)) {
                firstDashcamMatch = r;
            }
        }

        ScanResult targetMatch = (rememberedMatch != null) ? rememberedMatch : firstDashcamMatch;
        if (targetMatch != null) {
            stopScanLoop();
            targetSsid = targetMatch.SSID;
            isConnectingToTarget = true;
            isFastConnectingSaved = false;
            assocRetryCount = 0;
            DebugLogger.log("WIFI_SCAN", "Matched target dashcam: " + targetMatch.SSID + " (" + targetMatch.level + " dBm)");
            notifyStatus("Found " + targetMatch.SSID + " (" + targetMatch.level + " dBm). Connecting...");
            connectToSsid(targetMatch.SSID, DEFAULT_PASSWORD, targetMatch.capabilities, false);
        } else {
            notifyStatus("Found " + lastScanResults.size() + " networks [" + sb.toString() + "]. Searching for dashcam...");
        }
    }

    private ScanResult findScanResultBySsid(String ssid) {
        String target = cleanSsid(ssid);
        for (ScanResult r : lastScanResults) {
            if (r != null && r.SSID != null && cleanSsid(r.SSID).equalsIgnoreCase(target)) {
                return r;
            }
        }
        return null;
    }

    public void connectToSpecificSsid(String ssid, String password) {
        stopScanLoop();
        stopWatchdogs();
        targetSsid = ssid;
        isConnectingToTarget = true;
        isFastConnectingSaved = false;
        authErrorCount = 0;
        assocRetryCount = 0;
        notifyStatus("Connecting to " + ssid + "...");
        ScanResult sr = findScanResultBySsid(ssid);
        connectToSsid(ssid, password != null ? password : DEFAULT_PASSWORD,
                sr != null ? sr.capabilities : null, password != null);
    }

    private void connectToSsid(String ssid, String password, String capabilities, boolean forceUpdateCredentials) {
        int netId = -1;
        String quotedSsid = "\"" + cleanSsid(ssid) + "\"";
        List<WifiConfiguration> list = wifiManager.getConfiguredNetworks();
        if (list != null) {
            for (WifiConfiguration i : list) {
                if (i != null && i.SSID != null && i.SSID.equalsIgnoreCase(quotedSsid)) {
                    netId = i.networkId;
                    break;
                }
            }
        }

        boolean needsSave = false;
        if (netId == -1 || forceUpdateCredentials) {
            WifiConfiguration conf = new WifiConfiguration();
            conf.SSID = quotedSsid;
            conf.priority = 100;
            conf.status = WifiConfiguration.Status.ENABLED;

            int secMode = classifySecurityMode(capabilities);
            if (secMode == SEC_OPEN) {
                conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else if (secMode == SEC_WPA2_CCMP) {
                conf.preSharedKey = "\"" + password + "\"";
                conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                conf.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            } else {
                conf.preSharedKey = "\"" + password + "\"";
                conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                conf.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                conf.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
                conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
                conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
                conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
            }

            if (netId != -1) {
                conf.networkId = netId;
                int updated = wifiManager.updateNetwork(conf);
                if (updated != -1) {
                    netId = updated;
                }
            } else {
                netId = wifiManager.addNetwork(conf);
            }
            needsSave = (netId != -1);
        }

        if (netId != -1) {
            connectedNetworkId = netId;
            if (needsSave) {
                // Only write wpa_supplicant.conf when a brand-new profile is created or password updated!
                wifiManager.saveConfiguration();
            }
            connectAttemptStartMs = SystemClock.elapsedRealtime();
            boolean ok = wifiManager.enableNetwork(netId, true);
            WifiInfo cur = wifiManager.getConnectionInfo();
            SupplicantState st = (cur != null) ? cur.getSupplicantState() : null;
            if (st == SupplicantState.DISCONNECTED || st == SupplicantState.INACTIVE
                    || st == SupplicantState.DORMANT || st == null) {
                wifiManager.reconnect();
            }
            scheduleAssocWatchdog(ASSOC_WATCHDOG_MS);
            notifyStatus("Associating with " + ssid + " (network ID " + netId + ")...");
            Log.i(TAG, "wifiManager.enableNetwork(" + netId + ") returned " + ok + " (saved=" + needsSave + ")");
        } else {
            isConnectingToTarget = false;
            notifyStatus("Failed to configure Wi-Fi profile for " + ssid);
            startScanLoop();
        }
    }

    private void handleSupplicantState(SupplicantState state, int error) {
        if (state == null || isRestoring) return;
        Log.d(TAG, "SupplicantState=" + state + ", error=" + error);

        if (error == WifiManager.ERROR_AUTHENTICATING) {
            authErrorCount++;
            Log.w(TAG, "Supplicant reported auth error (count=" + authErrorCount + ")");
            if (authErrorCount >= 2) {
                stopWatchdogs();
                isConnectingToTarget = false;
                isFastConnectingSaved = false;
                if (connectedNetworkId != -1) {
                    try {
                        wifiManager.disableNetwork(connectedNetworkId);
                        wifiManager.removeNetwork(connectedNetworkId);
                        wifiManager.saveConfiguration();
                    } catch (Exception ignored) {}
                    connectedNetworkId = -1;
                }
                if (targetSsid != null) {
                    clearKnownCameraIfMatches(targetSsid);
                }
                notifyStatus("Authentication failed for " + (targetSsid != null ? targetSsid : "dashcam") + ". Please check camera password.");
            }
            return;
        }

        switch (state) {
            case ASSOCIATING:
                notifyStatus("Associating with " + (targetSsid != null ? targetSsid : "camera") + "...");
                break;
            case ASSOCIATED:
            case AUTHENTICATING:
                notifyStatus("Authenticating credentials...");
                if (isConnectingToTarget) {
                    scheduleAssocWatchdog(4000L);
                }
                break;
            case FOUR_WAY_HANDSHAKE:
            case GROUP_HANDSHAKE:
                notifyStatus("Verifying encryption keys...");
                if (isConnectingToTarget) {
                    scheduleAssocWatchdog(4000L);
                }
                break;
            case COMPLETED:
                authErrorCount = 0;
                assocRetryCount = 0;
                isFastConnectingSaved = false;
                stopWatchdogs();
                notifyStatus("Wi-Fi handshake complete. Requesting IP address (DHCP)...");
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null && info.getSSID() != null) {
                    String connectedSsid = cleanSsid(info.getSSID());
                    String hint = (targetSsid != null && !targetSsid.isEmpty()) ? targetSsid : knownSsid;
                    if (!connectedSsid.isEmpty() && (isMatchingSsid(connectedSsid)
                            || (!hint.isEmpty() && hint.equalsIgnoreCase(connectedSsid)))) {
                        // Start polling DHCP immediately without waiting ~4.5s for ConnectivityService broadcast!
                        startDhcpAndCameraProbe(connectedSsid);
                    }
                }
                break;
            case DISCONNECTED:
                if (isConnectingToTarget && authErrorCount == 0) {
                    notifyStatus("Wi-Fi negotiating, waiting for link...");
                    long elapsed = SystemClock.elapsedRealtime() - connectAttemptStartMs;
                    if (elapsed >= 1500L && assocRetryCount < MAX_ASSOC_WATCHDOG_RETRIES) {
                        // Bypass wpa_supplicant's 6s-22s blacklist backoff after a 650ms driver settle delay
                        scheduleAssocWatchdog(650L);
                    }
                }
                break;
            default:
                break;
        }
    }

    /**
     * Poll DHCP asynchronously until an IP address is leased, then probe the camera.
     */
    private void startDhcpAndCameraProbe(final String ssid) {
        stopScanLoop();
        stopWatchdogs();
        isConnectingToTarget = false;
        isFastConnectingSaved = false;
        if (isProbingCamera || executor.isShutdown()) return;
        isProbingCamera = true;

        final String cleanedSsid = cleanSsid(ssid);
        final boolean matchesKnownSsid = (!knownSsid.isEmpty() && knownSsid.equalsIgnoreCase(cleanedSsid));
        final boolean ssidTrusted = isMatchingSsid(cleanedSsid)
                || matchesKnownSsid
                || (targetSsid != null && cleanSsid(targetSsid).equalsIgnoreCase(cleanedSsid));

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String myIp = null;
                    String gatewayIp = null;
                    String netmask = null;
                    String activeBssid = null;

                    // Poll DHCP every 150ms (up to ~10.5s) so camera probe fires the instant dhcpcd assigns lease
                    for (int attempt = 0; attempt < 70; attempt++) {
                        if (isRestoring) {
                            isProbingCamera = false;
                            return;
                        }
                        WifiInfo cur = wifiManager.getConnectionInfo();
                        boolean rightNet = true;
                        if (cur != null && cur.getSSID() != null) {
                            String curSsid = cleanSsid(cur.getSSID());
                            if (!curSsid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(curSsid) && !"0x".equalsIgnoreCase(curSsid)) {
                                rightNet = curSsid.equalsIgnoreCase(cleanedSsid)
                                        && cur.getSupplicantState() == SupplicantState.COMPLETED;
                            }
                            activeBssid = cur.getBSSID();
                        }
                        DhcpInfo dhcp = wifiManager.getDhcpInfo();
                        if (rightNet && dhcp != null && dhcp.ipAddress != 0) {
                            myIp = intToIp(dhcp.ipAddress);
                            gatewayIp = (dhcp.gateway != 0) ? intToIp(dhcp.gateway) : CameraClient.DEFAULT_CAMERA_IP;
                            netmask = intToIp(dhcp.netmask);
                            DebugLogger.log("WIFI_DHCP", "Acquired IP=" + myIp + " Gateway=" + gatewayIp + " Netmask=" + netmask + " DNS1=" + intToIp(dhcp.dns1) + " Server=" + intToIp(dhcp.serverAddress));
                            break;
                        }
                        if (attempt % 7 == 0) {
                            notifyStatus("Acquiring IP address via DHCP...");
                        }
                        Thread.sleep(150);
                    }

                    if (myIp == null) {
                        DebugLogger.log("WIFI_DHCP", "DHCP timeout: Wi-Fi connected to " + cleanedSsid + ", but no IP address assigned.");
                        notifyStatus("DHCP timeout: Wi-Fi connected to " + cleanedSsid + ", but no IP address assigned yet.");
                        isProbingCamera = false;
                        return;
                    }

                    notifyStatus("Leased IP: " + myIp + " | Gateway: " + gatewayIp + " | Probing dashcam...");

                    List<String> candList = buildCandidateIps(knownCameraIp, matchesKnownSsid, gatewayIp, ssidTrusted);
                    String preferredSoc = matchesKnownSsid ? knownSocName : "";

                    ProbeResult bestProbe = null;
                    StringBuilder probeLog = new StringBuilder();

                    for (String ip : candList) {
                        ProbeResult res = probeCameraIp(ip, ssidTrusted, preferredSoc, matchesKnownSsid ? knownHttpPort : 80);
                        if (res != null) {
                            bestProbe = res;
                            probeLog.append(ip).append(" -> ").append(res.socType.displayName).append("; ");
                            DebugLogger.log("CAM_PROBE", "Host " + ip + " answered: " + res.socType.displayName + " on port " + res.httpPort);
                            break;
                        } else {
                            probeLog.append(ip).append(" (no response); ");
                            DebugLogger.log("CAM_PROBE", "Host " + ip + " gave no valid HTTP camera response");
                        }
                    }

                    if (bestProbe == null && !ssidTrusted) {
                        lastProbeVerified = false;
                        DebugLogger.log("CAM_PROBE", "Connected Wi-Fi \"" + cleanedSsid + "\" is not a dashcam; falling back to Wi-Fi scan.");
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                isProbingCamera = false;
                                notifyStatus("Current Wi-Fi (" + cleanedSsid + ") is not a dashcam. Scanning...");
                                startScanLoop();
                            }
                        });
                        return;
                    }

                    lastProbeVerified = (bestProbe != null);

                    final String finalCameraIp;
                    final CameraClient.SocType finalSocType;
                    final int finalHttpPort;
                    final String diagMsg;

                    if (bestProbe != null) {
                        finalCameraIp = bestProbe.ip;
                        finalSocType = bestProbe.socType;
                        finalHttpPort = bestProbe.httpPort;
                        diagMsg = "Dashcam online: " + finalSocType.displayName + " at " + finalCameraIp;
                        rememberVerifiedCamera(cleanedSsid, activeBssid, finalCameraIp, finalSocType, finalHttpPort);
                    } else {
                        finalCameraIp = (gatewayIp != null && !gatewayIp.equals("0.0.0.0")) ? gatewayIp : CameraClient.DEFAULT_CAMERA_IP;
                        finalSocType = CameraClient.SocType.NOVATEK; // fallback to Novatek for known dashcam SSID
                        finalHttpPort = 80;
                        diagMsg = "No HTTP response; assuming " + finalCameraIp + ". [" + probeLog.toString() + "]";
                    }

                    final String fullDetails = "Local IP: " + myIp + " | Netmask: " + netmask + " | GW: " + gatewayIp + " | Camera: " + finalSocType.displayName + " (" + finalCameraIp + ")";
                    DebugLogger.log("WIFI_CONN", fullDetails);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            isProbingCamera = false;
                            if (isRestoring) return;
                            notifyStatus(diagMsg);
                            if (listener != null) {
                                listener.onConnected(finalCameraIp, cleanedSsid, finalSocType, finalHttpPort, fullDetails);
                            }
                        }
                    });

                } catch (Exception e) {
                    Log.e(TAG, "Error in DHCP & Camera Probe", e);
                    isProbingCamera = false;
                }
            }
        });
    }

    private ProbeResult probeNovatekEndpoint(String ip, int port, String cmd, int timeoutMs, boolean allowEmptyBody) {
        HttpURLConnection conn = null;
        try {
            String portPart = (port == 80) ? "" : (":" + port);
            URL url = new URL("http://" + ip + portPart + "/?custom=1&cmd=" + cmd);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            DebugLogger.log("CAM_PROBE", "GET " + url + " -> " + code);
            if (code == 200) {
                String body = readShortBody(conn);
                String lower = body.toLowerCase();
                boolean isHtml = lower.contains("<html") || lower.contains("<!doctype html");
                boolean isXml = body.contains("<Function>") || body.contains("<Cmd>") || body.contains("<Status>") || lower.contains("<?xml");
                if (!isHtml && (isXml || allowEmptyBody)) {
                    DebugLogger.log("CAM_PROBE", "Novatek verified on " + ip + ":" + port + " via cmd=" + cmd);
                    return new ProbeResult(ip, CameraClient.SocType.NOVATEK, port, "Novatek NT966xx verified via cmd=" + cmd);
                }
            }
        } catch (Exception e) {
            DebugLogger.log("CAM_PROBE", ip + ":" + port + " cmd=" + cmd + " error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private ProbeResult probeJieLiEndpoint(String ip, boolean allowGenericFallback) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + ":8080/mnt/spiflash/res/dev_desc.txt");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            DebugLogger.log("CAM_PROBE", "GET " + url + " -> " + code);
            if (code == 200 || (allowGenericFallback && code == 404)) {
                DebugLogger.log("CAM_PROBE", "JieLi detected on " + ip + ":8080");
                return new ProbeResult(ip, CameraClient.SocType.JIELI, 8080, "JieLi AC5x/AC7x verified on port 8080");
            }
        } catch (Exception e) {
            DebugLogger.log("CAM_PROBE", ip + ":8080 error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private ProbeResult probeICatchEndpoint(String ip, boolean allowGenericFallback) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + ip + "/cgi-bin/Config.cgi?action=get&property=Camera.Menu.*");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            String body = (code == 200) ? readShortBody(conn) : "";
            DebugLogger.log("CAM_PROBE", "GET " + url + " -> " + code);
            boolean looksLikeHtml = body.toLowerCase().contains("<html");
            if ((code == 200 && !looksLikeHtml) || (allowGenericFallback && code == 401)) {
                DebugLogger.log("CAM_PROBE", "iCatch detected on " + ip);
                return new ProbeResult(ip, CameraClient.SocType.ICATCH, 80, "iCatch / AIT verified via Config.cgi");
            }
        } catch (Exception e) {
            DebugLogger.log("CAM_PROBE", ip + " Config.cgi error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String readShortBody(HttpURLConnection conn) {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            InputStream is = conn.getInputStream();
            if (is != null) {
                br = new BufferedReader(new InputStreamReader(is), 512);
                char[] buf = new char[512];
                int n = br.read(buf);
                if (n > 0) {
                    sb.append(buf, 0, n);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
        }
        return sb.toString();
    }

    /**
     * Test HTTP response on candidate IP and identify camera chipset, trying remembered SoC first if known.
     */
    private ProbeResult probeCameraIp(String ip, boolean allowGenericFallback, String preferredSoc, int preferredPort) {
        if (ip == null || ip.isEmpty() || ip.equals("0.0.0.0")) return null;

        // Fast-path for remembered non-Novatek SoCs (avoids 4.5s of Novatek port timeouts on JieLi/iCatch)
        if ("JIELI".equals(preferredSoc)) {
            ProbeResult fastJl = probeJieLiEndpoint(ip, allowGenericFallback);
            if (fastJl != null) return fastJl;
        } else if ("ICATCH".equals(preferredSoc)) {
            ProbeResult fastIc = probeICatchEndpoint(ip, allowGenericFallback);
            if (fastIc != null) return fastIc;
        } else if ("NOVATEK".equals(preferredSoc) && preferredPort == 3333) {
            ProbeResult fastNtk3333 = probeNovatekEndpoint(ip, 3333, "3504", 1200, allowGenericFallback);
            if (fastNtk3333 != null) return fastNtk3333;
        }

        // 1. Probe Novatek official detection (cmd=3504) and heartbeat (cmd=3016) on port 80
        ProbeResult r = probeNovatekEndpoint(ip, 80, "3504", 1500, allowGenericFallback);
        if (r != null) return r;

        r = probeNovatekEndpoint(ip, 80, "3016", 1200, allowGenericFallback);
        if (r != null) return r;

        // 2. Probe Novatek alternate HTTP port 3333
        r = probeNovatekEndpoint(ip, 3333, "3504", 1000, allowGenericFallback);
        if (r != null) return r;

        // 3. Probe JieLi port 8080
        r = probeJieLiEndpoint(ip, allowGenericFallback);
        if (r != null) return r;

        // 4. Probe iCatch / AIT Config.cgi
        r = probeICatchEndpoint(ip, allowGenericFallback);
        if (r != null) return r;

        // 5. Fallback test: ping root HTTP (ONLY when SSID explicitly matches dashcam or user-selected network)
        if (allowGenericFallback) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("http://" + ip + "/");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Connection", "close");
                int code = conn.getResponseCode();
                DebugLogger.log("CAM_PROBE", "GET " + url + " -> " + code);
                if (code == 200 || code == 401 || code == 404) {
                    return new ProbeResult(ip, CameraClient.SocType.UNKNOWN, 80, "Generic HTTP responding on port 80");
                }
            } catch (Exception e) {
                DebugLogger.log("CAM_PROBE", ip + " root HTTP error: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        return null;
    }

    /**
     * Temporarily restore original Wi-Fi / SoftAP state when the Activity moves to the background,
     * while keeping the executor alive so connectToDashcam() can seamlessly reconnect on foreground resume.
     */
    public void suspendForBackground() {
        restoreWifiStateInternal("background suspend");
    }

    /**
     * Restore the Wi-Fi state to exactly whatever it was before the app launched.
     */
    public void restoreAndExit() {
        restoreWifiStateInternal("exit");
    }

    public void shutdown() {
        stopScanLoop();
        stopWatchdogs();
        unregisterReceivers();
        executor.shutdownNow();
    }

    private void restoreWifiStateInternal(String reason) {
        if (isRestoring) return;
        isRestoring = true;
        isProbingCamera = false;
        isConnectingToTarget = false;
        isFastConnectingSaved = false;
        lastProbeVerified = false;

        stopScanLoop();
        stopWatchdogs();
        unregisterReceivers();
        notifyStatus("Restoring previous Wi-Fi state...");
        DebugLogger.log("WIFI_RESTORE", "Restoring Wi-Fi state (reason=" + reason + ")...");

        // Disconnect from dashcam WITHOUT deleting the saved wpa_supplicant profile so future launches
        // fast-connect immediately and avoid unnecessary /data/misc/wifi/wpa_supplicant.conf flash writes.
        int dashcamNetId = connectedNetworkId;
        if (dashcamNetId != -1) {
            try {
                wifiManager.disableNetwork(dashcamNetId);
                wifiManager.disconnect();
                DebugLogger.log("WIFI_RESTORE", "Disconnected dashcam network profile (retained id=" + dashcamNetId + " for fast reconnect)");
            } catch (Exception ignored) {}
            connectedNetworkId = -1;
        }

        // Return to initial state
        try {
            if (initialApEnabled) {
                // If initial state was Mode 2 (Connectivity Mode for wireless phone projection)
                if (initialApMode == 2) {
                    DebugLogger.log("WIFI_RESTORE", "Restoring Mode 2 (Connectivity Mode for wireless projection)...");
                    WifiConfiguration conf = (initialApConfig != null) ? initialApConfig : getWifiApConfiguration();
                    boolean ok = setWifiApEnabledForConnectivity(conf, true);
                    DebugLogger.log("WIFI_RESTORE", "setWifiApEnabledForConnectivity returned " + ok);
                    if (!ok) {
                        DebugLogger.log("WIFI_RESTORE", "Falling back to standard setWifiApEnabled...");
                        setWifiApEnabled(conf, true);
                    }
                } else {
                    // Initial state was Mode 1 (Consumer In-Car Hotspot)
                    DebugLogger.log("WIFI_RESTORE", "Restoring Mode 1 (In-Car Wi-Fi Hotspot)...");
                    setWifiApEnabled(initialApConfig, true);
                }
            } else if (initialWifiEnabled) {
                // Was standard Wi-Fi station mode: re-enable non-dashcam profiles & keep dashcam disabled
                // so the head unit does not auto-roam onto the no-internet dashcam outside FITCAMX.
                DebugLogger.log("WIFI_RESTORE", "Restoring standard Wi-Fi station mode...");
                wifiManager.setWifiEnabled(true);
                List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
                if (configs != null) {
                    for (WifiConfiguration config : configs) {
                        if (config == null) continue;
                        String cfgSsid = cleanSsid(config.SSID);
                        boolean isDashcam = (config.networkId == dashcamNetId)
                                || isMatchingSsid(cfgSsid)
                                || (!knownSsid.isEmpty() && cfgSsid.equalsIgnoreCase(knownSsid));
                        if (isDashcam) {
                            wifiManager.disableNetwork(config.networkId);
                        } else {
                            wifiManager.enableNetwork(config.networkId, false);
                        }
                    }
                }
                if (initialNetworkId != -1 && initialNetworkId != dashcamNetId) {
                    wifiManager.enableNetwork(initialNetworkId, true);
                }
                wifiManager.reconnect();
            } else {
                // Was Wi-Fi disabled
                DebugLogger.log("WIFI_RESTORE", "Restoring Wi-Fi disabled state...");
                wifiManager.setWifiEnabled(false);
            }
        } catch (Exception e) {
            DebugLogger.log("WIFI_RESTORE", "Error restoring Wi-Fi state: " + e.getMessage());
        }

        if (listener != null) {
            listener.onDisconnected();
        }
    }

    private void registerReceivers() {
        if (!isRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WIFI_AP_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            context.registerReceiver(wifiReceiver, filter);
            isRegistered = true;
        }
    }

    private void unregisterReceivers() {
        if (isRegistered) {
            try {
                context.unregisterReceiver(wifiReceiver);
            } catch (Exception ignored) {}
            isRegistered = false;
        }
    }

    // --- Reflection methods for SoftAP & OEM Modes on KitKat 4.4 ---

    public boolean isWifiApEnabled() {
        try {
            Method m = wifiManager.getClass().getMethod("isWifiApEnabled");
            return (Boolean) m.invoke(wifiManager);
        } catch (Exception e) {
            return false;
        }
    }

    public int getWifiApMode() {
        try {
            Method m = wifiManager.getClass().getMethod("getWifiApMode");
            Object res = m.invoke(wifiManager);
            if (res instanceof Integer) {
                return (Integer) res;
            }
        } catch (Throwable t) {
            Log.d(TAG, "getWifiApMode reflection not available: " + t.getMessage());
        }
        return -1;
    }

    public WifiConfiguration getWifiApConfiguration() {
        try {
            Method m = wifiManager.getClass().getMethod("getWifiApConfiguration");
            return (WifiConfiguration) m.invoke(wifiManager);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean setWifiApEnabled(WifiConfiguration config, boolean enabled) {
        try {
            Method m = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            return (Boolean) m.invoke(wifiManager, config, enabled);
        } catch (Exception e) {
            Log.e(TAG, "setWifiApEnabled reflection failed", e);
            return false;
        }
    }

    public boolean setWifiApEnabledForConnectivity(WifiConfiguration config, boolean enabled) {
        try {
            Method m = wifiManager.getClass().getMethod("setWifiApEnabledForConnectivity", WifiConfiguration.class, boolean.class);
            Object res = m.invoke(wifiManager, config, enabled);
            if (res instanceof Boolean) {
                return (Boolean) res;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "setWifiApEnabledForConnectivity reflection failed: " + t.getMessage());
            return false;
        }
    }

    public static boolean isMatchingSsid(String ssid) {
        if (ssid == null) return false;
        String clean = cleanSsid(ssid);
        return SSID_PATTERN.matcher(clean).matches();
    }

    /**
     * Determines if a current Wi-Fi connection is likely the dashcam.
     * Matches if SSID contains dashcam identifiers, matches an explicit user-selected target SSID,
     * or uses a dedicated dashcam-only subnet gateway (192.168.1.254 Novatek or 192.72.1.1 AIT).
     * Never matches standard consumer home router gateways (192.168.1.1 / 192.168.0.1).
     */
    public static boolean isLikelyDashcamConnection(String ssid, DhcpInfo dhcp) {
        return isLikelyDashcamConnection(ssid, dhcp, null);
    }

    public static boolean isLikelyDashcamConnection(String ssid, DhcpInfo dhcp, String explicitTargetSsid) {
        if (isMatchingSsid(ssid)) return true;
        if (explicitTargetSsid != null && !explicitTargetSsid.isEmpty()
                && cleanSsid(explicitTargetSsid).equalsIgnoreCase(cleanSsid(ssid))) {
            return true;
        }
        if (dhcp != null && dhcp.ipAddress != 0) {
            String gw = intToIp(dhcp.gateway);
            String srv = intToIp(dhcp.serverAddress);
            if (CameraClient.DEFAULT_CAMERA_IP.equals(gw) || CameraClient.DEFAULT_CAMERA_IP.equals(srv)
                    || "192.72.1.1".equals(gw)) {
                return true;
            }
        }
        return false;
    }

    private void notifyStatus(final String msg) {
        Log.i(TAG, msg);
        if (listener != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    listener.onStatusChanged(msg);
                }
            });
        }
    }

    public static String cleanSsid(String ssid) {
        if (ssid == null) return "";
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }

    private static String intToIp(int i) {
        return (i & 0xFF) + "." +
               ((i >> 8) & 0xFF) + "." +
               ((i >> 16) & 0xFF) + "." +
               ((i >> 24) & 0xFF);
    }
}
