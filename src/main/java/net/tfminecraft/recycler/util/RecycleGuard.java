package net.tfminecraft.recycler.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.recycler.Cache;

/**
 * Deposit policy: whitelist/blacklist paths and unbreakable items.
 */
public final class RecycleGuard {

    private RecycleGuard() {}

    public static boolean isBlocked(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return true;
        }
        if (Cache.depositBlockUnbreakable && isUnbreakable(item)) {
            return true;
        }
        String path = TLibs.getItemAPI().getChecker().getAsStringPath(item);
        if (path == null || path.isBlank()) {
            return Cache.depositWhitelistMode;
        }
        if (Cache.depositWhitelistMode) {
            return !matchesAny(path, Cache.depositWhitelistPaths);
        }
        return matchesAny(path, Cache.depositBlacklistPaths);
    }

    private static boolean isUnbreakable(ItemStack item) {
        if (!item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.isUnbreakable();
    }

    private static boolean matchesAny(String path, java.util.List<String> rules) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }
        for (String rule : rules) {
            if (pathMatches(path, rule)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pathMatches(String path, String rule) {
        if (rule == null || rule.isBlank()) {
            return false;
        }
        String trimmed = rule.trim();
        if (trimmed.endsWith("*")) {
            String prefix = trimmed.substring(0, trimmed.length() - 1);
            return path.regionMatches(true, 0, prefix, 0, prefix.length());
        }
        return path.equalsIgnoreCase(trimmed);
    }
}
