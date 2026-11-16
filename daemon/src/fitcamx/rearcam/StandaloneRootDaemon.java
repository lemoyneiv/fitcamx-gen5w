package fitcamx.rearcam;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lightweight uid=0 root service broker for Standalone NEMESIS installations (when HMG-API is
 * not installed on the head unit).
 *
 * Key invariants:
 * - Listens on abstract UNIX socket @fitcamx.root using the exact same LFD1 framing as hmgapid.
 * - Zero writes to /data flash at runtime; blocks at 0% CPU in LocalServerSocket.accept().
 * - Strictly authorizes callers by kernel SO_PEERCRED UID (root=0, system=1000, or fitcamx.app)
 *   and executes only fixed services ("rearcam", "screenshot", "reboot") — no arbitrary shell.
 */
public final class StandaloneRootDaemon {
    public static final String SOCKET_NAME = "fitcamx.root";
    public static final String APP_PACKAGE = "fitcamx.app";
    public static final String MOD_ID = "fitcamx-gen5w";
    public static final String DEFAULT_PKG_DIR = "/data/nemesis/apps/fitcamx.gen5w";

    private static final int MAX_ARGS = 16;
    private static final int MAX_ARG_BYTES = 65536;

    public interface ServiceLauncher {
        int startService(String serviceName, StringBuilder output);
        int triggerReboot(StringBuilder output);
        void requestShutdown();
    }

    private static volatile boolean sRunning = true;
    private static volatile LocalServerSocket sServer = null;

    private StandaloneRootDaemon() {}

    /**
     * Decodes a single LFD1 request from an InputStream.
     * Returns null on clean EOF prior to the 4-byte magic header.
     */
    public static String[] decodeRequest(InputStream in) throws IOException {
        byte[] magic = new byte[4];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        magic[0] = (byte) first;
        readFully(in, magic, 1, 3);
        if (magic[0] != 'L' || magic[1] != 'F' || magic[2] != 'D' || magic[3] != '1') {
            throw new IOException("Invalid LFD1 magic");
        }
        int argc = readLeU32(in);
        if (argc <= 0 || argc > MAX_ARGS) {
            throw new IOException("Invalid LFD1 argc: " + argc);
        }
        String[] argv = new String[argc];
        for (int i = 0; i < argc; i++) {
            int len = readLeU32(in);
            if (len < 0 || len > MAX_ARG_BYTES) {
                throw new IOException("Invalid LFD1 arg length: " + len);
            }
            byte[] buf = new byte[len];
            if (len > 0) {
                readFully(in, buf, 0, len);
            }
            argv[i] = new String(buf, "UTF-8");
        }
        return argv;
    }

    /**
     * Writes LFD1 response frames ('O' optional output + 'X' exit code) to an OutputStream.
     */
    public static void writeResponse(OutputStream out, String text, int exitCode) throws IOException {
        ByteArrayOutputStream frameBuf = new ByteArrayOutputStream(128);
        if (text != null && !text.isEmpty()) {
            byte[] msgBytes = text.getBytes("UTF-8");
            frameBuf.write('O');
            writeLeU32(frameBuf, msgBytes.length);
            frameBuf.write(msgBytes);
        }
        frameBuf.write('X');
        writeLeU32(frameBuf, 4);
        writeLeU32(frameBuf, exitCode);
        out.write(frameBuf.toByteArray());
        out.flush();
    }

    /**
     * Pure, unit-testable authorization and command dispatcher.
     */
    public static int dispatchCommand(String[] argv, int peerUid, int appUid,
                                      ServiceLauncher launcher, StringBuilder outMsg) {
        if (argv == null || argv.length == 0 || argv[0] == null) {
            outMsg.append("empty command\n");
            return 2;
        }
        String op = argv[0];
        if ("ping".equals(op) && argv.length == 1) {
            outMsg.append("ok\n");
            return 0;
        }

        boolean isRoot = (peerUid == 0);
        boolean isSystem = (peerUid == 1000);
        boolean isApp = (appUid > 0 && peerUid == appUid);
        if (!isRoot && !isSystem && !isApp) {
            outMsg.append("not allowed\n");
            return 13;
        }

        if ("whoami".equals(op) && argv.length == 1) {
            outMsg.append(MOD_ID).append('\n');
            return 0;
        }

        if ("stop".equals(op) && argv.length == 1) {
            if (!isRoot) {
                outMsg.append("not allowed\n");
                return 13;
            }
            outMsg.append("stopping\n");
            if (launcher != null) {
                launcher.requestShutdown();
            }
            return 0;
        }

        if (("service-start".equals(op) || "service-restart".equals(op)) && argv.length == 2) {
            String svc = argv[1];
            if (!"rearcam".equals(svc) && !"screenshot".equals(svc) && !"reboot".equals(svc)) {
                outMsg.append("unknown service: ").append(svc).append('\n');
                return 2;
            }
            return launcher != null ? launcher.startService(svc, outMsg) : 1;
        }

        if ("reboot".equals(op) && argv.length == 1) {
            return launcher != null ? launcher.triggerReboot(outMsg) : 1;
        }

        outMsg.append("unknown op\n");
        return 2;
    }

