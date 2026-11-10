package fitcamx.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UsbDownloader {
    private static final String TAG = "UsbDownloader";

    public static final String FOLDER_NAME = "DashcamFootage";
    public static final String[] USB_MOUNT_POINTS = {
        "/storage/usb0",
        "/storage/usb1",
        "/mnt/usb0",
        "/mnt/usb1",
        "/storage/ext_sd"
    };

    private final Handler mainHandler;
    private final ExecutorService executor;
    private volatile boolean isCancelled = false;
    private volatile boolean isDownloading = false;

    public interface DownloadCallback {
        void onProgress(int percent, double speedMbPerSec, long downloadedBytes, long totalBytes);
        void onComplete(File destinationFile);
        void onError(Exception error);
    }

    public UsbDownloader() {
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Locate the first writable USB drive folder
     */
    public static File getUsbDestinationFolder() {
        // First check dynamically discovered mounts
        List<File> discovered = DebugLogger.findUsbMountDirectories();
        for (File dir : discovered) {
            if (dir.exists() && dir.canWrite()) {
                File target = new File(dir, FOLDER_NAME);
                if (!target.exists()) {
                    target.mkdirs();
                }
                if (target.exists() && target.canWrite()) {
                    return target;
                }
                // Fallback to base dir if subfolder creation failed
                return dir;
            }
        }

        // Second check static mount points
        for (String mount : USB_MOUNT_POINTS) {
            File dir = new File(mount);
            if (dir.exists() && dir.canWrite()) {
                File target = new File(dir, FOLDER_NAME);
                if (!target.exists()) {
                    target.mkdirs();
                }
                if (target.exists() && target.canWrite()) {
                    return target;
                }
                return dir;
            }
        }
        return null;
    }

    public static boolean isUsbDriveAttached() {
        return getUsbDestinationFolder() != null;
    }

    public void cancelDownload() {
        isCancelled = true;
    }

    public boolean isDownloading() {
        return isDownloading;
    }

    public void shutdown() {
        isCancelled = true;
        executor.shutdownNow();
    }

    public void downloadClip(final ClipItem clip, final String cameraIp, final DownloadCallback callback) {
        downloadClip(clip, cameraIp, false, 80, callback);
    }

    public void downloadClip(final ClipItem clip, final String cameraIp, final boolean isJieLi, final int port, final DownloadCallback callback) {
        downloadClipToFolder(clip, cameraIp, isJieLi, port, null, callback);
    }

    public void downloadClipToFolder(final ClipItem clip, final String cameraIp, final boolean isJieLi, final int port, final File explicitDestFolder, final DownloadCallback callback) {
        if (executor.isShutdown()) return;
        isCancelled = false;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                isDownloading = true;
                File destFolder = (explicitDestFolder != null) ? explicitDestFolder : getUsbDestinationFolder();
                if (destFolder == null) {
                    isDownloading = false;
                    Exception e = new Exception("No writable USB drive found across checked mount points");
                    DebugLogger.log("USB_DL", "Download aborted: " + e.getMessage());
                    postError(callback, e);
                    return;
                }

                File outFile = new File(destFolder, clip.filename);
                File partFile = new File(destFolder, clip.filename + ".part");
                String httpUrl = clip.getHttpUrl(cameraIp, isJieLi, port);
                DebugLogger.log("USB_DL", "Starting atomic download: " + httpUrl + " -> " + outFile.getAbsolutePath());
                HttpURLConnection conn = null;
                InputStream in = null;
                FileOutputStream out = null;

                try {
                    if (partFile.exists()) {
                        partFile.delete();
                    }

                    URL url = new URL(httpUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Connection", "close");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(12000);

                    int code = conn.getResponseCode();
                    DebugLogger.log("USB_DL", "HTTP " + code + " for " + httpUrl);
                    if (code != 200 && code != 206) {
                        throw new Exception("HTTP server error code: " + code);
                    }

                    long totalBytes = conn.getContentLength();
                    if (totalBytes <= 0) {
                        totalBytes = clip.sizeBytes;
                    }

                    in = conn.getInputStream();
                    out = new FileOutputStream(partFile);

                    byte[] buffer = new byte[64 * 1024];
                    long downloaded = 0;
                    long startTime = System.currentTimeMillis();
                    long lastUpdateTime = startTime;

                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (isCancelled) {
                            throw new Exception("Download cancelled by user");
                        }

                        out.write(buffer, 0, read);
                        downloaded += read;

                        long now = System.currentTimeMillis();
                        if (now - lastUpdateTime >= 250 || (totalBytes > 0 && downloaded == totalBytes)) {
                            double elapsedSec = Math.max(0.001, (now - startTime) / 1000.0);
                            double speedMb = (downloaded / (1024.0 * 1024.0)) / elapsedSec;
                            int percent = (totalBytes > 0) ? (int) Math.min(100L, (downloaded * 100L) / totalBytes) : -1;

                            postProgress(callback, percent, speedMb, downloaded, totalBytes);
                            lastUpdateTime = now;
                        }
                    }

                    if (downloaded <= 0) {
                        throw new Exception("Received empty response from camera");
                    }
                    if (totalBytes > 0 && downloaded < totalBytes) {
                        throw new Exception("Incomplete download (" + downloaded + " / " + totalBytes + " bytes)");
                    }

                    out.flush();
                    try {
                        out.getFD().sync();
                    } catch (Exception ignored) {}
                    out.close();
                    out = null;
                    in.close();
                    in = null;
                    conn.disconnect();
                    conn = null;

                    if (outFile.exists() && !outFile.delete()) {
                        DebugLogger.log("USB_DL", "Warning: could not remove pre-existing file before rename: " + outFile.getAbsolutePath());
                    }
                    if (!partFile.renameTo(outFile)) {
                        throw new Exception("Failed committing temporary file to USB destination");
                    }

                    DebugLogger.log("USB_DL", "Download SUCCESS: " + outFile.getAbsolutePath() + " (" + outFile.length() + " bytes)");
                    postComplete(callback, outFile);

                } catch (Exception e) {
                    DebugLogger.log("USB_DL", "Download FAILED: " + clip.filename + " -> " + e.getMessage(), e);
                    try {
                        if (out != null) out.close();
                    } catch (Exception ignored) {}
                    try {
                        if (in != null) in.close();
                    } catch (Exception ignored) {}
                    try {
                        if (conn != null) conn.disconnect();
                    } catch (Exception ignored) {}

                    if (partFile.exists()) {
                        partFile.delete();
                    }

                    postError(callback, e);
                } finally {
                    isDownloading = false;
                }
            }
        });
    }

    private void postProgress(final DownloadCallback cb, final int percent, final double speedMb, final long current, final long total) {
        if (cb != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    cb.onProgress(percent, speedMb, current, total);
                }
            });
        }
    }

    private void postComplete(final DownloadCallback cb, final File destination) {
        if (cb != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    cb.onComplete(destination);
                }
            });
        }
    }

    private void postError(final DownloadCallback cb, final Exception err) {
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
