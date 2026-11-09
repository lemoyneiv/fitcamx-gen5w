package fitcamx.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Lightweight MPEG-TS Header Sniffer.
 * Inspects PAT (PID 0x0000) and PMT to determine elementary video stream codec:
 * - 0x24 = HEVC / H.265 (4K UHD 3840x2160 or 2K QHD 2560x1440)
 * - 0x1B = AVC / H.264 (1080p FHD 1920x1080)
 *
 * Operates on the first 4KB of data (~21 TS packets), executing in <20ms over Wi-Fi
 * and <1ms on local USB storage.
 */
public class TsHeaderSniffer {
    private static final String TAG = "TsHeaderSniffer";

    public static final int CODEC_UNKNOWN = 0;
    public static final int CODEC_AVC = 1;     // H.264 / 1080p (Stream type 0x1B)
    public static final int CODEC_HEVC = 2;    // H.265 / 4K / 2K (Stream type 0x24)

    private static final int BUFFER_SIZE = 4096;

    /**
     * Inspects a local file path or HTTP URL to determine video stream codec.
     * Blocks for a maximum of ~1500ms on network streams. Must be called off the UI thread.
     */
    public static int sniffCodec(String playUri) {
        if (playUri == null || playUri.isEmpty()) return CODEC_UNKNOWN;

        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead = 0;

        InputStream is = null;
        HttpURLConnection conn = null;
        try {
            if (playUri.startsWith("http://") || playUri.startsWith("https://")) {
                URL url = new URL(playUri);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Range", "bytes=0-" + (BUFFER_SIZE - 1));
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
                File file = new File(filePath);
                if (!file.exists() || !file.canRead()) return CODEC_UNKNOWN;

                is = new FileInputStream(file);
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

        if (bytesRead < 376) {
            DebugLogger.log(TAG, "Insufficient bytes read for TS header check: " + bytesRead);
            return CODEC_UNKNOWN;
        }

        return parseTsBuffer(buffer, bytesRead);
    }

    /**
     * Parses an in-memory buffer containing MPEG-TS packets to extract video codec stream type.
     */
    public static int parseTsBuffer(byte[] data, int length) {
        if (data == null || length < 376) return CODEC_UNKNOWN;

        // Locate sync byte 0x47 aligned to 188-byte packet boundary
        int syncOffset = -1;
        for (int i = 0; i <= length - 376; i++) {
            if (data[i] == 0x47 && data[i + 188] == 0x47) {
                syncOffset = i;
                break;
            }
        }

        if (syncOffset < 0) {
            DebugLogger.log(TAG, "MPEG-TS sync byte 0x47 boundary not found");
            return CODEC_UNKNOWN;
        }

        int pmtPid = -1;
        int maxPackets = (length - syncOffset) / 188;

        for (int pktIdx = 0; pktIdx < maxPackets; pktIdx++) {
            int pktStart = syncOffset + pktIdx * 188;
            if (data[pktStart] != 0x47) continue;

            // Transport Error Indicator (bit 7 of byte 1)
            if ((data[pktStart + 1] & 0x80) != 0) continue;

            boolean pusi = (data[pktStart + 1] & 0x40) != 0;
            int pid = ((data[pktStart + 1] & 0x1F) << 8) | (data[pktStart + 2] & 0xFF);

            // Adaptation field control: bits 4-5 of byte 3
            int afc = (data[pktStart + 3] & 0x30) >> 4;
            int payloadStart = pktStart + 4;
            if (afc == 2) {
                // Adaptation field only, no payload
                continue;
            } else if (afc == 3) {
                // Adaptation field followed by payload
                int afLen = data[pktStart + 4] & 0xFF;
                payloadStart = pktStart + 5 + afLen;
            }

            if (payloadStart >= pktStart + 188) continue;

            int tableStart = payloadStart;
            if (pusi) {
                int ptr = data[payloadStart] & 0xFF;
                tableStart = payloadStart + 1 + ptr;
            }

            if (tableStart + 3 >= pktStart + 188) continue;

            int tableId = data[tableStart] & 0xFF;

            // PAT check: PID 0x0000, table_id 0x00
            if (pid == 0x0000 && tableId == 0x00) {
                int sectionLen = ((data[tableStart + 1] & 0x0F) << 8) | (data[tableStart + 2] & 0xFF);
                int pos = tableStart + 8;
                int endPos = Math.min(pktStart + 188, tableStart + 3 + sectionLen - 4);
                while (pos + 4 <= endPos) {
                    int progNum = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                    int progPid = ((data[pos + 2] & 0x1F) << 8) | (data[pos + 3] & 0xFF);
                    if (progNum != 0) {
                        pmtPid = progPid;
                    }
                    pos += 4;
                }
            }
            // PMT check: pid == pmtPid, table_id 0x02
            else if (pmtPid != -1 && pid == pmtPid && tableId == 0x02) {
                int sectionLen = ((data[tableStart + 1] & 0x0F) << 8) | (data[tableStart + 2] & 0xFF);
                int progInfoLen = ((data[tableStart + 10] & 0x0F) << 8) | (data[tableStart + 11] & 0xFF);
                int esPos = tableStart + 12 + progInfoLen;
                int endPos = Math.min(pktStart + 188, tableStart + 3 + sectionLen - 4);

                while (esPos + 5 <= endPos) {
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
        }

        return CODEC_UNKNOWN;
    }
}
