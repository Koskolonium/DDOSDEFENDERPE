package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class KeyboardWalkCheck {

    /**
     * Keyboard-walk detection.
     *
     * Low-effort name generators frequently emit adjacent-key runs pulled straight
     * off the QWERTY layout — "qwerty", "asdfgh", "zxcvbn", "qazwsx", "123456".
     * Real usernames almost never contain a long horizontal keyboard run.
     *
     * Each character is mapped to a (row, column) code. Two consecutive characters
     * form a "walk step" when they sit on the same row one column apart (either
     * direction, so "poiuy" scores the same as "qwerty"). We track the longest
     * run of consecutive walk steps and score on its length.
     *
     * Zero allocations: the layout is resolved through a switch, no lookup table.
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 3) return 0;

        int longestRun = 1;   // run length measured in characters
        int currentRun = 1;
        int prevCode   = keyboardCode(name.charAt(0));

        for (int i = 1; i < len; i++) {
            int code = keyboardCode(name.charAt(i));

            // Same row (code / 100) and adjacent column (|col diff| == 1)
            // Letter keys only: number runs like "1234" are ordinary in real names.
            if (code >= 100 && prevCode >= 100
                    && (code / 100) == (prevCode / 100)
                    && Math.abs((code % 100) - (prevCode % 100)) == 1) {
                currentRun++;
                if (currentRun > longestRun) longestRun = currentRun;
            } else {
                currentRun = 1;
            }
            prevCode = code;
        }

        if (longestRun >= 6) return 35;
        if (longestRun == 5) return 25;
        if (longestRun == 4) return 12;
        return 0;
    }

    /**
     * Returns row*100 + column for a key, or -1 for anything off the main layout
     * (underscore, etc.). Case-insensitive.
     */
    private int keyboardCode(char c) {
        return switch (c) {
            // Number row (row 0)
            case '1' -> 0;   case '2' -> 1;   case '3' -> 2;   case '4' -> 3;
            case '5' -> 4;   case '6' -> 5;   case '7' -> 6;   case '8' -> 7;
            case '9' -> 8;   case '0' -> 9;
            // Top letter row (row 1)
            case 'q','Q' -> 100; case 'w','W' -> 101; case 'e','E' -> 102;
            case 'r','R' -> 103; case 't','T' -> 104; case 'y','Y' -> 105;
            case 'u','U' -> 106; case 'i','I' -> 107; case 'o','O' -> 108;
            case 'p','P' -> 109;
            // Home row (row 2)
            case 'a','A' -> 200; case 's','S' -> 201; case 'd','D' -> 202;
            case 'f','F' -> 203; case 'g','G' -> 204; case 'h','H' -> 205;
            case 'j','J' -> 206; case 'k','K' -> 207; case 'l','L' -> 208;
            // Bottom row (row 3)
            case 'z','Z' -> 300; case 'x','X' -> 301; case 'c','C' -> 302;
            case 'v','V' -> 303; case 'b','B' -> 304; case 'n','N' -> 305;
            case 'm','M' -> 306;
            default -> -1;
        };
    }
}
