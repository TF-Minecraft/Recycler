package net.tfminecraft.recycler.manager;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.recycler.GuiCache;
import net.tfminecraft.recycler.Messages;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.gui.RecyclerGuiHolder;
import net.tfminecraft.recycler.model.RecycleOutput;
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

    private static final Pattern PLACEHOLDER = Pattern.compile("%(item|chance|amount)%");

    private final RecycleProviderChain providerChain;

    public InventoryManager(RecycleProviderChain providerChain) {
        this.providerChain = providerChain;
    }

    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    public void openMain(Player player, RecycleSession session) {
        RecyclerGuiHolder holder = new RecyclerGuiHolder(session.getToken());
        var inv = Recycler.plugin.getServer().createInventory(holder, GridLayout.SIZE, RecyclerGuiHolder.TITLE);
        holder.setInventory(inv);
        fillShell(inv);
        player.openInventory(inv);
        StationEffects.playOpen(player);
    }

    public void refreshPreview(Player player, RecycleSession session, EscrowManager escrow) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof RecyclerGuiHolder holder
                && java.util.Objects.equals(holder.getToken(), session.getToken())) {
            var inv = holder.getInventory();
            clearPreview(inv);
            ItemStack escrowItem;
            try { escrowItem = escrow.getEscrow(session.getToken().owner()); }
            catch (java.io.IOException ex) { player.sendMessage("Recycler item needs storage recovery."); return; }
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
            if (stack == null) {
                continue;
            }
            stack.setAmount(Math.min(64, output.baseAmount()));
            if (output.returnChance() >= 0) {
                addChanceLore(stack, output);
            }
            inv.setItem(GridLayout.previewSlots().get(index), stack);
            index++;
        }
    }

    /** Appends preview.scrap_chance from messages.yml under the item's own lore. */
    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private void addChanceLore(ItemStack stack, RecycleOutput output) {
        List<String> template = Messages.getList("preview.scrap_chance");
        if (template.isEmpty()) {
            return;
        }
        Map<String, String> values = Map.of(
                "item", itemName(stack),
                "chance", formatChance(output.returnChance()),
                "amount", Integer.toString(output.baseAmount()));
        var meta = stack.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        for (String line : template) {
            // One pass over the template, so a name containing %amount% is left as written.
            Matcher match = PLACEHOLDER.matcher(line);
            StringBuilder filled = new StringBuilder();
            while (match.find()) {
                match.appendReplacement(filled, Matcher.quoteReplacement(values.get(match.group(1))));
            }
            match.appendTail(filled);
            lore.add(filled.toString());
        }
        meta.setLore(lore);
        stack.setItemMeta(meta);
    }

    /** The item's own name with its colour; plain vanilla items get their material name in white. */
    // Keep the existing legacy text representation, formatting, and exact-string comparisons.
    @SuppressWarnings("deprecation")
    private static String itemName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        if (meta.hasItemName()) {
            return meta.getItemName();
        }
        StringBuilder name = new StringBuilder();
        for (String word : stack.getType().name().toLowerCase(Locale.ROOT).split("_")) {
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return ChatColor.WHITE + name.toString();
    }

    /** 0.5 -> "50%", 0.255 -> "25.5%". */
    private static String formatChance(double chance) {
        return new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(chance * 100) + "%";
    }

}
