package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class WordNumberEchoCheck {

    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 8) return 0;

        // Step 1: Find Word A
        int wordAStart = 0;
        int wordAEnd = findWordEnd(name, wordAStart);
        if (wordAEnd - wordAStart < 2) return 0;

        // Step 2: Find Word B
        int wordBStart = wordAEnd;
        if (wordBStart < len && name.charAt(wordBStart) == '_') wordBStart++;
        int wordBEnd = findWordEnd(name, wordBStart);
        if (wordBEnd - wordBStart < 2) return 0;

        // Step 3: Find Numbers (1-4 digits)
        int numStart = wordBEnd;
        if (numStart < len && name.charAt(numStart) == '_') numStart++;
        int numEnd = numStart;
        while (numEnd < len && name.charAt(numEnd) >= '0' && name.charAt(numEnd) <= '9') {
            numEnd++;
        }
        int numLen = numEnd - numStart;
        if (numLen < 1 || numLen > 4) return 0;

        // Step 4: Find Trailing Fragment
        int trailStart = numEnd;
        if (trailStart < len && name.charAt(trailStart) == '_') trailStart++;
        int trailLen = len - trailStart;
        if (trailLen < 3) return 0;

        // Step 5: Match Logic (Zero Allocations)
        if (compare(name, trailStart, trailLen, wordAStart, wordAEnd - wordAStart) ||
                compare(name, trailStart, trailLen, wordBStart, wordBEnd - wordBStart) ||
                compareConcat(name, trailStart, trailLen, wordAStart, wordAEnd, wordBStart, wordBEnd) ||
                compareConcat(name, trailStart, trailLen, wordBStart, wordBEnd, wordAStart, wordAEnd)) {
            return 15;
        }

        return 0;
    }

    private int findWordEnd(String s, int start) {
        int i = start;
        int len = s.length();
        for (; i < len; i++) {
            char c = s.charAt(i);
            if (i > start) {
                char prev = s.charAt(i - 1);
                if (prev >= 'a' && prev <= 'z' && c >= 'A' && c <= 'Z') break;
            }
            if ((c >= '0' && c <= '9') || c == '_' || !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) break;
        }
        return i;
    }

    private boolean compare(String s, int tStart, int tLen, int targetStart, int targetLen) {
        if (tLen > targetLen) return false;
        for (int i = 0; i < tLen; i++) {
            if (!charMatch(s.charAt(tStart + i), s.charAt(targetStart + i))) return false;
        }
        return true;
    }

    private boolean compareConcat(String s, int tStart, int tLen, int w1S, int w1E, int w2S, int w2E) {
        int w1Len = w1E - w1S;
        int totalTargetLen = w1Len + (w2E - w2S);
        if (tLen > totalTargetLen) return false;

        for (int i = 0; i < tLen; i++) {
            char targetChar = (i < w1Len) ? s.charAt(w1S + i) : s.charAt(w2S + (i - w1Len));
            if (!charMatch(s.charAt(tStart + i), targetChar)) return false;
        }
        return true;
    }

    private boolean charMatch(char trail, char base) {
        // Simple case-insensitive ASCII match
        char t = (trail >= 'A' && trail <= 'Z') ? (char)(trail + 32) : trail;
        char b = (base >= 'A' && base <= 'Z') ? (char)(base + 32) : base;
        return t == b;
    }
}
