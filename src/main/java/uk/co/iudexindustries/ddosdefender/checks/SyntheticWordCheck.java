package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SyntheticWordCheck {

    /**
     * Ultra-optimized Synthetic Word Scoring.
     * Zero-allocation, single-pass character analysis.
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len == 0) return 0;

        int vowels = 0;
        int letters = 0;
        int maxConsonantRun = 0;
        int currentConsonantRun = 0;

        // Single-pass scan for letters, vowels and consonant clusters
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);

            if (isAlpha(c)) letters++;
            // 1. Vowel Check (Case-insensitive ASCII)
            if (isVowel(c)) {
                vowels++;
                currentConsonantRun = 0;
            }
            // 2. Consonant Run Check
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                currentConsonantRun++;
                if (currentConsonantRun > maxConsonantRun) maxConsonantRun = currentConsonantRun;
            }
            else {
                currentConsonantRun = 0;
            }
        }

        // Weights measured against 300k real names. A 4-consonant run is ordinary
        // ("Christopher", "Schmidt": 9% of real names), 5+ is rare; vowel/consonant
        // alternation is as common in real fantasy names as in generated ones, so
        // it only counts on longer names; in-word digits are LeetSpeakCheck's job.
        boolean noVowels  = vowels == 0 && letters >= 5;
        boolean fewVowels = vowels == 1 && letters >= 8;
        boolean altPattern = len >= 8 && AlternationPattern.looksSyntheticPattern(name);
        boolean capsAnomaly = CapitalisationAnomaly.hasCapitalizationAnomaly(name);
        int score = 0;
        if (noVowels)                 score += 20;
        else if (fewVowels)           score += 10;
        if (maxConsonantRun >= 6)     score += 20;
        else if (maxConsonantRun == 5) score += 10;
        if (altPattern)               score += 10;
        if (capsAnomaly && score > 0) score += 5;
        return score;
    }

    private boolean isVowel(char c) {
        // Fast-path ASCII check
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' ||
                c == 'A' || c == 'E' || c == 'I' || c == 'O' || c == 'U';
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
