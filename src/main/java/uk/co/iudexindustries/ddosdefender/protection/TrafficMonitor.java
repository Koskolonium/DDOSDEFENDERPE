package uk.co.iudexindustries.ddosdefender.protection;

import lombok.experimental.UtilityClass;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Counts what DDoSDefender turns away and tells the console about it without
 * flooding it: one summary every 15 minutes (only when something was blocked),
 * plus an immediate warning when a large burst is being blocked right now.
 */
@UtilityClass
public class TrafficMonitor {

    static final long REPORT_EVERY_MINUTES = 15;
    // A burst: this many blocks inside BURST_WINDOW_MS. Warned about at most
    // once per BURST_COOLDOWN_MS, so an attack produces a few lines, not thousands.
    static final int  BURST_THRESHOLD   = 100;
    static final long BURST_WINDOW_MS   = 10_000L;
    static final long BURST_COOLDOWN_MS = 60_000L;
    // Only the number of networks is shown, so past this there's no point remembering more.
    private static final int MAX_NETWORKS_COUNTED = 100_000;

    private static final AtomicLong totalBlocked = new AtomicLong();
    private static final AtomicLong blockedSinceReport = new AtomicLong();
    private static final Set<String> networksSinceReport = ConcurrentHashMap.newKeySet();

    private static final AtomicInteger burstCount = new AtomicInteger();
    private static volatile long burstWindowStart;
    private static volatile long lastBurstWarning;

    private static Logger logger;

    public void init(Logger log, ScheduledExecutorService scheduler) {
        logger = log;
        blockedSinceReport.set(0);
        networksSinceReport.clear();
        scheduler.scheduleAtFixedRate(TrafficMonitor::report,
                REPORT_EVERY_MINUTES, REPORT_EVERY_MINUTES, TimeUnit.MINUTES);
    }

    public void recordBlocked(String subnet) {
        totalBlocked.incrementAndGet();
        blockedSinceReport.incrementAndGet();
        if (networksSinceReport.size() < MAX_NETWORKS_COUNTED) networksSinceReport.add(subnet);
        checkBurst(System.currentTimeMillis());
    }

    private void checkBurst(long now) {
        if (now - burstWindowStart > BURST_WINDOW_MS) {
            burstWindowStart = now;
            burstCount.set(0);
        }
        if (burstCount.incrementAndGet() < BURST_THRESHOLD || now - lastBurstWarning < BURST_COOLDOWN_MS) return;
        lastBurstWarning = now;
        if (logger != null) {
            logger.warning("Heavy traffic: DDoSDefender has nullified " + BURST_THRESHOLD
                    + "+ connections in the last " + BURST_WINDOW_MS / 1000 + " seconds. Your server is protected.");
        }
    }

    static String summary(long blocked, int networks, long total) {
        return "DDoSDefender has nullified " + format(blocked) + " malicious connection" + (blocked == 1 ? "" : "s")
                + " from " + format(networks) + " network" + (networks == 1 ? "" : "s")
                + " in the last " + REPORT_EVERY_MINUTES + " minutes (" + format(total) + " since start-up).";
    }

    private void report() {
        long blocked = blockedSinceReport.getAndSet(0);
        int networks = networksSinceReport.size();
        networksSinceReport.clear();
        if (blocked > 0 && logger != null) logger.info(summary(blocked, networks, totalBlocked.get()));
    }

    private String format(long number) {
        return String.format("%,d", number);
    }
}
