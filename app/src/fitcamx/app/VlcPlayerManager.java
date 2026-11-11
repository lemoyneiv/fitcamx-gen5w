package fitcamx.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;
import java.util.List;

/**
 * High-performance VideoLAN (LibVLC) streaming and playback engine for Gen5W.
 *
 * Features:
 * 1. Bundled FFmpeg libavcodec software decoders for H.264 (AVC) and H.265 (HEVC),
 *    completely bypassing Android 4.4 KitKat's mediaserver / Stagefright (no what=100 SERVER_DIED crashes).
 * 2. Native RTSP-over-TCP client bypasses Gen5W cellular UDP drops.
 * 3. Dynamic Best-Fit Aspect Ratio Scaler: automatically scales 16:9, 4:3, and dual-sensor
 *    streams to fit the viewing area with letterbox/pillarbox and centers them perfectly.
 */
public class VlcPlayerManager {
    private static final String TAG = "VlcPlayerManager";

    static {
        // Pre-load C++ runtime and VLC native libraries in dependency order for Android 4.4 KitKat
        try {
            System.loadLibrary("c++_shared");
            Log.i(TAG, "Loaded libc++_shared.so successfully");
        } catch (Throwable t) {
            Log.w(TAG, "Pre-load libc++_shared warning: " + t.getMessage());
        }
        try {
            System.loadLibrary("vlc");
            Log.i(TAG, "Loaded libvlc.so successfully");
        } catch (Throwable t) {
            Log.w(TAG, "Pre-load libvlc warning: " + t.getMessage());
        }
        try {
            System.loadLibrary("vlcjni");
            Log.i(TAG, "Loaded libvlcjni.so successfully");
        } catch (Throwable t) {
            Log.w(TAG, "Pre-load libvlcjni warning: " + t.getMessage());
        }
    }

    public static final long LIVE_OPEN_TIMEOUT_MS = 8500L;
    public static final long LIVE_STALL_TIMEOUT_MS = 1200L;
    public static final long LIVE_WATCHDOG_POLL_MS = 250L;

    public interface LiveListener {
        void onPlaying();
        void onBuffering(float percent);
        void onStreamStalled(String reason);
        void onError(String message);
        void onStopped();
    }

    public interface GalleryListener {
        void onPlaying(boolean seekable, long totalLengthMs);
        void onVideoOutputStarted();
        void onBuffering(float percent);
        void onProgressChanged(float position, long currentTimeMs, long totalLengthMs, boolean seekable);
        void onEndReached();
        void onError(String message);
    }

    /**
     * Determines whether a Buffering event should display the gallery loading spinner.
     * - Before video output (Vout) has started, any buffering < 100% shows the spinner.
     * - Once video frames are actively rendering (hasVout == true), only show the spinner
     *   on genuine buffer underruns or seek rebuffers (percent < 25%), suppressing minor
     *   HTTP cache fluctuations so the spinner never overlays smoothly playing video.
     */
    public static boolean shouldShowSpinnerOnBuffering(float percent, boolean hasVout) {
        if (percent >= 100.0f) {
            return false;
        }
        return !hasVout || percent < 25.0f;
    }

    /**
     * Determines whether active playback progress should clear a lingering loading spinner
     * (guards against MPEG-TS HTTP streams that resume decoding without emitting a final
     * Buffering(100.0f) event).
     */
    public static boolean shouldClearSpinnerOnProgress(boolean spinnerVisible, boolean hasActiveVideo,
                                                       long lastIncompleteBufferingMs, long nowUptimeMs) {
        if (!spinnerVisible || !hasActiveVideo) {
            return false;
        }
        return lastIncompleteBufferingMs <= 0L || (nowUptimeMs - lastIncompleteBufferingMs) >= 400L;
    }

    /**
     * Returns the default initial playback rate for a gallery clip:
     * - 4K / 2K HEVC streams ALWAYS default to 0.33x rate dilation.
     * - 1080p AVC streams ALWAYS default to 1.0x full speed.
     */
    public static float getDefaultGalleryRate(boolean isHevc) {
        return isHevc ? 0.33f : 1.0f;
    }

    /**
     * Cycles playback speed within hardware-safe limits for the active clip's codec:
     * - 4K / 2K HEVC: toggles strictly between 0.33x <-> 0.5x (1.0x is disallowed as 4K software decode drops frames at 1.0x).
     * - 1080p AVC: cycles 1.0x -> 0.5x -> 0.33x -> 1.0x so users can slow down 1080p clips if desired.
     */
    public static float getNextGalleryRate(float currentRate, boolean isHevc) {
        if (isHevc) {
            return (currentRate < 0.4f) ? 0.5f : 0.33f;
        }
        if (currentRate > 0.8f) {
            return 0.5f;
        } else if (currentRate > 0.4f) {
            return 0.33f;
        } else {
            return 1.0f;
        }
    }

    /**
     * Ensures any requested rate stays within valid bounds for the clip's codec.
     */
    public static float sanitizeGalleryRate(float requestedRate, boolean isHevc) {
        if (isHevc) {
            if (requestedRate >= 0.45f && requestedRate <= 0.55f) {
                return 0.5f;
            }
            return 0.33f;
        }
        if (requestedRate > 0.05f && requestedRate <= 1.05f) {
            return requestedRate;
        }
        return 1.0f;
    }

