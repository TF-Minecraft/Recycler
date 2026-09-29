package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.recycler.gui.*;
import net.tfminecraft.recycler.manager.*;
import net.tfminecraft.recycler.model.*;
import net.tfminecraft.recycler.provider.*;
import net.tfminecraft.recycler.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;

class RecyclerManagerTest extends TestSupport {
  EscrowManager escrow;
  InventoryManager inventories;
  RecycleProviderChain chain;
  RecyclerManager manager;
  org.mockbukkit.mockbukkit.entity.PlayerMock player;

  @BeforeEach
  void managers() {
    escrow = mock(EscrowManager.class);
    inventories = mock(InventoryManager.class);
    chain = mock(RecycleProviderChain.class);
    manager = new RecyclerManager(escrow, inventories, chain);
    player = server.addPlayer();
  }

  InventoryClickEvent click(int raw) {
    var e = mock(InventoryClickEvent.class);
    when(e.getWhoClicked()).thenReturn(player);
    when(e.getView()).thenReturn(player.getOpenInventory());
    when(e.getRawSlot()).thenReturn(raw);
    when(e.getClickedInventory())
        .thenReturn(raw < 27 ? player.getOpenInventory().getTopInventory() : player.getInventory());
    when(e.getSlot()).thenReturn(raw < 27 ? raw : raw - 27);
    return e;
  }

  void open() {
    var holder = new RecyclerGuiHolder();
    var inv = server.createInventory(holder, 27);
    holder.setInventory(inv);
    player.openInventory(inv);
  }

  @Test
  void stationOpeningHonorsHandPermissionSneakingAndBlock() {
    var e = mock(PlayerInteractEvent.class);
    when(e.getPlayer()).thenReturn(player);
    when(e.getAction()).thenReturn(Action.LEFT_CLICK_BLOCK);
    manager.onStationInteract(e);
    when(e.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
    manager.onStationInteract(e);
    when(e.getHand()).thenReturn(EquipmentSlot.HAND);
    manager.onStationInteract(e);
    var block = mock(org.bukkit.block.Block.class);
    when(e.getClickedBlock()).thenReturn(block);
    var blockApi = mock(net.tfminecraft.tlibs.objects.api.BlockAPI.class, RETURNS_DEEP_STUBS);
    tlibs.when(net.tfminecraft.tlibs.TLibs::getBlockAPI).thenReturn(blockApi);
    manager.onStationInteract(e);
    when(blockApi.getChecker().checkBlock(block, Cache.stationBlock)).thenReturn(true);
    player.setSneaking(true);
    manager.onStationInteract(e);
    player.setSneaking(false);
    manager.onStationInteract(e);
    Cache.stationPermission = "";
    var loc = new Location(player.getWorld(), 1, 2, 3);
    when(block.getLocation()).thenReturn(loc);
    manager.onStationInteract(e);
    verify(inventories).openMain(player, manager.getOrCreateSession(player));
    assertEquals(loc, manager.getOrCreateSession(player).getStationLocation());
    Cache.stationPermission = "recycler.use";
    player.addAttachment(
        org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), "recycler.use", true);
    manager.onStationInteract(e);
    verify(e, times(2)).setCancelled(true);
  }

  @Test
  void clickGuardsAndInputCancellation() {
    var npc = mock(HumanEntity.class);
    var e = mock(InventoryClickEvent.class);
    when(e.getWhoClicked()).thenReturn(npc);
    manager.onInventoryClick(e);
    player.openInventory(server.createInventory(null, 9));
    manager.onInventoryClick(click(1));
    open();
    manager.onInventoryClick(click(-999));
    manager.onInventoryClick(click(1));
    manager.onInventoryClick(click(10));
    verify(escrow, never()).returnEscrow(player);
    when(escrow.hasEscrow(player.getUniqueId())).thenReturn(true);
    manager.onInventoryClick(click(10));
    verify(escrow).returnEscrow(player);
    verify(inventories).refreshPreview(player, manager.getOrCreateSession(player), escrow);
    e = click(27);
    when(e.getClickedInventory()).thenReturn(null);
    manager.onInventoryClick(e);
    when(e.getClickedInventory()).thenReturn(server.createInventory(null, 9));
    manager.onInventoryClick(e);
    e = click(27);
    when(e.isShiftClick()).thenReturn(true);
    manager.onInventoryClick(e);
    e = click(27);
    manager.onInventoryClick(e);
    when(e.getCurrentItem()).thenReturn(item(Material.AIR));
    manager.onInventoryClick(e);
  }

