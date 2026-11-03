package uk.co.iudexindustries.ddosdefender.protection;

import com.github.retrooper.packetevents.wrapper.handshaking.client.WrapperHandshakingClientHandshake.ConnectionIntention;
import lombok.experimental.UtilityClass;

/**
 * Sanity checks on a connection's very first packet, before anything costs
 * more than a few comparisons. Only rejects what no real client sends.
 *
 * Protocol versions are checked by range, not against a fixed list: servers
 * with ViaVersion/ViaBackwards accept clients from 1.7 up to versions newer
 * than this plugin, and a fixed list would turn those players away (and every
 * player the day a new Minecraft version comes out).
 */
@UtilityClass
public class HandshakeValidator {

    // Release protocols run from 4 (1.7.2) upwards — 26.2 is 776 — so 2000 leaves
    // years of headroom. Snapshots since 1.16.4 set bit 30 on a small number.
    private static final int MAX_RELEASE_PROTOCOL = 2_000;
    private static final int SNAPSHOT_FLAG = 0x40000000;
    private static final int MAX_SNAPSHOT_NUMBER = 1_000;

    // 0 = don't check (the default). A handshake carries the port the player
    // typed, which behind port forwarding or a proxy isn't the one we listen on.
    private static volatile int expectedPort;

    public void init(int port) {
        expectedPort = port;
    }

    public boolean isValid(int protocolVersion, int serverPort, ConnectionIntention intention) {
        if (expectedPort > 0 && serverPort != expectedPort) return false;
        return isKnownProtocol(protocolVersion) && isAllowedIntention(intention);
    }

    static boolean isKnownProtocol(int version) {
        if (version <= 0) return false; // also stops -1 slipping in through the snapshot bit
        if (version >= 4 && version <= MAX_RELEASE_PROTOCOL) return true;
        return (version & SNAPSHOT_FLAG) != 0 && (version & ~SNAPSHOT_FLAG) <= MAX_SNAPSHOT_NUMBER;
    }

    private boolean isAllowedIntention(ConnectionIntention intention) {
        return intention == ConnectionIntention.STATUS
                || intention == ConnectionIntention.LOGIN
                || intention == ConnectionIntention.TRANSFER;
    }
}
