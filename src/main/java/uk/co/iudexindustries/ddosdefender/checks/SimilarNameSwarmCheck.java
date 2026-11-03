package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public class SimilarNameSwarmCheck {

    private static final int MAX_DISTANCE = 2;

    /**
     * Levenshtein distance clustering over recent login names.
     *
     * Bot floods often use slight variations of a base name (substitutions,
     * insertions) to evade the simple base-name check. If a significant portion
     * of recent names are within edit distance 2 of the current name, this is a
     * swarm signal that goes beyond what LengthSwarmCheck/CapitalisationSwarmCheck detect.
     *
     * Uses a thread-local 17x17 DP table to avoid allocation on every call.
     * Per-pair cost is O(16x16) = 256 ops; for 60 recent names: ~15k ops total.
     */
    private static final ThreadLocal<int[][]> DP = ThreadLocal.withInitial(() -> new int[17][17]);

    public int score(String name, List<String> recentNameList) {
        if (name == null || recentNameList == null) return 0;
        int size = recentNameList.size();
        if (size < 5) return 0;

        int closeCount = 0;
        for (int i = 0; i < size; i++) {
            String recent = recentNameList.get(i);
            if (recent == null || recent.equalsIgnoreCase(name)) continue;
            if (editDistance(name, recent) <= MAX_DISTANCE) {
                closeCount++;
            }
        }

        int pct = (closeCount * 100) / size;
        if (pct >= 50) return 45;
        if (pct >= 30) return 30;
        if (pct >= 15) return 15;
        return 0;
    }

    private int editDistance(String a, String b) {
        int la = a.length();
        int lb = b.length();

        // If length difference exceeds the threshold, distance must exceed it too
        if (Math.abs(la - lb) > MAX_DISTANCE) return MAX_DISTANCE + 1;

        int[][] dp = DP.get();

        for (int i = 0; i <= la; i++) dp[i][0] = i;
        for (int j = 0; j <= lb; j++) dp[0][j] = j;

        for (int i = 1; i <= la; i++) {
            char ac = a.charAt(i - 1);
            if (ac >= 'A' && ac <= 'Z') ac = (char)(ac + 32);

            for (int j = 1; j <= lb; j++) {
                char bc = b.charAt(j - 1);
                if (bc >= 'A' && bc <= 'Z') bc = (char)(bc + 32);

                dp[i][j] = ac == bc
                        ? dp[i-1][j-1]
                        : 1 + Math.min(dp[i-1][j-1], Math.min(dp[i-1][j], dp[i][j-1]));
            }
        }

        return dp[la][lb];
    }
}