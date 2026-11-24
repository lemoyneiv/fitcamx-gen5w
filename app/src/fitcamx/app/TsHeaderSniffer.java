package fitcamx.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lightweight MPEG-TS & ISO-BMFF (MP4/MOV) Header & Resolution Sniffer.
 * Inspects MPEG-TS PAT/PMT (+ H.264 Annex-B SPS) or MP4/MOV VisualSampleEntry boxes to determine
 * both video codec and pixel dimensions:
 * - HEVC / H.265 (TS stream 0x24, MP4/MOV 'hvc1'/'hev1')
 * - AVC / H.264  (TS stream 0x1B, MP4/MOV 'avc1'/'avc3'), including 4K/2K H.264 recordings
 *
 * Operates on an 8KB header slice (<20ms over Wi-Fi, <1ms on USB), with a tail-moov
 * fallback for non-faststart MP4/MOV recordings.
 */
public class TsHeaderSniffer {
    private static final String TAG = "TsHeaderSniffer";

    public static final int CODEC_UNKNOWN = 0;
    public static final int CODEC_AVC = 1;     // H.264 (Stream type 0x1B / avc1 / avc3)
    public static final int CODEC_HEVC = 2;    // H.265 (Stream type 0x24 / hvc1 / hev1)

    public static final int HIGH_RES_MAX_1080P_WIDTH = 1920;
    public static final int HIGH_RES_MAX_1080P_HEIGHT = 1088;

    private static final int BUFFER_SIZE = 8192;
    private static final int TAIL_BUFFER_SIZE = 65536;

    /**
     * Immutable codec + pixel dimension result from header inspection.
     */
    public static final class MediaInfo {
        public static final MediaInfo UNKNOWN = new MediaInfo(CODEC_UNKNOWN, 0, 0);

        public final int codec;
        public final int width;
        public final int height;

        public MediaInfo(int codec, int width, int height) {
            this.codec = codec;
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
        }

        /**
         * Returns true when playback requires the 0.33x keyframe-only high-res software decode
         * profile (any HEVC clip, or any H.264 clip exceeding 1920x1088 such as 2K/4K avc1).
         */
        public boolean isHighRes() {
            return requiresHighResKeyframeProfile(codec, width, height);
        }

        public String describe() {
            String codecName = (codec == CODEC_HEVC) ? "HEVC"
                    : (codec == CODEC_AVC ? "AVC" : "UNKNOWN");
            if (width > 0 && height > 0) {
                return codecName + " " + width + "x" + height;
            }
            return isHighRes() ? ("4K/2K " + codecName) : ("1080p " + codecName);
        }
    }

    /**
     * Pure helper: determines whether a video track requires the 0.33x keyframe-only decode profile
     * on Gen5W's 1.0 GHz quad-core Cortex-A7 CPU.
     */
    public static boolean requiresHighResKeyframeProfile(int codec, int width, int height) {
        if (codec == CODEC_HEVC) {
            return true;
        }
        if (codec == CODEC_AVC) {
            return width > HIGH_RES_MAX_1080P_WIDTH || height > HIGH_RES_MAX_1080P_HEIGHT;
        }
        return false;
    }

    /**
     * Inspects a local file path or HTTP URL to determine video stream codec.
     */
    public static int sniffCodec(String playUri) {
        return sniffMediaInfo(playUri).codec;
    }

