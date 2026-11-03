package uk.co.iudexindustries.ddosdefender.checks;

import lombok.experimental.UtilityClass;

@UtilityClass
public class UuidVersionCheck {

    /**
     * Scores based on UUID version and variant fields.
     *
     * Online-mode Minecraft clients send version-4 (random) UUIDs.
     * Offline/cracked clients generate version-3 (MD5 name-based) UUIDs using
     * "OfflinePlayer:" + username as the hash input — a known bot signal.
     *
     * Parses directly from the string to avoid allocating a UUID object.
     * Format: xxxxxxxx-xxxx-Vxxx-Wxxx-xxxxxxxxxxxx
     *   V at index 14 = version nibble
     *   W at index 19 = variant nibble (RFC 4122 = 8/9/a/b, i.e. high bits 10xx)
     */
    public int score(String uuid) {
        if (uuid == null || uuid.length() != 36) return 0;

        int version = hexValue(uuid.charAt(14));
        if (version < 0) return 10;

        int variantNibble = hexValue(uuid.charAt(19));
        boolean rfcVariant = variantNibble >= 8 && variantNibble <= 11;

        int score = 0;

        if (version == 3) {
            score += 30;
        } else if (version != 4) {
            score += 20;
        }

        if (!rfcVariant) {
            score += 10;
        }

        return score;
    }

    private int hexValue(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
}