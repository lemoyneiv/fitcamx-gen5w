package fitcamx.rearcam;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Standalone NEMESIS installer, updater, HMG-API migrator, and uninstaller for FITCAMX Gen5W.
 *
 * Runs via /system/bin/app_process out of bin/rearcam.jar when HMG-API is NOT present on the
 * head unit, avoiding missing Linux CLI tools on stock Gen5W (no unzip, sed, awk, cut, tr)
 * and preserving byte-identical /system backups in /data/nemesis/backup/fitcamx.gen5w/.
 */
public final class StandaloneInstaller {
    public static final String BACKUP_PKG_NAME = "fitcamx.gen5w";
    public static final String APP_PACKAGE = "fitcamx.app";
    public static final String WIFI_XML_REL = "/system/etc/permissions/android.hardware.wifi.xml";
    public static final String WIFI_XML_CONTENT =
            "<permissions>\n    <feature name=\"android.hardware.wifi\" />\n</permissions>\n";
    public static final String WIFI_FEATURE_LINE = "-/system/etc/permissions/android.hardware.wifi.xml";
    public static final String PROP_WIFI_STATE = "persist.wifi.state";

    private StandaloneInstaller() {}

    /**
     * Execution environment abstraction supporting real Gen5W head units (root == "")
     * and isolated off-car tests (ROOT=/tmp/... + FITCAMX_HOST_TEST=1).
     */
    public static class Env {
        public final String root;
        public final boolean hostTest;

        public Env(String rootPrefix, boolean hostTest) {
            String r = rootPrefix != null ? rootPrefix.trim() : "";
            while (r.endsWith("/") && r.length() > 1) {
                r = r.substring(0, r.length() - 1);
            }
            if ("/".equals(r)) r = "";
            this.root = r;
            this.hostTest = hostTest;
        }

        public static Env fromProcessEnv() {
            String r = System.getenv("ROOT");
            boolean host = (r != null && !r.isEmpty()) || "1".equals(System.getenv("FITCAMX_HOST_TEST"));
            return new Env(r, host);
        }

        public File sysFile(String absPath) {
            if (root.isEmpty()) {
                return new File(absPath);
            }
            return new File(root + (absPath.startsWith("/") ? absPath : ("/" + absPath)));
        }

        public File backupDir() {
            return sysFile("/data/nemesis/backup/" + BACKUP_PKG_NAME);
        }

        public void recordHostAction(String action) {
            if (!hostTest || root.isEmpty()) return;
            try {
                File f = sysFile("/standalone-actions.log");
                f.getParentFile().mkdirs();
                String prev = f.isFile() ? readUtf8(f) : "";
                writeUtf8(f, prev + action + "\n");
            } catch (IOException ignored) {}
        }

        public void remountSystem(String mode) throws IOException {
            if (hostTest) {
                recordHostAction("remount " + mode);
                return;
            }
            File nem = new File("/system/xbin/nemesis");
            if (nem.canExecute()) {
                CmdResult r = execCmd("/system/xbin/nemesis", "remount", mode);
                if (r.code == 0) return;
            }
            CmdResult m = execCmd("/system/bin/mount", "-o", "remount," + mode, "/system");
            if (m.code != 0 && "rw".equals(mode)) {
                throw new IOException("Failed to remount /system rw: " + m.output.trim());
            }
        }

        public String getprop(String key) {
            if (hostTest) {
                Map<String, String> props = loadHostProps();
                String v = props.get(key);
                return v != null ? v : "";
            }
            String v = PosixBridge.getSystemProp(key, null);
            if (v != null) return v.trim();
            CmdResult r = execCmd("/system/bin/getprop", key);
            return r.code == 0 ? r.output.trim() : "";
        }

        public void setprop(String key, String value) throws IOException {
            String safeVal = value != null ? value : "";
            if (hostTest) {
                Map<String, String> props = loadHostProps();
                props.put(key, safeVal);
                saveHostProps(props);
                recordHostAction("setprop " + key + " " + safeVal);
                return;
            }
            CmdResult r = execCmd("/system/bin/setprop", key, safeVal);
            if (r.code != 0) {
                PosixBridge.setSystemProp(key, safeVal);
            }
        }

