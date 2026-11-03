package uk.co.iudexindustries.ddosdefender.mojang;

import uk.co.iudexindustries.ddosdefender.checks.NameScorer;
import lombok.experimental.UtilityClass;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Mojang lookups this server may spend per minute, shared out by how
 * human a name looks (its NameScorer score) rather than first come, first
 * served. While the budget is barely used everyone gets a lookup; as it fills
 * up, suspicious-looking names are turned away first, so in a flood of bots
 * the last part of the budget is still there for real newcomers.
 *
 *   score  < 25   may use the budget until it is 100% spent
 *   score 25-59   until 60% spent
 *   score 60-99   until 30% spent
 *   score 100+    never (blocked before getting here)
 */
@UtilityClass
public class MojangBudget {

    public static final int LIMIT_PER_MINUTE = 100;
    private static final long WINDOW_MS = 60_000L;

    private static final AtomicInteger used = new AtomicInteger();
    private static volatile long windowStart = System.currentTimeMillis();

    /** Takes one lookup if this score's share allows it. */
    public boolean tryTake(int score, long now) {
        if (score >= 100) return false;
        if (now - windowStart > WINDOW_MS) {
            windowStart = now;
            used.set(0);
        }
        int cap = capFor(score);
        // Check first so a refused name doesn't eat budget; a race can let
        // one or two extra through, which is harmless.
        if (used.get() >= cap) return false;
        return used.incrementAndGet() <= cap;
    }

    static int capFor(int score) {
        if (score < 25) return LIMIT_PER_MINUTE;
        if (score < 60) return LIMIT_PER_MINUTE * 60 / 100;
        return LIMIT_PER_MINUTE * 30 / 100;
    }

    /** Lookups spent in the current minute (for status output and tests). */
    public int used() {
        return used.get();
    }

    /** Test hook: start a fresh window. */
    static void reset(long now) {
        windowStart = now;
        used.set(0);
    }
}
