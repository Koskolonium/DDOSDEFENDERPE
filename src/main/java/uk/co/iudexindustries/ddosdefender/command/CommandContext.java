package uk.co.iudexindustries.ddosdefender.command;

/**
 * Minimal per-platform adapter for the /ii command so the actual subcommand
 * logic (AdminCommands) is written once and shared by Bukkit, BungeeCord and
 * Velocity's unrelated command-sender types.
 */
public interface CommandContext {
    void sendMessage(String legacyText);
    boolean hasPermission(String node);
}
