package uk.co.iudexindustries.ddosdefender.core;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import uk.co.iudexindustries.ddosdefender.database.DatabaseSync;
import uk.co.iudexindustries.ddosdefender.database.PlayerDatabase;
import uk.co.iudexindustries.ddosdefender.fastpass.FastPass;
import uk.co.iudexindustries.ddosdefender.listener.HandshakeListener;
import uk.co.iudexindustries.ddosdefender.listener.LoginListener;
import uk.co.iudexindustries.ddosdefender.listener.SessionFailureListener;
import uk.co.iudexindustries.ddosdefender.mojang.MojangApi;
import uk.co.iudexindustries.ddosdefender.network.TrustedProxies;
import uk.co.iudexindustries.ddosdefender.protection.HandshakeValidator;
import uk.co.iudexindustries.ddosdefender.protection.TrafficMonitor;
import uk.co.iudexindustries.ddosdefender.protection.UnderAttackMode;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Starts and stops DDoSDefender the same way on every platform (Paper/Spigot,
 * BungeeCord/Waterfall, Velocity). Only plain Java and PacketEvents'
 * platform-neutral API are used here; each platform's plugin class just reads
 * its data folder and logger and hands them over.
 *
 * PacketEvents is its own plugin (a hard dependency), already loaded by the
 * time we start: we only register and unregister listeners on it.
 */
public final class DefenderCore {

    private final ExecutorService verifier;
    private final ScheduledExecutorService scheduler;
    private final List<PacketListenerCommon> listeners;

    private DefenderCore(ExecutorService verifier, ScheduledExecutorService scheduler,
                         List<PacketListenerCommon> listeners) {
        this.verifier = verifier;
        this.scheduler = scheduler;
        this.listeners = listeners;
    }

    public static DefenderCore start(File dataFolder, PluginConfig config, Logger logger,
                                     StaffNotifier staff) throws IOException {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("could not create " + dataFolder.getAbsolutePath());
        }
        HandshakeValidator.init(config.expectedPort());
        TrustedProxies.init(config.trustedProxies());
        UnderAttackMode.init(logger, staff);

        FastPass.load(dataFolder);
        // The player database is only any use where accounts can be checked.
        if (config.accountChecks()) PlayerDatabase.open(dataFolder, logger);

        // Verification can wait on Mojang for a few seconds, so it gets its own
        // bounded pool; a full queue turns the login away rather than piling up.
        int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService verifier = new ThreadPoolExecutor(cores, Math.max(cores + 2, cores * 2),
                60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000), daemonThreads("DDoSDefender-Verify"));
        // Timers only: queue draining, cleanups, database sync.
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, daemonThreads("DDoSDefender-Timer"));

        TrafficMonitor.init(logger, scheduler);
        if (config.accountChecks()) {
            DatabaseSync.init(config.databaseServer(), config.reportNewPlayers(), dataFolder, logger, scheduler);
        }
        scheduler.scheduleAtFixedRate(MojangApi::cleanup, 15, 15, TimeUnit.MINUTES);

        List<PacketListenerCommon> listeners = List.of(
                new HandshakeListener(scheduler, config.allowLocalHost()),
                new LoginListener(logger, scheduler, verifier, config),
                new SessionFailureListener(logger));
        for (PacketListenerCommon listener : listeners) {
            PacketEvents.getAPI().getEventManager().registerListener(listener);
        }
        logger.info("DDoSDefender is protecting this server (" + FastPass.getVerified().size()
                + " FastPass players" + (config.accountChecks() ? "" : "; offline mode: account checks off") + ")");
        return new DefenderCore(verifier, scheduler, listeners);
    }

    /**
     * Stops in dependency order: no new packets reach us, background work
     * stops, queued reports go out, then the files close. Safe on plugin
     * reloads — the listeners don't linger and double up.
     */
    public void shutdown() {
        try {
            PacketEvents.getAPI().getEventManager().unregisterListeners(
                    listeners.toArray(new PacketListenerCommon[0]));
        } catch (Exception ignored) {
            // PacketEvents may already be shut down on server stop.
        }
        scheduler.shutdownNow();
        verifier.shutdownNow();
        DatabaseSync.flush();
        FastPass.close();
        PlayerDatabase.close();
    }

    // Daemon threads never keep a stopping server alive.
    private static java.util.concurrent.ThreadFactory daemonThreads(String name) {
        AtomicInteger count = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
