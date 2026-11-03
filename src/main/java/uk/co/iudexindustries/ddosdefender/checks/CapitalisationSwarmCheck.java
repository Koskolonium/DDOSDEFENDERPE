package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@UtilityClass
public class CapitalisationSwarmCheck {


    public String capitalizationPattern(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            if      (Character.isUpperCase(c)) sb.append('U');
            else if (Character.isLowerCase(c)) sb.append('l');
            else if (Character.isDigit(c))     sb.append('D');
            else                               sb.append('X');
        }
        return sb.toString();
    }

    /**
     * Swarm-level capitalization pattern detection.
     * Many names sharing the same caps pattern (e.g. lUlUlDD) = swarm signal.
     */
    // Scores the joining name only if its caps/digit pattern (e.g. "UlllDD") is
    // shared by a large part of the recent window — see LengthSwarmCheck for why.
    public int score(String playerName, List<String> names) {
        int size;
        if (playerName == null || names == null || (size = names.size()) < 10) return 0;
        long target = encode(playerName);
        int same = 0;
        for (int i = 0; i < size; i++) {
            String n = names.get(i);
            if (n != null && encode(n) == target) same++;
        }
        int pct = (same * 100) / size;
        if (pct >= 95) return 70;
        if (pct >= 90) return 65;
        if (pct >= 80) return 55;
        if (pct >= 70) return 45;
        if (pct >= 60) return 38;
        if (pct >= 50) return 30;
        if (pct >= 40) return 22;
        return 0;
    }

    // 2 bits per char (U=0, l=1, D=2, X=3) plus the length.
    private long encode(String n) {
        long pattern = 0L;
        int len = Math.min(n.length(), 16);
        for (int j = 0; j < len; j++) {
            char c = n.charAt(j);
            long type = (c >= 'A' && c <= 'Z') ? 0 :
                        (c >= 'a' && c <= 'z') ? 1 :
                        (c >= '0' && c <= '9') ? 2 : 3;
            pattern |= (type << (j << 1));
        }
        return pattern | ((long) n.length() << 32);
    }
}
