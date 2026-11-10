package fitcamx.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lightweight MPEG-TS & ISO-BMFF (MP4/MOV) Header Sniffer.
 * Inspects MPEG-TS PAT/PMT or MP4/MOV VisualSampleEntry boxes to determine video codec:
 * - HEVC / H.265 (TS stream 0x24, MP4/MOV 'hvc1'/'hev1')
 * - AVC / H.264  (TS stream 0x1B, MP4/MOV 'avc1'/'avc3')
 *
 * Operates on an 8KB header slice (<20ms over Wi-Fi, <1ms on USB), with a tail-moov
 * fallback for non-faststart MP4/MOV recordings.
 */
public class TsHeaderSniffer {
    private static final String TAG = "TsHeaderSniffer";

    public static final int CODEC_UNKNOWN = 0;
    public static final int CODEC_AVC = 1;     // H.264 / 1080p (Stream type 0x1B / avc1)
    public static final int CODEC_HEVC = 2;    // H.265 / 4K / 2K (Stream type 0x24 / hvc1 / hev1)

    private static final int BUFFER_SIZE = 8192;
    private static final int TAIL_BUFFER_SIZE = 65536;

    /**
     * Inspects a local file path or HTTP URL to determine video stream codec.
     * Blocks for a maximum of ~1500ms on network streams. Must be called off the UI thread.
     */
    public static int sniffCodec(String playUri) {
        if (playUri == null || playUri.isEmpty()) return CODEC_UNKNOWN;

        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead = 0;
        boolean isHttp = playUri.startsWith("http://") || playUri.startsWith("https://");
        File localFile = null;

        InputStream is = null;
        HttpURLConnection conn = null;
        try {
            if (isHttp) {
                URL url = new URL(playUri);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Range", "bytes=0-" + (BUFFER_SIZE - 1));
                conn.setRequestProperty("Connection", "close");
                conn.connect();

                is = conn.getInputStream();
                int read;
                while (bytesRead < BUFFER_SIZE && (read = is.read(buffer, bytesRead, BUFFER_SIZE - bytesRead)) != -1) {
                    bytesRead += read;
                }
            } else {
                String filePath = playUri;
                if (filePath.startsWith("file://")) {
                    filePath = filePath.substring(7);
                }
                localFile = new File(filePath);
                if (!localFile.exists() || !localFile.canRead()) return CODEC_UNKNOWN;

                is = new FileInputStream(localFile);
                int read;
                while (bytesRead < BUFFER_SIZE && (read = is.read(buffer, bytesRead, BUFFER_SIZE - bytesRead)) != -1) {
                    bytesRead += read;
                }
            }
        } catch (Exception e) {
            DebugLogger.log(TAG, "Sniff read error for " + playUri + ": " + e.getMessage());
            return CODEC_UNKNOWN;
        } finally {
            try {
                if (is != null) is.close();
            } catch (Exception ignored) {}
            try {
                if (conn != null) conn.disconnect();
            } catch (Exception ignored) {}
        }

        if (bytesRead < 16) {
            DebugLogger.log(TAG, "Insufficient bytes read for media header check: " + bytesRead);
            return CODEC_UNKNOWN;
        }

        // 1. Check ISO-BMFF (MP4 / MOV) container first if ftyp/moov/mdat signature is present
        if (isIsoBmffHeader(buffer, bytesRead)) {
            int mp4Codec = parseIsoBmffBuffer(buffer, bytesRead);
            if (mp4Codec != CODEC_UNKNOWN) {
                return mp4Codec;
            }
            // Dashcam MP4/MOV recordings frequently write the 'moov' atom at the end of the file
            return sniffIsoBmffTail(playUri, isHttp, localFile);
        }

        if (bytesRead < 376) {
            DebugLogger.log(TAG, "Insufficient bytes read for TS header check: " + bytesRead);
            return CODEC_UNKNOWN;
        }

        return parseTsBuffer(buffer, bytesRead);
    }

