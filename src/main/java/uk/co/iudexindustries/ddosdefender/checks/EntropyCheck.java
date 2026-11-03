package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

import java.util.Arrays;

@UtilityClass
public class EntropyCheck {

    private static final double LN2 = 0.6931471805599453;

    // Reuse the frequency table across calls on the same thread — avoids allocating
    // int[128] on every login check (10 logins/sec from the queue).
    private static final ThreadLocal<int[]> FREQ_BUF = ThreadLocal.withInitial(() -> new int[128]);

    /**
     * Shannon entropy of the username's character distribution.
     *
     * Human names follow linguistic patterns and cluster around 1.5–3.0 bits.
     * Bot names from a uniform random character pool produce consistently high
     * entropy (>3.5 bits) for names of 10+ characters.
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 8) return 0;

        int[] freq = FREQ_BUF.get();
        Arrays.fill(freq, 0); // JVM turns this into a SIMD memset — very fast

        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (c < 128) freq[c]++;
        }

        double entropy = 0.0;
        for (int i = 0; i < 128; i++) {
            if (freq[i] == 0) continue;
            double p = (double) freq[i] / len;
            entropy -= p * (Math.log(p) / LN2);
        }

        if (len >= 10 && entropy > 3.8) return 20;
        if (len >= 12 && entropy > 3.6) return 12;
        if (len >= 10 && entropy > 3.5) return 8;
        return 0;
    }
}
