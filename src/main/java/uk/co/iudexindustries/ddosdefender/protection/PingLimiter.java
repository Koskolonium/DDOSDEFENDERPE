package uk.co.iudexindustries.ddosdefender.protection;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PingLimiter {

    private final Map<String, SubnetEntry> entries = new ConcurrentHashMap<>();

    private static final class SubnetEntry {
        final ArrayDeque<Long> timestamps = new ArrayDeque<>(8);
    }

    // No allocations, no separate locks map, no race condition.
    // Inline trimming keeps the deque bounded to burstLimit+1 entries automatically.
    public boolean isOverLimit(String subnet, long now, long windowMs, int burstLimit) {
        SubnetEntry entry = entries.computeIfAbsent(subnet, k -> new SubnetEntry());
        synchronized (entry) {
            entry.timestamps.addLast(now);
            long cutoff = now - windowMs;
            while (!entry.timestamps.isEmpty() && entry.timestamps.peekFirst() < cutoff) {
                entry.timestamps.pollFirst();
            }
            return entry.timestamps.size() > burstLimit;
        }
    }

    public void forgetBefore(long cutoff) {
        entries.entrySet().removeIf(e -> {
            SubnetEntry entry = e.getValue();
            synchronized (entry) {
                entry.timestamps.removeIf(t -> t < cutoff);
                return entry.timestamps.isEmpty();
            }
        });
    }
}
