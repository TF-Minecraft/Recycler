package net.tfminecraft.recycler.provider;

import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.gunsandgadgets.guns.data.GunCraftInputs;
import net.tfminecraft.gunsandgadgets.utils.GunBrokenMarker;
import net.tfminecraft.gunsandgadgets.utils.GunStatRefresher;
import net.tfminecraft.recycler.Cache;
import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Returns the materials GunsAndGadgets recorded as taken when the gun was crafted.
 * Guns crafted before it recorded them, and broken guns, are not handled.
 */
public final class GunsAndGadgetsProvider implements RecycleProvider {

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public double returnRate() {
        return Cache.gunsReturnRate;
    }

    @Override
    public boolean canHandle(ItemStack item) {
        if (!GunStatRefresher.isManaged(item)) {
            return false;
        }
        if (GunBrokenMarker.isBroken(item)) {
            return false;
        }
        return GunCraftInputs.readFrom(item) != null;
    }

    @Override
    public List<RecycleOutput> resolveBaseOutputs(ItemStack item) {
        Map<String, Integer> used = GunCraftInputs.readFrom(item);
        if (used == null) {
            return List.of();
        }
        return RecycleOutput.fromAmounts(used);
    }
}
