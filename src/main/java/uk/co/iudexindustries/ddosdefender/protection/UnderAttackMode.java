package uk.co.iudexindustries.ddosdefender.protection;

import uk.co.iudexindustries.ddosdefender.core.StaffNotifier;
import uk.co.iudexindustries.ddosdefender.fastpass.FastPass;
import uk.co.iudexindustries.ddosdefender.listener.SessionFailureListener;
import lombok.experimental.UtilityClass;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Under-attack mode. Switched on when failed Mojang session checks (proven
 * impostors, see SessionFailureListener) come from many networks at once — a
 * distributed attack replaying real names + UUIDs, which per-network blocks
 * alone can't keep up with. While on, only FastPass players on their usual
 * network get in.
 *
 * Telling when it's over: once locked, impostors stop reaching the server's
 * session check, so failures stop and there'd be no signal. So one non-FastPass
 * login every PROBE_INTERVAL is let through as a probe — if probes keep failing
 * the attack is still on; QUIET_TO_END without a failure ends it. Probes also
 * give real newcomers a chance during a long attack.
 *
 * A manual "on" (admin command) stays on until a manual "off".
 */
@UtilityClass
public class UnderAttackMode {

    static final long WINDOW_MS         = 2 * 60_000L;
    static final int  TRIGGER_FAILURES  = 10;
    static final int  TRIGGER_NETWORKS  = 5;
    static final long QUIET_TO_END_MS   = 5 * 60_000L;
    static final long PROBE_INTERVAL_MS = 15_000L;

    private record Failure(long at, String subnet) {}

    private static final Object lock = new Object();
    private static final ArrayDeque<Failure> recent = new ArrayDeque<>();
    private static boolean active;
    private static boolean manual;
    private static long activeSince;
    private static long lastFailure;
    private static long lastProbe;
    private static Logger logger;
    private static StaffNotifier staff = StaffNotifier.NONE;

    public void init(Logger log, StaffNotifier notifier) {
        logger = log;
        staff = notifier;
    }

    /** A login failed Minecraft's own Mojang session check. */
    public void recordImpostor(String subnet, long now) {
        synchronized (lock) {
            lastFailure = now;
            recent.addLast(new Failure(now, subnet));
            while (!recent.isEmpty() && now - recent.peekFirst().at() > WINDOW_MS) recent.pollFirst();
            if (active || recent.size() < TRIGGER_FAILURES) return;
            Set<String> networks = new HashSet<>();
            for (Failure f : recent) networks.add(f.subnet());
            if (networks.size() < TRIGGER_NETWORKS) return;
            active = true;
            activeSince = now;
            announceAttack(recent.size(), networks.size());
        }
    }

    /** True while locked down; ends itself after QUIET_TO_END without a failure. */
    public boolean isActive(long now) {
        synchronized (lock) {
            if (active && !manual && now - lastFailure > QUIET_TO_END_MS) {
                active = false;
                recent.clear();
                announceAttackOver(now - activeSince);
            }
            return active;
        }
    }

    /** During lockdown: may this non-FastPass login through as a probe? */
    public boolean allowProbe(long now) {
        synchronized (lock) {
            if (now - lastProbe < PROBE_INTERVAL_MS) return false;
            lastProbe = now;
            return true;
        }
    }

    /** Admin command. On stays on until turned off; off also clears the trigger window. */
    public void setManual(boolean on, long now) {
        synchronized (lock) {
            boolean wasActive = active;
            active = on;
            manual = on;
            if (on) activeSince = now;
            if (on != wasActive) announceManual(on);
            recent.clear();
            lastFailure = now;
        }
    }

    public String status(long now) {
        boolean on = isActive(now);
        synchronized (lock) {
            if (!on) return "off (" + recent.size() + " failed verifications in the last 2 min)";
            return "ON" + (manual ? " (manual — /ddosdefender lockdown off to end)" : " (automatic)")
                    + " for " + (now - activeSince) / 60_000 + " min";
        }
    }

    // ── Announcements: the console, and staff in-game ─────────────────────────

    private void announceAttack(int failures, int networks) {
        warn("==================== DDoSDefender ====================",
             "ATTACK DETECTED: " + failures + " failed account verifications from " + networks
                     + " different networks in the last 2 minutes.",
             "Enhanced protection is now ON: only frequent players (FastPass) can join for the time being.",
             "One newcomer is let through every 15 seconds to check whether the attack is still going.",
             "It switches off by itself after 5 minutes without a failed verification.",
             "To end it now: /ddosdefender lockdown off",
             "======================================================");
        staff.send("§4§l⚠ DDoSDefender §8» §cAn attack on the server has been detected!\n"
                + "§7Enhanced protection is §c§lON§7: only frequent players can join for the time being.\n"
                + "§8It switches off by itself once the attack stops. §7/ddosdefender lockdown status");
    }

    private void announceAttackOver(long durationMs) {
        long minutes = Math.max(1, durationMs / 60_000);
        info("DDoSDefender: the attack is over (no failed account verifications for 5 minutes). "
                + "Enhanced protection was on for " + minutes + " min and is now OFF - everyone can join again.");
        staff.send("§2§l✔ DDoSDefender §8» §aThe attack is over.\n"
                + "§7Enhanced protection is §aOFF§7 after " + minutes + " min: everyone can join again.");
    }

    private void announceManual(boolean on) {
        if (on) {
            warn("DDoSDefender: enhanced protection switched ON by an admin - only frequent players (FastPass) "
                    + "can join until /ddosdefender lockdown off.");
            staff.send("§6§lDDoSDefender §8» §eEnhanced protection switched §c§lON§e by an admin.\n"
                    + "§7Only frequent players can join until §f/ddosdefender lockdown off§7.");
        } else {
            info("DDoSDefender: enhanced protection switched OFF by an admin - everyone can join again.");
            staff.send("§6§lDDoSDefender §8» §eEnhanced protection switched §a§lOFF§e by an admin.");
        }
    }

    private void warn(String... lines) {
        if (logger != null) for (String line : lines) logger.warning(line);
    }

    private void info(String message) {
        if (logger != null) logger.info(message);
    }
}
