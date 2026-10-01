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
        ScrapProvenance.readInputs(item).forEach((id, amount) -> addOutput(outputs, id, amount));
        return outputs;
    }

    private void addOutput(List<RecycleOutput> outputs, String baseId, int amount) {
        Ingredient ingredient = IngredientLoader.getByString(baseId);
        if (ingredient == null) {
            logMissing("ingredient", baseId);
            return;
        }
        String path = ingredient.getPath();
        if (path == null || path.isBlank()) {
            logMissing("ingredient path", baseId);
            return;
        }
        double rate = path.toLowerCase(java.util.Locale.ROOT).startsWith("m.gemstones.")
                ? Cache.scrapGemRates.getOrDefault(Integer.toString(ingredient.getIngredientData().getTier()),
                        Cache.scrapGemDefaultRate)
                : Cache.scrapReturnRate;
        outputs.add(new RecycleOutput(path, amount, rate));
    }

    @Override
    public double returnRate() {
        return Cache.scrapReturnRate;
    }

    private void logMissing(String kind, String id) {
        Recycler.plugin.getLogger().warning("[Recycler] Missing live AdvancedCrafting " + kind + " for scrap: " + id);
    }
}
