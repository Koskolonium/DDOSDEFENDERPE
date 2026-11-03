package uk.co.iudexindustries.ddosdefender.platform.bukkit;

import uk.co.iudexindustries.ddosdefender.command.CommandContext;
import uk.co.iudexindustries.ddosdefender.command.AdminCommands;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;

public class BukkitCommand implements TabExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        AdminCommands.handle(context(sender), args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return AdminCommands.complete(context(sender), args);
    }

    private static CommandContext context(CommandSender sender) {
        return new CommandContext() {
            @Override
            public void sendMessage(String legacyText) {
                sender.sendMessage(legacyText);
            }

            @Override
            public boolean hasPermission(String node) {
                return sender.hasPermission(node);
            }
        };
    }
}
