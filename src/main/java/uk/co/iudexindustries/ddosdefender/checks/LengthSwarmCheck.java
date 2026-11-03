package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;
import java.util.List;

@UtilityClass
public class LengthSwarmCheck {

    // Scores the joining name only if it shares its length with a large part of
    // the recent window: on a busy server real names cluster around 8-12
    // characters anyway, and a real player joining during a bot wave of a
    // different length must not inherit the wave's score.
    public int score(String playerName, List<String> names) {
        int size;
        if (playerName == null || names == null || (size = names.size()) < 10) return 0;
        int target = playerName.length();
        int same = 0;
        for (int i = 0; i < size; i++) {
            String n = names.get(i);
            if (n != null && n.length() == target) same++;
        }
        int pct = (same * 100) / size;
        if (pct >= 90) return 50;
        if (pct >= 80) return 40;
        if (pct >= 70) return 30;
        if (pct >= 60) return 25;
        if (pct >= 50) return 18;
        if (pct >= 40) return 10;
        return 0;
    }
}
