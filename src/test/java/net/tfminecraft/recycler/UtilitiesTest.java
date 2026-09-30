package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.lumine.mythic.lib.api.item.NBTItem;
import java.util.*;
import net.tfminecraft.recycler.event.*;
import net.tfminecraft.recycler.model.*;
import net.tfminecraft.recycler.provider.*;
import net.tfminecraft.recycler.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.junit.jupiter.api.Test;

class UtilitiesTest extends TestSupport {
  @Test
  void layoutAndSessionCopiesAndCompletionEvent() {
    assertEquals(27, GridLayout.SIZE);
    assertEquals(26, GridLayout.slot(2, 8));
    assertEquals(15, GridLayout.previewSlots().size());
    for (int slot = 0; slot < 27; slot++)
      assertEquals(
          1,
          (GridLayout.isPreviewSlot(slot) ? 1 : 0)
              + (GridLayout.isReservedSlot(slot) ? 1 : 0)
              + (GridLayout.isFillerSlot(slot) ? 1 : 0));
    assertFalse(GridLayout.isFillerSlot(30));
    var id = UUID.randomUUID();
    var session = new RecycleSession(id);
    assertEquals(id, session.getPlayerId());
    assertFalse(session.isConfirmed());
    session.setConfirmed(true);
    assertTrue(session.isConfirmed());
    var loc = new Location(server.addSimpleWorld("world"), 1, 2, 3);
    session.setStationLocation(loc);
    assertEquals(loc, session.getStationLocation());
    var stack = item(Material.DIAMOND);
    assertNull(session.createDisplayCopy(null));
    assertEquals(stack, session.createDisplayCopy(stack));
    assertNotSame(stack, session.createDisplayCopy(stack));
    var player = server.addPlayer();
    var event =
        new RecycleCompleteEvent(
            player, stack, "config", List.of(new RecycleOutput("v.diamond", 2)), loc);
    assertSame(player, event.getPlayer());
    assertEquals("config", event.getProviderId());
    assertEquals(2, event.getOutputs().getFirst().baseAmount());
    assertNotSame(stack, event.getInput());
    assertNotSame(loc, event.getStationLocation());
    assertSame(event.getHandlers(), RecycleCompleteEvent.getHandlerList());
    var empty = new RecycleCompleteEvent(null, null, null, null, null);
    assertNull(empty.getInput());
    assertNull(empty.getStationLocation());
    assertEquals("", empty.getProviderId());
    assertTrue(empty.getOutputs().isEmpty());
    assertEquals("", new RecycleOutput(null, 0).itemPath());
  }

  @Test
  void itemReferenceNormalizationBuildsDefensiveCopies() {
    assertEquals("", ItemRef.normalize(null));
    assertEquals("", ItemRef.normalize(" "));
    assertEquals("v.iron_ingot", ItemRef.normalize(" VANILLA.IRON_INGOT "));
    assertEquals("m.X.Y", ItemRef.normalize(" m.X.Y "));
    assertNull(ItemRef.build(null));
    assertNull(ItemRef.build("missing"));
    when(api.getCreator().getItemFromPath("air")).thenReturn(item(Material.AIR));
    assertNull(ItemRef.build("air"));
    when(api.getCreator().getItemFromPath("bad")).thenThrow(new IllegalArgumentException());
    assertNull(ItemRef.build("bad"));
    var stack = item(Material.DIAMOND);
    when(api.getCreator().getItemFromPath("gem")).thenReturn(stack);
    assertEquals(stack, ItemRef.build("gem"));
    assertNotSame(stack, ItemRef.build("gem"));
    ItemRef.applyBlankDisplay(null);
    ItemRef.applyBlankDisplay(item(Material.AIR));
    ItemRef.applyBlankDisplay(stack);
    assertEquals("", stack.getItemMeta().getDisplayName());
    assertTrue(stack.getItemMeta().hasItemFlag(ItemFlag.HIDE_ATTRIBUTES));
  }

