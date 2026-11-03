package uk.co.iudexindustries.ddosdefender.mojang;

import uk.co.iudexindustries.ddosdefender.core.DefenderCore;
import uk.co.iudexindustries.ddosdefender.database.PlayerDatabase;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MojangApi {

    private static final int MAX_CONCURRENT = Math.max(1,
            (int) Math.ceil(Runtime.getRuntime().availableProcessors() * 0.2));
    private static final Semaphore semaphore = new Semaphore(MAX_CONCURRENT);

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS)
            .build();

    // A timeout on this path turns a real first-time player away, so it gets
    // more patience than the name lookup above.
    private static final OkHttpClient profileClient = client.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build();

    // UUID result cache — avoids repeated API calls for the same username within 5 minutes.
    // null uuid means "player not found" (real 404 response), which is also cached so
    // bots hammering with invalid names don't consume API quota on retries.
    private record Cached(UUID uuid, long timestamp) {}
    private static final long CACHE_TTL_MS = 5 * 60_000L;
    private static final ConcurrentHashMap<String, Cached> resultCache = new ConcurrentHashMap<>();

    public static UUID uuidForName(String username) {
        long now = System.currentTimeMillis();

        // Cache hit — return immediately without touching the network or semaphore.
        Cached hit = resultCache.get(username);
        if (hit != null && now - hit.timestamp() < CACHE_TTL_MS) {
            return hit.uuid(); // null here means "not found" — caller will treat as fail
        }

        if (!semaphore.tryAcquire()) return null; // under load, skip rather than block
        try {
            Request request = new Request.Builder()
                    .url("https://api.mojang.com/users/profiles/minecraft/" + username)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                if (code == 404 || code == 204) {
                    // Remembered, so bots retrying a made-up name don't burn lookups.
                    resultCache.put(username, new Cached(null, now));
                    return null;
                }
                // Rate limited or Mojang trouble: proves nothing, so not remembered —
                // otherwise a real player would be turned away for 5 minutes.
                if (code != 200 || response.body() == null) return null;
                String json = response.body().string();
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                String id = obj.get("id").getAsString();
                UUID uuid = UUID.fromString(
                        id.replaceFirst(
                                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                                "$1-$2-$3-$4-$5"
                        )
                );
                resultCache.put(username, new Cached(uuid, now));
                // Store under Mojang's own capitalisation, not whatever the client typed.
                PlayerDatabase.store(uuid, obj.has("name") ? obj.get("name").getAsString() : username);
                return uuid;
            }
        } catch (Exception e) {
            // Transient network error — do NOT cache so the next attempt tries again.
            return null;
        } finally {
            semaphore.release();
        }
    }

    /**
     * Outcome of a profile lookup by UUID. FOUND carries Mojang's current name
     * for the account; NOT_FOUND means Mojang has no such account; UNAVAILABLE
     * means we could not get an answer (busy, timeout, rate limited), so
     * nothing was proven either way.
     */
    public enum Status { FOUND, NOT_FOUND, UNAVAILABLE }

    public record Profile(Status status, String name) {
        static final Profile NOT_FOUND   = new Profile(Status.NOT_FOUND, null);
        static final Profile UNAVAILABLE = new Profile(Status.UNAVAILABLE, null);
    }

    private record CachedProfile(Profile profile, long timestamp) {}
    private static final ConcurrentHashMap<UUID, CachedProfile> profileCache = new ConcurrentHashMap<>();
    // Every account missing from the player database comes through here, so
    // unlike fetchUUID a busy moment waits briefly for a slot instead of
    // failing a real player on the spot.
    private static final long SLOT_WAIT_MS = 2_000L;

    public static Profile profile(UUID uuid) {
        long now = System.currentTimeMillis();

        CachedProfile hit = profileCache.get(uuid);
        if (hit != null && now - hit.timestamp() < CACHE_TTL_MS) return hit.profile();

        try {
            if (!semaphore.tryAcquire(SLOT_WAIT_MS, TimeUnit.MILLISECONDS)) return Profile.UNAVAILABLE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Profile.UNAVAILABLE;
        }
        try {
            Request request = new Request.Builder()
                    .url("https://api.minecraftservices.com/minecraft/profile/lookup/"
                            + uuid.toString().replace("-", ""))
                    .build();
            try (Response response = profileClient.newCall(request).execute()) {
                int code = response.code();
                if (code == 404 || code == 204) {
                    // Cached so bots replaying a made-up UUID don't burn API quota.
                    profileCache.put(uuid, new CachedProfile(Profile.NOT_FOUND, now));
                    return Profile.NOT_FOUND;
                }
                if (code != 200 || response.body() == null) return Profile.UNAVAILABLE;

                JsonObject obj = JsonParser.parseString(response.body().string()).getAsJsonObject();
                Profile profile = new Profile(Status.FOUND, obj.get("name").getAsString());
                profileCache.put(uuid, new CachedProfile(profile, now));
                return profile;
            }
        } catch (Exception e) {
            // Transient network error — do NOT cache so the next attempt tries again.
            return Profile.UNAVAILABLE;
        } finally {
            semaphore.release();
        }
    }

    // Called from DefenderCore's scheduler every 15 minutes
    public static void cleanup() {
        long cutoff = System.currentTimeMillis() - CACHE_TTL_MS;
        resultCache.entrySet().removeIf(e -> e.getValue().timestamp() < cutoff);
        profileCache.entrySet().removeIf(e -> e.getValue().timestamp() < cutoff);
    }
}
