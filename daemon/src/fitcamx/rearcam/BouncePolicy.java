package fitcamx.rearcam;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Pure-Java policy and state decisions for Gen5W connections bounce on FITCAMX exit.
 *
 * Kept free of android.* imports so every audio-focus rule, worker-process allowlist,
 * multi-phone target choice, and transient-busy retry helper runs directly under host
 * JVM unit tests in FitcamUnitTests.
 */
public final class BouncePolicy {

    private BouncePolicy() {}

    /** Gen5W ModeManager ModeType constants. */
    public static final int MODE_NONE = 0;
    public static final int MODE_BT_AUDIO = 54;
    public static final int MODE_CARPLAY = 115;
    public static final int MODE_PROJECTION_GPM = 116;

    /** Gen5W MicomAudioManager audio mode constants. */
    public static final int MICOM_BT_AUDIO = 14;
    public static final int AUDIO_CARPLAY = 22;
    public static final int AUDIO_PROJECTION_GPM = 23;

    /** Android WifiManager SoftAP state constants. */
    public static final int WIFI_AP_STATE_DISABLING = 10;
    public static final int WIFI_AP_STATE_DISABLED = 11;
    public static final int WIFI_AP_STATE_ENABLING = 12;
    public static final int WIFI_AP_STATE_ENABLED = 13;

    /** Android BluetoothAdapter state constants. */
    public static final int BT_STATE_OFF = 10;
    public static final int BT_STATE_TURNING_ON = 11;
    public static final int BT_STATE_ON = 12;
    public static final int BT_STATE_TURNING_OFF = 13;

    /** PPService wireless projection type constants. */
    public static final int PP_TYPE_CARPLAY = 1;
    public static final int PP_TYPE_GPM = 2;

    /** Timing budgets & lease windows. */
    public static final long PROJECTION_RESUME_LEASE_MS = 45_000L;
    public static final long DISCONNECT_GRACE_MS = 350L;
    public static final long RADIO_DOWN_TIMEOUT_MS = 3_500L;
    public static final long WORKER_SETTLE_MS = 300L;
    public static final long SOFTAP_DISABLING_DRAIN_MS = 6_000L;
    public static final long SOFTAP_UP_TIMEOUT_MS = 4_000L;
    public static final long BT_UP_TIMEOUT_MS = 4_500L;
    public static final long RECONNECT_NUDGE_DELAY_MS = 6_000L;
    public static final long RECONNECT_WATCH_MAX_MS = 18_000L;

    public static final String SHORTCUT_CARPLAY = "com.hkmc.intent.action.DIPO_CUSTOM_SHORTCUT";
    public static final String SHORTCUT_GPM = "com.hkmc.intent.action.GPM_CUSTOM_SHORTCUT";
    public static final String FOCUS_DEFER_CAMERA = "DEFER_CAMERA";

    public static final String PKG_GPM_SINK = "com.google.android.projection.sink";
    public static final String PKG_DIPO = "com.daudio.app.dipo";
    public static final String PKG_PHONE_PROJECTION = "com.daudio.app.phoneprojection";

    private static final String[] CAMERA_PACKAGES = {
        "com.hkmc.system.app.rearcamera",
        "com.hkmc.app.rearcamera",
        "com.daudio.app.rearcamera",
        "com.hkmc.app.avm",
        "com.daudio.app.avm",
        "com.hkmc.avm.ui",
        "com.lge.app.avm"
    };

    /**
     * Holds hardware muteAV() during the bounce/reconnect window unless:
     *  - the driver already muted audio manually (userMute == 1), or
     *  - non-Bluetooth, non-Projection media (FM/AM/HD Radio, SiriusXM, USB media, Nature Sounds)
     *    is actively playing.
     */
    public static boolean shouldHoldHardwareMute(int curMode, int audMode, int userMute) {
        if (userMute == 1) {
            return false;
        }
        boolean nonBtNonProjMode = curMode > 0
                && curMode != MODE_BT_AUDIO
                && curMode != MODE_CARPLAY
                && curMode != MODE_PROJECTION_GPM;
        boolean nonBtNonProjAudio = audMode > 0
                && audMode != MICOM_BT_AUDIO
                && audMode != AUDIO_CARPLAY
                && audMode != AUDIO_PROJECTION_GPM;
        if (nonBtNonProjMode || nonBtNonProjAudio) {
            return false;
        }
        return true;
    }

