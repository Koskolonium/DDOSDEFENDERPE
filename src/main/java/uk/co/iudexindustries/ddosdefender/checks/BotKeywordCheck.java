package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class BotKeywordCheck {

    // Tiered by how exclusively they signal automation rather than human gaming culture.
    // Removed: "farm"/"grind"/"mob" (FarmKing, MobSlayer, GrindSet are common human names).
    // Removed: "alt" (substring of Walter, Alternate, Cobalt → too many false positives).
    private static final String[] KEYWORDS = {"bot", "macro", "afk", "auto"};
    private static final int[]    SCORES   = { 25,    20,      8,     5  };

    /**
     * Tiered bot keyword detection.
     * Zero allocations. No .toLowerCase(), No .contains().
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 3) return 0;

        for (int k = 0; k < KEYWORDS.length; k++) {
            if (containsIgnoreCase(name, KEYWORDS[k])) {
                return SCORES[k];
            }
        }

        return 0;
    }

    /**
     * A zero-allocation "contains" implementation.
     */
    private boolean containsIgnoreCase(String target, String keyword) {
        int tLen = target.length();
        int kLen = keyword.length();

        if (kLen > tLen) return false;

        // Scan the target string for the keyword
        for (int i = 0; i <= tLen - kLen; i++) {
            boolean match = true;
            for (int j = 0; j < kLen; j++) {
                char tChar = target.charAt(i + j);
                char kChar = keyword.charAt(j); // already lowercase

                // Fast ASCII lowercase conversion for comparison
                if (tChar >= 'A' && tChar <= 'Z') {
                    tChar = (char) (tChar + 32);
                }

                if (tChar != kChar) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }
}
