package net.tfminecraft.recycler.model;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

/**
 * Per-player GUI session state. Escrowed item is authoritative in {@link net.tfminecraft.recycler.manager.EscrowManager}.
 */
public final class RecycleSession {

    private final UUID playerId;
    private Location stationLocation;
    private boolean confirmed;

    public RecycleSession(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public Location getStationLocation() {
        return stationLocation;
    }

    public void setStationLocation(Location stationLocation) {
        this.stationLocation = stationLocation;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    /**
     * Display copy for GUI slot 10. Not the escrow source of truth.
     */
    public ItemStack createDisplayCopy(ItemStack escrowItem) {
        return escrowItem == null ? null : escrowItem.clone();
    }
}
