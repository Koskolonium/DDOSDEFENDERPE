package uk.co.iudexindustries.ddosdefender.database;

import uk.co.iudexindustries.ddosdefender.listener.LoginListener;
import lombok.experimental.UtilityClass;
import org.sqlite.SQLiteConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * On-disk SQLite store of known-real accounts, sized for tens of millions of
 * rows. Lets LoginListener confirm an account locally instead of
 * spending a Mojang API call.
 *
 * The file holds no UUIDs and no names. Every row is
 * HMAC-SHA256(key, uuid || lowercase name) cut to 8 bytes, where the key is
 * unique to this install's copy and is never written to disk: DatabaseSync
 * fetches it from the database server on every start and hands it to
 * unlock(). Without the key the file is random numbers; with it, it still
 * only answers "is this exact uuid+name real?" and can't be turned back into
 * a list of accounts.
 *
 * Nothing is ever loaded into memory: every lookup is a single B-tree descent
 * on disk through a deliberately tiny page cache, with memory-mapping off.
 *
 * Rows can go stale (players rename), so a hit only ever proves a UUID/name
 * pair IS real — a miss must fall back to the Mojang API, never be treated
 * as a failed verification.
 */
@UtilityClass
public class PlayerDatabase {

    private static final String FILE_NAME = "players.db";
    // Per-connection SQLite page cache. Kept tiny on purpose: point lookups
    // only ever touch the 3-4 pages of one B-tree descent.
    private static final int CACHE_KB = 2_048;

    // Two connections so WAL can do its job: lookups on the login path never
    // wait behind a write. JDBC statements aren't thread-safe,
    // hence one lock per connection.
    private static final Object readLock = new Object();
    private static final Object writeLock = new Object();

    private static final Object lifecycleLock = new Object();

    private static File dbFile;
    private static Logger logger;
    private static Connection reader;
    private static Connection writer;
    private static PreparedStatement selectStmt;
    private static PreparedStatement insertStmt;
    private static volatile boolean open;
    private static volatile SecretKeySpec key;

    /** Opens players.db if it has been downloaded; lookups stay off until unlock(). */
    public void open(File dataFolder, Logger log) {
        logger = log;
        dbFile = new File(dataFolder, FILE_NAME);
        synchronized (lifecycleLock) {
            connectLocked();
        }
    }

    /**
     * Swaps a freshly downloaded copy into place and opens it, with no
     * restart. The key is cleared: the caller unlocks with the new copy's key.
     */
    public boolean install(Path newFile) throws IOException {
        synchronized (lifecycleLock) {
            closeLocked();
            try {
                // Leftovers of the old file's WAL would be replayed onto the new one.
                Files.deleteIfExists(Path.of(dbFile.getPath() + "-wal"));
                Files.deleteIfExists(Path.of(dbFile.getPath() + "-shm"));
                Files.move(newFile, dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                // Reopen whatever is on disk, so a failed swap keeps the old database.
                connectLocked();
            }
            return open;
        }
    }

    /** Deletes the file, e.g. when the server no longer recognises our copy. */
    public void delete() {
        synchronized (lifecycleLock) {
            closeLocked();
            deleteFilesLocked();
        }
    }

    /** Enables lookups with this session's key (kept in memory only). */
    public void unlock(byte[] rawKey) {
        key = new SecretKeySpec(rawKey, "HmacSHA256");
    }

    public void close() {
        synchronized (lifecycleLock) {
            closeLocked();
        }
    }

    private void connectLocked() {
        if (!dbFile.exists()) {
            return;
        }
        try {
            writer = connect();
            if (!hasCurrentSchema(writer)) {
                // A 1.9-era copy (plain UUIDs and names). Never used again:
                // remove it so the new format is downloaded in its place.
                closeLocked();
                logger.info("Removing the old-format player database; a new copy will be downloaded");
                deleteFilesLocked();
                return;
            }
            reader = connect();
            selectStmt = reader.prepareStatement("SELECT 1 FROM h WHERE k = ?");
            insertStmt = writer.prepareStatement("INSERT OR IGNORE INTO h (k) VALUES (?)");
            open = true;
            logger.info("Player database opened (" + dbFile.length() / (1024 * 1024) + " MB on disk)");
        } catch (Exception | LinkageError e) {
            logger.warning("Player database unavailable, continuing without it: " + e);
            closeLocked();
        }
    }

    private void closeLocked() {
        open = false;
        key = null;
        synchronized (readLock) {
            closeQuietly(selectStmt);
            closeQuietly(reader);
            selectStmt = null;
            reader = null;
        }
        synchronized (writeLock) {
            closeQuietly(insertStmt);
            closeQuietly(writer);
            insertStmt = null;
            writer = null;
        }
    }

    private void deleteFilesLocked() {
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            new File(dbFile.getPath() + suffix).delete();
        }
    }