        public boolean isPackageInstalled(String pkg) {
            if (hostTest) {
                File pl = sysFile("/data/system/packages.list");
                return PosixBridge.parsePackageUidFromPackagesList(pkg, pl) > 0;
            }
            CmdResult r = execCmd("/system/bin/pm", "path", pkg);
            if (r.code == 0 && r.output.contains("package:")) {
                return true;
            }
            return PosixBridge.getPackageUid(pkg) > 0;
        }

        public String pmInstall(File worldReadableApk, String pkg) throws IOException {
            if (hostTest) {
                recordHostAction("pm install -r " + pkg);
                if ("1".equals(System.getenv("FITCAMX_TEST_PM_FAIL"))) {
                    return "Failure [TEST_PM_FAIL]";
                }
                File pl = sysFile("/data/system/packages.list");
                pl.getParentFile().mkdirs();
                if (PosixBridge.parsePackageUidFromPackagesList(pkg, pl) <= 0) {
                    String prev = pl.isFile() ? readUtf8(pl) : "";
                    writeUtf8(pl, prev + pkg + " 10188 0 /data/data/" + pkg + " default none\n");
                }
                File dataDir = sysFile("/data/data/" + pkg);
                dataDir.mkdirs();
                return null;
            }
            setprop("sys.boot_completed", "0");
            CmdResult r;
            try {
                r = execCmd("/system/bin/pm", "install", "-r", worldReadableApk.getAbsolutePath());
            } finally {
                setprop("sys.boot_completed", "1");
            }
            return r.output.contains("Success") ? null : r.output.trim();
        }

        public boolean pmUninstall(String pkg, boolean keepData) throws IOException {
            if (!isPackageInstalled(pkg)) {
                return true;
            }
            if (hostTest) {
                recordHostAction(keepData ? ("pm uninstall -k " + pkg) : ("pm uninstall " + pkg));
                File pl = sysFile("/data/system/packages.list");
                if (pl.isFile()) {
                    StringBuilder kept = new StringBuilder();
                    for (String line : readUtf8(pl).split("\n")) {
                        if (!line.trim().isEmpty() && !line.startsWith(pkg + " ")) {
                            kept.append(line).append('\n');
                        }
                    }
                    writeUtf8(pl, kept.toString());
                }
                if (!keepData) {
                    deleteRecursively(sysFile("/data/data/" + pkg));
                }
                return true;
            }
            CmdResult r = keepData
                    ? execCmd("/system/bin/pm", "uninstall", "-k", pkg)
                    : execCmd("/system/bin/pm", "uninstall", pkg);
            return r.code == 0 || r.output.contains("Success");
        }

        private Map<String, String> loadHostProps() {
            Map<String, String> map = new LinkedHashMap<String, String>();
            File f = sysFile("/data/local/tmp/test.props");
            if (!f.isFile()) return map;
            try {
                for (String line : readUtf8(f).split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        map.put(line.substring(0, eq).trim(), line.substring(eq + 1));
                    }
                }
            } catch (IOException ignored) {}
            return map;
        }

