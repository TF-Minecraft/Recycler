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

        Cache.maxReturnRate = config.getDouble("max_return_rate", Cache.maxReturnRate);
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
