package fitcamx.app;

import android.graphics.Bitmap;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IPC client for the on-demand uid=1000 RearCamDaemon (@fitcamx.rearcam).
 *
 * Connects directly to @fitcamx.rearcam when already warm; only asks hmgapid to start the
 * manual "rearcam" service when the socket is not yet listening (avoiding hmgapid's restart-on-start
 * behavior for running services).
 */
public class OemRearCameraClient {
    public static final String SOCKET_NAME = "fitcamx.rearcam";
    public static final String SERVICE_NAME = "rearcam";
    public static final int PROTOCOL_MAGIC = 0x46435231; // "FCR1"

    public static final byte STATUS_ERROR = 0;
    public static final byte STATUS_OWNED_LIVE = 1;
    public static final byte STATUS_SHARED_LIVE = 2;
    public static final byte STATUS_REVERSE = 3;
    public static final byte STATUS_STARTING = 4;
    public static final byte STATUS_CAR_OFF = 5;

    public interface ProbeCallback {
        void onProbeResult(boolean supported, int cameraType, String detail);
    }

    public interface StreamListener {
        void onFrame(Bitmap bitmap, byte status);
        void onStatusChanged(byte status, String message);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object streamLock = new Object();
    private final AtomicInteger desiredOverlayX = new AtomicInteger(1200);
    private final AtomicBoolean stockCameraSignalPending = new AtomicBoolean(false);

    private volatile Boolean cachedSupported = null;
    private volatile Thread activeStreamThread = null;
    private volatile boolean streamRunning = false;
    private volatile LocalSocket activeSocket = null;
    private volatile StreamListener activeListener = null;
    private volatile byte currentStreamStatus = -1;

    public boolean isKnownSupported() {
        Boolean s = cachedSupported;
        return s != null && s.booleanValue();
    }

    public boolean isStreamingLive() {
        byte st = currentStreamStatus;
        return streamRunning && (st == STATUS_OWNED_LIVE || st == STATUS_SHARED_LIVE);
    }

    public void probeSupportAsync(final ProbeCallback callback) {
        Boolean existing = cachedSupported;
        if (existing != null) {
            if (callback != null) {
                final boolean val = existing.booleanValue();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onProbeResult(val, val ? 0 : -1, "cached");
                    }
                });
            }
            return;
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean supported = false;
                int camType = -1;
                String detail = "Daemon unavailable";
                LocalSocket sock = null;
                try {
                    sock = connectOrStartDaemon(2800);
                    if (sock != null) {
                        sock.setSoTimeout(2500);
                        DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                        DataInputStream in = new DataInputStream(sock.getInputStream());
                        out.writeByte('P');
                        out.flush();
                        int magic = in.readInt();
                        if (magic == PROTOCOL_MAGIC) {
                            supported = in.readBoolean();
                            camType = in.readInt();
                            detail = in.readUTF();
                        } else {
                            detail = "Unexpected daemon protocol magic";
                        }
                    }
                } catch (Throwable err) {
                    detail = err.getClass().getSimpleName() + ": " + err.getMessage();
                } finally {
                    if (sock != null) {
                        try { sock.close(); } catch (IOException ignored) {}
                    }
                }

                cachedSupported = Boolean.valueOf(supported);
                DebugLogger.log("OEM_CAM", "Probe result: supported=" + supported
                        + ", type=" + camType + " (" + detail + ")");

