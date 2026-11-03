package uk.co.iudexindustries.ddosdefender.listener;

import uk.co.iudexindustries.ddosdefender.protection.ImpostorTracker;
import uk.co.iudexindustries.ddosdefender.protection.TrafficMonitor;
import uk.co.iudexindustries.ddosdefender.protection.UnderAttackMode;
import uk.co.iudexindustries.ddosdefender.network.RealAddress;
import uk.co.iudexindustries.ddosdefender.network.Subnets;
import uk.co.iudexindustries.ddosdefender.network.TrustedProxies;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Watches the server's own login disconnects for a failed Mojang session
 * check — the one way a client that passed our checks with a real name + UUID
 * can be proven not to own that account — and hands the network to
 * ImpostorTracker so its next attempts are stopped before they reach the
 * server's authentication again.
 *
 * Read-only: never changes or cancels a packet. Any other disconnect reason
 * (bans, whitelist, server full, Mojang's auth servers being down) is ignored.
 */
public class SessionFailureListener extends PacketListenerAbstract {

    // Vanilla, Paper and Velocity.
    private static final String UNVERIFIED_KEY = "multiplayer.disconnect.unverified_username";
    // Spigot's and BungeeCord's own wording.
    private static final List<String> UNVERIFIED_TEXT =
            List.of("failed to verify username", "not authenticated with minecraft.net");

    private final Logger logger;

    public SessionFailureListener(Logger logger) {
        super(PacketListenerPriority.MONITOR);
        this.logger = logger;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Login.Server.DISCONNECT) return;
        Component reason;
        try {
            reason = new WrapperLoginServerDisconnect(event).getReason();
        } catch (Exception e) {
            return;
        }
        if (!isUnverified(reason)) return;

        InetSocketAddress address = RealAddress.resolve(event.getUser().getAddress());
        if (address == null || address.getAddress() == null || address.getAddress().isLoopbackAddress()) return;
        String ip = address.getAddress().getHostAddress();
        // No real address behind a trusted proxy: blocking it would block everyone.
        if (TrustedProxies.isTrusted(ip)) return;

        String subnet = Subnets.of(ip);
        long now = System.currentTimeMillis();
        TrafficMonitor.recordBlocked(subnet);
        UnderAttackMode.recordImpostor(subnet, now);
        if (ImpostorTracker.recordFailure(ip, subnet, now)) {
            logger.warning("Repeated impostor logins (failed Mojang session check) from network " + subnet
                    + " — blocking it for 10 minutes");
        }
    }

    // The reason itself, or any part of it, is the "unverified username" message.
    static boolean isUnverified(Component component) {
        if (component == null) return false;
        if (component instanceof TranslatableComponent translatable
                && UNVERIFIED_KEY.equals(translatable.key())) {
            return true;
        }
        if (component instanceof TextComponent text) {
            String content = text.content().toLowerCase(Locale.ROOT);
            for (String phrase : UNVERIFIED_TEXT) {
                if (content.contains(phrase)) return true;
            }
        }
        for (Component child : component.children()) {
            if (isUnverified(child)) return true;
        }
        return false;
    }
}
