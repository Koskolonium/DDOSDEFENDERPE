package uk.co.iudexindustries.ddosdefender.listener;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import net.kyori.adventure.text.Component;
import uk.co.iudexindustries.ddosdefender.checks.NameScorer;
import uk.co.iudexindustries.ddosdefender.core.PluginConfig;
import uk.co.iudexindustries.ddosdefender.database.DatabaseSync;
import uk.co.iudexindustries.ddosdefender.database.PlayerDatabase;
import uk.co.iudexindustries.ddosdefender.fastpass.FastPass;
import uk.co.iudexindustries.ddosdefender.mojang.MojangApi;
import uk.co.iudexindustries.ddosdefender.mojang.MojangBudget;
import uk.co.iudexindustries.ddosdefender.network.RealAddress;
import uk.co.iudexindustries.ddosdefender.network.Subnets;
import uk.co.iudexindustries.ddosdefender.network.TrustedProxies;
import uk.co.iudexindustries.ddosdefender.protection.ImpostorTracker;
import uk.co.iudexindustries.ddosdefender.protection.KickMessages;
import uk.co.iudexindustries.ddosdefender.protection.LoginFormat;
import uk.co.iudexindustries.ddosdefender.protection.TrafficMonitor;
import uk.co.iudexindustries.ddosdefender.protection.UnderAttackMode;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides who may log in. Every Login Start packet (name + UUID) is held back,
 * checked off the network thread, and — if it passes — queued and handed on to
 * the server at a steady rate (LOGINS_PER_TICK every TICK_MS), so a burst of
 * real joins never hits the server all at once.
 *
 * What's handed on is the client's original packet, byte for byte, fed in
 * straight after PacketEvents' decoder — exactly where the server would have
 * received it. Nothing is rebuilt, so every client version (and the 1.19 chat
 * signing data) passes through untouched, and it doesn't pass this listener
 * again. (PacketEvents' own receivePacket() starts at the front of the
 * pipeline, which Velocity's frame decoder rejects.)
 *
 * The checks, in order (see verify()):
 *   1. FastPass       known player on one of their networks → straight in
 *   2. Impostor block their network recently failed Mojang's session check
 *   3. Under attack   only FastPass players, plus one probe every 15 s
 *   4. Bedrock        Floodgate players skip the Java-account checks below
 *   5. Format         name/UUID must be well-formed
 *   6. Database       exact UUID + name known to be real → in
 *   7. Database miss  name score decides whether Mojang is asked (MojangBudget)
 * Without a usable database (or for clients too old to send a UUID) a
 * name-score pipeline decides instead (verifyWithoutDatabase()). On an
 * offline-mode server there are no accounts to check, so steps 6-7 are
 * replaced by the name score alone (100+ is blocked).
 */
public class LoginListener extends PacketListenerAbstract {

    private static final int  QUEUE_CAPACITY = 60;
    private static final int  LOGINS_PER_TICK = 3;
    private static final long TICK_MS = 50L;
    private static final int  MAX_QUEUED_PER_NETWORK = 3;
    private static final long QUEUE_TIMEOUT_MS = 10_000L;
    // How far back the swarm checks look.
    private static final long JOIN_WINDOW_MS = 60_000L;

    private final Logger logger;
    private final ExecutorService verifier;
    private final boolean allowLocalHost;
    private final String bedrockPrefix;
    private final boolean accountChecks;

    private final LinkedBlockingQueue<QueuedLogin> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Map<String, AtomicInteger> queuedPerNetwork = new ConcurrentHashMap<>();
    private final Set<String> beingVerified = ConcurrentHashMap.newKeySet();

    // The join window: names and times of the last minute's scored logins.
    private final Object windowLock = new Object();
    private final ArrayDeque<String> windowNames = new ArrayDeque<>();
    private final ArrayDeque<Long> windowTimes = new ArrayDeque<>();
    // Base names ("Bot" of "Bot123") seen recently, for the no-database pipeline.
    private final Map<String, Long> recentBaseNames = new ConcurrentHashMap<>();

    // `packet` is the client's original Login Start (id + data), released once handed on or dropped.
    private record QueuedLogin(String name, User user, ByteBuf packet, String connectionId, String queueKey,
                               long queuedAt) {}

    public LoginListener(Logger logger, ScheduledExecutorService scheduler, ExecutorService verifier,
                         PluginConfig config) {
        super(PacketListenerPriority.LOW);
        this.logger = logger;
        this.verifier = verifier;
        this.allowLocalHost = config.allowLocalHost();
        this.bedrockPrefix = config.bedrockPrefix();
        this.accountChecks = config.accountChecks();
        scheduler.scheduleAtFixedRate(this::releaseQueued, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::forgetOld, 10, 10, TimeUnit.SECONDS);
    }

    // ── Receiving a login ────────────────────────────────────────────────────

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Login.Client.LOGIN_START) return;

        InetSocketAddress socket = event.getUser().getAddress();
        if (allowLocalHost && socket != null && socket.getAddress() != null
                && socket.getAddress().isLoopbackAddress()) {
            return;
        }
        InetSocketAddress address = RealAddress.resolve(socket);
        String connectionId = connectionId(address);
        // A second Login Start while the first is being checked: ignore it.
        if (connectionId != null && beingVerified.contains(connectionId)) {
            event.setCancelled(true);
            return;
        }

        // Copy the packet before reading it: reading moves the buffer's position
        // to the end, and a copy taken after would hold no data.
        ByteBuf packet = (ByteBuf) event.getFullBufferClone();
        WrapperLoginClientLoginStart login;
        try {
            login = new WrapperLoginClientLoginStart(event);
        } catch (Exception malformed) {
            packet.release();
            event.setCancelled(true);
            TrafficMonitor.recordBlocked(Subnets.of(ip(address)));
            KickMessages.close(event);
            return;
        }

        User user = event.getUser();
        event.setCancelled(true);
        if (connectionId != null) beingVerified.add(connectionId);

        String name = login.getUsername();
        UUID uuid = login.getPlayerUUID().orElse(null);
        try {
            verifier.execute(() -> verifyThenQueue(name, uuid, address, user, packet, connectionId));
        } catch (RejectedExecutionException overloaded) {
            reject(user, packet, connectionId, KickMessages.VERIFICATION_BUSY);
        }
    }

    private void verifyThenQueue(String name, UUID uuid, InetSocketAddress address, User user, ByteBuf packet,
                                 String connectionId) {
        try {
            Component refusal = verify(name, uuid, address);
            if (refusal != null) {
                reject(user, packet, connectionId, refusal);
                return;
            }
            // Per-network cap on waiting logins. Behind a trusted proxy every
            // player shares one network, so the account is the key instead.
            String ip = ip(address);
            String queueKey = TrustedProxies.isTrusted(ip)
                    ? "account:" + (uuid != null ? uuid : name)
                    : Subnets.of(ip);
            AtomicInteger waiting = queuedPerNetwork.computeIfAbsent(queueKey, k -> new AtomicInteger());
            if (waiting.incrementAndGet() > MAX_QUEUED_PER_NETWORK
                    || !queue.offer(new QueuedLogin(name, user, packet, connectionId, queueKey,
                    System.currentTimeMillis()))) {
                leaveQueue(queueKey);
                reject(user, packet, connectionId, KickMessages.RATE_LIMITED_1S);
                return;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Login check failed for " + name, e);
            reject(user, packet, connectionId, KickMessages.VERIFICATION_BUSY);
        }
    }

    // ── Releasing approved logins to the server ──────────────────────────────

    private void releaseQueued() {
        if (queue.isEmpty()) return;
        List<QueuedLogin> batch = new ArrayList<>(LOGINS_PER_TICK);
        queue.drainTo(batch, LOGINS_PER_TICK);
        long now = System.currentTimeMillis();

        for (QueuedLogin login : batch) {
            leaveQueue(login.queueKey());
            if (now - login.queuedAt() > QUEUE_TIMEOUT_MS) {
                reject(login.user(), login.packet(), login.connectionId(), KickMessages.RATE_LIMITED_1S);
                continue;
            }
            if (login.connectionId() != null) beingVerified.remove(login.connectionId());
            handOn(login);
        }
    }

    private void leaveQueue(String queueKey) {
        // Removed at zero, so an attack from millions of networks leaves nothing behind.
        queuedPerNetwork.computeIfPresent(queueKey, (k, waiting) -> waiting.decrementAndGet() <= 0 ? null : waiting);
    }

    // The original packet goes in right after PacketEvents' decoder: the next
    // handler is the server's own decoder, as if it had never been held back.
    // Netty runs it on the connection's own thread.
    private void handOn(QueuedLogin login) {
        try {
            Channel channel = (Channel) login.user().getChannel();
            ChannelHandlerContext decoder = channel.isOpen() ? channel.pipeline().context(PacketEvents.DECODER_NAME) : null;
            if (decoder == null) {
                login.packet().release(); // the player left while waiting
                return;
            }
            decoder.fireChannelRead(login.packet());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Could not pass " + login.name() + "'s login on to the server", e);
            KickMessages.close(login.user());
        }
    }

    private void reject(User user, ByteBuf packet, String connectionId, Component message) {
        KickMessages.disconnect(user, message);
        if (connectionId != null) beingVerified.remove(connectionId);
        packet.release();
    }

    // ── The checks ───────────────────────────────────────────────────────────

    /** @return null to let the player in, or the message to turn them away with */
    private Component verify(String name, UUID uuid, InetSocketAddress address) {
        String ip = ip(address);
        String subnet = Subnets.of(ip);
        long now = System.currentTimeMillis();

        // 1. FastPass: a known player on one of their (up to 3) networks. The
        //    refresh keeps the networks they actually use when a new one appears.
        String fastPassEntry = FastPass.getVerified().get(name);
        if (FastPass.matches(fastPassEntry, subnet)) {
            FastPass.verify(name, subnet);
            return null;
        }

        // 2. Somewhere on this IP or network recently failed Mojang's own
        //    session check (SessionFailureListener): don't spend another login on it.
        long impostorBlock = ImpostorTracker.blockedSeconds(ip, subnet, now);
        if (impostorBlock > 0) return blocked(subnet, KickMessages.impostorNetwork(impostorBlock));

        // 3. Under attack: only the FastPass players above, plus a probe now and then.
        if (UnderAttackMode.isActive(now) && !UnderAttackMode.allowProbe(now)) {
            return blocked(subnet, KickMessages.UNDER_ATTACK);
        }

        // 4. Bedrock players (Floodgate) have no Java account to look up.
        //    Floodgate itself verifies them; rate limits and steps 2-3 still applied.
        if (isBedrock(name, uuid)) return null;

        // 5. Well-formed name and UUID.
        if (!LoginFormat.isValid(name, uuid != null ? uuid.toString() : null)) {
            return blocked(subnet, KickMessages.BLOCKED);
        }

        // Offline-mode server: no account can be checked, only the name itself.
        if (!accountChecks) {
            if (scoreName(name, null) >= NameScorer.BLOCK_SCORE) return blocked(subnet, KickMessages.BLOCKED);
            return null;
        }

        // 6-7. The player database, when this server has an unlocked copy.
        if (PlayerDatabase.isAvailable() && uuid != null) {
            return verifyWithDatabase(name, uuid, subnet);
        }
        return verifyWithoutDatabase(name, uuid, subnet, fastPassEntry, now);
    }

    private Component verifyWithDatabase(String name, UUID uuid, String subnet) {
        if (PlayerDatabase.contains(uuid, name)) {
            FastPass.verify(name, subnet);
            return null;
        }

        // Missing from the database, or renamed. Mojang only issues version-4
        // UUIDs, and a 100+ name is blocked anyway — both free. Otherwise the
        // name's score decides how much of this minute's Mojang budget it may
        // use, so a bot flood can't use up what real newcomers need.
        if (uuid.version() != 4) return blocked(subnet, KickMessages.BLOCKED);
        int score = scoreName(name, uuid);
        if (score >= NameScorer.BLOCK_SCORE) return blocked(subnet, KickMessages.BLOCKED);
        if (!MojangBudget.tryTake(score, System.currentTimeMillis())) {
            return blocked(subnet, KickMessages.VERIFICATION_BUSY);
        }

        MojangApi.Profile profile = MojangApi.profile(uuid);
        switch (profile.status()) {
            case UNAVAILABLE -> { return KickMessages.VERIFICATION_BUSY; }
            case NOT_FOUND -> { return blocked(subnet, KickMessages.BLOCKED); }
            default -> { }
        }
        // Mojang's answer is the truth, whatever name the client claimed:
        // record it here and share it with every other server.
        PlayerDatabase.store(uuid, profile.name());
        DatabaseSync.report(uuid, profile.name());
        if (!name.equalsIgnoreCase(profile.name())) return blocked(subnet, KickMessages.BLOCKED);

        FastPass.verify(name, subnet);
        return null;
    }

    /**
     * Before the database is unlocked (first download, database server down)
     * or for pre-1.19 clients that send no UUID. Names that look suspicious
     * are checked with Mojang by name; the rest pass, as Minecraft's own login
     * still has to authenticate them.
     */
    private Component verifyWithoutDatabase(String name, UUID uuid, String subnet, String fastPassEntry, long now) {
        // Pre-approved by an admin (/ddosdefender fastpass add): confirm the name is real.
        if (FastPass.PENDING_KEY.equals(fastPassEntry)) {
            Component refusal = checkNameWithMojang(name, uuid, subnet, 0, now);
            if (refusal == null) FastPass.verify(name, subnet);
            return refusal;
        }
        // The same base name with a new number on the end, again: likely a bot run.
        if (repeatsRecentBaseName(name)) {
            Component refusal = checkNameWithMojang(name, uuid, subnet, 60, now);
            if (refusal != null) return refusal;
        }
        int score = scoreName(name, uuid);
        if (score >= NameScorer.BLOCK_SCORE) return blocked(subnet, KickMessages.BLOCKED);
        if (score >= 25) {
            Component refusal = checkNameWithMojang(name, uuid, subnet, score, now);
            if (refusal != null) return refusal;
            FastPass.verify(name, subnet);
        }
        return null;
    }

    // The name must be a real account and, if the client sent a UUID, that account's.
    private Component checkNameWithMojang(String name, UUID uuid, String subnet, int score, long now) {
        if (!MojangBudget.tryTake(score, now)) return blocked(subnet, KickMessages.VERIFICATION_BUSY);
        UUID real = MojangApi.uuidForName(name);
        if (real == null || (uuid != null && !uuid.equals(real))) return blocked(subnet, KickMessages.BLOCKED);
        return null;
    }

    private Component blocked(String subnet, Component message) {
        TrafficMonitor.recordBlocked(subnet);
        return message;
    }

    // Only when Floodgate is installed (an empty prefix otherwise, see the
    // platform plugins). Floodgate names carry its prefix; Floodgate UUIDs
    // are 00000000-0000-0000-xxxx (the Xbox ID).
    private boolean isBedrock(String name, UUID uuid) {
        if (bedrockPrefix.isEmpty()) return false;
        return name.startsWith(bedrockPrefix) || (uuid != null && uuid.getMostSignificantBits() == 0);
    }

    // Scores the name against the last minute's joins, then adds it to the window.
    private int scoreName(String name, UUID uuid) {
        long now = System.currentTimeMillis();
        List<String> names;
        List<Long> times;
        synchronized (windowLock) {
            while (!windowTimes.isEmpty() && windowTimes.peekFirst() < now - JOIN_WINDOW_MS) {
                windowTimes.pollFirst();
                windowNames.pollFirst();
            }
            names = new ArrayList<>(windowNames);
            times = new ArrayList<>(windowTimes);
            windowNames.addLast(name);
            windowTimes.addLast(now);
        }
        return NameScorer.score(name, uuid != null ? uuid.toString() : null, names, times);
    }

    private boolean repeatsRecentBaseName(String name) {
        int end = name.length();
        while (end > 0 && Character.isDigit(name.charAt(end - 1))) end--;
        if (end == name.length()) return false; // no trailing number
        String base = name.substring(0, end).toLowerCase();
        return recentBaseNames.put(base, System.currentTimeMillis()) != null;
    }

    // ── Bookkeeping ──────────────────────────────────────────────────────────

    private void forgetOld() {
        long now = System.currentTimeMillis();
        recentBaseNames.values().removeIf(at -> now - at > JOIN_WINDOW_MS);
    }

    private static String connectionId(InetSocketAddress address) {
        if (address == null || address.getAddress() == null) return null;
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    private static String ip(InetSocketAddress address) {
        if (address == null) return "unknown";
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }
}
