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
  void chancePreviewShowsPossibleAmountAndPreservesExistingLore() throws Exception {
    var chain = mock(RecycleProviderChain.class);
    var manager = new InventoryManager(chain);
    var player = server.addPlayer();
    var session = new RecycleSession(player.getUniqueId(), token(player));
    var escrow = mock(EscrowManager.class);
    var input = item(Material.IRON_NUGGET);
    when(escrow.getEscrow(session.getToken().owner())).thenReturn(input);
    when(chain.resolve(input)).thenReturn(RecycleResult.of("scrap",
        List.of(new RecycleOutput("gem", 1, .01)), new RecycleContext(.5, 1, 1)));
    manager.openMain(player, session);
    when(plugin.getResource("messages.yml"))
        .thenAnswer(x -> getClass().getResourceAsStream("/messages.yml"));
    Messages.load(dir.resolve("absent.yml").toFile());
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
      var lore = shown.getItemMeta().getLore();
      assertEquals(hasLore ? 4 : 3, lore.size());
      assertEquals("", lore.get(lore.size() - 3));
      assertEquals("§7Each §fDiamond §7has a §x§f§0§c§7§5§e1% §7chance", lore.get(lore.size() - 2));
      assertEquals("§7to be recovered §8(up to 1)", lore.getLast());
    }
  }

  @Test
  void chanceLoreUsesTheConfiguredLinesAndTheItemsOwnName() throws Exception {
    var chain = mock(RecycleProviderChain.class);
    var manager = new InventoryManager(chain);
    var player = server.addPlayer();
    var session = new RecycleSession(player.getUniqueId(), token(player));
    var escrow = mock(EscrowManager.class);
    var input = item(Material.IRON_NUGGET);
    when(escrow.getEscrow(session.getToken().owner())).thenReturn(input);
    when(chain.resolve(input)).thenReturn(RecycleResult.of("scrap",
        List.of(new RecycleOutput("steel", 70, .255)), new RecycleContext(.5, 1, 1)));
    when(api.getCreator().getItemFromPath("steel")).thenAnswer(x -> {
      var steel = item(Material.IRON_INGOT);
      var meta = steel.getItemMeta();
      meta.setDisplayName("§x§7§f§7§d§8§0Steel %amount% $1 Ingot");
      steel.setItemMeta(meta);
      return steel;
    });
    manager.openMain(player, session);
    var slot = GridLayout.previewSlots().getFirst();

    Messages.load(yaml("one-line.yml", "preview:\n  scrap_chance: \"%item% &#00FF00%chance% %amount%\"").toFile());
    manager.refreshPreview(player, session, escrow);
    var shown = player.getOpenInventory().getTopInventory().getItem(slot);
    assertEquals(64, shown.getAmount());
    // The name's own %amount% and $1 stay literal; only the template's placeholders are filled.
    assertEquals(List.of("§x§7§f§7§d§8§0Steel %amount% $1 Ingot §x§0§0§f§f§0§025.5% 70"), shown.getItemMeta().getLore());

    Messages.load(yaml("none.yml", "preview:\n  scrap_chance: []").toFile());
    manager.refreshPreview(player, session, escrow);
    assertFalse(player.getOpenInventory().getTopInventory().getItem(slot).getItemMeta().hasLore());
  }

  @Test
  void itemNamesAndChanceFormatting() throws Exception {
    var named = item(Material.DIAMOND);
    var meta = named.getItemMeta();
    meta.setItemName("§bRuby");
    named.setItemMeta(meta);
    assertEquals("§bRuby", invoke(InventoryManager.class, "itemName", new Class<?>[] {org.bukkit.inventory.ItemStack.class}, named));
    assertEquals("§fIron Ingot", invoke(InventoryManager.class, "itemName", new Class<?>[] {org.bukkit.inventory.ItemStack.class}, item(Material.IRON_INGOT)));
    assertEquals("50%", invoke(InventoryManager.class, "formatChance", new Class<?>[] {double.class}, .5));
    assertEquals("1%", invoke(InventoryManager.class, "formatChance", new Class<?>[] {double.class}, .01));
    assertEquals("7%", invoke(InventoryManager.class, "formatChance", new Class<?>[] {double.class}, .07));
    assertEquals("25.5%", invoke(InventoryManager.class, "formatChance", new Class<?>[] {double.class}, .255));
  }
  @Test
  void shellPreviewReplacementAndCapacityLimit() throws Exception {
    var chain = mock(RecycleProviderChain.class);
    var manager = new InventoryManager(chain);
    var player = server.addPlayer();
    var session = new RecycleSession(player.getUniqueId(), token(player));
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
    when(escrow.getEscrow(session.getToken().owner())).thenReturn(input);
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
  @Test void latePreviewCannotAddressReplacementMenuAndUnreadableInputStaysHeld() throws Exception {
    var chain=mock(RecycleProviderChain.class);var manager=new InventoryManager(chain);var player=server.addPlayer();
    var token=token(player);var session=new RecycleSession(player.getUniqueId(),token);var escrow=mock(EscrowManager.class);
    var foreign=new RecycleSession(player.getUniqueId(),new net.tfminecraft.recycler.ownership.QueueIdentity.Token(token.owner(),token.session()+1));
    manager.openMain(player,foreign);manager.refreshPreview(player,session,escrow);verifyNoInteractions(escrow);
    manager.openMain(player,session);when(escrow.getEscrow(token.owner())).thenThrow(new java.io.IOException("held"));manager.refreshPreview(player,session,escrow);
    assertNull(player.getOpenInventory().getTopInventory().getItem(GridLayout.SLOT_INPUT));
  }
}
