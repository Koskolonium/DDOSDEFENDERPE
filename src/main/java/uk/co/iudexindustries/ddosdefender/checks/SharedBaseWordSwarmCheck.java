package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public class SharedBaseWordSwarmCheck {

    private static final int MIN_BASE_LENGTH = 4;

    /**
     * Detects bot swarms that share the same base word (e.g. "koskolonium_1", "koskolonium_2").
     * Zero-allocation: no substring, no toLowerCase, no array — uses packed int and regionMatches.
     */
    public int score(String playerName, List<String> recentNameList) {
        if (playerName == null || recentNameList == null || recentNameList.size() < 2) return 0;

        int basePacked = longestAlphaSeg(playerName);
        int baseStart  = basePacked >> 8;
        int baseLen    = basePacked & 0xFF;
        if (baseLen < MIN_BASE_LENGTH) return 0;

        int matchCount = 0;
        int size = recentNameList.size();
        for (int i = 0; i < size; i++) {
            String name = recentNameList.get(i);
            if (name == null || name.equalsIgnoreCase(playerName)) continue;
            int packed = longestAlphaSeg(name);
            int segLen = packed & 0xFF;
            if (segLen == baseLen) {
                int segStart = packed >> 8;
                // Case-insensitive in-place comparison — zero allocations
                if (name.regionMatches(true, segStart, playerName, baseStart, baseLen)) {
                    matchCount++;
                }
            }
        }

        if (matchCount >= 7) return 60;
        if (matchCount >= 4) return 40;
        if (matchCount >= 2) return 25;
        return 0;
    }

    /**
     * Returns start index and length of the longest continuous run of letters, packed as:
     *   high 24 bits = start index, low 8 bits = length.
     * Minecraft names are at most 16 chars so both values fit in 8 bits safely.
     * No allocation, no toLowerCase — callers use regionMatches(true,...) for case-insensitive compare.
     */
    private int longestAlphaSeg(String name) {
        int len = name.length();
        int bestStart = 0, bestLen = 0, curStart = -1;
        for (int i = 0; i <= len; i++) {
            boolean isAlpha = i < len && (
                    (name.charAt(i) >= 'a' && name.charAt(i) <= 'z') ||
                    (name.charAt(i) >= 'A' && name.charAt(i) <= 'Z'));
            if (isAlpha) {
                if (curStart == -1) curStart = i;
            } else if (curStart != -1) {
                int curLen = i - curStart;
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
                curStart = -1;
            }
        }
        return (bestStart << 8) | bestLen;
    }
}
