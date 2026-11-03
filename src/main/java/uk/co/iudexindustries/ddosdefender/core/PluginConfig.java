package uk.co.iudexindustries.ddosdefender.core;

import uk.co.iudexindustries.ddosdefender.database.DatabaseSync;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * config.yml, read the same way on every platform.
 *
 * A deliberately small reader rather than a YAML library: BungeeCord and
 * Velocity don't put one on the plugin classpath, the file is flat, and using
 * one reader everywhere means a setting can't work on one platform and silently
 * fail on another. Understands top-level "key: value" lines and one list
 * ("key:" followed by "  - item" lines). Unknown keys are ignored, so old
 * config files (with the removed expected-host) still load.
 */
public record PluginConfig(
        int expectedPort,
        boolean allowLocalHost,
        List<String> trustedProxies,
        String bedrockPrefix,
        String databaseServer,
        boolean reportNewPlayers,
        boolean accountChecks) {

    /**
     * The same settings with Bedrock handling off. Used when Floodgate isn't
     * installed: otherwise anyone could put the prefix on a name to skip the
     * account checks.
     */
    public PluginConfig withoutBedrock() {
        return new PluginConfig(expectedPort, allowLocalHost, trustedProxies, "", databaseServer, reportNewPlayers,
                accountChecks);
    }

    /**
     * Account checks (player database, Mojang, UUID version) need an
     * online-mode server: on an offline-mode server names needn't be real
     * Mojang accounts and every UUID is a made-up version-3 one, so the
     * checks would turn nearly everyone away. Each platform passes its own
     * online-mode setting here.
     */
    public PluginConfig withAccountChecks(boolean onlineMode) {
        return new PluginConfig(expectedPort, allowLocalHost, trustedProxies, bedrockPrefix, databaseServer,
                reportNewPlayers, onlineMode);
    }

    /** Copies the bundled config.yml into the data folder if it's missing, then reads it. */
    public static PluginConfig loadOrCreate(File dataFolder, Class<?> resourceOwner) throws IOException {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("could not create " + dataFolder.getAbsolutePath());
        }
        File file = new File(dataFolder, "config.yml");
        if (!file.exists()) {
            try (InputStream in = resourceOwner.getClassLoader().getResourceAsStream("config.yml")) {
                if (in == null) throw new IOException("the bundled config.yml is missing from the jar");
                try (OutputStream out = Files.newOutputStream(file.toPath())) {
                    in.transferTo(out);
                }
            }
        }
        return parse(Files.readAllLines(file.toPath()));
    }

    static PluginConfig parse(List<String> lines) {
        int expectedPort = 0;
        boolean allowLocalHost = true;
        List<String> trustedProxies = new ArrayList<>();
        String bedrockPrefix = ".";
        String databaseServer = DatabaseSync.DEFAULT_SERVER;
        boolean reportNewPlayers = true;

        String currentList = null;
        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.isBlank()) continue;

            String trimmed = line.trim();
            if (trimmed.startsWith("-")) {
                if ("trusted-proxies".equals(currentList)) {
                    String item = unquote(trimmed.substring(1).trim());
                    if (!item.isEmpty()) trustedProxies.add(item);
                }
                continue;
            }

            currentList = null;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String value = unquote(line.substring(colon + 1).trim());

            switch (key) {
                case "expected-port" -> {
                    try { expectedPort = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
                }
                case "allow-local-host" -> allowLocalHost = !value.equalsIgnoreCase("false");
                case "trusted-proxies" -> {
                    currentList = key;
                    // Also accept the one-line form: trusted-proxies: [1.2.3.4, 5.6.7.8]
                    if (value.startsWith("[") && value.endsWith("]")) {
                        for (String item : value.substring(1, value.length() - 1).split(",")) {
                            if (!unquote(item.trim()).isEmpty()) trustedProxies.add(unquote(item.trim()));
                        }
                    }
                }
                case "bedrock-prefix" -> bedrockPrefix = value;
                case "database-server" -> databaseServer = value.isBlank() ? DatabaseSync.DEFAULT_SERVER : value;
                case "report-new-players" -> reportNewPlayers = !value.equalsIgnoreCase("false");
                default -> { }
            }
        }
        return new PluginConfig(expectedPort, allowLocalHost, List.copyOf(trustedProxies),
                bedrockPrefix, databaseServer, reportNewPlayers, true);
    }

    // A '#' inside quotes is part of the value, not a comment.
    private static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == '#' && !quoted) return line.substring(0, i);
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