  @Test
  void scaledOutputsMergeRoundDownAndSplitStacks() {
    var ctx = new RecycleContext(-1, 2, 0);
    assertEquals(0, ctx.returnRate());
    assertEquals(1, ctx.durabilityFactor());
    assertEquals(1, ctx.stackAmount());
    assertEquals(1, RecycleContext.of(null, 1, 1).stackAmount());
    var stack = item(Material.DIAMOND);
    stack.setAmount(4);
    assertEquals(4, RecycleContext.of(stack, 1, -1).stackAmount());
    var no = RecycleResult.notHandled();
    assertFalse(no.isHandled());
    assertFalse(no.hasYield());
    assertEquals("", no.getProviderId());
    var result =
        RecycleResult.of(
            "test",
            List.of(
                new RecycleOutput("gem", 100),
                new RecycleOutput("gem", 160),
                new RecycleOutput("zero", 0),
                new RecycleOutput("missing", 2),
                new RecycleOutput("air", 2)),
            new RecycleContext(.5, 1, 1));
    assertTrue(result.isHandled());
    assertTrue(result.hasYield());
    assertEquals("test", result.getProviderId());
    assertEquals(
        130,
        result.getOutputs().stream()
            .filter(o -> o.itemPath().equals("gem"))
            .findFirst()
            .orElseThrow()
            .baseAmount());
    when(api.getCreator().getItemFromPath("gem")).thenAnswer(i -> item(Material.DIAMOND));
    when(api.getCreator().getItemFromPath("air")).thenReturn(item(Material.AIR));
    assertEquals(
        List.of(64, 64, 2), result.buildItemStacks().stream().map(ItemStack::getAmount).toList());
  }

  @Test
  void depositPoliciesWhitelistBlacklistAndUnbreakableItems() {
    assertTrue(RecycleGuard.isBlocked(null));
    assertTrue(RecycleGuard.isBlocked(item(Material.AIR)));
    var stack = item(Material.DIAMOND);
    Cache.depositWhitelistMode = false;
    Cache.depositBlacklistPaths = null;
    assertFalse(RecycleGuard.isBlocked(stack));
    Cache.depositWhitelistMode = true;
    assertTrue(RecycleGuard.isBlocked(stack));
    when(api.getChecker().getAsStringPath(stack)).thenReturn(" ");
    assertTrue(RecycleGuard.isBlocked(stack));
    when(api.getChecker().getAsStringPath(stack)).thenReturn("v.diamond");
    Cache.depositWhitelistPaths = List.of();
    assertTrue(RecycleGuard.isBlocked(stack));
    Cache.depositWhitelistPaths = Arrays.asList(null, " ", "v.iron", "m.*", " V.DIAMOND ");
    assertFalse(RecycleGuard.isBlocked(stack));
    Cache.depositWhitelistPaths = List.of("V.*");
    assertFalse(RecycleGuard.isBlocked(stack));
    Cache.depositWhitelistMode = false;
    Cache.depositBlacklistPaths = List.of("v.*");
    assertTrue(RecycleGuard.isBlocked(stack));
    Cache.depositBlacklistPaths = null;
    assertFalse(RecycleGuard.isBlocked(stack));
    var meta = stack.getItemMeta();
    meta.setUnbreakable(true);
    stack.setItemMeta(meta);
    assertTrue(RecycleGuard.isBlocked(stack));
    Cache.depositBlockUnbreakable = false;
    assertFalse(RecycleGuard.isBlocked(stack));
    meta.setUnbreakable(false);
    stack.setItemMeta(meta);
    Cache.depositBlockUnbreakable = true;
    assertFalse(RecycleGuard.isBlocked(stack));
  }

  @Test
  void giveOrDropPreservesOverflow() {
    var player = mock(Player.class);
    var inv = mock(PlayerInventory.class);
    var world = mock(World.class);
    var loc = new Location(world, 0, 0, 0);
    when(player.getInventory()).thenReturn(inv);
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(loc);
    ItemGive.giveOrDrop(null, item(Material.DIAMOND));
    ItemGive.giveOrDrop(player, null);
    ItemGive.giveOrDrop(player, item(Material.AIR));
    var stack = item(Material.DIAMOND);
    when(inv.addItem(stack)).thenReturn(new HashMap<>(Map.of(0, stack)));
    ItemGive.giveOrDrop(player, stack);
    verify(world).dropItemNaturally(loc, stack);
    when(inv.addItem(stack)).thenReturn(new HashMap<>());
    ItemGive.giveOrDrop(player, stack);
    verify(inv, times(2)).addItem(stack);
  }

