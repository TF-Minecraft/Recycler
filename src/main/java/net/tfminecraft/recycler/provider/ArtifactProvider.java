package net.tfminecraft.recycler.provider;

import java.util.List;
import java.util.Locale;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import net.tfminecraft.magic.artifact.Artifact;
import net.tfminecraft.magic.artifact.ArtifactCareStore;
import net.tfminecraft.magic.artifact.ArtifactKeys;
import net.tfminecraft.magic.artifact.ArtifactWear;
import net.tfminecraft.recycler.Cache;
import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Magic artifacts muffled at least {@code artifact_returns.min_muffle} (0 = any artifact).
 * They are found rather than crafted, so there is no record of what went into them: the
 * return is a fixed amount by rarity from {@code artifact_returns}. Stored aura is not returned.
 *
 * <p>Spent husks (artifacts worn out in meditation) hold no aura, so min_muffle does not
 * apply to them. They return {@code artifact_returns.husk_fraction} of their rarity's amount,
 * rounded down.
 */
public final class ArtifactProvider implements RecycleProvider {

    @Override
    public int priority() {
        return 16;
    }

    @Override
    public double returnRate() {
        return Cache.artifactReturnRate;
    }

    @Override
    public boolean canHandle(ItemStack item) {
        if (ArtifactWear.isHusk(item)) {
            return true;
        }
        return Artifact.fromItem(item) != null && !belowMinMuffle(item);
    }

    /**
     * An artifact this provider will not recycle: still under {@code min_muffle}, or its
     * rarity returns nothing. The chain refuses it outright so a recipe file matching the
     * artifact cannot get around either setting.
     */
    public boolean refuses(ItemStack item) {
        if (ArtifactWear.isHusk(item)) {
            return outputsFor(item).isEmpty();
        }
        return Artifact.fromItem(item) != null && (belowMinMuffle(item) || outputsFor(item).isEmpty());
    }

    private static boolean belowMinMuffle(ItemStack item) {
        return ArtifactCareStore.readMuffle(item) < Cache.artifactMinMuffle;
    }

    @Override
    public List<RecycleOutput> resolveBaseOutputs(ItemStack item) {
        if (!ArtifactWear.isHusk(item) && Artifact.fromItem(item) == null) {
            return List.of();
        }
        return outputsFor(item);
    }

    private static List<RecycleOutput> outputsFor(ItemStack item) {
        int amount = Cache.artifactRarityReturns.getOrDefault(rarity(item), Cache.artifactDefaultReturn);
        if (ArtifactWear.isHusk(item)) {
            amount = (int) Math.floor(amount * Cache.artifactHuskFraction);
        }
        if (amount <= 0 || Cache.artifactReturnItem.isBlank()) {
            return List.of();
        }
        return List.of(new RecycleOutput(Cache.artifactReturnItem, amount));
    }

    /**
     * The rarity id Magic stamped when it generated the artifact, or "" for older
     * lore-only artifacts that never had one.
     */
    private static String rarity(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String stored = meta.getPersistentDataContainer().get(
                ArtifactKeys.artifactRarity(), PersistentDataType.STRING);
        return stored == null ? "" : stored.trim().toLowerCase(Locale.ROOT);
    }
}
