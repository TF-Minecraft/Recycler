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
import java.io.IOException;
import net.tfminecraft.recycler.ownership.QueueIdentity;
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
        QueueIdentity.Token token = escrowManager.token(player);
        RecycleSession old = sessions.get(player.getUniqueId());
        if (old != null && !token.equals(old.getToken())) end(player, old.getToken());
        return sessions.computeIfAbsent(player.getUniqueId(), id -> new RecycleSession(id, token));
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
        RecycleSession session;
        try { session = getOrCreateSession(player); }
        catch (IllegalArgumentException ex) { player.sendMessage(ex.getMessage()); return; }
        session.setStationLocation(block.getLocation());
        inventoryManager.openMain(player, session);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof RecyclerGuiHolder holder)) {
            return;
        }

        event.setCancelled(true);

        RecycleSession session = sessions.get(player.getUniqueId());
        if (session == null || !java.util.Objects.equals(holder.getToken(), session.getToken())
                || !escrowManager.current(session.getToken(), player)) { player.closeInventory(); return; }
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
        if (!(event.getInventory().getHolder() instanceof RecyclerGuiHolder holder)) {
            return;
        }
        RecycleSession session = sessions.get(player.getUniqueId());
        if (session == null || !java.util.Objects.equals(holder.getToken(), session.getToken()) || session.isConfirmed()) return;
        sessions.remove(player.getUniqueId());
        queue(session);
        if (escrowManager.current(session.getToken(), player)) escrowManager.deliverPending(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        RecycleSession session = sessions.remove(player.getUniqueId());
        if (session != null && !session.isConfirmed()) {
            queue(session);
        }
    }

    public void end(Player player, QueueIdentity.Token token) {
        RecycleSession session = sessions.get(player.getUniqueId());
        if (session == null || !token.equals(session.getToken())) return;
        sessions.remove(player.getUniqueId());
        if (!session.isConfirmed()) queue(session);
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof RecyclerGuiHolder holder
                && token.equals(holder.getToken())) player.closeInventory();
    }

    private void queue(RecycleSession session) {
        try { escrowManager.pending(session.getToken().owner()); }
        catch (IOException ex) { failure(ex); }
    }

    public boolean quiescent(QueueIdentity.Owner owner) {
        return sessions.values().stream().noneMatch(session -> session.getToken().owner().equals(owner));
    }

    private void failure(Exception ex) {
        Recycler.plugin.getLogger().warning("Recycler property held for recovery: " + ex.getMessage());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        escrowManager.deliverPending(event.getPlayer());
    }

    private void handleConfirm(Player player, RecycleSession session) {
        UUID id = player.getUniqueId();
        ItemStack escrow;
        try { escrow = escrowManager.getEscrow(session.getToken().owner()); }
        catch (IOException ex) { failure(ex); return; }
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

        result = result.roll();
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
        if (!escrowManager.current(session.getToken(), player)) return;
        try { escrowManager.consume(session.getToken().owner(), outputs); }
        catch (IOException ex) { failure(ex); player.sendMessage("Recycler storage needs recovery; input was retained."); return; }
        session.setConfirmed(true);
        sessions.remove(id);
        player.closeInventory();
        Bukkit.getPluginManager().callEvent(
                new RecycleCompleteEvent(player, input, providerId, outputLines, stationLoc));
        StationCompleteEffects.play(player, stationLoc);
        spawnOutputsStaggered(stationLoc, outputs);
        player.sendMessage(Messages.get("station.recycled"));
    }

    private void spawnOutputsStaggered(Location stationLoc, List<ItemStack> outputs) {
        if (outputs.isEmpty()) {
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
        ItemStack stack = event.getCurrentItem();

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
        try { escrowManager.deposit(player, session.getToken(), deposit, event.getSlot(), session.depositId()); session.deposited(); }
        catch (IOException | RuntimeException ex) { failure(ex); player.sendMessage("Recycler storage needs recovery; inspect retained item intent."); return; }
        inventoryManager.refreshPreview(player, session, escrowManager);
        StationEffects.playInputAccept(player);
        StationEffects.playPreviewRefresh(player);
    }

    private void handleInputTakeOut(Player player, RecycleSession session) {
        try { if (escrowManager.getEscrow(session.getToken().owner()) == null) return; }
        catch (IOException ex) { failure(ex); return; }
        if (!escrowManager.current(session.getToken(), player)) {
            return;
        }
        escrowManager.returnEscrow(player);
        inventoryManager.refreshPreview(player, session, escrowManager);
        StationEffects.playCancel(player);
    }
}
