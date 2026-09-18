package net.tfminecraft.recycler.util;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import io.lumine.mythic.lib.api.item.NBTItem;

/**
 * 0.0 at broken, 1.0 at full durability. Non-durable items return 1.0.
 * Uses MMOItems custom durability NBT when MMOItems is present.
 */
public final class DurabilityScaler {

    private DurabilityScaler() {}

    public static double factor(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 1.0;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("MMOItems")) {
            Double mmoFactor = mmoFactor(item);
            if (mmoFactor != null) {
                return mmoFactor;
            }
        }
        return vanillaFactor(item);
    }

    private static Double mmoFactor(ItemStack item) {
        try {
            NBTItem nbt = NBTItem.get(item);
            if (nbt.hasTag("MMOITEMS_CUSTOM_DURABILITY") && nbt.hasTag("MMOITEMS_MAX_DURABILITY")) {
                double current = nbt.getDouble("MMOITEMS_CUSTOM_DURABILITY");
                double max = nbt.getDouble("MMOITEMS_MAX_DURABILITY");
                if (max <= 0) {
                    return 1.0;
                }
                if (current <= 0) {
                    return 0.0;
                }
                return Math.max(0.0, Math.min(1.0, current / max));
            }
        } catch (Exception ex) {
            // Fall back to vanilla scaling
        }
        return null;
    }

    private static double vanillaFactor(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return 1.0;
        }
        int max = item.getType().getMaxDurability();
        if (max <= 0) {
            return 1.0;
        }
        int damage = damageable.getDamage();
        if (damage >= max) {
            return 0.0;
        }
        return 1.0 - (double) damage / max;
    }
}
