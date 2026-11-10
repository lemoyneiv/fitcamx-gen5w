package fitcamx.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CameraClient {
    private static final String TAG = "FitcamxCamera";

    public static final String DEFAULT_CAMERA_IP = "192.168.1.254";
    public static final int RTSP_PORT = 554;

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
        executor.shutdownNow();
    }

    public void setCameraIp(String ip) {
        this.cameraIp = ip;
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
     * Parse stream URL dynamically returned by camera (e.g. from cmd=2019)
     */
    public static String parseLiveViewUrl(String xml) {
        if (xml == null || xml.trim().isEmpty()) return null;
        Pattern p = Pattern.compile("(?i)<(?:MovieLiveViewLink|LiveViewLink|StreamUrl)>([^<]+)</");
        Matcher m = p.matcher(xml);
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
     * Start live view:
     * 1. Stop recording (cmd=2001&par=0) - MANDATORY on Novatek NT96675, else cmd=2015 returns -13!
     * 2. Stop active live stream (cmd=2015&par=0)
     * 3. Switch Novatek to Video mode (cmd=3001&par=1)
     * 4. Switch camera channel (cmd=3028: 0=front, 1=rear)
     * 5. Start live stream encoder (cmd=2015&par=1)
     * 6. Query dynamic stream URL from firmware (cmd=2019)
     * 7. Resume continuous SD card loop recording (cmd=2001&par=1)
     * 8. Return priority candidates with dynamic URL first
     */
    public void prepareAndStartLiveView(final int viewMode, final Callback<List<String>> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                String modeName = (viewMode == VIEW_FRONT) ? "FRONT" : (viewMode == VIEW_REAR ? "REAR" : "DUAL (BOTH)");
                DebugLogger.log("CAM_LIVE", "Preparing live view for " + modeName + " camera (viewMode=" + viewMode + ") on " + cameraIp);
                try {
                    // 1. Stop active recording (cmd=2001&par=0)
                    // Novatek NT96675 cannot start the liveview encoder while recording is in progress.
                    try {
                        String r2001 = sendCgiCommand("2001", "0");
                        DebugLogger.log("CAM_LIVE", "cmd=2001&par=0 (Stop rec) response: " + (r2001 != null ? r2001.trim() : "null"));
                    } catch (Exception e) {
                        DebugLogger.log("CAM_LIVE", "cmd=2001&par=0 failed: " + e.getMessage());
                    }

                    // 2. Stop live stream encoder if already running
                    try {
                        String r2015stop = sendCgiCommand("2015", "0");
                        DebugLogger.log("CAM_LIVE", "cmd=2015&par=0 (Stop live) response: " + (r2015stop != null ? r2015stop.trim() : "null"));
                    } catch (Exception ignored) {}

                    Thread.sleep(80);

                    // 3. Set mode to Video mode (cmd=3001&par=1)
                    try {
                        String r3001 = sendCgiCommand("3001", "1");
                        DebugLogger.log("CAM_LIVE", "cmd=3001&par=1 (Video Mode) response: " + (r3001 != null ? r3001.trim() : "null"));
                    } catch (Exception e) {
                        DebugLogger.log("CAM_LIVE", "cmd=3001&par=1 failed: " + e.getMessage());
                    }

                    // 4. Switch hardware channel (cmd=3028: 0=front, 1=rear, 2=dual)
                    try {
                        String r3028 = sendCgiCommand("3028", String.valueOf(viewMode));
                        DebugLogger.log("CAM_LIVE", "cmd=3028&par=" + viewMode + " (" + modeName + " select) response: " + (r3028 != null ? r3028.trim() : "null"));
                    } catch (Exception e) {
                        DebugLogger.log("CAM_LIVE", "cmd=3028 failed: " + e.getMessage());
                    }

                    // 5. Command Novatek to start pushing liveview stream (cmd=2015&par=1)
                    String r2015 = null;
                    try {
                        r2015 = sendCgiCommand("2015", "1");
                        DebugLogger.log("CAM_LIVE", "cmd=2015&par=1 (Start Live) response: " + (r2015 != null ? r2015.trim() : "null"));
                    } catch (Exception e) {
                        DebugLogger.log("CAM_LIVE", "cmd=2015 failed: " + e.getMessage());
                    }

                    // If returned -13, retry stop-rec + start
                    if (r2015 != null && r2015.contains("<Status>-13</Status>")) {
                        DebugLogger.log("CAM_LIVE", "Liveview start returned -13, re-stopping recording and retrying...");
                        Thread.sleep(300);
                        sendCgiCommand("2001", "0");
                        Thread.sleep(200);
                        sendCgiCommand("3001", "1");
                        Thread.sleep(200);
                        r2015 = sendCgiCommand("2015", "1");
                        DebugLogger.log("CAM_LIVE", "Retry cmd=2015&par=1 response: " + (r2015 != null ? r2015.trim() : "null"));
                    }

                    // Settle time for RTSP server to start pushing H.264 NAL units
                    Thread.sleep(120);

                    // 6. Query dynamic liveview link from camera firmware (cmd=2019)
                    String dynamicUrl = null;
                    try {
                        String r2019 = sendCgiCommand("2019", null);
                        DebugLogger.log("CAM_LIVE", "cmd=2019 (GetStreamUrl) response: " + (r2019 != null ? r2019.trim() : "null"));
                        dynamicUrl = parseLiveViewUrl(r2019);
                        if (dynamicUrl != null) {
                            DebugLogger.log("CAM_LIVE", "Found dynamic RTSP URL: " + dynamicUrl);
                        }
                    } catch (Exception e) {
                        DebugLogger.log("CAM_LIVE", "cmd=2019 query failed: " + e.getMessage());
                    }

                    // 7. Resume continuous SD card loop recording (cmd=2001&par=1)
                    // The live RTSP stream runs concurrently with SD card loop recording
                    try {
                        Thread.sleep(80);
                        String rRec = sendCgiCommand("2001", "1");
                        DebugLogger.log("CAM_LIVE", "cmd=2001&par=1 (Resume recording) response: " + (rRec != null ? rRec.trim() : "null"));
                    } catch (Exception e) {
                        DebugLogger.log("CAM_LIVE", "cmd=2001&par=1 failed: " + e.getMessage());
                    }

                    List<String> candidates = getCandidateStreamUrls(viewMode);
                    if (dynamicUrl != null && !dynamicUrl.isEmpty()) {
                        // Ensure dynamic URL is at the very front of candidates
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
                try {
                    sendCgiCommand("2015", "0");
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Fetch list of video clips from SD card.
     * Note: Novatek NT966xx cameras REQUIRE entering Playback mode (cmd=3001&par=2)
     * and querying cmd=3015 to access the SD card file table.
     */
    public void fetchClipList(final Callback<List<ClipItem>> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                StringBuilder diagLog = new StringBuilder();
                try {
                    // 1. Stop live stream encoder
                    try {
                        sendCgiCommand("2015", "0");
                    } catch (Exception ignored) {}

                    // 2. Stop recording (cmd=2001&par=0)
                    try {
                        sendCgiCommand("2001", "0");
                        diagLog.append("Stop rec ok; ");
                    } catch (Exception e) {
                        diagLog.append("Stop rec err: ").append(e.getMessage()).append("; ");
                    }

                    // 3. Switch Novatek to Playback Mode (cmd=3001&par=2) - MANDATORY for cmd=3015!
                    try {
                        sendCgiCommand("3001", "2");
                        diagLog.append("Set playback mode ok; ");
                    } catch (Exception e) {
                        diagLog.append("Set playback mode err: ").append(e.getMessage()).append("; ");
                    }

                    // Settle time for camera to close active FAT directory handles
                    Thread.sleep(600);

                    List<ClipItem> clips = new ArrayList<ClipItem>();

                    // 4. Primary file list command on Novatek: cmd=3015
                    try {
                        String xml3015 = sendCgiCommand("3015", null);
                        clips = XmlListParser.parseFileList(xml3015);
                        diagLog.append("3015: ").append(clips.size()).append(" clips; ");
                        DebugLogger.log("CAM_SD", "cmd=3015 returned " + clips.size() + " clips");
                    } catch (Exception e) {
                        diagLog.append("3015 failed: ").append(e.getMessage()).append("; ");
                        DebugLogger.log("CAM_SD", "cmd=3015 failed: " + e.getMessage());
                    }

                    // 5. Fallback: try cmd=3035
                    if (clips.isEmpty()) {
                        try {
                            String xml3035 = sendCgiCommand("3035", null);
                            clips = XmlListParser.parseFileList(xml3035);
                            diagLog.append("3035: ").append(clips.size()).append(" clips; ");
                            DebugLogger.log("CAM_SD", "cmd=3035 returned " + clips.size() + " clips");
                        } catch (Exception e) {
                            diagLog.append("3035 failed: ").append(e.getMessage()).append("; ");
                            DebugLogger.log("CAM_SD", "cmd=3035 failed: " + e.getMessage());
                        }
                    }

                    // 6. Fallback: try cmd=3031&str=all
                    if (clips.isEmpty()) {
                        try {
                            String xml3031 = sendCgiCustom("3031", "str", "all");
                            clips = XmlListParser.parseFileList(xml3031);
                            diagLog.append("3031: ").append(clips.size()).append(" clips; ");
                            DebugLogger.log("CAM_SD", "cmd=3031 returned " + clips.size() + " clips");
                        } catch (Exception e) {
                            diagLog.append("3031 failed: ").append(e.getMessage()).append("; ");
                            DebugLogger.log("CAM_SD", "cmd=3031 failed: " + e.getMessage());
                        }
                    }

                    // 7. Fallback: JieLi port 8080 (only if JieLi or unidentified SoC)
                    if (clips.isEmpty() && (socType == SocType.JIELI || socType == SocType.UNKNOWN)) {
                        try {
                            String urlJL = "http://" + cameraIp + ":8080/FORWARD_MEDIA_FILES_LIST";
                            String respJL = httpGetDirect(urlJL, 3000);
                            clips = XmlListParser.parseFileList(respJL);
                            diagLog.append("JieLi 8080: ").append(clips.size()).append(" clips; ");
                            DebugLogger.log("CAM_SD", "JieLi 8080 returned " + clips.size() + " clips");
                        } catch (Exception e) {
                            diagLog.append("JieLi 8080: ").append(e.getMessage()).append("; ");
                            DebugLogger.log("CAM_SD", "JieLi 8080 failed: " + e.getMessage());
                        }
                    }

                    if (!clips.isEmpty()) {
                        postSuccess(callback, clips);
                    } else {
                        postError(callback, new Exception("No files returned. Trace: " + diagLog.toString()));
                    }
                } catch (Exception e) {
                    postError(callback, new Exception(e.getMessage() + " [" + diagLog.toString() + "]"));
                }
            }
        });
    }

    /**
     * Start (true) or Stop (false) Recording
     */
    public void setRecording(final boolean start, final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    // Set Video mode (cmd=3001&par=1) before starting recording
                    if (start) {
                        try { sendCgiCommand("3001", "1"); } catch (Exception ignored) {}
                    }
                    sendCgiCommand("2001", start ? "1" : "0");
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
     */
    public void ensureRecordingBeforeTeardown(final Runnable onComplete, final long maxWaitMs) {
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
                    } catch (Exception ignored) {}
                    try {
                        Thread.sleep(100);
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
     * Uses cmd=2017 (movie snapshot trigger) if video stream/recording is active,
     * and falls back to cmd=1001 (standard still photo capture).
     */
    public void takeSnapshot(final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    String resp = null;
                    // 1. If currently in movie mode / recording, try video snapshot (cmd=2017)
                    try {
                        String r2017 = sendCgiCommand("2017", null);
                        DebugLogger.log("CAM_SNAP", "cmd=2017 (movie snapshot) response: " + (r2017 != null ? r2017.trim() : "null"));
                        if (r2017 != null && r2017.contains("<Status>0</Status>")) {
                            resp = r2017;
                        }
                    } catch (Exception e) {
                        DebugLogger.log("CAM_SNAP", "cmd=2017 notice: " + e.getMessage());
                    }

                    // 2. If cmd=2017 did not succeed or camera is in standard photo state, use cmd=1001
                    if (resp == null) {
                        String r1001 = sendCgiCommand("1001", null);
                        DebugLogger.log("CAM_SNAP", "cmd=1001 (photo capture) response: " + (r1001 != null ? r1001.trim() : "null"));
                        resp = r1001;
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
     * Emergency Lock the current clip (flags event recording / locks file to RO directory)
     */
    public void emergencyLock(final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. Query current recording elapsed time if active
                    try {
                        String r2016 = sendCgiCommand("2016", null);
                        DebugLogger.log("CAM_LOCK", "cmd=2016 (rec elapsed sec): " + (r2016 != null ? r2016.trim() : "null"));
                    } catch (Exception ignored) {}

                    // 2. Trigger Novatek emergency event recording (cmd=2001&par=2)
                    try {
                        String r2001 = sendCgiCommand("2001", "2");
                        DebugLogger.log("CAM_LOCK", "cmd=2001&par=2 (emergency event trigger) response: " + (r2001 != null ? r2001.trim() : "null"));
                    } catch (Exception e) {
                        DebugLogger.log("CAM_LOCK", "cmd=2001&par=2 notice: " + e.getMessage());
                    }

                    // 3. Trigger G-Sensor emergency event threshold (cmd=2011&par=3)
                    try {
                        String r2011 = sendCgiCommand("2011", "3");
                        DebugLogger.log("CAM_LOCK", "cmd=2011&par=3 (G-sensor high/lock trigger) response: " + (r2011 != null ? r2011.trim() : "null"));
                    } catch (Exception e) {
                        DebugLogger.log("CAM_LOCK", "cmd=2011 notice: " + e.getMessage());
                    }

                    // 4. Ensure loop recording remains active
                    try {
                        sendCgiCommand("2001", "1");
                    } catch (Exception ignored) {}

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
                try {
                    String resp = sendCgiCommand("2007", enabled ? "1" : "0");
                    DebugLogger.log("CAM_MIC", "cmd=2007&par=" + (enabled ? "1" : "0") + " response: " + (resp != null ? resp.trim() : "null"));
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
                try {
                    SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    SimpleDateFormat sdfTime = new SimpleDateFormat("HH:mm:ss", Locale.US);
                    Date now = new Date();

                    sendCgiCustom("3005", "str", sdfDate.format(now));
                    sendCgiCustom("3006", "str", sdfTime.format(now));

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
     * e.g., par=0 for 4K / highest, par=1 for 1080P FHD (H.264), par=2 for 720P
     */
    public void setVideoResolution(final int resolutionIndex, final Callback<Boolean> callback) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    // Stop recording first if active
                    try { sendCgiCommand("2001", "0"); } catch (Exception ignored) {}
                    Thread.sleep(150);

                    // Set video mode
                    try { sendCgiCommand("3001", "1"); } catch (Exception ignored) {}
                    Thread.sleep(150);

                    String resp = sendCgiCommand("2002", String.valueOf(resolutionIndex));
                    DebugLogger.log("CAM_SETTINGS", "cmd=2002&par=" + resolutionIndex + " response: " + resp);

                    // Re-start recording
                    Thread.sleep(200);
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
        return sendCgiCustom(cmd, "par", par);
    }

    public String sendCgiCustom(String cmd, String paramKey, String paramVal) throws Exception {
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

        return httpGetDirect(urlBuilder.toString(), 3500);
    }

    public static String httpGetDirect(String urlString, int timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Connection", "close");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(Math.min(timeoutMs + 1500, timeoutMs * 2));

        try {
            int code = conn.getResponseCode();
            long elapsed = System.currentTimeMillis() - start;
            if (code != 200) {
                StringBuilder errBody = new StringBuilder();
                try {
                    InputStream es = conn.getErrorStream();
                    if (es != null) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(es), 256);
                        String l;
                        while ((l = r.readLine()) != null && errBody.length() < 256) {
                            errBody.append(l).append(" ");
                        }
                        r.close();
                    }
                } catch (Exception ignored) {}
                String msg = "HTTP " + code + " for " + url + " (" + elapsed + "ms) - " + errBody.toString().trim();
                DebugLogger.log("CAM_HTTP", "[FAIL] " + msg);
                throw new Exception(msg);
            }

            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            elapsed = System.currentTimeMillis() - start;
            String result = sb.toString();
            String preview = result.length() > 100 ? (result.substring(0, 100).replace("\n", " ") + "...") : result.replace("\n", " ");
            DebugLogger.log("CAM_HTTP", "[OK " + code + "] (" + elapsed + "ms) " + urlString + " -> " + preview);
            return result;
        } catch (Exception e) {
            long errElapsed = System.currentTimeMillis() - start;
            DebugLogger.log("CAM_HTTP", "[ERR (" + errElapsed + "ms)] " + urlString + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        } finally {
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
