package uk.co.iudexindustries.ddosdefender.protection;

import lombok.experimental.UtilityClass;

@UtilityClass
public class LoginFormat {

    public static boolean isValid(String playerName, String uuid) {
        return validPlayerName(playerName) && validUuid(uuid);
    }

    private static boolean validPlayerName(String name) {
        if (name == null) return false;

        int len = name.length();
        if (len == 0 || len > 16) return false;

        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);

            // Allowed: A-Z, a-z, 0-9, _
            boolean ok =
                    (c >= 'A' && c <= 'Z') ||
                            (c >= 'a' && c <= 'z') ||
                            (c >= '0' && c <= '9') ||
                            c == '_';

            if (!ok) return false;
        }

        return true;
    }

    private static boolean validUuid(String uuid) {
        if (uuid == null) return true; // optional

        // UUID must be exactly 36 chars: 8-4-4-4-12
        if (uuid.length() != 36) return false;

        for (int i = 0; i < 36; i++) {
            char c = uuid.charAt(i);

            // Hyphens at fixed positions
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false;
                continue;
            }

            // Hex digit check
            boolean hex =
                    (c >= '0' && c <= '9') ||
                            (c >= 'a' && c <= 'f') ||
                            (c >= 'A' && c <= 'F');

            if (!hex) return false;
        }

        return true;
    }
}