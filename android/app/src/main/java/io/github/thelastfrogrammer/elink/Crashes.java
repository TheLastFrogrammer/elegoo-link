package io.github.thelastfrogrammer.elink;

import android.content.Context;

/**
 * A local record of crashes for the diagnostics report, so a problem on the phone leaves a trace to share. It keeps the
 * exception types and where they happened in the app's own code, never exception messages (they can carry addresses, names
 * or codes). Nothing is sent anywhere; the report is shared only from Settings › Share diagnostics…, by the user.
 */
final class Crashes {
    static final String AREA = "Crashes";
    static final String SEEN_KEY = "lastCrashAt";
    private static final String APP = "io.github.thelastfrogrammer.elink.";

    private Crashes() { }

    /** Records uncaught exceptions from here on, then hands them to Android as before. Safe to call more than once. */
    static void install(Context context) {
        Context app = context.getApplicationContext();
        Diagnostics.init(app.getFilesDir());
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof Recorder) return;
        Thread.setDefaultUncaughtExceptionHandler(new Recorder(app, previous));
    }

    /** "On main: IllegalStateException at MainActivity.render:1512 ← …; caused by NullPointerException at …" — types and app frames only. */
    static String describe(String thread, Throwable error) {
        StringBuilder text = new StringBuilder("On ").append(thread.startsWith("pool-") ? "a worker thread" : thread).append(": ");
        int depth = 0;
        for (Throwable t = error; t != null && depth < 4; t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (depth > 0) text.append("; caused by ");
            text.append(t.getClass().getSimpleName());
            StackTraceElement[] frames = t.getStackTrace();
            int shown = 0;
            for (int i = 0; i < frames.length && shown < 6; i++) {
                StackTraceElement f = frames[i];
                boolean ours = f.getClassName().startsWith(APP);
                if (!ours && !(i == 0 && shown == 0)) continue;   // the throwing frame, then the app's own
                text.append(shown == 0 ? " at " : " ← ").append(ours ? f.getClassName().substring(APP.length()) : f.getClassName())
                    .append('.').append(f.getMethodName()).append(':').append(f.getLineNumber());
                shown++;
            }
        }
        return text.toString();
    }

    private static final class Recorder implements Thread.UncaughtExceptionHandler {
        private final Context app; private final Thread.UncaughtExceptionHandler previous;
        Recorder(Context app, Thread.UncaughtExceptionHandler previous) { this.app = app; this.previous = previous; }
        @Override public void uncaughtException(Thread thread, Throwable error) {
            try {
                Diagnostics.note(AREA, describe(thread.getName(), error));
                app.getSharedPreferences("workshop-settings", Context.MODE_PRIVATE).edit().putLong(SEEN_KEY, System.currentTimeMillis()).commit();
            } catch (Throwable ignored) { }   // recording must never stand in the way of the crash itself
            if (previous != null) previous.uncaughtException(thread, error);
        }
    }
}
