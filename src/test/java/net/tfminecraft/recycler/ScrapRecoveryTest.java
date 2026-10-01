package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import net.tfminecraft.advancedcrafting.loaders.IngredientLoader;
import net.tfminecraft.advancedcrafting.objects.data.ScrapProvenance;
import net.tfminecraft.advancedcrafting.objects.ingredients.Ingredient;
import net.tfminecraft.recycler.loader.ConfigLoader;
import net.tfminecraft.recycler.model.RecycleOutput;
import net.tfminecraft.recycler.provider.*;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class ScrapRecoveryTest extends TestSupport {
  @Test
  void ratesAreConfigurableClampedAndResetOnReload() throws Exception {
    var loader = new ConfigLoader();
    loader.load(yaml("rates", "scrap_gem_return_rates:\n  default: 0.12\n  tiers:\n    '1': 0\n    '2': 2\n    '3': .NaN\n    '5': 0.9\n").toFile());
    assertEquals(Map.of("1", 0., "2", 1., "3", .5, "4", .75, "5", .9), Cache.scrapCatalystRates);
    assertEquals(.12, Cache.scrapCatalystDefaultRate);
    loader.load(yaml("rates", "{}").toFile());
    assertEquals(Map.of("1", .01, "2", .25, "3", .5, "4", .75), Cache.scrapCatalystRates);
    assertEquals(.01, Cache.scrapCatalystDefaultRate);
  }

  @Test
  void renamedRatesOverrideLegacyAndWhitelistResetsOnReload() throws Exception {
    var loader = new ConfigLoader();
    loader.load(yaml("rates", "scrap_gem_return_rates:\n  default: 0.9\nscrap_catalyst_return_rates:\n  default: 0.2\n  tiers:\n    '2': 0.4\n  whitelist_paths: [v.coal]\n").toFile());
    assertEquals(.2, Cache.scrapCatalystDefaultRate);
    assertEquals(.4, Cache.scrapCatalystRates.get("2"));
    assertEquals(List.of("v.coal"), Cache.scrapCatalystWhitelistPaths);
    loader.load(yaml("rates", "scrap_catalyst_return_rates:\n  whitelist_paths: []\n").toFile());
    assertTrue(Cache.scrapCatalystWhitelistPaths.isEmpty());
    loader.load(yaml("rates", "{}").toFile());
    assertEquals(List.of("m.gemstones.*"), Cache.scrapCatalystWhitelistPaths);
  }

  @Test
  void baseBypassesWhitelistAndCatalystsUseTierRatesOnlyWhenAllowed() {
    var scrap = item(Material.IRON_NUGGET);
    var gem = mock(Ingredient.class, RETURNS_DEEP_STUBS);
    var coal = mock(Ingredient.class, RETURNS_DEEP_STUBS);
    when(gem.getPath()).thenReturn("M.Gemstones.Ruby");
    when(gem.getIngredientData().getTier()).thenReturn(2);
    when(coal.getPath()).thenReturn("v.coal");
    when(coal.getIngredientData().getTier()).thenReturn(3);
    try (var provenance = mockStatic(ScrapProvenance.class);
         var ingredients = mockStatic(IngredientLoader.class)) {
      provenance.when(() -> ScrapProvenance.readBaseId(scrap)).thenReturn("GEM");
      provenance.when(() -> ScrapProvenance.readInputs(scrap)).thenReturn(Map.of("gem", 1, "coal", 2));
      ingredients.when(() -> IngredientLoader.getByString("gem")).thenReturn(gem);
      ingredients.when(() -> IngredientLoader.getByString("coal")).thenReturn(coal);
      var provider = new AlloyScrapProvider();
      var base = new RecycleOutput("M.Gemstones.Ruby", 1, Cache.scrapReturnRate);
      assertEquals(List.of(base), provider.resolveBaseOutputs(scrap));
      Cache.scrapCatalystWhitelistPaths = List.of("v.diamond", "m.gemstones.*", "V.COAL");
      assertEquals(Set.of(base, new RecycleOutput("v.coal", 2, .5)),
          new HashSet<>(provider.resolveBaseOutputs(scrap)));
      Cache.scrapCatalystWhitelistPaths = List.of();
      assertEquals(List.of(base), provider.resolveBaseOutputs(scrap));
      provenance.when(() -> ScrapProvenance.readBaseId(scrap)).thenReturn("coal");
      Cache.scrapCatalystWhitelistPaths = List.of("m.gemstones.*");
      assertEquals(Set.of(new RecycleOutput("v.coal", 2, Cache.scrapReturnRate),
          new RecycleOutput("M.Gemstones.Ruby", 1, .25)), new HashSet<>(provider.resolveBaseOutputs(scrap)));
    }
  }

  @Test
  void identicalScrapPreviewCanReturnDifferentMaterialsOnEachConfirmation() {
    var lines = List.of(new RecycleOutput("iron", 1, .5), new RecycleOutput("ruby", 1, .25));
    var ctx = new RecycleContext(1, 1, 1);
    var firstRolls = new ArrayDeque<>(List.of(.1, .9));
    var secondRolls = new ArrayDeque<>(List.of(.9, .1));
    assertEquals(List.of(new RecycleOutput("iron", 1)),
        RecycleResult.of("scrap", lines, ctx, true, firstRolls::remove).getOutputs());
    assertEquals(List.of(new RecycleOutput("ruby", 1)),
        RecycleResult.of("scrap", lines, ctx, true, secondRolls::remove).getOutputs());
    assertEquals(lines, RecycleResult.of("scrap", lines, ctx).getOutputs());
  }

  @Test
  void allRecordedGemsUseTheirTierIncludingMetalTypedGemsAndFutureTiers() {
    var scrap = item(Material.IRON_NUGGET);
    try (var provenance = mockStatic(ScrapProvenance.class);
         var ingredients = mockStatic(IngredientLoader.class)) {
      for (int tier : List.of(1, 2, 3, 4, 5)) {
        var gem = mock(Ingredient.class, RETURNS_DEEP_STUBS);
        when(gem.getPath()).thenReturn("m.gemstones.gem" + tier);
        when(gem.getIngredientData().getTier()).thenReturn(tier);
        ingredients.when(() -> IngredientLoader.getByString("gem")).thenReturn(gem);
        provenance.when(() -> ScrapProvenance.readInputs(scrap)).thenReturn(Map.of("gem", 3));
        double rate = Cache.scrapCatalystRates.getOrDefault(Integer.toString(tier), Cache.scrapCatalystDefaultRate);
        assertEquals(List.of(new RecycleOutput("m.gemstones.gem" + tier, 3, rate)),
            new AlloyScrapProvider().resolveBaseOutputs(scrap));
      }
    }
  }

  @Test
  void rollsEachMaterialUnitAcrossAmountsAndStacksWithoutFlooringOrRerollingPreview() {
    var lines = List.of(new RecycleOutput("iron", 1, .5), new RecycleOutput("ruby", 2, .75),
        new RecycleOutput("disabled", 1, 0), new RecycleOutput("guaranteed", 1, 1));
    var ctx = new RecycleContext(.5, 1, 2);
    var preview = RecycleResult.of("scrap", lines, ctx);
    assertEquals(List.of(new RecycleOutput("iron", 2, .5), new RecycleOutput("ruby", 4, .75),
        new RecycleOutput("guaranteed", 2, 1)), preview.getOutputs());
    var rolls = new ArrayDeque<>(List.of(.49, .5, .74, .75, .1, .9, .99, .99));
    var result = RecycleResult.of("scrap", lines, ctx, true, rolls::remove);
    assertEquals(List.of(new RecycleOutput("iron", 1), new RecycleOutput("ruby", 2),
        new RecycleOutput("guaranteed", 2)), result.getOutputs());
    assertTrue(rolls.isEmpty());
    assertTrue(RecycleResult.of("scrap", List.of(new RecycleOutput("iron", 1, .5)),
        new RecycleContext(1, 1, 1), true, () -> .9).getOutputs().isEmpty());
    assertEquals(List.of(new RecycleOutput("guaranteed", 2)),
        RecycleResult.of("scrap", List.of(new RecycleOutput("guaranteed", 2, 1)),
            new RecycleContext(1, 1, 1)).roll().getOutputs());
  }
}
