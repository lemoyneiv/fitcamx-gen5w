package android.os;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Handler {
    private static final ScheduledExecutorService SCHED = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TestMainHandler");
        t.setDaemon(true);
        return t;
    });

    public Handler() {}

    public Handler(Looper looper) {}

    public boolean post(Runnable r) {
        if (r != null) {
            SCHED.execute(r);
        }
        return true;
    }

    public boolean postDelayed(Runnable r, long delayMillis) {
        if (r != null) {
            SCHED.schedule(r, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
        }
        return true;
    }

    public void removeCallbacks(Runnable r) {}
    public void removeCallbacksAndMessages(Object token) {}
}
