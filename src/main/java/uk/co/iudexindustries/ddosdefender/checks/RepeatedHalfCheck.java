package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class RepeatedHalfCheck {

    /**
     * Optimized repeated word-pair detection (ABAB pattern).
     * Zero allocations. No .substring(), No .equalsIgnoreCase().
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();

        // Must be even length for a perfect ABAB split
        if (len < 4 || (len & 1) != 0) return 0;

        int half = len / 2;

        // Since the original code only returns 25 if totalPairLen * 2 == len,
        // we only need to check if the first half matches the second half.
        // This simplifies the logic from O(N^2) to O(N).

        for (int i = 0; i < half; i++) {
            char c1 = name.charAt(i);
            char c2 = name.charAt(i + half);

            if (c1 != c2) {
                // Manual case-insensitive check (ASCII only)
                if (c1 >= 'A' && c1 <= 'Z') c1 = (char) (c1 + 32);
                if (c2 >= 'A' && c2 <= 'Z') c2 = (char) (c2 + 32);

                if (c1 != c2) return 0;
            }
        }

        // If the loop completes, the first half matches the second half (e.g., "AbAb", "testtest")
        return 25;
    }
}
