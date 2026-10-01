package net.tfminecraft.recycler.provider;

import java.util.List;
import java.util.ArrayList;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.advancedcrafting.loaders.IngredientLoader;
import net.tfminecraft.advancedcrafting.objects.data.ScrapProvenance;
import net.tfminecraft.advancedcrafting.objects.ingredients.Ingredient;
import net.tfminecraft.recycler.Cache;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Returns recorded failed-forge ingredients with independent per-unit recovery chances.
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
        List<RecycleOutput> outputs = new ArrayList<>();
        String baseId = ScrapProvenance.readBaseId(item);
        ScrapProvenance.readInputs(item).forEach((id, amount) -> addOutput(outputs, id, amount,
                id.equalsIgnoreCase(baseId)));
        return outputs;
    }

    private void addOutput(List<RecycleOutput> outputs, String ingredientId, int amount, boolean base) {
        Ingredient ingredient = IngredientLoader.getByString(ingredientId);
        if (ingredient == null) {
            logMissing("ingredient", ingredientId);
            return;
        }
        String path = ingredient.getPath();
        if (path == null || path.isBlank()) {
            logMissing("ingredient path", ingredientId);
            return;
        }
        if (!base && !isCatalystWhitelisted(path)) return;
        double rate = base ? Cache.scrapReturnRate
                : Cache.scrapCatalystRates.getOrDefault(Integer.toString(ingredient.getIngredientData().getTier()),
                        Cache.scrapCatalystDefaultRate);
        outputs.add(new RecycleOutput(path, amount, rate));
    }

    private boolean isCatalystWhitelisted(String path) {
        String normalized = path.toLowerCase(java.util.Locale.ROOT);
        for (String rule : Cache.scrapCatalystWhitelistPaths) {
            String pattern = rule.toLowerCase(java.util.Locale.ROOT);
            if (pattern.endsWith("*")) {
                if (normalized.startsWith(pattern.substring(0, pattern.length() - 1))) return true;
            } else if (normalized.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double returnRate() {
        return Cache.scrapReturnRate;
    }

    private void logMissing(String kind, String id) {
        Recycler.plugin.getLogger().warning("[Recycler] Missing live AdvancedCrafting " + kind + " for scrap: " + id);
    }
}
