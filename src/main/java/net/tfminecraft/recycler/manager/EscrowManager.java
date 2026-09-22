package net.tfminecraft.recycler.manager;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import net.tfminecraft.recycler.Messages;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.util.ItemGive;

/**
 * Crash-safe escrow for items removed from the player while the GUI is open.
 * Persists to data/escrow/ and data/pending_returns/.
 */
public final class EscrowManager {

    private static final Gson GSON = new GsonBuilder().create();

    private final Map<UUID, ItemStack> memory = new ConcurrentHashMap<>();
    private File escrowFolder;
    private File pendingFolder;

    public void loadPending() {
        escrowFolder = new File(Recycler.plugin.getDataFolder(), "data/escrow");
        pendingFolder = new File(Recycler.plugin.getDataFolder(), "data/pending_returns");
        escrowFolder.mkdirs();
        pendingFolder.mkdirs();
        moveAllEscrowFilesToPending();
    }

    public boolean hasEscrow(UUID playerId) {
        return memory.containsKey(playerId) || escrowFile(playerId).exists();
    }

    public ItemStack getEscrow(UUID playerId) {
        ItemStack cached = memory.get(playerId);
        if (cached != null) {
            return cached.clone();
        }
        return loadEscrowFile(playerId);
    }

    public void putEscrow(UUID playerId, ItemStack item) {
        if (playerId == null || item == null || item.getType().isAir()) {
            return;
        }
        ItemStack copy = item.clone();
        memory.put(playerId, copy);
        saveEscrowFile(playerId, copy);
    }

    public void clearEscrow(UUID playerId) {
        memory.remove(playerId);
        deleteFile(escrowFile(playerId));
    }

    public void returnEscrow(Player player) {
        giveBackEscrow(player, true);
    }

    public void giveBackEscrow(Player player, boolean notify) {
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        ItemStack item = memory.remove(id);
        if (item == null) {
            item = loadEscrowFile(id);
        }
        deleteFile(escrowFile(id));
        if (item == null || item.getType().isAir()) {
            return;
        }
        ItemGive.giveOrDrop(player, item);
        if (notify) {
            player.sendMessage(Messages.get("escrow.returned"));
        }
    }

    public void flushAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hasEscrow(player.getUniqueId())) {
                returnEscrow(player);
            }
        }
    }

    public void savePending() {
        memory.clear();
        moveAllEscrowFilesToPending();
    }

    public boolean hasPending(UUID playerId) {
        return pendingFile(playerId).exists();
    }

    public List<UUID> listMemoryEscrowPlayerIds() {
        return new ArrayList<>(memory.keySet());
    }

    public int countMemoryEscrow() {
        return memory.size();
    }

    public List<UUID> listPendingPlayerIds() {
        if (pendingFolder == null || !pendingFolder.isDirectory()) {
            return List.of();
        }
        File[] files = pendingFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".json")) {
                continue;
            }
            try {
                ids.add(UUID.fromString(name.substring(0, name.length() - 5)));
            } catch (IllegalArgumentException ex) {
                Recycler.plugin.getLogger().warning("[Recycler] Ignoring invalid pending file name: " + name);
            }
        }
        return ids;
    }

    public int countPendingFiles() {
        return listPendingPlayerIds().size();
    }

    public void adminReturn(Player player) {
        if (player == null) {
            return;
        }
        giveBackEscrow(player, true);
        deliverPending(player);
    }

    public void deliverPending(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            deliverPending(player);
        }
    }

    public void deliverPending(Player player) {
        if (player == null) {
            return;
        }
        File file = pendingFile(player.getUniqueId());
        if (!file.exists()) {
            return;
        }
        ItemStack item = loadItemStackFile(file);
        deleteFile(file);
        if (item == null || item.getType().isAir()) {
            return;
        }
        ItemGive.giveOrDrop(player, item);
        player.sendMessage(Messages.get("escrow.returned"));
    }

    private void moveAllEscrowFilesToPending() {
        if (escrowFolder == null || !escrowFolder.isDirectory()) {
            return;
        }
        File[] files = escrowFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File source : files) {
            moveFileToPending(source);
        }
    }

    private void moveFileToPending(File source) {
        if (source == null || !source.isFile()) {
            return;
        }
        File dest = new File(pendingFolder, source.getName());
        if (dest.exists()) {
            deleteFile(dest);
        }
        if (source.renameTo(dest)) {
            return;
        }
        try {
            Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            deleteFile(source);
        } catch (Exception ex) {
            Recycler.plugin.getLogger().warning("[Recycler] Failed to move escrow file " + source.getName()
                    + " to pending_returns: " + ex.getMessage());
        }
    }

    private File escrowFile(UUID playerId) {
        return new File(escrowFolder, playerId.toString() + ".json");
    }

    private File pendingFile(UUID playerId) {
        return new File(pendingFolder, playerId.toString() + ".json");
    }

    private void saveEscrowFile(UUID playerId, ItemStack item) {
        File file = escrowFile(playerId);
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            writer.write(GSON.toJson(item.serialize()));
        } catch (Exception ex) {
            Recycler.plugin.getLogger().warning("[Recycler] Failed to save escrow for " + playerId + ": "
                    + ex.getMessage());
        }
    }

    private ItemStack loadEscrowFile(UUID playerId) {
        return loadItemStackFile(escrowFile(playerId));
    }

    private ItemStack loadItemStackFile(File file) {
        if (!file.exists()) {
            return null;
        }
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            Map<String, Object> map = GSON.fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
            if (map == null) {
                return null;
            }
            return ItemStack.deserialize(map);
        } catch (Exception ex) {
            Recycler.plugin.getLogger().warning("[Recycler] Failed to load item file " + file.getName() + ": "
                    + ex.getMessage());
            return null;
        }
    }

    private static void deleteFile(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }
}
