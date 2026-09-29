package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.lumine.mythic.lib.api.item.NBTItem;
import java.util.*;
import net.Indyuce.mmoitems.*;
import net.Indyuce.mmoitems.api.item.mmoitem.LiveMMOItem;
import net.Indyuce.mmoitems.stat.data.*;
import net.tfminecraft.geminfusion.goldsmith.*;
import net.tfminecraft.magic.gear.*;
import net.tfminecraft.recycler.model.*;
import net.tfminecraft.recycler.provider.*;
import org.bukkit.*;
import org.junit.jupiter.api.*;

class SocketProvidersTest extends TestSupport {
  @BeforeEach
  void integrations() {
    io.lumine.mythic.lib.MythicLib.plugin =
        mock(io.lumine.mythic.lib.MythicLib.class, RETURNS_DEEP_STUBS);
    when(io.lumine.mythic.lib.MythicLib.plugin.namespace()).thenReturn("mythiclib");
    MMOItems.plugin = mock(MMOItems.class, RETURNS_DEEP_STUBS);
    when(MMOItems.plugin.namespace()).thenReturn("mmoitems");
  }

  @AfterEach
  void clearIntegrations() {
    MMOItems.plugin = null;
    io.lumine.mythic.lib.MythicLib.plugin = null;
  }

  @Test
  void magicRejectsBrokenMissingAndSocketedGear() {
    var p = new MagicGearProvider();
    assertEquals(15, p.priority());
    var stack = item(Material.STICK);
    try (var prov = mockStatic(GearProvenance.class);
        var broken = mockStatic(GearBrokenMarker.class);
        var costs = mockStatic(GearCosts.class);
        var nbt = mockStatic(NBTItem.class)) {
      assertFalse(p.canHandle(stack));
      assertTrue(p.resolveBaseOutputs(stack).isEmpty());
      prov.when(() -> GearProvenance.isGear(stack)).thenReturn(true);
      broken.when(() -> GearBrokenMarker.isBroken(stack)).thenReturn(true);
      assertFalse(p.canHandle(stack));
      broken.when(() -> GearBrokenMarker.isBroken(stack)).thenReturn(false);
      prov.when(() -> GearProvenance.missingPartIds(stack)).thenReturn(List.of("missing"));
      assertFalse(p.canHandle(stack));
      prov.when(() -> GearProvenance.missingPartIds(stack)).thenReturn(List.of());
      nbt.when(() -> NBTItem.get(stack)).thenThrow(new IllegalArgumentException());
      assertFalse(p.canHandle(stack));
      nbt.when(() -> NBTItem.get(stack)).thenReturn(mock(NBTItem.class));
      try (var live = mockConstruction(LiveMMOItem.class)) {
        assertTrue(p.canHandle(stack));
      }
      try (var live =
          mockConstruction(
              LiveMMOItem.class,
              (m, c) -> {
                when(m.hasData(ItemStats.GEM_SOCKETS)).thenReturn(true);
                when(m.getData(ItemStats.GEM_SOCKETS)).thenReturn(new DoubleData(1));
              })) {
        assertTrue(p.canHandle(stack));
      }
      try (var live =
          mockConstruction(
              LiveMMOItem.class,
              (m, c) -> {
                when(m.hasData(ItemStats.GEM_SOCKETS)).thenReturn(true);
                var sockets = mock(GemSocketsData.class);
                when(m.getData(ItemStats.GEM_SOCKETS)).thenReturn(sockets);
              })) {
        assertTrue(p.canHandle(stack));
      }
      try (var live =
          mockConstruction(
              LiveMMOItem.class,
              (m, c) -> {
                when(m.hasData(ItemStats.GEM_SOCKETS)).thenReturn(true);
                var sockets = mock(GemSocketsData.class);
                when(sockets.getGems()).thenReturn(List.of(mock(GemstoneData.class)));
                when(m.getData(ItemStats.GEM_SOCKETS)).thenReturn(sockets);
              })) {
        assertFalse(p.canHandle(stack));
      }
      var map = new HashMap<String, Integer>();
      map.put("null", null);
      map.put("zero", 0);
      map.put("v.gold", 2);
      costs.when(() -> GearCosts.total(any())).thenReturn(map);
      assertEquals(List.of(new RecycleOutput("v.gold", 2)), p.resolveBaseOutputs(stack));
    }
  }

