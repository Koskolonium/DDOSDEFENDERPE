package uk.co.iudexindustries.ddosdefender.network;

import lombok.experimental.UtilityClass;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Real client addresses for connections that arrive through TCPShield.
 *
 * TCPShield puts the player's real address in the handshake's hostname as
 * "host///ip:port///timestamp///signature", signed (SHA512withECDSA) with
 * TCPShield's private key. Their own plugin unpacks it in a Bukkit event,
 * which runs after our packet listeners and only changes Paper's copy of
 * the address — so we read it ourselves, here, before any check runs.
 *
 * Only a valid TCPShield signature with a fresh timestamp is believed, so a
 * crafted hostname can't fake an address. Anything else (direct connections,
 * other proxies) keeps the socket address, exactly as before. The handshake
 * packet is never modified; the TCPShield plugin still sees it intact.
 *
 * Verification follows TCPShield's MIT-licensed RealIP plugin
 * (github.com/TCPShield/RealIP), whose public key is below.
 */
@UtilityClass
public class RealAddress {

    // TCPShield's handshake-signing public key (RealIP: signing_pub.key, EC P-384).
    private static final String TCPSHIELD_KEY =
            "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEEvy/Eh9v5QMkdzeTw1tPMCEljzKgw0zWwt926zRZJT9sv/OLEwkE/rB1"
            + "JrH3XXMTsv8w7gPzV4k9BjUAcZ1xH1yNG/AhNA23DQiFSXye/WFB+KlSr4hkx68niNJdvoPs";
    // TCPShield's plugin allows 3 s. A payload never reaches a client, so a
    // looser window costs nothing and spares servers with a drifting clock.
    private static final long MAX_SKEW_SECONDS = 60;
    // A connection's handshake and login are seconds apart; the login queue
    // can hold a player for a few minutes.
    private static final long TTL_MS = 10 * 60_000L;
    private static final int  MAX_ENTRIES = 100_000;
    // A signature check costs ~1-2 ms of CPU, and anyone can send a fake
    // "///" hostname. Past this many checks a second, payloads are treated as
    // unverified (the safe fallback) so floods can't turn this into a CPU sink.
    private static final int  MAX_VERIFICATIONS_PER_SECOND = 300;
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private static final PublicKey KEY = loadKey();
    // Socket address ("proxyIp:port") -> the real client behind it.
    private static final ConcurrentHashMap<String, Entry> realByConnection = new ConcurrentHashMap<>();
    private static final AtomicInteger verificationsThisSecond = new AtomicInteger();
    private static volatile long verificationSecond;

    private record Entry(InetSocketAddress real, long at) {}

    /**
     * Called for every handshake. Remembers the real address if the hostname
     * carries a valid TCPShield signature (and forgets any stale one for this
     * socket otherwise).
     *
     * @return the address every check should use for this connection
     */
    public InetSocketAddress fromHandshake(InetSocketAddress socket, String hostname) {
        String key = key(socket);
        if (key == null) return socket;
        long nowSeconds = System.currentTimeMillis() / 1000;
        InetSocketAddress real = hostname != null && hostname.contains("///") && takeVerification(nowSeconds)
                ? parse(hostname, nowSeconds, KEY) : null;
        if (real == null) {
            realByConnection.remove(key);
            return socket;
        }
        if (realByConnection.size() < MAX_ENTRIES) {
            realByConnection.put(key, new Entry(real, System.currentTimeMillis()));
        }
        return real;
    }

    /** The real address recorded at this connection's handshake, else the socket's own. */
    public InetSocketAddress resolve(InetSocketAddress socket) {
        String key = key(socket);
        Entry entry = key == null ? null : realByConnection.get(key);
        return entry != null ? entry.real() : socket;
    }

    // A race at the second boundary lets a few extra through; that's fine.
    private boolean takeVerification(long nowSeconds) {
        if (nowSeconds != verificationSecond) {
            verificationSecond = nowSeconds;
            verificationsThisSecond.set(0);
        }
        return verificationsThisSecond.incrementAndGet() <= MAX_VERIFICATIONS_PER_SECOND;
    }

    public void forgetOld() {
        long cutoff = System.currentTimeMillis() - TTL_MS;
        realByConnection.values().removeIf(e -> e.at() < cutoff);
    }

    /**
     * The verified real address in a TCPShield hostname, or null if the
     * hostname isn't a TCPShield payload or fails any check. Package-private
     * with the key as a parameter so it can be tested with a test key.
     */
    static InetSocketAddress parse(String hostname, long nowSeconds, PublicKey key) {
        if (hostname == null || key == null || !hostname.contains("///")) return null;
        String[] parts = hostname.split("///", -1);
        if (parts.length != 4) return null;
        try {
            String signature = parts[3];
            int nul = signature.indexOf('\0'); // Forge clients append "\0FML..." after it
            if (nul != -1) signature = signature.substring(0, nul);

            long timestamp = Long.parseLong(parts[2]);
            if (Math.abs(nowSeconds - timestamp) > MAX_SKEW_SECONDS) return null;

            int colon = parts[1].lastIndexOf(':');
            if (colon <= 0) return null;
            String host = parts[1].substring(0, colon);
            int port = Integer.parseInt(parts[1].substring(colon + 1));
            if (port < 0 || port > 65535 || !IP_LITERAL.matcher(host).matches()) return null;

            // Signed text exactly as TCPShield builds it.
            String signed = parts[0] + "///" + host + ":" + port + "///" + timestamp;
            Signature verifier = Signature.getInstance("SHA512withECDSA");
            verifier.initVerify(key);
            verifier.update(signed.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getDecoder().decode(signature))) return null;

            // A literal (checked above), so this never does a DNS lookup.
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (IllegalArgumentException | GeneralSecurityException | UnknownHostException e) {
            return null; // includes NumberFormatException and bad Base64
        }
    }

    private String key(InetSocketAddress socket) {
        if (socket == null || socket.getAddress() == null) return null;
        return socket.getAddress().getHostAddress() + ":" + socket.getPort();
    }

    private PublicKey loadKey() {
        try {
            return KeyFactory.getInstance("EC").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(TCPSHIELD_KEY)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null; // without it no payload is ever believed — same as before
        }
    }
}
