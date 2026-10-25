package io.github.thelastfrogrammer.elink.tv;

import java.util.Random;

/**
 * How long to wait before trying the phone again: doubling from `first` up to `max`, each wait shifted by up to a fifth
 * either way so several TVs never knock on the phone in step. Reset once a connection has stayed up. Pure Java.
 */
final class Backoff {
    private final long first, max;
    private final Random random;
    private long base;

    Backoff(long first, long max, Random random) { this.first = first; this.max = Math.max(first, max); this.random = random; }

    /** The next wait (ms). */
    synchronized long next() {
        base = base == 0 ? first : Math.min(max, base * 2);
        return Math.round(base * (0.8 + 0.4 * random.nextDouble()));
    }
    synchronized void reset() { base = 0; }
    /** At least this long next time (a phone that said "busy"), whatever the steps so far. */
    synchronized void atLeast(long ms) { if (base < ms / 2) base = Math.min(max, ms / 2); }
}
