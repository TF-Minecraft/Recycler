package net.tfminecraft.recycler.provider;

import java.util.List;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.advancedcrafting.loaders.IngredientLoader;
import net.tfminecraft.advancedcrafting.objects.data.ScrapProvenance;
import net.tfminecraft.advancedcrafting.objects.ingredients.Ingredient;
import net.tfminecraft.recycler.Cache;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Returns the base metal of the failed alloy forge that produced a piece of AdvancedCrafting scrap.
 * Scrap forged before AdvancedCrafting tagged it has no base and is not handled.
 * Requires AdvancedCrafting on the server (registered only when plugin is present).
 */
public final class AlloyScrapProvider implements RecycleProvider {

    @Override
    public int priority() {
        return 11;
    }

    @Override
    public boolean canHandle(ItemStack item) {
        return ScrapProvenance.readBaseId(item) != null;
    }

    @Override
    public List<RecycleOutput> resolveBaseOutputs(ItemStack item) {
        String baseId = ScrapProvenance.readBaseId(item);
        if (baseId == null || baseId.isBlank()) {
            return List.of();
        }
        Ingredient ingredient = IngredientLoader.getByString(baseId);
        if (ingredient == null) {
            logMissing("ingredient", baseId);
            return List.of();
        }
        String path = ingredient.getPath();
        if (path == null || path.isBlank()) {
            logMissing("ingredient path", baseId);
            return List.of();
        }
        return List.of(new RecycleOutput(path, 1));
    }

    @Override
    public double returnRate() {
        return Cache.scrapReturnRate;
    }

    private void logMissing(String kind, String id) {
        Recycler.plugin.getLogger().warning("[Recycler] Missing live AdvancedCrafting " + kind + " for scrap: " + id);
    }
}
