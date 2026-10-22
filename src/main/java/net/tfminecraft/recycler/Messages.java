package net.tfminecraft.recycler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Player-facing strings from messages.yml. Keys missing from the server's copy fall back to the bundled file.
 */
public final class Messages {

    private static final Pattern HEX = Pattern.compile("&?#([0-9a-fA-F]{6})");

    private static FileConfiguration config;

    private Messages() {}

    public static void load(File file) {
        FileConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            Recycler.plugin.getLogger().severe("[Recycler] Failed to load messages.yml: " + ex.getMessage());
            loaded = new YamlConfiguration();
        }
        FileConfiguration bundled = bundled();
        if (bundled != null) {
            loaded.setDefaults(bundled);
        }
        config = loaded;
    }

    public static void loadFromResources() {
        FileConfiguration bundled = bundled();
        if (bundled != null) {
            config = bundled;
        }
    }

    /** The jar's messages.yml, or null. A stream that fails only to close still counts as read. */
    private static FileConfiguration bundled() {
        FileConfiguration bundled = null;
        try {
            InputStream resource = Recycler.plugin.getResource("messages.yml");
            if (resource == null) {
                return null;
            }
            try (InputStream in = resource) {
                FileConfiguration loaded = new YamlConfiguration();
                loaded.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                bundled = loaded;
            }
        } catch (Exception ex) {
            Recycler.plugin.getLogger().warning("[Recycler] Failed to load bundled messages.yml: " + ex.getMessage());
        }
        return bundled;
    }

    public static String get(String path) {
        if (config == null) {
            return path;
        }
        // getString(path, def) would skip the bundled defaults.
        String message = config.getString(path);
        return colorize(message != null ? message : path);
    }

    /** A message written as a list of lines, or as one line. Missing or [] gives no lines. */
    public static List<String> getList(String path) {
        List<String> lines = new ArrayList<>();
        if (config == null) {
            return lines;
        }
        if (config.isList(path)) {
            for (String line : config.getStringList(path)) {
                lines.add(colorize(line));
            }
        } else if (config.isString(path)) {
            lines.add(colorize(config.getString(path)));
        }
        return lines;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    // Hex colours may be written #RRGGBB or &#RRGGBB; the & goes with the colour.
    @SuppressWarnings("deprecation")
    public static String colorize(String text) {
        Matcher match = HEX.matcher(text);
        StringBuilder colored = new StringBuilder();
        while (match.find()) {
            match.appendReplacement(colored,
                    Matcher.quoteReplacement(net.md_5.bungee.api.ChatColor.of("#" + match.group(1)).toString()));
        }
        match.appendTail(colored);
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', colored.toString());
    }
}
