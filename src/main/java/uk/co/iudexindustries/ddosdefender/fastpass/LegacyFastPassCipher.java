package uk.co.iudexindustries.ddosdefender.fastpass;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Reads FastPass entries written before 2.0, which were AES-encrypted under a
 * key shared by every copy of the plugin. Only used to convert them to
 * per-server fingerprints on first start (see FastPass); nothing is ever
 * encrypted with this key again.
 */
class LegacyFastPassCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private static final String LEGACY_KEY = "kP7v9N2wB4xL1zR5";

    static String decrypt(String encryptedData) throws Exception {
        byte[] raw = Base64.getDecoder().decode(encryptedData);

        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
        byte[] ciphertext = new byte[raw.length - IV_LENGTH];
        System.arraycopy(raw, IV_LENGTH, ciphertext, 0, ciphertext.length);

        SecretKeySpec secretKey = new SecretKeySpec(LEGACY_KEY.getBytes(), "AES");
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH, iv));
        return new String(cipher.doFinal(ciphertext));
    }
}
