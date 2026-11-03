package uk.co.iudexindustries.ddosdefender.network;

import lombok.experimental.UtilityClass;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;

@UtilityClass
public class Subnets {

    // IPv4 subnets are derived with cheap string slicing — no cache needed.
    // IPv6 subnets require InetAddress.getByName() (byte array parsing) — cache those.
    private static final int IP6_CACHE_MAX = 2048;
    private static final ConcurrentHashMap<String, String> ip6Cache = new ConcurrentHashMap<>();

    public String of(String ip) {
        if (ip == null || ip.isEmpty()) return "unknown";

        if (ip.indexOf(':') >= 0) return extractIPv6Subnet(ip);

        // IPv4: return first 3 octets (/24)
        int len = ip.length();
        int dots = 0;
        for (int i = 0; i < len; i++) {
            if (ip.charAt(i) == '.') {
                if (++dots == 3) return ip.substring(0, i);
            }
        }
        return ip; // fewer than 3 dots — return as-is
    }

    private String extractIPv6Subnet(String ip) {
        String cached = ip6Cache.get(ip);
        if (cached != null) return cached;

        try {
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length != 16) return ip;
            // /48 prefix — first 6 bytes
            String result = String.format("%02x%02x:%02x%02x:%02x%02x",
                    b[0], b[1], b[2], b[3], b[4], b[5]);
            // Simple size-capped cache — evict everything when full rather than tracking LRU.
            if (ip6Cache.size() < IP6_CACHE_MAX) {
                ip6Cache.put(ip, result);
            } else {
                ip6Cache.clear();
                ip6Cache.put(ip, result);
            }
            return result;
        } catch (UnknownHostException e) {
            return ip;
        }
    }
}