  @Test
  void jewelryRequiresOneMatchingProjectAndNoSocketedGems() {
    var p = new GoldsmithProvider();
    assertEquals(25, p.priority());
    var stack = item(Material.GOLD_NUGGET);
    try (var projects = mockStatic(JewelryProjectLoader.class);
        var nbt = mockStatic(NBTItem.class)) {
      LinkedHashMap<String, JewelryProject> registry = new LinkedHashMap<>();
      projects.when(JewelryProjectLoader::get).thenReturn(registry);
      assertFalse(p.canHandle(null));
      assertFalse(p.canHandle(item(Material.AIR)));
      assertFalse(p.canHandle(stack));
      assertTrue(p.resolveBaseOutputs(stack).isEmpty());
      registry.put("null", null);
      var project = mock(JewelryProject.class);
      registry.put("ring", project);
      assertFalse(p.canHandle(stack));
      when(project.getItem()).thenReturn(" ");
      assertFalse(p.canHandle(stack));
      when(project.getItem()).thenReturn("m.RING.X");
      assertFalse(p.canHandle(stack));
      when(api.getChecker().checkItemWithPath(stack, "m.RING.X"))
          .thenThrow(new IllegalArgumentException());
      assertFalse(p.canHandle(stack));
      var checker = api.getChecker();
      doReturn(true).when(checker).checkItemWithPath(stack, "m.RING.X");
      nbt.when(() -> NBTItem.get(stack)).thenThrow(new IllegalArgumentException());
      assertFalse(p.canHandle(stack));
      assertTrue(p.resolveBaseOutputs(stack).isEmpty());
      nbt.when(() -> NBTItem.get(stack)).thenReturn(mock(NBTItem.class));
      try (var live = mockConstruction(LiveMMOItem.class)) {
        assertTrue(p.canHandle(stack));
        var recipe = new LinkedHashMap<GoldsmithMaterial, Integer>();
        recipe.put(null, 1);
        var absent = mock(GoldsmithMaterial.class);
        recipe.put(absent, null);
        var zero = mock(GoldsmithMaterial.class);
        recipe.put(zero, 0);
        var noPath = mock(GoldsmithMaterial.class);
        recipe.put(noPath, 1);
        var blank = mock(GoldsmithMaterial.class);
        when(blank.getPath()).thenReturn(" ");
        recipe.put(blank, 1);
        var gold = mock(GoldsmithMaterial.class);
        when(gold.getPath()).thenReturn("v.gold_ingot");
        recipe.put(gold, 3);
        var gold2 = mock(GoldsmithMaterial.class);
        when(gold2.getPath()).thenReturn("v.gold_ingot");
        recipe.put(gold2, 2);
        when(project.getRecipe()).thenReturn(recipe);
        assertEquals(List.of(new RecycleOutput("v.gold_ingot", 5)), p.resolveBaseOutputs(stack));
        registry.put("duplicate", project);
        assertFalse(p.canHandle(stack));
        registry.remove("duplicate");
      }
      try (var live =
          mockConstruction(
              LiveMMOItem.class,
              (m, c) -> {
                when(m.hasData(ItemStats.GEM_SOCKETS)).thenReturn(true);
                when(m.getData(ItemStats.GEM_SOCKETS)).thenReturn(new DoubleData(1));
              })) {
        assertTrue(p.canHandle(stack));
      }
      try (var live =
          mockConstruction(
              LiveMMOItem.class,
              (m, c) -> {
                when(m.hasData(ItemStats.GEM_SOCKETS)).thenReturn(true);
                when(m.getData(ItemStats.GEM_SOCKETS)).thenReturn(mock(GemSocketsData.class));
              })) {
        assertTrue(p.canHandle(stack));
      }
      try (var live =
          mockConstruction(
              LiveMMOItem.class,
              (m, c) -> {
                when(m.hasData(ItemStats.GEM_SOCKETS)).thenReturn(true);
                var sockets = mock(GemSocketsData.class);
                when(sockets.getGems()).thenReturn(List.of(mock(GemstoneData.class)));
                when(m.getData(ItemStats.GEM_SOCKETS)).thenReturn(sockets);
              })) {
        assertFalse(p.canHandle(stack));
      }
    }
  }
}
