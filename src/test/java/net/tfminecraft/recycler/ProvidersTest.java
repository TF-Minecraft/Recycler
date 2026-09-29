package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import net.tfminecraft.advancedcrafting.loaders.IngredientLoader;
import net.tfminecraft.advancedcrafting.managers.AlloyManager;
import net.tfminecraft.advancedcrafting.objects.alloys.Alloy;
import net.tfminecraft.advancedcrafting.objects.data.*;
import net.tfminecraft.advancedcrafting.objects.ingredients.Ingredient;
import net.tfminecraft.gunsandgadgets.guns.data.GunCraftProvenance;
import net.tfminecraft.gunsandgadgets.guns.parts.GunPart;
import net.tfminecraft.gunsandgadgets.utils.*;
import net.tfminecraft.recycler.loader.*;
import net.tfminecraft.recycler.model.*;
import net.tfminecraft.recycler.provider.*;
import org.bukkit.*;
import org.junit.jupiter.api.Test;

class ProvidersTest extends TestSupport {
  @Test
  void advancedCraftingResolvesLiveIngredientsAlloysAndMissingDefinitions() {
    var provider = new AdvancedCraftingProvider();
    assertEquals(10, provider.priority());
    assertTrue(provider.appliesMaxReturnRate());
    assertEquals(Cache.maxReturnRate, provider.returnRate());
    var stack = item(Material.IRON_SWORD);
    try (var prov = mockStatic(CraftProvenance.class);
        var ingredients = mockStatic(IngredientLoader.class);
        var alloys = mockStatic(AlloyManager.class)) {
      assertFalse(provider.canHandle(stack));
      assertTrue(provider.resolveBaseOutputs(stack).isEmpty());
      var inputs = new ArrayList<CraftInput>();
      inputs.add(new CraftInput(null, "x", 1, 0));
      inputs.add(new CraftInput("other", "x", 1, 0));
      for (String id : Arrays.asList(null, "", " ", "missing", "nullpath", "blankpath", "iron"))
        inputs.add(new CraftInput("ingredient", id, 2, 0));
      inputs.add(new CraftInput("ingredient", "iron", 0, 0));
      for (String id : Arrays.asList(null, "missing", "nodata", "norecipe", "steel"))
        inputs.add(new CraftInput("alloy", id, 3, 0));
      inputs.add(new CraftInput("alloy", "steel", 0, 0));
      prov.when(() -> CraftProvenance.readFrom(stack))
          .thenReturn(new CraftProvenance("recipe", "quality", inputs, 0));
      Ingredient iron = mock(Ingredient.class),
          np = mock(Ingredient.class),
          blank = mock(Ingredient.class);
      when(iron.getPath()).thenReturn("v.iron_ingot");
      when(blank.getPath()).thenReturn(" ");
      ingredients.when(() -> IngredientLoader.getByString("iron")).thenReturn(iron);
      ingredients.when(() -> IngredientLoader.getByString("nullpath")).thenReturn(np);
      ingredients.when(() -> IngredientLoader.getByString("blankpath")).thenReturn(blank);
      var noData = mock(Alloy.class);
      alloys.when(() -> AlloyManager.getAlloyById("nodata")).thenReturn(noData);
      var noRecipe = mock(Alloy.class, RETURNS_DEEP_STUBS);
      when(noRecipe.getData().getRecipe()).thenReturn(null);
      alloys.when(() -> AlloyManager.getAlloyById("norecipe")).thenReturn(noRecipe);
      var steel = mock(Alloy.class, RETURNS_DEEP_STUBS);
      when(steel.getData().getRecipe()).thenReturn(new AlloyRecipe("iron", List.of("iron")));
      alloys.when(() -> AlloyManager.getAlloyById("steel")).thenReturn(steel);
      assertTrue(provider.canHandle(stack));
      assertEquals(
          List.of(new RecycleOutput("v.iron_ingot", 8)), provider.resolveBaseOutputs(stack));
    }
  }

  @Test
  void scrapUsesIndependentRateAndRejectsMissingBasePath() {
    var provider = new AlloyScrapProvider();
    assertEquals(11, provider.priority());
    assertEquals(Cache.scrapReturnRate, provider.returnRate());
    var stack = item(Material.IRON_NUGGET);
    try (var prov = mockStatic(ScrapProvenance.class);
        var ingredients = mockStatic(IngredientLoader.class)) {
      assertFalse(provider.canHandle(stack));
      assertTrue(provider.resolveBaseOutputs(stack).isEmpty());
      prov.when(() -> ScrapProvenance.readBaseId(stack)).thenReturn(" ");
      assertTrue(provider.resolveBaseOutputs(stack).isEmpty());
      prov.when(() -> ScrapProvenance.readBaseId(stack)).thenReturn("iron");
      assertTrue(provider.canHandle(stack));
      assertTrue(provider.resolveBaseOutputs(stack).isEmpty());
      var ingredient = mock(Ingredient.class);
      ingredients.when(() -> IngredientLoader.getByString("iron")).thenReturn(ingredient);
      assertTrue(provider.resolveBaseOutputs(stack).isEmpty());
      when(ingredient.getPath()).thenReturn(" ");
      assertTrue(provider.resolveBaseOutputs(stack).isEmpty());
      when(ingredient.getPath()).thenReturn("v.iron_ingot");
      assertEquals(
          List.of(new RecycleOutput("v.iron_ingot", 1)), provider.resolveBaseOutputs(stack));
    }
  }

