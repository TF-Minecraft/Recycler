package net.tfminecraft.recycler.ownership;

import org.bukkit.entity.Player;

/** A Paper save request precedes acknowledgment; it is not a transaction with the queue file. */
public final class InventoryCheckpoint {
    private InventoryCheckpoint() {}
    public static void save(Player player) { player.saveData(); }
}
