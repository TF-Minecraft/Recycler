package net.tfminecraft.recycler.util;

import java.util.Collections;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import me.Plugins.TLibs.TLibs;

/**
 * Yaml refs use vanilla.MATERIAL, m.TYPE.ID, or ia.namespace:id.
 * TLibs paths use v.MATERIAL for vanilla items.
 */
public final class ItemRef {

    private ItemRef() {}

    public static String normalize(String ref) {
        if (ref == null || ref.isBlank()) {
            return "";
        }
        String trimmed = ref.trim();
        if (trimmed.toLowerCase().startsWith("vanilla.")) {
            return "v." + trimmed.substring("vanilla.".length()).toLowerCase();
        }
        return trimmed;
    }

    public static ItemStack build(String ref) {
        String normalized = normalize(ref);
        if (normalized.isBlank()) {
            return null;
        }
        try {
            ItemStack item = TLibs.getItemAPI().getCreator().getItemFromPath(normalized);
            if (item == null || item.getType() == Material.AIR) {
                return null;
            }
            return item.clone();
        } catch (Exception ex) {
            return null;
        }
    }

    /** Gray glass and other GUI fillers: no visible name or lore. */
    public static void applyBlankDisplay(ItemStack item) {
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setDisplayName("");
        meta.setLore(Collections.emptyList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
    }
}
