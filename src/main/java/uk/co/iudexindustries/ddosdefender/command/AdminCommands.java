package uk.co.iudexindustries.ddosdefender.command;

import uk.co.iudexindustries.ddosdefender.checks.NameScorer;
import uk.co.iudexindustries.ddosdefender.fastpass.FastPass;
import uk.co.iudexindustries.ddosdefender.protection.LoginFormat;
import uk.co.iudexindustries.ddosdefender.protection.UnderAttackMode;
import uk.co.iudexindustries.ddosdefender.mojang.MojangBudget;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /ddosdefender (aliases /ii, /iudex, /iudexindustries), written once for all
 * platforms through CommandContext:
 *   fastpass add <player>     pre-approve a player (confirmed with Mojang on their next join)
 *   test <player>             how a name scores against the bot checks
 *   lockdown on|off|status    under-attack mode by hand
 */
public final class AdminCommands {

    private AdminCommands() {}

    public static void handle(CommandContext ctx, String[] args) {
        if (args.length == 0) {
            ctx.sendMessage("§cUsage: /ddosdefender fastpass add <player>  |  test <player>  |  lockdown on|off|status");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "fastpass" -> handleFastPass(ctx, args);
            case "test"     -> handleTest(ctx, args);
            case "lockdown" -> handleLockdown(ctx, args);
            default         -> ctx.sendMessage("§cUnknown subcommand. Usage: /ddosdefender fastpass add <player>  |  test <player>  |  lockdown on|off|status");
        }
    }

    private static void handleFastPass(CommandContext ctx, String[] args) {
        if (!ctx.hasPermission("iudex.fastpass")) {
            ctx.sendMessage("§cYou do not have permission to use this command.");
            return;
        }
        if (args.length != 3 || !args[1].equalsIgnoreCase("add")) {
            ctx.sendMessage("§cUsage: /ddosdefender fastpass add <player>");
            return;
        }
        if (!LoginFormat.isValid(args[2], null)) {
            ctx.sendMessage("§c\"" + args[2] + "\" isn't a valid Minecraft username.");
            return;
        }
        FastPass.prePass(args[2]);
        ctx.sendMessage("§a" + args[2] + " has been added to FastPass.");
    }

    private static void handleLockdown(CommandContext ctx, String[] args) {
        if (!ctx.hasPermission("iudex.lockdown")) {
            ctx.sendMessage("§cYou do not have permission to use this command.");
            return;
        }
        long now = System.currentTimeMillis();
        String action = args.length == 2 ? args[1].toLowerCase() : "";
        switch (action) {
            case "on" -> {
                UnderAttackMode.setManual(true, now);
                ctx.sendMessage("§eEnhanced protection §cON§e — only frequent players (FastPass) can join until §f/ddosdefender lockdown off§e.");
            }
            case "off" -> {
                UnderAttackMode.setManual(false, now);
                ctx.sendMessage("§eEnhanced protection §aOFF§e — everyone can join again.");
            }
            case "status" -> ctx.sendMessage("§7Under-attack mode: §f" + UnderAttackMode.status(now)
                    + "§7  |  Mojang lookups this minute: §f" + MojangBudget.used() + "/" + MojangBudget.LIMIT_PER_MINUTE);
            default -> ctx.sendMessage("§cUsage: /ddosdefender lockdown on|off|status");
        }
    }

    /**
     * Tab-completion for the command: the choices for the argument currently
     * being typed (the last element of {@code args}), limited to what this
     * sender has permission to run.
     */
    public static List<String> complete(CommandContext ctx, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            if (ctx.hasPermission("iudex.fastpass")) options.add("fastpass");
            if (ctx.hasPermission("iudex.test"))     options.add("test");
            if (ctx.hasPermission("iudex.lockdown")) options.add("lockdown");
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "fastpass" -> {
                    if (ctx.hasPermission("iudex.fastpass")) options.add("add");
                }
                case "lockdown" -> {
                    if (ctx.hasPermission("iudex.lockdown")) options.addAll(List.of("on", "off", "status"));
                }
                default -> {}
            }
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        options.removeIf(option -> !option.startsWith(typed));
        return options;
    }

    private static void handleTest(CommandContext ctx, String[] args) {
        if (!ctx.hasPermission("iudex.test")) {
            ctx.sendMessage("§cYou do not have permission to use this command.");
            return;
        }
        if (args.length != 2) {
            ctx.sendMessage("§cUsage: /ddosdefender test <player>");
            return;
        }

        String name = args[1];
        Map<String, Integer> scores = NameScorer.breakdown(name);
        int total = scores.values().stream().mapToInt(Integer::intValue).sum();

        ctx.sendMessage("§7§m        §r §b§l[DDoSDefender] §fName Test §7§m        §r");
        ctx.sendMessage("§7Name: §e" + name);
        boolean any = false;
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() == 0) continue;
            any = true;
            ctx.sendMessage("§7  " + entry.getKey() + ": " + (entry.getValue() < 20 ? "§e" : "§c") + entry.getValue());
        }
        if (!any) ctx.sendMessage("§7  No check found anything unusual.");

        String verdict = total < 25 ? "§alooks human §7(first claim on Mojang checks)"
                : total < 60 ? "§esuspicious §7(lower priority for Mojang checks during floods)"
                : total < NameScorer.BLOCK_SCORE ? "§6very suspicious §7(lowest priority for Mojang checks)"
                : "§cblocked §7(no Mojang check)";
        ctx.sendMessage("§7Score: §f" + total + " §7— " + verdict);
        ctx.sendMessage("§8§oOnly applies to players not in FastPass or the player database. "
                + "Swarm checks need live joins and aren't included.");
    }
}
