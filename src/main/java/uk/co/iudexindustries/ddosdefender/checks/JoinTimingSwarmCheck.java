package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;
import java.util.List;
import java.util.RandomAccess;

@UtilityClass
public class JoinTimingSwarmCheck {

    /**
     * Optimized Timing Analysis.
     * Uses integer math and avoids Iterator allocations.
     */
    public int score(List<Long> recentTimestamps, long newTimestamp) {
        int size;
        if (recentTimestamps == null || (size = recentTimestamps.size()) < 5) {
            return 0;
        }

        // Use index-based access to avoid Iterator/Long-wrapper overhead
        long lastTimestamp = recentTimestamps.get(size - 1);
        long diff = newTimestamp - lastTimestamp;
        if (diff <= 0) diff = 1;

        long totalDiffs = 0;
        int count = 0;

        // Optimized loop: assuming ArrayList for O(1) get()
        if (recentTimestamps instanceof RandomAccess) {
            for (int i = 1; i < size; i++) {
                long d = recentTimestamps.get(i) - recentTimestamps.get(i - 1);
                if (d > 0) {
                    totalDiffs += d;
                    count++;
                }
            }
        } else {
            // Fallback for non-RandomAccess lists
            long prev = recentTimestamps.get(0);
            for (int i = 1; i < size; i++) {
                long curr = recentTimestamps.get(i);
                long d = curr - prev;
                if (d > 0) {
                    totalDiffs += d;
                    count++;
                }
                prev = curr;
            }
        }

        if (count == 0) return 0;

        // Integer math optimization: Scale by 100 to calculate percentage
        // ratio = (diff * 100) / (totalDiffs / count)
        // Simplified: (diff * 100 * count) / totalDiffs
        long scaledRatio = (diff * 100 * count) / totalDiffs;

        // -----------------------------
        // Tiered scoring (Ratio 1.00 = 100)
        // -----------------------------
        if (scaledRatio >= 98 && scaledRatio <= 102) return 30;
        if (scaledRatio >= 96 && scaledRatio <= 104) return 25;
        if (scaledRatio >= 94 && scaledRatio <= 106) return 20;
        if (scaledRatio >= 92 && scaledRatio <= 108) return 15;
        if (scaledRatio >= 90 && scaledRatio <= 110) return 10;

        return 0;
    }
}
