package net.tfminecraft.recycler.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks the recycling station chest GUI.
 */
public final class RecyclerGuiHolder implements InventoryHolder {

    public static final String TITLE = "§8Recycling Station";

    private Inventory inventory;
    private final net.tfminecraft.recycler.ownership.QueueIdentity.Token token;

    public RecyclerGuiHolder() { this(null); }
    public RecyclerGuiHolder(net.tfminecraft.recycler.ownership.QueueIdentity.Token token) { this.token = token; }
    public net.tfminecraft.recycler.ownership.QueueIdentity.Token getToken() { return token; }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
