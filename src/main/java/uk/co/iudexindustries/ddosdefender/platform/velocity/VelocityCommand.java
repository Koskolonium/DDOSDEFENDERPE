package uk.co.iudexindustries.ddosdefender.platform.velocity;

import uk.co.iudexindustries.ddosdefender.command.CommandContext;
import uk.co.iudexindustries.ddosdefender.command.AdminCommands;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;

public class VelocityCommand implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
        AdminCommands.handle(context(invocation), invocation.arguments());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return AdminCommands.complete(context(invocation), invocation.arguments());
    }

    private static CommandContext context(Invocation invocation) {
        return new CommandContext() {
            @Override
            public void sendMessage(String legacyText) {
                invocation.source().sendMessage(LegacyComponentSerializer.legacySection().deserialize(legacyText));
            }

            @Override
            public boolean hasPermission(String node) {
                return invocation.source().hasPermission(node);
            }
        };
    }
}