    // The file comes from a download (or an older plugin version), so check
    // it is really the table we expect before trusting lookups against it.
    private boolean hasCurrentSchema(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(h)")) {
            while (rs.next()) {
                if ("k".equalsIgnoreCase(rs.getString("name")) && rs.getInt("pk") == 1
                        && "INTEGER".equalsIgnoreCase(rs.getString("type"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if a usable players.db is on disk, whether or not it is unlocked yet. */
    public boolean isInstalled() {
        return open;
    }

    /** True once the database is open and unlocked: lookups mean something. */
    public boolean isAvailable() {
        return open && key != null;
    }

    /** @return true if this exact account (UUID + name, any case) is known to be real. */
    public boolean contains(UUID uuid, String name) {
        if (uuid == null || name == null) return false;
        Long hash = hash(uuid, name);
        if (hash == null) return false;
        synchronized (readLock) {
            if (selectStmt == null) return false;
            try {
                selectStmt.setLong(1, hash);
                try (ResultSet rs = selectStmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                logger.warning("Player database lookup failed: " + e.getMessage());
                return false;
            }
        }
    }

    /** Records an account this server confirmed with Mojang itself. */
    public void store(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) return;
        Long hash = hash(uuid, name);
        if (hash != null) addHashes(new long[] {hash});
    }

    /** Adds rows already hashed with our key (the server's weekly update). */
    public void addHashes(long[] hashes) {
        if (!isAvailable() || hashes.length == 0) return;
        synchronized (writeLock) {
            if (insertStmt == null) return;
            try {
                writer.setAutoCommit(false);
                for (long h : hashes) {
                    insertStmt.setLong(1, h);
                    insertStmt.addBatch();
                }
                insertStmt.executeBatch();
                writer.commit();
            } catch (SQLException e) {
                logger.warning("Player database write failed: " + e.getMessage());
                try { writer.rollback(); } catch (SQLException ignored) {}
            } finally {
                try { writer.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        }
    }

    // HMAC-SHA256(key, uuid bytes || lowercase name), first 8 bytes as a
    // signed big-endian long; null while locked.
    private Long hash(UUID uuid, String name) {
        SecretKeySpec k = key;
        if (k == null) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(k);
            mac.update(ByteBuffer.allocate(16)
                    .putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits())
                    .array());
            byte[] digest = mac.doFinal(name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(Arrays.copyOf(digest, 8)).getLong();
        } catch (GeneralSecurityException e) {
            return null;
        }
    }

    // SQLiteConfig.createConnection goes straight to the driver rather than
    // through DriverManager, which can't see drivers loaded by a plugin
    // classloader on every platform.
    private Connection connect() throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setTempStore(SQLiteConfig.TempStore.FILE);
        config.setCacheSize(-CACHE_KB);
        config.setBusyTimeout(10_000);
        // No memory-mapping: the file must never be pulled into this process.
        config.setPragma(SQLiteConfig.Pragma.MMAP_SIZE, "0");
        return config.createConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }

    private void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }
}
