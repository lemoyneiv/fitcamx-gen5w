/*
 * Adapted in part from KramerTech/gen5-RearDashcam (MIT License, Copyright (c) 2026 KramerTech).
 * Vehicle service & RearCamera HAL wrapper for Gen5W analog backup camera direct-display access.
 */
package fitcamx.rearcam;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;

public final class CarBridge {
    public static final int GEAR_REVERSE = 1;
    public static final int CAMERA_TYPE_ANALOG = 0;
    public static final int GUIDE_LINES_OFF = 1;
    public static final int GUIDE_LINES_ON = 2;

    private static final String REAR_CAM_CLASS = "android.hardware.RearCamera";
    private static final String PARAMETERS_FMT =
            "crop-size=0x0;preview-overlay=%dx0;preview-size=%dx%d;crop-position=0,0";

    private static final String BCM_SERVICE = "com.hkmc.micom.bcm";
    private static final String BCM_IFACE = "com.hkmc.bcm.IBCMService";
    private static final int BCM_GET_IGN_STATUS = 0x11;

    private static final String PGS_SERVICE = "com.hkmc.micom.pgs";
    private static final String PGS_IFACE = "com.hkmc.pgs.IPgsService";
    private static final int PGS_REQUEST_GUIDE_LINE_STATE = 0x21;
    private static final int PGS_GET_RVM_GUIDE_LINE_STATE = 0x3b;

    /**
     * Strictly volatile RAM-only crash-recovery markers used so a restarted daemon can restore
     * hardware parking guide lines even if a prior process was SIGKILLed mid-stream (e.g. during
     * a live USB mod reinstall), with zero writes to internal /data eMMC flash:
     * 1) /mnt/shm/.fitcamx_pgs_hidden (verified against /proc/mounts to be tmpfs in RAM, uid=1000)
     * 2) debug.fitcamx.pgs_hidden (non-persistent RAM-only debug.* property permitted for uid=1000)
     */
    public static final String RAM_TMPFS_DIR = "/mnt/shm";
    public static final String RAM_PGS_MARKER_NAME = ".fitcamx_pgs_hidden";
    private static final String RAM_PROP_PGS_HIDDEN = "debug.fitcamx.pgs_hidden";

    private static final Object sPgsLock = new Object();
    private static volatile boolean sPgsHiddenInSession = false;
    private static Boolean sShmVerifiedTmpfs = null;
    private static int sPgsGeneration = 0;

    private CarBridge() {}

