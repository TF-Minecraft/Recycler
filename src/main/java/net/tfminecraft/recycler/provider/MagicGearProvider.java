package net.tfminecraft.recycler.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import io.lumine.mythic.lib.api.item.NBTItem;
import net.Indyuce.mmoitems.ItemStats;
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem;
import net.Indyuce.mmoitems.stat.data.GemSocketsData;
import net.Indyuce.mmoitems.stat.data.type.StatData;
import net.tfminecraft.magic.gear.GearBrokenMarker;
import net.tfminecraft.magic.gear.GearCosts;
import net.tfminecraft.magic.gear.GearProvenance;
import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Reads the stamped part list off a crafted mage weapon and sums the live part costs.
 * Resonance and any charge that went into the weapon are not returned, only materials.
 */
public final class MagicGearProvider implements RecycleProvider {

    @Override
    public int priority() {
        return 15;
    }

    @Override
    public boolean canHandle(ItemStack item) {
        if (!GearProvenance.isGear(item)) {
            return false;
        }
        if (GearBrokenMarker.isBroken(item)) {
            return false;
        }
        if (!GearProvenance.missingPartIds(item).isEmpty()) {
            return false;
        }
        // There is no unsocket flow, so recycling a socketed weapon would destroy the
        // runes. Refuse it instead and let the player keep both.
        return !hasSocketedRunes(item);
    }

    @Override
    public List<RecycleOutput> resolveBaseOutputs(ItemStack item) {
        if (!GearProvenance.isGear(item)) {
            return List.of();
        }
        List<RecycleOutput> outputs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : GearCosts.total(GearProvenance.resolveParts(item)).entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                outputs.add(new RecycleOutput(entry.getKey(), entry.getValue()));
            }
        }
        return outputs;
    }

    private static boolean hasSocketedRunes(ItemStack item) {
        try {
            LiveMMOItem mmo = new LiveMMOItem(NBTItem.get(item));
            if (!mmo.hasData(ItemStats.GEM_SOCKETS)) {
                return false;
            }
            StatData data = mmo.getData(ItemStats.GEM_SOCKETS);
            return data instanceof GemSocketsData sockets && !sockets.getGems().isEmpty();
        } catch (Exception ex) {
            // Unreadable socket data is treated as occupied, so a rune is never eaten.
            return true;
        }
    }
}