    /**
     * Inspects a local file path or HTTP URL to determine video codec and pixel dimensions.
     * Blocks for a maximum of ~1500ms on network streams. Must be called off the UI thread.
     */
    public static MediaInfo sniffMediaInfo(String playUri) {
        if (playUri == null || playUri.isEmpty()) return MediaInfo.UNKNOWN;

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
                if (filePath.startsWith("file:")) {
                    try {
                        localFile = new File(new java.net.URI(filePath));
                    } catch (Exception uriErr) {
                        if (filePath.startsWith("file://")) {
                            filePath = filePath.substring(7);
                        } else {
                            filePath = filePath.substring(5);
                        }
                        try {
                            filePath = java.net.URLDecoder.decode(filePath, "UTF-8");
                        } catch (Exception ignored) {
                            filePath = filePath.replace("%20", " ");
                        }
                        localFile = new File(filePath);
                    }
                } else {
                    localFile = new File(filePath);
                }
                if (!localFile.exists() || !localFile.canRead()) return MediaInfo.UNKNOWN;

                is = new FileInputStream(localFile);
                int read;
                while (bytesRead < BUFFER_SIZE && (read = is.read(buffer, bytesRead, BUFFER_SIZE - bytesRead)) != -1) {
                    bytesRead += read;
                }
            }
        } catch (Exception e) {
            DebugLogger.log(TAG, "Sniff read error for " + playUri + ": " + e.getMessage());
            return MediaInfo.UNKNOWN;
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
            return MediaInfo.UNKNOWN;
        }

        // 1. Check ISO-BMFF (MP4 / MOV) container first if ftyp/moov/mdat signature is present
        if (isIsoBmffHeader(buffer, bytesRead)) {
            MediaInfo mp4Info = parseIsoBmffMediaInfo(buffer, bytesRead);
            if (mp4Info.codec != CODEC_UNKNOWN) {
                return mp4Info;
            }
            // Dashcam MP4/MOV recordings frequently write the 'moov' atom at the end of the file
            return sniffIsoBmffTail(playUri, isHttp, localFile);
        }

        if (bytesRead < 376) {
            DebugLogger.log(TAG, "Insufficient bytes read for TS header check: " + bytesRead);
            return MediaInfo.UNKNOWN;
        }

        return parseMediaInfo(buffer, bytesRead);
    }

    private static MediaInfo sniffIsoBmffTail(String playUri, boolean isHttp, File localFile) {
        if (!isHttp && localFile != null) {
            RandomAccessFile raf = null;
            try {
                long fileLen = localFile.length();
                if (fileLen <= BUFFER_SIZE) return MediaInfo.UNKNOWN;
                int tailLen = (int) Math.min((long) TAIL_BUFFER_SIZE, fileLen);
                byte[] tail = new byte[tailLen];
                raf = new RandomAccessFile(localFile, "r");
                raf.seek(fileLen - tailLen);
                raf.readFully(tail);
                return parseIsoBmffMediaInfo(tail, tailLen);
            } catch (Exception e) {
                DebugLogger.log(TAG, "MP4/MOV tail read notice: " + e.getMessage());
                return MediaInfo.UNKNOWN;
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
                    conn.disconnect();
                    conn = null;
                    // Fallback for embedded HTTP servers that reject suffix ranges ("bytes=-N")
                    // but honor explicit ranges ("bytes=<start>-<end>") after HEAD Content-Length query.
                    long contentLen = queryHttpContentLength(playUri);
                    if (contentLen <= BUFFER_SIZE) {
                        return MediaInfo.UNKNOWN;
                    }
                    long startByte = Math.max(0L, contentLen - TAIL_BUFFER_SIZE);
                    conn = (HttpURLConnection) new URL(playUri).openConnection();
                    conn.setConnectTimeout(1000);
                    conn.setReadTimeout(1200);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Range", "bytes=" + startByte + "-" + (contentLen - 1));
                    conn.setRequestProperty("Connection", "close");
                    if (conn.getResponseCode() != 206) {
                        return MediaInfo.UNKNOWN;
                    }
                }
                is = conn.getInputStream();
                byte[] tail = new byte[TAIL_BUFFER_SIZE];
                int total = 0;
                int r;
                while (total < TAIL_BUFFER_SIZE && (r = is.read(tail, total, TAIL_BUFFER_SIZE - total)) != -1) {
                    total += r;
                }
                return parseIsoBmffMediaInfo(tail, total);
            } catch (Exception e) {
                DebugLogger.log(TAG, "HTTP MP4/MOV tail range notice: " + e.getMessage());
                return MediaInfo.UNKNOWN;
            } finally {
                try {
                    if (is != null) is.close();
                } catch (Exception ignored) {}
                try {
                    if (conn != null) conn.disconnect();
                } catch (Exception ignored) {}
            }
        }
        return MediaInfo.UNKNOWN;
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
     */
    public static int parseIsoBmffBuffer(byte[] data, int length) {
        return parseIsoBmffMediaInfo(data, length).codec;
    }

    /**
     * Scans an ISO-BMFF (MP4 / QuickTime MOV) buffer for VisualSampleEntry boxes and extracts
     * both the video codec and the 16-bit pixel width & height at offsets +28..+31 from the FourCC
     * (ISO/IEC 14496-12 Section 8.5.2.2 / QuickTime Video Sample Description).
     */
    public static MediaInfo parseIsoBmffMediaInfo(byte[] data, int length) {
        if (data == null || length < 16) return MediaInfo.UNKNOWN;
        int safeLen = Math.min(length, data.length);
        int limit = safeLen - 12;
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
                    int width = 0;
                    int height = 0;
                    if (i + 31 < safeLen) {
                        int w = ((data[i + 28] & 0xFF) << 8) | (data[i + 29] & 0xFF);
                        int h = ((data[i + 30] & 0xFF) << 8) | (data[i + 31] & 0xFF);
                        if (w >= 160 && w <= 8192 && h >= 120 && h <= 8192) {
                            width = w;
                            height = h;
                        }
                    }
                    // Optional fallback: if avc1 header zeroed width/height, inspect embedded avcC SPS
                    if (width == 0 && codec == CODEC_AVC) {
                        int boxEnd = Math.min(safeLen, (i - 4) + boxSize);
                        int[] spsDims = scanAvccBoxForSpsDimensions(data, i + 82, boxEnd);
                        if (spsDims != null) {
                            width = spsDims[0];
                            height = spsDims[1];
                        }
                    }
                    return new MediaInfo(codec, width, height);
                }
            }
        }
        return MediaInfo.UNKNOWN;
    }

    private static int[] scanAvccBoxForSpsDimensions(byte[] data, int start, int end) {
        if (start < 0 || start + 16 > end) return null;
        for (int p = start; p + 12 <= end; p++) {
            if (matchesFourCc(data, p, 'a', 'v', 'c', 'C')) {
                // AVCDecoderConfigurationRecord: [p+4]=version(1), [p+5..7]=profile/compat/level,
                // [p+8]=lengthSizeMinusOne, [p+9]=numOfSPS (& 0x1F), [p+10..11]=spsLength, [p+12..]=NAL
                int numSps = data[p + 9] & 0x1F;
                if (numSps >= 1 && p + 13 <= end) {
                    int spsLen = ((data[p + 10] & 0xFF) << 8) | (data[p + 11] & 0xFF);
                    int nalStart = p + 12;
                    if (spsLen >= 4 && nalStart + spsLen <= end && (data[nalStart] & 0x1F) == 7) {
                        byte[] rbsp = unescapeRbsp(data, nalStart + 1, spsLen - 1);
                        return parseH264SpsDimensions(rbsp, rbsp.length);
                    }
                }
            }
        }
        return null;
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
        return parseMediaInfo(data, length).codec;
    }

    /**
     * Parses an in-memory buffer containing ISO-BMFF or MPEG-TS/M2TS packets to extract
     * both codec and pixel dimensions (including H.264 Annex-B SPS in MPEG-TS).
     */
    public static MediaInfo parseMediaInfo(byte[] data, int length) {
        if (data == null) return MediaInfo.UNKNOWN;
        int safeLen = Math.min(length, data.length);
        if (safeLen >= 16 && isIsoBmffHeader(data, safeLen)) {
            return parseIsoBmffMediaInfo(data, safeLen);
        }
        if (safeLen < 376) return MediaInfo.UNKNOWN;

        // Try standard 188-byte MPEG-TS stride first, then 192-byte M2TS (4-byte timestamp + 188-byte TS)
        MediaInfo res = parseTsWithStride(data, safeLen, 188, 0);
        if (res.codec != CODEC_UNKNOWN) return res;
        return parseTsWithStride(data, safeLen, 192, 4);
    }

    private static MediaInfo parseTsWithStride(byte[] data, int length, int stride, int headerOffset) {
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
            return MediaInfo.UNKNOWN;
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
                int elemPid = ((data[esPos + 1] & 0x1F) << 8) | (data[esPos + 2] & 0xFF);
                int esInfoLen = ((data[esPos + 3] & 0x0F) << 8) | (data[esPos + 4] & 0xFF);

                if (streamType == 0x24) {
                    return new MediaInfo(CODEC_HEVC, 0, 0);
                } else if (streamType == 0x1B) {
                    int[] dims = extractTsAvcDimensions(data, length, syncOffset, stride, elemPid);
                    if (dims != null) {
                        return new MediaInfo(CODEC_AVC, dims[0], dims[1]);
                    }
                    return new MediaInfo(CODEC_AVC, 0, 0);
                }
                esPos += 5 + esInfoLen;
            }
        }

        return MediaInfo.UNKNOWN;
    }

    /**
     * Reassembles the initial video elementary stream payload for videoPid from MPEG-TS packets
     * and parses the first Annex-B H.264 SPS NAL unit (type 7) if present in the header buffer.
     */
    private static int[] extractTsAvcDimensions(byte[] data, int length, int syncOffset, int stride, int videoPid) {
        if (videoPid <= 0x001F || videoPid >= 0x1FFF) return null;
        byte[] esBuf = new byte[1024];
        int esLen = 0;

        for (int pktStart = syncOffset; pktStart + 188 <= length && esLen < esBuf.length; pktStart += stride) {
            if (data[pktStart] != 0x47) continue;
            if ((data[pktStart + 1] & 0x80) != 0) continue;
            int pid = ((data[pktStart + 1] & 0x1F) << 8) | (data[pktStart + 2] & 0xFF);
            if (pid != videoPid) continue;

            int afc = (data[pktStart + 3] & 0x30) >> 4;
            if (afc == 0 || afc == 2) continue;

            int payloadStart = pktStart + 4;
            if (afc == 3) {
                int afLen = data[pktStart + 4] & 0xFF;
                payloadStart = pktStart + 5 + afLen;
            }
            int pktEnd = pktStart + 188;
            if (payloadStart >= pktEnd) continue;

            // Strip PES header on PUSI packet (00 00 01 <stream_id> <len_hi> <len_lo> <flags1> <flags2> <hdr_len>)
            boolean pusi = (data[pktStart + 1] & 0x40) != 0;
            if (pusi && payloadStart + 9 <= pktEnd
                    && data[payloadStart] == 0 && data[payloadStart + 1] == 0 && data[payloadStart + 2] == 1) {
                int pesHdrLen = data[payloadStart + 8] & 0xFF;
                int afterPes = payloadStart + 9 + pesHdrLen;
                if (afterPes < pktEnd) {
                    payloadStart = afterPes;
                }
            }

            int copyLen = Math.min(pktEnd - payloadStart, esBuf.length - esLen);
            if (copyLen > 0) {
                System.arraycopy(data, payloadStart, esBuf, esLen, copyLen);
                esLen += copyLen;
            }
        }

        if (esLen < 8) return null;

        // Scan reassembled elementary stream for Annex-B start code 00 00 01 <nalHdr> with type == 7 (SPS)
        for (int i = 0; i + 4 < esLen; i++) {
            if (esBuf[i] == 0 && esBuf[i + 1] == 0 && esBuf[i + 2] == 1) {
                int nalHdr = esBuf[i + 3] & 0xFF;
                if ((nalHdr & 0x80) == 0 && (nalHdr & 0x1F) == 7) {
                    int spsStart = i + 4;
                    int spsEnd = esLen;
                    for (int j = spsStart; j + 2 < esLen; j++) {
                        if (esBuf[j] == 0 && esBuf[j + 1] == 0 && (esBuf[j + 2] == 1 || (j + 3 < esLen && esBuf[j + 2] == 0 && esBuf[j + 3] == 1))) {
                            spsEnd = j;
                            break;
                        }
                    }
                    if (spsEnd - spsStart >= 4) {
                        byte[] rbsp = unescapeRbsp(esBuf, spsStart, Math.min(spsEnd - spsStart, 256));
                        int[] dims = parseH264SpsDimensions(rbsp, rbsp.length);
                        if (dims != null) {
                            return dims;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static byte[] unescapeRbsp(byte[] src, int offset, int len) {
        byte[] out = new byte[len];
        int w = 0;
        int zeros = 0;
        int end = Math.min(src.length, offset + len);
        for (int i = offset; i < end; i++) {
            int b = src[i] & 0xFF;
            if (zeros >= 2 && b == 0x03) {
                zeros = 0;
                continue;
            }
            out[w++] = (byte) b;
            if (b == 0) {
                zeros++;
            } else {
                zeros = 0;
            }
        }
        if (w == out.length) return out;
        byte[] trimmed = new byte[w];
        System.arraycopy(out, 0, trimmed, 0, w);
        return trimmed;
    }

    /**
     * Pure ISO/IEC 14496-10 (H.264) Sequence Parameter Set RBSP parser (starting at profile_idc,
     * immediately after the 1-byte NAL header). Returns {width, height} or null if truncated/invalid.
     */
    public static int[] parseH264SpsDimensions(byte[] rbsp, int rbspLen) {
        if (rbsp == null || rbspLen < 4) return null;
        BitReader br = new BitReader(rbsp, Math.min(rbspLen, rbsp.length));
        try {
            int profileIdc = br.readBits(8);
            br.readBits(8); // constraint flags
            br.readBits(8); // level_idc
            br.readUe();    // seq_parameter_set_id

            int chromaFormatIdc = 1;
            int separateColourPlaneFlag = 0;
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244
                    || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118
                    || profileIdc == 128 || profileIdc == 138 || profileIdc == 139
                    || profileIdc == 134 || profileIdc == 135) {
                chromaFormatIdc = br.readUe();
                if (chromaFormatIdc > 3) return null;
                if (chromaFormatIdc == 3) {
                    separateColourPlaneFlag = br.readBits(1);
                }
                br.readUe(); // bit_depth_luma_minus8
                br.readUe(); // bit_depth_chroma_minus8
                br.readBits(1); // qpprime_y_zero_transform_bypass_flag
                int seqScalingMatrixPresent = br.readBits(1);
                if (seqScalingMatrixPresent == 1) {
                    int scalingCount = (chromaFormatIdc != 3) ? 8 : 12;
                    for (int idx = 0; idx < scalingCount; idx++) {
                        if (br.readBits(1) == 1) {
                            int listSize = (idx < 6) ? 16 : 64;
                            int lastScale = 8;
                            int nextScale = 8;
                            for (int j = 0; j < listSize; j++) {
                                if (nextScale != 0) {
                                    int deltaScale = br.readSe();
                                    nextScale = (lastScale + deltaScale + 256) % 256;
                                }
                                lastScale = (nextScale == 0) ? lastScale : nextScale;
                            }
                        }
                    }
                }
            }

            br.readUe(); // log2_max_frame_num_minus4
            int picOrderCntType = br.readUe();
            if (picOrderCntType == 0) {
                br.readUe(); // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                br.readBits(1); // delta_pic_order_always_zero_flag
                br.readSe();    // offset_for_non_ref_pic
                br.readSe();    // offset_for_top_to_bottom_field
                int numRefFramesInCycle = br.readUe();
                if (numRefFramesInCycle > 255) return null;
                for (int i = 0; i < numRefFramesInCycle; i++) {
                    br.readSe();
                }
            } else if (picOrderCntType != 2) {
                return null;
            }

            br.readUe();    // max_num_ref_frames
            br.readBits(1); // gaps_in_frame_num_value_allowed_flag

            int picWidthInMbsMinus1 = br.readUe();
            int picHeightInMapUnitsMinus1 = br.readUe();
            int frameMbsOnlyFlag = br.readBits(1);
            if (frameMbsOnlyFlag == 0) {
                br.readBits(1); // mb_adaptive_frame_field_flag
            }
            br.readBits(1); // direct_8x8_inference_flag

            int frameCroppingFlag = br.readBits(1);
            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (frameCroppingFlag == 1) {
                cropLeft = br.readUe();
                cropRight = br.readUe();
                cropTop = br.readUe();
                cropBottom = br.readUe();
            }

            if (br.overflow) return null;

            int chromaArrayType = (separateColourPlaneFlag == 0) ? chromaFormatIdc : 0;
            int cropUnitX;
            int cropUnitY;
            if (chromaArrayType == 0) {
                cropUnitX = 1;
                cropUnitY = 2 - frameMbsOnlyFlag;
            } else {
                int subWidthC = (chromaFormatIdc == 3) ? 1 : 2;
                int subHeightC = (chromaFormatIdc == 1) ? 2 : 1;
                cropUnitX = subWidthC;
                cropUnitY = subHeightC * (2 - frameMbsOnlyFlag);
            }

            int width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * cropUnitX;
            int height = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16 - (cropTop + cropBottom) * cropUnitY;
            if (width < 160 || width > 8192 || height < 120 || height > 8192) {
                return null;
            }
            return new int[] { width, height };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class BitReader {
        private final byte[] buf;
        private final int totalBits;
        private int bitPos = 0;
        boolean overflow = false;

        BitReader(byte[] buf, int byteLen) {
            this.buf = buf;
            this.totalBits = byteLen * 8;
        }

        int readBit() {
            if (bitPos >= totalBits) {
                overflow = true;
                return 0;
            }
            int v = (buf[bitPos >>> 3] >>> (7 - (bitPos & 7))) & 1;
            bitPos++;
            return v;
        }

        int readBits(int n) {
            int val = 0;
            for (int i = 0; i < n; i++) {
                val = (val << 1) | readBit();
            }
            return val;
        }

        int readUe() {
            int zeros = 0;
            while (bitPos < totalBits && readBit() == 0 && zeros < 31) {
                zeros++;
            }
            if (zeros >= 31 || overflow) {
                overflow = true;
                return 0;
            }
            if (zeros == 0) return 0;
            return ((1 << zeros) - 1) + readBits(zeros);
        }

        int readSe() {
            int codeNum = readUe();
            int abs = (codeNum + 1) >>> 1;
            return ((codeNum & 1) != 0) ? abs : -abs;
        }
    }

    private static long queryHttpContentLength(String playUri) {
        HttpURLConnection headConn = null;
        try {
            headConn = (HttpURLConnection) new URL(playUri).openConnection();
            headConn.setConnectTimeout(800);
            headConn.setReadTimeout(800);
            headConn.setRequestMethod("HEAD");
            headConn.setRequestProperty("Connection", "close");
            if (headConn.getResponseCode() == 200) {
                return headConn.getContentLength();
            }
        } catch (Exception ignored) {
        } finally {
            if (headConn != null) {
                try { headConn.disconnect(); } catch (Exception ignored) {}
            }
        }
        return -1L;
    }

    private static int resolveTableStart(byte[] data, int pktStart, int length) {
        int pktEnd = Math.min(pktStart + 188, length);
        if (pktStart + 4 >= pktEnd) return -1;

        boolean pusi = (data[pktStart + 1] & 0x40) != 0;
        if (!pusi) return -1;
        int afc = (data[pktStart + 3] & 0x30) >> 4;
        if (afc == 0 || afc == 2) return -1;

        int payloadStart = pktStart + 4;
        if (afc == 3) {
            int afLen = data[pktStart + 4] & 0xFF;
            payloadStart = pktStart + 5 + afLen;
        }
        if (payloadStart >= pktEnd) return -1;

        int ptr = data[payloadStart] & 0xFF;
        int tableStart = payloadStart + 1 + ptr;
        if (tableStart >= pktEnd) return -1;
        return tableStart;
    }
}