    private static int sniffIsoBmffTail(String playUri, boolean isHttp, File localFile) {
        if (!isHttp && localFile != null) {
            RandomAccessFile raf = null;
            try {
                long fileLen = localFile.length();
                if (fileLen <= BUFFER_SIZE) return CODEC_UNKNOWN;
                int tailLen = (int) Math.min((long) TAIL_BUFFER_SIZE, fileLen);
                byte[] tail = new byte[tailLen];
                raf = new RandomAccessFile(localFile, "r");
                raf.seek(fileLen - tailLen);
                raf.readFully(tail);
                return parseIsoBmffBuffer(tail, tailLen);
            } catch (Exception e) {
                DebugLogger.log(TAG, "MP4/MOV tail read notice: " + e.getMessage());
                return CODEC_UNKNOWN;
            } finally {
                if (raf != null) {
                    try { raf.close(); } catch (Exception ignored) {}
                }
            }
        } else if (isHttp) {
            HttpURLConnection conn = null;
            InputStream is = null;
            try {
                URL url = new URL(playUri);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1200);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Range", "bytes=-" + TAIL_BUFFER_SIZE);
                conn.setRequestProperty("Connection", "close");
                int code = conn.getResponseCode();
                if (code != 206) {
                    return CODEC_UNKNOWN;
                }
                is = conn.getInputStream();
                byte[] tail = new byte[TAIL_BUFFER_SIZE];
                int total = 0;
                int r;
                while (total < TAIL_BUFFER_SIZE && (r = is.read(tail, total, TAIL_BUFFER_SIZE - total)) != -1) {
                    total += r;
                }
                return parseIsoBmffBuffer(tail, total);
            } catch (Exception e) {
                DebugLogger.log(TAG, "HTTP MP4/MOV tail range notice: " + e.getMessage());
                return CODEC_UNKNOWN;
            } finally {
                try {
                    if (is != null) is.close();
                } catch (Exception ignored) {}
                try {
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }
        }
        return CODEC_UNKNOWN;
    }

    public static boolean isIsoBmffHeader(byte[] data, int length) {
        if (data == null || length < 12) return false;
        return matchesFourCc(data, 4, 'f', 't', 'y', 'p')
                || matchesFourCc(data, 4, 'm', 'o', 'o', 'v')
                || matchesFourCc(data, 4, 'm', 'd', 'a', 't')
                || matchesFourCc(data, 4, 'f', 'r', 'e', 'e')
                || matchesFourCc(data, 4, 'w', 'i', 'd', 'e');
    }

    /**
     * Scans an ISO-BMFF (MP4 / QuickTime MOV) buffer for VisualSampleEntry boxes:
     * 'hvc1' / 'hev1' -> CODEC_HEVC, 'avc1' / 'avc3' -> CODEC_AVC.
     * Validates box size and the mandatory 6-byte zero reserved field of SampleEntry.
     */
    public static int parseIsoBmffBuffer(byte[] data, int length) {
        if (data == null || length < 16) return CODEC_UNKNOWN;
        int limit = Math.min(length, data.length) - 12;
        for (int i = 4; i <= limit; i++) {
            int codec = CODEC_UNKNOWN;
            if (matchesFourCc(data, i, 'h', 'v', 'c', '1') || matchesFourCc(data, i, 'h', 'e', 'v', '1')) {
                codec = CODEC_HEVC;
            } else if (matchesFourCc(data, i, 'a', 'v', 'c', '1') || matchesFourCc(data, i, 'a', 'v', 'c', '3')) {
                codec = CODEC_AVC;
            }
            if (codec != CODEC_UNKNOWN) {
                int boxSize = ((data[i - 4] & 0xFF) << 24)
                        | ((data[i - 3] & 0xFF) << 16)
                        | ((data[i - 2] & 0xFF) << 8)
                        | (data[i - 1] & 0xFF);
                boolean reservedZero = (data[i + 4] == 0 && data[i + 5] == 0 && data[i + 6] == 0
                        && data[i + 7] == 0 && data[i + 8] == 0 && data[i + 9] == 0);
                if (reservedZero && boxSize >= 86 && boxSize <= 65536) {
                    return codec;
                }
            }
        }
        return CODEC_UNKNOWN;
    }

    private static boolean matchesFourCc(byte[] d, int offset, char c0, char c1, char c2, char c3) {
        return d[offset] == (byte) c0
                && d[offset + 1] == (byte) c1
                && d[offset + 2] == (byte) c2
                && d[offset + 3] == (byte) c3;
    }

    /**
     * Parses an in-memory buffer containing MPEG-TS (188B) or M2TS (192B) packets
     * or ISO-BMFF headers to extract video codec stream type.
     */
    public static int parseTsBuffer(byte[] data, int length) {
        if (data == null) return CODEC_UNKNOWN;
        int safeLen = Math.min(length, data.length);
        if (safeLen >= 16 && isIsoBmffHeader(data, safeLen)) {
            return parseIsoBmffBuffer(data, safeLen);
        }
        if (safeLen < 376) return CODEC_UNKNOWN;

        // Try standard 188-byte MPEG-TS stride first, then 192-byte M2TS (4-byte timestamp + 188-byte TS)
        int res = parseTsWithStride(data, safeLen, 188, 0);
        if (res != CODEC_UNKNOWN) return res;
        return parseTsWithStride(data, safeLen, 192, 4);
    }

