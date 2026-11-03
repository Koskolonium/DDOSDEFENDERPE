package uk.co.iudexindustries.ddosdefender.platform.bungee;

import uk.co.iudexindustries.ddosdefender.command.CommandContext;
import uk.co.iudexindustries.ddosdefender.command.AdminCommands;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

public class BungeeCommand extends Command implements TabExecutor {

    public BungeeCommand() {
        super("ddosdefender", null, "ii", "iudex", "iudexindustries");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        AdminCommands.handle(context(sender), args);
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        return AdminCommands.complete(context(sender), args);
    }

    private static CommandContext context(CommandSender sender) {
        return new CommandContext() {
            @Override
            public void sendMessage(String legacyText) {
                sender.sendMessage(TextComponent.fromLegacyText(legacyText));
            }

            @Override
            public boolean hasPermission(String node) {
                return sender.hasPermission(node);
            }
        };
    }
}
