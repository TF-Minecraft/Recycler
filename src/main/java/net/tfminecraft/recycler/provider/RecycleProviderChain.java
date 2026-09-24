package net.tfminecraft.recycler.provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.recycler.Cache;
import net.tfminecraft.recycler.util.DurabilityScaler;

/**
 * Ordered provider chain: AdvancedCrafting, Magic, GunsAndGadgets, goldsmithing, then config fallback.
 */
public final class RecycleProviderChain {

    private final List<RecycleProvider> providers = new ArrayList<>();

    public void rebuild() {
        providers.clear();
        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedCrafting")) {
            providers.add(new AdvancedCraftingProvider());
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Magic")) {
            providers.add(new MagicGearProvider());
        }
        if (Bukkit.getPluginManager().isPluginEnabled("GunsAndGadgets")) {
            providers.add(new GunsAndGadgetsProvider());
        }
        if (Bukkit.getPluginManager().isPluginEnabled("GemInfusion")) {
            providers.add(new GoldsmithProvider());
        }
        providers.add(new ConfigProvider());
        providers.sort(Comparator.comparingInt(RecycleProvider::priority));
    }

    public RecycleResult resolve(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return RecycleResult.notHandled();
        }
        for (RecycleProvider provider : providers) {
            if (!provider.canHandle(item)) {
                continue;
            }
            List<net.tfminecraft.recycler.model.RecycleOutput> base = provider.resolveBaseOutputs(item);
            if (base.isEmpty()) {
                continue;
            }
            double returnRate = provider.appliesMaxReturnRate() ? Cache.maxReturnRate : 1.0;
            RecycleContext ctx = RecycleContext.of(item, returnRate, DurabilityScaler.factor(item));
            return RecycleResult.of(providerName(provider), base, ctx);
        }
        return RecycleResult.notHandled();
    }

    private static String providerName(RecycleProvider provider) {
        return provider.getClass().getSimpleName();
    }
}
