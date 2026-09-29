package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.recycler.util.*;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;

class EffectsTest extends TestSupport {
  @Test
  void equalVelocityBoundsProduceConfiguredKickWithoutLosingOutput() {
    var world = mock(World.class);
    var entity = mock(Item.class);
    when(world.dropItem(any(Location.class), any())).thenReturn(entity);
    Cache.resultSpawnKickHorizontalMin = Cache.resultSpawnKickHorizontalMax = 0;
    Cache.resultSpawnKickVelocityMin = Cache.resultSpawnKickVelocityMax = .2;
    assertTrue(
        ResultSpawnEffects.spawnAtStation(new Location(world, 1, 2, 3), item(Material.DIAMOND)));
    verify(entity).setVelocity(new org.bukkit.util.Vector(0, .2, 0));
  }

  @Test
  void outputSpawnGuardsNamesTrailsAndParticleSettings() throws Exception {
    var world = mock(World.class);
    var entity = mock(Item.class);
    var loc = new Location(world, 1, 2, 3);
    when(world.dropItem(any(Location.class), any())).thenReturn(entity);
    when(entity.getLocation()).thenReturn(loc);
    when(entity.isValid()).thenReturn(true);
    assertFalse(ResultSpawnEffects.spawnAtStation(null, item(Material.DIAMOND)));
    assertFalse(
        ResultSpawnEffects.spawnAtStation(new Location(null, 0, 0, 0), item(Material.DIAMOND)));
    assertFalse(ResultSpawnEffects.spawnAtStation(loc, null));
    assertFalse(ResultSpawnEffects.spawnAtStation(loc, item(Material.AIR)));
    Cache.resultSpawnTrailTicks = 2;
    Cache.resultSpawnTrailIntervalTicks = 0;
    assertTrue(ResultSpawnEffects.spawnAtStation(loc, item(Material.DIAMOND)));
    server.getScheduler().performTicks(4);
    verify(entity).setCustomName("§f1x Diamond");
    verify(entity).setCustomNameVisible(true);
    var noMeta = mock(org.bukkit.inventory.ItemStack.class);
    when(noMeta.getType()).thenReturn(Material.STONE);
    when(noMeta.clone()).thenReturn(noMeta);
    ResultSpawnEffects.spawnAtStation(loc, noMeta);
    var named = item(Material.IRON_INGOT);
    var meta = named.getItemMeta();
    meta.setDisplayName("Metal");
    named.setItemMeta(meta);
    Cache.resultSpawnBurstParticles = false;
    assertTrue(ResultSpawnEffects.spawnAtStation(loc, named));
    when(entity.isDead()).thenReturn(true);
    server.getScheduler().performTicks(1);
    verify(entity).setCustomName("§f1x Metal");
    when(entity.isDead()).thenReturn(false);
    when(entity.isValid()).thenReturn(false);
    ResultSpawnEffects.spawnAtStation(loc, named);
    server.getScheduler().performTicks(1);
  }

  @Test
  void completionEffectsGuardParseAndDelayedOnlineChecks() {
    var player = mock(Player.class);
    var world = mock(World.class);
    var loc = new Location(world, 0, 0, 0);
    when(player.getLocation()).thenReturn(loc);
    StationCompleteEffects.play(null, loc);
    StationCompleteEffects.play(player, null);
    StationCompleteEffects.play(player, new Location(null, 0, 0, 0));
    Cache.stationCompleteExtraSoundDelayTicks = 0;
    StationCompleteEffects.play(player, loc);
    Cache.stationCompleteExtraSoundDelayTicks = 2;
    for (String value : Arrays.asList(null, " ", " none ")) {
      Cache.stationCompleteExtraSound = value;
      StationCompleteEffects.play(player, loc);
    }
    Cache.stationCompleteExtraSound = "BLOCK_ANVIL_USE";
    Cache.stationCompleteParticle = "invalid";
    StationCompleteEffects.play(player, loc);
    server.getScheduler().performTicks(3);
    when(player.isOnline()).thenReturn(true);
    Cache.stationCompleteParticle = null;
    Cache.stationCompleteExtraParticle = "none";
    StationCompleteEffects.play(player, loc);
    server.getScheduler().performTicks(3);
    Cache.stationCompleteParticle = "CRIT";
    Cache.stationCompleteExtraParticle = "END_ROD";
    StationCompleteEffects.play(player, loc);
    server.getScheduler().performTicks(3);
    verify(world, atLeastOnce())
        .spawnParticle(
            eq(Particle.END_ROD),
            any(Location.class),
            anyInt(),
            anyDouble(),
            anyDouble(),
            anyDouble(),
            anyDouble());
  }

  @Test
  void delayedBurstSkipsAWorldThatIsNoLongerAvailable() throws Exception {
    invoke(
        StationCompleteEffects.class,
        "spawnBurst",
        new Class[] {Location.class, String.class, int.class, double.class},
        new Location(null, 0, 0, 0),
        "CRIT",
        1,
        .2);
  }

  @Test
  void horizontalKickSamplesBothSignsWithoutChangingMagnitude() throws Exception {
    var random = mock(java.util.concurrent.ThreadLocalRandom.class);
    when(random.nextDouble(.1, .3)).thenReturn(.2);
    when(random.nextBoolean()).thenReturn(true, false);
    var types =
        new Class[] {java.util.concurrent.ThreadLocalRandom.class, double.class, double.class};
    assertEquals(.2, invoke(ResultSpawnEffects.class, "randomSigned", types, random, .1, .3));
    assertEquals(-.2, invoke(ResultSpawnEffects.class, "randomSigned", types, random, .1, .3));
  }
}
