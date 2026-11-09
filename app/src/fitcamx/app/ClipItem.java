package fitcamx.app;

import java.io.Serializable;

public class ClipItem implements Serializable {
    public final String name;       // e.g. "CARDV/MOVIE/2026_1107_120000_001F.MP4"
    public final String filename;   // e.g. "2026_1107_120000_001F.MP4"
    public final long sizeBytes;
    public final String timeStr;
    public final boolean isFront;
    public final boolean isLocked;

    public ClipItem(String rawPath, long sizeBytes, String timeStr, int attr) {
        if (rawPath == null) rawPath = "";
        String clean = rawPath.replace('\\', '/');
        if (clean.startsWith("A:/") || clean.startsWith("a:/") ||
            clean.startsWith("C:/") || clean.startsWith("c:/")) {
            clean = clean.substring(3);
        }
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }

        this.name = clean;
        int lastSlash = clean.lastIndexOf('/');
        this.filename = (lastSlash >= 0) ? clean.substring(lastSlash + 1) : clean;
        this.sizeBytes = sizeBytes;
        this.timeStr = timeStr;

        // FITCAMX HD2-Gi000 / Novatek filenames end with 'A.TS' (front) and 'B.TS' (rear),
        // or 'F.MP4' / 'F.MOV' (front) and 'R.MP4' / 'R.MOV' (rear)
        String upper = filename.toUpperCase();
        boolean isRear = upper.endsWith("B.TS") || upper.endsWith("B.MP4") || upper.endsWith("B.MOV") ||
                         upper.endsWith("R.TS") || upper.endsWith("R.MP4") || upper.endsWith("R.MOV") ||
                         upper.contains("_B.") || upper.contains("_R.") ||
                         upper.contains("REAR");
        this.isFront = !isRear;
        // Attr 33 or path containing RO / EMR indicates locked/emergency footage
        this.isLocked = (attr == 33) || clean.contains("/RO/") || clean.contains("/EMR/") || clean.startsWith("RO/") || clean.startsWith("EMR/");
    }

    public String getFormattedSize() {
        if (sizeBytes <= 0) return "-";
        double mb = sizeBytes / (1024.0 * 1024.0);
        return String.format("%.1f MB", mb);
    }

    public String getHttpUrl(String cameraIp) {
        return getHttpUrl(cameraIp, false, 80);
    }

    public String getHttpUrl(String cameraIp, boolean isJieLi, int port) {
        if (isJieLi) {
            String path = name.startsWith("/") ? name.substring(1) : name;
            return "http://" + cameraIp + ":" + (port > 0 ? port : 8080) + "/" + path;
        }

        // Novatek / default
        if (name.contains("/")) {
            return "http://" + cameraIp + "/" + name;
        } else {
            return "http://" + cameraIp + "/CARDV/MOVIE/" + filename;
        }
    }
}
