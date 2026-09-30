package net.tfminecraft.recycler.util;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import io.lumine.mythic.lib.api.item.NBTItem;

/**
 * 0.0 at broken, 1.0 at full durability. Non-durable items return 1.0.
 * Uses MMOItems custom durability NBT when MMOItems is present, otherwise the item's damage
 * against its max_damage component (MMOItems max-item-damage) or the material's default.
 */
public final class DurabilityScaler {

    /** MMOItems custom durability: the maximum, and the remaining uses (absent until first damaged). */
    private static final String MMO_MAX_DURABILITY = "MMOITEMS_MAX_DURABILITY";
    private static final String MMO_DURABILITY = "MMOITEMS_DURABILITY";

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
            if (!nbt.hasTag(MMO_MAX_DURABILITY)) {
                return null;
            }
            double max = nbt.getDouble(MMO_MAX_DURABILITY);
            if (max <= 0) {
                // MMOItems ignores custom durability without a positive maximum.
                return null;
            }
            double current = nbt.hasTag(MMO_DURABILITY) ? nbt.getDouble(MMO_DURABILITY) : max;
            return Math.max(0.0, Math.min(1.0, current / max));
        } catch (Exception ex) {
            // Fall back to vanilla scaling
            return null;
        }
    }

    private static double vanillaFactor(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return 1.0;
        }
        int max = damageable.hasMaxDamage() ? damageable.getMaxDamage() : item.getType().getMaxDurability();
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
