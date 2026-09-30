package net.tfminecraft.recycler.provider;

import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.ItemStats;
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem;
import net.Indyuce.mmoitems.stat.data.GemSocketsData;
import net.Indyuce.mmoitems.stat.data.type.StatData;
import net.tfminecraft.geminfusion.goldsmith.GoldsmithProvenance;
import net.tfminecraft.recycler.Cache;
import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Returns the materials a finished jewelry piece was actually made from, as recorded by GemInfusion.
 * A slot accepts any material of its type, so this can differ from the project recipe.
 * The infused gem is not returned: its roll is baked into the piece and it has no single item path.
 * Jewelry made before GemInfusion recorded its inputs is not handled.
 * Requires GemInfusion on the server (registered only when that plugin is present).
 */
public final class GoldsmithProvider implements RecycleProvider {

    @Override
    public int priority() {
        return 25;
    }

    @Override
    public double returnRate() {
        return Cache.goldsmithReturnRate;
    }

    @Override
    public boolean canHandle(ItemStack item) {
        return GoldsmithProvenance.read(item) != null && !hasSocketedGems(item);
    }

    @Override
    public List<RecycleOutput> resolveBaseOutputs(ItemStack item) {
        Map<String, Integer> used = GoldsmithProvenance.read(item);
        if (used == null || hasSocketedGems(item)) {
            return List.of();
        }
        return RecycleOutput.fromAmounts(used);
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
}
