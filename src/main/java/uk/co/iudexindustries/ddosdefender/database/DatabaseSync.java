package uk.co.iudexindustries.ddosdefender.database;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.experimental.UtilityClass;
import okhttp3.Response;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Talks to the central DDoSDefender database server (see /server in the
 * source tree) over protocol v2 (DatabaseClient), in both directions:
 *
 *  - On every start: register once, then ask for this install's key. The key
 *    unlocks the local copy for this run only and is never saved, so a
 *    banned install loses its database at its next restart.
 *  - No copy yet (or the server doesn't recognise ours): download a fresh
 *    one. The server allows the first download and then one a week. If it
 *    has no copy ready, we wait in its line and ask again every minute.
 *  - Every few hours: fetch the accounts added since our copy was made,
 *    already hashed with our key, and add them. No full re-downloads.
 *  - Every account this server had to confirm with Mojang is reported back,
 *    signed, so the shared database grows for everyone.
 *
 * Only a UUID and a name are ever reported — no IPs, nothing about this
 * server. The central server re-checks every report with Mojang itself
 * before storing it, so a report is a hint, never trusted data.
 */
@UtilityClass
public class DatabaseSync {

    public static final String DEFAULT_SERVER = "https://db.iudexindustries.co.uk";

    private static final int  MAX_PENDING   = 5_000;
    private static final int  REPORT_BATCH  = 500;
    private static final long FLUSH_SECONDS = 30L;
    // Until the database is unlocked, check this often (also how fast we
    // notice our turn in the download line); after that, fetch updates every
    // UPDATE_HOURS. After a failure, wait RETRY_MINUTES before trying again.
    private static final long TICK_SECONDS  = 60L;
    private static final long UPDATE_HOURS  = 6L;
    private static final long RETRY_MINUTES = 5L;
    // Generation time of our copy and how far updates have reached (epoch s).
    private static final String META_FILE = "players.db.meta";

    // Keyed by UUID so a player rejoining before the next flush is sent once.
    private static final ConcurrentHashMap<UUID, String> pending = new ConcurrentHashMap<>();
    private static final AtomicBoolean busy = new AtomicBoolean();

    private static DatabaseClient client;
    private static boolean reporting;
    private static File dataFolder;
    private static Logger logger;
    private static volatile boolean unlocked;
    private static volatile long lastUpdateMs;
    private static volatile long nextDownloadMs;
    private static volatile long nextAttemptMs;
    private static volatile int linePosition;

    public void init(String serverUrl, boolean reportNewPlayers, File folder, Logger log,
                     ScheduledExecutorService scheduler) {
        String server = serverUrl == null || serverUrl.isBlank()
                ? DEFAULT_SERVER : serverUrl.trim().replaceAll("/+$", "");
        reporting = reportNewPlayers;
        dataFolder = folder;
        logger = log;
        client = new DatabaseClient(server, folder, log);
        if (!client.isConfigured()) {
            log.warning("This build has no database server key — running without the player database");
            return;
        }
        // Left behind by plugin 1.9.
        new File(folder, "players.db.snapshot").delete();
        if (reporting) {
            scheduler.scheduleWithFixedDelay(DatabaseSync::flush, FLUSH_SECONDS, FLUSH_SECONDS, TimeUnit.SECONDS);
        }
        scheduler.scheduleWithFixedDelay(DatabaseSync::tick, 5, TICK_SECONDS, TimeUnit.SECONDS);
    }

    // Always on its own thread: registering and downloading take a while and
    // must not hold up the shared scheduler. One at a time.
    private void tick() {
        try {
            long now = System.currentTimeMillis();
            if (now < nextAttemptMs) return;
            if (unlocked && now - lastUpdateMs < TimeUnit.HOURS.toMillis(UPDATE_HOURS)) return;
            if (!unlocked && !PlayerDatabase.isInstalled() && now < nextDownloadMs) return;
            if (!busy.compareAndSet(false, true)) return;
            Thread thread = new Thread(() -> {
                try {
                    client.ensureRegistered();
                    if (unlocked) update();
                    else connect();
                } catch (DatabaseClient.ApiException e) {
                    if (e.code == 401 && "unknown install, register again".equals(e.getMessage())) {
                        client.resetIdentity();
                    }
                    logger.warning("Player database server refused: " + e.getMessage() + " — retrying later");
                    nextAttemptMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(RETRY_MINUTES);
                } catch (Exception e) {
                    logger.warning("Player database server unreachable: " + e.getMessage() + " — retrying later");
                    nextAttemptMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(RETRY_MINUTES);
                } finally {
                    busy.set(false);
                }
            }, "DDoSDefender-DB");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception ignored) {
            // A scheduled task that throws is never run again.
        }
    }

    // Gets this run's key, or a fresh copy if we have none the server knows.
    private void connect() throws IOException {
        if (PlayerDatabase.isInstalled()) {
            JsonObject session = client.call("POST", "/v2/session", new JsonObject());
            long[] meta = readMeta();
            if (!session.get("key").isJsonNull() && meta != null
                    && session.get("generated").getAsLong() == meta[0]) {
                PlayerDatabase.unlock(Base64.getDecoder().decode(session.get("key").getAsString()));
                unlocked = true;
                logger.info("Player database unlocked");
                update();
                return;
            }
            // Not the copy the server issued to this install (identity
            // re-made, or files copied from elsewhere): it is useless to us.
            logger.info("The installed player database was not issued to this server — replacing it");
            PlayerDatabase.delete();
        }
        download();
    }

    private void download() throws IOException {
        if (System.currentTimeMillis() < nextDownloadMs) return;
        JsonObject copy;
        try {
            copy = client.call("POST", "/v2/download", new JsonObject());
        } catch (DatabaseClient.ApiException e) {
            long wait = e.retryAfterSeconds();
            if (e.code == 503 && e.body.has("position") && wait > 0) {
                // In the server's line for a copy: ask again shortly.
                int position = e.body.get("position").getAsInt();
                if (position != linePosition) {
                    logger.info("Waiting in line for a player database copy (position " + position + ")");
                    linePosition = position;
                }
                nextDownloadMs = System.currentTimeMillis() + wait * 1000;
                return;
            }
            if ((e.code == 429 || e.code == 503) && wait > 0) {
                nextDownloadMs = System.currentTimeMillis() + wait * 1000;
                logger.warning("Player database download not available yet (" + e.getMessage()
                        + ") — next try in " + Math.max(1, wait / 60) + " min");
                return;
            }
            throw e;
        }

        long dbSize = copy.get("db_size").getAsLong();
        long size = copy.get("size").getAsLong();
        long free = dataFolder.getUsableSpace();
        if (free < dbSize + 64L * 1024 * 1024) {
            throw new IOException("not enough disk space for the player database: need "
                    + dbSize / (1024 * 1024) + " MB, have " + free / (1024 * 1024) + " MB");
        }
        linePosition = 0;
        logger.info("Downloading the player database (" + copy.get("rows").getAsLong() + " accounts, "
                + size / (1024 * 1024) + " MB)...");
        long start = System.currentTimeMillis();

        Path tmp = new File(dataFolder, "players.db.download").toPath();
        try {
            MessageDigest digest = sha256();
            try (Response response = client.openFile("/v2/file/" + copy.get("token").getAsString());
                 DigestInputStream raw = new DigestInputStream(response.body().byteStream(), digest);
                 InputStream in = new GZIPInputStream(new ProgressStream(raw, size,
                         line -> logger.info("Player database: " + line)), 1 << 16);
                 OutputStream out = Files.newOutputStream(tmp)) {
                // The hash is only checked at the end, so never trust the
                // stream further than the signed reply's sizes: a file that
                // unpacks to more than db_size (a gzip bomb) is cut off here
                // rather than filling the disk.
                byte[] buf = new byte[1 << 16];
                long written = 0;
                for (int n; (n = in.read(buf)) != -1; ) {
                    written += n;
                    if (written > dbSize) throw new IOException("download unpacks larger than announced — aborted");
                    out.write(buf, 0, n);
                }
                raw.transferTo(OutputStream.nullOutputStream()); // hash any bytes gzip didn't need
            }
            // The hash came in the signed reply, so this proves the file is ours.
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(copy.get("sha256").getAsString())) {
                throw new IOException("checksum mismatch — the download was corrupted, retrying later");
            }
            if (!PlayerDatabase.install(tmp)) {
                throw new IOException("the downloaded database could not be opened (see console)");
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        long generated = copy.get("generated").getAsLong();
        writeMeta(generated, generated);
        PlayerDatabase.unlock(Base64.getDecoder().decode(copy.get("key").getAsString()));
        unlocked = true;
        logger.info("Player database installed and active (" + (System.currentTimeMillis() - start) / 1000 + "s)");
        update();
    }

    // Adds accounts the network verified since our copy (or last update).
    private void update() throws IOException {
        long[] meta = readMeta();
        if (meta == null) return;
        JsonObject body = new JsonObject();
        body.addProperty("since", meta[1]);
        JsonObject delta = client.call("POST", "/v2/delta", body);
        if (delta.has("full") && delta.get("full").getAsBoolean()) {
            // Too far behind for an update; keep using what we have until the
            // weekly download allowance gives us a fresh copy.
            logger.info("Player database is far behind — requesting a fresh copy");
            download();
            lastUpdateMs = System.currentTimeMillis();
            return;
        }
        ByteBuffer packed = ByteBuffer.wrap(Base64.getDecoder().decode(delta.get("hashes").getAsString()));
        long[] hashes = new long[packed.remaining() / 8];
        for (int i = 0; i < hashes.length; i++) hashes[i] = packed.getLong();
        PlayerDatabase.addHashes(hashes);
        writeMeta(meta[0], delta.get("until").getAsLong());
        lastUpdateMs = System.currentTimeMillis();
        if (hashes.length > 0) logger.info("Player database updated with " + hashes.length + " new accounts");
    }

    /** Queues a Mojang-confirmed account for the central database. Never blocks. */
    public void report(UUID uuid, String name) {
        if (!reporting || uuid == null || name == null || pending.size() >= MAX_PENDING) return;
        pending.put(uuid, name);
    }

    /** Sends everything queued by report(). Runs on a timer and once at shutdown. */
    public void flush() {
        try {
            if (client == null || !client.isConfigured()) return;
            while (!pending.isEmpty()) {
                Map<UUID, String> batch = new HashMap<>();
                Iterator<Map.Entry<UUID, String>> it = pending.entrySet().iterator();
                while (it.hasNext() && batch.size() < REPORT_BATCH) {
                    Map.Entry<UUID, String> entry = it.next();
                    batch.put(entry.getKey(), entry.getValue());
                    it.remove();
                }
                if (!send(batch)) {
                    // Central server unreachable — keep them for the next round.
                    batch.forEach(pending::putIfAbsent);
                    return;
                }
            }
        } catch (Exception ignored) {
            // A scheduled task that throws is never run again.
        }
    }

    private boolean send(Map<UUID, String> batch) {
        JsonArray players = new JsonArray();
        batch.forEach((uuid, name) -> {
            JsonObject player = new JsonObject();
            player.addProperty("uuid", uuid.toString());
            player.addProperty("name", name);
            players.add(player);
        });
        JsonObject body = new JsonObject();
        body.add("players", players);
        try {
            client.call("POST", "/v2/report", body);
            return true;
        } catch (DatabaseClient.ApiException e) {
            // The server refused this batch (e.g. rate limited); resending
            // the same thing forever would not help, so drop it.
            return e.code < 500;
        } catch (IOException e) {
            return false; // includes "not registered yet"
        }
    }

    // {generated, since} or null if missing/damaged.
    private long[] readMeta() {
        try {
            JsonObject meta = JsonParser.parseString(
                    Files.readString(new File(dataFolder, META_FILE).toPath())).getAsJsonObject();
            return new long[] {meta.get("generated").getAsLong(), meta.get("since").getAsLong()};
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private void writeMeta(long generated, long since) throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("generated", generated);
        meta.addProperty("since", since);
        Files.writeString(new File(dataFolder, META_FILE).toPath(), meta.toString());
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Passes bytes through, announcing every 10% of the expected total.
    private static final class ProgressStream extends java.io.FilterInputStream {
        private final long total;
        private final Consumer<String> progress;
        private long read;
        private int lastTenth;

        ProgressStream(InputStream in, long total, Consumer<String> progress) {
            super(in);
            this.total = total;
            this.progress = progress;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) advance(1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) advance(n);
            return n;
        }

        // Also refuses more bytes than the signed reply announced.
        private void advance(int n) throws IOException {
            read += n;
            if (total <= 0) return;
            if (read > total) throw new IOException("download is larger than announced — aborted");
            int tenth = (int) (read * 10 / total);
            if (tenth > lastTenth && tenth < 10) {
                lastTenth = tenth;
                progress.accept("Downloading... " + tenth * 10 + "%");
            }
        }
    }
}
