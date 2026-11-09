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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XmlListParser {
    private static final String TAG = "FitcamxXmlParser";
    // Match any typical video filename like 2026_1107_120000_001F.MP4, 20260206165204_047632A.TS or 20261107120000.mov
    private static final Pattern FILE_REGEX = Pattern.compile("(?i)(?:[A-Za-z]:[\\\\/])?(?:(?:CARDV|NOVATEK)[\\\\/])?(?:MOVIE|PHOTO|RO|EMR)?[\\\\/]?([A-Za-z0-9_.-]+\\.(?:mp4|mov|avi|ts))");
    // JieLi attribute regex: f="name" or "f":"name"
    private static final Pattern JIELI_REGEX = Pattern.compile("(?i)(?:\"f\"|f)[\\s:=]+[\"']?([A-Za-z0-9_.-]+\\.(?:mp4|mov|avi|ts))[\"']?");

    public static List<ClipItem> parseFileList(String rawData) {
        List<ClipItem> items = new ArrayList<ClipItem>();
        if (rawData == null || rawData.trim().isEmpty()) {
            return items;
        }

        // 1. Try structured XML pull parsing first
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(rawData));

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
                            String lower = currentPath.toLowerCase();
                            if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") || lower.endsWith(".ts")) {
                                items.add(new ClipItem(currentPath, currentSize, currentTime, currentAttr));
                            }
                            currentPath = null;
                        }
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.d(TAG, "Standard XML parse passed, trying alternative parsers: " + e.getMessage());
        }

        // 2. Try JieLi pattern if empty
        if (items.isEmpty()) {
            Set<String> seen = new HashSet<String>();
            Matcher jm = JIELI_REGEX.matcher(rawData);
            while (jm.find()) {
                String fn = jm.group(1);
                if (fn != null && !seen.contains(fn)) {
                    seen.add(fn);
                    items.add(new ClipItem(fn, 0, "", 0));
                }
            }
        }

        // 3. Fallback regex on the entire response text
        if (items.isEmpty()) {
            Set<String> seen = new HashSet<String>();
            Matcher m = FILE_REGEX.matcher(rawData);
            while (m.find()) {
                String fn = m.group(1);
                if (fn != null && !seen.contains(fn)) {
                    seen.add(fn);
                    String fullPath = "CARDV/MOVIE/" + fn;
                    items.add(new ClipItem(fullPath, 0, "", 0));
                }
            }
        }

        // Sort descending: newest files first
        Collections.sort(items, new Comparator<ClipItem>() {
            @Override
            public int compare(ClipItem a, ClipItem b) {
                if (a.timeStr != null && b.timeStr != null && !a.timeStr.isEmpty() && !b.timeStr.isEmpty()) {
                    return b.timeStr.compareTo(a.timeStr);
                }
                return b.filename.compareTo(a.filename);
            }
        });

        return items;
    }
}
