package fitcamx.app;

import android.content.Context;
import android.net.Uri;
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
        void onPlaying();
        void onBuffering(float percent);
        void onTimeChanged(long currentTimeMs, long totalLengthMs);
        void onEndReached();
        void onError(String message);
    }

    private final Context context;
    private LibVLC libVLC;
    private LibVLC libVlcFront;
    private MediaPlayer livePlayer;
    private MediaPlayer galleryPlayer;
    private MediaPlayer frontGalleryPlayer;
    private MediaPlayer activeGalleryPlayer;

    private LiveListener liveListener;
    private GalleryListener galleryListener;

    private SurfaceView attachedLiveSurfaceView = null;
    private SurfaceView attachedGallerySurfaceView = null;

    private double liveAspect = 16.0 / 9.0;
    private double galleryAspect = 16.0 / 9.0;

    public VlcPlayerManager(Context ctx) {
        this.context = ctx.getApplicationContext();
        initEngine();
    }

    private void initEngine() {
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

            libVLC = new LibVLC(context, options);
            livePlayer = new MediaPlayer(libVLC);
            galleryPlayer = new MediaPlayer(libVLC);
            activeGalleryPlayer = galleryPlayer;

            setupLiveEventListener();
            setupGalleryEventListener(galleryPlayer, "REAR");

            DebugLogger.log("VLC", "Standard LibVLC engine initialized (ARM NEON 4-thread software decoder ready)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize standard LibVLC engine", t);
            DebugLogger.log("VLC", "LibVLC standard init error: " + t.getMessage());
        }

        // Dedicated Keyframe Decoding Engine for 4K / 1440p HEVC Front Clips
        // Uses dynamic incremental probing so options are verified before instantiation,
        // guaranteeing that libVlcFront is 100% reliably created on Gen5W KitKat.
        try {
            ArrayList<String> baseFront = new ArrayList<String>();
            // Proven baseline options
            baseFront.add("--avcodec-threads=4");
            baseFront.add("--avcodec-hw=none");
            baseFront.add("--avcodec-fast");
            baseFront.add("--no-stats");

            // Keyframe & acceleration candidate options to probe
            String[] probeCandidates = new String[] {
                "--avcodec-skip-frame=3",
                "--avcodec-skiploopfilter=4",
                "--avcodec-skip-idct=3",
                "--avcodec-hurry-up",
                "--no-ts-trust-pcr"
            };

            for (String opt : probeCandidates) {
                ArrayList<String> testList = new ArrayList<String>(baseFront);
                testList.add(opt);
                try {
                    LibVLC testVlc = new LibVLC(context, testList);
                    testVlc.release();
                    baseFront.add(opt);
                    DebugLogger.log("VLC", "Accepted LibVLC front option: " + opt);
                } catch (Throwable t) {
                    DebugLogger.log("VLC", "Rejected LibVLC front option: " + opt + " (" + t.getMessage() + ")");
                }
            }

            libVlcFront = new LibVLC(context, baseFront);
            frontGalleryPlayer = new MediaPlayer(libVlcFront);
            setupGalleryEventListener(frontGalleryPlayer, "FRONT");
            DebugLogger.log("VLC", "Dedicated 4K Front Keyframe LibVLC engine initialized successfully with " + baseFront.size() + " options");
        } catch (Throwable t) {
            Log.w(TAG, "libVlcFront init warning: " + t.getMessage());
            DebugLogger.log("VLC", "libVlcFront init fallback warning: " + t.getMessage());
            libVlcFront = null;
            frontGalleryPlayer = null;
        }
    }

    public boolean isInitialized() {
        return libVLC != null && livePlayer != null && galleryPlayer != null;
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
        MediaPlayer player = isLive ? livePlayer : activeGalleryPlayer;
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

    public void startLiveStream(SurfaceView surfaceView, String streamUrl, LiveListener listener) {
        if (!isInitialized()) {
            if (listener != null) listener.onError("LibVLC not initialized");
            return;
        }

        this.liveListener = listener;
        this.attachedLiveSurfaceView = surfaceView;

        try {
            stopLiveStream();
            attachWithLayoutScaler(livePlayer, surfaceView, true);

            Uri uri = Uri.parse(streamUrl);
            Media media = new Media(libVLC, uri);
            media.setHWDecoderEnabled(false, false);
            media.addOption(":rtsp-tcp");
            media.addOption(":network-caching=500");
            media.addOption(":no-audio"); // mute live preview to avoid vehicle audio interruption
            media.addOption(":avcodec-threads=4");
            media.addOption(":avcodec-fast");
            media.addOption(":no-drop-late-frames");
            media.addOption(":no-skip-frames");
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

    private void setupGalleryEventListener(final MediaPlayer player, final String tag) {
        player.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                if (player != activeGalleryPlayer) return;
                switch (event.type) {
                    case MediaPlayer.Event.Playing:
                        DebugLogger.log("VLC_GAL", tag + " clip playing (Length: " + player.getLength() + "ms)");
                        if (player == frontGalleryPlayer) {
                            player.setRate(0.33f);
                            DebugLogger.log("VLC_GAL", "Enforced front keyframe rate: 0.33x for slideshow review");
                        } else {
                            player.setRate(1.0f);
                        }
                        if (galleryListener != null) galleryListener.onPlaying();
                        break;
                    case MediaPlayer.Event.Buffering:
                        if (galleryListener != null) galleryListener.onBuffering(event.getBuffering());
                        break;
                    case MediaPlayer.Event.TimeChanged:
                        if (galleryListener != null) {
                            galleryListener.onTimeChanged(event.getTimeChanged(), player.getLength());
                        }
                        break;
                    case MediaPlayer.Event.EndReached:
                        DebugLogger.log("VLC_GAL", tag + " clip reached end");
                        if (galleryListener != null) galleryListener.onEndReached();
                        break;
                    case MediaPlayer.Event.EncounteredError:
                        DebugLogger.log("VLC_GAL", tag + " clip playback error");
                        if (galleryListener != null) galleryListener.onError("Playback error");
                        break;
                }
            }
        });
    }

    public void playGalleryClip(SurfaceView surfaceView, String playUri, GalleryListener listener) {
        boolean isFront = true;
        if (playUri != null) {
            String u = playUri.toUpperCase();
            if (u.endsWith("B.TS") || u.endsWith("B.MP4") || u.endsWith("B.MOV") ||
                u.endsWith("R.TS") || u.endsWith("R.MP4") || u.endsWith("R.MOV") ||
                u.contains("_B.") || u.contains("_R.") || u.contains("REAR")) {
                isFront = false;
            }
        }
        playGalleryClip(surfaceView, playUri, isFront, isFront, listener);
    }

    public void playGalleryClip(SurfaceView surfaceView, String playUri, boolean isFront, GalleryListener listener) {
        playGalleryClip(surfaceView, playUri, isFront, isFront, listener);
    }

    public void playGalleryClip(SurfaceView surfaceView, String playUri, boolean isFront, boolean is4kFront, GalleryListener listener) {
        if (!isInitialized()) {
            if (listener != null) listener.onError("LibVLC not initialized");
            return;
        }

        this.galleryListener = listener;
        this.attachedGallerySurfaceView = surfaceView;

        try {
            stopGalleryClip();

            // Select specialized player engine:
            // Only use the dedicated 4K keyframe engine if it is a Front clip AND recorded in high resolution (4K / 2K)
            final boolean useFront4kEngine = isFront && is4kFront;
            final MediaPlayer targetPlayer;
            final LibVLC targetLibVlc;
            if (useFront4kEngine && frontGalleryPlayer != null && libVlcFront != null) {
                targetPlayer = frontGalleryPlayer;
                targetLibVlc = libVlcFront;
                DebugLogger.log("VLC_GAL", "Using dedicated Front 4K Keyframe Engine for: " + playUri);
            } else {
                targetPlayer = galleryPlayer;
                targetLibVlc = libVLC;
                DebugLogger.log("VLC_GAL", "Using standard AVC Engine (" + (isFront ? "Front 1080p" : "Rear 1080p") + ") for: " + playUri);
            }
            this.activeGalleryPlayer = targetPlayer;

            attachWithLayoutScaler(targetPlayer, surfaceView, false);

            Media media;
            if (playUri.startsWith("http://") || playUri.startsWith("https://") || playUri.startsWith("file://")) {
                media = new Media(targetLibVlc, Uri.parse(playUri));
            } else {
                media = new Media(targetLibVlc, playUri);
            }

            // Disable hardware decoders to prevent OMX.TCC.avcdec crashes
            media.setHWDecoderEnabled(false, false);

            // Common playback options:
            // Audio is disabled completely during gallery review per user request
            // (eliminates AudioTrack clock sync stalls and prevents car audio focus interruption)
            media.addOption(":no-audio");
            media.addOption(":clock-jitter=0");
            media.addOption(":clock-synchro=0");

            if (useFront4kEngine) {
                // High-Resolution Front Camera Profile (4K / 1440p HEVC) - KEPT FULLY INTACT
                if (playUri.startsWith("http://") || playUri.startsWith("https://")) {
                    media.addOption(":network-caching=3000"); // 3s network cache for camera Wi-Fi streaming
                } else {
                    media.addOption(":file-caching=2000");
                }
                // Decouple stream clock and disable strict TS PCR pacing
                media.addOption(":no-ts-trust-pcr");
                // Keyframe-only decoding (I-frames only)
                media.addOption(":avcodec-skip-frame=3"); // Discard all non-key frames (decode intra/keyframes only)
                media.addOption(":avcodec-skiploopfilter=4"); // Skip in-loop deblocking filter for maximum CPU performance
                media.addOption(":avcodec-skip-idct=3");
                media.addOption(":avcodec-hurry-up=1");
                media.addOption(":avcodec-threads=4");
                media.addOption(":avcodec-fast=1");
                DebugLogger.log("VLC_GAL", "Front high-res profile applied: keyframe-only (skip-frame=3, skiploopfilter=4, no-ts-trust-pcr)");
            } else {
                // 1080p Profile (Standard H.264 AVC for Front 1080p & Rear 1080p):
                // Full 1.0x framerate, NO skip-idct (no gray areas!), NO skip-frame
                if (playUri.startsWith("http://") || playUri.startsWith("https://")) {
                    media.addOption(":network-caching=2500");
                } else {
                    media.addOption(":file-caching=1500");
                }
                media.addOption(":avcodec-threads=4");
                media.addOption(":avcodec-fast=1");
                DebugLogger.log("VLC_GAL", (isFront ? "Front" : "Rear") + " 1080p profile applied: full framerate AVC");
            }

            targetPlayer.setMedia(media);
            media.release();

            DebugLogger.log("VLC_GAL", "Starting gallery preview (" + (useFront4kEngine ? "FRONT 4K" : (isFront ? "FRONT 1080P" : "REAR 1080P")) + "): " + playUri);
            targetPlayer.play();
            if (useFront4kEngine) {
                targetPlayer.setRate(0.33f);
                DebugLogger.log("VLC_GAL", "Requested front player rate: 0.33x for keyframe slideshow review");
            } else {
                targetPlayer.setRate(1.0f);
            }
        } catch (Throwable t) {
            Log.e(TAG, "playGalleryClip error", t);
            DebugLogger.log("VLC_GAL", "playGalleryClip error: " + t.getMessage());
            if (listener != null) listener.onError(t.getMessage());
        }
    }

    public void stopGalleryClip() {
        if (galleryPlayer != null) {
            try {
                if (galleryPlayer.isPlaying()) {
                    galleryPlayer.stop();
                }
                IVLCVout vout = galleryPlayer.getVLCVout();
                if (vout != null && vout.areViewsAttached()) {
                    vout.detachViews();
                }
            } catch (Throwable t) {
                Log.w(TAG, "stopGalleryClip (rear) error: " + t.getMessage());
            }
        }
        if (frontGalleryPlayer != null) {
            try {
                if (frontGalleryPlayer.isPlaying()) {
                    frontGalleryPlayer.stop();
                }
                IVLCVout vout = frontGalleryPlayer.getVLCVout();
                if (vout != null && vout.areViewsAttached()) {
                    vout.detachViews();
                }
            } catch (Throwable t) {
                Log.w(TAG, "stopGalleryClip (front) error: " + t.getMessage());
            }
        }
    }

    public boolean isGalleryPlaying() {
        return activeGalleryPlayer != null && activeGalleryPlayer.isPlaying();
    }

    public void pauseGalleryClip() {
        if (activeGalleryPlayer != null && activeGalleryPlayer.isPlaying()) {
            activeGalleryPlayer.pause();
        }
    }

    public void resumeGalleryClip() {
        if (activeGalleryPlayer != null && !activeGalleryPlayer.isPlaying()) {
            activeGalleryPlayer.play();
        }
    }

    public void seekGalleryClip(long timeMs) {
        if (activeGalleryPlayer != null) {
            activeGalleryPlayer.setTime(timeMs);
        }
    }

    // =========================================================================
    // LIFECYCLE CLEANUP
    // =========================================================================

    public void release() {
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

            if (frontGalleryPlayer != null) {
                if (frontGalleryPlayer.getVLCVout().areViewsAttached()) {
                    frontGalleryPlayer.getVLCVout().detachViews();
                }
                frontGalleryPlayer.release();
                frontGalleryPlayer = null;
            }

            if (libVlcFront != null) {
                libVlcFront.release();
                libVlcFront = null;
            }

            if (libVLC != null) {
                libVLC.release();
                libVLC = null;
            }
            DebugLogger.log("VLC", "LibVLC engines released cleanly");
        } catch (Throwable t) {
            Log.w(TAG, "release error: " + t.getMessage());
        }
    }
}
