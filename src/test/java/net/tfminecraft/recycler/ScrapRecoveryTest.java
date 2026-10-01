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
    assertEquals(Map.of("1", 0., "2", 1., "3", .5, "4", .75, "5", .9), Cache.scrapGemRates);
    assertEquals(.12, Cache.scrapGemDefaultRate);
    loader.load(yaml("rates", "{}").toFile());
    assertEquals(Map.of("1", .01, "2", .25, "3", .5, "4", .75), Cache.scrapGemRates);
    assertEquals(.01, Cache.scrapGemDefaultRate);
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
        double rate = Cache.scrapGemRates.getOrDefault(Integer.toString(tier), Cache.scrapGemDefaultRate);
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
