package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class RepeatedCharacterCheck {

    /**
     * Repeated-character-run detection.
     *
     * Padding a name to a target length or filling a template with a single key
     * produces long identical runs — "aaaaaa", "Steveeee", "loool0000". HumanSegment
     * already rejects 3+ repeats when judging a segment as human-like, but that
     * signal is never scored on its own. A run of one character stretched across
     * the name is a cheap, reliable bot tell.
     *
     * Case-insensitive so "LOOOl" counts as a run of the same letter.
     * Single-pass, zero allocations.
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 3) return 0;

        int longestRun = 1;
        int currentRun = 1;
        char prev = fold(name.charAt(0));

        for (int i = 1; i < len; i++) {
            char c = fold(name.charAt(i));
            if (c == prev) {
                currentRun++;
                if (currentRun > longestRun) longestRun = currentRun;
            } else {
                currentRun = 1;
            }
            prev = c;
        }

        // Three in a row ("Shhh", "xxx") is ordinary.
        if (longestRun >= 6) return 25;
        if (longestRun == 5) return 18;
        if (longestRun == 4) return 10;
        return 0;
    }

    private char fold(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }
}
