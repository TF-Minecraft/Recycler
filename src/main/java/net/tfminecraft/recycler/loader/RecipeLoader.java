package net.tfminecraft.recycler.loader;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.recycler.Recycler;

/**
 * Static registry of config-defined recycle recipes (fallback provider).
 */
public final class RecipeLoader {

    private static final Map<String, Map<String, Integer>> recipes = new HashMap<>();

    public RecipeLoader() {}

    public static int size() {
        return recipes.size();
    }

    public static Map<String, Map<String, Integer>> getAll() {
        return Collections.unmodifiableMap(recipes);
    }

    public static Map<String, Integer> getOutputsForInput(String inputPath) {
        Map<String, Integer> outputs = recipes.get(normalize(inputPath));
        return outputs != null ? outputs : Map.of();
    }

    /**
     * Finds a recipe whose input path matches the item via TLibs ItemChecker
     * (e.g. {@code v.iron_sword} matches any iron sword material, including renamed or modeled).
     */
    public static String findMatchingInput(ItemStack item) {
        if (item == null || item.getType().isAir() || recipes.isEmpty()) {
            return null;
        }
        var checker = net.tfminecraft.tlibs.TLibs.getItemAPI().getChecker();
        for (String inputPath : recipes.keySet()) {
            try {
                if (checker.checkItemWithPath(item, inputPath)) {
                    return inputPath;
                }
            } catch (Exception ex) {
                Recycler.plugin.getLogger().warning("[Recycler] Recipe input path failed to match: " + inputPath);
            }
        }
        return null;
    }

    public boolean loadFolder(File folder) {
        recipes.clear();
        if (!folder.exists() || !folder.isDirectory()) {
            return true;
        }
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (files == null) {
            return true;
        }
        boolean ok = true;
        for (File file : files) {
            ok &= loadFile(file);
        }
        return ok;
    }

    private boolean loadFile(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception ex) {
            Recycler.plugin.getLogger().severe("[Recycler] Failed to load " + file.getName() + ": " + ex.getMessage());
            return false;
        }
        ConfigurationSection section = yaml.getConfigurationSection("recipes");
        if (section == null) {
            return true;
        }
        for (String recipeId : section.getKeys(false)) {
            ConfigurationSection recipe = section.getConfigurationSection(recipeId);
            if (recipe == null) {
                continue;
            }
            loadRecipeEntry(recipeId, recipe, file.getName());
        }
        return true;
    }

    private void loadRecipeEntry(String recipeId, ConfigurationSection recipe, String fileName) {
        String inputPath = firstNonBlank(recipe.getString("input"), recipe.getString("inpuit"));
        if (inputPath != null) {
            Map<String, Integer> outputs = readOutputs(recipe, recipeId, fileName);
            if (outputs.isEmpty()) {
                Recycler.plugin.getLogger().warning("[Recycler] Recipe '" + recipeId + "' has no valid outputs in "
                        + fileName);
                return;
            }
            recipes.put(normalize(inputPath), outputs);
            return;
        }
        loadLegacyPathRecipes(recipe, recipeId, fileName);
    }

    /**
     * Legacy: recipe key (or nested key) is the tlibs input path; outputs as yaml map.
     */
    private void loadLegacyPathRecipes(ConfigurationSection section, String pathPrefix, String fileName) {
        ConfigurationSection outputsSection = section.getConfigurationSection("outputs");
        if (outputsSection != null && !section.isList("outputs")) {
            Map<String, Integer> map = readOutputsMap(outputsSection);
            if (!map.isEmpty()) {
                recipes.put(normalize(pathPrefix), map);
                return;
            }
            Recycler.plugin.getLogger().warning("[Recycler] Recipe " + pathPrefix + " has empty outputs in "
                    + fileName);
            return;
        }
        for (String key : section.getKeys(false)) {
            if (isRecipeMetaKey(key)) {
                continue;
            }
            ConfigurationSection nested = section.getConfigurationSection(key);
            if (nested == null) {
                continue;
            }
            String fullPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            loadLegacyPathRecipes(nested, fullPath, fileName);
        }
    }

    private Map<String, Integer> readOutputs(ConfigurationSection recipe, String recipeId, String fileName) {
        List<String> lines = recipe.getStringList("outputs");
        if (lines != null && !lines.isEmpty()) {
            return parseOutputLines(lines, recipeId, fileName);
        }
        ConfigurationSection outputsSection = recipe.getConfigurationSection("outputs");
        if (outputsSection != null) {
            return readOutputsMap(outputsSection);
        }
        return Map.of();
    }

    private Map<String, Integer> parseOutputLines(List<String> lines, String recipeId, String fileName) {
        Map<String, Integer> map = new HashMap<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            parseOutputLine(line.trim(), map, recipeId, fileName);
        }
        return map;
    }

    private void parseOutputLine(String line, Map<String, Integer> map, String recipeId, String fileName) {
        String path;
        int amount;
        if (line.endsWith(")")) {
            int open = line.lastIndexOf('(');
            if (open <= 0) {
                Recycler.plugin.getLogger().warning("[Recycler] Recipe '" + recipeId + "' bad output line '"
                        + line + "' in " + fileName);
                return;
            }
            path = line.substring(0, open).trim();
            try {
                amount = Integer.parseInt(line.substring(open + 1, line.length() - 1).trim());
            } catch (NumberFormatException ex) {
                Recycler.plugin.getLogger().warning("[Recycler] Recipe '" + recipeId + "' bad amount in '"
                        + line + "' in " + fileName);
                return;
            }
        } else {
            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace <= 0) {
                Recycler.plugin.getLogger().warning("[Recycler] Recipe '" + recipeId + "' bad output line '"
                        + line + "' in " + fileName + " (expected 'path amount' or 'path(amount)')");
                return;
            }
            path = line.substring(0, lastSpace).trim();
            try {
                amount = Integer.parseInt(line.substring(lastSpace + 1).trim());
            } catch (NumberFormatException ex) {
                Recycler.plugin.getLogger().warning("[Recycler] Recipe '" + recipeId + "' bad amount in '"
                        + line + "' in " + fileName);
                return;
            }
        }
        if (path.isBlank() || amount <= 0) {
            Recycler.plugin.getLogger().warning("[Recycler] Recipe '" + recipeId + "' ignored output '"
                    + line + "' in " + fileName);
            return;
        }
        map.merge(normalize(path), amount, Integer::sum);
    }

    private Map<String, Integer> readOutputsMap(ConfigurationSection outputs) {
        Map<String, Integer> map = new HashMap<>();
        flattenOutputSection(outputs, "", map);
        return map;
    }

    private void flattenOutputSection(ConfigurationSection section, String pathPrefix, Map<String, Integer> map) {
        for (String key : section.getKeys(false)) {
            String fullPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;
            if (section.isInt(key)) {
                int amount = section.getInt(key, 0);
                if (amount > 0) {
                    map.put(normalize(fullPath), amount);
                }
                continue;
            }
            ConfigurationSection nested = section.getConfigurationSection(key);
            if (nested != null) {
                flattenOutputSection(nested, fullPath, map);
            }
        }
    }

    private static boolean isRecipeMetaKey(String key) {
        return "input".equalsIgnoreCase(key) || "inpuit".equalsIgnoreCase(key) || "outputs".equalsIgnoreCase(key);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    private static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.trim();
        if (trimmed.toLowerCase().startsWith("vanilla.")) {
            return "v." + trimmed.substring("vanilla.".length()).toLowerCase();
        }
        return trimmed.toLowerCase();
    }
}
