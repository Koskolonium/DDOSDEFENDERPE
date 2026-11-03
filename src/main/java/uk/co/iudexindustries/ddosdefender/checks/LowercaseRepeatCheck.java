package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class LowercaseRepeatCheck {

    /**
     * Optimized repeat pattern detection.
     * Zero allocations: No .substring(), No .toLowerCase(), No StringBuilder.
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 6) return 0;

        int i = len - 1;

        // Walk backwards through trailing lowercase characters
        // ASCII check is significantly faster than Character.isLowerCase()
        while (i >= 0) {
            char c = name.charAt(i);
            if (c >= 'a' && c <= 'z') {
                i--;
            } else {
                break;
            }
        }

        // If no lowercase suffix or the whole string is lowercase, return 0
        // (The logic implies we need a break point like a Digit or Uppercase)
        if (i == len - 1 || i == -1) return 0;

        int suffixStart = i + 1;
        int suffixLen = len - suffixStart;

        // Single-char suffix matches are noise (catches xXNameXx falsely — x matches x).
        // Require at least 3 chars to form a meaningful repeat.
        if (suffixLen < 3) return 0;

        // Check if the lowercase suffix matches the start of the name (case-insensitive)
        // This replaces lowerName.startsWith(lowerSuffix)
        for (int j = 0; j < suffixLen; j++) {
            char startChar = name.charAt(j);
            char suffixChar = name.charAt(suffixStart + j);

            // Manual case-insensitive comparison (ASCII only)
            if (startChar != suffixChar) {
                // If startChar is uppercase, convert it to lowercase for the check
                if (startChar >= 'A' && startChar <= 'Z') {
                    if ((char)(startChar + 32) != suffixChar) return 0;
                } else {
                    // Not a match
                    return 0;
                }
            }
        }

        return 15;
    }
}