                if (callback != null) {
                    final boolean finalSupported = supported;
                    final int finalType = camType;
                    final String finalDetail = detail;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onProbeResult(finalSupported, finalType, finalDetail);
                        }
                    });
                }
            }
        }, "FitcamxOemProbe");
        t.setDaemon(true);
        t.start();
    }

    public void setOverlayX(int overlayX) {
        desiredOverlayX.set(Math.max(0, overlayX));
    }

    public boolean isStreamActive() {
        return streamRunning;
    }

    public void notifyStockCameraStarting() {
        if (streamRunning) {
            stockCameraSignalPending.set(true);
        }
    }

    public void startStream(int initialOverlayX, final StreamListener listener) {
        desiredOverlayX.set(Math.max(0, initialOverlayX));
        synchronized (streamLock) {
            activeListener = listener;
            if (streamRunning && activeStreamThread != null && activeStreamThread.isAlive()) {
                byte st = currentStreamStatus;
                if (st == STATUS_REVERSE || st == STATUS_CAR_OFF || st == STATUS_ERROR) {
                    postStatus(st, describeStatus(st));
                }
                return;
            }
            stopStreamLocked();
            activeListener = listener;
            streamRunning = true;
            currentStreamStatus = -1;
            stockCameraSignalPending.set(false);
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    runStreamLoop();
                }
            }, "FitcamxOemStream");
            worker.setDaemon(true);
            activeStreamThread = worker;
            worker.start();
        }
    }

    public void stopStream() {
        synchronized (streamLock) {
            stopStreamLocked();
        }
    }

    private void stopStreamLocked() {
        streamRunning = false;
        activeListener = null;
        currentStreamStatus = -1;
        LocalSocket sock = activeSocket;
        activeSocket = null;
        if (sock != null) {
            try { sock.close(); } catch (IOException ignored) {}
        }
        Thread prev = activeStreamThread;
        activeStreamThread = null;
        if (prev != null && prev != Thread.currentThread()) {
            prev.interrupt();
        }
    }

    private LocalSocket tryConnectOnce(int soTimeoutMs) {
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            sock.setSoTimeout(soTimeoutMs);
            return sock;
        } catch (IOException e) {
            try { sock.close(); } catch (IOException ignored) {}
            return null;
        }
    }

    private LocalSocket connectOrStartDaemon(int maxWaitMs) {
        LocalSocket warm = tryConnectOnce(2500);
        if (warm != null) {
            return warm;
        }
        try {
            int rc = HmgapidClient.call("service-start", SERVICE_NAME);
            if (rc != 0) {
                DebugLogger.log("OEM_CAM", "hmgapid service-start " + SERVICE_NAME + " unavailable (rc=" + rc + ")");
                return null;
            }
        } catch (Throwable t) {
            return null;
        }

        long deadline = SystemClock.uptimeMillis() + maxWaitMs;
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(120L);
            LocalSocket connected = tryConnectOnce(2500);
            if (connected != null) {
                return connected;
            }
        }
        return null;
    }

    private void runStreamLoop() {
        Bitmap[] buffers = null;
        int bufferIdx = 0;
        byte[] rawFrameBytes = null;
        IntBuffer rawIntView = null;
        int[] pixelUnpack = null;
        byte lastStatus = -1;

        while (streamRunning && Thread.currentThread() == activeStreamThread) {
            LocalSocket sock = null;
            try {
                if (lastStatus != STATUS_STARTING) {
                    lastStatus = STATUS_STARTING;
                    currentStreamStatus = STATUS_STARTING;
                    postStatus(STATUS_STARTING, "Starting Backup Camera...");
                }
                sock = connectOrStartDaemon(2800);
                if (sock == null || !streamRunning) {
                    lastStatus = STATUS_ERROR;
                    currentStreamStatus = STATUS_ERROR;
                    postStatus(STATUS_ERROR, "Backup camera service unavailable");
                    SystemClock.sleep(1200L);
                    continue;
                }
                activeSocket = sock;
                sock.setSoTimeout(3000);

                DataOutputStream out = new DataOutputStream(sock.getOutputStream());
                DataInputStream in = new DataInputStream(sock.getInputStream());

                out.writeByte('S');
                out.writeInt(desiredOverlayX.get());
                out.flush();

                while (streamRunning && Thread.currentThread() == activeStreamThread) {
                    long pollStart = SystemClock.uptimeMillis();
                    if (stockCameraSignalPending.getAndSet(false)) {
                        out.writeByte('R');
                        out.flush();
                    }

                    out.writeByte('F');
                    out.writeInt(desiredOverlayX.get());
                    out.flush();

                    int magic = in.readInt();
                    if (magic != PROTOCOL_MAGIC) {
                        throw new IOException("Protocol sync lost");
                    }
                    byte status = in.readByte();
                    int w = in.readInt();
                    int h = in.readInt();
                    long seq = in.readLong();
                    String diagLogs = in.readUTF();

                    if (diagLogs != null && !diagLogs.isEmpty()) {
                        for (String line : diagLogs.split("\n")) {
                            if (!line.trim().isEmpty()) {
                                DebugLogger.log("OEM_CAM", line.trim());
                            }
                        }
                    }

                    if (status != lastStatus) {
                        lastStatus = status;
                        currentStreamStatus = status;
                        postStatus(status, describeStatus(status));
                    }

                    if (w > 0 && h > 0) {
                        int byteLen = w * h * 4;
                        if (rawFrameBytes == null || rawFrameBytes.length != byteLen) {
                            rawFrameBytes = new byte[byteLen];
                            rawIntView = ByteBuffer.wrap(rawFrameBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                            pixelUnpack = new int[w * h];
                            buffers = new Bitmap[] {
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            };
                        }
                        in.readFully(rawFrameBytes);
                        rawIntView.position(0);
                        rawIntView.get(pixelUnpack);

                        final Bitmap targetBmp = buffers[bufferIdx];
                        bufferIdx = (bufferIdx + 1) & 1;
                        targetBmp.setPixels(pixelUnpack, 0, w, 0, 0, w, h);
                        targetBmp.setHasAlpha(false);

                        final byte frameStatus = status;
                        if (streamRunning) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    StreamListener l = activeListener;
                                    if (streamRunning && l != null) {
                                        l.onFrame(targetBmp, frameStatus);
                                    }
                                }
                            });
                        }
                    }

                    long elapsed = SystemClock.uptimeMillis() - pollStart;
                    long waitMs = (w > 0 ? 48L : 42L) - elapsed;
                    if (waitMs > 4L && streamRunning) {
                        SystemClock.sleep(waitMs);
                    }
                }

                try {
                    out.writeByte('Q');
                    out.flush();
                } catch (IOException ignored) {}
            } catch (Throwable t) {
                if (streamRunning) {
                    DebugLogger.log("OEM_CAM", "Stream connection interrupted: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
                    SystemClock.sleep(600L);
                }
            } finally {
                if (sock != null) {
                    try { sock.close(); } catch (IOException ignored) {}
                }
                if (activeSocket == sock) {
                    activeSocket = null;
                }
            }
        }
    }

    private void postStatus(final byte status, final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                StreamListener l = activeListener;
                if (streamRunning && l != null) {
                    l.onStatusChanged(status, message);
                }
            }
        });
    }

    public static String describeStatus(byte status) {
        switch (status) {
            case STATUS_OWNED_LIVE:
                return "Live Backup Camera";
            case STATUS_SHARED_LIVE:
                return "Live Backup Camera (Shared)";
            case STATUS_REVERSE:
                return "Reverse Camera Active";
            case STATUS_CAR_OFF:
                return "Vehicle Ignition Off";
            case STATUS_STARTING:
                return "Starting Backup Camera...";
            default:
                return "Backup Camera Unavailable";
        }
    }
}
