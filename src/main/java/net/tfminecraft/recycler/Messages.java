package net.tfminecraft.recycler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Player-facing strings from messages.yml.
 */
public final class Messages {

    private static FileConfiguration config;

    private Messages() {}

    public static void load(File file) {
        FileConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
            config = loaded;
        } catch (IOException | InvalidConfigurationException ex) {
            Recycler.plugin.getLogger().severe("[Recycler] Failed to load messages.yml: " + ex.getMessage());
            config = new YamlConfiguration();
        }
    }

    public static void loadFromResources() {
        try (InputStream in = Recycler.plugin.getResource("messages.yml")) {
            if (in == null) {
                return;
            }
            FileConfiguration loaded = new YamlConfiguration();
            loaded.loadFromString(new String(in.readAllBytes()));
            config = loaded;
        } catch (Exception ex) {
            Recycler.plugin.getLogger().warning("[Recycler] Failed to load bundled messages.yml: " + ex.getMessage());
        }
    }

    public static String get(String path) {
        if (config == null) {
            return path;
        }
        return config.getString(path, path);
    }
}
