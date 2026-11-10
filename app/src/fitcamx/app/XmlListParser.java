package fitcamx.app;

import android.util.Log;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmlListParser {
    private static final String TAG = "FitcamxXmlParser";
    // Explicit Novatek <FPATH> tag matcher (checked before bare filename regex so <NAME> doesn't shadow RO/EMR paths)
    private static final Pattern FPATH_TAG_REGEX = Pattern.compile(
            "(?i)<FPATH>\\s*([^<]+?\\.(?:mp4|mov|avi|ts))\\s*</FPATH>");
    // Capture optional directory hierarchy (preserving CARDV/NOVATEK + MOVIE/MOVIE_E/RO/RO_E/EMR/EMR_E/EVENT) and video filename
    private static final Pattern FILE_REGEX = Pattern.compile(
            "(?i)(?:[A-Za-z]:[\\\\/])?((?:(?:CARDV|NOVATEK|DCIM)[\\\\/])?(?:(?:MOVIE(?:_E)?|VIDEO|RO(?:_E)?|EMR(?:_E)?|EVENT(?:_E)?)[\\\\/])?(?:(?:RO(?:_E)?|EMR(?:_E)?|EVENT(?:_E)?|FRONT|REAR)[\\\\/])?)([A-Za-z0-9_.-]+\\.(?:mp4|mov|avi|ts))");
    // JieLi attribute regex: f="name" or "f":"name"
    private static final Pattern JIELI_REGEX = Pattern.compile(
            "(?i)(?:\"f\"|f)[\\s:=]+[\"']?([A-Za-z0-9_./\\\\-]+\\.(?:mp4|mov|avi|ts))[\"']?");
    // Bare '&' not starting a valid XML entity
    private static final Pattern BARE_AMP_REGEX = Pattern.compile(
            "&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)");

    /**
     * Strips invalid XML 1.0 control bytes (e.g. embedded NULs) and escapes bare '&' characters
     * emitted by dashcam firmware so XmlPullParser does not abort mid-listing.
     */
    public static String sanitizeXml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                sb.append(c);
            }
        }
        return BARE_AMP_REGEX.matcher(sb.toString()).replaceAll("&amp;");
    }

    public static List<ClipItem> parseFileList(String rawData) {
        List<ClipItem> items = new ArrayList<ClipItem>();
        if (rawData == null || rawData.trim().isEmpty()) {
            return items;
        }

        String sanitized = sanitizeXml(rawData);
        Set<String> seenFilenames = new HashSet<String>();
        boolean xmlComplete = false;

        // 1. Try structured XML pull parsing first
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(sanitized));

            int eventType = parser.getEventType();
            String currentPath = null;
            long currentSize = 0;
            String currentTime = null;
            int currentAttr = 0;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        if ("File".equalsIgnoreCase(tagName) || "item".equalsIgnoreCase(tagName) || "F".equalsIgnoreCase(tagName)) {
                            currentPath = null;
                            currentSize = 0;
                            currentTime = null;
                            currentAttr = 0;
                        } else if ("FPATH".equalsIgnoreCase(tagName)) {
                            // FPATH has full directory path e.g. A:\NOVATEK\MOVIE\file.MOV
                            String fp = parser.nextText();
                            if (fp != null && !fp.trim().isEmpty()) {
                                currentPath = fp.trim();
                            }
                        } else if ("NAME".equalsIgnoreCase(tagName) || "FILE".equalsIgnoreCase(tagName) || "PATH".equalsIgnoreCase(tagName) || "url".equalsIgnoreCase(tagName)) {
                            if (currentPath == null) {
                                String n = parser.nextText();
                                if (n != null && !n.trim().isEmpty()) {
                                    currentPath = n.trim();
                                }
                            }
                        } else if ("SIZE".equalsIgnoreCase(tagName) || "FSIZE".equalsIgnoreCase(tagName) || "F_SIZE".equalsIgnoreCase(tagName) || "LENGTH".equalsIgnoreCase(tagName)) {
                            String s = parser.nextText();
                            try {
                                currentSize = Long.parseLong(s.trim());
                            } catch (Exception ignored) {}
                        } else if ("TIME".equalsIgnoreCase(tagName) || "DATE".equalsIgnoreCase(tagName)) {
                            currentTime = parser.nextText();
                        } else if ("ATTR".equalsIgnoreCase(tagName)) {
                            String a = parser.nextText();
                            try {
                                currentAttr = Integer.parseInt(a.trim());
                            } catch (Exception ignored) {}
                        }
                        break;

                    case XmlPullParser.END_TAG:
                        if (("File".equalsIgnoreCase(tagName) || "item".equalsIgnoreCase(tagName) || "F".equalsIgnoreCase(tagName)) && currentPath != null) {
                            String lower = currentPath.toLowerCase(Locale.US);
                            if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") || lower.endsWith(".ts")) {
                                ClipItem item = new ClipItem(currentPath, currentSize, currentTime, currentAttr);
                                if (seenFilenames.add(item.filename.toLowerCase(Locale.US))) {
                                    items.add(item);
                                }
                            }
                            currentPath = null;
                        }
                        break;
                }
                eventType = parser.next();
            }
            xmlComplete = true;
        } catch (Exception e) {
            Log.d(TAG, "XML parser stopped early (" + items.size() + " clips parsed so far), running regex recovery: " + e.getMessage());
        }

        // 2. Try JieLi pattern if XML yielded nothing
        if (items.isEmpty()) {
            Matcher jm = JIELI_REGEX.matcher(sanitized);
            while (jm.find()) {
                String fn = jm.group(1);
                if (fn != null) {
                    ClipItem item = new ClipItem(fn, 0, "", 0);
                    if (seenFilenames.add(item.filename.toLowerCase(Locale.US))) {
                        items.add(item);
                    }
                }
            }
        }

        // 3. Fallback / recovery regex on the entire response text (runs if XML was empty OR truncated mid-stream)
        if (items.isEmpty() || !xmlComplete) {
            // First extract explicit <FPATH> entries so directory paths (e.g. CARDV\RO, CARDV\EMR) aren't shadowed by <NAME>
            Matcher fm = FPATH_TAG_REGEX.matcher(sanitized);
            while (fm.find()) {
                String fpath = fm.group(1);
                if (fpath != null) {
                    ClipItem item = new ClipItem(fpath.trim(), 0, "", 0);
                    if (seenFilenames.add(item.filename.toLowerCase(Locale.US))) {
                        items.add(item);
                    }
                }
            }

            Matcher m = FILE_REGEX.matcher(sanitized);
            while (m.find()) {
                String dirPrefix = m.group(1);
                String fn = m.group(2);
                if (fn != null && seenFilenames.add(fn.toLowerCase(Locale.US))) {
                    String normDir = (dirPrefix != null) ? dirPrefix.replace('\\', '/') : "";
                    while (normDir.startsWith("/")) normDir = normDir.substring(1);
                    String fullPath;
                    if (!normDir.isEmpty()) {
                        fullPath = normDir.endsWith("/") ? (normDir + fn) : (normDir + "/" + fn);
                    } else {
                        fullPath = "CARDV/MOVIE/" + fn;
                    }
                    items.add(new ClipItem(fullPath, 0, "", 0));
                }
            }
        }

        // Sort descending: newest files first
        Collections.sort(items, new Comparator<ClipItem>() {
            @Override
            public int compare(ClipItem a, ClipItem b) {
                if (a.timeStr != null && b.timeStr != null && !a.timeStr.isEmpty() && !b.timeStr.isEmpty()) {
                    int cmp = b.timeStr.compareTo(a.timeStr);
                    if (cmp != 0) return cmp;
                }
                return b.filename.compareTo(a.filename);
            }
        });

        return items;
    }
}
