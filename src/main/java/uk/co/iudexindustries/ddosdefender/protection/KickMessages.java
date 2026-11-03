package uk.co.iudexindustries.ddosdefender.protection;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import lombok.experimental.UtilityClass;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * What a turned-away player sees, and the two ways of turning them away.
 * Messages that never change are built once, so a flood of rejections
 * doesn't rebuild them every time.
 */
@UtilityClass
public class KickMessages {

    /** Failed a check. Deliberately vague: no hint about which one. */
    public static final Component BLOCKED = message("Suspicious activity detected. Connection blocked.", NamedTextColor.GOLD);

    /** Not their fault: Mojang didn't answer, or this minute's lookups are used up. */
    public static final Component VERIFICATION_BUSY = message("Account verification is busy. Try again in a moment.", NamedTextColor.YELLOW);

    public static final Component UNDER_ATTACK = message(
            "Server under attack — only known players can join right now. Please try again in a few minutes.",
            NamedTextColor.YELLOW);

    public static final Component RATE_LIMITED_1S = rateLimited(1);

    public Component rateLimited(long seconds) {
        return message("Too many requests. Try again in " + seconds + "s.", NamedTextColor.YELLOW);
    }

    public Component impostorNetwork(long seconds) {
        return message("Failed account verification from your network. Try again in " + seconds + "s.",
                NamedTextColor.YELLOW);
    }

    /** Shows the message, then closes. Only valid once the client is in the login stage. */
    public void disconnect(PacketReceiveEvent event, Component message) {
        disconnect(event.getUser(), message);
    }

    public void disconnect(User user, Component message) {
        try { user.sendPacket(new WrapperLoginServerDisconnect(message)); } catch (Exception ignored) {}
        close(user);
    }

    /** Closes without a message: for handshakes and server-list pings, which have no screen to show one on. */
    public void close(PacketReceiveEvent event) {
        close(event.getUser());
    }

    public void close(User user) {
        try { user.closeConnection(); } catch (Exception ignored) {}
    }

    private Component message(String text, NamedTextColor colour) {
        return Component.text()
                .append(Component.text("DDoSDefender", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text("⚠ " + text + " ⚠", colour))
                .build();
    }
}