    /**
     * Pure helper: determines whether an active live stream has stalled due to missing RTP/video progress.
     */
    public static boolean isLiveStreamStalled(boolean hasPlayed, long lastProgressUptimeMs,
                                              long nowUptimeMs, long stallThresholdMs) {
        if (!hasPlayed || lastProgressUptimeMs <= 0L) {
            return false;
        }
        return (nowUptimeMs - lastProgressUptimeMs) >= stallThresholdMs;
    }

    /**
     * Pure helper: determines whether a live stream connection attempt hung in Opening state.
     */
    public static boolean isLiveOpenTimedOut(boolean hasPlayed, long openStartUptimeMs,
                                             long nowUptimeMs, long openTimeoutMs) {
        if (hasPlayed || openStartUptimeMs <= 0L) {
            return false;
        }
        return (nowUptimeMs - openStartUptimeMs) >= openTimeoutMs;
    }

    /**
     * Pure helper: determines whether LibVLC's input/demux/decoder/vout counters have genuinely
     * advanced since the previous watchdog sample. Strictly ignores zero-valued counters and
     * synthetic wall-clock TimeChanged ticks so RTP starvation is detected within LIVE_STALL_TIMEOUT_MS
     * instead of waiting 10 seconds for live555's internal inactivity timeout.
     */
    public static boolean didLiveStatsAdvance(int prevReadBytes, int prevDemuxBytes,
                                              int prevDecodedVideo, int prevDisplayedPics,
                                              int curReadBytes, int curDemuxBytes,
                                              int curDecodedVideo, int curDisplayedPics) {
        return (curReadBytes > 0 && curReadBytes != prevReadBytes)
                || (curDemuxBytes > 0 && curDemuxBytes != prevDemuxBytes)
                || (curDecodedVideo > 0 && curDecodedVideo != prevDecodedVideo)
                || (curDisplayedPics > 0 && curDisplayedPics != prevDisplayedPics);
    }

    /**
     * Pure helper: builds global LibVLC instance CLI arguments.
     * Note: In VLC 3.0, vout_thread_t inherits "video-filter" and "transform-type" from
     * libvlc_media_player_t -> LibVLC (NOT from input_thread_t / Media.addOption). Therefore,
     * horizontal mirroring uses a dedicated LibVLC + MediaPlayer pair configured with
     * --video-filter=transform and --transform-type=hflip, leaving standard live & SD gallery
     * playback unflipped.
     */
    public static ArrayList<String> buildEngineOptions(boolean mirrorHorizontal) {
        ArrayList<String> options = new ArrayList<String>(10);
        // RTSP over TCP is mandatory for Gen5W cellular firewall / rmnet0 bypass
        options.add("--rtsp-tcp");
        // Dedicated 4 worker threads for the 4 physical Cortex-A7 CPU cores
        options.add("--avcodec-threads=4");
        // Software decoding pipeline: bypass buggy OMX.TCC.avcdec on KitKat
        options.add("--avcodec-hw=none");
        // Fast decoding optimizations (fast IDCT, simplified motion compensation)
        options.add("--avcodec-fast");
        // Disable OSD & subtitle font scanning (avoids missing /system/etc/fonts.xml errors on Gen5W)
        options.add("--no-osd");
        options.add("--no-spu");
        if (mirrorHorizontal) {
            options.add("--video-filter=transform");
            options.add("--transform-type=hflip");
        }
        return options;
    }

