package uk.co.iudexindustries.ddosdefender.checks;

import uk.co.iudexindustries.ddosdefender.listener.LoginListener;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ImpersonationCheck {

    /**
     * Impersonation / default-identity detection.
     *
     * Bots often adopt a system or default identity to blend in — "Player123",
     * "Server_", "Console", "Guest42", "Default1", or the "MHF_" mob-head prefix.
     * Unlike BotKeywordCheck (automation keywords) these are whole identities that a real
     * account almost never registers, especially once a numeric/underscore suffix
     * is appended by a counter.
     *
     * Human first names ("Steve", "Alex") are intentionally excluded — they are
     * legitimate IGNs, and base-word swarms of them are already handled by
     * SharedBaseWordSwarmCheck and the LoginListener base-name heuristic.
     *
     * Prefix-matched, zero allocations (no toLowerCase / no substring).
     */
    private static final String[] IDENTITIES = {
            "player", "server", "console", "administrator", "admin",
            "default", "guest", "notch", "herobrine", "mhf_"
    };
    // Base score when the name IS the identity, plus a bonus when a
    // digit/underscore suffix is appended (the counter-driven bot shape).
    private static final int[] BASE_SCORES = {
            10, 12, 12, 12, 12,
            12, 10, 15, 15, 15
    };
    private static final int SUFFIX_BONUS = 13;

    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 4) return 0;

        for (int k = 0; k < IDENTITIES.length; k++) {
            String id = IDENTITIES[k];
            int idLen = id.length();
            if (idLen > len) continue;

            if (!prefixMatchesIgnoreCase(name, id, idLen)) continue;

            // Exact identity?
            if (idLen == len) return BASE_SCORES[k];

            // Otherwise the remainder must be purely a numeric/underscore suffix
            // ("Player123", "Server_", "Guest_7") to count as the bot shape.
            if (isDigitUnderscoreTail(name, idLen)) {
                return BASE_SCORES[k] + SUFFIX_BONUS;
            }
        }
        return 0;
    }

    private boolean prefixMatchesIgnoreCase(String name, String keyword, int kLen) {
        for (int j = 0; j < kLen; j++) {
            char nc = name.charAt(j);
            if (nc >= 'A' && nc <= 'Z') nc = (char) (nc + 32);
            if (nc != keyword.charAt(j)) return false; // keyword already lowercase
        }
        return true;
    }

    private boolean isDigitUnderscoreTail(String name, int from) {
        int len = name.length();
        boolean sawDigit = false;
        for (int i = from; i < len; i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') sawDigit = true;
            else if (c != '_') return false;
        }
        return sawDigit || from < len; // trailing "_" alone (e.g. "Server_") also qualifies
    }
}
