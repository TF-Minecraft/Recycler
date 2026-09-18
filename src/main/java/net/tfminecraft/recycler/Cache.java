package net.tfminecraft.recycler;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime scalars from config.yml. Recipe registry lives in {@link net.tfminecraft.recycler.loader.RecipeLoader}.
 */
public final class Cache {

    private Cache() {}

    public static String stationBlock = "iaf(tfmc:recycling_station)";
    public static String stationPermission = "recycler.use";

    public static double maxReturnRate = 0.8;
    public static boolean blockConfirmWhenZeroYield = true;

    public static boolean depositWhitelistMode = false;
    public static List<String> depositWhitelistPaths = new ArrayList<>();
    public static List<String> depositBlacklistPaths = new ArrayList<>();
    public static boolean depositBlockUnbreakable = true;

    public static String effectOpenSound = "block.stone_button.click_on";
    public static float effectOpenSoundVolume = 0.6f;
    public static float effectOpenSoundPitch = 1.0f;

    public static String effectInputAcceptSound = "entity.item_frame.place";
    public static float effectInputAcceptSoundVolume = 0.5f;
    public static float effectInputAcceptSoundPitch = 1.2f;

    public static String effectInputRejectSound = "entity.villager.no";
    public static float effectInputRejectSoundVolume = 0.8f;
    public static float effectInputRejectSoundPitch = 1.0f;

    public static String effectPreviewRefreshSound = "block.grindstone.use";
    public static float effectPreviewRefreshSoundVolume = 0.3f;
    public static float effectPreviewRefreshSoundPitch = 1.4f;

    public static String effectConfirmSound = "block.anvil.use";
    public static float effectConfirmSoundVolume = 0.7f;
    public static float effectConfirmSoundPitch = 1.1f;

    public static String effectCompleteSound = "block.anvil.land";
    public static float effectCompleteSoundVolume = 0.55f;
    public static float effectCompleteSoundPitch = 0.92f;

    public static String effectCancelSound = "block.note_block.bass";
    public static float effectCancelSoundVolume = 0.8f;
    public static float effectCancelSoundPitch = 0.6f;

    public static String stationCompleteSound = "block.anvil.land";
    public static float stationCompleteSoundVolume = 0.55f;
    public static float stationCompleteSoundPitch = 0.92f;
    public static String stationCompleteExtraSound = "none";
    public static float stationCompleteExtraSoundVolume = 0.0f;
    public static float stationCompleteExtraSoundPitch = 1.0f;
    public static int stationCompleteExtraSoundDelayTicks = 0;
    public static String stationCompleteParticle = "CRIT";
    public static int stationCompleteParticleCount = 10;
    public static double stationCompleteParticleRadius = 0.35;
    public static String stationCompleteExtraParticle = "none";
    public static int stationCompleteExtraParticleCount = 0;
    public static double stationCompleteExtraParticleRadius = 0.0;

    public static double resultSpawnKickVelocityMin = 0.09;
    public static double resultSpawnKickVelocityMax = 0.19;
    public static double resultSpawnKickHorizontalMin = 0.01;
    public static double resultSpawnKickHorizontalMax = 0.03;
    public static int resultSpawnTrailTicks = 140;
    public static int resultSpawnTrailIntervalTicks = 2;
    public static boolean resultSpawnBurstParticles = true;
    public static int resultSpawnStaggerTicks = 3;
}