    /**
     * Strictly allowlists only com.google.android.projection.sink and com.daudio.app.dipo
     * for Phase 3 SIGTERM worker refresh. Explicitly refuses com.daudio.app.phoneprojection
     * (keeping PPService's IWLDeviceInfo bound) and system_server.
     */
    public static boolean isAllowedWorkerTarget(String processCmdline) {
        if (processCmdline == null || processCmdline.isEmpty()) return false;
        String clean = processCmdline.trim();
        if (PKG_PHONE_PROJECTION.equals(clean) || "system_server".equals(clean)) {
            return false;
        }
        return PKG_GPM_SINK.equals(clean) || PKG_DIPO.equals(clean);
    }

    /**
     * Scopes Phase 3 worker SIGTERM refresh to the active projection protocol when known;
     * refreshes both allowlisted projection sinks when exiting FITCAMX (where neither was active).
     */
    public static boolean shouldRefreshWorker(
            String processCmdline,
            boolean hadGpm,
            boolean hadCp,
            boolean forced) {
        if (!isAllowedWorkerTarget(processCmdline)) return false;
        String clean = processCmdline.trim();
        if (forced) return true;
        if (hadGpm && !hadCp) {
            return PKG_GPM_SINK.equals(clean);
        }
        if (hadCp && !hadGpm) {
            return PKG_DIPO.equals(clean);
        }
        return true;
    }

    /**
     * Detects whether another bounce worker (from fitcamx or hmgapi-bounce) is already running.
     */
    public static boolean isBounceProcessCmdline(String processCmdline) {
        if (processCmdline == null || processCmdline.isEmpty()) return false;
        String clean = processCmdline.trim();
        return "fitcamx.bounce".equals(clean)
                || clean.contains("BounceRunner")
                || clean.startsWith("hmgapi-bounce");
    }

    public static boolean isCameraActive(boolean pgsCameraActive, int modeRearView, String foregroundPkg) {
        if (pgsCameraActive || modeRearView == 1) return true;
        if (foregroundPkg == null || foregroundPkg.isEmpty()) return false;
        String pkg = foregroundPkg.trim();
        for (int i = 0; i < CAMERA_PACKAGES.length; i++) {
            if (CAMERA_PACKAGES[i].equals(pkg)) return true;
        }
        return false;
    }

    public static String decideFocusShortcut(
            boolean cpUp,
            boolean gpmUp,
            int curMode,
            boolean cameraActive) {
        if (!cpUp && !gpmUp) return "";
        if (cpUp && curMode == MODE_CARPLAY) return "";
        if (gpmUp && !cpUp && curMode == MODE_PROJECTION_GPM) return "";
        if (cameraActive) return FOCUS_DEFER_CAMERA;
        return cpUp ? SHORTCUT_CARPLAY : SHORTCUT_GPM;
    }

    public static String normalizeBtMac(String addr) {
        if (addr == null) return "";
        String s = addr.trim().toUpperCase(Locale.US);
        if (s.length() != 17 || "00:00:00:00:00:00".equals(s)) return "";
        for (int i = 0; i < 17; i++) {
            char c = s.charAt(i);
            if (i % 3 == 2) {
                if (c != ':' && c != '-') return "";
            } else {
                boolean hex = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F');
                if (!hex) return "";
            }
        }
        return s.replace('-', ':');
    }

    /**
     * Multi-phone household target picker for the +6s PPService.connectPP fallback nudge:
     * prefers whichever phone connected Bluetooth HFP/ACL in the cabin right now, falling
     * back to pre-bounce session MACs only if HFP has not reported yet.
     */
    public static String selectReconnectNudgeTarget(
            String liveHfpMac,
            String liveConnMac,
            String preGpmMac,
            String preCpMac,
            String preHfpMac,
            String preConnMac) {
        String[] ordered = {
            liveHfpMac, liveConnMac, preGpmMac, preCpMac, preHfpMac, preConnMac
        };
        for (int i = 0; i < ordered.length; i++) {
            String m = normalizeBtMac(ordered[i]);
            if (!m.isEmpty()) return m;
        }
        return "";
    }

