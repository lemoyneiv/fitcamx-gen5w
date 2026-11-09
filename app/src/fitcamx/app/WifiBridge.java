package fitcamx.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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

    private final Context context;
    private final WifiManager wifiManager;
    private final Handler handler;
    private final ExecutorService executor;

    // Previous state to restore on exit
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
    private String targetSsid = null;
    private int scanAttempts = 0;
    private int authErrorCount = 0;
    private boolean isProbingCamera = false;
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
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * Start the state machine:
     * 1. Record original state (AP mode and STA)
     * 2. Disable AP if on
     * 3. Enable STA if off
     * 4. Begin active scanning and auto-connect
     */
    public void connectToDashcam() {
        isRestoring = false;
        isConnectingToTarget = false;
        targetSsid = null;
        scanAttempts = 0;
        authErrorCount = 0;

        recordInitialState();
        registerReceivers();

        if (initialApEnabled) {
            notifyStatus("Preparing Wi-Fi for dashcam...");
            if (initialApMode == 2) {
                DebugLogger.log("WIFI", "Temporarily disabling Connectivity AP (Mode 2) for dashcam link...");
                boolean ok = setWifiApEnabledForConnectivity(null, false);
                if (!ok) {
                    setWifiApEnabled(null, false);
                }
            } else {
                DebugLogger.log("WIFI", "Temporarily disabling In-Car Wi-Fi Hotspot (Mode 1)...");
                setWifiApEnabled(null, false);
            }
            // Receiver will wait for WIFI_AP_STATE_DISABLED then call enableStationMode()
        } else {
            enableStationMode();
        }
    }

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
        // First check if already connected to a matching SSID or dashcam gateway
        WifiInfo currentInfo = wifiManager.getConnectionInfo();
        if (currentInfo != null && currentInfo.getSSID() != null) {
            String ssid = cleanSsid(currentInfo.getSSID());
            DhcpInfo dhcp = wifiManager.getDhcpInfo();
            if (isLikelyDashcamConnection(ssid, dhcp)) {
                if (dhcp != null && dhcp.ipAddress != 0) {
                    notifyStatus("Already connected to " + ssid + ". Verifying camera...");
                    startDhcpAndCameraProbe(ssid);
                    return;
                }
            }
        }

        // Start scanning
        startScanLoop();
    }

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
        // If already connected and have IP, probe camera directly without tearing down Wi-Fi
        WifiInfo currentInfo = wifiManager.getConnectionInfo();
        if (currentInfo != null && currentInfo.getSSID() != null) {
            String ssid = cleanSsid(currentInfo.getSSID());
            DhcpInfo dhcp = wifiManager.getDhcpInfo();
            if (isLikelyDashcamConnection(ssid, dhcp)) {
                if (dhcp != null && dhcp.ipAddress != 0) {
                    notifyStatus("Re-checking camera connection on " + ssid + "...");
                    startDhcpAndCameraProbe(ssid);
                    return;
                }
            }
        }

        authErrorCount = 0;
        isConnectingToTarget = false;
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
                    enableStationMode();
                }
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                Log.d(TAG, "Broadcast: WIFI_STATE_CHANGED -> " + wifiState);
                if (wifiState == WifiManager.WIFI_STATE_ENABLED && !isRestoring) {
                    notifyStatus("Wi-Fi active. Scanning for networks...");
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
                if (netInfo != null && netInfo.isConnected()) {
                    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                    if (wifiInfo != null && wifiInfo.getSSID() != null) {
                        String ssid = cleanSsid(wifiInfo.getSSID());
                        DhcpInfo dhcp = wifiManager.getDhcpInfo();
                        if (isLikelyDashcamConnection(ssid, dhcp)) {
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

        if (lastScanResults.isEmpty()) {
            DebugLogger.log("WIFI_SCAN", "Scan #" + scanAttempts + ": 0 networks in range");
            notifyStatus("Scan #" + scanAttempts + ": 0 networks in range. Searching...");
            return;
        }

        // Build list of visible SSIDs for diagnostic feedback
        StringBuilder sb = new StringBuilder();
        ScanResult targetMatch = null;

        for (int i = 0; i < lastScanResults.size(); i++) {
            ScanResult r = lastScanResults.get(i);
            String name = (r.SSID != null) ? r.SSID : "<Hidden>";
            DebugLogger.log("WIFI_SCAN", "Found SSID: \"" + name + "\" BSSID=" + r.BSSID + " Level=" + r.level + "dBm Freq=" + r.frequency + "MHz Caps=" + r.capabilities);
            if (i < 4) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(name);
            }
            if (targetMatch == null && isMatchingSsid(r.SSID)) {
                targetMatch = r;
            }
        }

        if (targetMatch != null) {
            stopScanLoop();
            targetSsid = targetMatch.SSID;
            isConnectingToTarget = true;
            DebugLogger.log("WIFI_SCAN", "Matched target dashcam: " + targetMatch.SSID + " (" + targetMatch.level + " dBm)");
            notifyStatus("Found " + targetMatch.SSID + " (" + targetMatch.level + " dBm). Connecting...");
            connectToSsid(targetMatch.SSID, DEFAULT_PASSWORD);
        } else {
            notifyStatus("Found " + lastScanResults.size() + " networks [" + sb.toString() + "]. Searching for dashcam...");
        }
    }

    public void connectToSpecificSsid(String ssid, String password) {
        stopScanLoop();
        targetSsid = ssid;
        isConnectingToTarget = true;
        authErrorCount = 0;
        notifyStatus("Connecting to " + ssid + "...");
        connectToSsid(ssid, password != null ? password : DEFAULT_PASSWORD);
    }

    private void connectToSsid(String ssid, String password) {
        WifiConfiguration conf = new WifiConfiguration();
        conf.SSID = "\"" + ssid + "\"";
        conf.preSharedKey = "\"" + password + "\"";
        conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        conf.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        conf.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
        conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
        conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        conf.status = WifiConfiguration.Status.ENABLED;

        int netId = -1;
        List<WifiConfiguration> list = wifiManager.getConfiguredNetworks();
        if (list != null) {
            for (WifiConfiguration i : list) {
                if (i.SSID != null && i.SSID.equals("\"" + ssid + "\"")) {
                    netId = i.networkId;
                    conf.networkId = netId;
                    wifiManager.updateNetwork(conf);
                    break;
                }
            }
        }

        if (netId == -1) {
            netId = wifiManager.addNetwork(conf);
        }

        if (netId != -1) {
            connectedNetworkId = netId;
            wifiManager.saveConfiguration();
            // enableNetwork(netId, true) smoothly associates and disables others without aborting ongoing handshakes
            boolean ok = wifiManager.enableNetwork(netId, true);
            notifyStatus("Associating with " + ssid + " (network ID " + netId + ")...");
            Log.i(TAG, "wifiManager.enableNetwork(" + netId + ") returned " + ok);
        } else {
            isConnectingToTarget = false;
            notifyStatus("Failed to configure Wi-Fi profile for " + ssid);
            startScanLoop();
        }
    }

    private void handleSupplicantState(SupplicantState state, int error) {
        if (state == null) return;
        Log.d(TAG, "SupplicantState=" + state + ", error=" + error);

        if (error == 1) { // ERROR_AUTHENTICATING
            authErrorCount++;
            Log.w(TAG, "Supplicant reported auth error (count=" + authErrorCount + ")");
            if (authErrorCount >= 3) {
                notifyStatus("Authentication failed for " + (targetSsid != null ? targetSsid : "dashcam") + ". Please check camera password.");
                isConnectingToTarget = false;
            }
            return;
        }

        switch (state) {
            case ASSOCIATING:
                notifyStatus("Associating with " + (targetSsid != null ? targetSsid : "camera") + "...");
                break;
            case AUTHENTICATING:
                notifyStatus("Authenticating credentials...");
                break;
            case FOUR_WAY_HANDSHAKE:
            case GROUP_HANDSHAKE:
                notifyStatus("Verifying encryption keys...");
                break;
            case COMPLETED:
                authErrorCount = 0;
                notifyStatus("Wi-Fi handshake complete. Requesting IP address (DHCP)...");
                break;
            case DISCONNECTED:
                if (isConnectingToTarget && authErrorCount == 0) {
                    notifyStatus("Wi-Fi negotiating, waiting for link...");
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
        isConnectingToTarget = false;
        if (isProbingCamera) return;
        isProbingCamera = true;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String myIp = null;
                    String gatewayIp = null;
                    String netmask = null;

                    // Poll DHCP for up to 10 seconds (20 iterations * 500ms)
                    for (int attempt = 0; attempt < 20; attempt++) {
                        DhcpInfo dhcp = wifiManager.getDhcpInfo();
                        if (dhcp != null && dhcp.ipAddress != 0) {
                            myIp = intToIp(dhcp.ipAddress);
                            gatewayIp = (dhcp.gateway != 0) ? intToIp(dhcp.gateway) : CameraClient.DEFAULT_CAMERA_IP;
                            netmask = intToIp(dhcp.netmask);
                            DebugLogger.log("WIFI_DHCP", "Acquired IP=" + myIp + " Gateway=" + gatewayIp + " Netmask=" + netmask + " DNS1=" + intToIp(dhcp.dns1) + " Server=" + intToIp(dhcp.serverAddress));
                            break;
                        }
                        notifyStatus("Acquiring IP address via DHCP... (" + (attempt + 1) + "/20)");
                        Thread.sleep(500);
                    }

                    if (myIp == null) {
                        DebugLogger.log("WIFI_DHCP", "DHCP timeout: Wi-Fi connected to " + ssid + ", but no IP address assigned.");
                        notifyStatus("DHCP timeout: Wi-Fi connected to " + ssid + ", but no IP address assigned yet.");
                        isProbingCamera = false;
                        return;
                    }

                    notifyStatus("Leased IP: " + myIp + " | Gateway: " + gatewayIp + " | Probing dashcam...");

                    // Candidate IPs in order:
                    // 1. Gateway IP received via DHCP
                    // 2. 192.168.1.254 (Novatek FITCAMX standard)
                    // 3. 192.168.0.1 (HiSilicon / alternate)
                    // 4. 192.168.1.1 (iCatch / alternate)
                    // 5. 192.72.1.1 (AIT)
                    // 6. 192.168.100.1 (Allwinner)
                    List<String> candList = new ArrayList<String>();
                    if (gatewayIp != null && !gatewayIp.equals("0.0.0.0")) {
                        candList.add(gatewayIp);
                    }
                    if (!candList.contains(CameraClient.DEFAULT_CAMERA_IP)) {
                        candList.add(CameraClient.DEFAULT_CAMERA_IP);
                    }
                    if (!candList.contains("192.168.0.1")) candList.add("192.168.0.1");
                    if (!candList.contains("192.168.1.1")) candList.add("192.168.1.1");
                    if (!candList.contains("192.72.1.1")) candList.add("192.72.1.1");

                    ProbeResult bestProbe = null;
                    StringBuilder probeLog = new StringBuilder();

                    for (String ip : candList) {
                        ProbeResult res = probeCameraIp(ip);
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

                    final String finalCameraIp;
                    final CameraClient.SocType finalSocType;
                    final int finalHttpPort;
                    final String diagMsg;

                    if (bestProbe != null) {
                        finalCameraIp = bestProbe.ip;
                        finalSocType = bestProbe.socType;
                        finalHttpPort = bestProbe.httpPort;
                        diagMsg = "Dashcam online: " + finalSocType.displayName + " at " + finalCameraIp;
                    } else {
                        finalCameraIp = (gatewayIp != null && !gatewayIp.equals("0.0.0.0")) ? gatewayIp : CameraClient.DEFAULT_CAMERA_IP;
                        finalSocType = CameraClient.SocType.NOVATEK; // fallback to Novatek
                        finalHttpPort = 80;
                        diagMsg = "No HTTP response; assuming " + finalCameraIp + ". [" + probeLog.toString() + "]";
                    }

                    final String fullDetails = "Local IP: " + myIp + " | Netmask: " + netmask + " | GW: " + gatewayIp + " | Camera: " + finalSocType.displayName + " (" + finalCameraIp + ")";
                    DebugLogger.log("WIFI_CONN", fullDetails);

                    // Launch thorough background socket & HTTP diagnostic probe
                    final String diagCamIp = finalCameraIp;
                    final String diagGwIp = gatewayIp;
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                DebugLogger.runActiveNetworkDiagnostics(context, diagCamIp, diagGwIp);
                            } catch (Exception e) {
                                DebugLogger.log("DIAG", "Network diagnostics error", e);
                            }
                        }
                    });

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            isProbingCamera = false;
                            notifyStatus(diagMsg);
                            if (listener != null) {
                                listener.onConnected(finalCameraIp, ssid, finalSocType, finalHttpPort, fullDetails);
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

    /**
     * Test HTTP response on candidate IP and identify camera chipset
     */
    private ProbeResult probeCameraIp(String ip) {
        if (ip == null || ip.isEmpty() || ip.equals("0.0.0.0")) return null;

        // 1. Probe Novatek official detection (cmd=3504)
        try {
            URL url = new URL("http://" + ip + "/?custom=1&cmd=3504");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            DebugLogger.log("CAM_PROBE", "GET " + url + " -> " + code);
            if (code == 200) {
                DebugLogger.log("CAM_PROBE", "Novatek verified on " + ip + " via cmd=3504");
                return new ProbeResult(ip, CameraClient.SocType.NOVATEK, 80, "Novatek NT966xx verified via cmd=3504");
            }
        } catch (Exception e) {
            DebugLogger.log("CAM_PROBE", ip + " cmd=3504 error: " + e.getMessage());
        }

        // 2. Probe Novatek heartbeat (cmd=3016)
        try {
            URL url = new URL("http://" + ip + "/?custom=1&cmd=3016");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1800);
            conn.setReadTimeout(1800);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            DebugLogger.log("CAM_PROBE", "GET " + url + " -> " + code);
            if (code == 200) {
                DebugLogger.log("CAM_PROBE", "Novatek verified on " + ip + " via cmd=3016");
                return new ProbeResult(ip, CameraClient.SocType.NOVATEK, 80, "Novatek NT966xx verified via cmd=3016");
            }
        } catch (Exception e) {
            DebugLogger.log("CAM_PROBE", ip + " cmd=3016 error: " + e.getMessage());
        }

        // 3. Probe JieLi port 8080
        try {
            URL url = new URL("http://" + ip + ":8080/mnt/spiflash/res/dev_desc.txt");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1800);
            conn.setReadTimeout(1800);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            DebugLogger.log("CAM_PROBE", "GET " + url + " -> " + code);
            if (code == 200 || code == 404) {
                DebugLogger.log("CAM_PROBE", "JieLi detected on " + ip + ":8080");
                return new ProbeResult(ip, CameraClient.SocType.JIELI, 8080, "JieLi AC5x/AC7x verified on port 8080");
            }
        } catch (Exception e) {
            DebugLogger.log("CAM_PROBE", ip + ":8080 error: " + e.getMessage());
        }

        // 4. Probe iCatch / AIT Config.cgi
        try {
            URL url = new URL("http://" + ip + "/cgi-bin/Config.cgi?action=get&property=Camera.Menu.*");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1800);
            conn.setReadTimeout(1800);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            DebugLogger.log("CAM_PROBE", "GET " + url + " -> " + code);
            if (code == 200 || code == 401) {
                DebugLogger.log("CAM_PROBE", "iCatch detected on " + ip);
                return new ProbeResult(ip, CameraClient.SocType.ICATCH, 80, "iCatch / AIT verified via Config.cgi");
            }
        } catch (Exception e) {
            DebugLogger.log("CAM_PROBE", ip + " Config.cgi error: " + e.getMessage());
        }

        // 5. Fallback test: ping root HTTP
        try {
            URL url = new URL("http://" + ip + "/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            DebugLogger.log("CAM_PROBE", "GET " + url + " -> " + code);
            if (code == 200 || code == 401 || code == 404) {
                return new ProbeResult(ip, CameraClient.SocType.UNKNOWN, 80, "Generic HTTP responding on port 80");
            }
        } catch (Exception e) {
            DebugLogger.log("CAM_PROBE", ip + " root HTTP error: " + e.getMessage());
        }

        return null;
    }

    /**
     * Restore the Wi-Fi state to exactly whatever it was before the app launched.
     */
    public void restoreAndExit() {
        if (isRestoring) return;
        isRestoring = true;

        stopScanLoop();
        unregisterReceivers();
        notifyStatus("Restoring previous Wi-Fi state...");

        // Disconnect and remove the temporary dashcam network profile
        if (connectedNetworkId != -1) {
            try {
                wifiManager.disableNetwork(connectedNetworkId);
                wifiManager.disconnect();
                wifiManager.removeNetwork(connectedNetworkId);
                wifiManager.saveConfiguration();
                DebugLogger.log("WIFI_RESTORE", "Removed dashcam network profile (id=" + connectedNetworkId + ")");
            } catch (Exception ignored) {}
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
                // Was standard Wi-Fi station mode
                DebugLogger.log("WIFI_RESTORE", "Restoring standard Wi-Fi station mode...");
                wifiManager.setWifiEnabled(true);
                List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
                if (configs != null) {
                    for (WifiConfiguration config : configs) {
                        wifiManager.enableNetwork(config.networkId, false);
                    }
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
     * Matches if SSID contains dashcam identifiers OR if gateway/server IP is a known dashcam default
     * (192.168.1.254 Novatek standard, 192.168.0.1, 192.168.1.1, 192.72.1.1).
     */
    public static boolean isLikelyDashcamConnection(String ssid, DhcpInfo dhcp) {
        if (isMatchingSsid(ssid)) return true;
        if (dhcp != null && dhcp.ipAddress != 0) {
            String gw = intToIp(dhcp.gateway);
            String srv = intToIp(dhcp.serverAddress);
            if (CameraClient.DEFAULT_CAMERA_IP.equals(gw) || CameraClient.DEFAULT_CAMERA_IP.equals(srv) ||
                "192.168.0.1".equals(gw) || "192.168.1.1".equals(gw) || "192.72.1.1".equals(gw)) {
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
