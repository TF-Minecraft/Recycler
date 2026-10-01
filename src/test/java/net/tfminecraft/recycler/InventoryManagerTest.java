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
import org.junit.jupiter.api.Test;

class InventoryManagerTest extends TestSupport {
  @Test
  void chancePreviewShowsPossibleAmountAndPreservesExistingLore() {
    var chain = mock(RecycleProviderChain.class);
    var manager = new InventoryManager(chain);
    var player = server.addPlayer();
    var session = new RecycleSession(player.getUniqueId());
    var escrow = mock(EscrowManager.class);
    var input = item(Material.IRON_NUGGET);
    when(escrow.getEscrow(session.getPlayerId())).thenReturn(input);
    when(chain.resolve(input)).thenReturn(RecycleResult.of("scrap",
        List.of(new RecycleOutput("gem", 1, .01)), new RecycleContext(.5, 1, 1)));
    manager.openMain(player, session);
    for (boolean hasLore : List.of(false, true)) {
      when(api.getCreator().getItemFromPath("gem")).thenAnswer(x -> {
        var gem = item(Material.DIAMOND);
        var meta = gem.getItemMeta();
        if (hasLore) meta.setLore(List.of("Original lore"));
        gem.setItemMeta(meta);
        return gem;
      });
      manager.refreshPreview(player, session, escrow);
      var shown = player.getOpenInventory().getTopInventory().getItem(GridLayout.previewSlots().getFirst());
      assertEquals(1, shown.getAmount());
      assertEquals("Recovery chance: 1.0% per material", shown.getItemMeta().getLore().getLast());
      assertEquals(hasLore ? 2 : 1, shown.getItemMeta().getLore().size());
    }
  }
  @Test
  void shellPreviewReplacementAndCapacityLimit() {
    var chain = mock(RecycleProviderChain.class);
    var manager = new InventoryManager(chain);
    var player = server.addPlayer();
    var session = new RecycleSession(player.getUniqueId());
    var escrow = mock(EscrowManager.class);
    player.openInventory(server.createInventory(null, 9));
    manager.refreshPreview(player, session, escrow);
    manager.openMain(player, session);
    var inv = player.getOpenInventory().getTopInventory();
    assertInstanceOf(RecyclerGuiHolder.class, inv.getHolder());
    assertEquals(Material.GRAY_STAINED_GLASS_PANE, inv.getItem(1).getType());
    assertNull(inv.getItem(0));
    manager.refreshPreview(player, session, escrow);
    assertNull(inv.getItem(10));
    var input = item(Material.STONE);
    when(escrow.getEscrow(session.getPlayerId())).thenReturn(input);
    when(chain.resolve(input)).thenReturn(RecycleResult.notHandled());
    manager.refreshPreview(player, session, escrow);
    assertEquals(input, inv.getItem(10));
    when(chain.resolve(input))
        .thenReturn(RecycleResult.of("empty", List.of(), new RecycleContext(1, 1, 1)));
    manager.refreshPreview(player, session, escrow);
    var outputs = new ArrayList<RecycleOutput>();
    outputs.add(new RecycleOutput("missing", 2));
    outputs.add(new RecycleOutput("air", 2));
    for (int i = 0; i < 20; i++) {
      outputs.add(new RecycleOutput("v.diamond" + i, 65));
      when(api.getCreator().getItemFromPath("v.diamond" + i))
          .thenAnswer(x -> item(Material.DIAMOND));
    }
    when(api.getCreator().getItemFromPath("air")).thenReturn(item(Material.AIR));
    when(chain.resolve(input))
        .thenReturn(RecycleResult.of("test", outputs, new RecycleContext(1, 1, 1)));
    manager.refreshPreview(player, session, escrow);
    assertEquals(
        15,
        java.util.Arrays.stream(inv.getContents())
            .filter(s -> s != null && s.getType() == Material.DIAMOND)
            .count());
    assertEquals(64, inv.getItem(GridLayout.previewSlots().getFirst()).getAmount());
    when(api.getCreator().getItemFromPath(GuiCache.filler))
        .thenReturn(item(Material.BLACK_STAINED_GLASS_PANE));
    when(api.getCreator().getItemFromPath(GuiCache.confirmButton))
        .thenReturn(item(Material.EMERALD));
    when(api.getCreator().getItemFromPath(GuiCache.arrowRight)).thenReturn(item(Material.ARROW));
    manager.openMain(player, session);
    inv = player.getOpenInventory().getTopInventory();
    assertEquals(Material.EMERALD, inv.getItem(0).getType());
    for (int slot : GridLayout.ARROW_SLOTS)
      assertEquals(Material.ARROW, inv.getItem(slot).getType());
  }
}
