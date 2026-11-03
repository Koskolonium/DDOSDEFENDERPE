package uk.co.iudexindustries.ddosdefender.protection;

import lombok.experimental.UtilityClass;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Networks that recently sent a proven impostor: a login we let through that
 * then failed Minecraft's own Mojang session check ("Failed to verify
 * username"). That only happens when the client doesn't own the account —
 * e.g. a scraper replaying a real name + UUID — so it is proof, not a guess.
 *
 * Blocks are by IP and /24 (or /48) network, never by account: the names
 * scrapers use belong to real players, who must not be locked out.
 *
 *  - Each failure blocks that IP for 1 min, doubling per repeat (max 1 h);
 *    strikes are forgotten after an hour without one.
 *  - SUBNET_FAILURES failures from one network within SUBNET_WINDOW block the
 *    whole network for SUBNET_BLOCK.
 */
@UtilityClass
public class ImpostorTracker {

    private static final long IP_BLOCK_BASE_MS = 60_000L;
    private static final long IP_BLOCK_MAX_MS  = 60 * 60_000L;
    private static final long STRIKE_MEMORY_MS = 60 * 60_000L;
    private static final int  SUBNET_FAILURES  = 3;
    private static final long SUBNET_WINDOW_MS = 10 * 60_000L;
    private static final long SUBNET_BLOCK_MS  = 10 * 60_000L;
    // Bounds memory if a huge botnet is cycling addresses.
    private static final int  MAX_TRACKED = 200_000;

    private record IpRecord(int strikes, long blockedUntil, long lastFailure) {}

    private static final ConcurrentHashMap<String, IpRecord> ips = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> subnetFailures = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> subnetBlockedUntil = new ConcurrentHashMap<>();

    /**
     * Records a failed session check from this address.
     *
     * @return true if this failure also blocked the whole network
     */
    public boolean recordFailure(String ip, String subnet, long now) {
        if (ips.size() < MAX_TRACKED || ips.containsKey(ip)) {
            ips.compute(ip, (k, old) -> {
                int strikes = old == null || now - old.lastFailure() > STRIKE_MEMORY_MS ? 1 : old.strikes() + 1;
                long block = Math.min(IP_BLOCK_MAX_MS, IP_BLOCK_BASE_MS << Math.min(strikes - 1, 10));
                return new IpRecord(strikes, now + block, now);
            });
        }
        if (subnet == null || (subnetFailures.size() >= MAX_TRACKED && !subnetFailures.containsKey(subnet))) {
            return false;
        }
        ConcurrentLinkedDeque<Long> times = subnetFailures.computeIfAbsent(subnet, k -> new ConcurrentLinkedDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > SUBNET_WINDOW_MS) times.pollFirst();
        if (times.size() >= SUBNET_FAILURES) {
            Long previous = subnetBlockedUntil.put(subnet, now + SUBNET_BLOCK_MS);
            return previous == null || previous < now;
        }
        return false;
    }

    /** Seconds this address (or its network) is still blocked for; 0 if not blocked. */
    public long blockedSeconds(String ip, String subnet, long now) {
        long until = 0;
        IpRecord record = ips.get(ip);
        if (record != null) until = record.blockedUntil();
        Long subnetUntil = subnet == null ? null : subnetBlockedUntil.get(subnet);
        if (subnetUntil != null) until = Math.max(until, subnetUntil);
        return until > now ? (until - now + 999) / 1000 : 0;
    }

    public void forgetOld(long now) {
        ips.values().removeIf(r -> r.blockedUntil() < now && now - r.lastFailure() > STRIKE_MEMORY_MS);
        subnetBlockedUntil.values().removeIf(until -> until < now);
        subnetFailures.values().removeIf(times -> times.isEmpty() || now - times.peekLast() > SUBNET_WINDOW_MS);
    }
}
