package uk.co.iudexindustries.ddosdefender.platform.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import uk.co.iudexindustries.ddosdefender.core.DefenderCore;
import uk.co.iudexindustries.ddosdefender.core.PluginConfig;
import uk.co.iudexindustries.ddosdefender.core.StaffNotifier;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Entry point on Velocity. The annotation below becomes velocity-plugin.json
 * at build time (Velocity's annotation processor runs in the pom).
 */
@Plugin(
        id = "ddosdefender",
        name = "DDoSDefender",
        version = "Release V2.0",
        description = "Stops bot attacks and fake accounts before they reach your server.",
        url = "https://iudexindustries.co.uk",
        authors = {"Koskolonium"},
        dependencies = {@Dependency(id = "packetevents")}
)
public final class VelocityPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final File dataFolder;
    private DefenderCore core;

    @Inject
    public VelocityPlugin(ProxyServer proxy, org.slf4j.Logger slf4j, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = forwardTo(slf4j);
        this.dataFolder = dataDirectory.toFile();
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            PluginConfig config = PluginConfig.loadOrCreate(dataFolder, VelocityPlugin.class);
            if (!proxy.getPluginManager().isLoaded("floodgate")) config = config.withoutBedrock();
            config = config.withAccountChecks(proxy.getConfiguration().isOnlineMode());
            core = DefenderCore.start(dataFolder, config, logger, this::notifyStaff);
        } catch (Exception e) {
            logger.severe("Could not start: " + e.getMessage());
            return;
        }
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("ddosdefender").aliases("ii", "iudex", "iudexindustries").build(),
                new VelocityCommand());
    }

    // Velocity has no operators: alerts go to whoever has the permission.
    private void notifyStaff(String text) {
        var message = LegacyComponentSerializer.legacySection().deserialize(text);
        for (Player player : proxy.getAllPlayers()) {
            if (player.hasPermission(StaffNotifier.PERMISSION)) player.sendMessage(message);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (core != null) core.shutdown();
        core = null;
    }

    // The shared code logs through java.util.logging; Velocity's console only
    // shows SLF4J, so every record (and any exception with it) is passed on.
    // A Logger of our own rather than a Handler: Velocity 4 bridges JUL into
    // Log4j and ignores addHandler, which would lose our plugin prefix.
    private static Logger forwardTo(org.slf4j.Logger slf4j) {
        Logger logger = new Logger("DDoSDefender", null) {
            @Override
            public void log(LogRecord record) {
                String message = record.getMessage();
                Throwable thrown = record.getThrown();
                int level = record.getLevel().intValue();
                if (level >= Level.SEVERE.intValue()) slf4j.error(message, thrown);
                else if (level >= Level.WARNING.intValue()) slf4j.warn(message, thrown);
                else slf4j.info(message, thrown);
            }
        };
        logger.setLevel(Level.INFO);
        return logger;
    }
}
