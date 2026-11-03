package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class TruncatedRepeatCheck {

    /**
     * Ultra-optimized truncated repeat detection.
     * Zero allocations. No .substring(), No .toLowerCase().
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 8) return 0;

        // Leading word must start with uppercase
        char first = name.charAt(0);
        if (first < 'A' || first > 'Z') return 0;

        // leadLen is the length of "WordA" (e.g., "Stale")
        int maxLead = Math.min(12, len - 5); // len-5 ensures room for WordB (min 2) + suffix (min 3)

        for (int leadLen = 3; leadLen <= maxLead; leadLen++) {

            // Look for a lowercase truncated repeat (suffix) later in the name
            // j is the start index of the suffix
            for (int j = leadLen + 2; j <= len - 3; j++) {
                int suffixLen = len - j;

                // Optimized suffix check: Must be all lowercase AND match the start of the name
                if (isSuffixMatch(name, j, suffixLen)) {
                    return 25;
                }
            }
        }

        return 0;
    }

    /**
     * Helper to check if name.substring(j) is all lowercase AND
     * is a prefix of name.substring(0, leadLen) ignoring case.
     */
    private boolean isSuffixMatch(String name, int start, int suffixLen) {
        for (int i = 0; i < suffixLen; i++) {
            char suffixChar = name.charAt(start + i);

            // Suffix must be all lowercase letters
            if (suffixChar < 'a' || suffixChar > 'z') return false;

            char startChar = name.charAt(i);
            // Case-insensitive comparison: convert startChar to lower if needed
            if (startChar >= 'A' && startChar <= 'Z') {
                if ((char)(startChar + 32) != suffixChar) return false;
            } else if (startChar != suffixChar) {
                return false;
            }
        }
        return true;
    }
}
