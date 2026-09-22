package net.tfminecraft.recycler.manager;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.recycler.GuiCache;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.gui.RecyclerGuiHolder;
import net.tfminecraft.recycler.model.RecycleSession;
import net.tfminecraft.recycler.provider.RecycleProviderChain;
import net.tfminecraft.recycler.provider.RecycleResult;
import net.tfminecraft.recycler.util.GridLayout;
import net.tfminecraft.recycler.util.ItemRef;
import net.tfminecraft.recycler.util.StationEffects;

/**
 * Builds and updates the station GUI.
 */
public final class InventoryManager {

    private final RecycleProviderChain providerChain;

    public InventoryManager(RecycleProviderChain providerChain) {
        this.providerChain = providerChain;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public void openMain(Player player, RecycleSession session) {
        RecyclerGuiHolder holder = new RecyclerGuiHolder();
        var inv = Recycler.plugin.getServer().createInventory(holder, GridLayout.SIZE, RecyclerGuiHolder.TITLE);
        holder.setInventory(inv);
        fillShell(inv);
        player.openInventory(inv);
        StationEffects.playOpen(player);
    }

    public void refreshPreview(Player player, RecycleSession session, EscrowManager escrow) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof RecyclerGuiHolder holder) {
            var inv = holder.getInventory();
            clearPreview(inv);
            ItemStack escrowItem = escrow.getEscrow(session.getPlayerId());
            if (escrowItem != null) {
                inv.setItem(GridLayout.SLOT_INPUT, session.createDisplayCopy(escrowItem));
                RecycleResult result = providerChain.resolve(escrowItem);
                paintPreview(inv, result);
            } else {
                inv.setItem(GridLayout.SLOT_INPUT, null);
            }
        }
    }

    private void fillShell(org.bukkit.inventory.Inventory inv) {
        ItemStack filler = ItemRef.build(GuiCache.filler);
        if (filler == null) {
            filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        }
        ItemRef.applyBlankDisplay(filler);
        ItemStack confirm = ItemRef.build(GuiCache.confirmButton);
        ItemStack arrow = ItemRef.build(GuiCache.arrowRight);
        if (arrow != null) {
            ItemRef.applyBlankDisplay(arrow);
        }

        for (int slot = 0; slot < GridLayout.SIZE; slot++) {
            if (GridLayout.isFillerSlot(slot)) {
                inv.setItem(slot, filler.clone());
            }
        }
        if (arrow != null) {
            for (int arrowSlot : GridLayout.ARROW_SLOTS) {
                inv.setItem(arrowSlot, arrow.clone());
            }
        }
        if (confirm != null) {
            inv.setItem(GridLayout.SLOT_CONFIRM, confirm);
        }
    }

    private void clearPreview(org.bukkit.inventory.Inventory inv) {
        for (int slot : GridLayout.previewSlots()) {
            inv.setItem(slot, null);
        }
    }

    private void paintPreview(org.bukkit.inventory.Inventory inv, RecycleResult result) {
        if (!result.isHandled()) {
            return;
        }
        int index = 0;
        for (var output : result.getOutputs()) {
            if (index >= GridLayout.previewSlots().size()) {
                break;
            }
            var stack = ItemRef.build(output.itemPath());
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            stack.setAmount(Math.min(64, output.baseAmount()));
            inv.setItem(GridLayout.previewSlots().get(index), stack);
            index++;
        }
    }

}