package uk.co.iudexindustries.ddosdefender.platform.bukkit;

import org.bstats.bukkit.Metrics;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import uk.co.iudexindustries.ddosdefender.core.DefenderCore;
import uk.co.iudexindustries.ddosdefender.core.PluginConfig;
import uk.co.iudexindustries.ddosdefender.core.StaffNotifier;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/** Entry point on Paper, Spigot, Purpur and Folia (see plugin.yml). */
public final class BukkitPlugin extends JavaPlugin {

    private static final int BSTATS_ID = 32834;

    private DefenderCore core;

    @Override
    public void onEnable() {
        new Metrics(this, BSTATS_ID);
        try {
            PluginConfig config = PluginConfig.loadOrCreate(getDataFolder(), BukkitPlugin.class);
            if (getServer().getPluginManager().getPlugin("floodgate") == null) config = config.withoutBedrock();
            boolean behindProxy = warnIfBehindProxy(config);
            // Behind a proxy the server is offline-mode by design but the proxy
            // has already authenticated players, so account checks stay on.
            config = config.withAccountChecks(getServer().getOnlineMode() || behindProxy);
            core = DefenderCore.start(getDataFolder(), config, getLogger(), this::notifyStaff);
        } catch (Exception e) {
            getLogger().severe("Could not start: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        PluginCommand command = getCommand("ddosdefender");
        if (command != null) command.setExecutor(new BukkitCommand());
    }

    @Override
    public void onDisable() {
        if (core != null) core.shutdown();
        core = null;
    }

    // Alerts come from background threads. Folia's player methods are thread-safe;
    // elsewhere they're handed to the main thread.
    private void notifyStaff(String text) {
        Runnable send = () -> {
            for (Player player : getServer().getOnlinePlayers()) {
                if (player.hasPermission(StaffNotifier.PERMISSION)) player.sendMessage(text.split("\n"));
            }
        };
        try {
            if (FOLIA) send.run();
            else getServer().getScheduler().runTask(this, send);
        } catch (Exception ignored) {
            // Plugin disabling: nobody left to tell.
        }
    }

    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /*
     * Behind BungeeCord or Velocity every player connects from the proxy's
     * address, so per-network limits would throttle everyone together. The
     * plugin belongs on the proxy; if it's kept here, the proxy's IP must be a
     * trusted proxy.
     */
    private boolean warnIfBehindProxy(PluginConfig config) {
        boolean bungee = getServer().spigot().getConfig().getBoolean("settings.bungeecord", false);
        boolean velocity = paperVelocityForwarding();
        if ((bungee || velocity) && config.trustedProxies().isEmpty()) {
            getLogger().warning("This server is behind " + (velocity ? "Velocity" : "BungeeCord")
                    + ": install DDoSDefender on the proxy instead, or add the proxy's IP to "
                    + "trusted-proxies in config.yml — otherwise every player shares the proxy's limits.");
        }
        return bungee || velocity;
    }

    // Paper keeps Velocity forwarding in config/paper-global.yml (proxies.velocity.enabled).
    private boolean paperVelocityForwarding() {
        File file = new File("config", "paper-global.yml");
        if (!file.isFile()) return false;
        try {
            List<String> lines = Files.readAllLines(file.toPath());
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).trim().equals("velocity:")) continue;
                for (int j = i + 1; j < Math.min(i + 6, lines.size()); j++) {
                    String line = lines.get(j).trim();
                    if (line.startsWith("enabled:")) return line.endsWith("true");
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }
}
