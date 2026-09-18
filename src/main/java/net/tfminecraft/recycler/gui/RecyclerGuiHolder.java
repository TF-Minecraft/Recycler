package net.tfminecraft.recycler.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks the recycling station chest GUI.
 */
public final class RecyclerGuiHolder implements InventoryHolder {

    public static final String TITLE = "§8Recycling Station";

    private Inventory inventory;

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
