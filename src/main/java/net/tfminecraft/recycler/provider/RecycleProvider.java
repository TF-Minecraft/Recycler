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
     * Whether {@link net.tfminecraft.recycler.Cache#maxReturnRate} applies to this provider.
     * Config recipes use yaml amounts as-is (still scaled by durability and stack size).
     */
    default boolean appliesMaxReturnRate() {
        return true;
    }
}
