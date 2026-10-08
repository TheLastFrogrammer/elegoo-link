package io.github.thelastfrogrammer.elink;

/**
 * Bounded retries apply to connection establishment only, never to printer commands: five quick attempts (1, 2, 4, 8, 16 s),
 * then a slow tail of one attempt a minute for ten minutes, which covers a printer that reboots or a router that restarts.
 */
public final class ReconnectPolicy {
    static final int FAST = 5, TAIL = 10, TAIL_SECONDS = 60;
    private int attempts;
    public int nextDelaySeconds() {
        if (attempts >= FAST + TAIL) return -1;
        if (attempts >= FAST) { attempts++; return TAIL_SECONDS; }
        return 1 << attempts++;
    }
    public void connected() { attempts = 0; }
    public int attempts() { return attempts; }
    public boolean inTail() { return attempts > FAST; }
    /** "Retry 2/5 in 2s" while quick, "Retrying every 60 s (attempt 3 of 10)" in the tail. */
    public String describe(int delaySeconds) {
        return attempts <= FAST ? "Retry " + attempts + "/" + FAST + " in " + delaySeconds + "s. Use Disconnect to cancel."
            : "Still trying to reach the printer: attempt " + (attempts - FAST) + " of " + TAIL + ", one a minute. Use Disconnect to cancel.";
    }
}
