package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class AttackVocabularyCheck {

    /**
     * Attack / stresser vocabulary detection.
     *
     * Where BotKeywordCheck covers generic automation words (bot, macro, afk, auto), this
     * targets the vocabulary of attack tooling itself — names that advertise a
     * crasher, booter, or stresser ("CrashBot", "StressNet", "MCFlooder",
     * "botnet_09"). These terms carry little legitimate gaming meaning, so they
     * are strong intent signals.
     *
     * The keyword set is deliberately kept to tokens that rarely appear as
     * substrings of real names (e.g. "raid" is excluded — Mermaid/Braid — and
     * bare "dos" is excluded — Carlos). Tiered, first-match wins, zero
     * allocations (no toLowerCase / no contains).
     */
    private static final String[] KEYWORDS = {
            "botnet", "stresser", "exploit", "crash", "stress",
            "booter", "flood", "ddos", "nuke", "griefer"
    };
    private static final int[] SCORES = {
            30, 30, 25, 22, 22,
            20, 18, 25, 15, 15
    };

    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 4) return 0;

        for (int k = 0; k < KEYWORDS.length; k++) {
            if (containsIgnoreCase(name, KEYWORDS[k])) {
                return SCORES[k];
            }
        }
        return 0;
    }

    private boolean containsIgnoreCase(String target, String keyword) {
        int tLen = target.length();
        int kLen = keyword.length();
        if (kLen > tLen) return false;

        for (int i = 0; i <= tLen - kLen; i++) {
            boolean match = true;
            for (int j = 0; j < kLen; j++) {
                char tc = target.charAt(i + j);
                if (tc >= 'A' && tc <= 'Z') tc = (char) (tc + 32);
                if (tc != keyword.charAt(j)) { // keyword already lowercase
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }
}
