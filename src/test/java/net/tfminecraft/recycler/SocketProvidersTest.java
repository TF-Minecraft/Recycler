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

  private void assertSocketChecks(RecycleProvider p, org.bukkit.inventory.ItemStack stack) {
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

  @Test
  void magicReturnsRecordedInputsAndRejectsBrokenUnrecordedAndSocketedGear() {
    var p = new MagicGearProvider();
    assertEquals(15, p.priority());
    assertEquals(Cache.magicGearReturnRate, p.returnRate());
    var stack = item(Material.STICK);
    try (var prov = mockStatic(GearProvenance.class);
        var broken = mockStatic(GearBrokenMarker.class);
        var nbt = mockStatic(NBTItem.class)) {
      nbt.when(() -> NBTItem.get(stack)).thenReturn(mock(NBTItem.class));
      prov.when(() -> GearProvenance.readInputs(stack)).thenReturn(null);
      assertFalse(p.canHandle(stack));
      assertTrue(p.resolveBaseOutputs(stack).isEmpty());
      prov.when(() -> GearProvenance.isGear(stack)).thenReturn(true);
      broken.when(() -> GearBrokenMarker.isBroken(stack)).thenReturn(true);
      assertFalse(p.canHandle(stack));
      broken.when(() -> GearBrokenMarker.isBroken(stack)).thenReturn(false);
      assertFalse(p.canHandle(stack), "Weapons crafted before inputs were recorded are refused");
      prov.when(() -> GearProvenance.readInputs(stack)).thenReturn(Map.of("v.gold", 2, "zero", 0));
      nbt.when(() -> NBTItem.get(stack)).thenThrow(new IllegalArgumentException());
      assertFalse(p.canHandle(stack));
      nbt.when(() -> NBTItem.get(stack)).thenReturn(mock(NBTItem.class));
      assertSocketChecks(p, stack);
      assertEquals(List.of(new RecycleOutput("v.gold", 2)), p.resolveBaseOutputs(stack));
    }
  }

  @Test
  void jewelryReturnsDepositedMaterialsAndRejectsUnrecordedAndSocketedPieces() {
    var p = new GoldsmithProvider();
    assertEquals(25, p.priority());
    assertEquals(Cache.goldsmithReturnRate, p.returnRate());
    var stack = item(Material.GOLD_NUGGET);
    try (var prov = mockStatic(GoldsmithProvenance.class);
        var nbt = mockStatic(NBTItem.class)) {
      nbt.when(() -> NBTItem.get(stack)).thenReturn(mock(NBTItem.class));
      prov.when(() -> GoldsmithProvenance.read(stack)).thenReturn(null);
      assertFalse(p.canHandle(stack), "Jewelry made before inputs were recorded is refused");
      assertTrue(p.resolveBaseOutputs(stack).isEmpty());
      prov.when(() -> GoldsmithProvenance.read(stack))
          .thenReturn(Map.of("m.materials.shiny_gold", 4, "zero", 0));
      nbt.when(() -> NBTItem.get(stack)).thenThrow(new IllegalArgumentException());
      assertFalse(p.canHandle(stack));
      assertTrue(p.resolveBaseOutputs(stack).isEmpty());
      nbt.when(() -> NBTItem.get(stack)).thenReturn(mock(NBTItem.class));
      assertSocketChecks(p, stack);
      try (var live = mockConstruction(LiveMMOItem.class)) {
        assertEquals(
            List.of(new RecycleOutput("m.materials.shiny_gold", 4)), p.resolveBaseOutputs(stack));
      }
    }
  }
}
