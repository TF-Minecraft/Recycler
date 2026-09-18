package net.tfminecraft.recycler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.recycler.command.CommandManager;
import net.tfminecraft.recycler.loader.ConfigLoader;
import net.tfminecraft.recycler.loader.GuiLoader;
import net.tfminecraft.recycler.loader.RecipeLoader;
import net.tfminecraft.recycler.manager.EscrowManager;
import net.tfminecraft.recycler.manager.InventoryManager;
import net.tfminecraft.recycler.manager.RecyclerManager;
import net.tfminecraft.recycler.provider.RecycleProviderChain;

/**
 * Station-based item recycling. See docs/ for design and implementation order.
 */
public class Recycler extends JavaPlugin {

    public static Recycler plugin;

    private final ConfigLoader configLoader = new ConfigLoader();
    private final GuiLoader guiLoader = new GuiLoader();
    private final RecipeLoader recipeLoader = new RecipeLoader();
    private final CommandManager commandManager = new CommandManager();
    private final EscrowManager escrowManager = new EscrowManager();
    private final RecycleProviderChain providerChain = new RecycleProviderChain();
    private final InventoryManager inventoryManager = new InventoryManager(providerChain);
    private final RecyclerManager recyclerManager = new RecyclerManager(escrowManager, inventoryManager, providerChain);

    @Override
    public void onEnable() {
        plugin = this;
        createFolders();
        createConfigs();
        if (!loadConfigs()) {
            getLogger().warning("Recycler loaded with config errors.");
        }
        var cmd = getCommand("recycler");
        if (cmd != null) {
            cmd.setExecutor(commandManager);
            cmd.setTabCompleter(commandManager);
        } else {
            getLogger().severe("Command 'recycler' missing from plugin.yml");
        }
        getServer().getPluginManager().registerEvents(recyclerManager, this);
        escrowManager.loadPending();
        getLogger().info("Recycler enabled.");
    }

    @Override
    public void onDisable() {
        escrowManager.flushAllOnline();
        escrowManager.savePending();
        getLogger().info("Recycler disabled.");
    }

    public boolean reloadAll() {
        return loadConfigs();
    }

    public EscrowManager getEscrowManager() {
        return escrowManager;
    }

    public InventoryManager getInventoryManager() {
        return inventoryManager;
    }

    public RecyclerManager getRecyclerManager() {
        return recyclerManager;
    }

    public RecycleProviderChain getProviderChain() {
        return providerChain;
    }

    private boolean loadConfigs() {
        boolean ok = true;
        ok &= configLoader.loadSafe(new File(getDataFolder(), "config.yml"));
        ok &= guiLoader.loadSafe(new File(getDataFolder(), "gui.yml"));
        ok &= recipeLoader.loadFolder(new File(getDataFolder(), "recipes"));
        providerChain.rebuild();
        if (ok) {
            getLogger().info("[Recycler] Loaded " + RecipeLoader.size() + " config recipe(s).");
        }
        return ok;
    }

    private void createFolders() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }
        mkdir("data");
        mkdir("data/escrow");
        mkdir("data/pending_returns");
        mkdir("recipes");
    }

    private void mkdir(String relativePath) {
        File folder = new File(getDataFolder(), relativePath);
        if (!folder.exists()) {
            folder.mkdirs();
        }
    }

    private void createConfigs() {
        String[] defaultFiles = {
            "config.yml",
            "messages.yml",
            "gui.yml",
            "recipes/example.yml"
        };
        for (String path : defaultFiles) {
            copyResourceIfMissing(path);
        }
    }

    private void copyResourceIfMissing(String relativePath) {
        File target = new File(getDataFolder(), relativePath);
        if (target.exists()) {
            return;
        }
        target.getParentFile().mkdirs();
        try (InputStream in = getResource(relativePath)) {
            if (in == null) {
                getLogger().warning("Missing bundled resource: " + relativePath);
                return;
            }
            Files.copy(in, target.toPath());
        } catch (IOException ex) {
            getLogger().severe("Failed to copy default resource " + relativePath + ": " + ex.getMessage());
        }
    }
}
