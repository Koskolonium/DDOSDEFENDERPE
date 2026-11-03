package uk.co.iudexindustries.ddosdefender.network;

import lombok.experimental.UtilityClass;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// IPs of reverse proxies/tunnels (Playit.gg, BungeeCord, Velocity, TCPShield,
// etc.) that all player traffic legitimately passes through. Every real
// player looks identical to the network-layer checks when they share one of
// these addresses, so subnet-keyed rate limiting is exempted for them —
// per-account checks (the login queue, name scoring) still run.
@UtilityClass
public class TrustedProxies {

    private static Set<String> trustedIps = Collections.emptySet();

    public static void init(List<String> ips) {
        if (ips == null || ips.isEmpty()) {
            trustedIps = Collections.emptySet();
            return;
        }
        Set<String> set = new HashSet<>(ips.size());
        for (String ip : ips) {
            if (ip != null && !ip.isBlank()) set.add(ip.trim());
        }
        trustedIps = set;
    }

    public static boolean isTrusted(String ip) {
        return ip != null && trustedIps.contains(ip);
    }
}
