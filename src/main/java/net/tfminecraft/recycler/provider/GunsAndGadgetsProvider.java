package net.tfminecraft.recycler.provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.gunsandgadgets.guns.data.GunCraftProvenance;
import net.tfminecraft.gunsandgadgets.guns.parts.GunPart;
import net.tfminecraft.gunsandgadgets.utils.GunBrokenMarker;
import net.tfminecraft.gunsandgadgets.utils.GunStatRefresher;
import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Reads stamped part list from GunsAndGadgets crafted guns and sums live part costs.
 */
public final class GunsAndGadgetsProvider implements RecycleProvider {

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public boolean canHandle(ItemStack item) {
        if (!GunStatRefresher.isManaged(item)) {
            return false;
        }
        if (GunBrokenMarker.isBroken(item)) {
            return false;
        }
        GunCraftProvenance provenance = GunCraftProvenance.readFrom(item);
        if (provenance == null) {
            return false;
        }
        return provenance.resolveStampedParts().missingIds().isEmpty();
    }

    @Override
    public List<RecycleOutput> resolveBaseOutputs(ItemStack item) {
        GunCraftProvenance provenance = GunCraftProvenance.readFrom(item);
        if (provenance == null) {
            return List.of();
        }
        GunCraftProvenance.ResolvedParts resolved = provenance.resolveStampedParts();
        if (!resolved.missingIds().isEmpty()) {
            return List.of();
        }

        Map<String, Integer> merged = new HashMap<>();
        for (GunPart part : resolved.live()) {
            for (Map.Entry<String, Integer> entry : part.getCost().entrySet()) {
                int amount = entry.getValue();
                if (amount > 0) {
                    merged.merge(entry.getKey(), amount, Integer::sum);
                }
            }
        }

        List<RecycleOutput> outputs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            outputs.add(new RecycleOutput(entry.getKey(), entry.getValue()));
        }
        return outputs;
    }
}
