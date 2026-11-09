package fitcamx.app;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Client for hmgapid root daemon running on Gen5W over local abstract socket @hmgapid.
 * Protocol: "LFD1", u32 argc, then argc x (u32 len, bytes); all little-endian.
 * Response frames: 'O' u32 len bytes (stdout/stderr), 'X' u32 len(4) u32 code (exit code).
 */
public final class HmgapidClient {
    private HmgapidClient() {}

    /**
     * Executes a command on the hmgapid daemon.
     * @param args Command arguments (e.g. "service-start", "reboot")
     * @return Exit code returned by hmgapid (0 for success), or -1 on communication failure.
     */
    public static int call(String... args) {
        if (args == null || args.length == 0) return -1;
        LocalSocket socket = null;
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress("hmgapid", LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(5000);

            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[] { 'L', 'F', 'D', '1' });
            u32(req, args.length);
            for (String arg : args) {
                byte[] bytes = arg.getBytes("UTF-8");
                u32(req, bytes.length);
                req.write(bytes);
            }

            OutputStream out = socket.getOutputStream();
            out.write(req.toByteArray());
            out.flush();

            InputStream in = socket.getInputStream();
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
                            String msg = new String(outBuf, "UTF-8");
                            DebugLogger.log("HMGAPID", "daemon output: " + msg.trim());
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
                            DebugLogger.log("HMGAPID", "command " + args[0] + " finished with exit code " + code);
                            return (int) code;
                        }
                    }
                    return -1;
                } else {
                    DebugLogger.log("HMGAPID", "unexpected frame type 0x" + Integer.toHexString(type));
                    return -1;
                }
            }
            return -1;
        } catch (Throwable t) {
            DebugLogger.log("HMGAPID", "daemon communication error: " + t.getMessage());
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
     * Request head unit reboot through hmgapid.
     * Mods are authorized to start their declared root services via service-start.
     */
    public static boolean reboot() {
        DebugLogger.log("SYSTEM", "Requesting reboot via hmgapid root service...");
        int rc = call("service-start", "reboot");
        if (rc == 0) {
            DebugLogger.log("SYSTEM", "hmgapid service-start reboot succeeded");
            return true;
        }
        rc = call("service-restart", "reboot");
        if (rc == 0) {
            DebugLogger.log("SYSTEM", "hmgapid service-restart reboot succeeded");
            return true;
        }
        rc = call("reboot");
        if (rc == 0) {
            DebugLogger.log("SYSTEM", "hmgapid direct reboot succeeded");
            return true;
        }
        DebugLogger.log("SYSTEM", "hmgapid reboot attempts failed (rc=" + rc + ")");
        return false;
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
            long remaining = len;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) break;
                remaining -= skipped;
            }
        } catch (Throwable ignored) {}
    }
}
