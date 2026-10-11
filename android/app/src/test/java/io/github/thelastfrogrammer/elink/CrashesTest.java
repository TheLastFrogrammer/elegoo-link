package io.github.thelastfrogrammer.elink;

import org.junit.Test;
import static org.junit.Assert.*;

/** The crash record keeps exception types and the app's own frames, never messages (which can carry addresses or codes). */
public class CrashesTest {
    private static StackTraceElement frame(String cls, String method, int line) { return new StackTraceElement(cls, method, cls + ".java", line); }

    @Test public void keepsTypesAndAppFramesButNoMessages() {
        IllegalStateException cause = new IllegalStateException("access code 123456 refused by 192.168.1.50");
        cause.setStackTrace(new StackTraceElement[] {frame("org.eclipse.paho.Client", "connect", 10), frame("io.github.thelastfrogrammer.elink.Cc2Session", "open", 88)});
        RuntimeException error = new RuntimeException("wrapped: " + cause.getMessage(), cause);
        error.setStackTrace(new StackTraceElement[] {frame("android.os.Handler", "dispatch", 1), frame("io.github.thelastfrogrammer.elink.MainActivity", "render", 1512),
            frame("android.os.Looper", "loop", 2), frame("io.github.thelastfrogrammer.elink.PrinterService", "changed", 300)});
        String text = Crashes.describe("main", error);
        assertEquals("On main: RuntimeException at android.os.Handler.dispatch:1 ← MainActivity.render:1512 ← PrinterService.changed:300; "
            + "caused by IllegalStateException at org.eclipse.paho.Client.connect:10 ← Cc2Session.open:88", text);
        assertFalse(text.contains("123456")); assertFalse(text.contains("192.168"));
    }

    @Test public void workerThreadsAreNamedPlainly() {
        assertTrue(Crashes.describe("pool-3-thread-1", new NullPointerException()).startsWith("On a worker thread: NullPointerException"));
    }
}
