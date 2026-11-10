package android.content;

import java.io.File;

public abstract class Context {
    public static final String WIFI_SERVICE = "wifi";

    public File getFilesDir() {
        return null;
    }

    public Context getApplicationContext() {
        return this;
    }

    public Object getSystemService(String name) {
        return null;
    }

    public android.content.pm.PackageManager getPackageManager() {
        return null;
    }

    public String getPackageName() {
        return "fitcamx.app";
    }
}
