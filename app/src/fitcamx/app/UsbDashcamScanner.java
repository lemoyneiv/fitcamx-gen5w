/*
 * Scanner for standalone KramerTech/gen5-RearDashcam USB recordings (<mount>/Dashcam/).
 * Merges loop segments (*.ts), Saved/*.ts (+ Saved/names.txt labels), and Snapshots/*.{png,jpg}
 * into Fitcamx's unified Gallery timeline.
 */
package fitcamx.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UsbDashcamScanner {
    public static final String DEFAULT_FOLDER_NAME = "Dashcam";
    public static final String SAVED_SUBDIR = "Saved";
    public static final String SNAPSHOTS_SUBDIR = "Snapshots";
    public static final String SAVED_NAMES_FILE = "names.txt";
    public static final String EXTERNAL_STATUS_PATH = "/data/nemesis/dashcam/status";

    private static final Pattern TS_CLIP_PATTERN =
            Pattern.compile("^(\\d{8}_\\d{6})(?:_\\d+)?\\.ts$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHOTO_TIMESTAMP_PATTERN =
            Pattern.compile("(\\d{8}_\\d{6})");

    private UsbDashcamScanner() {}

    /**
     * Scans all mounted USB drives (plus any custom out= path reported by gen5-RearDashcam's
     * readable status file) for Dashcam clips and snapshots.
     */
    public static List<ClipItem> scanConnectedUsbDrives() {
        Set<String> canonicalDirs = new LinkedHashSet<String>();
        List<File> dashcamDirs = new ArrayList<File>();

        // 1. Check custom output directory if standalone gen5-RearDashcam status file is present
        String customOut = readConfiguredOutDir(new File(EXTERNAL_STATUS_PATH));
        if (customOut != null && !customOut.isEmpty()) {
            addCandidateDir(new File(customOut), canonicalDirs, dashcamDirs);
        }

        // 2. Check standard USB mount points
        String[] fixedMounts = new String[] {
            "/storage/usb0", "/storage/usb1", "/storage/usb2",
            "/mnt/media_rw/usb0", "/mnt/media_rw/usb1",
            "/mnt/usb_storage", "/mnt/udisk"
        };
        for (String mount : fixedMounts) {
            addCandidateDir(new File(mount, DEFAULT_FOLDER_NAME), canonicalDirs, dashcamDirs);
        }

        // 3. Check dynamically discovered USB mounts
        try {
            List<File> dynamicMounts = DebugLogger.findUsbMountDirectories();
            if (dynamicMounts != null) {
                for (File mount : dynamicMounts) {
                    addCandidateDir(new File(mount, DEFAULT_FOLDER_NAME), canonicalDirs, dashcamDirs);
                }
            }
        } catch (Throwable ignored) {}

        List<ClipItem> results = new ArrayList<ClipItem>();
        for (File dir : dashcamDirs) {
            results.addAll(scanDashcamDirectory(dir));
        }
        return results;
    }

    private static void addCandidateDir(File dir, Set<String> seenCanonical, List<File> outDirs) {
        if (dir == null || !dir.isDirectory() || !dir.canRead()) return;
        try {
            String canon = dir.getCanonicalPath();
            if (seenCanonical.add(canon)) {
                outDirs.add(dir);
            }
        } catch (Exception e) {
            String abs = dir.getAbsolutePath();
            if (seenCanonical.add(abs)) {
                outDirs.add(dir);
            }
        }
    }

    public static String readConfiguredOutDir(File statusFile) {
        if (statusFile == null || !statusFile.isFile() || !statusFile.canRead()) return null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(statusFile), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("out=")) {
                    String val = line.substring(4).trim();
                    if (!val.isEmpty()) return val;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Scans a single <mount>/Dashcam directory (including Saved/ and Snapshots/ subdirectories).
     */
    public static List<ClipItem> scanDashcamDirectory(File dashcamDir) {
        List<ClipItem> items = new ArrayList<ClipItem>();
        if (dashcamDir == null || !dashcamDir.isDirectory()) return items;

        SimpleDateFormat inFmt = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        SimpleDateFormat outFmt = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);

        // 1. Loop segments in root Dashcam/
        scanTsFiles(dashcamDir, false, null, inFmt, outFmt, items);

        // 2. Locked/saved segments in Dashcam/Saved/ (+ optional names.txt event labels)
        File savedDir = new File(dashcamDir, SAVED_SUBDIR);
        if (savedDir.isDirectory()) {
            Map<String, String> labels = readSavedLabels(new File(savedDir, SAVED_NAMES_FILE));
            scanTsFiles(savedDir, true, labels, inFmt, outFmt, items);
        }

        // 3. Still snapshots in Dashcam/Snapshots/
        File snapshotsDir = new File(dashcamDir, SNAPSHOTS_SUBDIR);
        if (snapshotsDir.isDirectory()) {
            scanSnapshotFiles(snapshotsDir, inFmt, outFmt, items);
        }

        return items;
    }

    private static void scanTsFiles(File dir, boolean isSaved, Map<String, String> labels,
                                    SimpleDateFormat inFmt, SimpleDateFormat outFmt, List<ClipItem> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || f.length() <= 0) continue;
            String fname = f.getName();
            Matcher m = TS_CLIP_PATTERN.matcher(fname);
            if (!m.matches()) continue;
            String timeStr = formatTimestampOrFallback(m.group(1), f.lastModified(), inFmt, outFmt);
            String label = (labels != null) ? labels.get(fname) : null;
            out.add(ClipItem.fromOemUsbFile(f, timeStr, isSaved, false, label));
        }
    }

    private static void scanSnapshotFiles(File dir, SimpleDateFormat inFmt, SimpleDateFormat outFmt,
                                          List<ClipItem> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || f.length() <= 0) continue;
            String fname = f.getName();
            if (!ClipItem.isPhotoFilename(fname)) continue;
            Matcher m = PHOTO_TIMESTAMP_PATTERN.matcher(fname);
            String rawTs = m.find() ? m.group(1) : null;
            String timeStr = formatTimestampOrFallback(rawTs, f.lastModified(), inFmt, outFmt);
            out.add(ClipItem.fromOemUsbFile(f, timeStr, false, true, null));
        }
    }

    public static Map<String, String> readSavedLabels(File namesFile) {
        Map<String, String> map = new HashMap<String, String>();
        if (namesFile == null || !namesFile.isFile() || !namesFile.canRead()) return map;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(namesFile), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab + 1 < line.length()) {
                    String fileKey = line.substring(0, tab).trim();
                    String label = line.substring(tab + 1).trim();
                    if (!fileKey.isEmpty() && !label.isEmpty()) {
                        map.put(fileKey, label);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private static String formatTimestampOrFallback(String ymdHms, long lastModifiedMs,
                                                    SimpleDateFormat inFmt, SimpleDateFormat outFmt) {
        if (ymdHms != null) {
            try {
                Date parsed = inFmt.parse(ymdHms);
                if (parsed != null) {
                    return outFmt.format(parsed);
                }
            } catch (ParseException ignored) {}
        }
        if (lastModifiedMs > 0) {
            return outFmt.format(new Date(lastModifiedMs));
        }
        return "";
    }
}
