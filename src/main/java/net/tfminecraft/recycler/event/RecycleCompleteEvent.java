package net.tfminecraft.recycler.event;

import java.util.Collections;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Fired when a player successfully confirms a recycle at the station.
 * Other plugins (professions, economy) may listen for hooks or logging.
 */
public final class RecycleCompleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ItemStack input;
    private final String providerId;
    private final List<RecycleOutput> outputs;
    private final Location stationLocation;

    public RecycleCompleteEvent(Player player, ItemStack input, String providerId, List<RecycleOutput> outputs,
            Location stationLocation) {
        this.player = player;
        this.input = input != null ? input.clone() : null;
        this.providerId = providerId != null ? providerId : "";
        this.outputs = outputs != null ? List.copyOf(outputs) : List.of();
        this.stationLocation = stationLocation != null ? stationLocation.clone() : null;
    }

    public Player getPlayer() {
        return player;
    }

    public ItemStack getInput() {
        return input != null ? input.clone() : null;
    }

    public String getProviderId() {
        return providerId;
    }

    public List<RecycleOutput> getOutputs() {
        return outputs;
    }

    public Location getStationLocation() {
        return stationLocation != null ? stationLocation.clone() : null;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
