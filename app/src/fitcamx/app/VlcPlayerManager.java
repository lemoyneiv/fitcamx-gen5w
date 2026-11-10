package fitcamx.app;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
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
 *    streams to fit the viewing area with letterbox/pillarbox and centers them perfectly
 *    (fixes previews being pushed to the right or left edge).
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

    public interface LiveListener {
        void onPlaying();
        void onBuffering(float percent);
        void onError(String message);
        void onStopped();
    }

    public interface GalleryListener {
        void onPlaying(boolean seekable, long totalLengthMs);
        void onBuffering(float percent);
        void onProgressChanged(float position, long currentTimeMs, long totalLengthMs, boolean seekable);
        void onEndReached();
        void onError(String message);
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

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Runnable> pendingReadyActions = new ArrayList<Runnable>();

    private volatile boolean initialized = false;
    private volatile boolean initFailed = false;
    private volatile boolean released = false;

    private LibVLC libVLC;
    private MediaPlayer livePlayer;
    private MediaPlayer galleryPlayer;

    private LiveListener liveListener;
    private GalleryListener galleryListener;

    private SurfaceView attachedLiveSurfaceView = null;
    private SurfaceView attachedGallerySurfaceView = null;

    private double liveAspect = 16.0 / 9.0;
    private double galleryAspect = 16.0 / 9.0;
    private volatile float targetGalleryRate = 1.0f;
    private volatile boolean galleryPaused = false;
    private volatile boolean gallerySeekable = false;
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
                    ArrayList<String> options = new ArrayList<String>();
                    // RTSP over TCP is mandatory for Gen5W cellular firewall / rmnet0 bypass
                    options.add("--rtsp-tcp");
                    // Dedicated 4 worker threads for the 4 physical Cortex-A7 CPU cores
                    options.add("--avcodec-threads=4");
                    // Software decoding pipeline: bypass buggy OMX.TCC.avcdec on KitKat
                    // LibVLC uses built-in ARM NEON SIMD assembly for crisp frames
                    options.add("--avcodec-hw=none");
                    // Fast decoding optimizations (fast IDCT, simplified motion compensation)
                    options.add("--avcodec-fast");
                    // Quiet non-critical logs
                    options.add("--no-stats");

                    final LibVLC createdLib = new LibVLC(context, options);
                    final MediaPlayer createdLive = new MediaPlayer(createdLib);
                    final MediaPlayer createdGallery = new MediaPlayer(createdLib);

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (released) {
                                try { createdLive.release(); } catch (Throwable ignored) {}
                                try { createdGallery.release(); } catch (Throwable ignored) {}
                                try { createdLib.release(); } catch (Throwable ignored) {}
                                return;
                            }
                            libVLC = createdLib;
                            livePlayer = createdLive;
                            galleryPlayer = createdGallery;

                            setupLiveEventListener();
                            setupGalleryEventListener();
                            initialized = true;

                            DebugLogger.log("VLC", "Consolidated LibVLC engine initialized asynchronously (ARM NEON 4-thread software decoder ready)");
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
     * native rendering pipeline and turning HEVC frames into gray noise. Android's
     * Hardware Composer handles scaling the native surface buffer to the View bounds
     * automatically in hardware at 0% CPU.
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

        // Immediately compute and apply layout bounds without waiting for stream decode callback
        applyVideoLayout(vout, surfaceView, isLive ? liveAspect : galleryAspect);
    }

    /**
     * Compute exact Best-Fit dimensions inside parent container, update SurfaceView
     * layout parameters to Gravity.CENTER, and notify LibVLC window size.
     */
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

        // Best-Fit letterbox / pillarbox inside parent
        int targetW = parentW;
        int targetH = (int) Math.round(parentW / aspect);
        if (targetH > parentH) {
            targetH = parentH;
            targetW = (int) Math.round(parentH * aspect);
        }

        // Align dimensions to even numbers for YUV 4:2:0 hardware surface compatibility
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
        MediaPlayer player = isLive ? livePlayer : galleryPlayer;
        IVLCVout vout = (player != null) ? player.getVLCVout() : null;
        applyVideoLayout(vout, surfaceView, aspect);
    }

    // =========================================================================
    // LIVE STREAM PLAYBACK
    // =========================================================================

    private void setupLiveEventListener() {
        livePlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        DebugLogger.log("VLC_LIVE", "Live stream playing (First frame rendered)");
                        if (liveListener != null) liveListener.onPlaying();
                        break;
                    case MediaPlayer.Event.Buffering:
                        if (liveListener != null) liveListener.onBuffering(event.getBuffering());
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        DebugLogger.log("VLC_LIVE", "Live stream encountered error");
                        if (liveListener != null) liveListener.onError("Live stream connection error");
                        break;
                    case MediaPlayer.Event.Stopped:
                        if (liveListener != null) liveListener.onStopped();
                        break;
                }
            }
        });
    }

    public void startLiveStream(final SurfaceView surfaceView, final String streamUrl, final LiveListener listener) {
        runWhenReady(new Runnable() {
            @Override
            public void run() {
                if (!isInitialized()) {
                    if (listener != null) listener.onError("LibVLC not initialized");
                    return;
                }

                liveListener = listener;
                attachedLiveSurfaceView = surfaceView;

                try {
                    stopLiveStream();
                    attachWithLayoutScaler(livePlayer, surfaceView, true);

                    Uri uri = Uri.parse(streamUrl);
                    Media media = new Media(libVLC, uri);
                    media.setHWDecoderEnabled(false, false);
                    media.addOption(":rtsp-tcp");
                    media.addOption(":network-caching=300");
                    media.addOption(":no-audio"); // mute live preview to avoid vehicle audio interruption
                    media.addOption(":avcodec-threads=4");
                    media.addOption(":avcodec-fast");
                    media.addOption(":clock-jitter=0");
                    media.addOption(":clock-synchro=0");

                    livePlayer.setMedia(media);
                    media.release();

                    DebugLogger.log("VLC_LIVE", "Starting live RTSP/TCP: " + streamUrl);
                    livePlayer.play();
                } catch (Throwable t) {
                    Log.e(TAG, "startLiveStream error", t);
                    DebugLogger.log("VLC_LIVE", "startLiveStream error: " + t.getMessage());
                    if (listener != null) listener.onError(t.getMessage());
                }
            }
        });
    }

    public void stopLiveStream() {
        if (livePlayer != null) {
            try {
                if (livePlayer.isPlaying()) {
                    livePlayer.stop();
                }
                IVLCVout vout = livePlayer.getVLCVout();
                if (vout != null && vout.areViewsAttached()) {
                    vout.detachViews();
                }
            } catch (Throwable t) {
                Log.w(TAG, "stopLiveStream error: " + t.getMessage());
            }
        }
    }

    // =========================================================================
    // GALLERY / SD CARD / USB FOOTAGE PLAYBACK
    // =========================================================================

    private void setupGalleryEventListener() {
        galleryPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        galleryPaused = false;
                        gallerySeekable = galleryPlayer.isSeekable();
                        long playLen = galleryPlayer.getLength();
                        DebugLogger.log("VLC_GAL", "Gallery clip playing (Length: " + playLen + "ms, Seekable: "
                                + gallerySeekable + ", rate=" + targetGalleryRate + "x)");
                        galleryPlayer.setRate(targetGalleryRate);
                        if (pendingSeekPosition >= 0.0f) {
                            float targetPos = Math.max(0.0f, Math.min(0.995f, pendingSeekPosition));
                            pendingSeekPosition = -1.0f;
                            if (gallerySeekable) {
                                galleryPlayer.setPosition(targetPos);
                            }
                        }
                        if (galleryListener != null) {
                            galleryListener.onPlaying(gallerySeekable, playLen);
                        }
                        break;
                    case MediaPlayer.Event.Paused:
                        galleryPaused = true;
                        break;
                    case MediaPlayer.Event.Stopped:
                        galleryPaused = false;
                        break;
                    case MediaPlayer.Event.Buffering:
                        if (galleryListener != null) galleryListener.onBuffering(event.getBuffering());
                        break;
                    case MediaPlayer.Event.SeekableChanged:
                        gallerySeekable = event.getSeekable();
                        if (galleryListener != null) {
                            galleryListener.onProgressChanged(galleryPlayer.getPosition(), galleryPlayer.getTime(),
                                    galleryPlayer.getLength(), gallerySeekable);
                        }
                        break;
                    case MediaPlayer.Event.PositionChanged:
                        gallerySeekable = galleryPlayer.isSeekable();
                        if (galleryListener != null) {
                            galleryListener.onProgressChanged(event.getPositionChanged(), galleryPlayer.getTime(),
                                    galleryPlayer.getLength(), gallerySeekable);
                        }
                        break;
                    case MediaPlayer.Event.TimeChanged:
                        gallerySeekable = galleryPlayer.isSeekable();
                        if (galleryListener != null) {
                            galleryListener.onProgressChanged(galleryPlayer.getPosition(), event.getTimeChanged(),
                                    galleryPlayer.getLength(), gallerySeekable);
                        }
                        break;
                    case MediaPlayer.Event.LengthChanged:
                        if (galleryListener != null) {
                            galleryListener.onProgressChanged(galleryPlayer.getPosition(), galleryPlayer.getTime(),
                                    event.getLengthChanged(), gallerySeekable);
                        }
                        break;
                    case MediaPlayer.Event.EndReached:
                        galleryPaused = false;
                        DebugLogger.log("VLC_GAL", "Gallery clip reached end");
                        if (galleryListener != null) galleryListener.onEndReached();
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        galleryPaused = false;
                        DebugLogger.log("VLC_GAL", "Gallery clip playback error");
                        if (galleryListener != null) galleryListener.onError("Playback error");
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
                galleryListener = listener;
                attachedGallerySurfaceView = surfaceView;
                targetGalleryRate = sanitizeGalleryRate(initialRate, useFront4kProfile);
                pendingSeekPosition = startSeekPosition;
                galleryPaused = false;
                gallerySeekable = false;

                try {
                    stopGalleryClip();
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
                        // Keyframe-only decoding (I-frames only) + skip HEVC in-loop deblocking filter
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

                    DebugLogger.log("VLC_GAL", "Starting gallery preview (" + (useFront4kProfile ? "FRONT 4K" : (isFront ? "FRONT 1080P" : "REAR 1080P")) + "): " + playUri);
                    galleryPlayer.play();
                    galleryPlayer.setRate(targetGalleryRate);
                } catch (Throwable t) {
                    Log.e(TAG, "playGalleryClip error", t);
                    DebugLogger.log("VLC_GAL", "playGalleryClip error: " + t.getMessage());
                    if (listener != null) listener.onError(t.getMessage());
                }
            }
        });
    }

    public void stopGalleryClip() {
        if (galleryPlayer != null) {
            try {
                if (galleryPlayer.isPlaying() || galleryPaused) {
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
    }

    public boolean isGalleryPlaying() {
        return galleryPlayer != null && galleryPlayer.isPlaying();
    }

    public boolean isGalleryPaused() {
        return galleryPaused;
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
            galleryPlayer.play();
            galleryPlayer.setRate(targetGalleryRate);
        }
    }

    public void seekGalleryPosition(float position) {
        if (galleryPlayer != null) {
            float clamped = Math.max(0.0f, Math.min(0.995f, position));
            try {
                galleryPlayer.setPosition(clamped);
                galleryPlayer.setRate(targetGalleryRate);
            } catch (Throwable t) {
                Log.w(TAG, "seekGalleryPosition error: " + t.getMessage());
            }
        }
    }

    public long getGalleryLengthMs() {
        return (galleryPlayer != null) ? galleryPlayer.getLength() : 0L;
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
    // LIFECYCLE CLEANUP
    // =========================================================================

    public void release() {
        released = true;
        pendingReadyActions.clear();
        try {
            stopLiveStream();
            stopGalleryClip();

            if (livePlayer != null) {
                if (livePlayer.getVLCVout().areViewsAttached()) {
                    livePlayer.getVLCVout().detachViews();
                }
                livePlayer.release();
                livePlayer = null;
            }

            if (galleryPlayer != null) {
                if (galleryPlayer.getVLCVout().areViewsAttached()) {
                    galleryPlayer.getVLCVout().detachViews();
                }
                galleryPlayer.release();
                galleryPlayer = null;
            }

            if (libVLC != null) {
                libVLC.release();
                libVLC = null;
            }
            initialized = false;
            DebugLogger.log("VLC", "LibVLC engine released cleanly");
        } catch (Throwable t) {
            Log.w(TAG, "release error: " + t.getMessage());
        }
    }
}
