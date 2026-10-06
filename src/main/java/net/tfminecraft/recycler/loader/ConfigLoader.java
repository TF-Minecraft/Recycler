package net.tfminecraft.recycler.loader;

import java.io.File;
import java.io.IOException;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import net.tfminecraft.tlibs.interfaces.LoaderInterface;
import net.tfminecraft.recycler.Cache;
import net.tfminecraft.recycler.Messages;
import net.tfminecraft.recycler.Recycler;

public final class ConfigLoader implements LoaderInterface {

    private static final double DEFAULT_CRAFTED_RATE = 0.5;
    private static final double DEFAULT_SCRAP_RATE = 0.5;
    private static final double DEFAULT_RECIPE_RATE = 1.0;
    private static final String DEFAULT_ARTIFACT_ITEM = "m.currency.enchanted_dust";
    private static final java.util.Map<String, Integer> DEFAULT_ARTIFACT_RETURNS = java.util.Map.of(
            "common", 4, "uncommon", 8, "rare", 12, "epic", 16, "legendary", 28);

    @Override
    public void load(File configFile) {
        loadSafe(configFile);
    }

    public boolean loadSafe(File configFile) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException ex) {
            Recycler.plugin.getLogger().severe("[Recycler] Failed to load config.yml: " + ex.getMessage());
            return false;
        }

        ConfigurationSection station = config.getConfigurationSection("station");
        if (station != null) {
            Cache.stationBlock = station.getString("block", Cache.stationBlock);
            Cache.stationPermission = station.getString("permission", Cache.stationPermission);
            applyStationCompleteEffects(station.getConfigurationSection("complete_effects"));
            applyResultSpawn(station.getConfigurationSection("result_spawn"));
        }

        // Legacy keys: max_return_rate covered every crafted item, scrap_return_rate only scrap.
        double craftedFallback = config.getDouble("max_return_rate", DEFAULT_CRAFTED_RATE);
        double scrapFallback = config.getDouble("scrap_return_rate", DEFAULT_SCRAP_RATE);
        ConfigurationSection rates = config.getConfigurationSection("return_rates");
        Cache.advancedCraftingReturnRate = readRate(rates, "advanced_crafting", craftedFallback, DEFAULT_CRAFTED_RATE);
        Cache.scrapReturnRate = readRate(rates, "alloy_scrap", scrapFallback, DEFAULT_SCRAP_RATE);
        ConfigurationSection catalystRates = config.getConfigurationSection("scrap_catalyst_return_rates");
        if (catalystRates == null) {
            catalystRates = config.getConfigurationSection("scrap_gem_return_rates");
        }
        Cache.scrapCatalystWhitelistPaths = config.contains("scrap_catalyst_return_rates.whitelist_paths")
                ? config.getStringList("scrap_catalyst_return_rates.whitelist_paths")
                : java.util.List.of("m.gemstones.*");
        ConfigurationSection tierConfig = catalystRates == null ? null : catalystRates.getConfigurationSection("tiers");
        var tierRates = new java.util.HashMap<String, Double>();
        var defaults = java.util.Map.of("1", 0.01, "2", 0.25, "3", 0.5, "4", 0.75);
        defaults.forEach((tier, rate) -> tierRates.put(tier, readRate(tierConfig, tier, rate, rate)));
        if (tierConfig != null) {
            for (String tier : tierConfig.getKeys(false)) {
                double defaultRate = defaults.getOrDefault(tier, 0.01);
                tierRates.put(tier, readRate(tierConfig, tier, defaultRate, defaultRate));
            }
        }
        Cache.scrapCatalystRates = java.util.Map.copyOf(tierRates);
        Cache.scrapCatalystDefaultRate = readRate(catalystRates,
                "default", 0.01, 0.01);
        Cache.magicGearReturnRate = readRate(rates, "magic_gear", craftedFallback, DEFAULT_CRAFTED_RATE);
        Cache.gunsReturnRate = readRate(rates, "guns", craftedFallback, DEFAULT_CRAFTED_RATE);
        Cache.goldsmithReturnRate = readRate(rates, "goldsmith_jewelry", craftedFallback, DEFAULT_CRAFTED_RATE);
        Cache.recipeReturnRate = readRate(rates, "recipes", DEFAULT_RECIPE_RATE, DEFAULT_RECIPE_RATE);
        Cache.artifactReturnRate = readRate(rates, "artifacts", DEFAULT_RECIPE_RATE, DEFAULT_RECIPE_RATE);
        applyArtifactReturns(config.getConfigurationSection("artifact_returns"));
        Cache.blockConfirmWhenZeroYield = config.getBoolean("block_confirm_when_zero_yield",
                Cache.blockConfirmWhenZeroYield);

        ConfigurationSection deposit = config.getConfigurationSection("deposit");
        if (deposit != null) {
            Cache.depositWhitelistMode = deposit.getBoolean("whitelist_mode", Cache.depositWhitelistMode);
            Cache.depositWhitelistPaths = deposit.getStringList("whitelist_paths");
            Cache.depositBlacklistPaths = deposit.getStringList("blacklist_paths");
            Cache.depositBlockUnbreakable = deposit.getBoolean("block_unbreakable", Cache.depositBlockUnbreakable);
        }

        applyEffect(config.getConfigurationSection("effects.open"),
                v -> Cache.effectOpenSound = v,
                v -> Cache.effectOpenSoundVolume = v,
                v -> Cache.effectOpenSoundPitch = v,
                Cache.effectOpenSound, Cache.effectOpenSoundVolume, Cache.effectOpenSoundPitch);
        applyEffect(config.getConfigurationSection("effects.input_accept"),
                v -> Cache.effectInputAcceptSound = v,
                v -> Cache.effectInputAcceptSoundVolume = v,
                v -> Cache.effectInputAcceptSoundPitch = v,
                Cache.effectInputAcceptSound, Cache.effectInputAcceptSoundVolume, Cache.effectInputAcceptSoundPitch);
        applyEffect(config.getConfigurationSection("effects.input_reject"),
                v -> Cache.effectInputRejectSound = v,
                v -> Cache.effectInputRejectSoundVolume = v,
                v -> Cache.effectInputRejectSoundPitch = v,
                Cache.effectInputRejectSound, Cache.effectInputRejectSoundVolume, Cache.effectInputRejectSoundPitch);
        applyEffect(config.getConfigurationSection("effects.preview_refresh"),
                v -> Cache.effectPreviewRefreshSound = v,
                v -> Cache.effectPreviewRefreshSoundVolume = v,
                v -> Cache.effectPreviewRefreshSoundPitch = v,
                Cache.effectPreviewRefreshSound, Cache.effectPreviewRefreshSoundVolume,
                Cache.effectPreviewRefreshSoundPitch);
        applyEffect(config.getConfigurationSection("effects.confirm"),
                v -> Cache.effectConfirmSound = v,
                v -> Cache.effectConfirmSoundVolume = v,
                v -> Cache.effectConfirmSoundPitch = v,
                Cache.effectConfirmSound, Cache.effectConfirmSoundVolume, Cache.effectConfirmSoundPitch);
        applyEffect(config.getConfigurationSection("effects.complete"),
                v -> Cache.effectCompleteSound = v,
                v -> Cache.effectCompleteSoundVolume = v,
                v -> Cache.effectCompleteSoundPitch = v,
                Cache.effectCompleteSound, Cache.effectCompleteSoundVolume, Cache.effectCompleteSoundPitch);
        applyEffect(config.getConfigurationSection("effects.cancel"),
                v -> Cache.effectCancelSound = v,
                v -> Cache.effectCancelSoundVolume = v,
                v -> Cache.effectCancelSoundPitch = v,
                Cache.effectCancelSound, Cache.effectCancelSoundVolume, Cache.effectCancelSoundPitch);

        Messages.load(new File(Recycler.plugin.getDataFolder(), "messages.yml"));
        return true;
    }

    /**
     * Reads return_rates.key (or the legacy fallback), clamped to 0.0-1.0. Non-finite values use the default.
     */
    private static double readRate(ConfigurationSection rates, String key, double fallback, double defaultRate) {
        double rate = rates != null ? rates.getDouble(key, fallback) : fallback;
        if (Double.isFinite(rate) && rate >= 0.0 && rate <= 1.0) {
            return rate;
        }
        double clamped = Double.isFinite(rate) ? Math.max(0.0, Math.min(1.0, rate)) : defaultRate;
        Recycler.plugin.getLogger().warning("[Recycler] return_rates." + key
                + " must be between 0.0 and 1.0; using " + clamped + " instead of " + rate);
        return clamped;
    }

    /**
     * Listed rarities override the defaults; unlisted ones keep them. Negative amounts become 0;
     * non-numbers keep the built-in amount.
     */
    private static void applyArtifactReturns(ConfigurationSection section) {
        var amounts = new java.util.HashMap<>(DEFAULT_ARTIFACT_RETURNS);
        Cache.artifactReturnItem = DEFAULT_ARTIFACT_ITEM;
        Cache.artifactDefaultReturn = 4;
        Cache.artifactMinMuffle = 0.0;
        if (section != null) {
            Cache.artifactMinMuffle = readRate(section, "min_muffle", 0.0, 0.0);
            Cache.artifactReturnItem = section.getString("item", DEFAULT_ARTIFACT_ITEM).trim();
            Cache.artifactDefaultReturn = readAmount(section, "default", 4);
            ConfigurationSection rarities = section.getConfigurationSection("rarities");
            if (rarities != null) {
                for (String rarity : rarities.getKeys(false)) {
                    // A non-number keeps the built-in amount, or default for a new rarity.
                    String id = rarity.trim().toLowerCase(java.util.Locale.ROOT);
                    int fallback = amounts.getOrDefault(id, Cache.artifactDefaultReturn);
                    amounts.put(id, readAmount(rarities, rarity, fallback));
                }
            }
        }
        Cache.artifactRarityReturns = java.util.Map.copyOf(amounts);
    }

    private static int readAmount(ConfigurationSection section, String key, int fallback) {
        int amount = section.getInt(key, fallback);
        if (amount >= 0) {
            return amount;
        }
        Recycler.plugin.getLogger().warning("[Recycler] artifact_returns amount for " + key
                + " cannot be negative; using 0 instead of " + amount);
        return 0;
    }

    private static void applyEffect(ConfigurationSection section,
            java.util.function.Consumer<String> sound,
            java.util.function.Consumer<Float> volume,
            java.util.function.Consumer<Float> pitch,
            String defaultSound, float defaultVolume, float defaultPitch) {
        if (section == null) {
            return;
        }
        sound.accept(section.getString("sound", defaultSound));
        volume.accept((float) section.getDouble("sound_volume", defaultVolume));
        pitch.accept((float) section.getDouble("sound_pitch", defaultPitch));
    }

    private static void applyStationCompleteEffects(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        Cache.stationCompleteSound = section.getString("sound", Cache.stationCompleteSound);
        Cache.stationCompleteSoundVolume = (float) section.getDouble("sound_volume",
                Cache.stationCompleteSoundVolume);
        Cache.stationCompleteSoundPitch = (float) section.getDouble("sound_pitch", Cache.stationCompleteSoundPitch);
        Cache.stationCompleteExtraSound = section.getString("extra_sound", Cache.stationCompleteExtraSound);
        Cache.stationCompleteExtraSoundVolume = (float) section.getDouble("extra_sound_volume",
                Cache.stationCompleteExtraSoundVolume);
        Cache.stationCompleteExtraSoundPitch = (float) section.getDouble("extra_sound_pitch",
                Cache.stationCompleteExtraSoundPitch);
        Cache.stationCompleteExtraSoundDelayTicks = section.getInt("extra_sound_delay_ticks",
                Cache.stationCompleteExtraSoundDelayTicks);
        Cache.stationCompleteParticle = section.getString("particle", Cache.stationCompleteParticle);
        Cache.stationCompleteParticleCount = section.getInt("particle_count", Cache.stationCompleteParticleCount);
        Cache.stationCompleteParticleRadius = section.getDouble("particle_radius", Cache.stationCompleteParticleRadius);
        Cache.stationCompleteExtraParticle = section.getString("extra_particle", Cache.stationCompleteExtraParticle);
        Cache.stationCompleteExtraParticleCount = section.getInt("extra_particle_count",
                Cache.stationCompleteExtraParticleCount);
        Cache.stationCompleteExtraParticleRadius = section.getDouble("extra_particle_radius",
                Cache.stationCompleteExtraParticleRadius);
    }

    private static void applyResultSpawn(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        Cache.resultSpawnKickVelocityMin = section.getDouble("kick_velocity_min", Cache.resultSpawnKickVelocityMin);
        Cache.resultSpawnKickVelocityMax = section.getDouble("kick_velocity_max", Cache.resultSpawnKickVelocityMax);
        Cache.resultSpawnKickHorizontalMin = section.getDouble("kick_horizontal_min", Cache.resultSpawnKickHorizontalMin);
        Cache.resultSpawnKickHorizontalMax = section.getDouble("kick_horizontal_max", Cache.resultSpawnKickHorizontalMax);
        Cache.resultSpawnTrailTicks = section.getInt("trail_ticks", Cache.resultSpawnTrailTicks);
        Cache.resultSpawnTrailIntervalTicks = section.getInt("trail_interval_ticks", Cache.resultSpawnTrailIntervalTicks);
        Cache.resultSpawnBurstParticles = section.getBoolean("burst_particles", Cache.resultSpawnBurstParticles);
        Cache.resultSpawnStaggerTicks = section.getInt("stagger_ticks", Cache.resultSpawnStaggerTicks);
    }
}
