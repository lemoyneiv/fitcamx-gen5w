/*
 * Adapted in part from KramerTech/gen5-RearDashcam (MIT License, Copyright (c) 2026 KramerTech).
 * On-demand uid=1000 helper daemon for Gen5W analog backup camera live view & coexistence.
 *
 * Key properties:
 * - Runs only while Fitcamx is open (started manually via hmgapid, auto-exits after 30s idle).
 * - Zero writes to internal /data flash (all diagnostics buffered in RAM and returned over IPC).
 * - Coexists seamlessly with standalone KramerTech/gen5-RearDashcam: passively shares /dev/admem
 *   when gen5-RearDashcam already holds RearCamera, or acquires RearCamera directly when idle.
 * - Releases RearCamera within <= 800 ms (with hard self-kill watchdog) on Reverse gear or stock
 *   camera start, restoring hardware parking guide lines first.
 */
package fitcamx.rearcam;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RearCamDaemon {
    public static final String SOCKET_NAME = "fitcamx.rearcam";
    public static final String APP_PACKAGE = "fitcamx.app";
    public static final int PROTOCOL_MAGIC = 0x46435231; // "FCR1"

    public static final int OUT_WIDTH = 720;
    public static final int OUT_HEIGHT = 480;

    public static final byte STATUS_ERROR = 0;
    public static final byte STATUS_OWNED_LIVE = 1;
    public static final byte STATUS_SHARED_LIVE = 2;
    public static final byte STATUS_REVERSE = 3;
    public static final byte STATUS_STARTING = 4;
    public static final byte STATUS_CAR_OFF = 5;

    public static final String EXTERNAL_STATUS_PATH = "/data/nemesis/dashcam/status";
    public static final String EXTERNAL_HEARTBEAT_PATH = "/data/nemesis/dashcam/heartbeat";

    private static final long IDLE_EXIT_MS = 30_000L;
    private static final long LEASE_TIMEOUT_MS = 1_800L;
    private static final long GUARD_POLL_MS = 50L;
    private static final long REVERSE_SETTLE_MS = 1_500L;
    /**
     * Standalone gen5-RearDashcam waits SETTLE_MS=3000ms (plus up to RETRY_MS=5000ms) after Reverse
     * before reclaiming RearCamera, and treats any open media.rearcamera client as the stock RVC.
     * Deferring direct open for up to 6.5s when gen5-RearDashcam is alive & recording lets it
     * reclaim RearCamera first so Fitcamx seamlessly enters STATUS_SHARED_LIVE without interrupting
     * continuous USB recording.
     */
    private static final long EXTERNAL_TAKEBACK_GRACE_MS = 6_500L;
    private static final long EXTERNAL_POLL_RETRY_MS = 200L;
    private static final long START_RETRY_MS = 1_200L;
    private static final long RELEASE_WATCHDOG_MS = 800L;
    private static final long WATCH_MS = 3L;
    private static final long WATCH_AFTER_START_MS = 12L;
    private static final long MIN_FRAME_INTERVAL_MS = 50L; // ~20 fps cap

    private static final int[][] CANDIDATE_RING_SIZES = {
        { 720, 480 }, { 1140, 720 }, { 1280, 720 }, { 1920, 720 }, { 1780, 720 },
        { 1420, 538 }, { 1280, 538 }, { 1280, 480 }, { 960, 720 }, { 760, 480 }
    };

    private static final ArrayDeque<String> sDiagLogs = new ArrayDeque<String>(64);
    private static final AtomicInteger sActiveClients = new AtomicInteger(0);
    private static volatile long sLastActivityMs = SystemClock.uptimeMillis();

    private static volatile Handler sCameraHandler;
    private static final Object sRamLock = new Object();
    private static long sMappedAddress = 0L;
    private static long sMappedLength = 0L;
    private static Boolean sCachedSupported = null;
    private static int sCachedCameraType = -1;
    private static String sCachedProbeDetail = "";

    private static final StreamEngine sEngine = new StreamEngine();

    private RearCamDaemon() {}

    public static int parseExternalHeartbeatPid(String heartbeatText) {
        if (heartbeatText == null) return -1;
        String trimmed = heartbeatText.trim();
        if (trimmed.isEmpty()) return -1;
        int space = trimmed.indexOf(' ');
        String pidToken = space > 0 ? trimmed.substring(0, space).trim() : trimmed;
        try {
            int pid = Integer.parseInt(pidToken);
            return pid > 0 ? pid : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static boolean shouldDeferToExternalRecorder(String statusText, boolean processAlive) {
        if (!processAlive || statusText == null || statusText.isEmpty()) {
            return false;
        }
        String state = null;
        boolean enabled = false;
        boolean drive = false;
        String[] lines = statusText.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("state=")) {
                state = line.substring(6).trim();
            } else if (line.startsWith("enabled=")) {
                enabled = "true".equalsIgnoreCase(line.substring(8).trim());
            } else if (line.startsWith("drive=")) {
                drive = "true".equalsIgnoreCase(line.substring(6).trim());
            }
        }
        if (state == null || state.isEmpty()) {
            return false;
        }
        if ("live".equals(state)) {
            return true;
        }
        return enabled && drive
                && !"off".equals(state)
                && !"caroff".equals(state)
                && !"unsupported".equals(state)
                && !"nodrive".equals(state);
    }

    private static String readSmallTextFile(File f, int maxChars) {
        if (f == null || !f.isFile() || !f.canRead()) return null;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder(256);
            String line;
            while ((line = br.readLine()) != null && sb.length() < maxChars) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static boolean isExternalRecorderExpectingCamera() {
        String hb = readSmallTextFile(new File(EXTERNAL_HEARTBEAT_PATH), 128);
        int pid = parseExternalHeartbeatPid(hb);
        if (pid <= 0 || !new File("/proc/" + pid).isDirectory()) {
            return false;
        }
        String st = readSmallTextFile(new File(EXTERNAL_STATUS_PATH), 1024);
        return shouldDeferToExternalRecorder(st, true);
    }

    public static void main(String[] args) {
        try {
            PosixBridge.dropRootIfNeeded();
        } catch (Throwable ignored) {}

        try {
            android.os.Process.class.getMethod("setArgV0", String.class)
                    .invoke(null, "fitcamx-rearcam");
        } catch (Throwable ignored) {}

        // Restore parking guide lines if a prior session died mid-stream
        CarBridge.restoreGuideLines(false);
        Runtime.getRuntime().addShutdownHook(new Thread("fitcamx-rearcam-shutdown") {
            @Override
            public void run() {
                try {
                    CarBridge.restoreGuideLines(false);
                } catch (Throwable ignored) {}
            }
        });

        startCameraLooperThread();
        sEngine.startGuardThread();
        startIdleExitWatchdog();

        logRam("Daemon ready (uid=" + PosixBridge.getCurrentUid() + ")");

        LocalServerSocket server = null;
        try {
            server = new LocalServerSocket(SOCKET_NAME);
            while (true) {
                final LocalSocket client = server.accept();
                sLastActivityMs = SystemClock.uptimeMillis();
                if (!isAuthorizedPeer(client)) {
                    try { client.close(); } catch (IOException ignored) {}
                    continue;
                }
                sActiveClients.incrementAndGet();
                Thread worker = new Thread("fitcamx-rearcam-client") {
                    @Override
                    public void run() {
                        try {
                            handleClient(client);
                        } catch (Throwable ignored) {
                        } finally {
                            try { client.close(); } catch (IOException ignored) {}
                            sActiveClients.decrementAndGet();
                            sLastActivityMs = SystemClock.uptimeMillis();
                        }
                    }
                };
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Throwable t) {
            CarBridge.restoreGuideLines(false);
            System.exit(0);
        } finally {
            if (server != null) {
                try { server.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void startCameraLooperThread() {
        final CountDownLatch ready = new CountDownLatch(1);
        Thread t = new Thread("fitcamx-cam-looper") {
            @Override
            public void run() {
                Looper.prepare();
                sCameraHandler = new Handler();
                ready.countDown();
                Looper.loop();
            }
        };
        t.setDaemon(true);
        t.start();
        try {
            ready.await(2000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
    }

    private static void startIdleExitWatchdog() {
        Thread t = new Thread("fitcamx-idle-exit") {
            @Override
            public void run() {
                while (true) {
                    SystemClock.sleep(2500L);
                    if (sActiveClients.get() <= 0 && !sEngine.hasActiveLease()) {
                        long idle = SystemClock.uptimeMillis() - sLastActivityMs;
                        if (idle >= IDLE_EXIT_MS) {
                            sEngine.stopSessionImmediate("idle exit");
                            CarBridge.restoreGuideLines(false);
                            System.exit(0);
                        }
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    private static boolean isAuthorizedPeer(LocalSocket client) {
        try {
            int peerUid = client.getPeerCredentials().getUid();
            if (peerUid == 0 || peerUid == 1000) return true;
            int appUid = PosixBridge.getPackageUid(APP_PACKAGE);
            return appUid > 0 && peerUid == appUid;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void logRam(String msg) {
        synchronized (sDiagLogs) {
            if (sDiagLogs.size() >= 64) {
                sDiagLogs.removeFirst();
            }
            sDiagLogs.addLast(msg);
        }
    }

    private static String drainDiagLogs() {
        synchronized (sDiagLogs) {
            if (sDiagLogs.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            while (!sDiagLogs.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(sDiagLogs.removeFirst());
            }
            return sb.toString();
        }
    }

    private static boolean ensureProbedAndMapped() {
        synchronized (sRamLock) {
            if (sCachedSupported != null) {
                return sCachedSupported.booleanValue();
            }
            int camType = CarBridge.getCameraType();
            sCachedCameraType = camType;
            if (camType != CarBridge.CAMERA_TYPE_ANALOG) {
                sCachedSupported = Boolean.FALSE;
                sCachedProbeDetail = "Unsupported RearCamera.getCameraType()=" + camType + " (analog=0 required)";
                logRam(sCachedProbeDetail);
                return false;
            }
            try {
                long[] region = PmapParser.findRearCameraRegion();
                if (region == null) {
                    sCachedSupported = Boolean.FALSE;
                    sCachedProbeDetail = "No rearcamera region in /proc/pmap";
                    logRam(sCachedProbeDetail);
                    return false;
                }
                if (!PmapParser.fitsRing(region[1], OUT_WIDTH, OUT_HEIGHT)) {
                    sCachedSupported = Boolean.FALSE;
                    sCachedProbeDetail = "rearcamera pmap region too small: 0x" + Long.toHexString(region[1]);
                    logRam(sCachedProbeDetail);
                    return false;
                }
                sMappedAddress = PosixBridge.mapReadOnlyAdmem(region[0], region[1]);
                sMappedLength = region[1];
                sCachedSupported = Boolean.TRUE;
                sCachedProbeDetail = String.format(Locale.US,
                        "Supported Gen5W analog backup camera (type=0, pmap=0x%x, size=0x%x)",
                        region[0], region[1]);
                logRam(sCachedProbeDetail);
                return true;
            } catch (Throwable t) {
                sCachedSupported = Boolean.FALSE;
                sCachedProbeDetail = "Failed to map /dev/admem: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                logRam(sCachedProbeDetail);
                return false;
            }
        }
    }

    private static void readRamInts(long byteOffset, int[] dst, int dstOffset, int intCount) throws Exception {
        if (byteOffset < 0 || byteOffset + (long) intCount * 4L > sMappedLength) {
            throw new IndexOutOfBoundsException("admem read out of range");
        }
        PosixBridge.peekIntArray(sMappedAddress + byteOffset, dst, dstOffset, intCount);
    }

    private static void handleClient(LocalSocket socket) throws IOException {
        socket.setSoTimeout(3000);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());

        int initialCmd = in.read();
        if (initialCmd < 0) return;

        if (initialCmd == 'P') {
            boolean ok = ensureProbedAndMapped();
            out.writeInt(PROTOCOL_MAGIC);
            out.writeBoolean(ok);
            out.writeInt(sCachedCameraType);
            out.writeUTF(sCachedProbeDetail != null ? sCachedProbeDetail : "");
            out.flush();
            return;
        }

        if (initialCmd != 'S') {
            return;
        }

        int initialOverlayX = in.readInt();
        if (!ensureProbedAndMapped()) {
            out.writeInt(PROTOCOL_MAGIC);
            out.writeByte(STATUS_ERROR);
            out.writeInt(0);
            out.writeInt(0);
            out.writeLong(0L);
            out.writeUTF(sCachedProbeDetail != null ? sCachedProbeDetail : "Unsupported");
            out.flush();
            return;
        }

        sEngine.touchLease(initialOverlayX);
        long lastSentSeq = 0L;
        byte[] frameBytes = new byte[OUT_WIDTH * OUT_HEIGHT * 4];
        IntBuffer frameIntView = ByteBuffer.wrap(frameBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        int[] snapshot = new int[OUT_WIDTH * OUT_HEIGHT];

        try {
            while (true) {
                int op;
                try {
                    op = in.read();
                } catch (EOFException eof) {
                    break;
                }
                if (op < 0 || op == 'Q') {
                    break;
                }
                if (op == 'R') {
                    sEngine.onStockCameraSignal();
                    continue;
                }
                if (op != 'F') {
                    break;
                }

                int requestedOverlayX = in.readInt();
                sLastActivityMs = SystemClock.uptimeMillis();
                sEngine.touchLease(requestedOverlayX);

                long seq = sEngine.copyLatestFrameIfNewer(lastSentSeq, snapshot);
                byte status = sEngine.getStatus();
                String logs = drainDiagLogs();

                out.writeInt(PROTOCOL_MAGIC);
                out.writeByte(status);
                if (seq > lastSentSeq && (status == STATUS_OWNED_LIVE || status == STATUS_SHARED_LIVE)) {
                    lastSentSeq = seq;
                    frameIntView.clear();
                    frameIntView.put(snapshot);
                    out.writeInt(OUT_WIDTH);
                    out.writeInt(OUT_HEIGHT);
                    out.writeLong(seq);
                    out.writeUTF(logs);
                    out.write(frameBytes);
                } else {
                    out.writeInt(0);
                    out.writeInt(0);
                    out.writeLong(lastSentSeq);
                    out.writeUTF(logs);
                }
                out.flush();
            }
        } finally {
            sEngine.clearLease();
        }
    }

    /**
     * Manages RearCamera ownership, coexistence with external recorders, 50ms Reverse gear guard,
     * and ring-buffer frame capture.
     */
    private static final class StreamEngine {
        private volatile long leaseUntilMs = 0L;
        private volatile int desiredOverlayX = 1200;
        private int appliedOverlayX = -1;
        private volatile long lastBlockMs = 0L;
        private volatile byte status = STATUS_STARTING;

        private CarBridge.RearCamSession ownedCamera = null;
        private RingWatcher ringWatcher = null;
        private boolean passiveSharedMode = false;
        private long nextStartAttemptMs = 0L;
        private long externalYieldUntilMs = 0L;
        private long externalYieldCooldownUntilMs = 0L;
        private long lastExternalCheckMs = 0L;

        private final Object frameLock = new Object();
        private final int[] latestPixels = new int[OUT_WIDTH * OUT_HEIGHT];
        private long latestFrameSeq = 0L;
        private long lastFrameAtMs = 0L;

        void touchLease(int overlayX) {
            desiredOverlayX = Math.max(0, overlayX);
            leaseUntilMs = SystemClock.uptimeMillis() + LEASE_TIMEOUT_MS;
        }

        void clearLease() {
            leaseUntilMs = 0L;
        }

        boolean hasActiveLease() {
            return SystemClock.uptimeMillis() < leaseUntilMs;
        }

        byte getStatus() {
            return status;
        }

        void onStockCameraSignal() {
            logRam("Stock camera start signal received; releasing immediately");
            lastBlockMs = SystemClock.uptimeMillis();
            releaseOwnedCameraWithWatchdog("stock camera broadcast");
            status = STATUS_REVERSE;
        }

        void publishFrame(int[] pixels) {
            long now = SystemClock.uptimeMillis();
            synchronized (frameLock) {
                if (now - lastFrameAtMs < MIN_FRAME_INTERVAL_MS) {
                    return;
                }
                for (int i = 0; i < latestPixels.length; i++) {
                    latestPixels[i] = pixels[i] | 0xFF000000;
                }
                latestFrameSeq++;
                lastFrameAtMs = now;
            }
        }

        long copyLatestFrameIfNewer(long sinceSeq, int[] dst) {
            synchronized (frameLock) {
                if (latestFrameSeq <= sinceSeq) {
                    return sinceSeq;
                }
                System.arraycopy(latestPixels, 0, dst, 0, dst.length);
                return latestFrameSeq;
            }
        }

        void startGuardThread() {
            Thread guard = new Thread("fitcamx-rearcam-guard") {
                @Override
                public void run() {
                    guardLoop();
                }
            };
            guard.setDaemon(true);
            guard.start();
        }

        private void guardLoop() {
            while (true) {
                try {
                    long now = SystemClock.uptimeMillis();
                    boolean wanted = now < leaseUntilMs && sMappedAddress != 0L;
                    if (!wanted) {
                        if (ownedCamera != null || ringWatcher != null) {
                            stopSessionImmediate("viewer closed");
                        }
                        status = STATUS_STARTING;
                        SystemClock.sleep(100L);
                        continue;
                    }

                    boolean reverseBlocked = isReverseBlockedNow();
                    boolean ignOn = CarBridge.isIgnitionOn();

                    if (!ignOn) {
                        stopSessionImmediate("ignition off");
                        status = STATUS_CAR_OFF;
                        SystemClock.sleep(GUARD_POLL_MS);
                        continue;
                    }

                    if (reverseBlocked) {
                        if (ownedCamera != null || ringWatcher != null) {
                            stopSessionImmediate("reverse gear");
                        }
                        status = STATUS_REVERSE;
                        SystemClock.sleep(GUARD_POLL_MS);
                        continue;
                    }

                    // Update overlayX dynamically if user toggled between Side-by-Side and Fullscreen
                    if (ownedCamera != null && desiredOverlayX != appliedOverlayX) {
                        final int newX = desiredOverlayX;
                        appliedOverlayX = newX;
                        Handler h = sCameraHandler;
                        if (h != null) {
                            h.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (ownedCamera != null) {
                                        ownedCamera.updateOverlayPosition(OUT_WIDTH, OUT_HEIGHT, newX);
                                    }
                                }
                            });
                        }
                    }

                    // Yield RearCamera if standalone gen5-RearDashcam became ready to record (e.g. USB inserted)
                    if (!passiveSharedMode && ownedCamera != null && (now - lastExternalCheckMs) >= 500L) {
                        lastExternalCheckMs = now;
                        if (now >= externalYieldCooldownUntilMs && isExternalRecorderExpectingCamera()) {
                            logRam("Yielding RearCamera to standalone USB dashcam recorder");
                            stopSessionImmediate("external recorder active");
                            status = STATUS_STARTING;
                            externalYieldUntilMs = now + EXTERNAL_TAKEBACK_GRACE_MS;
                            externalYieldCooldownUntilMs = now + 30_000L;
                            nextStartAttemptMs = now + EXTERNAL_POLL_RETRY_MS;
                        }
                    }

                    // Check if a passive shared session stalled (e.g. external recorder stopped)
                    if (passiveSharedMode && ringWatcher != null) {
                        long quietMs = now - ringWatcher.getLastCompletionMs();
                        if (quietMs > 2500L) {
                            logRam("Shared feed stalled (" + quietMs + "ms); re-evaluating camera ownership");
                            stopRingWatcherOnly();
                            passiveSharedMode = false;
                            nextStartAttemptMs = 0L;
                        }
                    }

                    // Check if our owned camera stalled for > 8s
                    if (!passiveSharedMode && ownedCamera != null && ringWatcher != null) {
                        long quietMs = now - ringWatcher.getLastCompletionMs();
                        if (quietMs > 8000L) {
                            logRam("Owned camera stalled (" + quietMs + "ms); restarting session");
                            stopSessionImmediate("frame stall");
                            nextStartAttemptMs = now + 500L;
                        }
                    }

                    if (ownedCamera == null && ringWatcher == null && now >= nextStartAttemptMs) {
                        nextStartAttemptMs = now + START_RETRY_MS;
                        startSession();
                    }
                } catch (Throwable t) {
                    logRam("Guard error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                SystemClock.sleep(GUARD_POLL_MS);
            }
        }

        private boolean isReverseBlockedNow() {
            long now = SystemClock.uptimeMillis();
            if (CarBridge.getGearStatus() == CarBridge.GEAR_REVERSE) {
                lastBlockMs = now;
                return true;
            }
            return lastBlockMs != 0L && (now - lastBlockMs) < REVERSE_SETTLE_MS;
        }

        private void startSession() {
            // 1. Coexistence check: does standalone gen5-RearDashcam (or stock camera) already hold RearCamera?
            if (PosixBridge.isRearCameraOpenInHal()) {
                if (isReverseBlockedNow()) {
                    status = STATUS_REVERSE;
                    return;
                }
                externalYieldUntilMs = 0L;
                int[] detected = detectActiveRingSize();
                int srcW = detected != null ? detected[0] : OUT_WIDTH;
                int srcH = detected != null ? detected[1] : OUT_HEIGHT;
                logRam("RearCamera already open in HAL; sharing /dev/admem passively at " + srcW + "x" + srcH);
                passiveSharedMode = true;
                ringWatcher = new RingWatcher(srcW, srcH, this);
                ringWatcher.start();
                status = STATUS_SHARED_LIVE;
                return;
            }

            // 1b. If standalone gen5-RearDashcam is alive & waiting out its 3s post-Reverse settle window
            // (or 5s startup retry) to reclaim RearCamera, let it open RearCamera first so we share /dev/admem.
            long now = SystemClock.uptimeMillis();
            boolean waitingForExternalTakeback = (now < externalYieldUntilMs)
                    || (lastBlockMs != 0L && (now - lastBlockMs) < EXTERNAL_TAKEBACK_GRACE_MS
                        && isExternalRecorderExpectingCamera());
            if (!waitingForExternalTakeback && now >= externalYieldCooldownUntilMs
                    && isExternalRecorderExpectingCamera()) {
                logRam("Standalone USB dashcam recorder is starting; waiting up to 6.5s to share /dev/admem");
                externalYieldUntilMs = now + EXTERNAL_TAKEBACK_GRACE_MS;
                externalYieldCooldownUntilMs = now + 30_000L;
                waitingForExternalTakeback = true;
            }
            if (waitingForExternalTakeback) {
                status = STATUS_STARTING;
                nextStartAttemptMs = now + EXTERNAL_POLL_RETRY_MS;
                return;
            }

            // 2. Active On-Demand Ownership: RearCamera is free
            passiveSharedMode = false;
            status = STATUS_STARTING;
            final int targetOverlayX = desiredOverlayX;
            final CountDownLatch startedLatch = new CountDownLatch(1);
            final boolean[] okHolder = new boolean[] { false };

            Handler h = sCameraHandler;
            if (h == null) return;
            h.post(new Runnable() {
                @Override
                public void run() {
                    CarBridge.RearCamSession cam = null;
                    try {
                        if (isReverseBlockedNow()) {
                            return;
                        }
                        String earlyMsg = PosixBridge.stopEarlyCameraIfRunning();
                        if (earlyMsg != null) {
                            logRam(earlyMsg);
                        }
                        if (isReverseBlockedNow()) {
                            return;
                        }
                        boolean hidLines = CarBridge.hideGuideLinesIfNeeded();
                        if (hidLines) {
                            logRam("Hid hardware parking guide lines for clean feed");
                        }
                        int camId = CarBridge.findStockBackCameraId();
                        cam = CarBridge.RearCamSession.open(camId, new Runnable() {
                            @Override
                            public void run() {
                                logRam("RearCamera HAL error callback; releasing");
                                stopSessionImmediate("HAL error");
                            }
                        });
                        if (isReverseBlockedNow()) {
                            logRam("Aborting RearCamera open immediately (reverse triggered mid-open)");
                            CarBridge.restoreGuideLines(true);
                            cam.release();
                            cam = null;
                            return;
                        }
                        cam.startDirectDisplay(OUT_WIDTH, OUT_HEIGHT, targetOverlayX);
                        if (isReverseBlockedNow()) {
                            logRam("Releasing RearCamera immediately (reverse triggered during preview start)");
                            CarBridge.restoreGuideLines(true);
                            cam.release();
                            cam = null;
                            return;
                        }
                        ownedCamera = cam;
                        appliedOverlayX = targetOverlayX;
                        okHolder[0] = true;
                    } catch (Throwable t) {
                        logRam("Open RearCamera failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                        CarBridge.restoreGuideLines(true);
                        if (cam != null) {
                            cam.release();
                        }
                    } finally {
                        startedLatch.countDown();
                    }
                }
            });

            try {
                startedLatch.await(3000L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}

            if (okHolder[0] && ownedCamera != null) {
                logRam("Started direct-display RearCamera (720x480, overlayX=" + targetOverlayX + ")");
                ringWatcher = new RingWatcher(OUT_WIDTH, OUT_HEIGHT, this);
                ringWatcher.start();
                status = STATUS_OWNED_LIVE;
            } else if (isReverseBlockedNow()) {
                status = STATUS_REVERSE;
            }
        }

        private void stopRingWatcherOnly() {
            RingWatcher rw = ringWatcher;
            ringWatcher = null;
            if (rw != null) {
                rw.stopWatching();
            }
        }

        void stopSessionImmediate(String reason) {
            stopRingWatcherOnly();
            passiveSharedMode = false;
            releaseOwnedCameraWithWatchdog(reason);
        }

        private void releaseOwnedCameraWithWatchdog(String reason) {
            // ALWAYS restore parking guide lines BEFORE releasing camera so even a watchdog kill leaves them restored!
            CarBridge.restoreGuideLines(true);
            final CarBridge.RearCamSession cam = ownedCamera;
            if (cam == null) return;
            ownedCamera = null;
            appliedOverlayX = -1;
            logRam("Releasing owned RearCamera (" + reason + ")");

            Handler h = sCameraHandler;
            if (h == null) {
                cam.release();
                return;
            }
            final CountDownLatch done = new CountDownLatch(1);
            h.postAtFrontOfQueue(new Runnable() {
                @Override
                public void run() {
                    try {
                        cam.release();
                    } finally {
                        done.countDown();
                    }
                }
            });

            boolean releasedInTime = false;
            try {
                releasedInTime = done.await(RELEASE_WATCHDOG_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}

            if (!releasedInTime) {
                // Hard safety guarantee: dying frees our Binder death-recipient handle in mediaserver immediately
                CarBridge.restoreGuideLines(false);
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        }

        /**
         * Probes /dev/admem across candidate Gen5W resolutions to find the active ring size when
         * sharing with an external camera owner.
         */
        private int[] detectActiveRingSize() {
            try {
                int blockBytes = 16384;
                int probeInts = 16;
                int maxBlocks = (int) Math.min(sMappedLength / blockBytes, 2560L);
                if (maxBlocks <= 0) return null;

                int[] sample = new int[probeInts];
                int[] hashes = new int[maxBlocks];
                boolean[] changed = new boolean[maxBlocks];

                for (int pass = 0; pass < 4; pass++) {
                    for (int b = 0; b < maxBlocks; b++) {
                        long off = (long) b * blockBytes + (blockBytes / 2) - (probeInts * 2L);
                        readRamInts(off, sample, 0, probeInts);
                        int h = 1;
                        for (int v : sample) h = 31 * h + v;
                        if (pass > 0 && h != hashes[b]) {
                            changed[b] = true;
                        }
                        hashes[b] = h;
                    }
                    if (pass < 3) SystemClock.sleep(45L);
                }

                int lastChangedBlock = -1;
                for (int b = maxBlocks - 1; b >= 0; b--) {
                    if (changed[b]) {
                        lastChangedBlock = b;
                        break;
                    }
                }
                if (lastChangedBlock < 0) {
                    return new int[] { OUT_WIDTH, OUT_HEIGHT };
                }
                long activeBytes = (long) (lastChangedBlock + 1) * blockBytes;
                int[] best = null;
                long bestRingBytes = Long.MAX_VALUE;
                for (int[] size : CANDIDATE_RING_SIZES) {
                    long ringBytes = (long) size[0] * (long) size[1] * 4L * PmapParser.RING_SLOTS;
                    if (ringBytes <= sMappedLength && ringBytes >= activeBytes && ringBytes < bestRingBytes) {
                        best = size;
                        bestRingBytes = ringBytes;
                    }
                }
                return best != null ? best : new int[] { OUT_WIDTH, OUT_HEIGHT };
            } catch (Throwable t) {
                return new int[] { OUT_WIDTH, OUT_HEIGHT };
            }
        }
    }

    /**
     * Polls a strip 1/16 down each of the 4 ring slots in /dev/admem; when slot N starts changing,
     * the previously-written slot is guaranteed complete and untorn for ~50 ms while the other 3
     * slots fill. Scales to 720x480 if sharing a larger ring.
     */
    private static final class RingWatcher {
        private final int srcWidth;
        private final int srcHeight;
        private final long frameBytes;
        private final int[] xs;
        private final int[] ys;
        private final StreamEngine sink;
        private volatile boolean running = true;
        private volatile long lastCompletionMs = SystemClock.uptimeMillis();
        private final Thread worker;

        RingWatcher(int srcWidth, int srcHeight, StreamEngine sink) {
            this.srcWidth = srcWidth;
            this.srcHeight = srcHeight;
            this.frameBytes = (long) srcWidth * (long) srcHeight * 4L;
            this.sink = sink;
            if (srcWidth == OUT_WIDTH && srcHeight == OUT_HEIGHT) {
                this.xs = null;
                this.ys = null;
            } else {
                this.xs = new int[OUT_WIDTH];
                this.ys = new int[OUT_HEIGHT];
                for (int c = 0; c < OUT_WIDTH; c++) {
                    this.xs[c] = (2 * c + 1) * srcWidth / (2 * OUT_WIDTH);
                }
                for (int r = 0; r < OUT_HEIGHT; r++) {
                    this.ys[r] = (2 * r + 1) * srcHeight / (2 * OUT_HEIGHT);
                }
            }
            this.worker = new Thread("fitcamx-ring-watch") {
                @Override
                public void run() {
                    loop();
                }
            };
            this.worker.setDaemon(true);
        }

        void start() {
            worker.start();
        }

        long getLastCompletionMs() {
            return lastCompletionMs;
        }

        void stopWatching() {
            running = false;
            try {
                worker.join(180L);
            } catch (InterruptedException ignored) {}
        }

        private void loop() {
            long stripOffset = ((long) (srcHeight / 16) * srcWidth + (srcWidth / 3)) * 4L;
            int stripLen = Math.max(16, srcWidth / 3);
            int[] strip = new int[stripLen];
            int[] lastHashes = new int[PmapParser.RING_SLOTS];
            int[] curHashes = new int[PmapParser.RING_SLOTS];
            int[] outBuf = new int[OUT_WIDTH * OUT_HEIGHT];
            int[] srcRow = (xs != null) ? new int[srcWidth] : null;

            int writingSlot = -1;
            boolean first = true;
            long lastCopiedMs = 0L;

            while (running) {
                boolean startedNewSlot = false;
                try {
                    int changedCount = 0;
                    int changedSlot = -1;
                    for (int s = 0; s < PmapParser.RING_SLOTS; s++) {
                        readRamInts(s * frameBytes + stripOffset, strip, 0, stripLen);
                        int h = 1;
                        for (int p : strip) h = 31 * h + p;
                        curHashes[s] = h;
                        if (!first && h != lastHashes[s]) {
                            changedCount++;
                            changedSlot = s;
                        }
                        lastHashes[s] = h;
                    }
                    first = false;

                    if (changedCount > 1) {
                        writingSlot = -1;
                    } else if (changedCount == 1 && changedSlot != writingSlot) {
                        int completedSlot = writingSlot;
                        writingSlot = changedSlot;
                        startedNewSlot = true;

                        if (completedSlot >= 0) {
                            long now = SystemClock.uptimeMillis();
                            lastCompletionMs = now;
                            if (now - lastCopiedMs >= MIN_FRAME_INTERVAL_MS) {
                                int beforeHash = lastHashes[completedSlot];
                                copySlot(completedSlot, outBuf, srcRow);
                                // Verify the completed slot did not start being overwritten during copy
                                readRamInts(completedSlot * frameBytes + stripOffset, strip, 0, stripLen);
                                int afterHash = 1;
                                for (int p : strip) afterHash = 31 * afterHash + p;
                                if (afterHash == beforeHash) {
                                    lastCopiedMs = now;
                                    sink.publishFrame(outBuf);
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    if (!running) break;
                    SystemClock.sleep(50L);
                }
                SystemClock.sleep(startedNewSlot ? WATCH_AFTER_START_MS : WATCH_MS);
            }
        }

        private void copySlot(int slot, int[] outBuf, int[] srcRow) throws Exception {
            long base = slot * frameBytes;
            if (xs == null) {
                readRamInts(base, outBuf, 0, outBuf.length);
            } else {
                int outIdx = 0;
                for (int r = 0; r < ys.length; r++) {
                    readRamInts(base + (long) ys[r] * srcWidth * 4L, srcRow, 0, srcWidth);
                    for (int c = 0; c < xs.length; c++) {
                        outBuf[outIdx++] = srcRow[xs[c]];
                    }
                }
            }
        }
    }
}
