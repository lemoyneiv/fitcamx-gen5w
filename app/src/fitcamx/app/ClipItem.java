package fitcamx.app;

import java.io.Serializable;
import java.net.URLEncoder;
import java.util.Locale;

public class ClipItem implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String name;       // e.g. "CARDV/MOVIE/2026_1107_120000_001F.MP4"
    public final String filename;   // e.g. "2026_1107_120000_001F.MP4"
    public final long sizeBytes;
    public final String timeStr;
    public final boolean isFront;
    public final boolean isLocked;
    public Boolean isHevc = null; // null = unprobed, true = HEVC (4K/2K), false = AVC (1080p)

    public ClipItem(String rawPath, long sizeBytes, String timeStr, int attr) {
        if (rawPath == null) rawPath = "";
        String clean = rawPath.trim().replace('\\', '/');
        if (clean.length() >= 3 && clean.charAt(1) == ':' && clean.charAt(2) == '/') {
            clean = clean.substring(3);
        }
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }

        this.name = clean;
        int lastSlash = clean.lastIndexOf('/');
        this.filename = (lastSlash >= 0) ? clean.substring(lastSlash + 1) : clean;
        this.sizeBytes = sizeBytes;
        this.timeStr = (timeStr != null) ? timeStr.trim() : "";

        this.isFront = !isRearFilename(clean, this.filename);

        // FAT Read-Only bit (0x01, e.g. 33 = ARCHIVE|READONLY) or RO / EMR / EVENT folder (including Novatek EMR_E / RO_E) indicates locked footage
        String upperPath = clean.toUpperCase(Locale.US);
        boolean readOnlyAttr = (attr > 0 && attr <= 0x3F && (attr & 0x01) != 0);
        this.isLocked = readOnlyAttr
                || upperPath.contains("/RO/") || upperPath.contains("/EMR/") || upperPath.contains("/EVENT/")
                || upperPath.contains("/RO_") || upperPath.contains("/EMR_") || upperPath.contains("/EVENT_")
                || upperPath.startsWith("RO/") || upperPath.startsWith("EMR/") || upperPath.startsWith("EVENT/")
                || upperPath.startsWith("RO_") || upperPath.startsWith("EMR_") || upperPath.startsWith("EVENT_");
    }

    /**
     * Detects rear-channel dashcam filenames:
     * FITCAMX / Novatek timestamps end with digit + 'B'/'R' (e.g. 20260206165204_047632B.TS, ...001R.MP4),
     * or explicit suffixes '_B', '_R', '-B', '-R', or 'REAR' token.
     */
    public static boolean isRearFilename(String fullPath, String fileName) {
        if (fullPath != null) {
            String upPath = fullPath.replace('\\', '/').toUpperCase(Locale.US);
            if (upPath.contains("/REAR/") || upPath.contains("_REAR/") || upPath.startsWith("REAR/")) {
                return true;
            }
        }
        if (fileName == null || fileName.isEmpty()) return false;
        String upper = fileName.toUpperCase(Locale.US);
        int dot = upper.lastIndexOf('.');
        String stem = (dot > 0) ? upper.substring(0, dot) : upper;
        if (stem.contains("REAR")
                || stem.startsWith("SUB_") || stem.contains("_SUB")
                || stem.endsWith("_CH2") || stem.contains("CH2_")
                || stem.endsWith("_B") || stem.endsWith("_R")
                || stem.endsWith("-B") || stem.endsWith("-R")) {
            return true;
        }
        if (stem.length() >= 2) {
            char last = stem.charAt(stem.length() - 1);
            char prev = stem.charAt(stem.length() - 2);
            if ((last == 'B' || last == 'R') && Character.isDigit(prev)) {
                return true;
            }
        }
        return false;
    }

    public String getFormattedSize() {
        if (sizeBytes <= 0) return "-";
        double mb = sizeBytes / (1024.0 * 1024.0);
        return String.format(Locale.US, "%.1f MB", mb);
    }

    public String getHttpUrl(String cameraIp) {
        return getHttpUrl(cameraIp, false, 80);
    }

    public String getHttpUrl(String cameraIp, boolean isJieLi, int port) {
        String rawRelPath;
        if (isJieLi) {
            rawRelPath = name.startsWith("/") ? name.substring(1) : name;
            int jlPort = (port > 0) ? port : 8080;
            return "http://" + cameraIp + ":" + jlPort + "/" + encodePathSegments(rawRelPath);
        }

        // Novatek / default
        rawRelPath = name.contains("/") ? name : ("CARDV/MOVIE/" + filename);
        String portPart = (port > 0 && port != 80) ? (":" + port) : "";
        return "http://" + cameraIp + portPart + "/" + encodePathSegments(rawRelPath);
    }

    private static String encodePathSegments(String path) {
        if (path == null || path.isEmpty()) return "";
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append('/');
            try {
                sb.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append(parts[i].replace(" ", "%20"));
            }
        }
        return sb.toString();
    }
}
