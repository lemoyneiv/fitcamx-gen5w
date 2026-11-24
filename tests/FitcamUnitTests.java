import android.content.Context;
import android.net.DhcpInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import fitcamx.app.*;
import fitcamx.rearcam.BouncePolicy;
import fitcamx.rearcam.CarBridge;
import fitcamx.rearcam.PmapParser;
import fitcamx.rearcam.PosixBridge;
import fitcamx.rearcam.RearCamDaemon;
import fitcamx.rearcam.StandaloneInstaller;
import fitcamx.rearcam.StandaloneRootDaemon;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class FitcamUnitTests {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean cond) {
        if (cond) {
            System.out.println("  [PASS] " + name);
            passed++;
        } else {
            System.out.println("  [FAIL] " + name);
            failed++;
        }
    }

    private static int ipv4ToLittleEndianInt(int a, int b, int c, int d) {
        return (a & 0xFF) | ((b & 0xFF) << 8) | ((c & 0xFF) << 16) | ((d & 0xFF) << 24);
    }

    private static byte[] buildTsPacket(int pid, boolean pusi, int ptrField, byte[] section) {
        byte[] pkt = new byte[188];
        Arrays.fill(pkt, (byte) 0xFF);
        pkt[0] = 0x47;
        pkt[1] = (byte) (((pusi ? 0x40 : 0x00) | ((pid >> 8) & 0x1F)) & 0xFF);
        pkt[2] = (byte) (pid & 0xFF);
        pkt[3] = 0x10;
        int pos = 4;
        if (pusi && pos < 188) {
            pkt[pos++] = (byte) (ptrField & 0xFF);
            pos += (ptrField & 0xFF);
        }
        if (section != null && pos < 188 && pos >= 0) {
            int copyLen = Math.min(section.length, 188 - pos);
            if (copyLen > 0) {
                System.arraycopy(section, 0, pkt, pos, copyLen);
            }
        }
        return pkt;
    }

    private static byte[] buildPatSection(int pmtPid) {
        return new byte[] {
            0x00, (byte) 0xB0, 0x0D,
            0x00, 0x01, (byte) 0xC1, 0x00, 0x00,
            0x00, 0x01, (byte) (0xE0 | ((pmtPid >> 8) & 0x1F)), (byte) (pmtPid & 0xFF),
            0x00, 0x00, 0x00, 0x00
        };
    }

    private static byte[] buildPmtSection(int streamType) {
        return new byte[] {
            0x02, (byte) 0xB0, 0x12,
            0x00, 0x01, (byte) 0xC1, 0x00, 0x00,
            (byte) 0xE1, 0x00,
            (byte) 0xF0, 0x00,
            (byte) streamType, (byte) 0xE1, 0x00, (byte) 0xF0, 0x00,
            0x00, 0x00, 0x00, 0x00
        };
    }

    private static void testDebugLoggerRamOnly() throws Exception {
        System.out.println("--- 1. DebugLogger RAM-Only & eMMC Flash Preservation ---");
        final File tempDataDir = Files.createTempDirectory("fitcam_InternalData_").toFile();
        try {
            File legacy1 = new File(tempDataDir, "fitcamx_runtime.log");
            File legacy2 = new File(tempDataDir, "fitcamx_debug_log.txt");
            Files.write(legacy1.toPath(), "old log".getBytes(StandardCharsets.UTF_8));
            Files.write(legacy2.toPath(), "old backup".getBytes(StandardCharsets.UTF_8));

            Context fakeCtx = new Context() {
                @Override
                public File getFilesDir() {
                    return tempDataDir;
                }
            };

            DebugLogger.init(fakeCtx);
            for (int i = 0; i < 25; i++) {
                DebugLogger.log("TEST", "Log message #" + i);
            }

            File[] remaining = tempDataDir.listFiles();
            check("Deletes legacy fitcamx_runtime.log & fitcamx_debug_log.txt on init()",
                  !legacy1.exists() && !legacy2.exists());
            check("Writes ZERO files to internal /data storage during init() and log()",
                  remaining != null && remaining.length == 0);
            check("Retains log messages in bounded RAM ring buffer",
                  DebugLogger.getLogsAsString().contains("Log message #24"));
        } finally {
            File[] files = tempDataDir.listFiles();
            if (files != null) for (File f : files) f.delete();
            tempDataDir.delete();
        }
    }

    private static void testWifiBridgeSafety() {
        System.out.println("\n--- 2. WifiBridge SSID & Home-Router False-Positive Protection ---");
        check("Matches CAR_CAM_1234", WifiBridge.isMatchingSsid("\"CAR_CAM_1234\""));
        check("Matches custom JsKiaEV6FITCAMX", WifiBridge.isMatchingSsid("JsKiaEV6FITCAMX"));
        check("Matches NOVATEK_96670", WifiBridge.isMatchingSsid("NOVATEK_96670"));
        check("Rejects ordinary home Wi-Fi SSID", !WifiBridge.isMatchingSsid("\"SmithFamilyWiFi_5G\""));
        check("Rejects Android <unknown ssid> placeholder", !WifiBridge.isMatchingSsid("<unknown ssid>"));

        DhcpInfo homeRouter1 = new DhcpInfo();
        homeRouter1.ipAddress = ipv4ToLittleEndianInt(192, 168, 1, 100);
        homeRouter1.gateway = ipv4ToLittleEndianInt(192, 168, 1, 1);
        check("Rejects 192.168.1.1 home Wi-Fi router with non-dashcam SSID",
              !WifiBridge.isLikelyDashcamConnection("\"SmithFamilyWiFi_5G\"", homeRouter1));

        DhcpInfo homeRouter0 = new DhcpInfo();
        homeRouter0.ipAddress = ipv4ToLittleEndianInt(192, 168, 0, 55);
        homeRouter0.gateway = ipv4ToLittleEndianInt(192, 168, 0, 1);
        check("Rejects 192.168.0.1 home Wi-Fi router with non-dashcam SSID",
              !WifiBridge.isLikelyDashcamConnection("\"MySpectrumWiFi\"", homeRouter0));

        DhcpInfo novatekDhcp = new DhcpInfo();
        novatekDhcp.ipAddress = ipv4ToLittleEndianInt(192, 168, 1, 20);
        novatekDhcp.gateway = ipv4ToLittleEndianInt(192, 168, 1, 254);
        check("Accepts 192.168.1.254 Novatek gateway even when SSID is hidden/custom",
              WifiBridge.isLikelyDashcamConnection("\"MyCustomDashcam\"", novatekDhcp));

        check("Accepts matching FITCAMX SSID regardless of gateway subnet",
              WifiBridge.isLikelyDashcamConnection("\"FITCAMX_EV6\"", homeRouter1));
    }

    private static void testTsHeaderSniffer() throws Exception {
        System.out.println("\n--- 3. TsHeaderSniffer (MPEG-TS, M2TS, Bounds Safety, MP4/MOV Header & Tail-moov) ---");
        byte[] pat = buildTsPacket(0x0000, true, 0, buildPatSection(0x1000));
        byte[] pmtHevc = buildTsPacket(0x1000, true, 0, buildPmtSection(0x24));
        byte[] pmtAvc = buildTsPacket(0x1000, true, 0, buildPmtSection(0x1B));
        byte[] filler = buildTsPacket(0x0100, false, 0, new byte[180]);

        byte[] tsHevc3 = new byte[188 * 3];
        System.arraycopy(pat, 0, tsHevc3, 0, 188);
        System.arraycopy(pmtHevc, 0, tsHevc3, 188, 188);
        System.arraycopy(filler, 0, tsHevc3, 376, 188);
        check("3-packet MPEG-TS HEVC (0x24) detection",
              TsHeaderSniffer.parseTsBuffer(tsHevc3, tsHevc3.length) == TsHeaderSniffer.CODEC_HEVC);

        byte[] tsAvcReordered = new byte[188 * 3];
        // PMT arrives BEFORE PAT in stream capture -> two-pass PAT->PMT resolves cleanly
        System.arraycopy(pmtAvc, 0, tsAvcReordered, 0, 188);
        System.arraycopy(pat, 0, tsAvcReordered, 188, 188);
        System.arraycopy(filler, 0, tsAvcReordered, 376, 188);
        check("Two-pass MPEG-TS AVC (0x1B) detection when PMT precedes PAT",
              TsHeaderSniffer.parseTsBuffer(tsAvcReordered, tsAvcReordered.length) == TsHeaderSniffer.CODEC_AVC);

        // 192-byte M2TS container (4-byte TP_extra_header + 188-byte TS packet)
        byte[] m2tsHevc = new byte[192 * 3];
        System.arraycopy(pat, 0, m2tsHevc, 4, 188);
        System.arraycopy(pmtHevc, 0, m2tsHevc, 192 + 4, 188);
        System.arraycopy(filler, 0, m2tsHevc, 384 + 4, 188);
        check("192-byte M2TS stride HEVC detection",
              TsHeaderSniffer.parseTsBuffer(m2tsHevc, m2tsHevc.length) == TsHeaderSniffer.CODEC_HEVC);

        // Out-of-bounds pointer_field fuzz test (previously threw ArrayIndexOutOfBoundsException)
        boolean noBoundsException = true;
        for (int badPtr : new int[] { 175, 178, 182, 183, 184, 200, 255 }) {
            byte[] badPat = buildTsPacket(0x0000, true, badPtr, buildPatSection(0x1000));
            byte[] badPmt = buildTsPacket(0x1000, true, badPtr, buildPmtSection(0x24));
            byte[] fuzz = new byte[188 * 3];
            System.arraycopy(badPat, 0, fuzz, 0, 188);
            System.arraycopy(badPmt, 0, fuzz, 188, 188);
            System.arraycopy(filler, 0, fuzz, 376, 188);
            try {
                TsHeaderSniffer.parseTsBuffer(fuzz, fuzz.length);
            } catch (Throwable t) {
                noBoundsException = false;
            }
        }
        check("Bounds safety on malformed PAT/PMT pointer_field near/beyond 188B boundary", noBoundsException);

        // False 2-packet 0x47 sync bytes inside payload before true 3-packet sync offset
        byte[] dummyWith47 = buildTsPacket(0x0100, false, 0, new byte[180]);
        byte[] shifted = new byte[12 + 188 * 3];
        shifted[2] = 0x47;
        System.arraycopy(dummyWith47, 0, shifted, 12, 188);
        shifted[2 + 188] = 0x47; // payload coincidence 188B after byte 2, NOT repeated at +376
        System.arraycopy(pat, 0, shifted, 12 + 188, 188);
        System.arraycopy(pmtHevc, 0, shifted, 12 + 376, 188);
        check("3-packet sync lock ignores 2-byte payload 0x47 coincidence and locks true TS stream",
              TsHeaderSniffer.parseTsBuffer(shifted, shifted.length) == TsHeaderSniffer.CODEC_HEVC);

        // Faststart MP4 / MOV headers (ISO-BMFF VisualSampleEntry boxSize >= 86 with 6-byte zero reserved header)
        byte[] mp4Hevc = new byte[256];
        byte[] ftypHvc1 = "\0\0\0\u0018ftypisom\0\0\u0002\0isommp42\0\0\0\u0090moov\0\0\0\u0088trak\0\0\0\u0080stsd\0\0\0\0\0\0\0\u0001\0\0\0\u0070hvc1\0\0\0\0\0\0\0\u0001".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(ftypHvc1, 0, mp4Hevc, 0, ftypHvc1.length);
        check("Faststart MP4 (ftyp + hvc1) detected as CODEC_HEVC",
              TsHeaderSniffer.parseTsBuffer(mp4Hevc, mp4Hevc.length) == TsHeaderSniffer.CODEC_HEVC);

        byte[] movAvc1 = new byte[256];
        byte[] ftypAvc1 = "\0\0\0\u0014ftypqt  \0\0\u0002\0qt  \0\0\0\u0090moov\0\0\0\u0088trak\0\0\0\u0080stsd\0\0\0\0\0\0\0\u0001\0\0\0\u0070avc1\0\0\0\0\0\0\0\u0001".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(ftypAvc1, 0, movAvc1, 0, ftypAvc1.length);
        // Set 1920x1080 VisualSampleEntry width/height at offsets +28..+31 from 'avc1'
        int avc1Pos = ftypAvc1.length - 12;
        movAvc1[avc1Pos + 28] = (byte) ((1920 >>> 8) & 0xFF);
        movAvc1[avc1Pos + 29] = (byte) (1920 & 0xFF);
        movAvc1[avc1Pos + 30] = (byte) ((1080 >>> 8) & 0xFF);
        movAvc1[avc1Pos + 31] = (byte) (1080 & 0xFF);
        TsHeaderSniffer.MediaInfo info1080Avc = TsHeaderSniffer.parseMediaInfo(movAvc1, movAvc1.length);
        check("Faststart MOV (ftyp + avc1 1920x1080) detected as 1080p CODEC_AVC (isHighRes=false)",
              info1080Avc.codec == TsHeaderSniffer.CODEC_AVC
              && info1080Avc.width == 1920 && info1080Avc.height == 1080
              && !info1080Avc.isHighRes());

        // Jared's Ioniq 6 4K H.264 .MP4 case (avc1 at 3840x2160 and 2560x1440 -> isHighRes=true!)
        byte[] mp4Avc4k = movAvc1.clone();
        mp4Avc4k[avc1Pos + 28] = (byte) ((3840 >>> 8) & 0xFF);
        mp4Avc4k[avc1Pos + 29] = (byte) (3840 & 0xFF);
        mp4Avc4k[avc1Pos + 30] = (byte) ((2160 >>> 8) & 0xFF);
        mp4Avc4k[avc1Pos + 31] = (byte) (2160 & 0xFF);
        TsHeaderSniffer.MediaInfo info4kAvc = TsHeaderSniffer.parseMediaInfo(mp4Avc4k, mp4Avc4k.length);

        byte[] mp4Avc2k = movAvc1.clone();
        mp4Avc2k[avc1Pos + 28] = (byte) ((2560 >>> 8) & 0xFF);
        mp4Avc2k[avc1Pos + 29] = (byte) (2560 & 0xFF);
        mp4Avc2k[avc1Pos + 30] = (byte) ((1440 >>> 8) & 0xFF);
        mp4Avc2k[avc1Pos + 31] = (byte) (1440 & 0xFF);
        TsHeaderSniffer.MediaInfo info2kAvc = TsHeaderSniffer.parseMediaInfo(mp4Avc2k, mp4Avc2k.length);
        check("4K (3840x2160) & 2K (2560x1440) H.264 avc1 MP4/MOV enable high-res 0.33x keyframe profile",
              info4kAvc.codec == TsHeaderSniffer.CODEC_AVC
              && info4kAvc.width == 3840 && info4kAvc.height == 2160
              && info4kAvc.isHighRes()
              && "AVC 3840x2160".equals(info4kAvc.describe())
              && info2kAvc.width == 2560 && info2kAvc.height == 1440
              && info2kAvc.isHighRes());

        // MPEG-TS 0x1B H.264 with Annex-B High Profile SPS (3840x2160 vs cropped 1920x1080)
        // SPS 3840x2160: profile=100(0x64), compat=0, level=51(0x33), sps_id=0, chroma=1, luma=0, chroma_bd=0, qp=0, scale=0 (0xAC),
        // log2_fn=0, poc=0, log2_poc=0, ref=1, gaps=0, width_mbs_m1=239, height_mbs_m1=134, frame_only=1, direct8x8=1, crop=0
        byte[] sps4kRbsp = new byte[] {
            0x64, 0x00, 0x33, (byte) 0xAC, (byte) 0xE8, 0x03, (byte) 0xC0, 0x04, 0x3E, (byte) 0x80
        };
        // SPS 1920x1080: width_mbs_m1=119, height_mbs_m1=67 (1088), crop=1 (0,0,0,4 -> 1080)
        byte[] sps1080Rbsp = new byte[] {
            0x64, 0x00, 0x28, (byte) 0xAC, (byte) 0xE8, 0x07, (byte) 0x80, 0x22, 0x7E, 0x58
        };
        int[] dims4kSps = TsHeaderSniffer.parseH264SpsDimensions(sps4kRbsp, sps4kRbsp.length);
        int[] dims1080Sps = TsHeaderSniffer.parseH264SpsDimensions(sps1080Rbsp, sps1080Rbsp.length);

        byte[] pes4k = new byte[180];
        // PES header (00 00 01 E0 00 00 80 00 00) + Annex-B SPS (00 00 01 67 <rbsp>) + PPS (00 00 01 68)
        byte[] pesPrefix = new byte[] { 0, 0, 1, (byte) 0xE0, 0, 0, (byte) 0x80, 0, 0, 0, 0, 1, 0x67 };
        System.arraycopy(pesPrefix, 0, pes4k, 0, pesPrefix.length);
        System.arraycopy(sps4kRbsp, 0, pes4k, pesPrefix.length, sps4kRbsp.length);
        pes4k[pesPrefix.length + sps4kRbsp.length + 2] = 1;
        pes4k[pesPrefix.length + sps4kRbsp.length + 3] = 0x68;
        byte[] videoPkt4k = new byte[188];
        videoPkt4k[0] = 0x47;
        videoPkt4k[1] = 0x41; // PUSI=1, PID=0x0100 (matches buildPmtSection elementary_PID)
        videoPkt4k[2] = 0x00;
        videoPkt4k[3] = 0x10; // payload only
        System.arraycopy(pes4k, 0, videoPkt4k, 4, 180);

        byte[] tsAvc4k = new byte[188 * 3];
        System.arraycopy(pat, 0, tsAvc4k, 0, 188);
        System.arraycopy(pmtAvc, 0, tsAvc4k, 188, 188);
        System.arraycopy(videoPkt4k, 0, tsAvc4k, 376, 188);
        TsHeaderSniffer.MediaInfo ts4kInfo = TsHeaderSniffer.parseMediaInfo(tsAvc4k, tsAvc4k.length);
        check("MPEG-TS H.264 Annex-B SPS parses 3840x2160 (isHighRes=true) vs cropped 1920x1080 (isHighRes=false)",
              dims4kSps != null && dims4kSps[0] == 3840 && dims4kSps[1] == 2160
              && dims1080Sps != null && dims1080Sps[0] == 1920 && dims1080Sps[1] == 1080
              && ts4kInfo.codec == TsHeaderSniffer.CODEC_AVC
              && ts4kInfo.width == 3840 && ts4kInfo.height == 2160
              && ts4kInfo.isHighRes());

        // Non-faststart MP4 (ftyp + 150 KB mdat + tail moov with hev1) -> local file & HTTP Range fallback
        final byte[] nonFaststartMp4 = new byte[150 * 1024];
        byte[] headerBox = "\0\0\0\u0018ftypmp42\0\0\0\0mp42isom\0\u0002\u0040\0mdat".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(headerBox, 0, nonFaststartMp4, 0, headerBox.length);
        byte[] tailMoov = "\0\0\0\u0090moov\0\0\0\u0088trak\0\0\0\u0080stsd\0\0\0\0\0\0\0\u0001\0\0\0\u0070hev1\0\0\0\0\0\0\0\u0001".getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(tailMoov, 0, nonFaststartMp4, nonFaststartMp4.length - tailMoov.length - 128, tailMoov.length);

        File tmpMp4 = Files.createTempFile("fitcam tail moov ", ".MP4").toFile();
        try {
            Files.write(tmpMp4.toPath(), nonFaststartMp4);
            check("Local non-faststart MP4 (tail moov after 150KB mdat) detected via RandomAccessFile",
                  TsHeaderSniffer.sniffCodec(tmpMp4.getAbsolutePath()) == TsHeaderSniffer.CODEC_HEVC);
            String fileUriWithPct20 = tmpMp4.toURI().toString();
            check("file:// URI with percent-encoded spaces (%20) decoded and sniffed cleanly",
                  fileUriWithPct20.contains("%20")
                  && TsHeaderSniffer.sniffCodec(fileUriWithPct20) == TsHeaderSniffer.CODEC_HEVC);
        } finally {
            tmpMp4.delete();
        }

        HttpServer rangeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        rangeServer.createContext("/CARDV/MOVIE/TAIL.MP4", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String range = ex.getRequestHeaders().getFirst("Range");
                byte[] slice;
                if (range != null && range.startsWith("bytes=-")) {
                    int suffix = Integer.parseInt(range.substring("bytes=-".length()));
                    int start = Math.max(0, nonFaststartMp4.length - suffix);
                    slice = Arrays.copyOfRange(nonFaststartMp4, start, nonFaststartMp4.length);
                } else {
                    slice = Arrays.copyOfRange(nonFaststartMp4, 0, Math.min(8192, nonFaststartMp4.length));
                }
                ex.sendResponseHeaders(206, slice.length);
                OutputStream os = ex.getResponseBody();
                os.write(slice);
                os.close();
            }
        });
        // Server that rejects suffix Range (bytes=-65536) with 416, requiring HEAD + explicit Range: bytes=X-Y
        rangeServer.createContext("/CARDV/MOVIE/EXPLICIT_TAIL.MP4", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                    ex.getResponseHeaders().set("Content-Length", String.valueOf(nonFaststartMp4.length));
                    ex.sendResponseHeaders(200, -1);
                    ex.close();
                    return;
                }
                String range = ex.getRequestHeaders().getFirst("Range");
                if (range != null && range.startsWith("bytes=-")) {
                    ex.sendResponseHeaders(416, -1);
                    ex.close();
                    return;
                }
                if (range != null && range.startsWith("bytes=")) {
                    String spec = range.substring("bytes=".length());
                    int dash = spec.indexOf('-');
                    int start = Integer.parseInt(spec.substring(0, dash));
                    int end = Integer.parseInt(spec.substring(dash + 1));
                    byte[] slice = Arrays.copyOfRange(nonFaststartMp4, start, Math.min(nonFaststartMp4.length, end + 1));
                    ex.sendResponseHeaders(206, slice.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(slice);
                    os.close();
                    return;
                }
                byte[] head = Arrays.copyOfRange(nonFaststartMp4, 0, 8192);
                ex.sendResponseHeaders(200, head.length);
                OutputStream os = ex.getResponseBody();
                os.write(head);
                os.close();
            }
        });
        rangeServer.start();
        try {
            int rPort = rangeServer.getAddress().getPort();
            String url = "http://127.0.0.1:" + rPort + "/CARDV/MOVIE/TAIL.MP4";
            check("HTTP non-faststart MP4 detected via Range: bytes=-65536 fallback",
                  TsHeaderSniffer.sniffCodec(url) == TsHeaderSniffer.CODEC_HEVC);
            String explicitUrl = "http://127.0.0.1:" + rPort + "/CARDV/MOVIE/EXPLICIT_TAIL.MP4";
            check("HTTP non-faststart MP4 detected via HEAD + explicit Range: bytes=start-end when suffix Range returns 416",
                  TsHeaderSniffer.sniffCodec(explicitUrl) == TsHeaderSniffer.CODEC_HEVC);
        } finally {
            rangeServer.stop(0);
        }
    }

    private static void testClipItemAndXmlParser() {
        System.out.println("\n--- 4. ClipItem & XmlListParser ---");
        ClipItem customPortClip = new ClipItem("A:\\CARDV\\MOVIE\\20261108 120000A.TS", 104857600L, "2026/11/08 12:00:00", 32);
        String url3333 = customPortClip.getHttpUrl("192.168.1.254", false, 3333);
        String url80 = customPortClip.getHttpUrl("192.168.1.254", false, 80);
        String urlJieli = customPortClip.getHttpUrl("192.168.8.1", true, 8080);

        check("ClipItem honors custom Novatek HTTP port (:3333) and encodes spaces (%20)",
              "http://192.168.1.254:3333/CARDV/MOVIE/20261108%20120000A.TS".equals(url3333));
        check("ClipItem omits default port :80 cleanly",
              "http://192.168.1.254/CARDV/MOVIE/20261108%20120000A.TS".equals(url80));
        check("ClipItem builds JieLi :8080 URL cleanly",
              "http://192.168.8.1:8080/CARDV/MOVIE/20261108%20120000A.TS".equals(urlJieli));

        check("Rear filename detection accepts standard rear suffixes (B, _R, REAR, SUB, CH2)",
              ClipItem.isRearFilename("A:\\CARDV\\MOVIE\\20261108_120000B.TS", "20261108_120000B.TS")
              && ClipItem.isRearFilename("A:\\CARDV\\MOVIE\\20261108_120000_R.MP4", "20261108_120000_R.MP4")
              && ClipItem.isRearFilename("A:\\CARDV\\REAR\\20261108_120000.TS", "20261108_120000.TS")
              && ClipItem.isRearFilename("A:\\CARDV\\MOVIE\\SUB_0001.MOV", "SUB_0001.MOV"));

        check("Rear filename detection does NOT misclassify English words ending in R/B as rear",
              !ClipItem.isRearFilename("A:\\CARDV\\MOVIE\\CAR.MP4", "CAR.MP4")
              && !ClipItem.isRearFilename("A:\\CARDV\\MOVIE\\DRIVER.TS", "DRIVER.TS")
              && !ClipItem.isRearFilename("A:\\CARDV\\MOVIE\\OCTOBER.MP4", "OCTOBER.MP4")
              && !ClipItem.isRearFilename("A:\\CARDV\\MOVIE\\CLUB.MOV", "CLUB.MOV"));

        ClipItem lockedAttr1 = new ClipItem("A:\\CARDV\\MOVIE\\20261108_120000A.TS", 1000L, "2026/11/08 12:00:00", 1);
        ClipItem lockedRoDir = new ClipItem("A:\\CARDV\\RO\\20261108_120100A.TS", 1000L, "2026/11/08 12:01:00", 32);
        ClipItem lockedEmrEDir = new ClipItem("A:\\CARDV\\EMR_E\\20260710163910_089528B.TS", 1000L, "2026/07/10 16:39:10", 32);
        ClipItem unlockedNormal = new ClipItem("A:\\CARDV\\MOVIE\\20261108_120200A.TS", 1000L, "2026/11/08 12:02:00", 32);
        check("ClipItem detects FAT Read-Only attribute and RO/EMR/EMR_E directory lock flags",
              lockedAttr1.isLocked && lockedRoDir.isLocked && lockedEmrEDir.isLocked && !lockedEmrEDir.isFront && !unlockedNormal.isLocked);

        // XmlListParser sanitization (NUL bytes + bare ampersands)
        String dirtyXml = "<?xml version=\"1.0\"?>\0<LIST>\n" +
            "<ALLFile><File><NAME>R&D_01A.TS</NAME><FPATH>A:\\CARDV\\MOVIE\\R&D_01A.TS</FPATH><SIZE>10000</SIZE><TIME>2026/11/08 12:00:00</TIME><ATTR>32</ATTR></File></ALLFile>\n" +
            "<ALLFile><File><NAME>SAFE&amp;OK_02B.TS</NAME><FPATH>A:\\CARDV\\EMR\\SAFE&amp;OK_02B.TS</FPATH><SIZE>20000</SIZE><TIME>2026/11/08 12:01:00</TIME><ATTR>33</ATTR></File></ALLFile>\n" +
            "</LIST>";
        List<ClipItem> cleanParsed = XmlListParser.parseFileList(dirtyXml);
        check("XmlListParser sanitizes NUL bytes and bare '&' without breaking '&amp;'",
              cleanParsed.size() == 2 && cleanParsed.get(0).isLocked && !cleanParsed.get(0).isFront);

        // Mid-XML structural corruption (broken unclosed tag mid-list) -> regex recovery preserves pre- and post-error clips + RO folder
        String midBrokenXml = "<?xml version=\"1.0\"?>\n<LIST>\n" +
            "<ALLFile><File><NAME>20261108_120000A.TS</NAME><FPATH>A:\\CARDV\\MOVIE\\20261108_120000A.TS</FPATH><SIZE>150000000</SIZE><TIME>2026/11/08 12:00:00</TIME><ATTR>32</ATTR></File></ALLFile>\n" +
            "<ALLFile><File><NAME>CORRUPT_UNCLOSED_TAG\n" +
            "<ALLFile><File><NAME>20261108_120500A.TS</NAME><FPATH>A:\\CARDV\\RO\\20261108_120500A.TS</FPATH><SIZE>150000000</SIZE><TIME>2026/11/08 12:05:00</TIME><ATTR>33</ATTR></File></ALLFile>\n" +
            "</LIST>";
        List<ClipItem> recovered = XmlListParser.parseFileList(midBrokenXml);
        check("XmlListParser recovers remaining clips via regex after mid-XML structural error without losing RO lock",
              recovered.size() == 2 && recovered.get(0).filename.equals("20261108_120500A.TS") && recovered.get(0).isLocked);

        // Still photo support (.JPG / .PNG), CARDV/PHOTO URL mapping, and KB size formatting
        ClipItem barePhoto = new ClipItem("20261110_143000A.JPG", 420L * 1024L, "2026/11/10 14:30:00", 32);
        ClipItem rearPhoto = new ClipItem("A:\\CARDV\\PHOTO\\20261110_143005B.JPG", 380L * 1024L, "2026/11/10 14:30:05", 32);
        check("ClipItem classifies .JPG snapshots as isPhoto=true, preserves Front/Rear tag, and formats <1MB in KB",
              barePhoto.isPhoto && barePhoto.isFront
              && "http://192.168.1.254/CARDV/PHOTO/20261110_143000A.JPG".equals(barePhoto.getHttpUrl("192.168.1.254", false, 80))
              && "420 KB".equals(barePhoto.getFormattedSize())
              && rearPhoto.isPhoto && !rearPhoto.isFront);

        // Cross-folder identical basename preservation (MOVIE vs EMR)
        String crossFolderXml = "<LIST>" +
            "<ALLFile><File><NAME>20261108_120000A.TS</NAME><FPATH>A:\\CARDV\\MOVIE\\20261108_120000A.TS</FPATH><SIZE>100000</SIZE><TIME>2026/11/08 12:00:00</TIME><ATTR>32</ATTR></File></ALLFile>" +
            "<ALLFile><File><NAME>20261108_120000A.TS</NAME><FPATH>A:\\CARDV\\EMR\\20261108_120000A.TS</FPATH><SIZE>100000</SIZE><TIME>2026/11/08 12:00:05</TIME><ATTR>33</ATTR></File></ALLFile>" +
            "<ALLFile><File><NAME>20261108_120000A.TS</NAME><FPATH>A:\\CARDV\\EMR\\20261108_120000A.TS</FPATH><SIZE>100000</SIZE><TIME>2026/11/08 12:00:05</TIME><ATTR>33</ATTR></File></ALLFile>" +
            "</LIST>";
        List<ClipItem> crossItems = XmlListParser.parseFileList(crossFolderXml);
        check("XmlListParser preserves identical basenames across MOVIE and EMR while deduplicating identical paths",
              crossItems.size() == 2 && crossItems.get(0).isLocked && !crossItems.get(1).isLocked);

        // HTML directory index parsing for supplemental /CARDV/PHOTO/ fallback
        String photoHtml = "<html><body><a href=\"../\">../</a>" +
            "<a href=\"20261110_091522A.JPG\">20261110_091522A.JPG</a>" +
            "<a href=\"20261110_091522B.PNG\">20261110_091522B.PNG</a>" +
            "<a href=\"THUMB.TXT\">THUMB.TXT</a></body></html>";
        List<ClipItem> htmlPhotos = XmlListParser.parseFileList(photoHtml, "CARDV/PHOTO/");
        check("XmlListParser parses HTML <a href> photo directory listings and ignores non-media files",
              htmlPhotos.size() == 2 && htmlPhotos.get(0).isPhoto && htmlPhotos.get(1).isPhoto);

        // 120-item TimSort comparator transitivity stress test (valid, empty, malformed, and equal timestamps)
        boolean timSortTransitive = true;
        try {
            java.util.Random rng = new java.util.Random(42L);
            for (int trial = 0; trial < 10; trial++) {
                List<ClipItem> mixed = new ArrayList<ClipItem>();
                for (int i = 0; i < 120; i++) {
                    String t;
                    int mod = i % 5;
                    if (mod == 0) t = "";
                    else if (mod == 1) t = null;
                    else if (mod == 2) t = "2026/11/10 12:00:" + String.format("%02d", i % 60);
                    else if (mod == 3) t = "2026-11-10 12:00:15";
                    else t = "INVALID_TIME_" + (i % 7);
                    String folder = (i % 3 == 0) ? "A:\\CARDV\\PHOTO\\" : "A:\\CARDV\\MOVIE\\";
                    String ext = (i % 3 == 0) ? ".JPG" : ".TS";
                    mixed.add(new ClipItem(folder + "CLIP_" + (i % 25) + ext, i * 1024L, t, 32));
                }
                Collections.shuffle(mixed, rng);
                XmlListParser.sortClipsDescending(mixed);
                if (mixed.get(0).timeStr == null || mixed.get(0).timeStr.isEmpty()) {
                    timSortTransitive = false;
                }
            }
        } catch (Throwable sortErr) {
            timSortTransitive = false;
        }
        check("XmlListParser.sortClipsDescending satisfies strict TimSort total-order transitivity on 120 mixed items",
              timSortTransitive);
    }

    private static void testCameraClientProtocol() throws Exception {
        System.out.println("\n--- 5. CameraClient Serialization, Time Sync & Pre-Teardown Recording Safety ---");
        String liveXml = "<Function><Cmd>2019</Cmd><Status>0</Status><MovieLiveViewLink>rtsp://192.168.1.254/xxx.mov</MovieLiveViewLink></Function>";
        check("CameraClient.parseLiveViewUrl extracts RTSP link",
              "rtsp://192.168.1.254/xxx.mov".equals(CameraClient.parseLiveViewUrl(liveXml)));

        CameraClient ntkClient = new CameraClient("192.168.1.254");
        ntkClient.setSocType(CameraClient.SocType.NOVATEK, 80);
        List<String> ntkFrontUrls = ntkClient.getCandidateStreamUrls(true);
        Set<String> uniqueUrls = new HashSet<String>(ntkFrontUrls);
        boolean hasForeignJieli = false;
        for (String u : ntkFrontUrls) {
            if (u.contains(":8080")) hasForeignJieli = true;
        }
        check("Novatek getCandidateStreamUrls returns 4 unique URLs with zero JieLi :8080 probes",
              ntkFrontUrls.size() == 4 && uniqueUrls.size() == ntkFrontUrls.size() && !hasForeignJieli);
        ntkClient.shutdown();

        final List<String> receivedQueries = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger statusToReturn = new AtomicInteger(0);
        HttpServer camServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        camServer.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String path = ex.getRequestURI().getPath();
                String rawQuery = ex.getRequestURI().getRawQuery();
                receivedQueries.add(rawQuery != null ? rawQuery : path);
                byte[] resp;
                if ("/CARDV/PHOTO/".equals(path)) {
                    resp = "<html><a href=\"20261110_150000A.JPG\">20261110_150000A.JPG</a></html>".getBytes(StandardCharsets.UTF_8);
                } else if (rawQuery != null && rawQuery.contains("cmd=3015")) {
                    resp = ("<LIST><ALLFile><File><NAME>20261110_145900A.TS</NAME>"
                            + "<FPATH>A:\\CARDV\\MOVIE\\20261110_145900A.TS</FPATH>"
                            + "<SIZE>80000000</SIZE><TIME>2026/11/10 14:59:00</TIME><ATTR>32</ATTR></File></ALLFile></LIST>")
                            .getBytes(StandardCharsets.UTF_8);
                } else if (rawQuery != null && rawQuery.contains("cmd=2019")) {
                    resp = "<Function><Cmd>2019</Cmd><Status>0</Status><MovieLiveViewLink>rtsp://127.0.0.1/xxx.mov</MovieLiveViewLink></Function>"
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    resp = ("<Function><Status>" + statusToReturn.get() + "</Status></Function>").getBytes(StandardCharsets.UTF_8);
                }
                ex.sendResponseHeaders(200, resp.length);
                OutputStream os = ex.getResponseBody();
                os.write(resp);
                os.close();
            }
        });
        camServer.start();

        CameraClient mockCam = new CameraClient("127.0.0.1");
        mockCam.setSocType(CameraClient.SocType.NOVATEK, camServer.getAddress().getPort());
        try {
            final CountDownLatch syncLatch = new CountDownLatch(1);
            mockCam.syncCarTime(new CameraClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean r) { syncLatch.countDown(); }
                @Override public void onError(Exception e) { syncLatch.countDown(); }
            });
            boolean synced = syncLatch.await(3, TimeUnit.SECONDS);
            boolean has3005 = false, has3006 = false, hasBad3014 = false, hasRawSpace = false;
            synchronized (receivedQueries) {
                for (String q : receivedQueries) {
                    if (q.contains("cmd=3005&str=")) has3005 = true;
                    if (q.contains("cmd=3006&str=")) has3006 = true;
                    if (q.contains("cmd=3014")) hasBad3014 = true;
                    if (q.contains(" ")) hasRawSpace = true;
                }
            }
            check("syncCarTime sends cmd=3005 + cmd=3006 without malformed cmd=3014 or raw spaces",
                  synced && has3005 && has3006 && !hasBad3014 && !hasRawSpace);

            // Fast PIP switch check (first live start caches Movie mode + cmd=2019; second switch sends ONLY cmd=3028)
            final CountDownLatch live1Latch = new CountDownLatch(1);
            mockCam.prepareAndStartLiveView(CameraClient.VIEW_FRONT, new CameraClient.Callback<List<String>>() {
                @Override public void onSuccess(List<String> r) { live1Latch.countDown(); }
                @Override public void onError(Exception e) { live1Latch.countDown(); }
            });
            live1Latch.await(3, TimeUnit.SECONDS);
            receivedQueries.clear();
            final CountDownLatch live2Latch = new CountDownLatch(1);
            mockCam.prepareAndStartLiveView(CameraClient.VIEW_DUAL, new CameraClient.Callback<List<String>>() {
                @Override public void onSuccess(List<String> r) { live2Latch.countDown(); }
                @Override public void onError(Exception e) { live2Latch.countDown(); }
            });
            live2Latch.await(3, TimeUnit.SECONDS);
            check("Fast PIP view switch sends single non-disruptive cmd=3028&par=2 without pausing recording",
                  receivedQueries.size() == 1 && "custom=1&cmd=3028&par=2".equals(receivedQueries.get(0)));

            // Supplemental photo discovery during fetchClipList
            final CountDownLatch listLatch = new CountDownLatch(1);
            final List<ClipItem> fetchedItems = Collections.synchronizedList(new ArrayList<ClipItem>());
            mockCam.fetchClipList(new CameraClient.Callback<List<ClipItem>>() {
                @Override public void onSuccess(List<ClipItem> r) { fetchedItems.addAll(r); listLatch.countDown(); }
                @Override public void onError(Exception e) { listLatch.countDown(); }
            });
            listLatch.await(4, TimeUnit.SECONDS);
            boolean hasVideo = false, hasPhoto = false;
            for (ClipItem item : fetchedItems) {
                if (item.isPhoto) hasPhoto = true;
                else hasVideo = true;
            }
            check("fetchClipList returns both video clips and supplemental /CARDV/PHOTO/ snapshots",
                  fetchedItems.size() == 2 && hasVideo && hasPhoto);

            // Negative CGI <Status>-13</Status> rejection check
            statusToReturn.set(-13);
            final CountDownLatch negLatch = new CountDownLatch(1);
            final AtomicBoolean rejectedNegativeStatus = new AtomicBoolean(false);
            mockCam.setAudioEnabled(false, new CameraClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean r) { negLatch.countDown(); }
                @Override public void onError(Exception e) { rejectedNegativeStatus.set(true); negLatch.countDown(); }
            });
            negLatch.await(3, TimeUnit.SECONDS);
            check("CameraClient rejects negative CGI <Status>-13</Status> instead of reporting false success",
                  rejectedNegativeStatus.get());
            statusToReturn.set(0);

            receivedQueries.clear();
            final CountDownLatch teardownLatch = new CountDownLatch(1);
            final AtomicInteger queriesAtCallbackMoment = new AtomicInteger(0);
            mockCam.ensureRecordingBeforeTeardown(new Runnable() {
                @Override
                public void run() {
                    queriesAtCallbackMoment.set(receivedQueries.size());
                    teardownLatch.countDown();
                }
            }, 2500L);
            boolean finishedTeardown = teardownLatch.await(3, TimeUnit.SECONDS);
            check("ensureRecordingBeforeTeardown completes cmd=3001&par=1 -> cmd=2001&par=1 BEFORE running Wi-Fi disconnect callback",
                  finishedTeardown
                  && queriesAtCallbackMoment.get() == 2
                  && receivedQueries.get(0).equals("custom=1&cmd=3001&par=1")
                  && receivedQueries.get(1).equals("custom=1&cmd=2001&par=1"));
        } finally {
            mockCam.shutdown();
            camServer.stop(0);
        }

        // Unreachable camera watchdog test
        CameraClient deadCam = new CameraClient("127.0.0.1");
        deadCam.setSocType(CameraClient.SocType.NOVATEK, 1); // closed port 1 -> immediate ECONNREFUSED or watchdog
        try {
            final CountDownLatch wdLatch = new CountDownLatch(1);
            final AtomicInteger callCount = new AtomicInteger(0);
            deadCam.ensureRecordingBeforeTeardown(new Runnable() {
                @Override
                public void run() {
                    callCount.incrementAndGet();
                    wdLatch.countDown();
                }
            }, 400L);
            boolean wdDone = wdLatch.await(2, TimeUnit.SECONDS);
            Thread.sleep(500L); // ensure watchdog doesn't fire a second time
            check("ensureRecordingBeforeTeardown invokes teardown callback exactly ONCE even when camera is unreachable",
                  wdDone && callCount.get() == 1);
        } finally {
            deadCam.shutdown();
        }
    }

    private static void testUsbDownloaderAtomicity() throws Exception {
        System.out.println("\n--- 6. UsbDownloader Atomic .part Staging & Truncation Rejection ---");
        final byte[] validPayload = new byte[128 * 1024];
        for (int i = 0; i < validPayload.length; i++) {
            validPayload[i] = (byte) ((i * 31) & 0xFF);
        }

        HttpServer dlServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        dlServer.createContext("/CARDV/MOVIE/GOOD_01A.TS", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                ex.sendResponseHeaders(200, validPayload.length);
                OutputStream os = ex.getResponseBody();
                os.write(validPayload);
                os.close();
            }
        });
        dlServer.createContext("/CARDV/MOVIE/TRUNC_02A.TS", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                // Advertise 128 KB Content-Length, but drop TCP stream after 32 KB!
                ex.sendResponseHeaders(200, validPayload.length);
                OutputStream os = ex.getResponseBody();
                os.write(validPayload, 0, 32 * 1024);
                os.flush();
                ex.close();
            }
        });
        dlServer.start();

        File usbTempDir = Files.createTempDirectory("fitcam_usb_dl_").toFile();
        UsbDownloader downloader = new UsbDownloader();
        int port = dlServer.getAddress().getPort();

        try {
            // Case A: Complete atomic download
            ClipItem goodClip = new ClipItem("A:\\CARDV\\MOVIE\\GOOD_01A.TS", validPayload.length, "2026/11/08 12:00:00", 32);
            final CountDownLatch okLatch = new CountDownLatch(1);
            final AtomicReference<File> savedRef = new AtomicReference<File>();
            downloader.downloadClipToFolder(goodClip, "127.0.0.1", false, port, usbTempDir, new UsbDownloader.DownloadCallback() {
                @Override public void onProgress(int p, double s, long d, long t) {}
                @Override public void onComplete(File dest) { savedRef.set(dest); okLatch.countDown(); }
                @Override public void onError(Exception err) { okLatch.countDown(); }
            });
            boolean okDone = okLatch.await(4, TimeUnit.SECONDS);
            File expectedGood = new File(usbTempDir, "GOOD_01A.TS");
            File leftoverPartGood = new File(usbTempDir, "GOOD_01A.TS.part");
            check("Complete download commits exact file atomically and removes .part staging file",
                  okDone && savedRef.get() != null
                  && expectedGood.exists() && expectedGood.length() == validPayload.length
                  && !leftoverPartGood.exists()
                  && Arrays.equals(validPayload, Files.readAllBytes(expectedGood.toPath())));

            // Case B: Mid-transfer Wi-Fi drop / truncated stream
            ClipItem truncClip = new ClipItem("A:\\CARDV\\MOVIE\\TRUNC_02A.TS", validPayload.length, "2026/11/08 12:01:00", 32);
            final CountDownLatch errLatch = new CountDownLatch(1);
            final AtomicBoolean gotError = new AtomicBoolean(false);
            downloader.downloadClipToFolder(truncClip, "127.0.0.1", false, port, usbTempDir, new UsbDownloader.DownloadCallback() {
                @Override public void onProgress(int p, double s, long d, long t) {}
                @Override public void onComplete(File dest) { errLatch.countDown(); }
                @Override public void onError(Exception err) { gotError.set(true); errLatch.countDown(); }
            });
            boolean errDone = errLatch.await(4, TimeUnit.SECONDS);
            File corruptTarget = new File(usbTempDir, "TRUNC_02A.TS");
            File leftoverPartTrunc = new File(usbTempDir, "TRUNC_02A.TS.part");
            check("Mid-stream disconnect triggers onError, deletes .part, and leaves ZERO truncated .TS file on USB",
                  errDone && gotError.get() && !corruptTarget.exists() && !leftoverPartTrunc.exists());
        } finally {
            downloader.shutdown();
            dlServer.stop(0);
            File[] files = usbTempDir.listFiles();
            if (files != null) for (File f : files) f.delete();
            usbTempDir.delete();
        }
    }

    private static void testGalleryRatePolicy() {
        System.out.println("\n--- 7. Gallery Playback Speed Policy (4K HEVC vs 1080p AVC) ---");
        check("4K HEVC clips always default to 0.33x",
              Math.abs(VlcPlayerManager.getDefaultGalleryRate(true) - 0.33f) < 0.001f);
        check("1080p AVC clips always default to 1.0x",
              Math.abs(VlcPlayerManager.getDefaultGalleryRate(false) - 1.0f) < 0.001f);

        float hevcStep1 = VlcPlayerManager.getNextGalleryRate(0.33f, true);
        float hevcStep2 = VlcPlayerManager.getNextGalleryRate(hevcStep1, true);
        check("4K HEVC speed toggle cycles strictly 0.33x <-> 0.5x and never allows 1.0x",
              Math.abs(hevcStep1 - 0.5f) < 0.001f && Math.abs(hevcStep2 - 0.33f) < 0.001f);

        check("4K HEVC sanitizeGalleryRate rejects 1.0x and clamps back to 0.33x",
              Math.abs(VlcPlayerManager.sanitizeGalleryRate(1.0f, true) - 0.33f) < 0.001f
              && Math.abs(VlcPlayerManager.sanitizeGalleryRate(0.5f, true) - 0.5f) < 0.001f);

        float avcStep1 = VlcPlayerManager.getNextGalleryRate(1.0f, false);
        float avcStep2 = VlcPlayerManager.getNextGalleryRate(avcStep1, false);
        float avcStep3 = VlcPlayerManager.getNextGalleryRate(avcStep2, false);
        check("1080p AVC speed toggle cycles 1.0x -> 0.5x -> 0.33x -> 1.0x",
              Math.abs(avcStep1 - 0.5f) < 0.001f
              && Math.abs(avcStep2 - 0.33f) < 0.001f
              && Math.abs(avcStep3 - 1.0f) < 0.001f);
    }

    private static void testWifiFastConnectHelpers() {
        System.out.println("\n--- 8. WifiBridge Fast-Reconnect, Cipher Selection & Candidate Ordering ---");
        List<String> savedProfiles = Arrays.asList("\"Nuwave_IoT\"", "\"Office TV\"", "\"CAR_CAM_OTHER\"", "\"CAR_CAM_CM703_7bf6\"");
        check("choosePreferredSavedSsid prioritizes remembered vehicle camera over other saved networks",
              "CAR_CAM_CM703_7bf6".equals(WifiBridge.choosePreferredSavedSsid(savedProfiles, "CAR_CAM_CM703_7bf6")));
        check("choosePreferredSavedSsid falls back to saved dashcam pattern when remembered SSID is empty",
              "CAR_CAM_OTHER".equals(WifiBridge.choosePreferredSavedSsid(savedProfiles, "")));
        check("choosePreferredSavedSsid ignores non-dashcam saved networks when no camera profile exists",
              WifiBridge.choosePreferredSavedSsid(Arrays.asList("\"Nuwave_IoT\"", "\"Office TV\""), "") == null);

        List<String> scanWithTarget = Arrays.asList("Nuwave_IoT", "CAR_CAM_CM703_7bf6", "FITCAMX_ALT");
        check("findAlternateDashcamInScan preserves direct handshake when remembered camera is visible",
              WifiBridge.findAlternateDashcamInScan(scanWithTarget, "CAR_CAM_CM703_7bf6") == null);

        List<String> scanReplacedCam = Arrays.asList("Nuwave_IoT", "FITCAMX_ALT_8888", "Office TV");
        check("findAlternateDashcamInScan immediately switches when remembered camera is absent and new camera is visible",
              "FITCAMX_ALT_8888".equals(WifiBridge.findAlternateDashcamInScan(scanReplacedCam, "CAR_CAM_CM703_7bf6")));

        check("classifySecurityMode selects pure WPA2-CCMP for [WPA2-PSK-CCMP][ESS] and OPEN for [ESS]",
              WifiBridge.classifySecurityMode("[WPA2-PSK-CCMP][ESS]") == WifiBridge.SEC_WPA2_CCMP
              && WifiBridge.classifySecurityMode("[ESS]") == WifiBridge.SEC_OPEN
              && WifiBridge.classifySecurityMode("[WPA-PSK-TKIP+CCMP][WPA2-PSK-TKIP+CCMP][ESS]") == WifiBridge.SEC_WPA_MIXED);

        List<String> cands = WifiBridge.buildCandidateIps("192.168.1.254", true, "192.168.1.254", true);
        check("buildCandidateIps places remembered camera IP first without duplicate entries",
              !cands.isEmpty() && "192.168.1.254".equals(cands.get(0))
              && Collections.frequency(cands, "192.168.1.254") == 1);
    }

    private static void testScreenshotColorNormalizer() {
        System.out.println("\n--- 9. ScreenshotHelper TCC803x BGRA<->RGBA Detection & Channel Swap ---");
        int w = 40, h = 20;
        int[] img = new int[w * h];
        Arrays.fill(img, 0xFF151922); // dark sidebar background

        // Fill FITCAMX ROI [4..24, 2..12) with TCC803x BGRA-swapped orange pixels: R=157, G=95, B=21
        int swappedOrange = 0xFF000000 | (157 << 16) | (95 << 8) | 21;
        for (int y = 2; y < 12; y++) {
            for (int x = 4; x < 24; x++) {
                img[y * w + x] = swappedOrange;
            }
        }
        check("shouldSwapRedBlue detects TCC803x BGRA-swapped orange FITCAMX logo pixels",
              ScreenshotHelper.shouldSwapRedBlue(img, w, h, 4, 2, 24, 12));

        ScreenshotHelper.swapRedBlueInPlace(img);
        int corrected = img[5 * w + 10];
        int r = (corrected >>> 16) & 0xFF;
        int g = (corrected >>> 8) & 0xFF;
        int b = corrected & 0xFF;
        check("swapRedBlueInPlace restores exact true-blue RGB channels (21, 95, 157) while preserving alpha & green",
              ((corrected >>> 24) & 0xFF) == 0xFF && r == 21 && g == 95 && b == 157);
        check("shouldSwapRedBlue returns false on already true-blue RGBA screenshots (idempotent)",
              !ScreenshotHelper.shouldSwapRedBlue(img, w, h, 4, 2, 24, 12));

        // Verify theme-aware shouldSwapRedBlue across all 6 Material 3 curated palettes (including warm R>B palettes)
        boolean allThemesDetectedSwap = true;
        boolean allThemesIdempotentAfterSwap = true;
        for (ThemeManager.Palette p : ThemeManager.getPalettes()) {
            int[] themedImg = new int[w * h];
            Arrays.fill(themedImg, p.surfaceContainerLow);
            int expR = (p.primary >>> 16) & 0xFF;
            int expG = (p.primary >>> 8) & 0xFF;
            int expB = p.primary & 0xFF;
            int bgraSwappedGlyph = 0xFF000000 | (expB << 16) | (expG << 8) | expR;
            for (int y = 2; y < 12; y++) {
                for (int x = 4; x < 24; x++) {
                    themedImg[y * w + x] = bgraSwappedGlyph;
                }
            }
            if (!ScreenshotHelper.shouldSwapRedBlue(themedImg, w, h, 4, 2, 24, 12, p.primary)) {
                allThemesDetectedSwap = false;
            }
            ScreenshotHelper.swapRedBlueInPlace(themedImg);
            if (ScreenshotHelper.shouldSwapRedBlue(themedImg, w, h, 4, 2, 24, 12, p.primary)) {
                allThemesIdempotentAfterSwap = false;
            }
        }
        check("Theme-aware shouldSwapRedBlue detects BGRA swap across all 6 M3 themes (cool & warm R>B)",
              allThemesDetectedSwap);
        check("Theme-aware shouldSwapRedBlue is idempotent on normal RGBA across all 6 M3 themes",
              allThemesIdempotentAfterSwap);
    }

    private static void testGalleryBufferingAndSpinnerPolicy() {
        System.out.println("\n--- 10. VlcPlayerManager Gallery Buffering & Spinner Visibility Policy ---");
        check("Pre-Vout initial buffering (<100%) displays loading spinner",
              VlcPlayerManager.shouldShowSpinnerOnBuffering(0.0f, false)
              && VlcPlayerManager.shouldShowSpinnerOnBuffering(65.0f, false));
        check("Buffering at 100% never displays loading spinner",
              !VlcPlayerManager.shouldShowSpinnerOnBuffering(100.0f, false)
              && !VlcPlayerManager.shouldShowSpinnerOnBuffering(100.0f, true));
        check("Post-Vout minor HTTP TCP jitter (25%-99%) does NOT flash spinner over playing 1080p video",
              !VlcPlayerManager.shouldShowSpinnerOnBuffering(85.0f, true)
              && !VlcPlayerManager.shouldShowSpinnerOnBuffering(25.0f, true));
        check("Post-Vout seek or genuine cache underrun (<25%) displays spinner cleanly",
              VlcPlayerManager.shouldShowSpinnerOnBuffering(0.0f, true)
              && VlcPlayerManager.shouldShowSpinnerOnBuffering(15.0f, true));
        check("Active progress clears stuck spinner once >=400ms elapsed since last incomplete buffering event",
              !VlcPlayerManager.shouldClearSpinnerOnProgress(true, true, 1000L, 1200L)
              && VlcPlayerManager.shouldClearSpinnerOnProgress(true, true, 1000L, 1450L)
              && VlcPlayerManager.shouldClearSpinnerOnProgress(true, true, 0L, 1050L)
              && !VlcPlayerManager.shouldClearSpinnerOnProgress(true, false, 0L, 2000L));
    }

    private static void testMaterial3ThemeEngine() {
        System.out.println("\n--- 11. Material 3 Curated Theme Engine & WCAG Contrast Verification ---");
        ThemeManager.Palette[] palettes = ThemeManager.getPalettes();
        check("Curated M3 palette catalog contains exactly 6 themes with Ocean Blue default",
              palettes.length == 6 && ThemeManager.ID_OCEAN_BLUE.equals(ThemeManager.getDefaultPalette().id));

        // Verify full cyclic traversal via nextPaletteId
        Set<String> visited = new HashSet<String>();
        String cur = ThemeManager.ID_OCEAN_BLUE;
        for (int i = 0; i < palettes.length; i++) {
            visited.add(cur);
            cur = ThemeManager.nextPaletteId(cur);
        }
        check("nextPaletteId cycles cleanly through all 6 unique palettes and wraps back to Ocean Blue",
              visited.size() == 6 && ThemeManager.ID_OCEAN_BLUE.equals(cur));

        check("getPaletteById falls back safely to Ocean Blue on null or unknown ID",
              ThemeManager.ID_OCEAN_BLUE.equals(ThemeManager.getPaletteById(null).id)
              && ThemeManager.ID_OCEAN_BLUE.equals(ThemeManager.getPaletteById("nonexistent_theme").id));

        // WCAG contrast checks across all 6 palettes for automotive legibility
        boolean contrastOk = true;
        String worstPair = "";
        for (ThemeManager.Palette p : palettes) {
            double cSurface = ThemeManager.contrastRatio(p.onSurface, p.surface);
            double cPrimary = ThemeManager.contrastRatio(p.onPrimary, p.primary);
            double cSecondary = ThemeManager.contrastRatio(p.onSecondaryContainer, p.secondaryContainer);
            double cTertiary = ThemeManager.contrastRatio(p.onTertiaryContainer, p.tertiaryContainer);
            double cError = ThemeManager.contrastRatio(p.onErrorContainer, p.errorContainer);
            if (cSurface < 4.5 || cPrimary < 4.5 || cSecondary < 4.5 || cTertiary < 4.5 || cError < 4.5) {
                contrastOk = false;
                worstPair = p.id + " (surf=" + cSurface + ", prim=" + cPrimary + ", sec=" + cSecondary + ")";
            }
        }
        check("All 6 M3 palettes satisfy WCAG AA (>= 4.5:1) contrast across surface, primary, secondary, tertiary & error roles"
              + (worstPair.isEmpty() ? "" : " [" + worstPair + "]"),
              contrastOk);

        // State-layer blend & alpha helpers
        int alphaApplied = ThemeManager.withAlpha(0xFF9ECAFF, 128);
        int midBlend = ThemeManager.blendArgb(0xFF000000, 0xFFFFFFFF, 0.5f);
        check("ThemeManager.withAlpha & blendArgb compute accurate ARGB state-layer colors",
              alphaApplied == 0x809ECAFF
              && ((midBlend >>> 24) & 0xFF) == 0xFF
              && Math.abs(((midBlend >>> 16) & 0xFF) - 128) <= 1);
    }

    private static void testLiveWatchdogKeepaliveAndMirrorOptions() {
        System.out.println("\n--- 12. Live Stream Watchdog, Novatek Keepalive & Horizontal Mirror Options ---");
        check("isLiveStreamStalled ignores pre-playback state and healthy <1.2s intervals",
              VlcPlayerManager.LIVE_STALL_TIMEOUT_MS == 1200L
              && VlcPlayerManager.LIVE_WATCHDOG_POLL_MS == 250L
              && !VlcPlayerManager.isLiveStreamStalled(false, 1000L, 9000L, VlcPlayerManager.LIVE_STALL_TIMEOUT_MS)
              && !VlcPlayerManager.isLiveStreamStalled(true, 1000L, 2199L, VlcPlayerManager.LIVE_STALL_TIMEOUT_MS));
        check("isLiveStreamStalled detects frozen stream once >=1200ms elapse without RTP/frame progress",
              VlcPlayerManager.isLiveStreamStalled(true, 1000L, 2200L, VlcPlayerManager.LIVE_STALL_TIMEOUT_MS));

        check("didLiveStatsAdvance rejects all-zero initial stats and unchanged stalled counters while accepting real RTP/frame advances",
              !VlcPlayerManager.didLiveStatsAdvance(0, 0, 0, 0, 0, 0, 0, 0)
              && !VlcPlayerManager.didLiveStatsAdvance(0, 48000, 120, 118, 0, 48000, 120, 118)
              && VlcPlayerManager.didLiveStatsAdvance(0, 48000, 120, 118, 0, 52096, 120, 118)
              && VlcPlayerManager.didLiveStatsAdvance(0, 48000, 120, 118, 0, 48000, 121, 118)
              && VlcPlayerManager.didLiveStatsAdvance(0, 48000, 120, 118, 0, 48000, 120, 119)
              && VlcPlayerManager.didLiveStatsAdvance(1024, 0, 0, 0, 2048, 0, 0, 0));

        check("isLiveOpenTimedOut detects hung RTSP Opening state >=8500ms and ignores playing streams",
              !VlcPlayerManager.isLiveOpenTimedOut(false, 1000L, 9400L, VlcPlayerManager.LIVE_OPEN_TIMEOUT_MS)
              && VlcPlayerManager.isLiveOpenTimedOut(false, 1000L, 9500L, VlcPlayerManager.LIVE_OPEN_TIMEOUT_MS)
              && !VlcPlayerManager.isLiveOpenTimedOut(true, 1000L, 25000L, VlcPlayerManager.LIVE_OPEN_TIMEOUT_MS));

        List<String> stdEngineOpts = VlcPlayerManager.buildEngineOptions(false);
        List<String> mirrorEngineOpts = VlcPlayerManager.buildEngineOptions(true);
        check("buildEngineOptions(false) configures software NEON decode with --no-osd/--no-spu and omits transform filter",
              stdEngineOpts.contains("--rtsp-tcp")
              && stdEngineOpts.contains("--avcodec-hw=none")
              && stdEngineOpts.contains("--no-osd")
              && stdEngineOpts.contains("--no-spu")
              && !stdEngineOpts.contains("--video-filter=transform")
              && !stdEngineOpts.contains("--transform-type=hflip"));
        check("buildEngineOptions(true) configures LibVLC-level --video-filter=transform & --transform-type=hflip inherited by vout_thread_t",
              mirrorEngineOpts.contains("--video-filter=transform")
              && mirrorEngineOpts.contains("--transform-type=hflip")
              && mirrorEngineOpts.contains("--no-osd")
              && mirrorEngineOpts.contains("--no-spu"));

        List<String> mediaOpts = VlcPlayerManager.buildLiveMediaOptions(true);
        check("buildLiveMediaOptions configures 500ms TCP RTSP caching without dead input-level video-filter flags",
              mediaOpts.contains(":rtsp-tcp")
              && mediaOpts.contains(":network-caching=500")
              && mediaOpts.contains(":ipv4-timeout=5000")
              && !mediaOpts.contains(":video-filter=transform"));

        check("CameraClient.shouldSendKeepalive fires on 12s idle Novatek sessions outside teardown",
              CameraClient.KEEPALIVE_MIN_IDLE_MS == 12000L
              && CameraClient.shouldSendKeepalive(true, false, 0L, 5000L, CameraClient.KEEPALIVE_MIN_IDLE_MS)
              && !CameraClient.shouldSendKeepalive(true, false, 10000L, 21000L, CameraClient.KEEPALIVE_MIN_IDLE_MS)
              && CameraClient.shouldSendKeepalive(true, false, 10000L, 22500L, CameraClient.KEEPALIVE_MIN_IDLE_MS)
              && !CameraClient.shouldSendKeepalive(true, true, 1000L, 50000L, CameraClient.KEEPALIVE_MIN_IDLE_MS)
              && !CameraClient.shouldSendKeepalive(false, false, 1000L, 50000L, CameraClient.KEEPALIVE_MIN_IDLE_MS));

        check("Per-view mirror preference keys & live badge labels isolate Front, Rear, and Dual modes",
              "mirror_front".equals(MainActivity.getMirrorPrefKey(CameraClient.VIEW_FRONT))
              && "mirror_rear".equals(MainActivity.getMirrorPrefKey(CameraClient.VIEW_REAR))
              && "mirror_dual".equals(MainActivity.getMirrorPrefKey(CameraClient.VIEW_DUAL))
              && "REAR \u00B7 MIRROR (LIVE)".equals(MainActivity.formatLiveStreamBadge("REAR", true, "LIVE"))
              && "FRONT (LIVE)".equals(MainActivity.formatLiveStreamBadge("FRONT", false, "LIVE")));

        check("computeLiveRecoveryDelayMs & isSilentLiveRefresh run silent 50ms Tier-1 RTSP refresh before escalating",
              MainActivity.computeLiveRecoveryDelayMs(1) == 50L
              && MainActivity.computeLiveRecoveryDelayMs(2) == 400L
              && MainActivity.computeLiveRecoveryDelayMs(3) == 1000L
              && MainActivity.computeLiveRecoveryDelayMs(5) == 2500L
              && MainActivity.isSilentLiveRefresh(1)
              && !MainActivity.isSilentLiveRefresh(2));
    }

    private static void testWifiCustomPasswordAndManualFallback() {
        System.out.println("\n--- 13. WifiBridge Custom Password Validation & Manual SSID Fallback ---");
        check("isValidWifiPassword enforces 8..63 ASCII or 64-hex WPA PSK and allows empty on Open networks",
              WifiBridge.isValidWifiPassword("", WifiBridge.SEC_OPEN)
              && !WifiBridge.isValidWifiPassword("", WifiBridge.SEC_WPA2_CCMP)
              && !WifiBridge.isValidWifiPassword("1234567", WifiBridge.SEC_WPA2_CCMP)
              && WifiBridge.isValidWifiPassword("MyCustomCamPass!", WifiBridge.SEC_WPA2_CCMP)
              && WifiBridge.isValidWifiPassword("0123456789abcdef0123456789ABCDEF0123456789abcdef0123456789ABCDEF", WifiBridge.SEC_WPA2_CCMP)
              && !WifiBridge.isValidWifiPassword("0123456789abcdef0123456789ABCDEF0123456789abcdef0123456789ABCDEG", WifiBridge.SEC_WPA2_CCMP));

        String rawHex64 = "0123456789abcdef0123456789ABCDEF0123456789abcdef0123456789ABCDEF";
        check("formatPreSharedKey quotes ASCII passphrases safely while keeping 64-hex keys unquoted",
              "\"Custom8888\"".equals(WifiBridge.formatPreSharedKey("Custom8888"))
              && "\"Pass\\\"WithQuote\"".equals(WifiBridge.formatPreSharedKey("Pass\"WithQuote"))
              && rawHex64.equals(WifiBridge.formatPreSharedKey(rawHex64)));

        check("shouldTriggerAuthFallback triggers on explicit auth errors, WPA handshake drops, or exhausted retries",
              !WifiBridge.shouldTriggerAuthFallback(1, 1, 2)
              && WifiBridge.shouldTriggerAuthFallback(2, 0, 0)
              && WifiBridge.shouldTriggerAuthFallback(0, 2, 0)
              && WifiBridge.shouldTriggerAuthFallback(0, 0, WifiBridge.MAX_ASSOC_WATCHDOG_RETRIES));

        List<String> rawScan = Arrays.asList("Home_5G", "CoffeeShop", "CAR_CAM_CM703_7bf6", "MyCustomCam", "home_5g");
        List<String> ordered = WifiBridge.prioritizeSsidsForPicker(rawScan, "MyCustomCam", "");
        check("prioritizeSsidsForPicker deduplicates SSIDs and pins failed & dashcam SSIDs to the top",
              ordered.size() == 4
              && "MyCustomCam".equals(ordered.get(0))
              && "CAR_CAM_CM703_7bf6".equals(ordered.get(1)));
    }

    private static void testOemViewRotationAndGeometry() {
        System.out.println("\n--- 14. OEM Backup Camera View Rotation, Side-by-Side Geometry & Mirror Orientation ---");
        // Unsupported trims (e.g. 360 SVM type=6 -> hasOemRear=false): silently omit OEM views even if pref set
        check("computeAvailableViewModes silently hides OEM views on unsupported trims (single & dual Fitcamx)",
              Arrays.equals(new int[] { CameraClient.VIEW_FRONT },
                      MainActivity.computeAvailableViewModes(false, false, false))
              && Arrays.equals(new int[] { CameraClient.VIEW_FRONT },
                      MainActivity.computeAvailableViewModes(false, false, true))
              && Arrays.equals(new int[] { CameraClient.VIEW_FRONT, CameraClient.VIEW_DUAL, CameraClient.VIEW_REAR },
                      MainActivity.computeAvailableViewModes(true, false, true)));

        // Supported Gen5W trim (hasOemRear=true)
        int[] singleWithOem = new int[] {
            CameraClient.VIEW_FRONT, MainActivity.VIEW_OEM_DUAL, MainActivity.VIEW_OEM_REAR
        };
        int[] dualDefault = new int[] {
            CameraClient.VIEW_FRONT, CameraClient.VIEW_DUAL, CameraClient.VIEW_REAR
        };
        int[] dualWithExtraOem = new int[] {
            CameraClient.VIEW_FRONT, CameraClient.VIEW_DUAL, CameraClient.VIEW_REAR,
            MainActivity.VIEW_OEM_DUAL, MainActivity.VIEW_OEM_REAR
        };
        check("computeAvailableViewModes enables Side-by-Side & Car Backup for front-only Fitcamx and additively appends them when Advanced Option is enabled",
              Arrays.equals(singleWithOem, MainActivity.computeAvailableViewModes(false, true, false))
              && Arrays.equals(dualDefault, MainActivity.computeAvailableViewModes(true, true, false))
              && Arrays.equals(dualWithExtraOem, MainActivity.computeAvailableViewModes(true, true, true)));

        check("getNextViewMode cycles cleanly through all 5 additive views and falls back to FRONT when mode is absent",
              MainActivity.getNextViewMode(CameraClient.VIEW_FRONT, dualWithExtraOem) == CameraClient.VIEW_DUAL
              && MainActivity.getNextViewMode(CameraClient.VIEW_DUAL, dualWithExtraOem) == CameraClient.VIEW_REAR
              && MainActivity.getNextViewMode(CameraClient.VIEW_REAR, dualWithExtraOem) == MainActivity.VIEW_OEM_DUAL
              && MainActivity.getNextViewMode(MainActivity.VIEW_OEM_DUAL, dualWithExtraOem) == MainActivity.VIEW_OEM_REAR
              && MainActivity.getNextViewMode(MainActivity.VIEW_OEM_REAR, dualWithExtraOem) == CameraClient.VIEW_FRONT
              && MainActivity.getNextViewMode(MainActivity.VIEW_OEM_REAR, dualDefault) == CameraClient.VIEW_FRONT);

        check("getMirrorPrefKey & getViewModeLabel isolate VIEW_OEM_DUAL and VIEW_OEM_REAR settings",
              "mirror_oem_dual".equals(MainActivity.getMirrorPrefKey(MainActivity.VIEW_OEM_DUAL))
              && "mirror_oem_rear".equals(MainActivity.getMirrorPrefKey(MainActivity.VIEW_OEM_REAR))
              && "SIDE-BY-SIDE".equals(MainActivity.getViewModeLabel(MainActivity.VIEW_OEM_DUAL))
              && "BACKUP".equals(MainActivity.getViewModeLabel(MainActivity.VIEW_OEM_REAR)));

        int overlaySideBySide = MainActivity.computeOemOverlayX(1920, MainActivity.VIEW_OEM_DUAL);
        int overlayFullRear = MainActivity.computeOemOverlayX(1920, MainActivity.VIEW_OEM_REAR);
        int frontW1x = MainActivity.computeSideBySideFrontWidthPx(1438, 281, 1920);
        int frontW15x = MainActivity.computeSideBySideFrontWidthPx(1198, 421, 1920);
        check("computeOemOverlayX & computeSideBySideFrontWidthPx keep surface_live's RDMA0 hole strictly left of RDMA1 [1200..1920]",
              overlaySideBySide == 1200
              && overlayFullRear == 600
              && (281 + frontW1x) <= overlaySideBySide - 2
              && (421 + frontW15x) <= overlaySideBySide - 2
              && frontW1x >= 600 && frontW15x >= 500);

        float[] fit = OemRearView.computeFitBounds(800, 400, 720, 480);
        check("OemRearView flips raw mirror-sensor frames when Mirror=OFF and preserves 3:2 aspect ratio",
              OemRearView.shouldFlipRawSensorHorizontally(false)
              && !OemRearView.shouldFlipRawSensorHorizontally(true)
              && Math.abs(fit[0] - 100f) < 0.01f
              && Math.abs(fit[1] - 0f) < 0.01f
              && Math.abs(fit[2] - 700f) < 0.01f
              && Math.abs(fit[3] - 400f) < 0.01f);

        check("OemRearCameraClient.describeStatus maps owned, shared, reverse-gear, ignition-off, and error states",
              "Live Backup Camera".equals(OemRearCameraClient.describeStatus(OemRearCameraClient.STATUS_OWNED_LIVE))
              && "Live Backup Camera (Shared)".equals(OemRearCameraClient.describeStatus(OemRearCameraClient.STATUS_SHARED_LIVE))
              && "Reverse Camera Active".equals(OemRearCameraClient.describeStatus(OemRearCameraClient.STATUS_REVERSE))
              && "Vehicle Ignition Off".equals(OemRearCameraClient.describeStatus(OemRearCameraClient.STATUS_CAR_OFF))
              && "Starting Backup Camera...".equals(OemRearCameraClient.describeStatus(OemRearCameraClient.STATUS_STARTING))
              && "Backup Camera Unavailable".equals(OemRearCameraClient.describeStatus(OemRearCameraClient.STATUS_ERROR)));
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteTree(c);
        }
        f.delete();
    }

    private static void testPmapParserAndUsbDashcamScanner() throws Exception {
        System.out.println("\n--- 15. /proc/pmap Parser, Standalone USB Dashcam Gallery Scanner & Local USB Export ---");
        String samplePmap =
                "0x88000000 0x04400000 vmem\n" +
                "0x8c400000 0x00800000 rearcamera\n" +
                "0x8cc00000 0x01000000 fb_video\n";
        long[] region = PmapParser.parsePmapText(samplePmap, PmapParser.REGION_NAME);
        long[] missing = PmapParser.parsePmapText("0x88000000 0x04400000 vmem\n", PmapParser.REGION_NAME);
        check("PmapParser parses unsigned 32-bit physical rearcamera base/size on API 19 and validates 4-slot 720x480 ring capacity",
              region != null
              && region[0] == 0x8c400000L
              && region[1] == 0x00800000L
              && missing == null
              && PmapParser.fitsRing(region[1], 720, 480)
              && !PmapParser.fitsRing(4L * 1024L * 1024L, 720, 480));

        String sampleMounts =
                "tmpfs /mnt/shm tmpfs rw,nosuid,nodev,noexec,relatime,size=5120k,mode=777,uid=1000,gid=1003 0 0\n" +
                "/dev/block/platform/bdm/by-name/data /data ext4 rw,nosuid,nodev,noatime 0 0\n";
        check("CarBridge.isTmpfsMountPoint accepts Gen5W /mnt/shm RAM tmpfs and rejects ext4 /data",
              CarBridge.isTmpfsMountPoint(sampleMounts, "/mnt/shm")
              && !CarBridge.isTmpfsMountPoint(sampleMounts, "/data")
              && !CarBridge.isTmpfsMountPoint(sampleMounts, "/mnt/shm/sub"));

        File tempRoot = Files.createTempDirectory("fitcam_usb_dashcam_").toFile();
        File simShm = new File(tempRoot, "shm");
        File simData = new File(tempRoot, "data");
        simShm.mkdirs();
        simData.mkdirs();
        String simMounts =
                "tmpfs " + simShm.getAbsolutePath() + " tmpfs rw,mode=777,uid=1000 0 0\n" +
                "/dev/block/data " + simData.getAbsolutePath() + " ext4 rw 0 0\n";
        boolean wroteShm = CarBridge.writeRamMarkerFile(simShm, simMounts, CarBridge.RAM_PGS_MARKER_NAME, true);
        boolean seenShm = CarBridge.isRamMarkerFilePresent(simShm, simMounts, CarBridge.RAM_PGS_MARKER_NAME);
        boolean clearedShm = CarBridge.writeRamMarkerFile(simShm, simMounts, CarBridge.RAM_PGS_MARKER_NAME, false);
        boolean goneShm = !CarBridge.isRamMarkerFilePresent(simShm, simMounts, CarBridge.RAM_PGS_MARKER_NAME);
        boolean refusedExt4 = !CarBridge.writeRamMarkerFile(simData, simMounts, CarBridge.RAM_PGS_MARKER_NAME, true)
                && !new File(simData, CarBridge.RAM_PGS_MARKER_NAME).exists();
        check("CarBridge RAM PGS marker sets/clears cleanly on tmpfs and refuses to touch ext4 flash directories",
              wroteShm && seenShm && clearedShm && goneShm && refusedExt4);

        check("RearCamDaemon.parseExternalHeartbeatPid extracts live gen5-RearDashcam PID and rejects malformed inputs",
              RearCamDaemon.parseExternalHeartbeatPid("4120 88\n") == 4120
              && RearCamDaemon.parseExternalHeartbeatPid("995") == 995
              && RearCamDaemon.parseExternalHeartbeatPid("") == -1
              && RearCamDaemon.parseExternalHeartbeatPid(null) == -1
              && RearCamDaemon.parseExternalHeartbeatPid("0 1\n") == -1
              && RearCamDaemon.parseExternalHeartbeatPid("bad 12\n") == -1);

        String activeReversingStatus = "state=reversing\nenabled=true\ndrive=true\nout=/storage/usb0/Dashcam\n";
        String activeStartingStatus = "state=starting\nenabled=true\ndrive=true\nout=/storage/usb0/Dashcam\n";
        String activeRecordingStatus = "state=recording\nenabled=true\ndrive=true\nout=/storage/usb0/Dashcam\n";
        String activeLiveStatus = "state=live\nenabled=false\ndrive=false\n";
        String disabledStatus = "state=off\nenabled=false\ndrive=true\n";
        String noDriveStatus = "state=nodrive\nenabled=true\ndrive=false\n";
        String carOffStatus = "state=caroff\nenabled=true\ndrive=true\n";
        check("RearCamDaemon.shouldDeferToExternalRecorder yields during gen5-RearDashcam settle/recording and ignores dead/disabled/nodrive states",
              RearCamDaemon.shouldDeferToExternalRecorder(activeReversingStatus, true)
              && RearCamDaemon.shouldDeferToExternalRecorder(activeStartingStatus, true)
              && RearCamDaemon.shouldDeferToExternalRecorder(activeRecordingStatus, true)
              && RearCamDaemon.shouldDeferToExternalRecorder(activeLiveStatus, true)
              && !RearCamDaemon.shouldDeferToExternalRecorder(activeRecordingStatus, false)
              && !RearCamDaemon.shouldDeferToExternalRecorder(disabledStatus, true)
              && !RearCamDaemon.shouldDeferToExternalRecorder(noDriveStatus, true)
              && !RearCamDaemon.shouldDeferToExternalRecorder(carOffStatus, true));

        UsbDownloader localExporter = new UsbDownloader();
        try {
            File dashcamDir = new File(tempRoot, "Dashcam");
            File savedDir = new File(dashcamDir, "Saved");
            File snapDir = new File(dashcamDir, "Snapshots");
            File exportDir = new File(tempRoot, "ExportDest");
            savedDir.mkdirs();
            snapDir.mkdirs();
            exportDir.mkdirs();

            File statusFile = new File(tempRoot, "status");
            Files.write(statusFile.toPath(),
                    ("running=1\nout=" + dashcamDir.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));
            check("UsbDashcamScanner.readConfiguredOutDir parses custom out= path from status file",
                  dashcamDir.getAbsolutePath().equals(UsbDashcamScanner.readConfiguredOutDir(statusFile)));

            byte[] dummyTs = new byte[188 * 4];
            Arrays.fill(dummyTs, (byte) 0x47);
            Files.write(new File(dashcamDir, "20261111_142205.ts").toPath(), dummyTs);
            Files.write(new File(dashcamDir, "20261111_142305_1.ts").toPath(), dummyTs);
            Files.write(new File(dashcamDir, "20261111_142400.ts").toPath(), new byte[0]); // 0-byte incomplete -> ignored

            File savedClipFile = new File(savedDir, "20261111_142500.ts");
            Files.write(savedClipFile.toPath(), dummyTs);
            Files.write(new File(savedDir, "names.txt").toPath(),
                    "20261111_142500.ts\tNear Miss\n".getBytes(StandardCharsets.UTF_8));

            byte[] dummyPng = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
            Files.write(new File(snapDir, "snap_20261111_142610.png").toPath(), dummyPng);

            List<ClipItem> scanned = UsbDashcamScanner.scanDashcamDirectory(dashcamDir);
            XmlListParser.sortClipsDescending(scanned);

            ClipItem foundSaved = null;
            ClipItem foundSnap = null;
            boolean allOemRear = true;
            for (ClipItem c : scanned) {
                if (!c.isOemUsbClip || c.isFront) allOemRear = false;
                if ("20261111_142500.ts".equals(c.filename)) foundSaved = c;
                if ("snap_20261111_142610.png".equals(c.filename)) foundSnap = c;
            }
            check("UsbDashcamScanner discovers loop .ts, Saved/*.ts with names.txt event label, and Snapshots/*.png while skipping 0-byte files",
                  scanned.size() == 4
                  && allOemRear
                  && foundSaved != null
                  && foundSaved.isLocked
                  && !foundSaved.isPhoto
                  && "2026/11/11 14:25:00".equals(foundSaved.timeStr)
                  && "20261111_142500.ts \u00B7 Near Miss".equals(foundSaved.displayLabel)
                  && foundSnap != null
                  && foundSnap.isPhoto
                  && !foundSnap.isLocked
                  && "2026/11/11 14:26:10".equals(foundSnap.timeStr));

            // Verify atomic local copy via UsbDownloader without HTTP
            final CountDownLatch copyLatch = new CountDownLatch(1);
            final AtomicReference<File> copiedRef = new AtomicReference<File>(null);
            localExporter.downloadClipToFolder(foundSaved, "192.168.1.254", false, 80, exportDir,
                    new UsbDownloader.DownloadCallback() {
                        @Override public void onProgress(int p, double s, long d, long t) {}
                        @Override public void onComplete(File dest) { copiedRef.set(dest); copyLatch.countDown(); }
                        @Override public void onError(Exception e) { copyLatch.countDown(); }
                    });
            boolean copyDone = copyLatch.await(3, TimeUnit.SECONDS);
            File copiedFile = copiedRef.get();
            check("UsbDownloader atomically copies local USB Dashcam clip to destination folder offline",
                  copyDone && copiedFile != null && copiedFile.isFile() && copiedFile.length() == dummyTs.length);
        } finally {
            localExporter.shutdown();
            deleteTree(tempRoot);
        }
    }

    private static void testFormatSdCardAndRearProbe() throws Exception {
        System.out.println("\n--- 16. Non-Disruptive Rear Channel Probe & Format SD Card Recovery Sequence ---");
        check("inferRearChannelFromStatusXml detects dual-cam index>=6 with 3028 (TRUE), front-only 4K index 6 missing/-256 3028 (FALSE), and ambiguous single index (null)",
              Boolean.TRUE.equals(CameraClient.inferRearChannelFromStatusXml(
                      "<Function><Cmd>2002</Cmd><Status>6</Status><Cmd>3028</Cmd><Status>0</Status></Function>"))
              && Boolean.FALSE.equals(CameraClient.inferRearChannelFromStatusXml(
                      "<Function><Cmd>2002</Cmd><Status>6</Status><Cmd>2001</Cmd><Status>1</Status></Function>"))
              && Boolean.FALSE.equals(CameraClient.inferRearChannelFromStatusXml(
                      "<Function><Cmd>2002</Cmd><Status>6</Status><Cmd>3028</Cmd><Status>-256</Status></Function>"))
              && Boolean.FALSE.equals(CameraClient.inferRearChannelFromStatusXml(
                      "<Function><Cmd>2002</Cmd><Status>0</Status><Cmd>2001</Cmd><Status>1</Status></Function>"))
              && CameraClient.inferRearChannelFromStatusXml(
                      "<Function><Cmd>2002</Cmd><Status>0</Status><Cmd>3028</Cmd><Status>0</Status></Function>") == null);

        final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
        final AtomicBoolean disableMovieDirHtml = new AtomicBoolean(false);
        final AtomicBoolean serveRearInMovieDir = new AtomicBoolean(false);
        final AtomicInteger rear3028Status = new AtomicInteger(-13);
        final AtomicInteger activeMode = new AtomicInteger(1); // 1 = Movie, 2 = Playback/System
        final AtomicInteger formatCalls = new AtomicInteger(0);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String path = ex.getRequestURI().getPath();
                String query = ex.getRequestURI().getRawQuery();
                requests.add(query != null ? query : path);
                if ("/CARDV/MOVIE/".equals(path) && disableMovieDirHtml.get()) {
                    byte[] nf = "Not Found".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(404, nf.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(nf);
                    os.close();
                    return;
                }
                byte[] resp;
                if ("/CARDV/MOVIE/".equals(path)) {
                    String html = serveRearInMovieDir.get()
                            ? "<html><a href=\"20261111_120000A.TS\">A</a><a href=\"20261111_120000B.TS\">B</a></html>"
                            : "<html><a href=\"20261111_120000A.TS\">A1</a><a href=\"20261111_120100A.TS\">A2</a></html>";
                    resp = html.getBytes(StandardCharsets.UTF_8);
                } else if (query != null && query.contains("cmd=3028&par=1")) {
                    resp = ("<Function><Cmd>3028</Cmd><Status>" + rear3028Status.get() + "</Status></Function>")
                            .getBytes(StandardCharsets.UTF_8);
                } else if (query != null && query.contains("cmd=3001&par=2")) {
                    activeMode.set(2);
                    resp = "<Function><Status>0</Status></Function>".getBytes(StandardCharsets.UTF_8);
                } else if (query != null && query.contains("cmd=3001&par=1")) {
                    activeMode.set(1);
                    resp = "<Function><Status>0</Status></Function>".getBytes(StandardCharsets.UTF_8);
                } else if (query != null && query.contains("cmd=3010&par=1")) {
                    formatCalls.incrementAndGet();
                    // Simulate NT96675 firmware that returns -13 until switched to mode 2
                    int st = (activeMode.get() == 2) ? 0 : -13;
                    resp = ("<Function><Cmd>3010</Cmd><Status>" + st + "</Status></Function>").getBytes(StandardCharsets.UTF_8);
                } else {
                    resp = "<Function><Status>0</Status></Function>".getBytes(StandardCharsets.UTF_8);
                }
                ex.sendResponseHeaders(200, resp.length);
                OutputStream os = ex.getResponseBody();
                os.write(resp);
                os.close();
            }
        });
        server.start();

        CameraClient cam = new CameraClient("127.0.0.1");
        cam.setSocType(CameraClient.SocType.NOVATEK, server.getAddress().getPort());
        try {
            // 1. Front-only non-disruptive HTTP movie index probe
            final CountDownLatch p1Latch = new CountDownLatch(1);
            final AtomicReference<Boolean> p1Res = new AtomicReference<Boolean>(Boolean.TRUE);
            cam.probeRearChannelNonDisruptive(new CameraClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean r) { p1Res.set(r); p1Latch.countDown(); }
                @Override public void onError(Exception e) { p1Latch.countDown(); }
            });
            p1Latch.await(3, TimeUnit.SECONDS);
            boolean sentStopRecDuringProbe = false;
            synchronized (requests) {
                for (String q : requests) {
                    if (q.contains("cmd=2001")) sentStopRecDuringProbe = true;
                }
            }
            check("probeRearChannelNonDisruptive detects front-only Fitcamx (Boolean.FALSE) without stopping loop recording",
                  Boolean.FALSE.equals(p1Res.get()) && !sentStopRecDuringProbe);

            // 2. Dual-channel non-disruptive HTTP movie index probe
            serveRearInMovieDir.set(true);
            final CountDownLatch p2Latch = new CountDownLatch(1);
            final AtomicReference<Boolean> p2Res = new AtomicReference<Boolean>(null);
            cam.probeRearChannelNonDisruptive(new CameraClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean r) { p2Res.set(r); p2Latch.countDown(); }
                @Override public void onError(Exception e) { p2Latch.countDown(); }
            });
            p2Latch.await(3, TimeUnit.SECONDS);
            check("probeRearChannelNonDisruptive detects dual-channel Fitcamx (Boolean.TRUE) when *B.TS rear clips exist",
                  Boolean.TRUE.equals(p2Res.get()));

            // 2b. Disabled HFS HTML directory index -> cmd=3028&par=1 fallback + immediate cmd=3028&par=0 restore
            disableMovieDirHtml.set(true);
            rear3028Status.set(-13);
            requests.clear();
            final CountDownLatch p3Latch = new CountDownLatch(1);
            final AtomicReference<Boolean> p3Res = new AtomicReference<Boolean>(Boolean.TRUE);
            cam.probeRearChannelNonDisruptive(new CameraClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean r) { p3Res.set(r); p3Latch.countDown(); }
                @Override public void onError(Exception e) { p3Latch.countDown(); }
            });
            p3Latch.await(3, TimeUnit.SECONDS);
            boolean restoredFront3028 = false;
            synchronized (requests) {
                for (String q : requests) {
                    if (q.contains("cmd=3028&par=0")) restoredFront3028 = true;
                }
            }
            check("probeRearChannelNonDisruptive falls back to cmd=3028&par=1 when HFS HTML index is disabled and restores cmd=3028&par=0",
                  Boolean.FALSE.equals(p3Res.get()) && restoredFront3028);

            // 3. Format SD Card with -13 Playback-mode retry + automatic Movie/recording resumption
            requests.clear();
            final CountDownLatch fmtLatch = new CountDownLatch(1);
            final AtomicBoolean fmtOk = new AtomicBoolean(false);
            cam.formatSdCard(new CameraClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean r) { fmtOk.set(Boolean.TRUE.equals(r)); fmtLatch.countDown(); }
                @Override public void onError(Exception e) { fmtLatch.countDown(); }
            });
            boolean finished = fmtLatch.await(6, TimeUnit.SECONDS);
            int lastFmtIdx = -1, resumeModeIdx = -1, resumeRecIdx = -1;
            synchronized (requests) {
                for (int i = 0; i < requests.size(); i++) {
                    String q = requests.get(i);
                    if (q.contains("cmd=3010&par=1")) lastFmtIdx = i;
                    if (lastFmtIdx >= 0 && i > lastFmtIdx && q.contains("cmd=3001&par=1")) resumeModeIdx = i;
                    if (lastFmtIdx >= 0 && i > lastFmtIdx && q.contains("cmd=2001&par=1")) resumeRecIdx = i;
                }
            }
            check("formatSdCard retries cmd=3010&par=1 via Playback mode on -13 and automatically resumes Movie mode + loop recording",
                  finished && fmtOk.get() && formatCalls.get() == 2
                  && lastFmtIdx > 0 && resumeModeIdx > lastFmtIdx && resumeRecIdx > resumeModeIdx);
        } finally {
            cam.shutdown();
            server.stop(0);
        }
    }

    private static void testStandaloneNemesisAndRootDaemon() throws Exception {
        System.out.println("\n--- 17. Adaptive Standalone NEMESIS Installer & @fitcamx.root LFD1 Daemon ---");

        // 1. LFD1 wire protocol round-trip between HmgapidClient and StandaloneRootDaemon
        byte[] encodedReq = HmgapidClient.encodeRequest("service-start", "rearcam");
        String[] decodedArgs = StandaloneRootDaemon.decodeRequest(new ByteArrayInputStream(encodedReq));
        ByteArrayOutputStream respBuf = new ByteArrayOutputStream();
        StandaloneRootDaemon.writeResponse(respBuf, "started\n", 0);
        StringBuilder capturedOut = new StringBuilder();
        int parsedRc = HmgapidClient.readResponse(
                new ByteArrayInputStream(respBuf.toByteArray()), null, capturedOut);
        check("LFD1 request/response wire framing round-trips identically between HmgapidClient and StandaloneRootDaemon",
              decodedArgs != null
              && Arrays.equals(new String[] { "service-start", "rearcam" }, decodedArgs)
              && parsedRc == 0
              && "started\n".equals(capturedOut.toString())
              && StandaloneRootDaemon.decodeRequest(new ByteArrayInputStream(new byte[0])) == null);

        // 2. StandaloneRootDaemon UID authorization & fixed service allowlist
        final List<String> startedServices = new ArrayList<String>();
        final AtomicBoolean rebootTriggered = new AtomicBoolean(false);
        final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
        StandaloneRootDaemon.ServiceLauncher mockLauncher = new StandaloneRootDaemon.ServiceLauncher() {
            @Override
            public int startService(String serviceName, StringBuilder output) {
                startedServices.add(serviceName);
                output.append("ok:").append(serviceName).append('\n');
                return 0;
            }

            @Override
            public int triggerReboot(StringBuilder output) {
                rebootTriggered.set(true);
                output.append("restarting\n");
                return 0;
            }

            @Override
            public void requestShutdown() {
                shutdownRequested.set(true);
            }
        };

        int appUid = 10188;
        int unauthUid = 10999;
        StringBuilder msg = new StringBuilder();
        int rcPingUnauth = StandaloneRootDaemon.dispatchCommand(new String[] { "ping" }, unauthUid, appUid, mockLauncher, msg);
        int rcWhoamiUnauth = StandaloneRootDaemon.dispatchCommand(new String[] { "whoami" }, unauthUid, appUid, mockLauncher, new StringBuilder());
        int rcSvcUnauth = StandaloneRootDaemon.dispatchCommand(new String[] { "service-start", "rearcam" }, unauthUid, appUid, mockLauncher, new StringBuilder());
        StringBuilder whoamiOut = new StringBuilder();
        int rcWhoamiApp = StandaloneRootDaemon.dispatchCommand(new String[] { "whoami" }, appUid, appUid, mockLauncher, whoamiOut);
        int rcRearcamApp = StandaloneRootDaemon.dispatchCommand(new String[] { "service-start", "rearcam" }, appUid, appUid, mockLauncher, new StringBuilder());
        int rcShotApp = StandaloneRootDaemon.dispatchCommand(new String[] { "service-restart", "screenshot" }, appUid, appUid, mockLauncher, new StringBuilder());
        int rcBadSvc = StandaloneRootDaemon.dispatchCommand(new String[] { "service-start", "arbitrary" }, appUid, appUid, mockLauncher, new StringBuilder());
        int rcStopApp = StandaloneRootDaemon.dispatchCommand(new String[] { "stop" }, appUid, appUid, mockLauncher, new StringBuilder());
        boolean shutAfterAppStop = shutdownRequested.get();
        int rcRebootApp = StandaloneRootDaemon.dispatchCommand(new String[] { "reboot" }, appUid, appUid, mockLauncher, new StringBuilder());
        int rcStopRoot = StandaloneRootDaemon.dispatchCommand(new String[] { "stop" }, 0, appUid, mockLauncher, new StringBuilder());

        check("StandaloneRootDaemon enforces peer UID authorization, blocks unknown ops/services, and restricts stop to uid=0",
              rcPingUnauth == 0
              && rcWhoamiUnauth == 13
              && rcSvcUnauth == 13
              && rcWhoamiApp == 0 && "fitcamx-gen5w\n".equals(whoamiOut.toString())
              && rcRearcamApp == 0 && rcShotApp == 0
              && Arrays.equals(new String[] { "rearcam", "screenshot" }, startedServices.toArray(new String[0]))
              && rcBadSvc == 2
              && rcStopApp == 13 && !shutAfterAppStop
              && rcRebootApp == 0 && rebootTriggered.get()
              && rcStopRoot == 0 && shutdownRequested.get());

        // 3. featurexml.cfg line commenting & uncommenting
        String stockCfg = "-/system/etc/permissions/com.hkmc.foo.xml\n-/system/etc/permissions/android.hardware.wifi.xml\n";
        String commentedCfg = StandaloneInstaller.commentOutWifiFeatureLine(stockCfg);
        String alreadyCommented = StandaloneInstaller.commentOutWifiFeatureLine(commentedCfg);
        String restoredCfg = StandaloneInstaller.uncommentWifiFeatureLine(commentedCfg);
        check("StandaloneInstaller comments & uncomments android.hardware.wifi.xml in featurexml.cfg byte-for-byte",
              commentedCfg != null
              && commentedCfg.contains("#-/system/etc/permissions/android.hardware.wifi.xml\n")
              && alreadyCommented == null
              && stockCfg.equals(restoredCfg));

        // 4. Full StandaloneInstaller lifecycle (Stock install -> Update -> Uninstall + Tilepad coexistence + HMG-API migration)
        File tempRoot = Files.createTempDirectory("fitcam_standalone_").toFile();
        try {
            // Create synthetic signed mod zip with Fitcamx.apk + svc scripts
            File modZip = new File(tempRoot, "package.zip");
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(modZip));
            zos.putNextEntry(new ZipEntry("app/Fitcamx.apk"));
            zos.write("FAKE_APK_PAYLOAD_V110".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("svc/screenshot.sh"));
            zos.write("#!/system/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("svc/reboot.sh"));
            zos.write("#!/system/bin/sh\n/system/bin/reboot\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.close();

            // 4a. Stock Gen5W unit (no android.hardware.wifi.xml, uncommented featurexml.cfg, persist.wifi.state=1)
            File fakeSysRoot = new File(tempRoot, "unit_stock");
            StandaloneInstaller.Env envStock = new StandaloneInstaller.Env(fakeSysRoot.getAbsolutePath(), true);
            File cfgFile = envStock.sysFile("/system/onebin/neev_us/featurexml.cfg");
            cfgFile.getParentFile().mkdirs();
            Files.write(cfgFile.toPath(), stockCfg.getBytes(StandardCharsets.UTF_8));
            envStock.sysFile("/system/etc/permissions").mkdirs();
            envStock.setprop("ro.mobis.onebindir", "neev_us");
            envStock.setprop("persist.wifi.state", "1");
            envStock.setprop("sys.boot_completed", "0"); // Test deferred APK install during early boot first!

            File pkgDir = envStock.sysFile("/data/nemesis/apps/fitcamx.gen5w");
            pkgDir.mkdirs();
            Files.copy(modZip.toPath(), new File(pkgDir, "mod_tmp.zip").toPath());
            new File(pkgDir, "mod").mkdirs();
            new File(pkgDir, "mod_tmp.zip").renameTo(new File(pkgDir, "mod/package.zip"));

            boolean restart1 = StandaloneInstaller.installStandalone(envStock, pkgDir, new File(pkgDir, "mod/package.zip"));
            boolean wifiXmlCreated = envStock.sysFile(StandaloneInstaller.WIFI_XML_REL).isFile();
            boolean pendingWhileDown = new File(pkgDir, "standalone/apk.pending").isFile()
                    && !envStock.isPackageInstalled("fitcamx.app");

            // Boot completes -> ensureAppInstalled installs APK and clears apk.pending
            envStock.setprop("sys.boot_completed", "1");
            StandaloneInstaller.ensureAppInstalled(envStock, pkgDir);
            boolean installedAfterBoot = envStock.isPackageInstalled("fitcamx.app")
                    && !new File(pkgDir, "standalone/apk.pending").isFile();

            // Idempotent update keeps backups intact and requires no second restart
            boolean restart2 = StandaloneInstaller.installStandalone(envStock, pkgDir, new File(pkgDir, "mod/package.zip"));

            // Migrate-to-HMG-API preserves /data/data/fitcamx.app user preferences while restoring /system backups
            File userPref = envStock.sysFile("/data/data/fitcamx.app/shared_prefs/fitcamx.xml");
            userPref.getParentFile().mkdirs();
            Files.write(userPref.toPath(), "<map/>".getBytes(StandardCharsets.UTF_8));
            StandaloneInstaller.uninstallStandalone(envStock, pkgDir, true);
            boolean migratedCleanly = !envStock.sysFile(StandaloneInstaller.WIFI_XML_REL).exists()
                    && stockCfg.equals(new String(Files.readAllBytes(cfgFile.toPath()), StandardCharsets.UTF_8))
                    && "1".equals(envStock.getprop("persist.wifi.state"))
                    && !envStock.backupDir().exists()
                    && !envStock.isPackageInstalled("fitcamx.app")
                    && userPref.isFile();

            // Reinstall & full uninstall removes app data too
            StandaloneInstaller.installStandalone(envStock, pkgDir, new File(pkgDir, "mod/package.zip"));
            StandaloneInstaller.uninstallStandalone(envStock, pkgDir, false);
            boolean fullUninstallClean = !envStock.sysFile(StandaloneInstaller.WIFI_XML_REL).exists()
                    && stockCfg.equals(new String(Files.readAllBytes(cfgFile.toPath()), StandardCharsets.UTF_8))
                    && "1".equals(envStock.getprop("persist.wifi.state"))
                    && !userPref.exists();

            check("StandaloneInstaller enables Wi-Fi, handles early-boot deferred APK install, migrates cleanly to HMG-API, and restores stock /system byte-for-byte",
                  restart1 && wifiXmlCreated && pendingWhileDown && installedAfterBoot
                  && !restart2 && migratedCleanly && fullUninstallClean);

            // 4b. Pre-existing Tilepad unit (android.hardware.wifi.xml already present, featurexml.cfg already commented, persist.wifi.state=3)
            File tilepadRoot = new File(tempRoot, "unit_tilepad");
            StandaloneInstaller.Env envTile = new StandaloneInstaller.Env(tilepadRoot.getAbsolutePath(), true);
            File tileWifiXml = envTile.sysFile(StandaloneInstaller.WIFI_XML_REL);
            tileWifiXml.getParentFile().mkdirs();
            Files.write(tileWifiXml.toPath(), StandaloneInstaller.WIFI_XML_CONTENT.getBytes(StandardCharsets.UTF_8));
            File tileCfg = envTile.sysFile("/system/onebin/cvev_us/featurexml.cfg");
            tileCfg.getParentFile().mkdirs();
            Files.write(tileCfg.toPath(), commentedCfg.getBytes(StandardCharsets.UTF_8));
            envTile.setprop("ro.mobis.onebindir", "cvev_us");
            envTile.setprop("persist.wifi.state", "3");
            envTile.setprop("sys.boot_completed", "1");

            File tilePkgDir = envTile.sysFile("/data/nemesis/apps/fitcamx.gen5w");
            tilePkgDir.mkdirs();
            boolean tileRestart = StandaloneInstaller.installStandalone(envTile, tilePkgDir, modZip);
            StandaloneInstaller.uninstallStandalone(envTile, tilePkgDir, false);

            check("StandaloneInstaller preserves pre-existing Tilepad Wi-Fi client configuration on uninstall",
                  !tileRestart
                  && tileWifiXml.isFile()
                  && commentedCfg.equals(new String(Files.readAllBytes(tileCfg.toPath()), StandardCharsets.UTF_8))
                  && "3".equals(envTile.getprop("persist.wifi.state")));
        } finally {
            deleteTree(tempRoot);
        }
    }

    private static void test4kLivePreviewFallbackAndSinglePassResolution() throws Exception {
        System.out.println("\n--- 18. Single-ISP 4K Paused-Recording Live Preview & Single-Pass Resolution ---");

        check("isImmediate4kRecordingStall matches 0-frame & <5000ms pre-recording ring-buffer stalls only in unverified 4K recording mode",
              MainActivity.isImmediate4kRecordingStall(false, true, false, false, 1370L)
              && MainActivity.isImmediate4kRecordingStall(false, true, false, true, 3120L)
              && !MainActivity.isImmediate4kRecordingStall(false, true, false, true, 5000L)
              && !MainActivity.isImmediate4kRecordingStall(false, true, false, true, 8500L)
              && !MainActivity.isImmediate4kRecordingStall(true, true, false, false, 1200L)
              && !MainActivity.isImmediate4kRecordingStall(false, false, false, false, 1200L)
              && !MainActivity.isImmediate4kRecordingStall(false, true, true, false, 1200L));

        check("shouldFallbackToPaused4kLivePreview triggers at 2 consecutive immediate 4K stalls and respects guards",
              !MainActivity.shouldFallbackToPaused4kLivePreview(false, true, false, false, 1)
              && MainActivity.shouldFallbackToPaused4kLivePreview(false, true, false, false, 2)
              && !MainActivity.shouldFallbackToPaused4kLivePreview(true, true, false, false, 3)
              && !MainActivity.shouldFallbackToPaused4kLivePreview(false, false, false, false, 3)
              && !MainActivity.shouldFallbackToPaused4kLivePreview(false, true, true, false, 3)
              && !MainActivity.shouldFallbackToPaused4kLivePreview(false, true, false, true, 3));

        final List<String> queries = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger stopRecAttempts = new AtomicInteger(0);
        final AtomicBoolean slowStartRecFor4k = new AtomicBoolean(false);
        final AtomicBoolean startRecDelivered = new AtomicBoolean(false);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String q = ex.getRequestURI().getRawQuery();
                if (q != null) {
                    queries.add(q);
                }
                byte[] resp;
                if (q != null && q.contains("cmd=2001&par=0")) {
                    int n = stopRecAttempts.incrementAndGet();
                    int st = (n == 1) ? -13 : 0;
                    resp = ("<Function><Cmd>2001</Cmd><Status>" + st + "</Status></Function>")
                            .getBytes(StandardCharsets.UTF_8);
                } else if (q != null && q.contains("cmd=3028")) {
                    resp = "<Function><Cmd>3028</Cmd><Status>-256</Status></Function>"
                            .getBytes(StandardCharsets.UTF_8);
                } else if (q != null && q.contains("cmd=2019")) {
                    resp = "<Function><Cmd>2019</Cmd><Status>0</Status><MovieLiveViewLink>rtsp://127.0.0.1/xxx.mov</MovieLiveViewLink></Function>"
                            .getBytes(StandardCharsets.UTF_8);
                } else if (q != null && q.contains("cmd=2001&par=1")) {
                    if (slowStartRecFor4k.get()) {
                        try {
                            Thread.sleep(1350L);
                        } catch (InterruptedException ignored) {}
                    }
                    startRecDelivered.set(true);
                    resp = "<Function><Cmd>2001</Cmd><Status>0</Status></Function>"
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    resp = "<Function><Status>0</Status></Function>".getBytes(StandardCharsets.UTF_8);
                }
                ex.sendResponseHeaders(200, resp.length);
                OutputStream os = ex.getResponseBody();
                os.write(resp);
                os.close();
            }
        });
        server.start();

        CameraClient cam = new CameraClient("127.0.0.1");
        cam.setSocType(CameraClient.SocType.NOVATEK, server.getAddress().getPort());
        try {
            // 1. Paused-recording 4K Live Preview: retries -13 on 2001&par=0, learns -256 on 3028, starts 2015&par=1, omits 2001&par=1
            final CountDownLatch l1 = new CountDownLatch(1);
            final AtomicReference<List<String>> urls1 = new AtomicReference<List<String>>();
            cam.prepareAndStartLiveView(CameraClient.VIEW_FRONT, false, new CameraClient.Callback<List<String>>() {
                @Override public void onSuccess(List<String> r) { urls1.set(r); l1.countDown(); }
                @Override public void onError(Exception e) { l1.countDown(); }
            });
            boolean ok1 = l1.await(5, TimeUnit.SECONDS);
            int stopCount1 = 0;
            int ch3028Count1 = 0;
            boolean startedLive1 = false;
            boolean resumedRec1 = false;
            synchronized (queries) {
                for (String q : queries) {
                    if (q.contains("cmd=2001&par=0")) stopCount1++;
                    if (q.contains("cmd=3028")) ch3028Count1++;
                    if (q.contains("cmd=2015&par=1")) startedLive1 = true;
                    if (q.contains("cmd=2001&par=1")) resumedRec1 = true;
                }
            }
            check("prepareAndStartLiveView(..., false) retries Status -13 on stop-rec, starts 2015&par=1, and omits 2001&par=1",
                  ok1 && urls1.get() != null && !urls1.get().isEmpty()
                  && "rtsp://127.0.0.1/xxx.mov".equals(urls1.get().get(0))
                  && stopCount1 == 2 && ch3028Count1 == 1 && startedLive1 && !resumedRec1);

            // 2. Subsequent resumeRecording=true call skips unsupported cmd=3028 and resumes cmd=2001&par=1
            queries.clear();
            final CountDownLatch l2 = new CountDownLatch(1);
            cam.prepareAndStartLiveView(CameraClient.VIEW_FRONT, true, new CameraClient.Callback<List<String>>() {
                @Override public void onSuccess(List<String> r) { l2.countDown(); }
                @Override public void onError(Exception e) { l2.countDown(); }
            });
            boolean ok2 = l2.await(5, TimeUnit.SECONDS);
            boolean sent3028SecondTime = false;
            boolean resumedRec2 = false;
            synchronized (queries) {
                for (String q : queries) {
                    if (q.contains("cmd=3028")) sent3028SecondTime = true;
                    if (q.contains("cmd=2001&par=1")) resumedRec2 = true;
                }
            }
            check("prepareAndStartLiveView(..., true) skips cached unsupported cmd=3028 and resumes cmd=2001&par=1",
                  ok2 && !sent3028SecondTime && resumedRec2);

            // 3. Single-pass setVideoResolution(6, true) skips redundant 2015&par=1 and 2001&par=1
            queries.clear();
            final CountDownLatch l3 = new CountDownLatch(1);
            final AtomicBoolean resOk = new AtomicBoolean(false);
            cam.setVideoResolution(6, true, new CameraClient.Callback<Boolean>() {
                @Override public void onSuccess(Boolean r) { resOk.set(Boolean.TRUE.equals(r)); l3.countDown(); }
                @Override public void onError(Exception e) { l3.countDown(); }
            });
            boolean ok3 = l3.await(5, TimeUnit.SECONDS);
            boolean sentRes6 = false;
            boolean sentRedundantRestart = false;
            synchronized (queries) {
                for (String q : queries) {
                    if (q.contains("cmd=2002&par=6")) sentRes6 = true;
                    if (q.contains("cmd=2015&par=1") || q.contains("cmd=2001&par=1")) sentRedundantRestart = true;
                }
            }
            check("setVideoResolution(6, true) sets 4K index without redundant encoder/recording restart before startLiveStream()",
                  ok3 && resOk.get() && sentRes6 && !sentRedundantRestart);

            // 4. ensureRecordingBeforeTeardown(..., 2200L) waits cleanly across ~1.35s 4K .TS segment open
            queries.clear();
            startRecDelivered.set(false);
            slowStartRecFor4k.set(true);
            final CountDownLatch l4 = new CountDownLatch(1);
            cam.ensureRecordingBeforeTeardown(new Runnable() {
                @Override
                public void run() {
                    l4.countDown();
                }
            }, 2200L);
            boolean ok4 = l4.await(4, TimeUnit.SECONDS);
            cam.clearTeardownFlag();
            check("ensureRecordingBeforeTeardown(..., 2200L) completes 1.35s 4K .TS segment open before teardown",
                  ok4 && startRecDelivered.get());
        } finally {
            cam.shutdown();
            server.stop(0);
        }
    }

    private static void testLive555BridgeCm7053dwAndBouncePolicy() throws Exception {
        System.out.println("\n--- 19. Live555 Interface IPv4 Binding, CM7053DW Dual Detection & Connections Bounce Policy ---");

        // 1. Live555Bridge IPv4 network-byte-order conversions & safety filters
        int expected33 = ipv4ToLittleEndianInt(192, 168, 1, 33); // 0x2101a8c0
        int expected254 = ipv4ToLittleEndianInt(192, 168, 1, 254); // 0xfe01a8c0
        check("Live555Bridge converts wlan0 IPv4 addresses to network-byte-order in_addr_t matching DhcpInfo.ipAddress",
              Live555Bridge.ipv4StringToNetOrderInt("192.168.1.33") == expected33
              && Live555Bridge.ipv4StringToNetOrderInt(" 192.168.1.254 ") == expected254
              && "192.168.1.33".equals(Live555Bridge.netOrderIntToIpv4String(expected33))
              && "192.168.1.254".equals(Live555Bridge.netOrderIntToIpv4String(expected254)));

        check("Live555Bridge rejects wildcard, loopback, broadcast, and malformed IPv4 strings so INADDR_ANY fallback remains safe",
              Live555Bridge.ipv4StringToNetOrderInt(null) == 0
              && Live555Bridge.ipv4StringToNetOrderInt("") == 0
              && Live555Bridge.ipv4StringToNetOrderInt("0.0.0.0") == 0
              && Live555Bridge.ipv4StringToNetOrderInt("127.0.0.1") == 0
              && Live555Bridge.ipv4StringToNetOrderInt("255.255.255.255") == 0
              && Live555Bridge.ipv4StringToNetOrderInt("192.168.1.999") == 0
              && Live555Bridge.ipv4StringToNetOrderInt("192.168.1") == 0
              && Live555Bridge.ipv4BytesToNetOrderInt(null) == 0
              && Live555Bridge.ipv4BytesToNetOrderInt(new byte[] { 127, 0, 0, 1 }) == 0);

        check("VlcPlayerManager.buildEngineOptions enables --verbose=1 for field warning diagnostics",
              VlcPlayerManager.buildEngineOptions(false).contains("--verbose=1")
              && VlcPlayerManager.buildEngineOptions(true).contains("--verbose=1"));

        // 2. CM7053DW dual-channel status XML vs Jared/Toaster front-only 4K status XML
        String drathoCm7053dwXml =
                "<Function><Cmd>2002</Cmd><Status>1</Status>" +
                "<Cmd>2004</Cmd><Status>1</Status>" +
                "<Cmd>2007</Cmd><Status>1</Status>" +
                "<Cmd>3038</Cmd><Status>0</Status></Function>";
        String cm7053dw4kXml =
                "<Function><Cmd>2002</Cmd><Status>6</Status><Cmd>3038</Cmd><Status>0</Status></Function>";
        String jaredFrontOnlyXml =
                "<Function><Cmd>2002</Cmd><Status>8</Status><Cmd>2007</Cmd><Status>1</Status></Function>";
        String toasterFrontOnlyXml =
                "<Function><Cmd>2002</Cmd><Status>6</Status><Cmd>2007</Cmd><Status>1</Status></Function>";

        check("inferRearChannelFromStatusXml preserves rear probe on CM7053DW (3038 / verified 3028) while rejecting Jared & Toaster single-cam XMLs",
              CameraClient.inferRearChannelFromStatusXml(drathoCm7053dwXml, false) == null
              && CameraClient.inferRearChannelFromStatusXml(drathoCm7053dwXml, true) == null
              && Boolean.TRUE.equals(CameraClient.inferRearChannelFromStatusXml(cm7053dw4kXml, false))
              && Boolean.TRUE.equals(CameraClient.inferRearChannelFromStatusXml(toasterFrontOnlyXml, true))
              && Boolean.FALSE.equals(CameraClient.inferRearChannelFromStatusXml(jaredFrontOnlyXml, false))
              && Boolean.FALSE.equals(CameraClient.inferRearChannelFromStatusXml(toasterFrontOnlyXml, false)));

        // Verify CameraClient.isChannelSelect3028Verified() tracks Status=0 on cmd=3028&par=0
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String q = ex.getRequestURI().getRawQuery();
                byte[] resp;
                if (q != null && q.contains("cmd=3028")) {
                    resp = "<Function><Cmd>3028</Cmd><Status>0</Status></Function>".getBytes(StandardCharsets.UTF_8);
                } else if (q != null && q.contains("cmd=2019")) {
                    resp = "<Function><Cmd>2019</Cmd><Status>0</Status><MovieLiveViewLink>rtsp://127.0.0.1/xxx.mov</MovieLiveViewLink></Function>"
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    resp = "<Function><Status>0</Status></Function>".getBytes(StandardCharsets.UTF_8);
                }
                ex.sendResponseHeaders(200, resp.length);
                OutputStream os = ex.getResponseBody();
                os.write(resp);
                os.close();
            }
        });
        server.start();
        CameraClient cam = new CameraClient("127.0.0.1");
        cam.setSocType(CameraClient.SocType.NOVATEK, server.getAddress().getPort());
        try {
            check("CameraClient.isChannelSelect3028Verified starts false before cmd=3028 handshake",
                  !cam.isChannelSelect3028Verified());
            final CountDownLatch latch = new CountDownLatch(1);
            cam.prepareAndStartLiveView(CameraClient.VIEW_FRONT, true, new CameraClient.Callback<List<String>>() {
                @Override public void onSuccess(List<String> r) { latch.countDown(); }
                @Override public void onError(Exception e) { latch.countDown(); }
            });
            boolean done = latch.await(5, TimeUnit.SECONDS);
            check("CameraClient.isChannelSelect3028Verified becomes true once cmd=3028&par=0 returns Status=0",
                  done && cam.isChannelSelect3028Verified());
        } finally {
            cam.shutdown();
            server.stop(0);
        }

        // 3. BouncePolicy pure-Java safety invariants & StandaloneRootDaemon 'bounce' allowlist
        check("BouncePolicy.shouldHoldHardwareMute mutes projection/BT audio glitches while preserving FM/USB media and manual user mute",
              BouncePolicy.shouldHoldHardwareMute(BouncePolicy.MODE_NONE, 0, 0)
              && BouncePolicy.shouldHoldHardwareMute(BouncePolicy.MODE_BT_AUDIO, BouncePolicy.MICOM_BT_AUDIO, 0)
              && BouncePolicy.shouldHoldHardwareMute(BouncePolicy.MODE_CARPLAY, BouncePolicy.AUDIO_CARPLAY, 0)
              && BouncePolicy.shouldHoldHardwareMute(BouncePolicy.MODE_PROJECTION_GPM, BouncePolicy.AUDIO_PROJECTION_GPM, 0)
              && !BouncePolicy.shouldHoldHardwareMute(BouncePolicy.MODE_PROJECTION_GPM, BouncePolicy.AUDIO_PROJECTION_GPM, 1)
              && !BouncePolicy.shouldHoldHardwareMute(12 /* FM Radio */, 2, 0));

        check("BouncePolicy.isAllowedWorkerTarget & shouldRefreshWorker strictly allowlist projection sinks and block PPService/system_server",
              BouncePolicy.isAllowedWorkerTarget("com.google.android.projection.sink")
              && BouncePolicy.isAllowedWorkerTarget("com.daudio.app.dipo")
              && !BouncePolicy.isAllowedWorkerTarget("com.daudio.app.phoneprojection")
              && !BouncePolicy.isAllowedWorkerTarget("system_server")
              && BouncePolicy.shouldRefreshWorker("com.google.android.projection.sink", false, false, false)
              && BouncePolicy.shouldRefreshWorker("com.daudio.app.dipo", false, false, false)
              && BouncePolicy.shouldRefreshWorker("com.google.android.projection.sink", true, false, false)
              && !BouncePolicy.shouldRefreshWorker("com.daudio.app.dipo", true, false, false));

        check("BouncePolicy.selectReconnectNudgeTarget & selectProjectionTypeForNudge prioritize live cabin HFP phone over stale MACs",
              "AA:BB:CC:11:22:33".equals(BouncePolicy.selectReconnectNudgeTarget(
                      "aa-bb-cc-11-22-33", "DE:AD:BE:EF:00:01", "11:22:33:44:55:66", "", "", ""))
              && "11:22:33:44:55:66".equals(BouncePolicy.selectReconnectNudgeTarget(
                      "00:00:00:00:00:00", "", "11:22:33:44:55:66", "", "", ""))
              && BouncePolicy.selectProjectionTypeForNudge("AA:BB:CC:11:22:33", true, 0, "", "", 0) == BouncePolicy.PP_TYPE_CARPLAY
              && BouncePolicy.selectProjectionTypeForNudge("AA:BB:CC:11:22:33", false, BouncePolicy.PP_TYPE_GPM, "", "", 0) == BouncePolicy.PP_TYPE_GPM
              && BouncePolicy.redactMac("AA:BB:CC:11:22:33").startsWith("bt#")
              && "none".equals(BouncePolicy.redactMac("")));

        check("BouncePolicy.decideFocusShortcut & isCameraActive defer projection shortcut while OEM rear/AVM camera is on screen",
              BouncePolicy.isCameraActive(true, 0, "")
              && BouncePolicy.isCameraActive(false, 0, "com.hkmc.app.avm")
              && BouncePolicy.FOCUS_DEFER_CAMERA.equals(BouncePolicy.decideFocusShortcut(false, true, 0, true))
              && BouncePolicy.SHORTCUT_GPM.equals(BouncePolicy.decideFocusShortcut(false, true, 0, false))
              && BouncePolicy.SHORTCUT_CARPLAY.equals(BouncePolicy.decideFocusShortcut(true, false, 0, false))
              && "".equals(BouncePolicy.decideFocusShortcut(false, true, BouncePolicy.MODE_PROJECTION_GPM, false)));

        check("BouncePolicy radio state guards & concurrency cmdline detector match fitcamx.bounce and hmgapi-bounce",
              BouncePolicy.isBounceProcessCmdline("fitcamx.bounce")
              && BouncePolicy.isBounceProcessCmdline("hmgapi-bounce:soft")
              && !BouncePolicy.isBounceProcessCmdline("fitcamx.rearcam")
              && BouncePolicy.shouldInvokeBluetoothEnable(BouncePolicy.BT_STATE_OFF)
              && !BouncePolicy.shouldInvokeBluetoothEnable(BouncePolicy.BT_STATE_TURNING_ON)
              && !BouncePolicy.shouldInvokeBluetoothEnable(BouncePolicy.BT_STATE_ON)
              && BouncePolicy.isSoftApReadyForReenable(BouncePolicy.WIFI_AP_STATE_DISABLED)
              && !BouncePolicy.isSoftApReadyForReenable(BouncePolicy.WIFI_AP_STATE_DISABLING)
              && BouncePolicy.isTransientHmgapidBusyError("hmgapid is busy"));

        final List<String> launched = new ArrayList<String>();
        StandaloneRootDaemon.ServiceLauncher bounceMock = new StandaloneRootDaemon.ServiceLauncher() {
            @Override public int startService(String s, StringBuilder out) { launched.add(s); out.append("started\n"); return 0; }
            @Override public int triggerReboot(StringBuilder out) { return 0; }
            @Override public void requestShutdown() {}
        };
        int rcStartBounce = StandaloneRootDaemon.dispatchCommand(
                new String[] { "service-start", "bounce" }, 10188, 10188, bounceMock, new StringBuilder());
        int rcRestartBounce = StandaloneRootDaemon.dispatchCommand(
                new String[] { "service-restart", "bounce" }, 10188, 10188, bounceMock, new StringBuilder());
        check("StandaloneRootDaemon allowlists 'bounce' service-start & service-restart for fitcamx.app",
              rcStartBounce == 0 && rcRestartBounce == 0 && launched.size() == 2 && "bounce".equals(launched.get(0)));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== FITCAMX Gen5W Automated Java Unit & Integration Test Suite ===\n");
        testDebugLoggerRamOnly();
        testWifiBridgeSafety();
        testTsHeaderSniffer();
        testClipItemAndXmlParser();
        testCameraClientProtocol();
        testUsbDownloaderAtomicity();
        testGalleryRatePolicy();
        testWifiFastConnectHelpers();
        testScreenshotColorNormalizer();
        testGalleryBufferingAndSpinnerPolicy();
        testMaterial3ThemeEngine();
        testLiveWatchdogKeepaliveAndMirrorOptions();
        testWifiCustomPasswordAndManualFallback();
        testOemViewRotationAndGeometry();
        testPmapParserAndUsbDashcamScanner();
        testFormatSdCardAndRearProbe();
        testStandaloneNemesisAndRootDaemon();
        test4kLivePreviewFallbackAndSinglePassResolution();
        testLive555BridgeCm7053dwAndBouncePolicy();

        System.out.println("\n=== Java Test Summary: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
