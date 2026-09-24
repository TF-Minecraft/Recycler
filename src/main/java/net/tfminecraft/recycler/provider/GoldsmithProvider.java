package net.tfminecraft.recycler.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.ItemStats;
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem;
import net.Indyuce.mmoitems.stat.data.GemSocketsData;
import net.Indyuce.mmoitems.stat.data.type.StatData;
import net.tfminecraft.geminfusion.goldsmith.GoldsmithMaterial;
import net.tfminecraft.geminfusion.goldsmith.JewelryProject;
import net.tfminecraft.geminfusion.goldsmith.JewelryProjectLoader;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.model.RecycleOutput;
import net.tfminecraft.tlibs.TLibs;

/**
 * Returns the live goldsmithing recipe for a finished jewelry piece.
 * The infused gem is not returned: its roll is baked into the piece and it has no single item path.
 * Requires GemInfusion on the server (registered only when that plugin is present).
 */
public final class GoldsmithProvider implements RecycleProvider {

    @Override
    public int priority() {
        return 25;
    }

    @Override
    public boolean canHandle(ItemStack item) {
        return matchingProject(item) != null && !hasSocketedGems(item);
    }

    @Override
    public List<RecycleOutput> resolveBaseOutputs(ItemStack item) {
        JewelryProject project = matchingProject(item);
        if (project == null || hasSocketedGems(item)) {
            return List.of();
        }

        Map<String, Integer> merged = new LinkedHashMap<>();
        for (Map.Entry<GoldsmithMaterial, Integer> entry : project.getRecipe().entrySet()) {
            GoldsmithMaterial material = entry.getKey();
            Integer amount = entry.getValue();
            if (material == null || amount == null || amount <= 0) {
                continue;
            }
            String path = material.getPath();
            if (path == null || path.isBlank()) {
                logMissing(project.getId(), material.getId());
                continue;
            }
            merged.merge(path, amount, Integer::sum);
        }

        List<RecycleOutput> outputs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            outputs.add(new RecycleOutput(entry.getKey(), entry.getValue()));
        }
        return outputs;
    }

    private static JewelryProject matchingProject(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        List<JewelryProject> matches = new ArrayList<>();
        for (JewelryProject project : JewelryProjectLoader.get().values()) {
            if (project == null) {
                continue;
            }
            String path = project.getItem();
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, path)) {
                    matches.add(project);
                }
            } catch (RuntimeException ex) {
                Recycler.plugin.getLogger().warning(
                        "[Recycler] Failed to match goldsmith project " + project.getId() + ": " + ex.getMessage());
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            Recycler.plugin.getLogger().warning(
                    "[Recycler] Multiple goldsmith projects match one item; refusing to recycle.");
        }
        return null;
    }

    /**
     * Jewelry has no unsocket flow here. A socketed gem would be destroyed, so refuse the piece.
     * Unreadable socket data is treated as occupied.
     */
    private static boolean hasSocketedGems(ItemStack item) {
        try {
            LiveMMOItem mmo = new LiveMMOItem(NBTItem.get(item));
            if (!mmo.hasData(ItemStats.GEM_SOCKETS)) {
                return false;
            }
            StatData data = mmo.getData(ItemStats.GEM_SOCKETS);
            return data instanceof GemSocketsData sockets && !sockets.getGems().isEmpty();
        } catch (Exception ex) {
            return true;
        }
    }

    private static void logMissing(String projectId, String materialId) {
        Recycler.plugin.getLogger().warning(
                "[Recycler] Goldsmith project " + projectId + " is missing a live material path: " + materialId);
    }
}
