package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.tfminecraft.recycler.loader.*;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class LoadersTest extends TestSupport {
  @Test
  void configurationDefaultsOverridesAndInvalidRates() throws Exception {
    var loader = new ConfigLoader();
    var gui = new GuiLoader();
    yaml("messages.yml", "hello: world");
    assertTrue(loader.loadSafe(yaml("empty.yml", "{}").toFile()));
    assertTrue(gui.loadSafe(dir.resolve("empty.yml").toFile()));
    for (String name : List.of("config.yml", "gui.yml"))
      Files.copy(getClass().getResourceAsStream("/" + name), dir.resolve(name));
    loader.load(dir.resolve("config.yml").toFile());
    gui.load(dir.resolve("gui.yml").toFile());
    assertEquals(.8, Cache.advancedCraftingReturnRate);
    assertEquals(.5, Cache.scrapReturnRate);
    assertEquals(.8, Cache.magicGearReturnRate);
    assertEquals(.8, Cache.gunsReturnRate);
    assertEquals(.8, Cache.goldsmithReturnRate);
    assertEquals(1, Cache.recipeReturnRate);
    assertEquals("world", Messages.get("hello"));
    assertFalse(loader.loadSafe(dir.resolve("missing").toFile()));
    assertFalse(gui.loadSafe(dir.resolve("missing").toFile()));
    assertFalse(loader.loadSafe(yaml("bad.yml", "bad: [").toFile()));
    assertFalse(gui.loadSafe(dir.resolve("bad.yml").toFile()));
    for (String value : List.of("-1", "2", ".NaN", ".inf", "0", "1")) {
      assertTrue(
          loader.loadSafe(
              yaml("rate.yml", "scrap_return_rate: " + value + "\nstation: {}\ndeposit: {}\n")
                  .toFile()));
      assertTrue(Double.isFinite(Cache.scrapReturnRate));
      assertTrue(Cache.scrapReturnRate >= 0 && Cache.scrapReturnRate <= 1);
    }
    loader.loadSafe(
        yaml(
                "rates.yml",
                "return_rates:\n  advanced_crafting: 0.7\n  alloy_scrap: 0.25\n  magic_gear: 0.6\n"
                    + "  guns: 5\n  goldsmith_jewelry: .NaN\n  recipes: 0.9\n")
            .toFile());
    assertEquals(.7, Cache.advancedCraftingReturnRate);
    assertEquals(.25, Cache.scrapReturnRate);
    assertEquals(.6, Cache.magicGearReturnRate);
    assertEquals(1, Cache.gunsReturnRate);
    assertEquals(.8, Cache.goldsmithReturnRate);
    assertEquals(.9, Cache.recipeReturnRate);
    loader.loadSafe(yaml("legacy.yml", "max_return_rate: 0.6\n").toFile());
    assertEquals(.6, Cache.advancedCraftingReturnRate, "Old max_return_rate still applies");
    assertEquals(.6, Cache.goldsmithReturnRate);
    assertEquals(.5, Cache.scrapReturnRate);
    assertEquals(1, Cache.recipeReturnRate);
    for (String effect :
        List.of(
            "open",
            "input_accept",
            "input_reject",
            "preview_refresh",
            "confirm",
            "complete",
            "cancel")) {
      loader.load(
          yaml(
                  "effects.yml",
                  "effects:\n  "
                      + effect
                      + ":\n    sound: test:foo\n    sound_volume: 0.2\n    sound_pitch: 0.4")
              .toFile());
    }
  }

  @Test
  void messagesFallbackResourcesAndMalformedData() throws Exception {
    var field = Messages.class.getDeclaredField("config");
    field.setAccessible(true);
    field.set(null, null);
    assertEquals("missing", Messages.get("missing"));
    Messages.load(dir.resolve("absent").toFile());
    assertEquals("absent", Messages.get("absent"));
    Messages.load(yaml("bad", "x: [").toFile());
    Messages.load(yaml("good", "greet: hello").toFile());
    assertEquals("hello", Messages.get("greet"));
    Messages.loadFromResources();
    assertEquals("hello", Messages.get("greet"));
    when(plugin.getResource("messages.yml"))
        .thenReturn(new ByteArrayInputStream("greet: bundled".getBytes()));
    Messages.loadFromResources();
    assertEquals("bundled", Messages.get("greet"));
    when(plugin.getResource("messages.yml"))
        .thenReturn(new ByteArrayInputStream("x: [".getBytes()));
    Messages.loadFromResources();
    assertEquals("bundled", Messages.get("greet"));
  }

  @Test
  void recipesParseCurrentLegacyAliasesAndRejectMalformedEntries() throws Exception {
    var loader = new RecipeLoader();
    assertTrue(loader.loadFolder(dir.resolve("absent").toFile()));
    var file =
        yaml(
            "recipes.yml",
            """
recipes:
  valid:
    input: ' Vanilla.IRON_SWORD '
    outputs: ['vanilla.IRON_INGOT(2)', 'v.iron_ingot 3', 'v.gold_ingot 2', '', ' ', '(2)', 'bad)', 'v.iron_ingot(x)', 'noamount', 'v.bad x', 'v.zero(0)', '  (2)', 'v.negative -1']
  typo:
    input: ' '
    inpuit: ' v.diamond_sword '
    outputs:
      vanilla:
        diamond: 2
      v:
        gold_ingot: 0
        stone: -1
        ignored: string
  empty:
    input: v.air
    outputs: []
  missing:
    input: v.dirt
  scalar: invalid
  v:
    stick:
      outputs:
        v:
          oak_planks: 1
    stone:
      outputs:
        v.air: 0
    input: ' '
    inpuit: ' '
    outputs: []
    nothing: scalar
  blank:
    input: ' '
    inpuit: ' '
""");
    assertTrue(loader.loadFolder(dir.toFile()));
    assertEquals(3, RecipeLoader.size());
    assertEquals(
        Map.of("v.iron_ingot", 5, "v.gold_ingot", 2),
        RecipeLoader.getOutputsForInput("VANILLA.IRON_SWORD"));
    assertEquals(2, RecipeLoader.getOutputsForInput("v.diamond_sword").get("v.diamond"));
    assertTrue(RecipeLoader.getOutputsForInput(null).isEmpty());
    assertTrue(RecipeLoader.getOutputsForInput("no").isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> RecipeLoader.getAll().clear());
    assertNull(RecipeLoader.findMatchingInput(null));
    assertNull(RecipeLoader.findMatchingInput(item(Material.AIR)));
    var sword = item(Material.IRON_SWORD);
    assertNull(RecipeLoader.findMatchingInput(sword));
    when(api.getChecker().checkItemWithPath(sword, "v.iron_sword")).thenReturn(true);
    assertEquals("v.iron_sword", RecipeLoader.findMatchingInput(sword));
    when(api.getChecker().checkItemWithPath(sword, "v.iron_sword"))
        .thenThrow(new IllegalArgumentException());
    assertNull(RecipeLoader.findMatchingInput(sword));
    yaml("empty.yaml", "hello: world");
    yaml("bad.yaml", "bad: [");
    yaml("ignore.txt", "ignored");
    assertFalse(loader.loadFolder(dir.toFile()));
    assertTrue(loader.loadFolder(file.toFile()));
    assertNull(RecipeLoader.findMatchingInput(sword));
    File unreadable = mock(File.class);
    when(unreadable.exists()).thenReturn(true);
    when(unreadable.isDirectory()).thenReturn(true);
    assertTrue(loader.loadFolder(unreadable));
  }

  @Test
  void recipeRootEmptyPrefixAndWhitespaceAliases() throws Exception {
    var section = new org.bukkit.configuration.file.YamlConfiguration();
    section.set("v.stick.outputs.v.diamond", 2);
    invoke(
        new RecipeLoader(),
        "loadLegacyPathRecipes",
        new Class[] {
          org.bukkit.configuration.ConfigurationSection.class, String.class, String.class
        },
        section,
        "",
        "legacy.yml");
    assertEquals(2, RecipeLoader.getOutputsForInput("v.stick").get("v.diamond"));
    assertEquals(
        "fallback",
        invoke(
            RecipeLoader.class,
            "firstNonBlank",
            new Class[] {String.class, String.class},
            null,
            " fallback "));
    assertNull(
        invoke(
            RecipeLoader.class,
            "firstNonBlank",
            new Class[] {String.class, String.class},
            null,
            null));
  }

  @Test
  void messageResourceCloseFailureIsReportedWithoutDiscardingLoadedMessages() {
    when(plugin.getResource("messages.yml"))
        .thenReturn(
            new ByteArrayInputStream("x: yes".getBytes()) {
              public void close() throws IOException {
                throw new IOException("close failed");
              }
            });
    Messages.loadFromResources();
    assertEquals("true", Messages.get("x"));
  }

  @Test
  void resourceReadAndCloseErrorsAreBothHandled() {
    var stream = mock(InputStream.class);
    try {
      when(stream.readAllBytes()).thenThrow(new IOException("read"));
      doThrow(new IOException("close")).when(stream).close();
    } catch (IOException ex) {
      throw new AssertionError(ex);
    }
    when(plugin.getResource("messages.yml")).thenReturn(stream);
    Messages.loadFromResources();
  }
}
