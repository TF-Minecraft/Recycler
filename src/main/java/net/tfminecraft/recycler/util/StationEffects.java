package net.tfminecraft.recycler.util;

import org.bukkit.entity.Player;

import net.tfminecraft.recycler.Cache;

public final class StationEffects {

    private StationEffects() {}

    public static void playOpen(Player player) {
        SoundKeys.play(player, Cache.effectOpenSound, Cache.effectOpenSoundVolume, Cache.effectOpenSoundPitch);
    }

    public static void playInputAccept(Player player) {
        SoundKeys.play(player, Cache.effectInputAcceptSound, Cache.effectInputAcceptSoundVolume,
                Cache.effectInputAcceptSoundPitch);
    }

    public static void playInputReject(Player player) {
        SoundKeys.play(player, Cache.effectInputRejectSound, Cache.effectInputRejectSoundVolume,
                Cache.effectInputRejectSoundPitch);
    }

    public static void playPreviewRefresh(Player player) {
        SoundKeys.play(player, Cache.effectPreviewRefreshSound, Cache.effectPreviewRefreshSoundVolume,
                Cache.effectPreviewRefreshSoundPitch);
    }

    public static void playCancel(Player player) {
        SoundKeys.play(player, Cache.effectCancelSound, Cache.effectCancelSoundVolume, Cache.effectCancelSoundPitch);
    }

    public static void playConfirm(Player player) {
        SoundKeys.play(player, Cache.effectConfirmSound, Cache.effectConfirmSoundVolume, Cache.effectConfirmSoundPitch);
    }
}
