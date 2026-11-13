package fitcamx.app;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class UsbDownloader {
    private static final String TAG = "UsbDownloader";

    public static final String FOLDER_NAME = "DashcamFootage";
    public static final String[] USB_MOUNT_POINTS = {
        "/storage/usb0",
        "/storage/usb1",
        "/storage/usb2",
        "/mnt/media_rw/usb0",
        "/mnt/media_rw/usb1",
        "/mnt/usb0",
        "/mnt/usb1",
        "/storage/ext_sd"
    };

    private static final long MIN_HEADROOM_BYTES = 4L * 1024L * 1024L;

    private final Handler mainHandler;
    private final ExecutorService executor;
    private final AtomicBoolean activeTransfer = new AtomicBoolean(false);
    private volatile boolean isCancelled = false;
    private volatile HttpURLConnection activeConnection = null;
    private volatile InputStream activeInputStream = null;

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
     * Locate the first writable USB drive folder (creating DashcamFootage if createIfMissing is true).
     * Pass createIfMissing=false for passive status checks to avoid synchronous FAT32 directory writes.
     */
    public static File getUsbDestinationFolder(boolean createIfMissing) {
        List<File> discovered = DebugLogger.findUsbMountDirectories();
        for (File dir : discovered) {
            if (dir.exists() && dir.canWrite()) {
                File target = new File(dir, FOLDER_NAME);
                if (!target.exists() && createIfMissing) {
                    target.mkdirs();
                }
                if (target.exists() && target.canWrite()) {
                    return target;
                }
                return dir;
            }
        }

        for (String mount : USB_MOUNT_POINTS) {
            File dir = new File(mount);
            if (dir.exists() && dir.canWrite()) {
                File target = new File(dir, FOLDER_NAME);
                if (!target.exists() && createIfMissing) {
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

    public static File getUsbDestinationFolder() {
        return getUsbDestinationFolder(true);
    }

    public static boolean isUsbDriveAttached() {
        return getUsbDestinationFolder(false) != null;
    }

    public void cancelDownload() {
        isCancelled = true;
        final InputStream in = activeInputStream;
        final HttpURLConnection conn = activeConnection;
        if (in != null || conn != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (in != null) in.close();
                    } catch (Exception ignored) {}
                    try {
                        if (conn != null) conn.disconnect();
                    } catch (Exception ignored) {}
                }
            }, "FitcamxUsbCancel").start();
        }
    }

    public boolean isDownloading() {
        return activeTransfer.get();
    }

    public void shutdown() {
        cancelDownload();
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
        if (clip == null) {
            postError(callback, new Exception("No media item selected"));
            return;
        }
        if (!activeTransfer.compareAndSet(false, true)) {
            postError(callback, new Exception("Another USB transfer is already in progress"));
            return;
        }

        isCancelled = false;
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    File destFolder = (explicitDestFolder != null) ? explicitDestFolder : getUsbDestinationFolder(true);
                    if (destFolder == null) {
                        activeTransfer.set(false);
                        Exception e = new Exception("No writable USB drive found across checked mount points");
                        DebugLogger.log("USB_DL", "Download aborted: " + e.getMessage());
                        postError(callback, e);
                        return;
                    }

                    long usableSpace = destFolder.getUsableSpace();
                    if (clip.sizeBytes > 0 && usableSpace > 0 && usableSpace < clip.sizeBytes + MIN_HEADROOM_BYTES) {
                        activeTransfer.set(false);
                        long needMb = (clip.sizeBytes + MIN_HEADROOM_BYTES) / (1024L * 1024L);
                        long freeMb = usableSpace / (1024L * 1024L);
                        Exception e = new Exception("Insufficient USB space (" + freeMb + " MB free, need " + needMb + " MB)");
                        DebugLogger.log("USB_DL", "Download aborted: " + e.getMessage());
                        postError(callback, e);
                        return;
                    }

                    File outFile = new File(destFolder, clip.filename);
                    File partFile = new File(destFolder, clip.filename + ".part");
                    boolean isLocalCopy = (clip.localFilePath != null && !clip.localFilePath.isEmpty());
                    String httpUrl = isLocalCopy ? null : clip.getHttpUrl(cameraIp, isJieLi, port);
                    DebugLogger.log("USB_DL", "Starting atomic transfer: "
                            + (isLocalCopy ? clip.localFilePath : httpUrl) + " -> " + outFile.getAbsolutePath());
                    HttpURLConnection conn = null;
                    InputStream in = null;
                    FileOutputStream out = null;

                    try {
                        if (partFile.exists()) {
                            partFile.delete();
                        }

                        long totalBytes;
                        if (isLocalCopy) {
                            File srcFile = new File(clip.localFilePath);
                            if (!srcFile.isFile() || !srcFile.canRead()) {
                                throw new Exception("Source USB clip not found: " + clip.localFilePath);
                            }
                            if (srcFile.getCanonicalPath().equals(outFile.getCanonicalPath())) {
                                postComplete(callback, outFile);
                                return;
                            }
                            totalBytes = srcFile.length();
                            if (totalBytes > 0 && usableSpace > 0 && usableSpace < totalBytes + MIN_HEADROOM_BYTES) {
                                throw new Exception("Insufficient USB space for " + (totalBytes / (1024L * 1024L)) + " MB file");
                            }
                            in = new java.io.FileInputStream(srcFile);
                        } else {
                            URL url = new URL(httpUrl);
                            conn = (HttpURLConnection) url.openConnection();
                            activeConnection = conn;
                            conn.setRequestMethod("GET");
                            conn.setRequestProperty("Connection", "close");
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(12000);

                            int code = conn.getResponseCode();
                            DebugLogger.log("USB_DL", "HTTP " + code + " for " + httpUrl);
                            if (code != 200 && code != 206) {
                                throw new Exception("HTTP server error code: " + code);
                            }

                            totalBytes = conn.getContentLength();
                            if (totalBytes <= 0) {
                                totalBytes = clip.sizeBytes;
                            }
                            if (totalBytes > 0 && usableSpace > 0 && usableSpace < totalBytes + MIN_HEADROOM_BYTES) {
                                throw new Exception("Insufficient USB space for " + (totalBytes / (1024L * 1024L)) + " MB file");
                            }

                            in = conn.getInputStream();
                        }
                        activeInputStream = in;
                        out = new FileOutputStream(partFile);

                        byte[] buffer = new byte[64 * 1024];
                        long downloaded = 0;
                        long startTime = System.currentTimeMillis();
                        long lastUpdateTime = startTime;

                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            if (isCancelled) {
                                throw new Exception("Transfer cancelled");
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

                        if (isCancelled) {
                            throw new Exception("Transfer cancelled");
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
                        activeInputStream = null;
                        if (conn != null) {
                            conn.disconnect();
                            conn = null;
                        }
                        activeConnection = null;

                        if (outFile.exists() && !outFile.delete()) {
                            DebugLogger.log("USB_DL", "Warning: could not remove pre-existing file before rename: " + outFile.getAbsolutePath());
                        }
                        if (!partFile.renameTo(outFile)) {
                            throw new Exception("Failed committing temporary file to USB destination");
                        }

                        DebugLogger.log("USB_DL", "Download SUCCESS: " + outFile.getAbsolutePath() + " (" + outFile.length() + " bytes)");
                        postComplete(callback, outFile);

                    } catch (Exception e) {
                        String errMsg = isCancelled ? "Transfer cancelled" : e.getMessage();
                        DebugLogger.log("USB_DL", "Download ended: " + clip.filename + " -> " + errMsg);
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

                        postError(callback, isCancelled ? new Exception("Transfer cancelled") : e);
                    } finally {
                        activeInputStream = null;
                        activeConnection = null;
                        activeTransfer.set(false);
                    }
                }
            });
        } catch (RuntimeException rex) {
            activeTransfer.set(false);
            postError(callback, rex);
        }
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