  @Test
  void gunsRequireIntactManagedProvenanceAndMergeLivePositiveCosts() {
    var provider = new GunsAndGadgetsProvider();
    assertEquals(20, provider.priority());
    var stack = item(Material.CROSSBOW);
    try (var managed = mockStatic(GunStatRefresher.class);
        var broken = mockStatic(GunBrokenMarker.class);
        var provenance = mockStatic(GunCraftProvenance.class)) {
      assertFalse(provider.canHandle(stack));
      assertTrue(provider.resolveBaseOutputs(stack).isEmpty());
      managed.when(() -> GunStatRefresher.isManaged(stack)).thenReturn(true);
      broken.when(() -> GunBrokenMarker.isBroken(stack)).thenReturn(true);
      assertFalse(provider.canHandle(stack));
      broken.when(() -> GunBrokenMarker.isBroken(stack)).thenReturn(false);
      assertFalse(provider.canHandle(stack));
      var prov = mock(GunCraftProvenance.class);
      provenance.when(() -> GunCraftProvenance.readFrom(stack)).thenReturn(prov);
      when(prov.resolveStampedParts())
          .thenReturn(new GunCraftProvenance.ResolvedParts(List.of(), List.of("missing")));
      assertFalse(provider.canHandle(stack));
      assertTrue(provider.resolveBaseOutputs(stack).isEmpty());
      var part = mock(GunPart.class);
      when(part.getCost())
          .thenReturn(new HashMap<>(Map.of("v.iron", 2, "zero", 0, "negative", -1)));
      when(prov.resolveStampedParts())
          .thenReturn(new GunCraftProvenance.ResolvedParts(List.of(part, part), List.of()));
      assertTrue(provider.canHandle(stack));
      assertEquals(List.of(new RecycleOutput("v.iron", 4)), provider.resolveBaseOutputs(stack));
    }
  }

  @Test
  void configFallbackUsesYamlQuantitiesAndChainSelectsFirstYieldingProvider() throws Exception {
    var config = new ConfigProvider();
    assertEquals(Integer.MAX_VALUE, config.priority());
    assertFalse(config.appliesMaxReturnRate());
    assertEquals(1, config.returnRate());
    var stack = item(Material.STONE);
    new RecipeLoader().loadFolder(dir.toFile());
    assertFalse(config.canHandle(stack));
    assertTrue(config.resolveBaseOutputs(stack).isEmpty());
    yaml("recipes.yml", "recipes:\n  x:\n    input: v.stone\n    outputs: ['v.diamond 2']");
    new RecipeLoader().loadFolder(dir.toFile());
    when(api.getChecker().checkItemWithPath(stack, "v.stone")).thenReturn(true);
    assertTrue(config.canHandle(stack));
    assertEquals(List.of(new RecycleOutput("v.diamond", 2)), config.resolveBaseOutputs(stack));
    RecipeLoader.getOutputsForInput("v.stone").put("zero", 0);
    assertEquals(1, config.resolveBaseOutputs(stack).size());
    var chain = new RecycleProviderChain();
    chain.rebuild();
    assertFalse(chain.resolve(null).isHandled());
    assertFalse(chain.resolve(item(Material.AIR)).isHandled());
    assertEquals("ConfigProvider", chain.resolve(stack).getProviderId());
    assertFalse(chain.resolve(item(Material.DIRT)).isHandled());
    for (String name : List.of("AdvancedCrafting", "Magic", "GunsAndGadgets", "GemInfusion"))
      org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(name);
    chain.rebuild();
    var field = RecycleProviderChain.class.getDeclaredField("providers");
    field.setAccessible(true);
    @SuppressWarnings("unchecked")
    var providers = (List<RecycleProvider>) field.get(chain);
    assertEquals(
        List.of(10, 11, 15, 20, 25, Integer.MAX_VALUE),
        providers.stream().map(RecycleProvider::priority).toList());
    providers.clear();
    var empty = mock(RecycleProvider.class);
    when(empty.canHandle(stack)).thenReturn(true);
    when(empty.resolveBaseOutputs(stack)).thenReturn(List.of());
    providers.add(empty);
    providers.add(config);
    assertEquals("ConfigProvider", chain.resolve(stack).getProviderId());
  }
}
