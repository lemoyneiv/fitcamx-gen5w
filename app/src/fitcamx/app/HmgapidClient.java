package fitcamx.app;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Client for the Gen5W root service daemon over local abstract UNIX socket.
 *
 * Primary endpoint: @hmgapid (when HMG-API is installed and manages fitcamx-gen5w).
 * Fallback endpoint: @fitcamx.root (StandaloneRootDaemon when installed standalone on NEMESIS).
 *
 * Wire protocol: "LFD1", u32 argc, then argc x (u32 len, bytes); all little-endian.
 * Response frames: 'O' u32 len bytes (stdout/stderr), 'X' u32 len(4) u32 code (exit code).
 */
public final class HmgapidClient {
    public static final String PRIMARY_SOCKET = "hmgapid";
    public static final String FALLBACK_SOCKET = "fitcamx.root";
    private static final int CONNECT_FAILED = Integer.MIN_VALUE;

    private static volatile Boolean cachedRootAvailable = null;

    private HmgapidClient() {}

    /**
     * Encodes an LFD1 command request payload.
     */
    public static byte[] encodeRequest(String... args) throws java.io.IOException {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("LFD1 args must not be empty");
        }
        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.write(new byte[] { 'L', 'F', 'D', '1' });
        u32(req, args.length);
        for (String arg : args) {
            byte[] bytes = (arg != null ? arg : "").getBytes("UTF-8");
            u32(req, bytes.length);
            req.write(bytes);
        }
        return req.toByteArray();
    }

    /**
     * Reads LFD1 response frames ('O' output and 'X' exit code) from an InputStream.
     */
    public static int readResponse(InputStream in, String cmdName, StringBuilder outCapture) {
        byte[] header = new byte[5];
        while (readFully(in, header, 5)) {
            int type = header[0] & 0xff;
            long len = ((long) (header[1] & 0xff))
                    | (((long) (header[2] & 0xff)) << 8)
                    | (((long) (header[3] & 0xff)) << 16)
                    | (((long) (header[4] & 0xff)) << 24);

            if (type == 'O') { // Output frame
                if (len > 0 && len <= 65536) {
                    byte[] outBuf = new byte[(int) len];
                    if (readFully(in, outBuf, (int) len)) {
                        try {
                            String msg = new String(outBuf, "UTF-8");
                            if (outCapture != null) {
                                outCapture.append(msg);
                            }
                            if (cmdName != null) {
                                DebugLogger.log("HMGAPID", "daemon output: " + msg.trim());
                            }
                        } catch (Throwable ignored) {}
                    }
                } else {
                    skipFully(in, len);
                }
            } else if (type == 'X') { // Exit code frame
                if (len == 4) {
                    byte[] codeBuf = new byte[4];
                    if (readFully(in, codeBuf, 4)) {
                        long code = ((long) (codeBuf[0] & 0xff))
                                | (((long) (codeBuf[1] & 0xff)) << 8)
                                | (((long) (codeBuf[2] & 0xff)) << 16)
                                | (((long) (codeBuf[3] & 0xff)) << 24);
                        if (cmdName != null && !"whoami".equals(cmdName) && !"ping".equals(cmdName)) {
                            DebugLogger.log("HMGAPID", "command " + cmdName + " finished with exit code " + code);
                        }
                        return (int) code;
                    }
                }
                return -1;
            } else {
                if (cmdName != null) {
                    DebugLogger.log("HMGAPID", "unexpected frame type 0x" + Integer.toHexString(type));
                }
                return -1;
            }
        }
        return -1;
    }

    /**
     * Executes a command on hmgapid (@hmgapid), automatically falling back to StandaloneRootDaemon
     * (@fitcamx.root) when HMG-API is absent or does not manage fitcamx-gen5w.
     */
    public static int call(String... args) {
        return callWithTimeout(5000, args);
    }

    public static int callWithTimeout(int timeoutMs, String... args) {
        if (args == null || args.length == 0) return -1;
        boolean quietProbe = "whoami".equals(args[0]) || "ping".equals(args[0]);
        int rc = callSocket(PRIMARY_SOCKET, timeoutMs, quietProbe, args);
        if (rc == 0) {
            return 0;
        }
        int fb = callSocket(FALLBACK_SOCKET, timeoutMs, quietProbe, args);
        if (fb != CONNECT_FAILED) {
            if (fb == 0 || rc == CONNECT_FAILED) {
                return fb;
            }
        }
        if (rc == CONNECT_FAILED && !quietProbe) {
            DebugLogger.log("HMGAPID", "root daemon unavailable on @" + PRIMARY_SOCKET + " and @" + FALLBACK_SOCKET);
        }
        return rc == CONNECT_FAILED ? -1 : rc;
    }

    private static int callSocket(String socketName, int timeoutMs, boolean quiet, String... args) {
        LocalSocket socket = null;
        try {
            socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
            } catch (Throwable connectErr) {
                return CONNECT_FAILED;
            }
            socket.setSoTimeout(Math.max(150, timeoutMs));

            byte[] payload = encodeRequest(args);
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();

            InputStream in = socket.getInputStream();
            return readResponse(in, quiet ? null : args[0], null);
        } catch (Throwable t) {
            if (!quiet) {
                DebugLogger.log("HMGAPID", socketName + " communication error: " + t.getMessage());
            }
            return -1;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Returns whether an authorized root service daemon (@hmgapid or @fitcamx.root) is available.
     * Defaults to true prior to async probe completion so standard installs never hide reboot UI.
     */
    public static boolean isRootServiceCachedAvailable() {
        Boolean v = cachedRootAvailable;
        return v == null || v.booleanValue();
    }

    /**
     * Asynchronously probes @hmgapid / @fitcamx.root authorization off the main UI thread.
     */
    public static void probeRootServiceAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = (callWithTimeout(400, "whoami") == 0);
                cachedRootAvailable = Boolean.valueOf(ok);
            }
        }, "FitcamxRootProbe");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Request head unit reboot through @hmgapid or @fitcamx.root.
     */
    public static boolean reboot() {
        DebugLogger.log("SYSTEM", "Requesting reboot via root service...");
        int rc = call("service-start", "reboot");
        if (rc == 0) {
            DebugLogger.log("SYSTEM", "service-start reboot succeeded");
            return true;
        }
        rc = call("service-restart", "reboot");
        if (rc == 0) {
            DebugLogger.log("SYSTEM", "service-restart reboot succeeded");
            return true;
        }
        rc = call("reboot");
        if (rc == 0) {
            DebugLogger.log("SYSTEM", "direct reboot succeeded");
            return true;
        }
        DebugLogger.log("SYSTEM", "root service reboot attempts failed (rc=" + rc + ")");
        return false;
    }

    /**
     * Request root framebuffer screenshot capture to attached USB via manual service.
     */
    public static boolean requestScreenshot() {
        int rc = call("service-start", "screenshot");
        if (rc == 0) {
            return true;
        }
        rc = call("service-restart", "screenshot");
        return rc == 0;
    }

    private static void u32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write(v >> 8 & 0xff);
        o.write(v >> 16 & 0xff);
        o.write(v >> 24 & 0xff);
    }

    private static boolean readFully(InputStream in, byte[] b, int len) {
        int offset = 0;
        try {
            while (offset < len) {
                int read = in.read(b, offset, len - offset);
                if (read < 0) return false;
                offset += read;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void skipFully(InputStream in, long len) {
        try {
            byte[] discard = new byte[4096];
            long remaining = len;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped > 0) {
                    remaining -= skipped;
                } else {
                    int toRead = (int) Math.min((long) discard.length, remaining);
                    int read = in.read(discard, 0, toRead);
                    if (read < 0) break;
                    remaining -= read;
                }
            }
        } catch (Throwable ignored) {}
    }
}
