package fitcamx.app;

import android.util.Log;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.net.URLDecoder;
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

    // Complete <File>...</File> block recovery matcher (preserves FPATH, SIZE, TIME, ATTR after mid-XML syntax errors)
    private static final Pattern FILE_BLOCK_REGEX = Pattern.compile(
            "(?is)<(?:File|item|F)\\b[^>]*>((?:(?!</?(?:File|item|F)\\b).)*?)</(?:File|item|F)>");
    private static final Pattern BLOCK_FPATH_REGEX = Pattern.compile("(?i)<FPATH>\\s*([^<]+?)\\s*</FPATH>");
    private static final Pattern BLOCK_NAME_REGEX = Pattern.compile("(?i)<(?:NAME|FILE|PATH|url)>\\s*([^<]+?)\\s*</(?:NAME|FILE|PATH|url)>");
    private static final Pattern BLOCK_SIZE_REGEX = Pattern.compile("(?i)<(?:SIZE|FSIZE|F_SIZE|LENGTH)>\\s*(\\d+)\\s*</(?:SIZE|FSIZE|F_SIZE|LENGTH)>");
    private static final Pattern BLOCK_TIME_REGEX = Pattern.compile("(?i)<(?:TIME|DATE)>\\s*([^<]+?)\\s*</(?:TIME|DATE)>");
    private static final Pattern BLOCK_ATTR_REGEX = Pattern.compile("(?i)<ATTR>\\s*(-?\\d+)\\s*</ATTR>");

    // Explicit Novatek <FPATH> tag matcher (checked before bare filename regex so <NAME> doesn't shadow RO/EMR/PHOTO paths)
    private static final Pattern FPATH_TAG_REGEX = Pattern.compile(
            "(?i)<FPATH>\\s*([^<]+?\\.(?:mp4|mov|avi|ts|jpe?g|png))\\s*</FPATH>");

    // Capture optional directory hierarchy (preserving CARDV/NOVATEK/DCIM + MOVIE/RO/EMR/EVENT/PHOTO) and media filename
    private static final Pattern FILE_REGEX = Pattern.compile(
            "(?i)(?:[A-Za-z]:[\\\\/])?((?:(?:CARDV|NOVATEK|DCIM)[\\\\/])?(?:(?:MOVIE(?:_E)?|VIDEO|RO(?:_E)?|EMR(?:_E)?|EVENT(?:_E)?|PHOTO(?:_E)?|PIC|SNAP)[\\\\/])?(?:(?:RO(?:_E)?|EMR(?:_E)?|EVENT(?:_E)?|PHOTO(?:_E)?|FRONT|REAR)[\\\\/])?)([A-Za-z0-9_.-]+\\.(?:mp4|mov|avi|ts|jpe?g|png))");

    // JieLi attribute regex: f="name" or "f":"name"
    private static final Pattern JIELI_REGEX = Pattern.compile(
            "(?i)(?:\"f\"|f)[\\s:=]+[\"']?([A-Za-z0-9_./\\\\-]+\\.(?:mp4|mov|avi|ts|jpe?g|png))[\"']?");

    // HTML directory index <a href="..."> fallback (e.g. GET /CARDV/PHOTO/)
    private static final Pattern HTML_HREF_REGEX = Pattern.compile(
            "(?i)href\\s*=\\s*[\"']([^\"'>?#]+?\\.(?:mp4|mov|avi|ts|jpe?g|png))[\"']");

    /**
     * Single-pass linear sanitizer: strips invalid XML 1.0 control bytes (e.g. embedded NULs)
     * and escapes bare '&' characters without regex backtracking or redundant full-buffer copies.
     */
    public static String sanitizeXml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        final int len = raw.length();
        boolean needsTransform = false;
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || (c == '&' && !isValidXmlEntityAt(raw, i))) {
                needsTransform = true;
                break;
            }
        }
        if (!needsTransform) {
            return raw;
        }

        StringBuilder sb = new StringBuilder(len + 32);
        for (int i = 0; i < len; i++) {
            char c = raw.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                continue;
            }
            if (c == '&' && !isValidXmlEntityAt(raw, i)) {
                sb.append("&amp;");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isValidXmlEntityAt(String s, int ampIdx) {
        final int maxEnd = Math.min(s.length(), ampIdx + 12);
        int semi = -1;
        for (int i = ampIdx + 1; i < maxEnd; i++) {
            char c = s.charAt(i);
            if (c == ';') {
                semi = i;
                break;
            }
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '#')) {
                return false;
            }
        }
        if (semi <= ampIdx + 1) return false;
        String ent = s.substring(ampIdx + 1, semi);
        if ("amp".equals(ent) || "lt".equals(ent) || "gt".equals(ent) || "quot".equals(ent) || "apos".equals(ent)) {
            return true;
        }
        if (ent.charAt(0) == '#' && ent.length() >= 2) {
            if (ent.charAt(1) == 'x' || ent.charAt(1) == 'X') {
                if (ent.length() < 3) return false;
                for (int j = 2; j < ent.length(); j++) {
                    char hc = ent.charAt(j);
                    if (!((hc >= '0' && hc <= '9') || (hc >= 'a' && hc <= 'f') || (hc >= 'A' && hc <= 'F'))) {
                        return false;
                    }
                }
                return true;
            } else {
                for (int j = 1; j < ent.length(); j++) {
                    char dc = ent.charAt(j);
                    if (dc < '0' || dc > '9') return false;
                }
                return true;
            }
        }
        return false;
    }

    private static String normalizeDedupKey(ClipItem item) {
        if (item == null) return "";
        String path = item.name;
        if (path == null || path.isEmpty()) {
            return item.filename.toLowerCase(Locale.US);
        }
        if (!path.contains("/")) {
            String defDir = item.isPhoto ? "cardv/photo/" : "cardv/movie/";
            return defDir + path.toLowerCase(Locale.US);
        }
        return path.toLowerCase(Locale.US);
    }

    public static List<ClipItem> parseFileList(String rawData) {
        return parseFileList(rawData, null);
    }

    public static List<ClipItem> parseFileList(String rawData, String defaultDirectoryPrefix) {
        List<ClipItem> items = new ArrayList<ClipItem>();
        if (rawData == null || rawData.trim().isEmpty()) {
            return items;
        }

        String sanitized = sanitizeXml(rawData);
        Set<String> seenPaths = new HashSet<String>();
        Set<String> seenFilenamesWithExplicitDir = new HashSet<String>();
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
                            if (ClipItem.isSupportedMediaFilename(currentPath)) {
                                ClipItem item = new ClipItem(currentPath, currentSize, currentTime, currentAttr);
                                if (seenPaths.add(normalizeDedupKey(item))) {
                                    items.add(item);
                                    if (item.name.contains("/")) {
                                        seenFilenamesWithExplicitDir.add(item.filename.toLowerCase(Locale.US));
                                    }
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
            Log.d(TAG, "XML parser stopped early (" + items.size() + " items parsed so far), running regex recovery: " + e.getMessage());
        }

        // 2. Try JieLi pattern if XML yielded nothing
        if (items.isEmpty()) {
            Matcher jm = JIELI_REGEX.matcher(sanitized);
            while (jm.find()) {
                String fn = jm.group(1);
                if (fn != null) {
                    ClipItem item = new ClipItem(fn, 0, "", 0);
                    if (seenPaths.add(normalizeDedupKey(item))) {
                        items.add(item);
                    }
                }
            }
        }

        // 3. Try HTML <a href="..."> directory index links (e.g. /CARDV/PHOTO/ directory listing)
        if (items.isEmpty()) {
            Matcher hm = HTML_HREF_REGEX.matcher(sanitized);
            while (hm.find()) {
                String href = hm.group(1);
                if (href == null) continue;
                try {
                    href = URLDecoder.decode(href.trim(), "UTF-8");
                } catch (Exception ignored) {
                    href = href.trim().replace("%20", " ");
                }
                if (href.startsWith("../") || href.equals(".")) continue;
                String fullPath = href;
                if (!href.contains("/") && !href.contains("\\") && defaultDirectoryPrefix != null && !defaultDirectoryPrefix.isEmpty()) {
                    String pfx = defaultDirectoryPrefix.endsWith("/") ? defaultDirectoryPrefix : (defaultDirectoryPrefix + "/");
                    fullPath = pfx + href;
                }
                if (ClipItem.isSupportedMediaFilename(fullPath)) {
                    ClipItem item = new ClipItem(fullPath, 0, "", 0);
                    if (seenPaths.add(normalizeDedupKey(item))) {
                        items.add(item);
                        if (item.name.contains("/")) {
                            seenFilenamesWithExplicitDir.add(item.filename.toLowerCase(Locale.US));
                        }
                    }
                }
            }
        }

        // 4. Fallback / recovery regex on the entire response text (runs if XML was empty OR truncated mid-stream)
        if (items.isEmpty() || !xmlComplete) {
            // 4a. Recover intact <File>...</File> blocks with full SIZE, TIME, ATTR & FPATH metadata
            Matcher bm = FILE_BLOCK_REGEX.matcher(sanitized);
            while (bm.find()) {
                String block = bm.group(1);
                if (block == null) continue;
                String bPath = null;
                Matcher mFp = BLOCK_FPATH_REGEX.matcher(block);
                if (mFp.find()) {
                    bPath = mFp.group(1).trim();
                } else {
                    Matcher mNm = BLOCK_NAME_REGEX.matcher(block);
                    if (mNm.find()) {
                        bPath = mNm.group(1).trim();
                    }
                }
                if (bPath == null || !ClipItem.isSupportedMediaFilename(bPath)) continue;

                long bSize = 0L;
                Matcher mSz = BLOCK_SIZE_REGEX.matcher(block);
                if (mSz.find()) {
                    try {
                        bSize = Long.parseLong(mSz.group(1));
                    } catch (Exception ignored) {}
                }
                String bTime = "";
                Matcher mTm = BLOCK_TIME_REGEX.matcher(block);
                if (mTm.find()) {
                    bTime = mTm.group(1).trim();
                }
                int bAttr = 0;
                Matcher mAt = BLOCK_ATTR_REGEX.matcher(block);
                if (mAt.find()) {
                    try {
                        bAttr = Integer.parseInt(mAt.group(1));
                    } catch (Exception ignored) {}
                }

                ClipItem item = new ClipItem(bPath, bSize, bTime, bAttr);
                if (seenPaths.add(normalizeDedupKey(item))) {
                    items.add(item);
                    if (item.name.contains("/")) {
                        seenFilenamesWithExplicitDir.add(item.filename.toLowerCase(Locale.US));
                    }
                }
            }

            // 4b. Extract explicit <FPATH> entries (even in truncated final blocks) so RO/EMR/PHOTO paths aren't shadowed by bare <NAME>
            Matcher fm = FPATH_TAG_REGEX.matcher(sanitized);
            while (fm.find()) {
                String fpath = fm.group(1);
                if (fpath != null) {
                    ClipItem item = new ClipItem(fpath.trim(), 0, "", 0);
                    if (seenPaths.add(normalizeDedupKey(item))) {
                        items.add(item);
                        if (item.name.contains("/")) {
                            seenFilenamesWithExplicitDir.add(item.filename.toLowerCase(Locale.US));
                        }
                    }
                }
            }

            Matcher m = FILE_REGEX.matcher(sanitized);
            while (m.find()) {
                String dirPrefix = m.group(1);
                String fn = m.group(2);
                if (fn == null) continue;
                String normDir = (dirPrefix != null) ? dirPrefix.replace('\\', '/') : "";
                while (normDir.startsWith("/")) normDir = normDir.substring(1);

                String fullPath;
                if (!normDir.isEmpty()) {
                    fullPath = normDir.endsWith("/") ? (normDir + fn) : (normDir + "/" + fn);
                } else {
                    // Bare filename (e.g. <NAME> tag): skip if we already captured an explicit directory entry for this basename
                    if (seenFilenamesWithExplicitDir.contains(fn.toLowerCase(Locale.US))) {
                        continue;
                    }
                    String fallbackDir = defaultDirectoryPrefix;
                    if (fallbackDir == null || fallbackDir.isEmpty()) {
                        fallbackDir = ClipItem.isPhotoFilename(fn) ? "CARDV/PHOTO/" : "CARDV/MOVIE/";
                    } else if (!fallbackDir.endsWith("/")) {
                        fallbackDir = fallbackDir + "/";
                    }
                    fullPath = fallbackDir + fn;
                }
                ClipItem item = new ClipItem(fullPath, 0, "", 0);
                if (seenPaths.add(normalizeDedupKey(item))) {
                    items.add(item);
                }
            }
        }

        sortClipsDescending(items);
        return items;
    }

    /**
     * Strictly transitive total-order descending comparator (newest files first).
     * Prevents TimSort IllegalArgumentException when mixing XML-parsed clips (with timeStr)
     * and regex-recovered clips (with empty timeStr).
     */
    public static void sortClipsDescending(List<ClipItem> items) {
        if (items == null || items.size() <= 1) return;
        Collections.sort(items, new Comparator<ClipItem>() {
            @Override
            public int compare(ClipItem a, ClipItem b) {
                if (a == b) return 0;
                if (a == null) return 1;
                if (b == null) return -1;

                boolean aHasTime = (a.timeStr != null && !a.timeStr.isEmpty());
                boolean bHasTime = (b.timeStr != null && !b.timeStr.isEmpty());
                if (aHasTime != bHasTime) {
                    return aHasTime ? -1 : 1;
                }
                if (aHasTime) {
                    int cmpTime = b.timeStr.compareTo(a.timeStr);
                    if (cmpTime != 0) return cmpTime;
                }
                int cmpFile = b.filename.compareTo(a.filename);
                if (cmpFile != 0) return cmpFile;
                return b.name.compareTo(a.name);
            }
        });
    }
}