    private static int parseTsWithStride(byte[] data, int length, int stride, int headerOffset) {
        int syncOffset = -1;
        boolean requireThreeSyncs = (length >= headerOffset + 2 * stride + 188);
        int maxSearch = requireThreeSyncs ? (length - (headerOffset + 2 * stride + 1)) : (length - (headerOffset + stride + 1));

        for (int i = 0; i <= maxSearch; i++) {
            int s0 = i + headerOffset;
            int s1 = s0 + stride;
            if (data[s0] == 0x47 && data[s1] == 0x47) {
                if (!requireThreeSyncs || data[s0 + 2 * stride] == 0x47) {
                    syncOffset = s0;
                    break;
                }
            }
        }

        if (syncOffset < 0) {
            return CODEC_UNKNOWN;
        }

        // Pass 1: Locate PMT PID from PAT (PID 0x0000, table_id 0x00)
        int pmtPid = -1;
        for (int pktStart = syncOffset; pktStart + 188 <= length; pktStart += stride) {
            if (data[pktStart] != 0x47) continue;
            if ((data[pktStart + 1] & 0x80) != 0) continue; // Transport Error Indicator

            int pid = ((data[pktStart + 1] & 0x1F) << 8) | (data[pktStart + 2] & 0xFF);
            if (pid != 0x0000) continue;

            int tableStart = resolveTableStart(data, pktStart, length);
            if (tableStart < 0 || tableStart + 8 > pktStart + 188 || tableStart + 8 > length) continue;

            int tableId = data[tableStart] & 0xFF;
            if (tableId != 0x00) continue;

            int sectionLen = ((data[tableStart + 1] & 0x0F) << 8) | (data[tableStart + 2] & 0xFF);
            int pos = tableStart + 8;
            int endPos = Math.min(Math.min(pktStart + 188, length), tableStart + 3 + sectionLen - 4);
            while (pos + 4 <= endPos) {
                int progNum = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                int progPid = ((data[pos + 2] & 0x1F) << 8) | (data[pos + 3] & 0xFF);
                if (progNum != 0 && progPid > 0x001F && progPid < 0x1FFF) {
                    pmtPid = progPid;
                    break;
                }
                pos += 4;
            }
            if (pmtPid != -1) break;
        }

        // Pass 2: Scan PMT packets (matching pmtPid if PAT was found, otherwise any valid PMT table_id 0x02)
        for (int pktStart = syncOffset; pktStart + 188 <= length; pktStart += stride) {
            if (data[pktStart] != 0x47) continue;
            if ((data[pktStart + 1] & 0x80) != 0) continue;

            int pid = ((data[pktStart + 1] & 0x1F) << 8) | (data[pktStart + 2] & 0xFF);
            if (pmtPid != -1) {
                if (pid != pmtPid) continue;
            } else {
                if (pid <= 0x001F || pid == 0x1FFF) continue;
            }

            int tableStart = resolveTableStart(data, pktStart, length);
            if (tableStart < 0 || tableStart + 12 > pktStart + 188 || tableStart + 12 > length) continue;

            int tableId = data[tableStart] & 0xFF;
            if (tableId != 0x02) continue;

            int sectionLen = ((data[tableStart + 1] & 0x0F) << 8) | (data[tableStart + 2] & 0xFF);
            int progInfoLen = ((data[tableStart + 10] & 0x0F) << 8) | (data[tableStart + 11] & 0xFF);
            int esPos = tableStart + 12 + progInfoLen;
            int endPos = Math.min(Math.min(pktStart + 188, length), tableStart + 3 + sectionLen - 4);

            while (esPos >= 0 && esPos + 5 <= endPos) {
                int streamType = data[esPos] & 0xFF;
                int esInfoLen = ((data[esPos + 3] & 0x0F) << 8) | (data[esPos + 4] & 0xFF);

                if (streamType == 0x24) {
                    return CODEC_HEVC;
                } else if (streamType == 0x1B) {
                    return CODEC_AVC;
                }
                esPos += 5 + esInfoLen;
            }
        }

        return CODEC_UNKNOWN;
    }

    private static int resolveTableStart(byte[] data, int pktStart, int length) {
        int pktEnd = Math.min(pktStart + 188, length);
        if (pktStart + 4 >= pktEnd) return -1;

        boolean pusi = (data[pktStart + 1] & 0x40) != 0;
        int afc = (data[pktStart + 3] & 0x30) >> 4;
        if (afc == 0 || afc == 2) return -1;

        int payloadStart = pktStart + 4;
        if (afc == 3) {
            int afLen = data[pktStart + 4] & 0xFF;
            payloadStart = pktStart + 5 + afLen;
        }
        if (payloadStart >= pktEnd) return -1;

        int tableStart = payloadStart;
        if (pusi) {
            int ptr = data[payloadStart] & 0xFF;
            tableStart = payloadStart + 1 + ptr;
        }
        if (tableStart >= pktEnd) return -1;
        return tableStart;
    }
}
