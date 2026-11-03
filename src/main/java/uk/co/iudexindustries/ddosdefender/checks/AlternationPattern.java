package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class AlternationPattern {

    /**
     * Optimized synthetic pattern detection.
     * Works on any CharSequence (String, StringBuilder, etc.) to avoid allocations.
     */
    public boolean looksSyntheticPattern(CharSequence s) {
        int len = s.length();
        if (len < 6) return false;

        // 1. Optimized Repeating Pattern Check (e.g., "ababab")
        // Replaces: s.replace(s.substring(0,2), "").isEmpty()
        if (isRepeating(s, len)) return true;

        // 2. Optimized Vowel/Consonant Switch Check
        int switches = 0;
        for (int i = 1; i < len; i++) {
            if (isVowel(s.charAt(i - 1)) != isVowel(s.charAt(i))) {
                switches++;
            }
        }

        // Use integer math: switches > len * 0.8  =>  switches * 10 > len * 8
        return (switches * 10) > (len * 8);
    }

    private boolean isRepeating(CharSequence s, int len) {
        char c0 = s.charAt(0);
        char c1 = s.charAt(1);
        for (int i = 2; i < len; i++) {
            char target = (i % 2 == 0) ? c0 : c1;
            if (s.charAt(i) != target) return false;
        }
        return true;
    }

    private boolean isVowel(char c) {
        // Fast ASCII vowel check
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' ||
                c == 'A' || c == 'E' || c == 'I' || c == 'O' || c == 'U';
    }
}