    /**
     * Determines PPService.connectPP projection type (1 CarPlay, 2 GPM wireless projection)
     * for targetMac.
     */
    public static int selectProjectionTypeForNudge(
            String targetMac,
            boolean cpAutoAvailable,
            int wlTransferType,
            String preGpmMac,
            String preCpMac,
            int preMode) {
        String norm = normalizeBtMac(targetMac);
        if (norm.isEmpty()) return 0;
        if (cpAutoAvailable || wlTransferType == PP_TYPE_CARPLAY) {
            return PP_TYPE_CARPLAY;
        }
        if (wlTransferType == PP_TYPE_GPM) {
            return PP_TYPE_GPM;
        }
        if (norm.equals(normalizeBtMac(preCpMac))) {
            return PP_TYPE_CARPLAY;
        }
        if (norm.equals(normalizeBtMac(preGpmMac))) {
            return PP_TYPE_GPM;
        }
        if (preMode == MODE_CARPLAY) return PP_TYPE_CARPLAY;
        return PP_TYPE_GPM;
    }

    /**
     * Prevents calling IBluetoothManager.enable() a second time when Android 4.4
     * BluetoothManagerService is already in STATE_TURNING_ON (11) or STATE_ON (12).
     */
    public static boolean shouldInvokeBluetoothEnable(int btState) {
        return btState != BT_STATE_TURNING_ON && btState != BT_STATE_ON;
    }

    /**
     * Returns true once WifiStateMachine has finished exiting SoftApStoppingState (10)
     * and is neither active nor enabling.
     */
    public static boolean isSoftApReadyForReenable(int apState) {
        return apState != WIFI_AP_STATE_DISABLING
                && apState != WIFI_AP_STATE_ENABLING
                && apState != WIFI_AP_STATE_ENABLED;
    }

    /**
     * Identifies transient hmgapid worker-slot saturation so client IPC calls retry cleanly.
     */
    public static boolean isTransientHmgapidBusyError(String message) {
        if (message == null || message.isEmpty()) return false;
        String low = message.toLowerCase(Locale.US);
        return low.contains("hmgapid is busy") || low.contains("broken pipe");
    }

    public static String buildProjectionResumeJson(
            String bootId,
            int preMode,
            String btAddr,
            long nowElapsedMs) {
        int effMode = (preMode == MODE_CARPLAY || preMode == MODE_PROJECTION_GPM)
                ? preMode
                : MODE_PROJECTION_GPM;
        String src = (effMode == MODE_CARPLAY) ? "cp" : "aa";
        String cleanBoot = escapeJson(bootId == null ? "" : bootId.trim());
        String cleanMac = normalizeBtMac(btAddr);
        long exp = Math.max(0L, nowElapsedMs) + PROJECTION_RESUME_LEASE_MS;
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"bootId\":\"").append(cleanBoot).append('"');
        sb.append(",\"preMode\":").append(effMode);
        sb.append(",\"source\":\"").append(src).append('"');
        if (!cleanMac.isEmpty()) {
            sb.append(",\"btAddr\":\"").append(cleanMac).append('"');
        }
        sb.append(",\"resumedAtElapsedMs\":").append(Math.max(0L, nowElapsedMs));
        sb.append(",\"expiresAtElapsedMs\":").append(exp);
        sb.append("}\n");
        return sb.toString();
    }

    public static String redactMac(String mac) {
        String norm = normalizeBtMac(mac);
        if (norm.isEmpty()) return "none";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(("hmgapi-bounce:" + norm).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(9);
            hex.append("bt#");
            for (int i = 0; i < 3; i++) {
                int v = dig[i] & 0xff;
                if (v < 16) hex.append('0');
                hex.append(Integer.toHexString(v));
            }
            return hex.toString();
        } catch (Exception e) {
            return "bt#redacted";
        }
    }

    static String escapeJson(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c >= 0x20) out.append(c);
        }
        return out.toString();
    }
}
