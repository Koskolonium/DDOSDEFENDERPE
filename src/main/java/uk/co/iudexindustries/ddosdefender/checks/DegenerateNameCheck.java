package uk.co.iudexindustries.ddosdefender.checks;

import uk.co.iudexindustries.ddosdefender.protection.LoginFormat;
import lombok.experimental.UtilityClass;

@UtilityClass
public class DegenerateNameCheck {

    /**
     * Degenerate name pattern detection.
     *
     * Catches structurally invalid or trivially generated names that slip past
     * LoginFormat's character-level validation but are never used by real players:
     *   - All same character ("aaaaaaaa", "_______")
     *   - No letters at all ("_123_456", "___")
     *   - Multiple leading or trailing underscores ("__Steve", "Player__")
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len == 0) return 0;

        // All same character
        char first = name.charAt(0);
        boolean allSame = true;
        for (int i = 1; i < len; i++) {
            if (name.charAt(i) != first) { allSame = false; break; }
        }
        if (allSame && len >= 3) return 60;

        // No letters — only underscores and/or digits
        boolean hasLetter = false;
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) { hasLetter = true; break; }
        }
        if (!hasLetter) return 30;

        // Multiple leading or trailing underscores
        int leading = 0;
        while (leading < len && name.charAt(leading) == '_') leading++;
        int trailing = 0;
        while (trailing < len && name.charAt(len - 1 - trailing) == '_') trailing++;
        if (leading >= 2 || trailing >= 2) return 15;

        return 0;
    }
}