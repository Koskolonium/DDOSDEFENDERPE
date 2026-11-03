package uk.co.iudexindustries.ddosdefender.fastpass;

import uk.co.iudexindustries.ddosdefender.listener.LoginListener;

import lombok.experimental.UtilityClass;
import org.sqlite.SQLiteConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verified players (name -> fingerprints of the last MAX_NETWORKS subnets they
 * were verified on, most recent first, comma-separated). A login from any of
 * them passes straight through. Lives fully in memory for the login path and
 * is mirrored to fastpass.db, a small SQLite file created on first start and
 * loaded on every start after that. Each change is written through as it happens, so
 * nothing is lost on a crash.
 *
 * The subnet itself is never kept, only HMAC-SHA256(fastpass.key, subnet):
 * FastPass only ever asks "same subnet as last time?", which a fingerprint
 * answers just as well, and there is nothing to decrypt. fastpass.key is
 * random per server, so one server's files say nothing about another's.
 */
@UtilityClass
public class FastPass {

    public static final String PENDING_KEY = "PENDING_VERIFICATION";

    private static final String KEY_FILE = "fastpass.key";
    // Marks a stored fingerprint; anything else in fastpass.db is a pre-2.0
    // AES-encrypted subnet, converted on load.
    private static final String FINGERPRINT_PREFIX = "h:";
    // Home, mobile data, a friend's house... A fourth network pushes out the
    // one used least recently.
    static final int MAX_NETWORKS = 3;

    // Map: PlayerName -> "h:...,h:...,h:..." (most recent first), or PENDING_KEY
    private static final ConcurrentHashMap<String, String> verifiedPlayers = new ConcurrentHashMap<>();

    private static final Object dbLock = new Object();
    private static Connection connection;
    private static PreparedStatement upsertStmt;
    private static SecretKeySpec key;

    public void load(File dataFolder) throws IOException {
        key = new SecretKeySpec(loadOrCreateKey(new File(dataFolder, KEY_FILE)), "HmacSHA256");
        Map<String, String> converted = new HashMap<>();
        try {
            synchronized (dbLock) {
                SQLiteConfig config = new SQLiteConfig();
                config.setJournalMode(SQLiteConfig.JournalMode.WAL);
                config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
                connection = config.createConnection(
                        "jdbc:sqlite:" + new File(dataFolder, "fastpass.db").getAbsolutePath());
                try (Statement st = connection.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS fastpass ("
                            + "name TEXT NOT NULL PRIMARY KEY, "
                            + "subnet TEXT NOT NULL"
                            + ") WITHOUT ROWID");
                    try (ResultSet rs = st.executeQuery("SELECT name, subnet FROM fastpass")) {
                        while (rs.next()) {
                            String name = rs.getString(1);
                            String stored = rs.getString(2);
                            if (stored.startsWith(FINGERPRINT_PREFIX) || PENDING_KEY.equals(stored)) {
                                verifiedPlayers.put(name, stored);
                                continue;
                            }
                            try {
                                converted.put(name, fromLegacy(LegacyFastPassCipher.decrypt(stored)));
                            } catch (Exception ignored) {
                                // Unreadable row — the player just gets verified again.
                            }
                        }
                    }
                }
                upsertStmt = connection.prepareStatement(
                        "INSERT OR REPLACE INTO fastpass (name, subnet) VALUES (?, ?)");
            }
        } catch (SQLException | LinkageError e) {
            throw new IOException("Could not open fastpass.db: " + e, e);
        }
        // Rewrites the pre-2.0 rows, so no readable subnet stays on disk.
        converted.forEach(FastPass::put);
        migrateLegacyFile(new File(dataFolder, "FastPass.txt"));
    }

    private static byte[] loadOrCreateKey(File file) throws IOException {
        if (file.isFile()) {
            byte[] existing = Files.readAllBytes(file.toPath());
            if (existing.length == 32) return existing;
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        Files.write(file.toPath(), fresh);
        file.setReadable(false, false);
        file.setReadable(true, true);
        return fresh;
    }

    // Versions up to 1.8 kept FastPass in a text file ("name encryptedSubnet"
    // per line). Pull it in once, then rename it so it is never read again.
    private void migrateLegacyFile(File legacy) {
        if (!legacy.isFile()) return;
        try {
            for (String line : Files.readAllLines(legacy.toPath())) {
                String[] parts = line.trim().split(" ");
                if (parts.length != 2) continue;
                try {
                    put(parts[0], fromLegacy(LegacyFastPassCipher.decrypt(parts[1])));
                } catch (Exception ignored) {}
            }
            legacy.renameTo(new File(legacy.getPath() + ".migrated"));
        } catch (IOException ignored) {}
    }

    private String fromLegacy(String plain) {
        return PENDING_KEY.equals(plain) ? PENDING_KEY : fingerprint(plain);
    }

    public void close() {
        synchronized (dbLock) {
            try { if (upsertStmt != null) upsertStmt.close(); } catch (SQLException ignored) {}
            try { if (connection != null) connection.close(); } catch (SQLException ignored) {}
            upsertStmt = null;
            connection = null;
        }
    }

    /** Records (or refreshes) this subnet as one of the player's networks. */
    public void verify(String playerName, String subnet) {
        if (playerName == null || playerName.isBlank() || subnet == null) return;
        String name = playerName.trim();
        put(name, withNetwork(verifiedPlayers.get(name), fingerprint(subnet)));
    }

    // Puts `fingerprint` first and keeps up to MAX_NETWORKS - 1 of the others.
    static String withNetwork(String stored, String fingerprint) {
        List<String> networks = new ArrayList<>(MAX_NETWORKS);
        networks.add(fingerprint);
        if (stored != null && !PENDING_KEY.equals(stored)) {
            for (String known : stored.split(",")) {
                if (networks.size() == MAX_NETWORKS) break;
                if (!known.equals(fingerprint)) networks.add(known);
            }
        }
        return String.join(",", networks);
    }

    /**
     * Used by LoginListener: name -> subnet fingerprints, or PENDING_KEY.
     * Compare against a live subnet with {@link #matches}.
     */
    public ConcurrentHashMap<String, String> getVerified() {
        return verifiedPlayers;
    }

    /** True if this subnet is one of the networks in {@code stored} (from getVerified). */
    public boolean matches(String stored, String subnet) {
        if (stored == null || subnet == null || PENDING_KEY.equals(stored)) return false;
        String fingerprint = fingerprint(subnet);
        for (String known : stored.split(",")) {
            if (known.equals(fingerprint)) return true;
        }
        return false;
    }

    public void prePass(String playerName) {
        if (playerName == null || playerName.isBlank()) return;
        // Using a placeholder string instead of null to prevent NPE
        put(playerName.trim(), PENDING_KEY);
    }

    private String fingerprint(String subnet) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] digest = mac.doFinal(subnet.getBytes(StandardCharsets.UTF_8));
            return FINGERPRINT_PREFIX + Base64.getEncoder().withoutPadding()
                    .encodeToString(Arrays.copyOf(digest, 16));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void put(String name, String value) {
        // A regular rejoining from the same subnet changes nothing — skip the write.
        if (value.equals(verifiedPlayers.put(name, value))) return;
        synchronized (dbLock) {
            if (upsertStmt == null) return;
            try {
                upsertStmt.setString(1, name);
                upsertStmt.setString(2, value);
                upsertStmt.executeUpdate();
            } catch (Exception ignored) {
                // Still held in memory; only persistence of this one entry is lost.
            }
        }
    }
}