        private void saveHostProps(Map<String, String> map) throws IOException {
            File f = sysFile("/data/local/tmp/test.props");
            f.getParentFile().mkdirs();
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : map.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            writeUtf8(f, sb.toString());
        }
    }

    private static final class CmdResult {
        final int code;
        final String output;

        CmdResult(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    private static CmdResult execCmd(String... cmd) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            try { p.getOutputStream().close(); } catch (IOException ignored) {}
            byte[] out = readAllBytes(p.getInputStream());
            int rc = p.waitFor();
            return new CmdResult(rc, new String(out, "UTF-8"));
        } catch (Throwable t) {
            return new CmdResult(-1, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Comments out an uncommented "-/system/etc/permissions/android.hardware.wifi.xml" line
     * in featurexml.cfg so stock Gen5W does not delete android.hardware.wifi.xml on boot.
     * Returns null if no change is needed (absent or already commented out).
     */
    public static String commentOutWifiFeatureLine(String content) {
        if (content == null) return null;
        boolean trailingNewline = content.endsWith("\n");
        String[] rawLines = content.split("\n", -1);
        int limit = trailingNewline ? rawLines.length - 1 : rawLines.length;
        boolean changed = false;
        StringBuilder sb = new StringBuilder(content.length() + 4);
        for (int i = 0; i < limit; i++) {
            String line = rawLines[i];
            String trimmed = line.trim();
            if (WIFI_FEATURE_LINE.equals(trimmed)) {
                sb.append('#').append(line);
                changed = true;
            } else {
                sb.append(line);
            }
            if (i < limit - 1 || trailingNewline) {
                sb.append('\n');
            }
        }
        return changed ? sb.toString() : null;
    }

    /**
     * Uncomments "#-/system/etc/permissions/android.hardware.wifi.xml" while preserving all
     * other lines in featurexml.cfg.
     */
    public static String uncommentWifiFeatureLine(String content) {
        if (content == null) return "";
        boolean trailingNewline = content.endsWith("\n");
        String[] rawLines = content.split("\n", -1);
        int limit = trailingNewline ? rawLines.length - 1 : rawLines.length;
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < limit; i++) {
            String line = rawLines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("#") && WIFI_FEATURE_LINE.equals(trimmed.substring(1).trim())) {
                int hashIdx = line.indexOf('#');
                sb.append(line.substring(0, hashIdx)).append(line.substring(hashIdx + 1));
            } else {
                sb.append(line);
            }
            if (i < limit - 1 || trailingNewline) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    public static String resolveOnebinDirName(Env env) {
        String prop = env.getprop("ro.mobis.onebindir");
        if (prop != null && !prop.isEmpty()) {
            return prop;
        }
        File onebinRoot = env.sysFile("/system/onebin");
        File[] children = onebinRoot.listFiles();
        if (children == null) return "";
        List<String> candidates = new ArrayList<String>();
        Arrays.sort(children);
        for (File c : children) {
            if (c.isDirectory() && new File(c, "featurexml.cfg").isFile()) {
                candidates.add(c.getName());
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : "";
    }

    /**
     * Enables Gen5W Wi-Fi client mode with non-destructive backups in /data/nemesis/backup/fitcamx.gen5w/.
     * Returns true if /system was modified and a head-unit restart is recommended.
     */
    public static boolean applyWifiClientConfig(Env env) throws IOException {
        File backupDir = env.backupDir();
        File wifiXml = env.sysFile(WIFI_XML_REL);
        File bakAbsent = new File(backupDir, "perm.android.hardware.wifi.xml.absent");
        File bakMarker = new File(backupDir, "perm.android.hardware.wifi.xml");

        String onebin = resolveOnebinDirName(env);
        File cfgFile = !onebin.isEmpty()
                ? env.sysFile("/system/onebin/" + onebin + "/featurexml.cfg")
                : null;
        String cfgOriginal = (cfgFile != null && cfgFile.isFile()) ? readUtf8(cfgFile) : null;
        String cfgUpdated = cfgOriginal != null ? commentOutWifiFeatureLine(cfgOriginal) : null;

        boolean needXmlWrite = !wifiXml.isFile()
                || (bakAbsent.isFile() && !WIFI_XML_CONTENT.equals(readUtf8(wifiXml)));
        boolean needCfgWrite = (cfgUpdated != null);
        boolean restartNeeded = false;

        if (needXmlWrite || needCfgWrite) {
            backupDir.mkdirs();
            env.remountSystem("rw");
            try {
                if (!wifiXml.isFile()) {
                    if (!bakAbsent.isFile()) {
                        writeUtf8(bakAbsent, "");
                        writeUtf8(bakMarker, "");
                    }
                    wifiXml.getParentFile().mkdirs();
                    writeUtf8(wifiXml, WIFI_XML_CONTENT);
                    makeWorldReadable644(wifiXml);
                    restartNeeded = true;
                } else if (bakAbsent.isFile() && !WIFI_XML_CONTENT.equals(readUtf8(wifiXml))) {
                    writeUtf8(wifiXml, WIFI_XML_CONTENT);
                    makeWorldReadable644(wifiXml);
                }

                if (needCfgWrite && cfgFile != null) {
                    File bakCfg = new File(backupDir, "featurexml." + onebin + ".cfg");
                    if (!bakCfg.isFile()) {
                        copyFile(cfgFile, bakCfg);
                    }
                    writeUtf8(cfgFile, cfgUpdated);
                    makeWorldReadable644(cfgFile);
                    restartNeeded = true;
                }
            } finally {
                env.remountSystem("ro");
            }
        }

        String curWifiState = env.getprop(PROP_WIFI_STATE);
        if (!"3".equals(curWifiState)) {
            backupDir.mkdirs();
            File bakProp = new File(backupDir, "prop.persist.wifi.state");
            if (!bakProp.isFile()) {
                writeUtf8(bakProp, (curWifiState != null ? curWifiState : "") + "\n");
            }
            env.setprop(PROP_WIFI_STATE, "3");
        }

        return restartNeeded;
    }

    /**
     * Restores only the files and properties backed up in /data/nemesis/backup/fitcamx.gen5w/,
     * leaving pre-existing Tilepad / user configurations untouched.
     */
    public static void restoreWifiClientConfig(Env env) throws IOException {
        File backupDir = env.backupDir();
        if (!backupDir.isDirectory()) {
            return;
        }

        File bakAbsent = new File(backupDir, "perm.android.hardware.wifi.xml.absent");
        File[] children = backupDir.listFiles();
        List<File> cfgBackups = new ArrayList<File>();
        if (children != null) {
            Arrays.sort(children);
            for (File f : children) {
                String n = f.getName();
                if (f.isFile() && n.startsWith("featurexml.") && n.endsWith(".cfg")) {
                    cfgBackups.add(f);
                }
            }
        }

        if (bakAbsent.isFile() || !cfgBackups.isEmpty()) {
            env.remountSystem("rw");
            try {
                if (bakAbsent.isFile()) {
                    File wifiXml = env.sysFile(WIFI_XML_REL);
                    if (wifiXml.exists()) {
                        wifiXml.delete();
                    }
                }
                for (File bakCfg : cfgBackups) {
                    String n = bakCfg.getName();
                    String onebin = n.substring("featurexml.".length(), n.length() - ".cfg".length());
                    File cfgFile = env.sysFile("/system/onebin/" + onebin + "/featurexml.cfg");
                    if (!cfgFile.isFile()) continue;
                    byte[] bakBytes = readBytes(bakCfg);
                    String bakText = new String(bakBytes, "UTF-8");
                    String liveText = readUtf8(cfgFile);
                    String expectedOursOnly = commentOutWifiFeatureLine(bakText);
                    if (expectedOursOnly != null && expectedOursOnly.equals(liveText)) {
                        writeBytes(cfgFile, bakBytes);
                        makeWorldReadable644(cfgFile);
                    } else {
                        writeUtf8(cfgFile, uncommentWifiFeatureLine(liveText));
                        makeWorldReadable644(cfgFile);
                    }
                }
            } finally {
                env.remountSystem("ro");
            }
        }

        File bakProp = new File(backupDir, "prop.persist.wifi.state");
        if (bakProp.isFile()) {
            String raw = readUtf8(bakProp);
            int nl = raw.indexOf('\n');
            String stockVal = nl >= 0 ? raw.substring(0, nl) : raw;
            if (stockVal.endsWith("\r")) {
                stockVal = stockVal.substring(0, stockVal.length() - 1);
            }
            env.setprop(PROP_WIFI_STATE, stockVal);
        }

        deleteRecursively(backupDir);
    }

    /**
     * Installs or updates FITCAMX in standalone NEMESIS mode.
     * Returns true if a head-unit restart is recommended (first-time /system Wi-Fi permission add).
     */
    public static boolean installStandalone(Env env, File pkgDir, File modZip) throws IOException {
        if (modZip == null || !modZip.isFile()) {
            throw new IOException("Missing mod package zip: " + modZip);
        }
        File standaloneDir = new File(pkgDir, "standalone");
        File svcDir = new File(standaloneDir, "svc");
        svcDir.mkdirs();

        // 1. Extract lightweight root helper scripts from mod/package.zip (saving ~18.4 MB by streaming APK directly)
        String apkMd5;
        ZipFile zf = new ZipFile(modZip);
        try {
            extractRequiredEntry(zf, "svc/screenshot.sh", new File(svcDir, "screenshot.sh"), true);
            extractRequiredEntry(zf, "svc/reboot.sh", new File(svcDir, "reboot.sh"), true);
            ZipEntry apkEntry = zf.getEntry("app/Fitcamx.apk");
            if (apkEntry == null) {
                throw new IOException("mod/package.zip is missing app/Fitcamx.apk");
            }
            apkMd5 = md5Hex(zf.getInputStream(apkEntry));
        } finally {
            zf.close();
        }

        // 2. Enable Gen5W Wi-Fi client feature & property with stock backups
        boolean restartNeeded = applyWifiClientConfig(env);

        // 3. Install or update Fitcamx.apk if Android is booted (otherwise mark pending for service.sh / open.sh)
        File md5File = new File(standaloneDir, "apk.md5");
        File pendingFile = new File(standaloneDir, "apk.pending");
        if ("1".equals(env.getprop("sys.boot_completed"))) {
            String installedMd5 = md5File.isFile() ? readUtf8(md5File).trim() : "";
            if (!env.isPackageInstalled(APP_PACKAGE) || !apkMd5.equals(installedMd5)) {
                installApkFromModZip(env, modZip);
                writeUtf8(md5File, apkMd5 + "\n");
            }
            pendingFile.delete();
        } else {
            writeUtf8(pendingFile, apkMd5 + "\n");
        }

        // 4. Mark standalone mode active so bin/service.sh runs StandaloneRootDaemon
        File enabledFile = new File(standaloneDir, "enabled");
        writeUtf8(enabledFile, "1\n");
        makeWorldReadable644(enabledFile);
        makeWorldTraversable(pkgDir);
        makeWorldTraversable(standaloneDir);
        makeWorldTraversable(svcDir);

        return restartNeeded;
    }

    /**
     * Ensures Fitcamx.apk is installed once Android has finished booting.
     */
    public static void ensureAppInstalled(Env env, File pkgDir) throws IOException {
        if (!"1".equals(env.getprop("sys.boot_completed"))) {
            throw new IOException("Android has not finished booting yet");
        }
        File modZip = new File(pkgDir, "mod/package.zip");
        File standaloneDir = new File(pkgDir, "standalone");
        File pendingFile = new File(standaloneDir, "apk.pending");
        if (!pendingFile.isFile() && env.isPackageInstalled(APP_PACKAGE)) {
            return;
        }
        if (!modZip.isFile()) {
            throw new IOException("Missing " + modZip.getAbsolutePath());
        }
        String apkMd5;
        ZipFile zf = new ZipFile(modZip);
        try {
            ZipEntry apkEntry = zf.getEntry("app/Fitcamx.apk");
            if (apkEntry == null) {
                throw new IOException("mod/package.zip is missing app/Fitcamx.apk");
            }
            apkMd5 = md5Hex(zf.getInputStream(apkEntry));
        } finally {
            zf.close();
        }
        installApkFromModZip(env, modZip);
        standaloneDir.mkdirs();
        writeUtf8(new File(standaloneDir, "apk.md5"), apkMd5 + "\n");
        pendingFile.delete();
    }

    /**
     * Uninstalls standalone mode (or migrates cleanly to HMG-API when keepAppData == true).
     */
    public static void uninstallStandalone(Env env, File pkgDir, boolean keepAppData) throws IOException {
        if (!env.hostTest) {
            StandaloneRootDaemon.requestStopRunningDaemons();
            try {
                CarBridge.restoreGuideLines(false);
            } catch (Throwable ignored) {}
        } else {
            env.recordHostAction("stop-daemon");
        }

        restoreWifiClientConfig(env);
        env.pmUninstall(APP_PACKAGE, keepAppData);
        if (pkgDir != null) {
            deleteRecursively(new File(pkgDir, "standalone"));
        }
    }

    private static void installApkFromModZip(Env env, File modZip) throws IOException {
        File tmpDir = env.sysFile("/data/local/tmp");
        tmpDir.mkdirs();
        makeWorldTraversable(tmpDir);
        File tmpApk = new File(tmpDir, "fitcamx-install.apk");
        ZipFile zf = new ZipFile(modZip);
        try {
            extractRequiredEntry(zf, "app/Fitcamx.apk", tmpApk, false);
            makeWorldReadable644(tmpApk);
            String err = env.pmInstall(tmpApk, APP_PACKAGE);
            if (err != null) {
                throw new IOException("pm install failed: " + err);
            }
        } finally {
            zf.close();
            tmpApk.delete();
        }
    }

    private static void extractRequiredEntry(ZipFile zf, String entryName, File dest, boolean executable)
            throws IOException {
        ZipEntry entry = zf.getEntry(entryName);
        if (entry == null) {
            throw new IOException("Missing entry in mod/package.zip: " + entryName);
        }
        dest.getParentFile().mkdirs();
        InputStream in = zf.getInputStream(entry);
        try {
            FileOutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        dest.setReadable(true, false);
        dest.setWritable(true, true);
        if (executable) {
            dest.setExecutable(true, false);
        }
    }

    private static void makeWorldReadable644(File f) {
        f.setReadable(true, false);
        f.setWritable(false, false);
        f.setWritable(true, true);
    }

    private static void makeWorldTraversable(File dir) {
        if (dir != null && dir.isDirectory()) {
            dir.setReadable(true, false);
            dir.setExecutable(true, false);
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        writeBytes(dst, readBytes(src));
    }

    private static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readAllBytes(in);
        } finally {
            in.close();
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String readUtf8(File f) throws IOException {
        return new String(readBytes(f), "UTF-8");
    }

    private static void writeBytes(File f, byte[] data) throws IOException {
        if (f.getParentFile() != null) {
            f.getParentFile().mkdirs();
        }
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    private static void writeUtf8(File f, String s) throws IOException {
        writeBytes(f, s.getBytes("UTF-8"));
    }

    private static String md5Hex(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] dig = md.digest();
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < dig.length; i++) {
                int v = dig[i] & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("MD5 unavailable", e);
        } finally {
            in.close();
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        f.delete();
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            System.err.println("usage: StandaloneInstaller <install|ensure-app|uninstall|migrate-to-hmgapi|stop-daemon> ...");
            System.exit(2);
            return;
        }
        Env env = Env.fromProcessEnv();
        String cmd = args[0];
        try {
            if ("stop-daemon".equals(cmd)) {
                if (!env.hostTest) {
                    StandaloneRootDaemon.requestStopRunningDaemons();
                } else {
                    env.recordHostAction("stop-daemon");
                }
                return;
            }
            if ("install".equals(cmd)) {
                if (args.length < 3) {
                    System.err.println("usage: StandaloneInstaller install <pkgDir> <modZip>");
                    System.exit(2);
                    return;
                }
                boolean restart = installStandalone(env, new File(args[1]), new File(args[2]));
                if (restart) {
                    System.out.println("FITCAMX standalone installed: restart the head unit to finish enabling Wi-Fi client");
                } else {
                    System.out.println("FITCAMX standalone installed");
                }
                return;
            }
            if ("ensure-app".equals(cmd)) {
                File pkgDir = new File(args.length > 1 ? args[1] : StandaloneRootDaemon.DEFAULT_PKG_DIR);
                ensureAppInstalled(env, pkgDir);
                return;
            }
            if ("uninstall".equals(cmd)) {
                File pkgDir = args.length > 1 ? new File(args[1]) : null;
                uninstallStandalone(env, pkgDir, false);
                System.out.println("FITCAMX standalone uninstalled; /system restored");
                return;
            }
            if ("migrate-to-hmgapi".equals(cmd)) {
                File pkgDir = args.length > 1 ? new File(args[1]) : null;
                uninstallStandalone(env, pkgDir, true);
                System.out.println("FITCAMX standalone state migrated for HMG-API adoption");
                return;
            }
            System.err.println("Unknown command: " + cmd);
            System.exit(2);
        } catch (Throwable t) {
            System.err.println("FITCAMX standalone error: " + t.getMessage());
            System.exit(1);
        }
    }
}