  @Test
  void depositsRejectPoliciesOrUnknownItemsAndReplaceEscrow() {
    open();
    var e = click(27);
    var stack = new ItemStack(Material.DIAMOND, 3);
    when(e.getCurrentItem()).thenReturn(stack);
    try (var guards = mockStatic(RecycleGuard.class)) {
      guards.when(() -> RecycleGuard.isBlocked(stack)).thenReturn(true);
      manager.onInventoryClick(e);
      verify(escrow, never()).putEscrow(any(), any());
      guards.when(() -> RecycleGuard.isBlocked(stack)).thenReturn(false);
      when(chain.resolve(stack)).thenReturn(RecycleResult.notHandled());
      manager.onInventoryClick(e);
      when(chain.resolve(stack))
          .thenReturn(RecycleResult.of("test", List.of(), new RecycleContext(1, 1, 1)));
      player.getInventory().setItem(0, stack);
      manager.onInventoryClick(e);
      assertNull(player.getInventory().getItem(0));
      verify(escrow).putEscrow(player.getUniqueId(), stack);
      when(escrow.hasEscrow(player.getUniqueId())).thenReturn(true);
      manager.onInventoryClick(e);
      verify(escrow).giveBackEscrow(player, false);
    }
  }

  @Test
  void confirmRequiresEscrowAndYieldThenCompletesExactlyOnce() {
    open();
    var e = click(0);
    manager.onInventoryClick(e);
    when(escrow.getEscrow(player.getUniqueId())).thenReturn(item(Material.AIR));
    manager.onInventoryClick(e);
    var input = item(Material.DIAMOND);
    when(escrow.getEscrow(player.getUniqueId())).thenReturn(input);
    when(chain.resolve(input)).thenReturn(RecycleResult.notHandled());
    manager.onInventoryClick(e);
    when(chain.resolve(input))
        .thenReturn(RecycleResult.of("zero", List.of(), new RecycleContext(1, 1, 1)));
    manager.onInventoryClick(e);
    verify(escrow, never()).clearEscrow(any());
    Cache.blockConfirmWhenZeroYield = false;
    try (var effects = mockStatic(StationCompleteEffects.class)) {
      manager.onInventoryClick(e);
      verify(escrow).clearEscrow(player.getUniqueId());
    }
    open();
    var session = manager.getOrCreateSession(player);
    var loc = new Location(player.getWorld(), 4, 5, 6);
    session.setStationLocation(loc);
    when(chain.resolve(input))
        .thenReturn(
            RecycleResult.of(
                "test", List.of(new RecycleOutput("gem", 1)), new RecycleContext(1, 1, 1)));
    when(api.getCreator().getItemFromPath("gem")).thenAnswer(i -> item(Material.EMERALD));
    Cache.blockConfirmWhenZeroYield = true;
    Cache.resultSpawnStaggerTicks = 0;
    try (var effects = mockStatic(StationCompleteEffects.class);
        var spawn = mockStatic(ResultSpawnEffects.class)) {
      manager.onInventoryClick(click(0));
      assertTrue(session.isConfirmed());
      server.getScheduler().performTicks(3);
      spawn.verify(() -> ResultSpawnEffects.spawnAtStation(eq(loc), any(ItemStack.class)));
      verify(escrow, times(2)).clearEscrow(player.getUniqueId());
    }
  }

  @Test
  void dragCloseQuitAndJoinRespectSessionState() {
    var drag = mock(InventoryDragEvent.class);
    when(drag.getWhoClicked()).thenReturn(mock(HumanEntity.class));
    manager.onInventoryDrag(drag);
    when(drag.getWhoClicked()).thenReturn(player);
    player.openInventory(server.createInventory(null, 9));
    when(drag.getView()).thenAnswer(i -> player.getOpenInventory());
    manager.onInventoryDrag(drag);
    open();
    manager.onInventoryDrag(drag);
    verify(drag).setCancelled(true);
    var close = mock(InventoryCloseEvent.class);
    when(close.getPlayer()).thenReturn(mock(HumanEntity.class));
    manager.onInventoryClose(close);
    when(close.getPlayer()).thenReturn(player);
    when(close.getInventory()).thenReturn(server.createInventory(null, 9));
    manager.onInventoryClose(close);
    when(close.getInventory()).thenReturn(player.getOpenInventory().getTopInventory());
    manager.onInventoryClose(close);
    verify(escrow).returnEscrow(player);
    var session = manager.getOrCreateSession(player);
    session.setConfirmed(true);
    manager.onInventoryClose(close);
    verify(escrow).returnEscrow(player);
    session.setConfirmed(false);
    manager.onInventoryClose(close);
    verify(escrow, times(2)).returnEscrow(player);
    var quit = new PlayerQuitEvent(player, "quit");
    manager.onQuit(quit);
    manager.getOrCreateSession(player).setConfirmed(true);
    manager.onQuit(quit);
    manager.getOrCreateSession(player);
    manager.onQuit(quit);
    verify(escrow, times(3)).returnEscrow(player);
    manager.onJoin(new PlayerJoinEvent(player, "join"));
    verify(escrow).deliverPending(player);
  }
}
