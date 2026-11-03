package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class DecorationWrapperCheck {

    /**
     * Affix-wrapper detection.
     *
     * The "xX...Xx" wrapper ("xXNinjaXx", "xX_Sniper_Xx") is a low-effort naming
     * signature reused heavily by throwaway and bot accounts. It also shows up
     * as a symmetric non-letter frame around a core word. Because a handful of
     * genuine players still use it, this scores low — it is meant to nudge a
     * name toward the Mojang-verify gate in combination with other signals, not
     * to block on its own.
     *
     * Zero allocations, constant work.
     */
    public int score(String name) {
        if (name == null) return 0;
        int len = name.length();
        if (len < 6) return 0;

        // "xX ... Xx" — both ends are a pair of x/X characters wrapping a core.
        boolean xHead = isX(name.charAt(0)) && isX(name.charAt(1));
        boolean xTail = isX(name.charAt(len - 1)) && isX(name.charAt(len - 2));
        if (xHead && xTail && hasLetterBetween(name, 2, len - 2)) {
            return 12;
        }

        // Symmetric underscore frame around a core word ("__Core__").
        int leading = 0;
        while (leading < len && name.charAt(leading) == '_') leading++;
        int trailing = 0;
        while (trailing < len && name.charAt(len - 1 - trailing) == '_') trailing++;
        if (leading >= 1 && trailing >= 1 && leading == trailing
                && hasLetterBetween(name, leading, len - trailing)) {
            // Single symmetric underscores are common; only the padded form is botty.
            return leading >= 2 ? 12 : 0;
        }

        return 0;
    }

    private boolean isX(char c) {
        return c == 'x' || c == 'X';
    }

    private boolean hasLetterBetween(String name, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        }
        return false;
    }
}
