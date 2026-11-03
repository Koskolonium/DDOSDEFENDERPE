package uk.co.iudexindustries.ddosdefender.platform.bungee;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import uk.co.iudexindustries.ddosdefender.core.DefenderCore;
import uk.co.iudexindustries.ddosdefender.core.PluginConfig;
import uk.co.iudexindustries.ddosdefender.core.StaffNotifier;

/** Entry point on BungeeCord and Waterfall (see bungee.yml). */
public final class BungeePlugin extends Plugin {

    private DefenderCore core;

    @Override
    public void onEnable() {
        try {
            PluginConfig config = PluginConfig.loadOrCreate(getDataFolder(), BungeePlugin.class);
            if (getProxy().getPluginManager().getPlugin("floodgate") == null) config = config.withoutBedrock();
            config = config.withAccountChecks(getProxy().getConfig().isOnlineMode());
            core = DefenderCore.start(getDataFolder(), config, getLogger(), this::notifyStaff);
        } catch (Exception e) {
            getLogger().severe("Could not start: " + e.getMessage());
            return;
        }
        getProxy().getPluginManager().registerCommand(this, new BungeeCommand());
    }

    // BungeeCord has no operators: alerts go to whoever has the permission
    // (permissions: in BungeeCord's config.yml, or a permissions plugin).
    private void notifyStaff(String text) {
        for (ProxiedPlayer player : getProxy().getPlayers()) {
            if (player.hasPermission(StaffNotifier.PERMISSION)) player.sendMessage(TextComponent.fromLegacyText(text));
        }
    }

    @Override
    public void onDisable() {
        if (core != null) core.shutdown();
        core = null;
    }
}
