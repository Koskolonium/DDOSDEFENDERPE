package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class HumanSegment {

    /**
     * Optimized Human-Like Segment Heuristic.
     * Single-pass scan, zero heap allocations.
     */
    public boolean isHumanLike(String segment) {
        if (segment == null) return false;
        int len = segment.length();

        // 1. Length constraint (3-12)
        if (len < 3 || len > 12) return false;

        int vowels = 0;
        int consonants = 0;
        int maxConsonantRun = 0;
        int currentConsonantRun = 0;

        int maxRepeatRun = 1;
        int currentRepeatRun = 1;
        char lastChar = '\0';

        // 2. Single Linear Scan for all heuristics
        for (int i = 0; i < len; i++) {
            char rawC = segment.charAt(i);
            // Manual case conversion for ASCII
            char c = (rawC >= 'A' && rawC <= 'Z') ? (char)(rawC + 32) : rawC;

            // Vowel vs Consonant logic
            if (isVowel(c)) {
                vowels++;
                currentConsonantRun = 0;
            } else if (c >= 'a' && c <= 'z') {
                consonants++;
                currentConsonantRun++;
                if (currentConsonantRun > maxConsonantRun) maxConsonantRun = currentConsonantRun;
            } else {
                // Non-alphabetic resets consonant cluster
                currentConsonantRun = 0;
            }

            // Character repetition logic
            if (i > 0 && c == lastChar) {
                currentRepeatRun++;
                if (currentRepeatRun > maxRepeatRun) maxRepeatRun = currentRepeatRun;
            } else {
                currentRepeatRun = 1;
            }
            lastChar = c;
        }

        // 3. Evaluate gathered metrics
        if (vowels == 0) return false; // Must have a vowel
        if (maxConsonantRun >= 4) return false; // Long consonant cluster
        if (consonants > 5 && vowels == 1) return false; // Unbalanced
        if (maxRepeatRun >= 3) return false; // Repeated chars (e.g. aaa)

        // 4. Zero-allocation synthetic check (AlternationPattern now accepts CharSequence)
        if (AlternationPattern.looksSyntheticPattern(segment)) return false;

        return true;
    }

    private boolean isVowel(char c) {
        // Fast-path ASCII check
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' ||
                c == 'A' || c == 'E' || c == 'I' || c == 'O' || c == 'U';
    }
}
