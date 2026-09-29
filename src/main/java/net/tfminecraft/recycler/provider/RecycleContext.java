package net.tfminecraft.recycler.provider;

import org.bukkit.inventory.ItemStack;

/**
 * Inputs for scaling resolved outputs.
 */
public record RecycleContext(double returnRate, double durabilityFactor, int stackAmount) {

    public RecycleContext {
        returnRate = Math.max(0.0, returnRate);
        durabilityFactor = Math.max(0.0, Math.min(1.0, durabilityFactor));
        stackAmount = Math.max(1, stackAmount);
    }

    public static RecycleContext of(ItemStack item, double returnRate, double durabilityFactor) {
        int amount = item != null ? item.getAmount() : 1;
        return new RecycleContext(returnRate, durabilityFactor, amount);
    }

    public double combinedScale() {
        return returnRate * durabilityFactor;
    }
}
