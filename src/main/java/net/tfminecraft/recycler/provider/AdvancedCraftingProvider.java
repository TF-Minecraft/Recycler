package net.tfminecraft.recycler.provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.advancedcrafting.loaders.IngredientLoader;
import net.tfminecraft.advancedcrafting.managers.AlloyManager;
import net.tfminecraft.advancedcrafting.objects.alloys.Alloy;
import net.tfminecraft.advancedcrafting.objects.data.AlloyRecipe;
import net.tfminecraft.advancedcrafting.objects.data.CraftInput;
import net.tfminecraft.advancedcrafting.objects.data.CraftProvenance;
import net.tfminecraft.advancedcrafting.objects.ingredients.Ingredient;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Reads {@link CraftProvenance} from crafted AdvancedCrafting items.
 * Requires AdvancedCrafting on the server (registered only when plugin is present).
 */
public final class AdvancedCraftingProvider implements RecycleProvider {

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean canHandle(ItemStack item) {
        return CraftProvenance.readFrom(item) != null;
    }

    @Override
    public List<RecycleOutput> resolveBaseOutputs(ItemStack item) {
        CraftProvenance provenance = CraftProvenance.readFrom(item);
        if (provenance == null) {
            return List.of();
        }

        Map<String, Integer> merged = new HashMap<>();
        for (CraftInput input : provenance.getInputs()) {
            String kind = input.getKind();
            if (kind == null) {
                continue;
            }
            if (kind.equalsIgnoreCase("ingredient")) {
                addIngredientPath(merged, input.getId(), input.getAmount());
            } else if (kind.equalsIgnoreCase("alloy")) {
                addAlloyInputs(merged, input.getId(), input.getAmount());
            }
        }

        List<RecycleOutput> outputs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            outputs.add(new RecycleOutput(entry.getKey(), entry.getValue()));
        }
        return outputs;
    }

    private void addAlloyInputs(Map<String, Integer> merged, String alloyId, int alloyAmount) {
        if (alloyId == null || alloyAmount <= 0) {
            return;
        }
        Alloy alloy = AlloyManager.getAlloyById(alloyId);
        if (alloy == null || alloy.getData() == null) {
            logMissing("alloy", alloyId);
            return;
        }
        AlloyRecipe recipe = alloy.getData().getRecipe();
        if (recipe == null) {
            logMissing("alloy recipe", alloyId);
            return;
        }
        addIngredientPath(merged, recipe.getBaseId(), alloyAmount);
        for (String catalystId : recipe.getCatalystIds()) {
            addIngredientPath(merged, catalystId, alloyAmount);
        }
    }

    private void addIngredientPath(Map<String, Integer> merged, String ingredientId, int amount) {
        if (ingredientId == null || ingredientId.isBlank() || amount <= 0) {
            return;
        }
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
        merged.merge(path, amount, Integer::sum);
    }

    private void logMissing(String kind, String id) {
        Recycler.plugin.getLogger().warning("[Recycler] Missing live AdvancedCrafting " + kind + ": " + id);
    }
}
