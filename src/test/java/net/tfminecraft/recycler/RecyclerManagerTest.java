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
  void managers() throws Exception {
    escrow = mock(EscrowManager.class);
    inventories = mock(InventoryManager.class);
    chain = mock(RecycleProviderChain.class);
    manager = new RecyclerManager(escrow, inventories, chain);
    player = server.addPlayer();
    when(escrow.token(player)).thenAnswer(call -> token(player));
    when(escrow.current(any(), eq(player))).thenAnswer(call -> call.getArgument(0).equals(token(player)));
    doAnswer(call -> { int slot = call.getArgument(3); player.getInventory().setItem(slot, null); return null; }).when(escrow).deposit(any(), any(), any(), anyInt(), any());
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
    var holder = new RecyclerGuiHolder(manager.getOrCreateSession(player).getToken());
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
  void clickGuardsAndInputCancellation() throws Exception {
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
    when(escrow.getEscrow(token(player).owner())).thenReturn(item(Material.DIAMOND));
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
  void depositsRejectPoliciesOrUnknownItemsAndReplaceEscrow() throws Exception {
    open();
    var e = click(27);
    var stack = new ItemStack(Material.DIAMOND, 3);
    when(e.getCurrentItem()).thenReturn(stack);
    try (var guards = mockStatic(RecycleGuard.class)) {
      guards.when(() -> RecycleGuard.isBlocked(stack)).thenReturn(true);
      manager.onInventoryClick(e);
      verify(escrow, never()).deposit(any(), any(), any(), anyInt(), any());
      guards.when(() -> RecycleGuard.isBlocked(stack)).thenReturn(false);
      when(chain.resolve(stack)).thenReturn(RecycleResult.notHandled());
      manager.onInventoryClick(e);
      when(chain.resolve(stack))
          .thenReturn(RecycleResult.of("test", List.of(), new RecycleContext(1, 1, 1)));
      player.getInventory().setItem(0, stack);
      manager.onInventoryClick(e);
      assertNull(player.getInventory().getItem(0));
      var captured = token(player);
      verify(escrow).deposit(eq(player), eq(captured), eq(stack), eq(0), any());
      when(escrow.getEscrow(token(player).owner())).thenReturn(item(Material.DIAMOND));
      manager.onInventoryClick(e);
      verify(escrow, times(2)).deposit(eq(player), eq(captured), eq(stack), eq(0), any());
    }
  }

  @Test
  void confirmRequiresEscrowAndYieldThenCompletesExactlyOnce() throws Exception {
    open();
    var e = click(0);
    manager.onInventoryClick(e);
    when(escrow.getEscrow(token(player).owner())).thenReturn(item(Material.AIR));
    manager.onInventoryClick(e);
    var input = item(Material.DIAMOND);
    when(escrow.getEscrow(token(player).owner())).thenReturn(input);
    when(chain.resolve(input)).thenReturn(RecycleResult.notHandled());
    manager.onInventoryClick(e);
    when(chain.resolve(input))
        .thenReturn(RecycleResult.of("zero", List.of(), new RecycleContext(1, 1, 1)));
    manager.onInventoryClick(e);
    verify(escrow, never()).consume(any(), any());
    Cache.blockConfirmWhenZeroYield = false;
    try (var effects = mockStatic(StationCompleteEffects.class)) {
      manager.onInventoryClick(e);
      verify(escrow).consume(token(player).owner(), List.of());
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
      verify(escrow, times(2)).consume(eq(token(player).owner()), anyList());
    }
  }

  @Test
  void dragCloseQuitAndJoinRespectSessionState() throws Exception {
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
    verify(escrow).pending(token(player).owner());
    var session = manager.getOrCreateSession(player);
    session.setConfirmed(true);
    open();
    when(close.getInventory()).thenReturn(player.getOpenInventory().getTopInventory());
    manager.onInventoryClose(close);
    var quit = new PlayerQuitEvent(player, "quit");
    manager.onQuit(quit);
    manager.getOrCreateSession(player);
    manager.onQuit(quit);
    verify(escrow, times(2)).pending(token(player).owner());
    manager.onJoin(new PlayerJoinEvent(player, "join"));
    verify(escrow, times(2)).deliverPending(player);
  }
  @Test void staleMenusNeverRecaptureAndOutgoingEndUsesOriginalOwner() throws Exception {
    var first = manager.getOrCreateSession(player); open();
    assertFalse(manager.quiescent(first.getToken().owner()));
    assertTrue(manager.quiescent(token(server.addPlayer()).owner()));
    var other = new net.tfminecraft.recycler.ownership.QueueIdentity.Token(first.getToken().owner(), 2);
    manager.end(player, other); assertSame(first, manager.getOrCreateSession(player));
    when(escrow.token(player)).thenReturn(other);
    var replacement = manager.getOrCreateSession(player);
    assertEquals(other, replacement.getToken()); verify(escrow).pending(first.getToken().owner());
    open(); when(escrow.current(other,player)).thenReturn(false); manager.onInventoryClick(click(0));
    verify(escrow,never()).consume(any(),any());
    var oldHolder = new RecyclerGuiHolder(first.getToken()); var inv=server.createInventory(oldHolder,27); oldHolder.setInventory(inv); player.openInventory(inv);
    manager.onInventoryClick(click(0));
    var close=mock(InventoryCloseEvent.class);when(close.getPlayer()).thenReturn(player);when(close.getInventory()).thenReturn(inv);
    manager.onInventoryClose(close); assertFalse(manager.quiescent(other.owner()));
    var emptyHolder = new RecyclerGuiHolder();inv=server.createInventory(emptyHolder,27);emptyHolder.setInventory(inv);player.openInventory(inv);
    replacement.setConfirmed(true);manager.end(player,other);assertTrue(manager.quiescent(other.owner()));
    manager.end(player,other);manager.onInventoryClose(close);manager.onInventoryClick(click(0));
    manager.getOrCreateSession(player).setConfirmed(true);manager.onQuit(new PlayerQuitEvent(player,"quit"));
    manager.onQuit(new PlayerQuitEvent(player,"quit"));
    manager.getOrCreateSession(player);player.openInventory(server.createInventory(null,9));manager.end(player,other);
  }
  @Test void failedDepositRetriesSameOperationAndCommitRechecksAuthority() throws Exception {
    open(); var captured=token(player); var input=item(Material.DIAMOND); var event=click(27);when(event.getCurrentItem()).thenReturn(input);
    when(chain.resolve(input)).thenReturn(RecycleResult.of("test",List.of(),new RecycleContext(1,1,1)));
    doThrow(new java.io.IOException("disk")).when(escrow).deposit(any(),any(),any(),anyInt(),any());
    manager.onInventoryClick(event);manager.onInventoryClick(event);
    var ids=org.mockito.ArgumentCaptor.forClass(UUID.class);verify(escrow,times(2)).deposit(any(),any(),any(),anyInt(),ids.capture());
    assertEquals(ids.getAllValues().get(0),ids.getAllValues().get(1));
    doNothing().when(escrow).deposit(any(),any(),any(),anyInt(),any());manager.onInventoryClick(event);
    assertNotEquals(ids.getValue(),manager.getOrCreateSession(player).depositId());
    when(escrow.getEscrow(captured.owner())).thenThrow(new java.io.IOException("unreadable"));
    manager.onInventoryClick(click(0));manager.onInventoryClick(click(10));verify(escrow,never()).consume(any(),any());
    doReturn(input).when(escrow).getEscrow(captured.owner());Cache.blockConfirmWhenZeroYield=false;
    when(escrow.current(any(),eq(player))).thenReturn(true,false);manager.onInventoryClick(click(0));verify(escrow,never()).consume(any(),any());
    when(escrow.current(any(),eq(player))).thenReturn(true,false);manager.onInventoryClick(click(10));verify(escrow,never()).returnEscrow(player);
    when(escrow.current(any(),eq(player))).thenReturn(true);doThrow(new java.io.IOException("save")).when(escrow).consume(any(),any());
    manager.onInventoryClick(click(0));assertFalse(manager.getOrCreateSession(player).isConfirmed());
    doThrow(new java.io.IOException("queue")).when(escrow).pending(any());
    when(escrow.current(any(),eq(player))).thenReturn(false);
    var close=mock(InventoryCloseEvent.class);when(close.getPlayer()).thenReturn(player);when(close.getInventory()).thenReturn(player.getOpenInventory().getTopInventory());
    manager.onInventoryClose(close);verify(escrow,never()).deliverPending(player);assertTrue(manager.quiescent(captured.owner()));
  }
  @Test void stationWithoutLivingCharacterIsRejectedBeforeOpening() {
    var event=mock(PlayerInteractEvent.class);when(event.getPlayer()).thenReturn(player);when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);when(event.getHand()).thenReturn(EquipmentSlot.HAND);
    var block=mock(org.bukkit.block.Block.class);when(event.getClickedBlock()).thenReturn(block);
    var api=mock(net.tfminecraft.tlibs.objects.api.BlockAPI.class,RETURNS_DEEP_STUBS);tlibs.when(net.tfminecraft.tlibs.TLibs::getBlockAPI).thenReturn(api);
    when(api.getChecker().checkBlock(block,Cache.stationBlock)).thenReturn(true);Cache.stationPermission="";
    when(escrow.token(player)).thenThrow(new IllegalArgumentException("living character required"));manager.onStationInteract(event);
    verify(inventories,never()).openMain(any(),any());assertEquals("living character required",player.nextMessage());
  }
}
