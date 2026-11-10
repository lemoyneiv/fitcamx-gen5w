package fitcamx.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures screenshots strictly to an attached USB flash drive (<usb>/DashcamFootage/).
 * Zero writes to internal /data eMMC flash are ever performed.
 *
 * Primary path: triggers HMG-API's manual root "screenshot" service (/system/bin/screencap -p)
 * so hardware-accelerated SurfaceView video frames are captured alongside UI chrome, waits until
 * the PNG file is atomically finalized on USB, auto-corrects Telechips TCC803x BGRA<->RGBA
 * channel swaps when detected, and only THEN posts the completion Toast.
 *
 * Fallback path (when hmgapid is absent, e.g. non-HMG-API environment): renders the window
 * DecorView into an ARGB_8888 Bitmap and writes PNG atomically (.part -> .png) to USB.
 */
public class ScreenshotHelper {
    private static final AtomicBoolean CAPTURING = new AtomicBoolean(false);
    private static final long HMGAPID_POLL_INTERVAL_MS = 120L;
    private static final long HMGAPID_TIMEOUT_MS = 3800L;

    public interface CompletionListener {
        void onComplete(File savedFile, String errorMessage);
    }

    /**
     * Pure pixel detector for Telechips TCC803x /system/bin/screencap BGRA <-> RGBA channel swap.
     * Examines the FITCAMX title ROI (which is rendered in @color/accent_blue #2196F3, B >> R).
     * Returns true ONLY when strongly orange-shifted pixels (R >> B, R > G) clearly dominate.
     */
    public static boolean shouldSwapRedBlue(int[] argbPixels, int imgWidth, int imgHeight,
                                            int roiLeft, int roiTop, int roiRight, int roiBottom) {
        if (argbPixels == null || imgWidth <= 0 || imgHeight <= 0) return false;
        if (argbPixels.length < imgWidth * imgHeight) return false;

        int left = Math.max(0, Math.min(imgWidth, roiLeft));
        int right = Math.max(0, Math.min(imgWidth, roiRight));
        int top = Math.max(0, Math.min(imgHeight, roiTop));
        int bottom = Math.max(0, Math.min(imgHeight, roiBottom));
        if (right <= left || bottom <= top) return false;

        int swappedVotes = 0;
        int normalVotes = 0;

        for (int y = top; y < bottom; y++) {
            int rowBase = y * imgWidth;
            for (int x = left; x < right; x++) {
                int p = argbPixels[rowBase + x];
                int a = (p >>> 24) & 0xFF;
                if (a < 180) continue;
                int r = (p >>> 16) & 0xFF;
                int g = (p >>> 8) & 0xFF;
                int b = p & 0xFF;

                if (r - b >= 35 && r > g) {
                    swappedVotes++;
                } else if (b - r >= 35 && b > g) {
                    normalVotes++;
                }
            }
        }
        return swappedVotes >= 12 && swappedVotes > (normalVotes * 3);
    }

    /**
     * Swaps Red (bits 16..23) and Blue (bits 0..7) channels in-place across ARGB_8888 pixels.
     */
    public static void swapRedBlueInPlace(int[] argbPixels) {
        if (argbPixels == null) return;
        for (int i = 0; i < argbPixels.length; i++) {
            int p = argbPixels[i];
            argbPixels[i] = (p & 0xFF00FF00)
                    | ((p & 0x00FF0000) >>> 16)
                    | ((p & 0x000000FF) << 16);
        }
    }

