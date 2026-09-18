package net.tfminecraft.recycler.util;

import java.util.Map;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Give items to inventory with overflow drop at player feet.
 */
public final class ItemGive {

    private ItemGive() {}

    public static void giveOrDrop(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir()) {
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack drop : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }
}