    public static boolean isTmpfsMountPoint(String mountsText, String mountPoint) {
        if (mountsText == null || mountPoint == null || mountPoint.isEmpty()) {
            return false;
        }
        String[] lines = mountsText.split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            if (parts.length >= 3 && mountPoint.equals(parts[1]) && "tmpfs".equals(parts[2])) {
                return true;
            }
        }
        return false;
    }

    public static boolean writeRamMarkerFile(File dir, String mountsText, String markerName, boolean set) {
        if (dir == null || markerName == null || markerName.isEmpty()) return false;
        if (!isTmpfsMountPoint(mountsText, dir.getAbsolutePath()) || !dir.isDirectory()) {
            return false;
        }
        File marker = new File(dir, markerName);
        if (set) {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(marker, false);
                fos.write('1');
                return true;
            } catch (Throwable t) {
                return false;
            } finally {
                if (fos != null) {
                    try { fos.close(); } catch (IOException ignored) {}
                }
            }
        } else {
            return !marker.exists() || marker.delete();
        }
    }

    public static boolean isRamMarkerFilePresent(File dir, String mountsText, String markerName) {
        if (dir == null || markerName == null || markerName.isEmpty()) return false;
        if (!isTmpfsMountPoint(mountsText, dir.getAbsolutePath())) {
            return false;
        }
        return new File(dir, markerName).isFile();
    }

    private static boolean isShmTmpfs() {
        Boolean cached = sShmVerifiedTmpfs;
        if (cached != null) return cached.booleanValue();
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Throwable t) {
            sShmVerifiedTmpfs = Boolean.FALSE;
            return false;
        } finally {
            if (br != null) {
                try { br.close(); } catch (IOException ignored) {}
            }
        }
        boolean ok = isTmpfsMountPoint(sb.toString(), RAM_TMPFS_DIR) && new File(RAM_TMPFS_DIR).isDirectory();
        sShmVerifiedTmpfs = Boolean.valueOf(ok);
        return ok;
    }

    private static boolean isRamPgsMarkerSet() {
        if ("1".equals(PosixBridge.getSystemProp(RAM_PROP_PGS_HIDDEN, "0"))) {
            return true;
        }
        return isShmTmpfs() && new File(RAM_TMPFS_DIR, RAM_PGS_MARKER_NAME).isFile();
    }

    private static void setRamPgsMarker(boolean hidden) {
        PosixBridge.setSystemProp(RAM_PROP_PGS_HIDDEN, hidden ? "1" : "0");
        if (!isShmTmpfs()) return;
        File marker = new File(RAM_TMPFS_DIR, RAM_PGS_MARKER_NAME);
        if (hidden) {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(marker, false);
                fos.write('1');
            } catch (Throwable ignored) {
            } finally {
                if (fos != null) {
                    try { fos.close(); } catch (IOException ignored) {}
                }
            }
        } else {
            try {
                if (marker.exists()) {
                    marker.delete();
                }
            } catch (Throwable ignored) {}
        }
    }

    public static int getCameraType() {
        try {
            Class<?> cls = Class.forName(REAR_CAM_CLASS);
            return (Integer) invokeMethod(cls, null, "getCameraType", new Class<?>[0]);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int getGearStatus() {
        try {
            Class<?> cls = Class.forName(REAR_CAM_CLASS);
            return (Integer) invokeMethod(cls, null, "getGearStatus", new Class<?>[0]);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean isIgnitionOn() {
        Integer val = transactInt(BCM_SERVICE, BCM_IFACE, BCM_GET_IGN_STATUS, null);
        return val == null || val != 0;
    }

    public static Integer getGuideLinesState() {
        return transactInt(PGS_SERVICE, PGS_IFACE, PGS_GET_RVM_GUIDE_LINE_STATE, null);
    }

    public static boolean setGuideLinesState(int state) {
        return transactInt(PGS_SERVICE, PGS_IFACE, PGS_REQUEST_GUIDE_LINE_STATE, state) != null;
    }

    /**
     * Hides hardware-drawn parking guide lines if the driver currently has them enabled,
     * recording a RAM-only marker so they are always restored on release or restart.
     */
    public static boolean hideGuideLinesIfNeeded() {
        synchronized (sPgsLock) {
            sPgsGeneration++;
            boolean alreadyMarked = sPgsHiddenInSession || isRamPgsMarkerSet();
            if (!alreadyMarked) {
                Integer cur = getGuideLinesState();
                if (cur == null || cur != GUIDE_LINES_ON) {
                    return false;
                }
                setRamPgsMarker(true);
            }
            sPgsHiddenInSession = true;
            if (setGuideLinesState(GUIDE_LINES_OFF)) {
                return true;
            }
            sPgsHiddenInSession = false;
            if (!alreadyMarked) {
                setRamPgsMarker(false);
            }
            return false;
        }
    }

    /**
     * Immediately restores parking guide lines if we hid them (or if a prior session left the
     * RAM marker set), then verifies restoration asynchronously against dropped PGS requests.
     */
    public static void restoreGuideLines(boolean asyncVerify) {
        final int gen;
        synchronized (sPgsLock) {
            boolean marked = sPgsHiddenInSession || isRamPgsMarkerSet();
            sPgsHiddenInSession = false;
            if (!marked) return;
            gen = ++sPgsGeneration;
            setGuideLinesState(GUIDE_LINES_ON);
        }
        if (!asyncVerify) {
            Integer cur = getGuideLinesState();
            if (cur != null && cur == GUIDE_LINES_ON) {
                setRamPgsMarker(false);
            }
            return;
        }
        Thread verifyThread = new Thread("fitcamx-pgs-restore") {
            @Override
            public void run() {
                for (int i = 0; i < 6; i++) {
                    SystemClock.sleep(450L);
                    Integer state = getGuideLinesState();
                    synchronized (sPgsLock) {
                        if (sPgsGeneration != gen) return;
                        if (state != null && state == GUIDE_LINES_ON) {
                            setRamPgsMarker(false);
                            return;
                        }
                        setGuideLinesState(GUIDE_LINES_ON);
                    }
                }
            }
        };
        verifyThread.setDaemon(true);
        verifyThread.start();
    }

    public static int findStockBackCameraId() {
        try {
            Class<?> cls = Class.forName(REAR_CAM_CLASS);
            int count = (Integer) invokeMethod(cls, null, "getNumberOfCameras", new Class<?>[0]);
            Class<?> infoCls = Class.forName(REAR_CAM_CLASS + "$RearCameraInfo");
            Field facingField = infoCls.getField("facing");
            for (int id = 0; id < count; id++) {
                Object info = infoCls.newInstance();
                int rc = (Integer) invokeMethod(cls, null, "getCameraInfo", new Class<?>[] { int.class, infoCls }, id, info);
                if (rc == 0 && facingField.getInt(info) == 0) {
                    return (id == 0 || id == 3) ? id : 0;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static Integer transactInt(String serviceName, String iface, int code, Integer optArg) {
        IBinder binder = PosixBridge.getService(serviceName);
        if (binder == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(iface);
            if (optArg != null) {
                data.writeInt(optArg);
            }
            if (!binder.transact(code, data, reply, 0)) {
                return null;
            }
            reply.readException();
            return reply.dataAvail() >= 4 ? reply.readInt() : 0;
        } catch (Throwable t) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Direct-display RearCamera handle (NEVER attaches a preview Surface or Texture).
     */
    public static final class RearCamSession {
        private final Class<?> camClass;
        private final Object camInstance;
        private boolean previewing;
        private LocalSocket optionalOverlayLease;

        private RearCamSession(Class<?> camClass, Object camInstance) {
            this.camClass = camClass;
            this.camInstance = camInstance;
        }

        public static RearCamSession open(int cameraId, final Runnable onHalError) throws Exception {
            Class<?> cls = Class.forName(REAR_CAM_CLASS);
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object inst = ctor.newInstance();

            setField(cls, inst, "mCameraId", cameraId);
            Constructor<?> handlerCtor = Class.forName(REAR_CAM_CLASS + "$EventHandler_")
                    .getDeclaredConstructor(cls, cls, Looper.class);
            handlerCtor.setAccessible(true);
            setField(cls, inst, "mEventHandler", handlerCtor.newInstance(inst, inst, Looper.myLooper()));

            invokeMethod(cls, inst, "native_setup",
                    new Class<?>[] { Object.class, int.class, String.class },
                    new WeakReference<Object>(inst), cameraId, "android");
            setField(cls, inst, "initialized", true);

            final RearCamSession session = new RearCamSession(cls, inst);
            try {
                Class<?> errIface = Class.forName(REAR_CAM_CLASS + "$ErrorCallback");
                Object proxy = Proxy.newProxyInstance(errIface.getClassLoader(), new Class<?>[] { errIface },
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object self, Method m, Object[] args) {
                                String name = m.getName();
                                if ("onError".equals(name)) {
                                    if (onHalError != null) onHalError.run();
                                    return null;
                                }
                                if ("equals".equals(name)) return self == (args != null && args.length > 0 ? args[0] : null);
                                if ("hashCode".equals(name)) return System.identityHashCode(self);
                                return "toString".equals(name) ? "FitcamxRearCamErrorCallback" : null;
                            }
                        });
                invokeMethod(cls, inst, "setErrorCallback", new Class<?>[] { errIface }, proxy);
            } catch (Throwable ignored) {}
            return session;
        }

        public void startDirectDisplay(int width, int height, int overlayX) throws Exception {
            int safeX = Math.max(0, overlayX);
            String params = String.format(Locale.US, PARAMETERS_FMT, safeX, width, height);
            invokeMethod(camClass, camInstance, "native_setParameters", new Class<?>[] { String.class }, params);
            previewing = true;
            invokeMethod(camClass, camInstance, "startPreview", new Class<?>[0]);
            tryOptionalOverlayLease(width, height);
        }

        public void updateOverlayPosition(int width, int height, int overlayX) {
            if (!previewing) return;
            try {
                int safeX = Math.max(0, overlayX);
                String params = String.format(Locale.US, PARAMETERS_FMT, safeX, width, height);
                invokeMethod(camClass, camInstance, "native_setParameters", new Class<?>[] { String.class }, params);
            } catch (Throwable ignored) {}
        }

        /**
         * Opportunistically leases RDMA1 alpha transparency if KramerTech's dashcam-overlay boot
         * helper happens to be running on this unit (harmless no-op if not installed).
         */
        private void tryOptionalOverlayLease(int width, int height) {
            LocalSocket sock = new LocalSocket();
            try {
                sock.connect(new LocalSocketAddress("dev.skramer.dashcam.overlay"));
                sock.setSoTimeout(250);
                sock.getOutputStream().write(new byte[] {
                        'H', (byte) (width >> 8), (byte) width, (byte) (height >> 8), (byte) height
                });
                if (sock.getInputStream().read() == 'H') {
                    optionalOverlayLease = sock;
                    return;
                }
            } catch (Throwable ignored) {}
            try { sock.close(); } catch (IOException ignored) {}
        }

        private void releaseOptionalOverlayLease() {
            LocalSocket sock = optionalOverlayLease;
            optionalOverlayLease = null;
            if (sock == null) return;
            try {
                sock.getOutputStream().write('S');
                sock.getInputStream().read();
            } catch (Throwable ignored) {
            } finally {
                try { sock.close(); } catch (IOException ignored) {}
            }
        }

        public void release() {
            releaseOptionalOverlayLease();
            if (previewing) {
                previewing = false;
                try {
                    invokeMethod(camClass, camInstance, "stopPreview", new Class<?>[0]);
                } catch (Throwable ignored) {}
            }
            try {
                invokeMethod(camClass, camInstance, "release", new Class<?>[0]);
            } catch (Throwable ignored) {}
        }
    }

    private static void setField(Class<?> cls, Object target, String fieldName, Object value) throws Exception {
        Field f = cls.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object invokeMethod(Class<?> cls, Object target, String methodName,
                                       Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = cls.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw ite;
        }
    }
}
