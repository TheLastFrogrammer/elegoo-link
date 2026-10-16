package io.github.thelastfrogrammer.elink;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;

/**
 * Notes in the log when the app's screen thread is held up for more than two seconds: how long, and which of the app's own
 * methods it was in (no data, no addresses). A thread outside the screen thread checks a heartbeat every half second.
 */
final class MainThreadWatch {
    static final String AREA = "App responsiveness";
    private static volatile boolean started;
    private static volatile long beat;

    static synchronized void start() {
        if (started) return; started = true; beat = SystemClock.elapsedRealtime();
        Handler main = new Handler(Looper.getMainLooper());
        Thread watcher = new Thread(() -> {
            long stallStart = 0; String where = "";
            while (true) {
                main.post(() -> beat = SystemClock.elapsedRealtime());
                try { Thread.sleep(500); } catch (InterruptedException stop) { return; }
                long late = SystemClock.elapsedRealtime() - beat;
                if (late > 2_000 && stallStart == 0) {
                    stallStart = beat; where = frames(Looper.getMainLooper().getThread().getStackTrace());
                    Diagnostics.note(AREA, "screen thread busy for over 2 s, in: " + where);
                } else if (late <= 1_000 && stallStart != 0) {
                    Diagnostics.note(AREA, "screen thread free again after " + (beat - stallStart) / 100 / 10.0 + " s (was in: " + where + ")");
                    stallStart = 0;
                }
            }
        }, "main-thread-watch");
        watcher.setDaemon(true); watcher.start();
    }

    /** The app's own frames, innermost first (at most 6), plus the innermost frame overall when it is a library's. */
    static String frames(StackTraceElement[] stack) {
        List<String> out = new ArrayList<>();
        if (stack.length > 0 && !stack[0].getClassName().startsWith("io.github.thelastfrogrammer")) out.add(short_(stack[0]));
        for (StackTraceElement e : stack) if (e.getClassName().startsWith("io.github.thelastfrogrammer") && out.size() < 7) out.add(short_(e));
        return out.isEmpty() ? "(no app code: Android itself)" : String.join(" < ", out);
    }
    private static String short_(StackTraceElement e) {
        String cls = e.getClassName(); cls = cls.substring(cls.lastIndexOf('.') + 1);
        return cls + "." + e.getMethodName() + ":" + e.getLineNumber();
    }
}
