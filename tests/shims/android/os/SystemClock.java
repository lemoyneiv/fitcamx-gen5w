package android.os;

public final class SystemClock {
    private static final long START_NANO = System.nanoTime();

    private SystemClock() {}

    public static long uptimeMillis() {
        return ((System.nanoTime() - START_NANO) / 1000000L) + 1000L;
    }

    public static long elapsedRealtime() {
        return uptimeMillis();
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
