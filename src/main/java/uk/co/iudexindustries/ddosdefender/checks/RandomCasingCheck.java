package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

/**
 * Random casing. Real names capitalise at word starts ("TheBeastMaster",
 * "xXNinjaXx", "MCprogamer"); random strings scatter capitals ("aXkLpQ").
 * Counts capitals that follow a lowercase letter or digit, aren't the last
 * character, and don't begin a word of 2+ lowercase letters.
 *
 * Measured on 300k real names: 99% have at most 2, 99.9% at most 4; random
 * mixed-case bots typically have 2-3 or more.
 */
@UtilityClass
public class RandomCasingCheck {

    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        int noisy = 0;
        for (int i = 1; i < len - 1; i++) {
            char c = name.charAt(i);
            if (c < 'A' || c > 'Z') continue;
            char prev = name.charAt(i - 1);
            if (prev >= 'A' && prev <= 'Z') continue; // part of an acronym / caps run
            int lowers = 0;
            for (int j = i + 1; j < len && name.charAt(j) >= 'a' && name.charAt(j) <= 'z'; j++) lowers++;
            if (lowers < 2) noisy++;
        }
        if (noisy >= 4) return 25;
        if (noisy >= 3) return 15;
        return 0;
    }
}
