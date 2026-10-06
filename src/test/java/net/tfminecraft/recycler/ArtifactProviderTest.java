package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.magic.artifact.*;
import net.tfminecraft.recycler.model.*;
import net.tfminecraft.recycler.provider.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;

class ArtifactProviderTest extends TestSupport {
  private static final NamespacedKey RARITY = new NamespacedKey("magic", "artifact_rarity");

  private ItemStack artifact(String rarity) {
    var stack = item(Material.BLAZE_ROD);
    if (rarity != null) {
      var meta = stack.getItemMeta();
      meta.getPersistentDataContainer().set(RARITY, PersistentDataType.STRING, rarity);
      stack.setItemMeta(meta);
    }
    return stack;
  }

  @Test
  void artifactsReturnDustByRarityAndMinMuffleGatesThem() {
    var p = new ArtifactProvider();
    assertEquals(16, p.priority());
    assertEquals(Cache.artifactReturnRate, p.returnRate());
    var plain = item(Material.STICK);
    var legendary = artifact(" Legendary ");
    var unknown = artifact("mythic");
    var unrecorded = artifact(null);
    try (var art = mockStatic(Artifact.class);
        var keys = mockStatic(ArtifactKeys.class);
        var care = mockStatic(ArtifactCareStore.class)) {
      keys.when(ArtifactKeys::artifactRarity).thenReturn(RARITY);
      var found = mock(Artifact.class);
      for (var stack : List.of(legendary, unknown, unrecorded))
        art.when(() -> Artifact.fromItem(stack)).thenReturn(found);
      assertFalse(p.canHandle(plain));
      assertTrue(p.resolveBaseOutputs(plain).isEmpty());
      assertTrue(p.canHandle(legendary), "An unmuffled artifact passes the default min_muffle 0");
      Cache.artifactMinMuffle = 1.0;
      care.when(() -> ArtifactCareStore.readMuffle(legendary)).thenReturn(0.5);
      assertFalse(p.canHandle(legendary), "Below min_muffle is refused");
      assertTrue(p.refuses(legendary));
      assertFalse(p.refuses(plain), "Only artifacts are refused");
      care.when(() -> ArtifactCareStore.readMuffle(legendary)).thenReturn(1.0);
      assertTrue(p.canHandle(legendary), "Fully muffled meets min_muffle 1.0");
      assertEquals(
          List.of(new RecycleOutput("m.currency.enchanted_dust", 7)),
          p.resolveBaseOutputs(legendary));
      assertEquals(
          List.of(new RecycleOutput("m.currency.enchanted_dust", 1)),
          p.resolveBaseOutputs(unknown),
          "A rarity not in the table uses default");
      assertEquals(
          List.of(new RecycleOutput("m.currency.enchanted_dust", 1)),
          p.resolveBaseOutputs(unrecorded),
          "Lore-only artifacts with no rarity use default");
      Cache.artifactRarityReturns = Map.of("legendary", 0);
      assertTrue(p.resolveBaseOutputs(legendary).isEmpty());
      Cache.artifactReturnItem = " ";
      assertTrue(p.resolveBaseOutputs(unknown).isEmpty());
    }
  }

  @Test
  void chainRefusesArtifactsBelowMinMuffleEvenWhenARecipeMatches() throws Exception {
    yaml("recipes.yml", "recipes:\n  rod:\n    input: v.blaze_rod\n    outputs: ['v.stick 1']");
    new net.tfminecraft.recycler.loader.RecipeLoader().loadFolder(dir.toFile());
    var stack = artifact("rare");
    when(api.getChecker().checkItemWithPath(stack, "v.blaze_rod")).thenReturn(true);
    org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin("Magic");
    var chain = new RecycleProviderChain();
    chain.rebuild();
    try (var art = mockStatic(Artifact.class);
        var keys = mockStatic(ArtifactKeys.class);
        var care = mockStatic(ArtifactCareStore.class);
        var gear = mockStatic(net.tfminecraft.magic.gear.GearProvenance.class)) {
      keys.when(ArtifactKeys::artifactRarity).thenReturn(RARITY);
      art.when(() -> Artifact.fromItem(stack)).thenReturn(mock(Artifact.class));
      Cache.artifactMinMuffle = 1.0;
      care.when(() -> ArtifactCareStore.readMuffle(stack)).thenReturn(0.5);
      assertFalse(chain.resolve(stack).isHandled(), "The recipe file cannot bypass min_muffle");
      care.when(() -> ArtifactCareStore.readMuffle(stack)).thenReturn(1.0);
      var result = chain.resolve(stack);
      assertEquals("ArtifactProvider", result.getProviderId());
      assertEquals(
          List.of(new RecycleOutput("m.currency.enchanted_dust", 3)), result.getOutputs());
      Cache.artifactRarityReturns = Map.of("rare", 0);
      assertFalse(
          chain.resolve(stack).isHandled(), "A rarity set to 0 is refused, not sent to recipes");
    }
  }
}
