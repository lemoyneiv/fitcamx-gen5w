package fitcamx.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CameraClient {
    private static final String TAG = "FitcamxCamera";

    public static final String DEFAULT_CAMERA_IP = "192.168.1.254";
    public static final int RTSP_PORT = 554;

    private static final Pattern CGI_STATUS_PATTERN = Pattern.compile("(?i)<Status>\\s*(-?\\d+)\\s*</Status>");
    private static final Pattern LIVE_URL_PATTERN = Pattern.compile("(?i)<(?:MovieLiveViewLink|LiveViewLink|StreamUrl)>([^<]+)</");

    public enum SocType {
        NOVATEK("Novatek NT966xx"),
        JIELI("JieLi AC5x/AC7x"),
        ICATCH("iCatch / AIT"),
        ALLWINNER("Allwinner / Sunxi"),
        HISILICON("HiSilicon"),
        UNKNOWN("Generic Dashcam");

        public final String displayName;
        SocType(String displayName) {
            this.displayName = displayName;
        }
    }

    private String cameraIp;
    private SocType socType = SocType.NOVATEK;
    private int httpPort = 80;
    private final Handler mainHandler;
    private final ExecutorService executor;

    // Session state tracking for streamlined CGI transitions & teardown serialization
    private volatile int knownCameraMode = -1; // -1=unknown, 1=Movie, 2=Playback
    private volatile String cachedDynamicRtspUrl = null;
    private volatile boolean teardownActive = false;
    private volatile HttpURLConnection activeConnection = null;

    public interface Callback<T> {
        void onSuccess(T result);
        void onError(Exception error);
    }

    public CameraClient() {
        this(DEFAULT_CAMERA_IP);
    }

    public CameraClient(String cameraIp) {
        this.cameraIp = cameraIp;
        this.mainHandler = new Handler(Looper.getMainLooper());
        // Single-thread CGI queue prevents concurrent HTTP command collisions on Novatek's lwIP server
        this.executor = Executors.newSingleThreadExecutor();
    }

    private void runAsync(Runnable task) {
        if (!executor.isShutdown()) {
            executor.execute(task);
        }
    }

    public void shutdown() {
        teardownActive = true;
        abortActiveConnection();
        executor.shutdownNow();
    }

    public void resetSessionState() {
        teardownActive = false;
        knownCameraMode = -1;
        cachedDynamicRtspUrl = null;
    }

    private void abortActiveConnection() {
        final HttpURLConnection conn = activeConnection;
        if (conn != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {}
                }
            }, "FitcamxHttpAbort").start();
        }
    }

    public void setCameraIp(String ip) {
        if (ip != null && !ip.equals(this.cameraIp)) {
            resetSessionState();
        }
        this.cameraIp = ip;
        teardownActive = false;
        Log.i(TAG, "Camera IP set to: " + ip);
    }

    public String getCameraIp() {
        return cameraIp;
    }

    public void setSocType(SocType type, int port) {
        this.socType = type;
        this.httpPort = port > 0 ? port : 80;
        Log.i(TAG, "Camera SoC set to: " + type.displayName + " on port " + this.httpPort);
    }

    public SocType getSocType() {
        return socType;
    }

    public int getHttpPort() {
        return httpPort;
    }

    /**
     * Extracts numeric <Status> code from a single-command Novatek XML response,
     * returning Integer.MIN_VALUE if no <Status> tag is present.
     */
    public static int parseCgiStatus(String xml) {
        if (xml == null || xml.isEmpty()) return Integer.MIN_VALUE;
        Matcher m = CGI_STATUS_PATTERN.matcher(xml);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {}
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Returns true when an HTTP 200 CGI response either carries <Status>0</Status>
     * or contains non-Function payload without a negative <Status> error code.
     */
    public static boolean isCgiStatusOk(String xml) {
        if (xml == null) return false;
        int st = parseCgiStatus(xml);
        if (st == Integer.MIN_VALUE) {
            // Non-Novatek or <LIST> response without <Status> tag
            return true;
        }
        return st == 0;
    }

    /**
     * Parse stream URL dynamically returned by camera (e.g. from cmd=2019)
     */
    public static String parseLiveViewUrl(String xml) {
        if (xml == null || xml.trim().isEmpty()) return null;
        Matcher m = LIVE_URL_PATTERN.matcher(xml);
        if (m.find()) {
            String url = m.group(1).trim();
            if (url.startsWith("rtsp://") || url.startsWith("http://")) {
                return url;
            }
        }
        return null;
    }

    public static final int VIEW_FRONT = 0;
    public static final int VIEW_REAR = 1;
    public static final int VIEW_DUAL = 2;

    /**
     * Provide candidate stream URLs in priority order for robust playback.
     */
    public List<String> getCandidateStreamUrls(int viewMode) {
        return getCandidateStreamUrls(viewMode != VIEW_REAR);
    }

    public List<String> getCandidateStreamUrls(boolean front) {
        List<String> list = new ArrayList<String>();
        switch (socType) {
            case NOVATEK:
                addNovatekUrls(list);
                break;
            case JIELI:
                addJieLiUrls(list, front);
                addNovatekUrls(list);
                break;
            case ICATCH:
                addICatchUrls(list, front);
                addNovatekUrls(list);
                break;
            case ALLWINNER:
                addAllwinnerUrls(list, front);
                addNovatekUrls(list);
                break;
            case HISILICON:
                addHiSiliconUrls(list, front);
                addNovatekUrls(list);
                break;
            case UNKNOWN:
            default:
                addNovatekUrls(list);
                addJieLiUrls(list, front);
                addICatchUrls(list, front);
                addAllwinnerUrls(list, front);
                addHiSiliconUrls(list, front);
                break;
        }
        return list;
    }

    private void addNovatekUrls(List<String> list) {
        // Note: rtsp://ip/... defaults to TCP port 554 in RFC 2326 / LibVLC; omit duplicate :554 entries
        addUnique(list, "rtsp://" + cameraIp + "/xxx.mov");
        addUnique(list, "rtsp://" + cameraIp + "/xxxx.mov");
        addUnique(list, "rtsp://" + cameraIp + "/live.mov");
        addUnique(list, "http://" + cameraIp + ":8192");
    }

    private void addJieLiUrls(List<String> list, boolean front) {
        if (front) {
            addUnique(list, "rtsp://" + cameraIp + ":" + RTSP_PORT + "/264_pcm_rt/XXX.hd");
            addUnique(list, "rtsp://" + cameraIp + ":" + RTSP_PORT + "/264_pcm_rt/XXX.fhd");
            addUnique(list, "rtsp://" + cameraIp + ":" + RTSP_PORT + "/264_pcm_rt/XXX.sd");
        } else {
            addUnique(list, "rtsp://" + cameraIp + ":" + RTSP_PORT + "/264_pcm_rt/rear.hd");
            addUnique(list, "rtsp://" + cameraIp + ":" + RTSP_PORT + "/264_pcm_rt/rear.fhd");
            addUnique(list, "rtsp://" + cameraIp + ":" + RTSP_PORT + "/264_pcm_rt/rear.sd");
        }
    }

    private void addICatchUrls(List<String> list, boolean front) {
        if (front) {
            addUnique(list, "rtsp://" + cameraIp + "/liveRTSP/v1");
        } else {
            addUnique(list, "rtsp://" + cameraIp + "/liveRTSP/v2");
            addUnique(list, "rtsp://" + cameraIp + "/liveRTSP/av4");
        }
    }

    private void addAllwinnerUrls(List<String> list, boolean front) {
        addUnique(list, "rtsp://" + cameraIp + ":8554/" + (front ? "ch01" : "ch13"));
    }

    private void addHiSiliconUrls(List<String> list, boolean front) {
        addUnique(list, "rtsp://" + cameraIp + ":" + RTSP_PORT + "/livestream/" + (front ? "1" : "2"));
    }

    private static void addUnique(List<String> list, String url) {
        if (!list.contains(url)) {
            list.add(url);
        }
    }

    /**
     * Prepare and start live view:
     * - Fast-path when already in Movie Mode (knownCameraMode == 1): attempts non-disruptive PIP switch
     *   (cmd=3028) without stopping SD card recording first (~60ms vs ~2.5s, zero .TS file fragmentation),
     *   falling back automatically if firmware requires recording to pause (Status -13).
     * - Full sequence on initial connect or when returning from Playback Mode (knownCameraMode == 2).
     * - Caches cmd=2019 dynamic RTSP URL per session to eliminate redundant queries & sleep delays.
     */
    public void prepareAndStartLiveView(final int viewMode, final Callback<List<String>> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                String modeName = (viewMode == VIEW_FRONT) ? "FRONT" : (viewMode == VIEW_REAR ? "REAR" : "DUAL (BOTH)");
                DebugLogger.log("CAM_LIVE", "Preparing live view for " + modeName + " camera (viewMode=" + viewMode + ") on " + cameraIp);
                try {
                    boolean switchedFast = false;

                    // Fast PIP channel switch when already in Movie Mode with cached RTSP stream active
                    if (socType == SocType.NOVATEK && knownCameraMode == 1 && cachedDynamicRtspUrl != null) {
                        try {
                            String rFast3028 = sendCgiCommand("3028", String.valueOf(viewMode));
                            if (isCgiStatusOk(rFast3028)) {
                                switchedFast = true;
                                DebugLogger.log("CAM_LIVE", "Fast non-disruptive channel switch succeeded for " + modeName);
                            }
                        } catch (Exception ignored) {}
                    }

                    if (!switchedFast) {
                        if (teardownActive) return;
                        // 1. Stop active recording (cmd=2001&par=0) - mandatory on NT96675 before starting liveview encoder
                        try {
                            String r2001 = sendCgiCommand("2001", "0");
                            DebugLogger.log("CAM_LIVE", "cmd=2001&par=0 (Stop rec) response: " + (r2001 != null ? r2001.trim() : "null"));
                        } catch (Exception e) {
                            DebugLogger.log("CAM_LIVE", "cmd=2001&par=0 failed: " + e.getMessage());
                        }

                        if (teardownActive) return;
                        // 2. Stop live stream encoder if already running
                        try {
                            String r2015stop = sendCgiCommand("2015", "0");
                            DebugLogger.log("CAM_LIVE", "cmd=2015&par=0 (Stop live) response: " + (r2015stop != null ? r2015stop.trim() : "null"));
                        } catch (Exception ignored) {}

                        // 3. Switch Novatek to Video mode (cmd=3001&par=1) when unknown or returning from Playback mode
                        if (knownCameraMode != 1) {
                            Thread.sleep(40);
                            try {
                                String r3001 = sendCgiCommand("3001", "1");
                                DebugLogger.log("CAM_LIVE", "cmd=3001&par=1 (Video Mode) response: " + (r3001 != null ? r3001.trim() : "null"));
                                if (isCgiStatusOk(r3001)) {
                                    knownCameraMode = 1;
                                }
                            } catch (Exception e) {
                                DebugLogger.log("CAM_LIVE", "cmd=3001&par=1 failed: " + e.getMessage());
                            }
                        }

                        if (teardownActive) return;
                        // 4. Switch hardware channel (cmd=3028: 0=front, 1=rear, 2=dual)
                        try {
                            String r3028 = sendCgiCommand("3028", String.valueOf(viewMode));
                            DebugLogger.log("CAM_LIVE", "cmd=3028&par=" + viewMode + " (" + modeName + " select) response: " + (r3028 != null ? r3028.trim() : "null"));
                        } catch (Exception e) {
                            DebugLogger.log("CAM_LIVE", "cmd=3028 failed: " + e.getMessage());
                        }

                        if (teardownActive) return;
                        // 5. Command Novatek to start pushing liveview stream (cmd=2015&par=1)
                        String r2015 = null;
                        try {
                            r2015 = sendCgiCommand("2015", "1");
                            DebugLogger.log("CAM_LIVE", "cmd=2015&par=1 (Start Live) response: " + (r2015 != null ? r2015.trim() : "null"));
                        } catch (Exception e) {
                            DebugLogger.log("CAM_LIVE", "cmd=2015 failed: " + e.getMessage());
                        }

                        // If returned -13, retry stop-rec + start
                        if (r2015 != null && parseCgiStatus(r2015) == -13 && !teardownActive) {
                            DebugLogger.log("CAM_LIVE", "Liveview start returned -13, re-stopping recording and retrying...");
                            Thread.sleep(200);
                            sendCgiCommand("2001", "0");
                            Thread.sleep(120);
                            sendCgiCommand("3001", "1");
                            Thread.sleep(120);
                            r2015 = sendCgiCommand("2015", "1");
                            DebugLogger.log("CAM_LIVE", "Retry cmd=2015&par=1 response: " + (r2015 != null ? r2015.trim() : "null"));
                        }

                        // 6. Query dynamic liveview link from camera firmware once per session (cmd=2019)
                        if (cachedDynamicRtspUrl == null && !teardownActive) {
                            Thread.sleep(60);
                            try {
                                String r2019 = sendCgiCommand("2019", null);
                                DebugLogger.log("CAM_LIVE", "cmd=2019 (GetStreamUrl) response: " + (r2019 != null ? r2019.trim() : "null"));
                                String parsed = parseLiveViewUrl(r2019);
                                if (parsed != null && !parsed.isEmpty()) {
                                    cachedDynamicRtspUrl = parsed;
                                    DebugLogger.log("CAM_LIVE", "Cached dynamic RTSP URL: " + parsed);
                                }
                            } catch (Exception e) {
                                DebugLogger.log("CAM_LIVE", "cmd=2019 query failed: " + e.getMessage());
                            }
                        }

                        if (teardownActive) return;
                        // 7. Resume continuous SD card loop recording (cmd=2001&par=1)
                        try {
                            Thread.sleep(40);
                            String rRec = sendCgiCommand("2001", "1");
                            DebugLogger.log("CAM_LIVE", "cmd=2001&par=1 (Resume recording) response: " + (rRec != null ? rRec.trim() : "null"));
                            if (isCgiStatusOk(rRec)) {
                                knownCameraMode = 1;
                            }
                        } catch (Exception e) {
                            DebugLogger.log("CAM_LIVE", "cmd=2001&par=1 failed: " + e.getMessage());
                        }
                    }

                    List<String> candidates = getCandidateStreamUrls(viewMode);
                    String dynamicUrl = cachedDynamicRtspUrl;
                    if (dynamicUrl != null && !dynamicUrl.isEmpty()) {
                        candidates.remove(dynamicUrl);
                        candidates.add(0, dynamicUrl);
                    }

                    DebugLogger.log("CAM_LIVE", "Generated " + candidates.size() + " stream candidates. First: " + candidates.get(0));
                    postSuccess(callback, candidates);
                } catch (Exception e) {
                    DebugLogger.log("CAM_LIVE", "prepareAndStartLiveView error", e);
                    postError(callback, e);
                }
            }
        });
    }

    public void prepareAndStartLiveView(final boolean front, final Callback<List<String>> callback) {
        prepareAndStartLiveView(front ? VIEW_FRONT : VIEW_REAR, callback);
    }

    /**
     * Stop live view streaming encoder on camera
     */
    public void stopLiveView() {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                try {
                    sendCgiCommand("2015", "0");
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Switches the camera into Playback Mode (cmd=2001&par=0 -> cmd=2015&par=0 -> cmd=3001&par=2)
     * WITHOUT running the slow 16-second cmd=3015 FAT32 walk when the clip list is already cached.
     */
    public void enterPlaybackMode(final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                try {
                    if (knownCameraMode != 2) {
                        // Stop recording BEFORE stopping liveview (avoids 980ms Status -13 stall on NT966xx)
                        try {
                            sendCgiCommand("2001", "0");
                        } catch (Exception ignored) {}
                        if (teardownActive) return;
                        try {
                            sendCgiCommand("2015", "0");
                        } catch (Exception ignored) {}
                        if (teardownActive) return;
                        String r3001 = sendCgiCommand("3001", "2");
                        if (isCgiStatusOk(r3001)) {
                            knownCameraMode = 2;
                        }
                    }
                    postSuccess(callback, true);
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    /**
     * Fetch list of video clips and still photos from SD card.
     * Note: Novatek NT966xx cameras REQUIRE stopping recording BEFORE stopping liveview,
     * then entering Playback mode (cmd=3001&par=2) and querying cmd=3015.
     */
    public void fetchClipList(final Callback<List<ClipItem>> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                StringBuilder diagLog = new StringBuilder();
                try {
                    // 1. Stop recording FIRST (cmd=2001&par=0) so cmd=2015&par=0 does not stall with Status -13
                    try {
                        sendCgiCommand("2001", "0");
                        diagLog.append("Stop rec ok; ");
                    } catch (Exception e) {
                        diagLog.append("Stop rec err: ").append(e.getMessage()).append("; ");
                    }

                    if (teardownActive) return;
                    // 2. Stop live stream encoder
                    try {
                        sendCgiCommand("2015", "0");
                    } catch (Exception ignored) {}

                    if (teardownActive) return;
                    // 3. Switch Novatek to Playback Mode (cmd=3001&par=2) - MANDATORY for cmd=3015!
                    try {
                        String r3001 = sendCgiCommand("3001", "2");
                        if (isCgiStatusOk(r3001)) {
                            knownCameraMode = 2;
                        }
                        diagLog.append("Set playback mode ok; ");
                    } catch (Exception e) {
                        diagLog.append("Set playback mode err: ").append(e.getMessage()).append("; ");
                    }

                    if (teardownActive) return;
                    // Brief settle time for camera to close active FAT directory handles
                    Thread.sleep(250);

                    List<ClipItem> clips = new ArrayList<ClipItem>();

                    // 4. Primary file list command on Novatek: cmd=3015
                    if (!teardownActive) {
                        try {
                            String xml3015 = sendCgiCommand("3015", null, 20000);
                            clips = XmlListParser.parseFileList(xml3015);
                            diagLog.append("3015: ").append(clips.size()).append(" items; ");
                            DebugLogger.log("CAM_SD", "cmd=3015 returned " + clips.size() + " media items");
                        } catch (Exception e) {
                            diagLog.append("3015 failed: ").append(e.getMessage()).append("; ");
                            DebugLogger.log("CAM_SD", "cmd=3015 failed: " + e.getMessage());
                        }
                    }

                    // 5. Fallback: try cmd=3035
                    if (clips.isEmpty() && !teardownActive) {
                        try {
                            String xml3035 = sendCgiCommand("3035", null);
                            clips = XmlListParser.parseFileList(xml3035);
                            diagLog.append("3035: ").append(clips.size()).append(" items; ");
                            DebugLogger.log("CAM_SD", "cmd=3035 returned " + clips.size() + " items");
                        } catch (Exception e) {
                            diagLog.append("3035 failed: ").append(e.getMessage()).append("; ");
                            DebugLogger.log("CAM_SD", "cmd=3035 failed: " + e.getMessage());
                        }
                    }

                    // 6. Fallback: try cmd=3031&str=all
                    if (clips.isEmpty() && !teardownActive) {
                        try {
                            String xml3031 = sendCgiCustom("3031", "str", "all");
                            clips = XmlListParser.parseFileList(xml3031);
                            diagLog.append("3031: ").append(clips.size()).append(" items; ");
                            DebugLogger.log("CAM_SD", "cmd=3031 returned " + clips.size() + " items");
                        } catch (Exception e) {
                            diagLog.append("3031 failed: ").append(e.getMessage()).append("; ");
                            DebugLogger.log("CAM_SD", "cmd=3031 failed: " + e.getMessage());
                        }
                    }

                    // 7. Supplemental still-photo directory check if firmware omitted PHOTO folder from cmd=3015
                    if (!teardownActive && socType == SocType.NOVATEK && !containsPhotoItem(clips)) {
                        supplementNovatekPhotoDirectory(clips);
                    }

                    // 8. Fallback: JieLi port 8080 (only if JieLi or unidentified SoC)
                    if (clips.isEmpty() && !teardownActive && (socType == SocType.JIELI || socType == SocType.UNKNOWN)) {
                        try {
                            String urlJL = "http://" + cameraIp + ":8080/FORWARD_MEDIA_FILES_LIST";
                            String respJL = httpGetDirectTracked(urlJL, 3000);
                            clips = XmlListParser.parseFileList(respJL);
                            diagLog.append("JieLi 8080: ").append(clips.size()).append(" items; ");
                            DebugLogger.log("CAM_SD", "JieLi 8080 returned " + clips.size() + " items");
                        } catch (Exception e) {
                            diagLog.append("JieLi 8080: ").append(e.getMessage()).append("; ");
                            DebugLogger.log("CAM_SD", "JieLi 8080 failed: " + e.getMessage());
                        }
                    }

                    if (teardownActive) return;

                    if (!clips.isEmpty()) {
                        postSuccess(callback, clips);
                    } else {
                        postError(callback, new Exception("No files returned. Trace: " + diagLog.toString()));
                    }
                } catch (Exception e) {
                    if (!teardownActive) {
                        postError(callback, new Exception(e.getMessage() + " [" + diagLog.toString() + "]"));
                    }
                }
            }
        });
    }

    private static boolean containsPhotoItem(List<ClipItem> items) {
        if (items == null) return false;
        for (ClipItem c : items) {
            if (c != null && c.isPhoto) return true;
        }
        return false;
    }

    private void supplementNovatekPhotoDirectory(List<ClipItem> clips) {
        String portPart = (httpPort > 0 && httpPort != 80) ? (":" + httpPort) : "";
        String[] photoDirs = new String[] { "CARDV/PHOTO/", "NOVATEK/PHOTO/", "PHOTO/" };
        Set<String> existingNames = new HashSet<String>();
        for (ClipItem c : clips) {
            existingNames.add(c.name.toLowerCase(Locale.US));
        }

        for (String dir : photoDirs) {
            if (teardownActive) return;
            try {
                String url = "http://" + cameraIp + portPart + "/" + dir;
                String html = httpGetDirectTracked(url, 800);
                List<ClipItem> foundPhotos = XmlListParser.parseFileList(html, dir);
                int added = 0;
                for (ClipItem p : foundPhotos) {
                    if (p.isPhoto && existingNames.add(p.name.toLowerCase(Locale.US))) {
                        clips.add(p);
                        added++;
                    }
                }
                if (added > 0) {
                    DebugLogger.log("CAM_SD", "Discovered " + added + " supplemental photos in /" + dir);
                    XmlListParser.sortClipsDescending(clips);
                    break;
                }
            } catch (Exception ignored) {
                // Fast 404 or unsupported directory index is expected on firmwares that include PHOTO in cmd=3015
            }
        }
    }

    /**
     * Start (true) or Stop (false) Recording
     */
    public void setRecording(final boolean start, final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                try {
                    if (start) {
                        try {
                            String rMode = sendCgiCommand("3001", "1");
                            if (isCgiStatusOk(rMode)) knownCameraMode = 1;
                        } catch (Exception ignored) {}
                    }
                    String resp = sendCgiCommand("2001", start ? "1" : "0");
                    if (!isCgiStatusOk(resp)) {
                        int st = parseCgiStatus(resp);
                        // On Novatek, stopping an already-stopped stream or starting an already-running stream may return -22; verify actual state
                        if (st != -22) {
                            throw new Exception("Camera rejected recording toggle (status=" + st + ")");
                        }
                    }
                    postSuccess(callback, true);
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    /**
     * Synchronously guarantees Video Mode (cmd=3001&par=1) + Resume Recording (cmd=2001&par=1)
     * have been delivered to the dashcam BEFORE Wi-Fi STA is torn down, bounded by maxWaitMs.
     * Also cancels any in-flight SD clip list query so Playback Mode cannot overwrite Movie Mode.
     */
    public void ensureRecordingBeforeTeardown(final Runnable onComplete, final long maxWaitMs) {
        teardownActive = true;
        abortActiveConnection();

        final AtomicBoolean done = new AtomicBoolean(false);
        final Runnable finishOnce = new Runnable() {
            @Override
            public void run() {
                if (done.compareAndSet(false, true) && onComplete != null) {
                    onComplete.run();
                }
            }
        };

        mainHandler.postDelayed(finishOnce, Math.max(250L, maxWaitMs));

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String portPart = (httpPort > 0 && httpPort != 80) ? (":" + httpPort) : "";
                    String base = "http://" + cameraIp + portPart + "/?custom=1&cmd=";
                    try {
                        httpGetDirect(base + "3001&par=1", 700);
                        knownCameraMode = 1;
                    } catch (Exception ignored) {}
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException ignored) {}
                    try {
                        httpGetDirect(base + "2001&par=1", 700);
                        DebugLogger.log("CAM_EXIT", "Verified dashcam loop recording resumed prior to Wi-Fi teardown");
                    } catch (Exception e) {
                        DebugLogger.log("CAM_EXIT", "Pre-teardown recording resume notice: " + e.getMessage());
                    }
                } finally {
                    mainHandler.post(finishOnce);
                }
            }
        }, "FitcamxExitRecGuard").start();
    }

    /**
     * Take a snapshot photo.
     * Uses cmd=2017 (PIM movie snapshot trigger) when video stream/recording is active,
     * falling back to cmd=1001 only if cmd=2017 is unsupported, and validates <Status>0</Status>.
     */
    public void takeSnapshot(final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                try {
                    boolean captured = false;
                    int lastStatus = Integer.MIN_VALUE;

                    // 1. Try movie-mode snapshot (cmd=2017)
                    try {
                        String r2017 = sendCgiCommand("2017", null);
                        DebugLogger.log("CAM_SNAP", "cmd=2017 (movie snapshot) response: " + (r2017 != null ? r2017.trim() : "null"));
                        if (isCgiStatusOk(r2017)) {
                            captured = true;
                        } else {
                            lastStatus = parseCgiStatus(r2017);
                        }
                    } catch (Exception e) {
                        DebugLogger.log("CAM_SNAP", "cmd=2017 notice: " + e.getMessage());
                    }

                    // 2. Fallback to cmd=1001 (still photo capture) if cmd=2017 did not succeed
                    if (!captured) {
                        String r1001 = sendCgiCommand("1001", null);
                        DebugLogger.log("CAM_SNAP", "cmd=1001 (photo capture) response: " + (r1001 != null ? r1001.trim() : "null"));
                        if (isCgiStatusOk(r1001)) {
                            captured = true;
                        } else {
                            lastStatus = parseCgiStatus(r1001);
                        }
                    }

                    if (!captured) {
                        throw new Exception("Camera rejected snapshot request (status=" + lastStatus + ")");
                    }
                    postSuccess(callback, true);
                } catch (Exception e) {
                    DebugLogger.log("CAM_SNAP", "Snapshot error: " + e.getMessage());
                    postError(callback, e);
                }
            }
        });
    }

    /**
     * Emergency Lock the current clip (flags event recording / locks file to RO/EMR directory)
     */
    public void emergencyLock(final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                try {
                    boolean lockAccepted = false;
                    int lastErrStatus = Integer.MIN_VALUE;

                    try {
                        String r2016 = sendCgiCommand("2016", null);
                        DebugLogger.log("CAM_LOCK", "cmd=2016 (rec elapsed sec): " + (r2016 != null ? r2016.trim() : "null"));
                    } catch (Exception ignored) {}

                    try {
                        String r2001 = sendCgiCommand("2001", "2");
                        DebugLogger.log("CAM_LOCK", "cmd=2001&par=2 (emergency event trigger) response: " + (r2001 != null ? r2001.trim() : "null"));
                        if (isCgiStatusOk(r2001)) {
                            lockAccepted = true;
                        } else {
                            lastErrStatus = parseCgiStatus(r2001);
                        }
                    } catch (Exception e) {
                        DebugLogger.log("CAM_LOCK", "cmd=2001&par=2 notice: " + e.getMessage());
                    }

                    if (!lockAccepted) {
                        try {
                            String r2011 = sendCgiCommand("2011", "3");
                            DebugLogger.log("CAM_LOCK", "cmd=2011&par=3 (G-sensor high/lock trigger) response: " + (r2011 != null ? r2011.trim() : "null"));
                            if (isCgiStatusOk(r2011)) {
                                lockAccepted = true;
                            } else {
                                lastErrStatus = parseCgiStatus(r2011);
                            }
                        } catch (Exception e) {
                            DebugLogger.log("CAM_LOCK", "cmd=2011 notice: " + e.getMessage());
                        }
                    }

                    // Ensure loop recording remains active
                    try {
                        sendCgiCommand("2001", "1");
                    } catch (Exception ignored) {}

                    if (!lockAccepted) {
                        throw new Exception("Camera rejected emergency lock command (status=" + lastErrStatus + ")");
                    }
                    postSuccess(callback, true);
                } catch (Exception e) {
                    DebugLogger.log("CAM_LOCK", "emergencyLock error: " + e.getMessage());
                    postError(callback, e);
                }
            }
        });
    }

    /**
     * Toggle microphone audio recording (cmd=2007: 1=on, 0=muted)
     */
    public void setAudioEnabled(final boolean enabled, final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                try {
                    String resp = sendCgiCommand("2007", enabled ? "1" : "0");
                    DebugLogger.log("CAM_MIC", "cmd=2007&par=" + (enabled ? "1" : "0") + " response: " + (resp != null ? resp.trim() : "null"));
                    if (!isCgiStatusOk(resp)) {
                        throw new Exception("Camera rejected microphone command (status=" + parseCgiStatus(resp) + ")");
                    }
                    postSuccess(callback, true);
                } catch (Exception e) {
                    DebugLogger.log("CAM_MIC", "cmd=2007 error: " + e.getMessage());
                    postError(callback, e);
                }
            }
        });
    }

    /**
     * Synchronize dashcam clock to the car's current system time (cmd=3005 date + cmd=3006 time)
     */
    public void syncCarTime(final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                try {
                    SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    SimpleDateFormat sdfTime = new SimpleDateFormat("HH:mm:ss", Locale.US);
                    Date now = new Date();

                    String rDate = sendCgiCustom("3005", "str", sdfDate.format(now));
                    String rTime = sendCgiCustom("3006", "str", sdfTime.format(now));
                    if (!isCgiStatusOk(rDate) || !isCgiStatusOk(rTime)) {
                        throw new Exception("Camera rejected clock sync");
                    }

                    postSuccess(callback, true);
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    /**
     * Query all camera menu options and active settings (cmd=3014 / cmd=3002)
     */
    public void queryCameraStatus(final Callback<String> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                try {
                    String statusXml = sendCgiCommand("3014", null);
                    if (statusXml == null || statusXml.isEmpty()) {
                        statusXml = sendCgiCommand("3002", null);
                    }
                    DebugLogger.log("CAM_STATUS", "Camera Status XML: " + (statusXml != null ? statusXml.trim() : "null"));
                    postSuccess(callback, statusXml);
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    /**
     * Change camera movie recording resolution (cmd=2002)
     * e.g., par=0 for 4K / highest, par=1 for 2K / 1080P
     */
    public void setVideoResolution(final int resolutionIndex, final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                if (teardownActive) return;
                try {
                    // Stop recording & liveview before reconfiguring encoder resolution
                    try { sendCgiCommand("2001", "0"); } catch (Exception ignored) {}
                    try { sendCgiCommand("2015", "0"); } catch (Exception ignored) {}
                    Thread.sleep(100);

                    try {
                        String rMode = sendCgiCommand("3001", "1");
                        if (isCgiStatusOk(rMode)) knownCameraMode = 1;
                    } catch (Exception ignored) {}
                    Thread.sleep(80);

                    String resp = sendCgiCommand("2002", String.valueOf(resolutionIndex));
                    DebugLogger.log("CAM_SETTINGS", "cmd=2002&par=" + resolutionIndex + " response: " + (resp != null ? resp.trim() : "null"));
                    if (!isCgiStatusOk(resp)) {
                        // Ensure recording resumes even if resolution change was rejected
                        try { sendCgiCommand("2015", "1"); } catch (Exception ignored) {}
                        try { sendCgiCommand("2001", "1"); } catch (Exception ignored) {}
                        throw new Exception("Camera rejected resolution change (status=" + parseCgiStatus(resp) + ")");
                    }

                    // Re-start liveview encoder & loop recording
                    Thread.sleep(80);
                    try { sendCgiCommand("2015", "1"); } catch (Exception ignored) {}
                    Thread.sleep(60);
                    try { sendCgiCommand("2001", "1"); } catch (Exception ignored) {}

                    postSuccess(callback, true);
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    // --- Low-level HTTP CGI methods ---

    public String sendCgiCommand(String cmd, String par) throws Exception {
        return sendCgiCustom(cmd, "par", par, 3500);
    }

    public String sendCgiCommand(String cmd, String par, int readTimeoutMs) throws Exception {
        return sendCgiCustom(cmd, "par", par, readTimeoutMs);
    }

    public String sendCgiCustom(String cmd, String paramKey, String paramVal) throws Exception {
        return sendCgiCustom(cmd, paramKey, paramVal, 3500);
    }

    public String sendCgiCustom(String cmd, String paramKey, String paramVal, int readTimeoutMs) throws Exception {
        StringBuilder urlBuilder = new StringBuilder("http://");
        urlBuilder.append(cameraIp);
        if (httpPort > 0 && httpPort != 80) {
            urlBuilder.append(":").append(httpPort);
        }
        urlBuilder.append("/?custom=1&cmd=").append(cmd);
        if (paramVal != null && !paramVal.isEmpty()) {
            String encoded = URLEncoder.encode(paramVal, "UTF-8").replace("+", "%20");
            urlBuilder.append("&").append(paramKey).append("=").append(encoded);
        }

        return httpGetDirectImpl(urlBuilder.toString(), 3500, Math.max(readTimeoutMs, 5000), this);
    }

    private String httpGetDirectTracked(String urlString, int timeoutMs) throws Exception {
        return httpGetDirectImpl(urlString, timeoutMs, Math.min(timeoutMs + 1500, timeoutMs * 2), this);
    }

    public static String httpGetDirect(String urlString, int timeoutMs) throws Exception {
        return httpGetDirectImpl(urlString, timeoutMs, Math.min(timeoutMs + 1500, timeoutMs * 2), null);
    }

    private static String httpGetDirectImpl(String urlString, int connectTimeoutMs, int readTimeoutMs, CameraClient owner) throws Exception {
        long start = System.currentTimeMillis();
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (owner != null) {
            owner.activeConnection = conn;
        }
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Connection", "close");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);

        try {
            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - start;
            if (code != 200) {
                StringBuilder errBody = new StringBuilder();
                try {
                    InputStream es = conn.getErrorStream();
                    if (es != null) {
                        InputStreamReader r = new InputStreamReader(es, "UTF-8");
                        char[] cbuf = new char[256];
                        int n = r.read(cbuf);
                        if (n > 0) errBody.append(cbuf, 0, n);
                        r.close();
                    }
                } catch (Exception ignored) {}
                String msg = "HTTP " + code + " for " + url + " (" + elapsed + "ms) - " + errBody.toString().trim();
                DebugLogger.log("CAM_HTTP", "[FAIL] " + msg);
                throw new Exception(msg);
            }

            int contentLen = conn.getContentLength();
            int initCap = (contentLen > 0 && contentLen < 2 * 1024 * 1024) ? contentLen : 512;
            StringBuilder sb = new StringBuilder(initCap);
            InputStream in = conn.getInputStream();
            InputStreamReader reader = new InputStreamReader(in, "UTF-8");
            char[] buf = new char[8192];
            int nRead;
            while ((nRead = reader.read(buf)) != -1) {
                sb.append(buf, 0, nRead);
            }
            reader.close();

            elapsed = System.currentTimeMillis() - start;
            String result = sb.toString();
            int prevLen = Math.min(100, result.length());
            String preview = result.substring(0, prevLen).replace('\n', ' ').replace('\r', ' ');
            if (result.length() > 100) preview = preview + "...";
            DebugLogger.log("CAM_HTTP", "[OK " + code + "] (" + elapsed + "ms) " + urlString + " -> " + preview);
            return result;
        } catch (Exception e) {
            long errElapsed = System.currentTimeMillis() - start;
            DebugLogger.log("CAM_HTTP", "[ERR (" + errElapsed + "ms)] " + urlString + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        } finally {
            if (owner != null && owner.activeConnection == conn) {
                owner.activeConnection = null;
            }
            conn.disconnect();
        }
    }

    private <T> void postSuccess(final Callback<T> cb, final T val) {
        if (cb != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    cb.onSuccess(val);
                }
            });
        }
    }

    private <T> void postError(final Callback<T> cb, final Exception err) {
        if (cb != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    cb.onError(err);
                }
            });
        }
    }
}
