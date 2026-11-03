package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.List;

@UtilityClass
public class NumberSequenceSwarmCheck {

    private static final int MIN_WINDOW = 5;

    /**
     * Numeric-suffix progression (swarm).
     *
     * A counter-driven flood joins as "name1", "name2", … "nameN" — the base
     * word may differ, but the trailing numbers march through a near-consecutive
     * range. SharedBaseWordSwarmCheck catches shared *base words*; this catches the *numeric
     * progression* even when the words vary, by looking at how tightly the
     * trailing-digit suffixes of the recent window cluster together.
     *
     * Only names shaped "letters then 1-6 digits" contribute, which filters out
     * random human names that merely happen to end in a number. Suffixes are
     * sorted and the longest near-consecutive run (gap ≤ 2) is scored.
     *
     * One small int[] is allocated per call — acceptable for a swarm check that
     * runs once per login, mirroring SimilarNameSwarmCheck's per-call DP cost.
     */
    public int score(String playerName, List<String> recentNameList) {
        if (recentNameList == null) return 0;
        int size = recentNameList.size();
        if (size < MIN_WINDOW) return 0;

        int[] suffixes = new int[size];
        int count = 0;

        for (int i = 0; i < size; i++) {
            int suffix = templatedSuffix(recentNameList.get(i));
            if (suffix >= 0) suffixes[count++] = suffix;
        }

        if (count < 4) return 0;

        Arrays.sort(suffixes, 0, count);

        // Longest run of near-consecutive suffixes (allow small gaps for drops).
        int longestRun = 1;
        int currentRun = 1;
        for (int i = 1; i < count; i++) {
            int gap = suffixes[i] - suffixes[i - 1];
            if (gap == 0) {
                continue; // duplicate suffix value — neither extends nor breaks the run
            } else if (gap <= 2) {
                currentRun++;
                if (currentRun > longestRun) longestRun = currentRun;
            } else {
                currentRun = 1;
            }
        }

        if (longestRun >= 15) return 50;
        if (longestRun >= 8)  return 35;
        if (longestRun >= 4)  return 20;
        return 0;
    }

    /**
     * Returns the trailing-digit value for a name shaped "letters then 1-6
     * digits" (optionally with a single underscore before the digits), or -1 if
     * the name does not fit the template. Zero allocation — parses digits in place.
     */
    private int templatedSuffix(String name) {
        if (name == null) return -1;
        int len = name.length();
        if (len < 2) return -1;

        int i = len - 1;
        int digitCount = 0;
        long value = 0;
        long place = 1;
        while (i >= 0 && name.charAt(i) >= '0' && name.charAt(i) <= '9') {
            value += (name.charAt(i) - '0') * place;
            place *= 10;
            digitCount++;
            i--;
        }
        if (digitCount < 1 || digitCount > 6) return -1;

        // Optional single separator between the word and the number.
        if (i >= 0 && name.charAt(i) == '_') i--;

        // Everything before must be letters, and there must be at least one.
        if (i < 0) return -1;
        for (int j = 0; j <= i; j++) {
            char c = name.charAt(j);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return -1;
        }

        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
