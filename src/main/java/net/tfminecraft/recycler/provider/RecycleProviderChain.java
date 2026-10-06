package net.tfminecraft.recycler.provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.recycler.util.DurabilityScaler;

/**
 * Ordered provider chain: AdvancedCrafting, alloy scrap, Magic gear, Magic artifacts, GunsAndGadgets,
 * goldsmithing, then config fallback.
 */
public final class RecycleProviderChain {

    private final List<RecycleProvider> providers = new ArrayList<>();

    public void rebuild() {
        providers.clear();
        if (Bukkit.getPluginManager().isPluginEnabled("AdvancedCrafting")) {
            providers.add(new AdvancedCraftingProvider());
            providers.add(new AlloyScrapProvider());
        }
        if (Bukkit.getPluginManager().isPluginEnabled("Magic")) {
            providers.add(new MagicGearProvider());
            providers.add(new ArtifactProvider());
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
            RecycleContext ctx = RecycleContext.of(item, provider.returnRate(), DurabilityScaler.factor(item));
            return RecycleResult.of(providerName(provider), base, ctx);
        }
        return RecycleResult.notHandled();
    }

    private static String providerName(RecycleProvider provider) {
        return provider.getClass().getSimpleName();
    }
}