    /**
     * Initiates a USB-only screenshot capture and displays a Toast AFTER the PNG has been written.
     */
    public static void captureToUsb(final Activity activity, final View blueTitleAnchor,
                                    final CompletionListener listener) {
        if (activity == null || activity.isFinishing()) return;
        if (!CAPTURING.compareAndSet(false, true)) {
            return;
        }

        final Handler uiHandler = new Handler(Looper.getMainLooper());

        // Record screen-space ROI of the blue "FITCAMX" title view on the UI thread
        final int roiLeft;
        final int roiTop;
        final int roiRight;
        final int roiBottom;
        if (blueTitleAnchor != null && blueTitleAnchor.getWidth() > 0 && blueTitleAnchor.getHeight() > 0) {
            int[] loc = new int[2];
            blueTitleAnchor.getLocationOnScreen(loc);
            roiLeft = loc[0];
            roiTop = loc[1];
            roiRight = loc[0] + blueTitleAnchor.getWidth();
            roiBottom = loc[1] + blueTitleAnchor.getHeight();
        } else {
            roiLeft = 0;
            roiTop = 0;
            roiRight = 0;
            roiBottom = 0;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                File savedFile = null;
                String errMsg = null;
                try {
                    File usbFolder = UsbDownloader.getUsbDestinationFolder(true);
                    if (usbFolder == null || !usbFolder.exists() || !usbFolder.canWrite()) {
                        errMsg = "No USB drive attached — insert USB drive to save screenshot";
                        DebugLogger.log("SCREENSHOT", errMsg);
                        return;
                    }

                    // Snapshot existing screenshot filenames before triggering capture
                    Set<String> existingNames = new HashSet<String>();
                    File[] beforeList = usbFolder.listFiles();
                    if (beforeList != null) {
                        for (File f : beforeList) {
                            if (f != null && f.isFile() && isScreenshotPng(f.getName())) {
                                existingNames.add(f.getName());
                            }
                        }
                    }

                    long triggerWallMs = System.currentTimeMillis();
                    boolean hmgapidStarted = HmgapidClient.requestScreenshot();
                    if (hmgapidStarted) {
                        savedFile = waitForHmgapidScreenshot(usbFolder, existingNames, triggerWallMs);
                        if (savedFile != null) {
                            normalizeScreenshotColorsIfNeeded(savedFile, roiLeft, roiTop, roiRight, roiBottom);
                            DebugLogger.log("SCREENSHOT", "Saved root framebuffer screenshot to USB: "
                                    + savedFile.getAbsolutePath() + " (" + savedFile.length() + " bytes)");
                        }
                    }

                    // Fallback to Java DecorView capture on USB if hmgapid is unavailable
                    if (savedFile == null) {
                        savedFile = captureDecorViewToUsb(activity, uiHandler, usbFolder);
                        if (savedFile != null) {
                            DebugLogger.log("SCREENSHOT", "Saved view screenshot to USB: "
                                    + savedFile.getAbsolutePath() + " (" + savedFile.length() + " bytes)");
                        } else {
                            errMsg = "Failed to write screenshot to USB drive";
                            DebugLogger.log("SCREENSHOT", errMsg);
                        }
                    }
                } catch (Throwable t) {
                    errMsg = "Screenshot error: " + t.getMessage();
                    DebugLogger.log("SCREENSHOT", errMsg);
                } finally {
                    CAPTURING.set(false);
                    final File finalSaved = savedFile;
                    final String finalErr = errMsg;
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!activity.isFinishing()) {
                                if (finalSaved != null) {
                                    Toast.makeText(activity,
                                            "Screenshot saved to USB: " + finalSaved.getName(),
                                            Toast.LENGTH_SHORT).show();
                                } else if (finalErr != null) {
                                    Toast.makeText(activity, finalErr, Toast.LENGTH_SHORT).show();
                                }
                            }
                            if (listener != null) {
                                listener.onComplete(finalSaved, finalErr);
                            }
                        }
                    });
                }
            }
        }, "FitcamxScreenshot").start();
    }

    private static boolean isScreenshotPng(String name) {
        return name != null
                && name.startsWith("fitcamx_screenshot_")
                && name.endsWith(".png")
                && !name.endsWith(".part");
    }

    private static File waitForHmgapidScreenshot(File usbFolder, Set<String> existingNames, long triggerWallMs) {
        long deadline = SystemClock.elapsedRealtime() + HMGAPID_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(HMGAPID_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }

            File[] files = usbFolder.listFiles();
            if (files == null) continue;

            boolean partInFlight = false;
            File candidate = null;
            for (File f : files) {
                if (f == null) continue;
                String name = f.getName();
                if (name.startsWith(".fitcamx_shot_") && name.endsWith(".part")) {
                    partInFlight = true;
                } else if (f.isFile() && isScreenshotPng(name)) {
                    boolean isNewName = !existingNames.contains(name);
                    boolean isFreshMod = f.lastModified() >= (triggerWallMs - 1500L);
                    if (isNewName && f.length() > 0L) {
                        if (candidate == null || f.lastModified() >= candidate.lastModified()) {
                            candidate = f;
                        }
                    } else if (candidate == null && isFreshMod && !existingNames.contains(name) && f.length() > 0L) {
                        candidate = f;
                    }
                }
            }

            if (candidate != null && !partInFlight && candidate.length() > 0L) {
                return candidate;
            }
        }
        return null;
    }

    private static void normalizeScreenshotColorsIfNeeded(File pngFile,
                                                          int roiLeft, int roiTop, int roiRight, int roiBottom) {
        if (pngFile == null || !pngFile.exists() || roiRight <= roiLeft || roiBottom <= roiTop) {
            return;
        }
        Bitmap bmp = null;
        File tempFix = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inMutable = true;
            bmp = BitmapFactory.decodeFile(pngFile.getAbsolutePath(), opts);
            if (bmp == null) return;

            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (w <= 0 || h <= 0) return;

            int[] pixels = new int[w * h];
            bmp.getPixels(pixels, 0, w, 0, 0, w, h);
            if (!shouldSwapRedBlue(pixels, w, h, roiLeft, roiTop, roiRight, roiBottom)) {
                return;
            }

            swapRedBlueInPlace(pixels);
            if (!bmp.isMutable()) {
                Bitmap mutable = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.recycle();
                bmp = mutable;
            }
            bmp.setPixels(pixels, 0, w, 0, 0, w, h);

            tempFix = new File(pngFile.getParentFile(), "." + pngFile.getName() + ".fix.part");
            FileOutputStream fos = new FileOutputStream(tempFix);
            boolean ok;
            try {
                ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Exception ignored) {}
            } finally {
                try {
                    fos.close();
                } catch (Exception ignored) {}
            }

            if (ok && tempFix.exists() && tempFix.length() > 0L) {
                if (!tempFix.renameTo(pngFile)) {
                    pngFile.delete();
                    tempFix.renameTo(pngFile);
                }
                DebugLogger.log("SCREENSHOT", "Corrected TCC803x BGRA->RGBA channel order for " + pngFile.getName());
            } else {
                tempFix.delete();
            }
        } catch (Throwable t) {
            DebugLogger.log("SCREENSHOT", "Color check notice: " + t.getMessage());
            if (tempFix != null && tempFix.exists()) {
                tempFix.delete();
            }
        } finally {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
            }
        }
    }

    private static File captureDecorViewToUsb(final Activity activity, Handler uiHandler, File usbFolder) {
        final AtomicReference<Bitmap> bitmapRef = new AtomicReference<Bitmap>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        uiHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!activity.isFinishing() && activity.getWindow() != null) {
                        View decor = activity.getWindow().getDecorView();
                        int w = decor.getWidth();
                        int h = decor.getHeight();
                        if (w > 0 && h > 0) {
                            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            Canvas canvas = new Canvas(bmp);
                            decor.draw(canvas);
                            bitmapRef.set(bmp);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            }
        });

        try {
            latch.await(2000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        Bitmap bmp = bitmapRef.get();
        if (bmp == null) return null;

        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File outFile = new File(usbFolder, "fitcamx_screenshot_" + ts + ".png");
        int suffix = 1;
        while (outFile.exists()) {
            outFile = new File(usbFolder, "fitcamx_screenshot_" + ts + "_" + suffix + ".png");
            suffix++;
        }
        File partFile = new File(usbFolder, ".fitcamx_shot_" + ts + "_ui.part");

        try {
            FileOutputStream fos = new FileOutputStream(partFile);
            boolean ok;
            try {
                ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (Exception ignored) {}
            } finally {
                try {
                    fos.close();
                } catch (Exception ignored) {}
            }

            if (ok && partFile.exists() && partFile.length() > 0L) {
                if (partFile.renameTo(outFile)) {
                    return outFile;
                }
            }
            partFile.delete();
            return null;
        } catch (Throwable t) {
            partFile.delete();
            return null;
        } finally {
            if (!bmp.isRecycled()) {
                bmp.recycle();
            }
        }
    }
}
