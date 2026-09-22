package net.tfminecraft.recycler.manager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.recycler.Cache;
import net.tfminecraft.recycler.Messages;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.gui.RecyclerGuiHolder;
import net.tfminecraft.recycler.event.RecycleCompleteEvent;
import net.tfminecraft.recycler.model.RecycleOutput;
import net.tfminecraft.recycler.model.RecycleSession;
import net.tfminecraft.recycler.provider.RecycleProviderChain;
import net.tfminecraft.recycler.provider.RecycleResult;
import net.tfminecraft.recycler.util.GridLayout;
import net.tfminecraft.recycler.util.RecycleGuard;
import net.tfminecraft.recycler.util.ResultSpawnEffects;
import net.tfminecraft.recycler.util.StationCompleteEffects;
import net.tfminecraft.recycler.util.StationEffects;

/**
 * Station interact, GUI lifecycle, deposit/escrow, confirm/cancel.
 */
public final class RecyclerManager implements Listener {

    private final EscrowManager escrowManager;
    private final InventoryManager inventoryManager;
    private final RecycleProviderChain providerChain;
    private final Map<UUID, RecycleSession> sessions = new ConcurrentHashMap<>();

    public RecyclerManager(EscrowManager escrowManager, InventoryManager inventoryManager,
            RecycleProviderChain providerChain) {
        this.escrowManager = escrowManager;
        this.inventoryManager = inventoryManager;
        this.providerChain = providerChain;
    }

    public RecycleSession getOrCreateSession(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), id -> new RecycleSession(id));
    }

    @EventHandler
    public void onStationInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        if (!TLibs.getBlockAPI().getChecker().checkBlock(block, Cache.stationBlock)) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking()) {
            return;
        }
        if (!Cache.stationPermission.isBlank() && !player.hasPermission(Cache.stationPermission)) {
            return;
        }
        event.setCancelled(true);
        RecycleSession session = getOrCreateSession(player);
        session.setStationLocation(block.getLocation());
        inventoryManager.openMain(player, session);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof RecyclerGuiHolder)) {
            return;
        }

        event.setCancelled(true);

        RecycleSession session = getOrCreateSession(player);
        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        Inventory clickedInventory = event.getClickedInventory();

        if (rawSlot < topSize) {
            if (rawSlot == GridLayout.SLOT_CONFIRM) {
                handleConfirm(player, session);
                return;
            }
            if (rawSlot == GridLayout.SLOT_INPUT) {
                handleInputTakeOut(player, session);
            }
            return;
        }

        if (clickedInventory == null || !clickedInventory.equals(event.getView().getBottomInventory())) {
            return;
        }
        if (event.isShiftClick()) {
            return;
        }
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) {
            return;
        }
        handleDepositFromPlayer(event, player, session);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof RecyclerGuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof RecyclerGuiHolder)) {
            return;
        }
        RecycleSession session = sessions.get(player.getUniqueId());
        if (session != null && session.isConfirmed()) {
            return;
        }
        escrowManager.returnEscrow(player);
        sessions.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        RecycleSession session = sessions.remove(player.getUniqueId());
        if (session != null && !session.isConfirmed()) {
            escrowManager.returnEscrow(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        escrowManager.deliverPending(event.getPlayer());
    }

    private void handleConfirm(Player player, RecycleSession session) {
        UUID id = player.getUniqueId();
        ItemStack escrow = escrowManager.getEscrow(id);
        if (escrow == null || escrow.getType().isAir()) {
            return;
        }

        RecycleResult result = providerChain.resolve(escrow);
        if (!result.isHandled()) {
            return;
        }
        if (Cache.blockConfirmWhenZeroYield && !result.hasYield()) {
            player.sendMessage(Messages.get("station.zero_yield"));
            StationEffects.playInputReject(player);
            return;
        }

        ItemStack inputClone = escrow.clone();
        String providerId = result.getProviderId();
        List<RecycleOutput> outputLines = result.getOutputs();
        List<ItemStack> outputs = result.buildItemStacks();
        Location stationLoc = session.getStationLocation();
        if (stationLoc == null) {
            stationLoc = player.getLocation();
        }
        final Location confirmLoc = stationLoc;

        StationEffects.playConfirm(player);
        completeRecycle(player, session, id, inputClone, providerId, outputLines, outputs, confirmLoc);
    }

    private void completeRecycle(Player player, RecycleSession session, UUID id, ItemStack input, String providerId,
            List<RecycleOutput> outputLines, List<ItemStack> outputs, Location stationLoc) {
        session.setConfirmed(true);
        escrowManager.clearEscrow(id);
        sessions.remove(id);
        player.closeInventory();
        Bukkit.getPluginManager().callEvent(
                new RecycleCompleteEvent(player, input, providerId, outputLines, stationLoc));
        StationCompleteEffects.play(player, stationLoc);
        spawnOutputsStaggered(stationLoc, outputs);
        player.sendMessage(Messages.get("station.recycled"));
    }

    private void spawnOutputsStaggered(Location stationLoc, List<ItemStack> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return;
        }
        int stagger = Math.max(1, Cache.resultSpawnStaggerTicks);
        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                if (index >= outputs.size()) {
                    cancel();
                    return;
                }
                ResultSpawnEffects.spawnAtStation(stationLoc, outputs.get(index));
                index++;
            }
        }.runTaskTimer(Recycler.plugin, 0L, stagger);
    }

    private void handleDepositFromPlayer(InventoryClickEvent event, Player player, RecycleSession session) {
        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory == null) {
            return;
        }

        ItemStack stack = event.getCurrentItem();
        if (stack == null || stack.getType() == Material.AIR) {
            return;
        }

        if (RecycleGuard.isBlocked(stack)) {
            player.sendMessage(Messages.get("station.blocked"));
            StationEffects.playInputReject(player);
            return;
        }

        RecycleResult result = providerChain.resolve(stack);
        if (!result.isHandled()) {
            player.sendMessage(Messages.get("station.not_recyclable"));
            StationEffects.playInputReject(player);
            return;
        }

        ItemStack deposit = stack.clone();
        clickedInventory.setItem(event.getSlot(), null);

        UUID playerId = player.getUniqueId();
        if (escrowManager.hasEscrow(playerId)) {
            escrowManager.giveBackEscrow(player, false);
        }
        escrowManager.putEscrow(playerId, deposit);
        inventoryManager.refreshPreview(player, session, escrowManager);
        StationEffects.playInputAccept(player);
        StationEffects.playPreviewRefresh(player);
    }

    private void handleInputTakeOut(Player player, RecycleSession session) {
        if (!escrowManager.hasEscrow(player.getUniqueId())) {
            return;
        }
        escrowManager.returnEscrow(player);
        inventoryManager.refreshPreview(player, session, escrowManager);
        StationEffects.playCancel(player);
    }
}