    /**
     * Sends a clean uid=0 stop request to @fitcamx.root if running, and also stops @fitcamx.rearcam.
     */
    public static void requestStopRunningDaemons() {
        // 1. Stop @fitcamx.rearcam if currently active
        LocalSocket rearSock = null;
        try {
            rearSock = new LocalSocket();
            rearSock.connect(new LocalSocketAddress(RearCamDaemon.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            rearSock.setSoTimeout(600);
            OutputStream ro = rearSock.getOutputStream();
            ro.write('X');
            ro.flush();
            rearSock.getInputStream().read();
        } catch (Throwable ignored) {
        } finally {
            if (rearSock != null) {
                try { rearSock.close(); } catch (IOException ignored) {}
            }
        }

        // 2. Stop @fitcamx.root if currently active
        LocalSocket sock = null;
        try {
            sock = new LocalSocket();
            sock.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            sock.setSoTimeout(800);
            ByteArrayOutputStream req = new ByteArrayOutputStream(32);
            req.write(new byte[] { 'L', 'F', 'D', '1' });
            writeLeU32(req, 1);
            writeLeU32(req, 4);
            req.write(new byte[] { 's', 't', 'o', 'p' });
            OutputStream out = sock.getOutputStream();
            out.write(req.toByteArray());
            out.flush();
            byte[] drain = new byte[64];
            sock.getInputStream().read(drain);
        } catch (Throwable ignored) {
            return;
        } finally {
            if (sock != null) {
                try { sock.close(); } catch (IOException ignored) {}
            }
        }

        // Wait up to 800 ms for the abstract socket to unbind
        long deadline = SystemClock.uptimeMillis() + 800L;
        while (SystemClock.uptimeMillis() < deadline) {
            LocalSocket probe = new LocalSocket();
            try {
                probe.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
                try { probe.close(); } catch (IOException ignored) {}
                SystemClock.sleep(50L);
            } catch (IOException gone) {
                try { probe.close(); } catch (IOException ignored) {}
                break;
            }
        }
    }

    public static void main(String[] args) {
        final File pkgDir = new File((args != null && args.length > 0 && args[0] != null && !args[0].isEmpty())
                ? args[0] : DEFAULT_PKG_DIR);

        // Exit immediately if standalone mode is not enabled
        if (!new File(pkgDir, "standalone/enabled").isFile()) {
            return;
        }

        try {
            android.os.Process.class.getMethod("setArgV0", String.class)
                    .invoke(null, "fitcamx-root");
        } catch (Throwable ignored) {}

        final ServiceLauncher launcher = new RealServiceLauncher(pkgDir);
        LocalServerSocket server = null;
        try {
            // Atomic kernel single-instance lock: exits cleanly if already listening
            server = new LocalServerSocket(SOCKET_NAME);
            sServer = server;
            while (sRunning) {
                final LocalSocket client = server.accept();
                Thread worker = new Thread("fitcamx-root-ipc") {
                    @Override
                    public void run() {
                        handleClient(client, launcher);
                    }
                };
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Throwable ignored) {
            // Another instance already owns @fitcamx.root or server closed during stop
        } finally {
            if (server != null) {
                try { server.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void handleClient(LocalSocket client, ServiceLauncher launcher) {
        try {
            client.setSoTimeout(3000);
            int peerUid = -1;
            try {
                peerUid = client.getPeerCredentials().getUid();
            } catch (Throwable ignored) {}
            int appUid = PosixBridge.getPackageUid(APP_PACKAGE);

            String[] argv = decodeRequest(client.getInputStream());
            if (argv == null) {
                return;
            }
            StringBuilder outMsg = new StringBuilder(64);
            int rc = dispatchCommand(argv, peerUid, appUid, launcher, outMsg);
            writeResponse(client.getOutputStream(), outMsg.toString(), rc);
            if (!sRunning) {
                LocalServerSocket srv = sServer;
                if (srv != null) {
                    try { srv.close(); } catch (IOException ignored) {}
                }
                System.exit(0);
            }
        } catch (Throwable ignored) {
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private static final class RealServiceLauncher implements ServiceLauncher {
        private final File pkgDir;

        RealServiceLauncher(File pkgDir) {
            this.pkgDir = pkgDir;
        }

        @Override
        public int startService(String serviceName, StringBuilder output) {
            if ("rearcam".equals(serviceName)) {
                File jar = new File(pkgDir, "bin/rearcam.jar");
                if (!jar.isFile()) {
                    output.append("missing bin/rearcam.jar\n");
                    return 1;
                }
                return spawnDetached(
                        "CLASSPATH=\"$1\" ANDROID_ROOT=/system ANDROID_DATA=/data "
                        + "/system/bin/app_process /system/bin --nice-name=fitcamx.rearcam "
                        + "fitcamx.rearcam.RearCamDaemon </dev/null >/dev/null 2>&1 &",
                        jar.getAbsolutePath(), output);
            }
            if ("screenshot".equals(serviceName)) {
                File script = new File(pkgDir, "standalone/svc/screenshot.sh");
                if (!script.isFile()) {
                    output.append("missing standalone/svc/screenshot.sh\n");
                    return 1;
                }
                return spawnDetached(
                        "/system/bin/sh \"$1\" </dev/null >/dev/null 2>&1 &",
                        script.getAbsolutePath(), output);
            }
            if ("reboot".equals(serviceName)) {
                return triggerReboot(output);
            }
            return 2;
        }

        @Override
        public int triggerReboot(StringBuilder output) {
            File script = new File(pkgDir, "standalone/svc/reboot.sh");
            output.append("restarting\n");
            if (script.isFile()) {
                return spawnDetached(
                        "(sleep 1; /system/bin/sh \"$1\" || /system/bin/reboot) </dev/null >/dev/null 2>&1 &",
                        script.getAbsolutePath(), output);
            }
            return spawnDetached(
                    "(sleep 1; sync; /system/bin/reboot) </dev/null >/dev/null 2>&1 &",
                    "", output);
        }

        @Override
        public void requestShutdown() {
            sRunning = false;
        }

        private int spawnDetached(String shCmd, String arg1, StringBuilder output) {
            Process proc = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", shCmd, "sh", arg1);
                String bootCp = System.getenv("BOOTCLASSPATH");
                if (bootCp != null && !bootCp.isEmpty()) {
                    pb.environment().put("BOOTCLASSPATH", bootCp);
                }
                proc = pb.start();
                try { proc.getOutputStream().close(); } catch (IOException ignored) {}
                drainQuietly(proc.getInputStream());
                drainQuietly(proc.getErrorStream());
                return proc.waitFor();
            } catch (Throwable t) {
                output.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
                return 1;
            } finally {
                if (proc != null) {
                    try { proc.destroy(); } catch (Throwable ignored) {}
                }
            }
        }

        private static void drainQuietly(InputStream in) {
            if (in == null) return;
            try {
                byte[] buf = new byte[256];
                while (in.read(buf) > 0) {
                    // discard
                }
            } catch (IOException ignored) {
            } finally {
                try { in.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static void readFully(InputStream in, byte[] dst, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(dst, off + total, len - total);
            if (n < 0) {
                throw new EOFException("Unexpected EOF in LFD1 frame");
            }
            total += n;
        }
    }

    private static int readLeU32(InputStream in) throws IOException {
        byte[] b = new byte[4];
        readFully(in, b, 0, 4);
        return (b[0] & 0xff)
                | ((b[1] & 0xff) << 8)
                | ((b[2] & 0xff) << 16)
                | ((b[3] & 0xff) << 24);
    }

    private static void writeLeU32(OutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }
}
