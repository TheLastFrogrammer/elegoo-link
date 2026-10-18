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
                long asleep = SystemClock.elapsedRealtime();
                try { Thread.sleep(500); } catch (InterruptedException stop) { return; }
                // This thread itself was held far past its half second: the phone slept or Android paused the whole app, so
                // the screen thread was not busy either. Start the count again.
                if (SystemClock.elapsedRealtime() - asleep > 1_500) { if (stallStart == 0) beat = SystemClock.elapsedRealtime(); continue; }
                long late = SystemClock.elapsedRealtime() - beat;
                if (late > 2_000 && stallStart == 0) {
                    StackTraceElement[] stack = Looper.getMainLooper().getThread().getStackTrace();
                    // Waiting for its next message: idle, with the heartbeat not yet delivered (the app was just resumed).
                    if (idle(stack)) { beat = SystemClock.elapsedRealtime(); continue; }
                    stallStart = beat; where = frames(stack);
                    Diagnostics.note(AREA, "screen thread busy for over 2 s, in: " + where);
                } else if (late <= 1_000 && stallStart != 0) {
                    Diagnostics.note(AREA, "screen thread free again after " + (beat - stallStart) / 100 / 10.0 + " s (was in: " + where + ")");
                    stallStart = 0;
                }
            }
        }, "main-thread-watch");
        watcher.setDaemon(true); watcher.start();
        // Android reports disk writes and network use on the screen thread; each place in the app is noted once (20 at most).
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            java.util.Set<String> seen = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
            android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder().detectDiskWrites().detectNetwork().detectCustomSlowCalls()
                .penaltyListener(java.util.concurrent.Executors.newSingleThreadExecutor(), violation -> {
                    String where = frames(violation.getStackTrace());
                    if (seen.size() < 20 && seen.add(where)) Diagnostics.note(AREA, violation.getClass().getSimpleName().replace("Violation", "") + " on the screen thread, in: " + where);
                }).build());
        }
    }

    /** Whether the screen thread is waiting for its next message (nothing of the app's own running). */
    static boolean idle(StackTraceElement[] stack) {
        return stack.length > 0 && stack[0].getMethodName().equals("nativePollOnce") && stack[0].getClassName().endsWith("MessageQueue");
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
