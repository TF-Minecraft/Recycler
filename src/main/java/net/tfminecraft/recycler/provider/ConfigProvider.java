package net.tfminecraft.recycler.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.recycler.loader.RecipeLoader;
import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Fallback: recipes/*.yml matched via TLibs ItemChecker (not exact path strings).
 */
public final class ConfigProvider implements RecycleProvider {

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean appliesMaxReturnRate() {
        return false;
    }

    @Override
    public boolean canHandle(ItemStack item) {
        return RecipeLoader.findMatchingInput(item) != null;
    }

    @Override
    public List<RecycleOutput> resolveBaseOutputs(ItemStack item) {
        String inputPath = RecipeLoader.findMatchingInput(item);
        if (inputPath == null) {
            return List.of();
        }
        Map<String, Integer> outputs = RecipeLoader.getOutputsForInput(inputPath);
        List<RecycleOutput> list = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : outputs.entrySet()) {
            if (entry.getValue() > 0) {
                list.add(new RecycleOutput(entry.getKey(), entry.getValue()));
            }
        }
        return list;
    }
}