  @Test
  void soundsNormalizeNamespacedKeysAndCatchPlaybackFailure() {
    assertNull(SoundKeys.normalize(null));
    assertEquals(" ", SoundKeys.normalize(" "));
    assertEquals("minecraft:block.anvil.use", SoundKeys.normalize(" BLOCK_ANVIL_USE "));
    assertEquals("custom:foo_bar", SoundKeys.normalize(" Custom:foo_bar "));
    assertEquals("custom:foo.bar", SoundKeys.normalize("Custom:FOO_BAR"));
    assertEquals("minecraft:foo", SoundKeys.normalize("FOO"));
    var player = mock(Player.class);
    SoundKeys.play(null, "x", 1, 1);
    SoundKeys.play(player, null, 1, 1);
    SoundKeys.play(player, " ", 1, 1);
    SoundKeys.play(player, "x", 1, 1);
    verify(player).playSound(nullable(Location.class), eq("minecraft:x"), eq(1f), eq(1f));
    doThrow(new IllegalArgumentException())
        .when(player)
        .playSound(nullable(Location.class), anyString(), anyFloat(), anyFloat());
    SoundKeys.play(player, "x", 1, 1);
    StationEffects.playOpen(player);
    StationEffects.playInputAccept(player);
    StationEffects.playInputReject(player);
    StationEffects.playPreviewRefresh(player);
    StationEffects.playCancel(player);
    StationEffects.playConfirm(player);
  }

  @Test
  void durabilityVanillaAndMmoFallbacks() {
    assertEquals(1, DurabilityScaler.factor(null));
    assertEquals(1, DurabilityScaler.factor(item(Material.DIAMOND)));
    var sword = item(Material.IRON_SWORD);
    var meta = (Damageable) sword.getItemMeta();
    meta.setDamage(100);
    sword.setItemMeta(meta);
    assertEquals(1 - 100d / 250, DurabilityScaler.factor(sword));
    meta.setDamage(250);
    sword.setItemMeta(meta);
    assertEquals(0, DurabilityScaler.factor(sword));
    var gem = item(Material.DIAMOND);
    var gm = gem.getItemMeta();
    gm.setDisplayName("gem");
    gem.setItemMeta(gm);
    assertEquals(1, DurabilityScaler.factor(gem));
    org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin("MMOItems");
    try (var api = mockStatic(NBTItem.class)) {
      var nbt = mock(NBTItem.class);
      api.when(() -> NBTItem.get(sword)).thenReturn(nbt);
      assertEquals(0, DurabilityScaler.factor(sword));
      when(nbt.hasTag("MMOITEMS_CUSTOM_DURABILITY")).thenReturn(true);
      assertEquals(0, DurabilityScaler.factor(sword));
      when(nbt.hasTag("MMOITEMS_MAX_DURABILITY")).thenReturn(true);
      assertEquals(1, DurabilityScaler.factor(sword));
      when(nbt.getDouble("MMOITEMS_MAX_DURABILITY")).thenReturn(100d);
      assertEquals(0, DurabilityScaler.factor(sword));
      when(nbt.getDouble("MMOITEMS_CUSTOM_DURABILITY")).thenReturn(50d);
      assertEquals(.5, DurabilityScaler.factor(sword));
      api.when(() -> NBTItem.get(sword)).thenThrow(new IllegalArgumentException());
      assertEquals(0, DurabilityScaler.factor(sword));
    }
  }

  @Test
  void plainMetadataAndPolicyMissesUseDocumentedDefaults() {
    var plain = mock(ItemStack.class);
    when(plain.getType()).thenReturn(Material.DIAMOND);
    assertEquals(1, DurabilityScaler.factor(plain));
    Cache.depositWhitelistMode = false;
    Cache.depositBlacklistPaths = List.of();
    assertFalse(RecycleGuard.isBlocked(plain));
    when(plain.hasItemMeta()).thenReturn(true);
    when(plain.getItemMeta()).thenReturn(mock(ItemMeta.class));
    assertEquals(1, DurabilityScaler.factor(plain));
    when(api.getChecker().getAsStringPath(plain)).thenReturn("v.diamond");
    Cache.depositBlacklistPaths = List.of("v.iron");
    assertFalse(RecycleGuard.isBlocked(plain));
  }
}