    /**
     * Pure helper: builds LibVLC per-media options for Live RTSP/HTTP streaming.
     */
    public static List<String> buildLiveMediaOptions(boolean mirrorHorizontal) {
        List<String> opts = new ArrayList<String>(8);
        opts.add(":rtsp-tcp");
        opts.add(":network-caching=300");
        opts.add(":ipv4-timeout=5000");
        opts.add(":no-audio"); // mute live preview to avoid vehicle audio interruption
        opts.add(":avcodec-threads=4");
        opts.add(":avcodec-fast");
        opts.add(":clock-jitter=0");
        opts.add(":clock-synchro=0");
        return opts;
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Runnable> pendingReadyActions = new ArrayList<Runnable>();

    private volatile boolean initialized = false;
    private volatile boolean initFailed = false;
    private volatile boolean released = false;

    private LibVLC libVLC;
    private LibVLC mirrorLibVLC;
    private MediaPlayer livePlayer;
    private MediaPlayer mirrorLivePlayer;
    private MediaPlayer activeLivePlayer;
    private MediaPlayer galleryPlayer;

    private volatile LiveListener liveListener;
    private volatile GalleryListener galleryListener;
    private volatile int liveSessionGen = 0;
    private volatile int gallerySessionGen = 0;
    private volatile boolean liveStopNeeded = false;
    private volatile boolean liveHasPlayed = false;
    private volatile long liveOpenStartUptimeMs = 0L;
    private volatile long lastLiveProgressUptimeMs = 0L;
    private int lastLiveReadBytes = 0;
    private int lastLiveDemuxBytes = 0;
    private int lastLiveDecodedVideo = 0;
    private int lastLiveDisplayedPictures = 0;
    private volatile boolean galleryStopNeeded = false;
    private volatile boolean galleryOpening = false;

    private SurfaceView attachedLiveSurfaceView = null;
    private SurfaceView attachedGallerySurfaceView = null;

    private double liveAspect = 16.0 / 9.0;
    private double galleryAspect = 16.0 / 9.0;
    private volatile float targetGalleryRate = 1.0f;
    private volatile boolean galleryPaused = false;
    private volatile boolean gallerySeekable = false;
    private volatile long galleryLengthMs = 0L;
    private volatile float pendingSeekPosition = -1.0f;

    public VlcPlayerManager(Context ctx) {
        this.context = ctx.getApplicationContext();
        initEngineAsync();
    }

    private void initEngineAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final LibVLC createdLib = new LibVLC(context, buildEngineOptions(false));
                    final MediaPlayer createdLive = new MediaPlayer(createdLib);
                    final MediaPlayer createdGallery = new MediaPlayer(createdLib);

                    LibVLC tempMirrorLib = null;
                    MediaPlayer tempMirrorLive = null;
                    try {
                        tempMirrorLib = new LibVLC(context, buildEngineOptions(true));
                        tempMirrorLive = new MediaPlayer(tempMirrorLib);
                    } catch (Throwable mirrorErr) {
                        Log.w(TAG, "Mirror LibVLC init warning: " + mirrorErr.getMessage());
                        DebugLogger.log("VLC", "Mirror LibVLC init warning: " + mirrorErr.getMessage());
                        if (tempMirrorLib != null) {
                            try { tempMirrorLib.release(); } catch (Throwable ignored) {}
                            tempMirrorLib = null;
                        }
                    }
                    final LibVLC createdMirrorLib = tempMirrorLib;
                    final MediaPlayer createdMirrorLive = tempMirrorLive;

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (released) {
                                releaseNativeObjectsAsync(createdLive, createdMirrorLive, createdGallery, createdLib, createdMirrorLib);
                                return;
                            }
                            libVLC = createdLib;
                            mirrorLibVLC = createdMirrorLib;
                            livePlayer = createdLive;
                            mirrorLivePlayer = createdMirrorLive;
                            activeLivePlayer = createdLive;
                            galleryPlayer = createdGallery;
                            initialized = true;

                            DebugLogger.log("VLC", "Consolidated LibVLC engine initialized asynchronously (ARM NEON 4-thread software decoder + native hflip mirror engine="
                                    + (createdMirrorLive != null) + ")");
                            flushPendingActions();
                        }
                    });
                } catch (final Throwable t) {
                    Log.e(TAG, "Failed to initialize LibVLC engine", t);
                    DebugLogger.log("VLC", "LibVLC async init error: " + t.getMessage());
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            initFailed = true;
                            flushPendingActions();
                        }
                    });
                }
            }
        }, "FitcamxVlcInit").start();
    }

    private void runWhenReady(Runnable action) {
        if (released) return;
        if (initialized || initFailed) {
            action.run();
        } else {
            pendingReadyActions.add(action);
        }
    }

    private void flushPendingActions() {
        List<Runnable> copy = new ArrayList<Runnable>(pendingReadyActions);
        pendingReadyActions.clear();
        for (Runnable r : copy) {
            r.run();
        }
    }

    public boolean isInitialized() {
        return initialized && !released && libVLC != null && livePlayer != null && galleryPlayer != null;
    }

    public boolean isAvailable() {
        return !released && !initFailed;
    }

    // =========================================================================
    // DYNAMIC SURFACE SCALING & CENTERING
    // =========================================================================

    /**
     * Attach a SurfaceView with dynamic aspect ratio listener so the video frame
     * is perfectly centered and scaled to fit inside its parent container without
     * overflow or misalignment.
     *
     * Note: We NEVER call surfaceView.getHolder().setFixedSize(). On Android KitKat,
     * setFixedSize destroys the active GraphicBuffer in SurfaceFlinger, crashing the
     * native rendering pipeline and turning HEVC frames into gray noise.
     */
    private void attachWithLayoutScaler(final MediaPlayer player, final SurfaceView surfaceView, final boolean isLive) {
        final IVLCVout vout = player.getVLCVout();
        if (vout.areViewsAttached()) {
            vout.detachViews();
        }

        vout.setVideoView(surfaceView);
        vout.attachViews(new IVLCVout.OnNewVideoLayoutListener() {
            @Override
            public void onNewVideoLayout(IVLCVout vout, int width, int height,
                                         int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
                double aspect = 16.0 / 9.0;
                if (visibleWidth > 0 && visibleHeight > 0) {
                    aspect = (double) visibleWidth / (double) visibleHeight;
                    if (sarNum > 0 && sarDen > 0) {
                        aspect = aspect * ((double) sarNum / (double) sarDen);
                    }
                }
                if (isLive) {
                    liveAspect = aspect;
                } else {
                    galleryAspect = aspect;
                }
                applyVideoLayout(vout, surfaceView, aspect);
            }
        });

        applyVideoLayout(vout, surfaceView, isLive ? liveAspect : galleryAspect);
    }

    private void applyVideoLayout(final IVLCVout vout, final SurfaceView surfaceView, double aspect) {
        if (surfaceView == null) return;
        final View parent = (View) surfaceView.getParent();
        if (parent == null) return;

        final int parentW = parent.getWidth();
        final int parentH = parent.getHeight();

        if (parentW <= 0 || parentH <= 0) {
            final double finalAsp = aspect;
            parent.post(new Runnable() {
                @Override
                public void run() {
                    applyVideoLayout(vout, surfaceView, finalAsp);
                }
            });
            return;
        }

        if (aspect <= 0.1) aspect = 16.0 / 9.0;

        int targetW = parentW;
        int targetH = (int) Math.round(parentW / aspect);
        if (targetH > parentH) {
            targetH = parentH;
            targetW = (int) Math.round(parentH * aspect);
        }

        targetW = targetW & ~1;
        targetH = targetH & ~1;

        final int finalW = targetW;
        final int finalH = targetH;
        final double finalAspect = aspect;

        surfaceView.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
                    if (lp != null) {
                        boolean changed = false;
                        if (lp.width != finalW || lp.height != finalH) {
                            lp.width = finalW;
                            lp.height = finalH;
                            changed = true;
                        }
                        if (lp instanceof FrameLayout.LayoutParams) {
                            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
                            if (flp.gravity != Gravity.CENTER) {
                                flp.gravity = Gravity.CENTER;
                                changed = true;
                            }
                        }
                        if (changed) {
                            surfaceView.setLayoutParams(lp);
                            surfaceView.requestLayout();
                            DebugLogger.log("VLC_LAYOUT", String.format("Surface centered at %dx%d (Parent: %dx%d, Aspect: %.3f)",
                                    finalW, finalH, parentW, parentH, finalAspect));
                        }
                    }
                    if (vout != null && vout.areViewsAttached()) {
                        vout.setWindowSize(finalW, finalH);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "applyVideoLayout error: " + t.getMessage());
                }
            }
        });
    }

    public void recomputeLayout(final SurfaceView surfaceView, final boolean isLive) {
        if (surfaceView == null) return;
        double aspect = isLive ? liveAspect : galleryAspect;
        MediaPlayer player = isLive ? (activeLivePlayer != null ? activeLivePlayer : livePlayer) : galleryPlayer;
        IVLCVout vout = (player != null) ? player.getVLCVout() : null;
        applyVideoLayout(vout, surfaceView, aspect);
    }

    // =========================================================================
    // LIVE STREAM PLAYBACK
    // =========================================================================

    private final Runnable liveWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            final MediaPlayer currentPlayer = activeLivePlayer;
            if (released || !liveStopNeeded || currentPlayer == null) {
                return;
            }
            final int observedGen = liveSessionGen;
            final LiveListener activeListener = liveListener;
            long now = SystemClock.uptimeMillis();

            if (!liveHasPlayed) {
                if (isLiveOpenTimedOut(false, liveOpenStartUptimeMs, now, LIVE_OPEN_TIMEOUT_MS)) {
                    DebugLogger.log("VLC_LIVE", "Live stream open timed out after " + (now - liveOpenStartUptimeMs) + "ms");
                    stopLiveStream();
                    if (activeListener != null) {
                        activeListener.onError("Live stream open timeout");
                    }
                    return;
                }
                mainHandler.postDelayed(this, LIVE_WATCHDOG_POLL_MS);
                return;
            }

            try {
                IMedia media = currentPlayer.getMedia();
                if (media != null) {
                    try {
                        IMedia.Stats stats = media.getStats();
                        if (stats != null && didLiveStatsAdvance(
                                lastLiveReadBytes, lastLiveDemuxBytes,
                                lastLiveDecodedVideo, lastLiveDisplayedPictures,
                                stats.readBytes, stats.demuxReadBytes,
                                stats.decodedVideo, stats.displayedPictures)) {
                            lastLiveReadBytes = stats.readBytes;
                            lastLiveDemuxBytes = stats.demuxReadBytes;
                            lastLiveDecodedVideo = stats.decodedVideo;
                            lastLiveDisplayedPictures = stats.displayedPictures;
                            lastLiveProgressUptimeMs = now;
                        }
                    } finally {
                        media.release();
                    }
                }
            } catch (Throwable ignored) {}

            if (observedGen != liveSessionGen || !liveStopNeeded) {
                return;
            }

            if (isLiveStreamStalled(liveHasPlayed, lastLiveProgressUptimeMs, now, LIVE_STALL_TIMEOUT_MS)) {
                long silentMs = now - lastLiveProgressUptimeMs;
                DebugLogger.log("VLC_LIVE", "Live stream stall detected (no RTP/frame progress for " + silentMs + "ms)");
                stopLiveStream();
                if (activeListener != null) {
                    activeListener.onStreamStalled(String.format(java.util.Locale.US,
                            "No RTP/video frames for %.1fs", silentMs / 1000.0));
                }
                return;
            }

            mainHandler.postDelayed(this, LIVE_WATCHDOG_POLL_MS);
        }
    };

    private void armLiveWatchdog() {
        mainHandler.removeCallbacks(liveWatchdogRunnable);
        mainHandler.postDelayed(liveWatchdogRunnable, LIVE_WATCHDOG_POLL_MS);
    }

    private void disarmLiveWatchdog() {
        mainHandler.removeCallbacks(liveWatchdogRunnable);
    }

    private void bindLiveEventListener(final MediaPlayer targetPlayer, final int sessionGen, final LiveListener listener) {
        targetPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                if (sessionGen != liveSessionGen || released || activeLivePlayer != targetPlayer) {
                    return;
                }
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        liveHasPlayed = true;
                        lastLiveProgressUptimeMs = SystemClock.uptimeMillis();
                        DebugLogger.log("VLC_LIVE", "Live stream playing (First frame rendered)");
                        if (listener != null) listener.onPlaying();
                        break;
                    case MediaPlayer.Event.Vout:
                        if (event.getVoutCount() > 0) {
                            liveHasPlayed = true;
                            lastLiveProgressUptimeMs = SystemClock.uptimeMillis();
                        }
                        break;
                    case MediaPlayer.Event.TimeChanged:
                    case MediaPlayer.Event.PositionChanged:
                        // Intentionally do NOT reset lastLiveProgressUptimeMs here: VLC's internal
                        // monotonic input_clock_t keeps emitting TimeChanged for 10s while live555
                        // receives 0 RTP bytes. Real progress is tracked strictly via IMedia.Stats.
                        break;
                    case MediaPlayer.Event.Buffering:
                        float pct = event.getBuffering();
                        if (listener != null) {
                            if (shouldShowSpinnerOnBuffering(pct, liveHasPlayed)) {
                                listener.onBuffering(pct);
                            } else {
                                listener.onBuffering(100.0f);
                            }
                        }
                        break;
                    case MediaPlayer.Event.EndReached: {
                        disarmLiveWatchdog();
                        boolean wasUnexpected = liveStopNeeded;
                        boolean hadVideo = liveHasPlayed;
                        liveStopNeeded = false;
                        DebugLogger.log("VLC_LIVE", "Live stream reached EOF (wasPlaying=" + hadVideo + ")");
                        if (wasUnexpected && listener != null) {
                            if (hadVideo) {
                                listener.onStreamStalled("RTSP stream ended (EOF)");
                            } else {
                                listener.onError("Live stream ended before rendering");
                            }
                        }
                        break;
                    }
                    case MediaPlayer.Event.EncounteredError: {
                        disarmLiveWatchdog();
                        boolean hadVideo = liveHasPlayed;
                        liveStopNeeded = false;
                        DebugLogger.log("VLC_LIVE", "Live stream encountered error (wasPlaying=" + hadVideo + ")");
                        if (listener != null) {
                            if (hadVideo) {
                                listener.onStreamStalled("RTSP stream connection error");
                            } else {
                                listener.onError("Live stream connection error");
                            }
                        }
                        break;
                    }
                    case MediaPlayer.Event.Stopped: {
                        disarmLiveWatchdog();
                        boolean unexpectedStop = liveStopNeeded && liveHasPlayed;
                        liveStopNeeded = false;
                        if (unexpectedStop) {
                            DebugLogger.log("VLC_LIVE", "Live stream stopped unexpectedly mid-session");
                            if (listener != null) {
                                listener.onStreamStalled("RTSP stream stopped unexpectedly");
                            }
                        } else if (listener != null) {
                            listener.onStopped();
                        }
                        break;
                    }
                }
            }
        });
    }

    public void startLiveStream(final SurfaceView surfaceView, final String streamUrl, final LiveListener listener) {
        startLiveStream(surfaceView, streamUrl, false, listener);
    }

    public void startLiveStream(final SurfaceView surfaceView, final String streamUrl,
                                final boolean mirrorHorizontal, final LiveListener listener) {
        runWhenReady(new Runnable() {
            @Override
            public void run() {
                if (!isInitialized()) {
                    if (listener != null) listener.onError("LibVLC not initialized");
                    return;
                }

                try {
                    stopLiveStream();
                    final boolean useMirrorEngine = mirrorHorizontal && mirrorLibVLC != null && mirrorLivePlayer != null;
                    final LibVLC targetLib = useMirrorEngine ? mirrorLibVLC : libVLC;
                    final MediaPlayer targetPlayer = useMirrorEngine ? mirrorLivePlayer : livePlayer;

                    final int myGen = ++liveSessionGen;
                    activeLivePlayer = targetPlayer;
                    liveListener = listener;
                    attachedLiveSurfaceView = surfaceView;
                    liveHasPlayed = false;
                    long now = SystemClock.uptimeMillis();
                    liveOpenStartUptimeMs = now;
                    lastLiveProgressUptimeMs = now;
                    lastLiveReadBytes = 0;
                    lastLiveDemuxBytes = 0;
                    lastLiveDecodedVideo = 0;
                    lastLiveDisplayedPictures = 0;

                    // Re-binding calls VLCObject.setEventListener, which flushes stale MainLooper
                    // EventRunnables (such as Event.Stopped from the previous stream) before play().
                    bindLiveEventListener(targetPlayer, myGen, listener);
                    attachWithLayoutScaler(targetPlayer, surfaceView, true);

                    Uri uri = Uri.parse(streamUrl);
                    Media media = new Media(targetLib, uri);
                    media.setHWDecoderEnabled(false, false);
                    for (String opt : buildLiveMediaOptions(mirrorHorizontal)) {
                        media.addOption(opt);
                    }

                    targetPlayer.setMedia(media);
                    media.release();

                    liveStopNeeded = true;
                    DebugLogger.log("VLC_LIVE", "Starting live RTSP/TCP"
                            + (useMirrorEngine ? " [MIRRORED hflip]" : "") + ": " + streamUrl);
                    targetPlayer.play();
                    armLiveWatchdog();
                } catch (Throwable t) {
                    disarmLiveWatchdog();
                    liveStopNeeded = false;
                    Log.e(TAG, "startLiveStream error", t);
                    DebugLogger.log("VLC_LIVE", "startLiveStream error: " + t.getMessage());
                    if (listener != null) listener.onError(t.getMessage());
                }
            }
        });
    }

    private static void stopAndDetachLivePlayer(MediaPlayer player, boolean forceStop) {
        if (player == null) return;
        try {
            if (forceStop || player.isPlaying()) {
                player.stop();
            }
            IVLCVout vout = player.getVLCVout();
            if (vout != null && vout.areViewsAttached()) {
                vout.detachViews();
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopLiveStream error: " + t.getMessage());
        }
    }

    public void stopLiveStream() {
        disarmLiveWatchdog();
        liveSessionGen++;
        liveListener = null;
        liveHasPlayed = false;
        boolean needForceStop = liveStopNeeded;
        liveStopNeeded = false;

        MediaPlayer current = activeLivePlayer;
        if (current != null) {
            stopAndDetachLivePlayer(current, needForceStop);
        }
        if (livePlayer != null && livePlayer != current) {
            stopAndDetachLivePlayer(livePlayer, false);
        }
        if (mirrorLivePlayer != null && mirrorLivePlayer != current) {
            stopAndDetachLivePlayer(mirrorLivePlayer, false);
        }
    }

    // =========================================================================
    // GALLERY / SD CARD / USB FOOTAGE PLAYBACK
    // =========================================================================

    private void bindGalleryEventListener(final int sessionGen, final GalleryListener listener) {
        galleryPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                if (sessionGen != gallerySessionGen || released || galleryPlayer == null) {
                    return;
                }
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        galleryOpening = false;
                        galleryPaused = false;
                        gallerySeekable = galleryPlayer.isSeekable();
                        galleryLengthMs = galleryPlayer.getLength();
                        DebugLogger.log("VLC_GAL", "Gallery clip playing (Length: " + galleryLengthMs + "ms, Seekable: "
                                + gallerySeekable + ", rate=" + targetGalleryRate + "x)");
                        galleryPlayer.setRate(targetGalleryRate);
                        if (pendingSeekPosition >= 0.0f) {
                            float targetPos = Math.max(0.0f, Math.min(0.995f, pendingSeekPosition));
                            pendingSeekPosition = -1.0f;
                            if (gallerySeekable) {
                                galleryPlayer.setPosition(targetPos);
                            }
                        }
                        if (listener != null) {
                            listener.onPlaying(gallerySeekable, galleryLengthMs);
                        }
                        break;
                    case MediaPlayer.Event.Vout:
                        if (event.getVoutCount() > 0 && listener != null) {
                            listener.onVideoOutputStarted();
                        }
                        break;
                    case MediaPlayer.Event.Paused:
                        galleryPaused = true;
                        break;
                    case MediaPlayer.Event.Stopped:
                        galleryOpening = false;
                        galleryPaused = false;
                        break;
                    case MediaPlayer.Event.Buffering:
                        if (listener != null) {
                            listener.onBuffering(event.getBuffering());
                        }
                        break;
                    case MediaPlayer.Event.SeekableChanged:
                        gallerySeekable = event.getSeekable();
                        galleryLengthMs = galleryPlayer.getLength();
                        if (listener != null) {
                            listener.onProgressChanged(galleryPlayer.getPosition(), galleryPlayer.getTime(),
                                    galleryLengthMs, gallerySeekable);
                        }
                        break;
                    case MediaPlayer.Event.PositionChanged:
                        gallerySeekable = galleryPlayer.isSeekable();
                        galleryLengthMs = galleryPlayer.getLength();
                        if (listener != null) {
                            listener.onProgressChanged(event.getPositionChanged(), galleryPlayer.getTime(),
                                    galleryLengthMs, gallerySeekable);
                        }
                        break;
                    case MediaPlayer.Event.TimeChanged:
                        gallerySeekable = galleryPlayer.isSeekable();
                        galleryLengthMs = galleryPlayer.getLength();
                        if (listener != null) {
                            listener.onProgressChanged(galleryPlayer.getPosition(), event.getTimeChanged(),
                                    galleryLengthMs, gallerySeekable);
                        }
                        break;
                    case MediaPlayer.Event.LengthChanged:
                        galleryLengthMs = event.getLengthChanged();
                        if (listener != null) {
                            listener.onProgressChanged(galleryPlayer.getPosition(), galleryPlayer.getTime(),
                                    galleryLengthMs, gallerySeekable);
                        }
                        break;
                    case MediaPlayer.Event.EndReached:
                        galleryOpening = false;
                        galleryStopNeeded = false;
                        galleryPaused = false;
                        DebugLogger.log("VLC_GAL", "Gallery clip reached end");
                        if (listener != null) listener.onEndReached();
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        galleryOpening = false;
                        galleryStopNeeded = false;
                        galleryPaused = false;
                        DebugLogger.log("VLC_GAL", "Gallery clip playback error");
                        if (listener != null) listener.onError("Playback error");
                        break;
                }
            }
        });
    }

    public void playGalleryClip(SurfaceView surfaceView, String playUri, GalleryListener listener) {
        boolean isFront = !ClipItem.isRearFilename(playUri, playUri);
        playGalleryClip(surfaceView, playUri, isFront, isFront, listener);
    }

    public void playGalleryClip(SurfaceView surfaceView, String playUri, boolean isFront, GalleryListener listener) {
        playGalleryClip(surfaceView, playUri, isFront, isFront, listener);
    }

    public void playGalleryClip(SurfaceView surfaceView, String playUri, boolean isFront, boolean is4kFront, GalleryListener listener) {
        float defaultRate = getDefaultGalleryRate(isFront && is4kFront);
        playGalleryClip(surfaceView, playUri, isFront, is4kFront, defaultRate, -1.0f, listener);
    }

    public void playGalleryClip(SurfaceView surfaceView, String playUri, boolean isFront,
                                boolean is4kFront, float initialRate, GalleryListener listener) {
        playGalleryClip(surfaceView, playUri, isFront, is4kFront, initialRate, -1.0f, listener);
    }

    public void playGalleryClip(final SurfaceView surfaceView, final String playUri, final boolean isFront,
                                final boolean is4kFront, final float initialRate, final float startSeekPosition,
                                final GalleryListener listener) {
        runWhenReady(new Runnable() {
            @Override
            public void run() {
                if (!isInitialized()) {
                    if (listener != null) listener.onError("LibVLC not initialized");
                    return;
                }

                final boolean useFront4kProfile = isFront && is4kFront;
                try {
                    stopGalleryClip();

                    final int myGen = ++gallerySessionGen;
                    galleryListener = listener;
                    attachedGallerySurfaceView = surfaceView;
                    targetGalleryRate = sanitizeGalleryRate(initialRate, useFront4kProfile);
                    pendingSeekPosition = startSeekPosition;
                    galleryPaused = false;
                    gallerySeekable = false;
                    galleryLengthMs = 0L;

                    // Re-binding flushes VLCObject's MainLooper Handler queue so Event.Stopped from
                    // the previous clip can never suppress callbacks for this new clip session.
                    bindGalleryEventListener(myGen, listener);
                    attachWithLayoutScaler(galleryPlayer, surfaceView, false);

                    boolean isHttp = playUri.startsWith("http://") || playUri.startsWith("https://");
                    Media media;
                    if (isHttp || playUri.startsWith("file://")) {
                        media = new Media(libVLC, Uri.parse(playUri));
                    } else {
                        media = new Media(libVLC, playUri);
                    }

                    // Disable hardware decoders to prevent OMX.TCC.avcdec crashes
                    media.setHWDecoderEnabled(false, false);

                    // Audio disabled during gallery review to avoid AudioTrack clock stalls & car audio focus steal
                    media.addOption(":no-audio");
                    media.addOption(":clock-jitter=0");
                    media.addOption(":clock-synchro=0");
                    // Enable byte-percentage position & seeking for headerless MPEG-TS (.TS) dashcam files
                    media.addOption(":ts-seek-percent");

                    if (useFront4kProfile) {
                        if (isHttp) {
                            media.addOption(":network-caching=2000");
                        } else {
                            media.addOption(":file-caching=600");
                        }
                        // Decouple stream clock and disable strict TS PCR pacing
                        media.addOption(":no-ts-trust-pcr");
                        // Keyframe-only decoding (I-frames only) + skip HEVC in-loop deblocking filter.
                        // Keep FFmpeg's default 4-core frame multithreading (FF_THREAD_FRAME) enabled across
                        // GOP keyframes (do NOT force thread_type=slice on single-slice Novatek HEVC).
                        media.addOption(":avcodec-skip-frame=3");
                        media.addOption(":avcodec-skiploopfilter=4");
                        media.addOption(":avcodec-hurry-up=1");
                        media.addOption(":avcodec-threads=4");
                        media.addOption(":avcodec-fast=1");
                        DebugLogger.log("VLC_GAL", "Front high-res HEVC profile applied: keyframe-only (rate=" + targetGalleryRate + "x)");
                    } else {
                        if (isHttp) {
                            media.addOption(":network-caching=1500");
                        } else {
                            media.addOption(":file-caching=500");
                        }
                        media.addOption(":avcodec-threads=4");
                        media.addOption(":avcodec-fast=1");
                        DebugLogger.log("VLC_GAL", (isFront ? "Front" : "Rear") + " 1080p AVC profile applied (rate=" + targetGalleryRate + "x)");
                    }

                    galleryPlayer.setMedia(media);
                    media.release();

                    galleryOpening = true;
                    galleryStopNeeded = true;
                    DebugLogger.log("VLC_GAL", "Starting gallery preview (" + (useFront4kProfile ? "FRONT 4K" : (isFront ? "FRONT 1080P" : "REAR 1080P")) + "): " + playUri);
                    galleryPlayer.play();
                    galleryPlayer.setRate(targetGalleryRate);
                } catch (Throwable t) {
                    galleryOpening = false;
                    galleryStopNeeded = false;
                    Log.e(TAG, "playGalleryClip error", t);
                    DebugLogger.log("VLC_GAL", "playGalleryClip error: " + t.getMessage());
                    if (listener != null) listener.onError(t.getMessage());
                }
            }
        });
    }

    public void stopGalleryClip() {
        gallerySessionGen++;
        galleryListener = null;
        galleryOpening = false;
        if (galleryPlayer != null) {
            try {
                // Stop unconditionally when galleryStopNeeded is true (covers Opening & Buffering states too)
                if (galleryStopNeeded || galleryPlayer.isPlaying() || galleryPaused) {
                    galleryStopNeeded = false;
                    galleryPlayer.stop();
                }
                galleryPaused = false;
                gallerySeekable = false;
                IVLCVout vout = galleryPlayer.getVLCVout();
                if (vout != null && vout.areViewsAttached()) {
                    vout.detachViews();
                }
            } catch (Throwable t) {
                Log.w(TAG, "stopGalleryClip error: " + t.getMessage());
            }
        }
        galleryStopNeeded = false;
    }

    public boolean isGalleryPlaying() {
        return galleryPlayer != null && galleryPlayer.isPlaying();
    }

    public boolean isGalleryPaused() {
        return galleryPaused;
    }

    public boolean isGalleryOpening() {
        return galleryOpening;
    }

    public boolean isGallerySeekable() {
        return galleryPlayer != null && (gallerySeekable || galleryPlayer.isSeekable());
    }

    public void pauseGalleryClip() {
        if (galleryPlayer != null && galleryPlayer.isPlaying()) {
            galleryPlayer.pause();
            galleryPaused = true;
        }
    }

    public void resumeGalleryClip() {
        if (galleryPlayer != null && !galleryPlayer.isPlaying()) {
            galleryPaused = false;
            galleryStopNeeded = true;
            galleryPlayer.play();
            galleryPlayer.setRate(targetGalleryRate);
        }
    }

    public void seekGalleryPosition(float position) {
        if (galleryPlayer != null) {
            float clamped = Math.max(0.0f, Math.min(0.995f, position));
            if (galleryOpening && !gallerySeekable) {
                pendingSeekPosition = clamped;
                DebugLogger.log("VLC_GAL", String.format(java.util.Locale.US,
                        "Queued pending gallery seek to %.1f%%", clamped * 100.0f));
                return;
            }
            try {
                DebugLogger.log("VLC_GAL", String.format(java.util.Locale.US,
                        "Seeking gallery clip to %.1f%%", clamped * 100.0f));
                galleryPlayer.setPosition(clamped);
                galleryPlayer.setRate(targetGalleryRate);
            } catch (Throwable t) {
                Log.w(TAG, "seekGalleryPosition error: " + t.getMessage());
            }
        }
    }

    public long getGalleryLengthMs() {
        return galleryLengthMs;
    }

    public void setGalleryRate(float rate, boolean isHevc) {
        float safeRate = sanitizeGalleryRate(rate, isHevc);
        targetGalleryRate = safeRate;
        if (galleryPlayer != null) {
            try {
                galleryPlayer.setRate(safeRate);
            } catch (Throwable ignored) {}
        }
    }

    public float getGalleryRate() {
        return targetGalleryRate;
    }

    // =========================================================================
    // LIFECYCLE CLEANUP (NON-BLOCKING UI THREAD DETACH + ASYNC NATIVE JOIN)
    // =========================================================================

    private static void releaseNativeObjectsAsync(final MediaPlayer live, final MediaPlayer mirrorLive,
                                                  final MediaPlayer gallery, final LibVLC vlc,
                                                  final LibVLC mirrorVlc) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (live != null) {
                        try { live.stop(); } catch (Throwable ignored) {}
                        try { live.release(); } catch (Throwable ignored) {}
                    }
                    if (mirrorLive != null) {
                        try { mirrorLive.stop(); } catch (Throwable ignored) {}
                        try { mirrorLive.release(); } catch (Throwable ignored) {}
                    }
                    if (gallery != null) {
                        try { gallery.stop(); } catch (Throwable ignored) {}
                        try { gallery.release(); } catch (Throwable ignored) {}
                    }
                    if (vlc != null) {
                        try { vlc.release(); } catch (Throwable ignored) {}
                    }
                    if (mirrorVlc != null) {
                        try { mirrorVlc.release(); } catch (Throwable ignored) {}
                    }
                    DebugLogger.log("VLC", "LibVLC native engines released cleanly on background thread");
                } catch (Throwable t) {
                    Log.w(TAG, "Async release warning: " + t.getMessage());
                }
            }
        }, "FitcamxVlcRelease").start();
    }

    public void release() {
        if (released) return;
        released = true;
        disarmLiveWatchdog();
        initialized = false;
        liveSessionGen++;
        gallerySessionGen++;
        liveStopNeeded = false;
        liveHasPlayed = false;
        galleryStopNeeded = false;
        galleryOpening = false;
        liveListener = null;
        galleryListener = null;
        pendingReadyActions.clear();

        final MediaPlayer liveToRelease = livePlayer;
        final MediaPlayer mirrorLiveToRelease = mirrorLivePlayer;
        final MediaPlayer galleryToRelease = galleryPlayer;
        final LibVLC vlcToRelease = libVLC;
        final LibVLC mirrorVlcToRelease = mirrorLibVLC;
        activeLivePlayer = null;
        livePlayer = null;
        mirrorLivePlayer = null;
        galleryPlayer = null;
        libVLC = null;
        mirrorLibVLC = null;

        try {
            if (liveToRelease != null) {
                liveToRelease.setEventListener(null);
                IVLCVout vout = liveToRelease.getVLCVout();
                if (vout != null && vout.areViewsAttached()) {
                    vout.detachViews();
                }
            }
            if (mirrorLiveToRelease != null) {
                mirrorLiveToRelease.setEventListener(null);
                IVLCVout vout = mirrorLiveToRelease.getVLCVout();
                if (vout != null && vout.areViewsAttached()) {
                    vout.detachViews();
                }
            }
            if (galleryToRelease != null) {
                galleryToRelease.setEventListener(null);
                IVLCVout vout = galleryToRelease.getVLCVout();
                if (vout != null && vout.areViewsAttached()) {
                    vout.detachViews();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Surface detach warning during release: " + t.getMessage());
        }

        if (liveToRelease != null || mirrorLiveToRelease != null || galleryToRelease != null
                || vlcToRelease != null || mirrorVlcToRelease != null) {
            releaseNativeObjectsAsync(liveToRelease, mirrorLiveToRelease, galleryToRelease,
                    vlcToRelease, mirrorVlcToRelease);
        }
    }
}
