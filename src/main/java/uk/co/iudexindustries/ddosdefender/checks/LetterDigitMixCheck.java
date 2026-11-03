package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class LetterDigitMixCheck {

    /**
     * Alternating letter/digit detection.
     *
     * A recognisable generator signature is strict letter-digit alternation:
     * "a1b2c3d4", "x9y8z7", "p0o9i8". Each adjacent pair flips between a letter
     * and a digit. This is almost never produced by a human — even "Word2024"
     * only flips once — so a high alternation ratio over the whole name is a
     * strong synthetic marker that SyntheticWordCheck's vowel/consonant logic misses.
     *
     * Single-pass over adjacent pairs, zero allocations.
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 6) return 0;

        int letters = 0;
        int digits = 0;
        int alternations = 0;

        char prev = name.charAt(0);
        if (isAlpha(prev)) letters++;
        else if (isDigit(prev)) digits++;

        for (int i = 1; i < len; i++) {
            char c = name.charAt(i);
            if (isAlpha(c)) letters++;
            else if (isDigit(c)) digits++;

            // Count a flip when exactly one of the pair is a digit and the other a letter.
            boolean prevDigit = isDigit(prev);
            boolean curDigit  = isDigit(c);
            boolean prevAlpha = isAlpha(prev);
            boolean curAlpha  = isAlpha(c);
            if ((prevDigit && curAlpha) || (prevAlpha && curDigit)) {
                alternations++;
            }
            prev = c;
        }

        // Needs real substance on both sides — filters "Word12" style names.
        if (letters < 2 || digits < 2) return 0;
        // Flips between letters and digits: real names rarely flip more than 3
        // times ("xX1Xx", "Name123"; 99% <= 3), hex/UUID-style strings usually 6+.
        int flipScore = alternations >= 8 ? 30 : alternations >= 6 ? 20 : alternations >= 4 ? 10 : 0;

        int pct = (alternations * 100) / (len - 1);
        if (pct >= 80) return Math.max(20, flipScore);
        if (pct >= 60) return Math.max(10, flipScore);
        return flipScore;
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
