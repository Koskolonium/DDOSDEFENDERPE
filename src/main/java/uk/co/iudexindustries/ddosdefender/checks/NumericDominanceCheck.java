package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class NumericDominanceCheck {

    /**
     * Numeric-dominance detection.
     *
     * Generators that key names off a counter or a random integer produce names
     * that are mostly digits ("user1928374", "2847561x") or carry a long
     * contiguous digit run. Human names occasionally end in a year or a small
     * number, but rarely cross 50% digits or run 6+ digits in a row.
     *
     * "Word + 2-4 digits" is the most common human shape, so this deliberately
     * only fires well above that band. Pure-digit names are left to
     * DegenerateNameCheck (no-letter degenerate). Single-pass, zero allocations.
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 6) return 0;

        int digits = 0;
        int letters = 0;
        int maxDigitRun = 0;
        int currentDigitRun = 0;

        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
                currentDigitRun++;
                if (currentDigitRun > maxDigitRun) maxDigitRun = currentDigitRun;
            } else {
                currentDigitRun = 0;
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) letters++;
            }
        }

        // Needs at least one letter here — all-digit names are DegenerateNameCheck's job.
        if (letters == 0 || digits == 0) return 0;

        int ratio = (digits * 100) / len;

        int score = 0;
        // Long contiguous digit run — the clearest signal.
        // Long numbers are common in real names ("kim1234567890"); kept gentle.
        if (maxDigitRun >= 8)      score = 20;
        else if (maxDigitRun >= 6) score = 10;

        // High digit ratio across the whole name.
        if (ratio >= 60 && score < 15) score = 15;
        else if (ratio >= 50 && score < 8) score = 8;

        return score;
    }
}
