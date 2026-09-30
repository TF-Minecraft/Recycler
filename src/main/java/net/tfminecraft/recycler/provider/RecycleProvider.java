package net.tfminecraft.recycler.provider;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.recycler.model.RecycleOutput;

import java.util.List;

/**
 * Plugin-specific or config-based recycle resolution.
 */
public interface RecycleProvider {

    /**
     * Lower values run first. Config fallback should use {@link Integer#MAX_VALUE}.
     */
    int priority();

    boolean canHandle(ItemStack item);

    /**
     * Base outputs before global return rate and durability scaling.
     */
    List<RecycleOutput> resolveBaseOutputs(ItemStack item);

    /**
     * This provider's {@code return_rates} entry, applied to base outputs before durability scaling.
     */
    double returnRate();
}
