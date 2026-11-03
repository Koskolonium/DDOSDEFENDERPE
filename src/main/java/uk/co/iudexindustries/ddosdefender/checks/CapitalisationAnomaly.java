package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class CapitalisationAnomaly {

    /**
     * Ultra-optimized Capitalization Anomaly Detection.
     * Single-pass scan, zero allocations, no Character class overhead.
     */
    public boolean hasCapitalizationAnomaly(String name) {
        if (name == null) return false;
        int len = name.length();
        if (len < 3) return false;

        int upperCount = 0;
        int lowerCount = 0;
        int switches = 0;
        int currentCluster = 0;

        boolean prevWasUpper = false;
        boolean prevWasLetter = false;

        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            boolean isUpper = (c >= 'A' && c <= 'Z');
            boolean isLower = (c >= 'a' && c <= 'z');

            if (isUpper) {
                upperCount++;
                currentCluster++;
                // Optimization: Condition 3 (Cluster) exit
                if (currentCluster >= 3) return true;

                if (prevWasLetter && !prevWasUpper) {
                    switches++;
                }
                prevWasUpper = true;
                prevWasLetter = true;
            } else if (isLower) {
                lowerCount++;
                currentCluster = 0;

                if (prevWasLetter && prevWasUpper) {
                    switches++;
                }
                prevWasUpper = false;
                prevWasLetter = true;
            } else {
                // Not a letter (digit or underscore)
                currentCluster = 0;
                prevWasLetter = false;
            }
        }

        // Condition 1: Uppercase dominance
        int totalLetters = upperCount + lowerCount;
        if (upperCount > 0 && upperCount >= lowerCount && totalLetters >= 4) {
            return true;
        }

        // Condition 2: Alternating case pattern (e.g. AbAbA)
        // switches >= (len / 2)
        return switches >= (len >> 1);
    }
}
