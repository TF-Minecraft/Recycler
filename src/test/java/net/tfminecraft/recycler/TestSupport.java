package net.tfminecraft.recycler;

import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import org.mockito.*;

class TestSupport {
  @TempDir Path dir;
  ServerMock server;
  Recycler plugin;
  ItemAPI api;
  MockedStatic<TLibs> tlibs;
  MockedStatic<net.tfminecraft.recycler.ownership.InventoryCheckpoint> inventoryCheckpoint;
  Map<java.lang.reflect.Field, Object> cache = new HashMap<>();
  net.tfminecraft.tlibs.character.CharacterIdentity characters;
  final Map<UUID, net.tfminecraft.tlibs.character.CharacterRef> heirs = new HashMap<>();
  final Map<UUID, net.tfminecraft.tlibs.character.CharacterSession> sessions = new HashMap<>();
  final Map<UUID, net.tfminecraft.tlibs.character.CharacterInfo> infos = new HashMap<>();

  net.tfminecraft.tlibs.character.CharacterRef reviewed(UUID account) {
    return heirs.computeIfAbsent(account, owner -> {
      var ref = new net.tfminecraft.tlibs.character.CharacterRef(owner, UUID.randomUUID());
      infos.put(ref.characterId(), new net.tfminecraft.tlibs.character.CharacterInfo(ref, "Reviewed", net.tfminecraft.tlibs.character.CharacterStatus.ALIVE, null, Set.of(), 0, null));
      return ref;
    });
  }
  net.tfminecraft.recycler.ownership.QueueIdentity.Token token(org.bukkit.entity.Player player) {
    return net.tfminecraft.recycler.ownership.QueueIdentity.load().current(player).orElseThrow();
  }

  @BeforeEach
  void baseSetup() throws Exception {
    server = MockBukkit.mock();
    plugin = mock(Recycler.class);
    Recycler.plugin = plugin;
    when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
    when(plugin.getDataFolder()).thenReturn(dir.toFile());
    when(plugin.getServer()).thenReturn(server);
    when(plugin.isEnabled()).thenReturn(true);
    when(plugin.getName()).thenReturn("Recycler");
    when(plugin.getPluginLoader()).thenReturn(MockBukkit.createMockPlugin("RecyclerFixture").getPluginLoader());
    when(plugin.getDescription()).thenReturn(new org.bukkit.plugin.PluginDescriptionFile("Recycler", "test", Recycler.class.getName()));
    characters = mock(net.tfminecraft.tlibs.character.CharacterIdentity.class);
    server.getServicesManager().register(net.tfminecraft.tlibs.character.CharacterIdentity.class, characters, plugin, org.bukkit.plugin.ServicePriority.Normal);
    when(characters.currentSession(any())).thenAnswer(call -> {
      var p = (org.bukkit.entity.Player) call.getArgument(0);
      if (sessions.containsKey(p.getUniqueId())) return Optional.ofNullable(sessions.get(p.getUniqueId()));
      var session = new net.tfminecraft.tlibs.character.CharacterSession(reviewed(p.getUniqueId()), 1);
      sessions.put(p.getUniqueId(), session); return Optional.of(session);
    });
    when(characters.isCurrent(any())).thenAnswer(call -> {
      var s = (net.tfminecraft.tlibs.character.CharacterSession) call.getArgument(0);
      return s.equals(sessions.get(s.ref().ownerId()));
    });
    when(characters.info(any())).thenAnswer(call -> Optional.ofNullable(infos.get(call.getArgument(0))));
    when(characters.legacyHeirsReady()).thenReturn(true);
    when(characters.legacyHeir(any())).thenAnswer(call -> Optional.of(reviewed(call.getArgument(0))));
    api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
    // MockBukkit saveData is unimplemented; effect/intent tests inject failures at this Paper boundary.
    inventoryCheckpoint = mockStatic(net.tfminecraft.recycler.ownership.InventoryCheckpoint.class);
    when(api.getCreator().getItemFromPath(anyString())).thenReturn(null);
    when(api.getChecker().getAsStringPath(any())).thenReturn(null);
    tlibs = mockStatic(TLibs.class);
    tlibs.when(TLibs::getItemAPI).thenReturn(api);
    for (Class<?> type : List.of(Cache.class, GuiCache.class))
      for (var f : type.getFields()) cache.put(f, f.get(null));
  }

  @AfterEach
  void baseCleanup() throws Exception {
    tlibs.close();
    inventoryCheckpoint.close();
    MockBukkit.unmock();
    Recycler.plugin = null;
    for (var e : cache.entrySet()) e.getKey().set(null, e.getValue());
  }

  ItemStack item(Material m) {
    return new ItemStack(m);
  }

  Path yaml(String name, String text) throws Exception {
    var p = dir.resolve(name);
    Files.writeString(p, text);
    return p;
  }

  static Object invoke(Object target, String name, Class<?>[] types, Object... args)
      throws Exception {
    Class<?> c = target instanceof Class<?> cls ? cls : target.getClass();
    java.lang.reflect.Method m;
    while (true) {
      try {
        m = c.getDeclaredMethod(name, types);
        break;
      } catch (NoSuchMethodException ex) {
        c = c.getSuperclass();
        if (c == null) throw ex;
      }
    }
    m.setAccessible(true);
    return m.invoke(target instanceof Class<?> ? null : target, args);
  }
}
