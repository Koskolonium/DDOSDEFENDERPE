package uk.co.iudexindustries.ddosdefender.core;

/**
 * Sends an alert to every online player allowed to see them (operators by
 * default on Paper/Spigot; on BungeeCord and Velocity, whoever is given the
 * permission). Text uses § colour codes; each platform converts it.
 * Must be safe to call from any thread.
 */
@FunctionalInterface
public interface StaffNotifier {

    String PERMISSION = "iudex.alerts";

    StaffNotifier NONE = text -> { };

    void send(String legacyText);
}
