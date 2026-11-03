package uk.co.iudexindustries.ddosdefender.listener;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake;
import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake.ConnectionIntention;
import uk.co.iudexindustries.ddosdefender.network.RealAddress;
import uk.co.iudexindustries.ddosdefender.network.Subnets;
import uk.co.iudexindustries.ddosdefender.network.TrustedProxies;
import uk.co.iudexindustries.ddosdefender.protection.HandshakeValidator;
import uk.co.iudexindustries.ddosdefender.protection.ImpostorTracker;
import uk.co.iudexindustries.ddosdefender.protection.KickMessages;
import uk.co.iudexindustries.ddosdefender.protection.PingLimiter;
import uk.co.iudexindustries.ddosdefender.protection.TrafficMonitor;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The first line of defence: every connection's handshake packet, before the
 * server spends anything on it.
 *
 *  1. Works out the real client address (TCPShield's signed data, if present).
 *  2. Drops handshakes no real client sends (see HandshakeValidator).
 *  3. Server-list pings: at most PING_BURST per PING_WINDOW per network.
 *  4. Logins: at most MAX_LOGINS_PER_SECOND server-wide, and one per
 *     LOGIN_COOLDOWN per network (trusted proxies are exempt from the
 *     per-network part — every player shares their address).
 */
public class HandshakeListener extends PacketListenerAbstract {

    private static final long PING_WINDOW_MS = 2_000L;
    private static final int  PING_BURST = 3;
    private static final long LOGIN_COOLDOWN_MS = 1_000L;
    private static final int  MAX_LOGINS_PER_SECOND = 25;
    private static final long IDLE_FORGET_MS = 60_000L;

    private final boolean allowLocalHost;
    private final PingLimiter pings = new PingLimiter();
    private final Map<String, Long> loginCooldownUntil = new ConcurrentHashMap<>();
    private final AtomicInteger loginsThisSecond = new AtomicInteger();

    public HandshakeListener(ScheduledExecutorService scheduler, boolean allowLocalHost) {
        super(PacketListenerPriority.LOW);
        this.allowLocalHost = allowLocalHost;
        scheduler.scheduleAtFixedRate(() -> loginsThisSecond.set(0), 1, 1, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::forgetIdle, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Handshaking.Client.HANDSHAKE) return;

        WrapperHandshakingClientHandshake handshake;
        try {
            handshake = new WrapperHandshakingClientHandshake(event);
        } catch (Exception malformed) {
            drop(event, "invalid");
            return;
        }
        if (!HandshakeValidator.isValid(handshake.getProtocolVersion(), handshake.getServerPort(),
                handshake.getIntention())) {
            drop(event, "invalid");
            return;
        }

        InetSocketAddress address = RealAddress.fromHandshake(
                event.getUser().getAddress(), handshake.getServerAddress());
        if (address == null || address.getAddress() == null) return;
        if (allowLocalHost && address.getAddress().isLoopbackAddress()) return;

        String ip = address.getAddress().getHostAddress();
        String subnet = Subnets.of(ip);
        boolean trustedProxy = TrustedProxies.isTrusted(ip);
        long now = System.currentTimeMillis();

        if (handshake.getIntention() == ConnectionIntention.STATUS) {
            if (!trustedProxy && pings.isOverLimit(subnet, now, PING_WINDOW_MS, PING_BURST)) {
                drop(event, subnet);
            }
            return;
        }

        // Login or transfer.
        if (loginsThisSecond.incrementAndGet() > MAX_LOGINS_PER_SECOND) {
            kick(event, subnet, KickMessages.RATE_LIMITED_1S);
            return;
        }
        if (trustedProxy) return;

        long[] waitSeconds = {0};
        loginCooldownUntil.compute(subnet, (key, until) -> {
            if (until != null && now < until) {
                waitSeconds[0] = Math.max(1, (until - now + 999) / 1000);
                return until + LOGIN_COOLDOWN_MS; // retrying during the cooldown extends it
            }
            return now + LOGIN_COOLDOWN_MS;
        });
        if (waitSeconds[0] > 0) kick(event, subnet, KickMessages.rateLimited(waitSeconds[0]));
    }

    // Before login there's no screen to show a message on: just close.
    private void drop(PacketReceiveEvent event, String subnet) {
        event.setCancelled(true);
        TrafficMonitor.recordBlocked(subnet);
        KickMessages.close(event);
    }

    private void kick(PacketReceiveEvent event, String subnet, net.kyori.adventure.text.Component message) {
        event.setCancelled(true);
        TrafficMonitor.recordBlocked(subnet);
        KickMessages.disconnect(event, message);
    }

    private void forgetIdle() {
        long now = System.currentTimeMillis();
        loginCooldownUntil.values().removeIf(until -> until < now - IDLE_FORGET_MS);
        pings.forgetBefore(now - IDLE_FORGET_MS);
        RealAddress.forgetOld();
        ImpostorTracker.forgetOld(now);
    }
}
