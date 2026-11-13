/*
 * Adapted in part from KramerTech/gen5-RearDashcam (MIT License, Copyright (c) 2026 KramerTech).
 * Pure-reflection bridge to Android 4.4 KitKat hidden libcore/framework APIs so rearcam.jar
 * compiles cleanly against public android-19 SDK stubs without writing any files to /data.
 */
package fitcamx.rearcam;

import android.os.IBinder;
import android.os.SystemClock;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class PosixBridge {
    private static final int SYSTEM_UID = 1000;
    private static final int SDCARD_R_GID = 1028;

    private static final String TCC_CM_DEVICE = "/dev/tcc_cm_ctrl";
    private static final int IOCTL_KNOCK = 4;
    private static final String HANDOVER_PROPERTY = "persist.sys.earlycam.handover";
    private static final int EARLYCAM_STOP_TRIES = 3;
    private static final long EARLYCAM_SETTLE_MS = 500L;

    private static final String ADMEM_DEVICE = "/dev/admem";

    private static volatile Object sOs;
    private static volatile Method sPeekIntArray;
    private static volatile Method sGetService;
    private static volatile Method sPropGet;
    private static volatile Method sPropSet;

    private PosixBridge() {}

    private static Object getOs() throws Exception {
        Object cached = sOs;
        if (cached != null) return cached;
        Class<?> libcoreCls = Class.forName("libcore.io.Libcore");
        Field osField = libcoreCls.getField("os");
        cached = osField.get(null);
        sOs = cached;
        return cached;
    }

    private static int getOsConstant(String name, int fallback) {
        try {
            Class<?> c = Class.forName("libcore.io.OsConstants");
            return c.getField(name).getInt(null);
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static void dropRootIfNeeded() throws Exception {
        Object os = getOs();
        Class<?> osCls = os.getClass();
        int uid = (Integer) osCls.getMethod("getuid").invoke(os);
        if (uid != 0) return;
        osCls.getMethod("setgid", int.class).invoke(os, SDCARD_R_GID);
        osCls.getMethod("setuid", int.class).invoke(os, SYSTEM_UID);
    }

    public static int getCurrentUid() {
        try {
            Object os = getOs();
            return (Integer) os.getClass().getMethod("getuid").invoke(os);
        } catch (Throwable t) {
            return android.os.Process.myUid();
        }
    }

    /**
     * Queries /dev/tcc_cm_ctrl ioctl(4): returns 1 while the boot Cortex-M early camera is active,
     * 0 once unloaded. Does not mutate hardware state.
     */
    public static int knockEarlyCamera() throws Exception {
        Object os = getOs();
        Class<?> osCls = os.getClass();
        int oRdwr = getOsConstant("O_RDWR", 2);
        Method openMethod = osCls.getMethod("open", String.class, int.class, int.class);
        Method closeMethod = osCls.getMethod("close", FileDescriptor.class);
        Class<?> mutableIntCls = Class.forName("libcore.util.MutableInt");
        Constructor<?> mutableIntCtor = mutableIntCls.getConstructor(int.class);
        Method ioctlIntMethod = osCls.getMethod("ioctlInt", FileDescriptor.class, int.class, mutableIntCls);

        FileDescriptor fd = (FileDescriptor) openMethod.invoke(os, TCC_CM_DEVICE, oRdwr, 0);
        try {
            Object arg = mutableIntCtor.newInstance(0);
            return (Integer) ioctlIntMethod.invoke(os, fd, IOCTL_KNOCK, arg);
        } finally {
            try {
                closeMethod.invoke(os, fd);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Stops the SoC's Cortex-M early camera if still loaded, matching stock RearMode handover.
     */
    public static String stopEarlyCameraIfRunning() throws Exception {
        int knock;
        try {
            knock = knockEarlyCamera();
        } catch (Throwable t) {
            return "earlycam knock unavailable (" + t.getClass().getSimpleName() + ")";
        }
        if (knock == 0) {
            return null;
        }
        if (knock != 1) {
            return "earlycam knock returned " + knock + "; leaving untouched";
        }
        Class<?> rearCamCls = Class.forName("android.hardware.RearCamera");
        setSystemProp(HANDOVER_PROPERTY, "");
        int lastGear = -1;
        Method stopMethod = rearCamCls.getMethod("stopEarlyCamera");
        for (int i = 0; i < EARLYCAM_STOP_TRIES && lastGear == -1; i++) {
            lastGear = (Integer) stopMethod.invoke(null);
        }
        if (lastGear == -1) {
            throw new IllegalStateException("RearCamera.stopEarlyCamera() returned -1");
        }
        int unloaded = (Integer) rearCamCls.getMethod("disableCMControl").invoke(null);
        SystemClock.sleep(EARLYCAM_SETTLE_MS);
        return "earlycam stopped cleanly (lastGear=" + lastGear + ", disableCM=" + unloaded + ")";
    }

    /**
     * Maps the physical rearcamera region read-only via /dev/admem (zero side effects on V4L2/HAL).
     */
    public static long mapReadOnlyAdmem(long physBase, long byteLength) throws Exception {
        Object os = getOs();
        Class<?> osCls = os.getClass();
        int oRdonly = getOsConstant("O_RDONLY", 0);
        int oSync = getOsConstant("O_SYNC", 0x101000);
        int protRead = getOsConstant("PROT_READ", 1);
        int mapShared = getOsConstant("MAP_SHARED", 1);

        Method openMethod = osCls.getMethod("open", String.class, int.class, int.class);
        Method closeMethod = osCls.getMethod("close", FileDescriptor.class);
        Method mmapMethod = osCls.getMethod("mmap",
                long.class, long.class, int.class, int.class, FileDescriptor.class, long.class);

        FileDescriptor fd = (FileDescriptor) openMethod.invoke(os, ADMEM_DEVICE, oRdonly | oSync, 0);
        try {
            return (Long) mmapMethod.invoke(os, 0L, byteLength, protRead, mapShared, fd, physBase);
        } finally {
            try {
                closeMethod.invoke(os, fd);
            } catch (Throwable ignored) {}
        }
    }

    public static void peekIntArray(long address, int[] dst, int dstOffset, int intCount) throws Exception {
        Method m = sPeekIntArray;
        if (m == null) {
            Class<?> memCls = Class.forName("libcore.io.Memory");
            m = memCls.getMethod("peekIntArray", long.class, int[].class, int.class, int.class, boolean.class);
            sPeekIntArray = m;
        }
        m.invoke(null, address, dst, dstOffset, intCount, false);
    }

    public static IBinder getService(String serviceName) {
        try {
            Method m = sGetService;
            if (m == null) {
                Class<?> smCls = Class.forName("android.os.ServiceManager");
                m = smCls.getMethod("getService", String.class);
                sGetService = m;
            }
            return (IBinder) m.invoke(null, serviceName);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Checks whether media.rearcamera currently has an open client ("Device is open") using an
     * in-memory kernel pipe — zero temporary files or /data writes!
     */
    public static boolean isRearCameraOpenInHal() {
        IBinder service = getService("media.rearcamera");
        if (service == null) return false;
        Object os = null;
        Method closeMethod = null;
        FileDescriptor readFd = null;
        FileDescriptor writeFd = null;
        try {
            os = getOs();
            Class<?> osCls = os.getClass();
            closeMethod = osCls.getMethod("close", FileDescriptor.class);
            FileDescriptor[] pipe = (FileDescriptor[]) osCls.getMethod("pipe").invoke(os);
            if (pipe == null || pipe.length < 2) return false;
            readFd = pipe[0];
            writeFd = pipe[1];

            final FileDescriptor dumpFd = writeFd;
            final IBinder svc = service;
            // Dump on current thread; media.rearcamera dump is < 2 KB so it fits inside the 64 KB pipe buffer.
            svc.dump(dumpFd, new String[0]);
            closeMethod.invoke(os, writeFd);
            writeFd = null;

            byte[] buf = new byte[16384];
            int total = 0;
            FileInputStream fis = new FileInputStream(readFd);
            while (total < buf.length) {
                int n = fis.read(buf, total, buf.length - total);
                if (n <= 0) break;
                total += n;
            }
            if (total <= 0) return false;
            String dumpText = new String(buf, 0, total, "UTF-8");
            return dumpText.contains("Device is open");
        } catch (Throwable t) {
            return false;
        } finally {
            if (os != null && closeMethod != null) {
                if (writeFd != null) {
                    try { closeMethod.invoke(os, writeFd); } catch (Throwable ignored) {}
                }
                if (readFd != null) {
                    try { closeMethod.invoke(os, readFd); } catch (Throwable ignored) {}
                }
            }
        }
    }

    public static String getSystemProp(String key, String defValue) {
        try {
            Method m = sPropGet;
            if (m == null) {
                Class<?> spCls = Class.forName("android.os.SystemProperties");
                m = spCls.getMethod("get", String.class, String.class);
                sPropGet = m;
            }
            String v = (String) m.invoke(null, key, defValue);
            return v != null ? v : defValue;
        } catch (Throwable t) {
            return defValue;
        }
    }

    public static boolean setSystemProp(String key, String value) {
        try {
            Method m = sPropSet;
            if (m == null) {
                Class<?> spCls = Class.forName("android.os.SystemProperties");
                m = spCls.getMethod("set", String.class, String.class);
                sPropSet = m;
            }
            m.invoke(null, key, value != null ? value : "");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int getPackageUid(String packageName) {
        try {
            Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
            if (pm == null) return -1;
            return (Integer) Class.forName("android.content.pm.IPackageManager")
                    .getMethod("getPackageUid", String.class, int.class)
                    .invoke(pm, packageName, 0);
        } catch (Throwable t) {
            return -1;
        }
    }
}
