package net.tfminecraft.recycler.loader;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.recycler.GuiCache;
import net.tfminecraft.recycler.Recycler;

public final class GuiLoader implements LoaderInterface {

    @Override
    public void load(File configFile) {
        loadSafe(configFile);
    }

    public boolean loadSafe(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException ex) {
            Recycler.plugin.getLogger().severe("[Recycler] Failed to load gui.yml: " + ex.getMessage());
            return false;
        }

        GuiCache.confirmButton = config.getString("items.confirm_button", GuiCache.confirmButton);
        GuiCache.filler = config.getString("items.filler", GuiCache.filler);
        GuiCache.arrowRight = config.getString("items.arrow_right", GuiCache.arrowRight);
        return true;
    }
}
