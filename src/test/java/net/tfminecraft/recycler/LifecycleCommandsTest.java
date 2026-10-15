package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.tfminecraft.recycler.command.*;
import net.tfminecraft.recycler.manager.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class LifecycleCommandsTest extends TestSupport {
  @Test
  void commandsEnforcePermissionsReloadListAndReturnEscrow() {
    var commands = new CommandManager();
    var sender = mock(CommandSender.class);
    var command = mock(Command.class);
    assertTrue(commands.onCommand(sender, command, "recycler", new String[] {}));
    verify(sender).sendMessage("§cNo permission.");
    assertTrue(commands.onTabComplete(sender, command, "recycler", new String[] {""}).isEmpty());
    when(sender.hasPermission("recycler.admin")).thenReturn(true);
    for (String[] args :
        List.of(
            new String[] {},
            new String[] {"reload"},
            new String[] {"unknown"},
            new String[] {"escrow"},
            new String[] {"escrow", "unknown"},
            new String[] {"escrow", "return"})) commands.onCommand(sender, command, "r", args);
    when(plugin.reloadAll()).thenReturn(true);
    commands.onCommand(sender, command, "r", new String[] {"reload"});
    verify(plugin, times(2)).reloadAll();
    var escrow = mock(EscrowManager.class);
    when(plugin.getEscrowManager()).thenReturn(escrow);
    var player = server.addPlayer("Alice");
    var offline = server.getOfflinePlayer(UUID.randomUUID());
    when(escrow.listMemoryEscrowPlayerIds())
        .thenReturn(List.of(player.getUniqueId(), offline.getUniqueId()));
    when(escrow.listPendingPlayerIds())
        .thenReturn(List.of(player.getUniqueId(), offline.getUniqueId()));
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      var nameless = mock(org.bukkit.OfflinePlayer.class);
      bukkit.when(() -> Bukkit.getOfflinePlayer(offline.getUniqueId())).thenReturn(nameless);
      when(escrow.describeQueues()).thenReturn(List.of("captured owner / operation (held)"));
      commands.onCommand(sender, command, "r", new String[] {"escrow", "list"});
      verify(sender).sendMessage(contains("captured owner"));
      commands.onCommand(sender, command, "r", new String[] {"escrow", "return", "missing"});
    }
    commands.onCommand(sender, command, "r", new String[] {"escrow", "return", "Alice"});
    when(escrow.hasEscrow(player.getUniqueId())).thenReturn(true);
    commands.onCommand(sender, command, "r", new String[] {"escrow", "return", "Alice"});
    when(escrow.hasEscrow(player.getUniqueId())).thenReturn(false);
    when(escrow.hasPending(player.getUniqueId())).thenReturn(true);
    commands.onCommand(sender, command, "r", new String[] {"escrow", "return", "Alice"});
    verify(escrow, times(3)).adminReturn(player);
    commands.onCommand(sender, command, "r", new String[] {"escrow", "return", "missing"});
    player.disconnect();
    commands.onCommand(sender, command, "r", new String[] {"escrow", "return", "Alice"});
    verify(sender).sendMessage(contains("Player offline."));
  }

  @Test
  void tabCompletionFiltersAtEveryDepth() {
    var c = new CommandManager();
    var sender = mock(CommandSender.class);
    when(sender.hasPermission("recycler.admin")).thenReturn(true);
    server.addPlayer("Alice");
    server.addPlayer("Bob");
    assertEquals(
        List.of("reload", "escrow"), c.onTabComplete(sender, null, "r", new String[] {null}));
    assertEquals(
        List.of("reload", "escrow"), c.onTabComplete(sender, null, "r", new String[] {""}));
    assertEquals(List.of("reload"), c.onTabComplete(sender, null, "r", new String[] {"RE"}));
    assertEquals(
        List.of("list", "return"), c.onTabComplete(sender, null, "r", new String[] {"escrow", ""}));
    assertEquals(
        List.of("Alice"),
        c.onTabComplete(sender, null, "r", new String[] {"escrow", "return", "a"}));
    for (String[] args :
        List.of(
            new String[] {},
            new String[] {"other", "x"},
            new String[] {"other", "return", "x"},
            new String[] {"escrow", "list", "x"},
            new String[] {"escrow", "return", "x", "x"}))
      assertTrue(c.onTabComplete(sender, null, "r", args).isEmpty());
  }

  @Test
  void returnCompletionIncludesRecognizedOfflineQueuesWithoutPerKeystrokeDiscovery() throws Exception {
    var escrow = spy(new EscrowManager());
    when(plugin.getEscrowManager()).thenReturn(escrow);
    var alice = server.addPlayer("Alice");
    var aliceRow = queued(escrow, token(alice).owner(), "pending");
    alice.disconnect();
    var bob = server.addPlayer("Bob");
    queued(escrow, token(bob).owner(), "escrow");
    var held = server.addPlayer("Herbie");
    queued(escrow, token(held).owner(), "held");
    held.disconnect();
    var finished = server.addPlayer("Finished");
    queued(escrow, token(finished).owner(), "delivered");
    finished.disconnect();
    UUID unknownId = UUID.randomUUID();
    queued(escrow, new net.tfminecraft.recycler.ownership.QueueIdentity.Owner(unknownId, UUID.randomUUID()), "pending");
    var sender = mock(CommandSender.class);
    when(sender.hasPermission("recycler.admin")).thenReturn(true);
    var commands = new CommandManager();
    try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
      var unknown = mock(OfflinePlayer.class);
      bukkit.when(() -> Bukkit.getOfflinePlayer(unknownId)).thenReturn(unknown);
      commands.startCompletionRefresh(plugin);
      clearInvocations(escrow);
      bukkit.clearInvocations();
      assertEquals(List.of("Alice", "Bob", "Herbie"), commands.onTabComplete(sender, null, "recycler", new String[]{"escrow", "return", ""}));
      assertEquals(List.of("Alice"), commands.onTabComplete(sender, null, "recycler", new String[]{"ESCROW", "RETURN", "aL"}));
      assertEquals(List.of("Bob"), commands.onTabComplete(sender, null, "recycler", new String[]{"escrow", "return", "b"}));
      assertTrue(commands.onTabComplete(sender, null, "recycler", new String[]{"escrow", "return", "Finished"}).isEmpty());
      assertTrue(commands.onTabComplete(sender, null, "recycler", new String[]{"escrow", "return", "missing"}).isEmpty());
      verify(escrow, never()).listPendingPlayerIds();
      bukkit.verify(() -> Bukkit.getOfflinePlayer(any(UUID.class)), never());
      commands.onCommand(sender, null, "recycler", new String[]{"escrow", "return", "Alice"});
      verify(sender).sendMessage(contains("Player offline. Pending return file exists for §fAlice"));
      when(sender.hasPermission("recycler.admin")).thenReturn(false);
      assertTrue(commands.onTabComplete(sender, null, "recycler", new String[]{"escrow", "return", ""}).isEmpty());
      when(sender.hasPermission("recycler.admin")).thenReturn(true);
      escrow.store().update(aliceRow, row -> row.addProperty("state", "delivered"));
      var later = server.addPlayer("LaterOwner");
      queued(escrow, token(later).owner(), "pending");
      later.disconnect();
      server.getScheduler().performTicks(100);
      assertTrue(commands.onTabComplete(sender, null, "recycler", new String[]{"escrow", "return", "Alice"}).isEmpty());
      assertEquals(List.of("LaterOwner"), commands.onTabComplete(sender, null, "recycler", new String[]{"escrow", "return", "la"}));
    }
  }

  private com.google.gson.JsonObject queued(EscrowManager escrow, net.tfminecraft.recycler.ownership.QueueIdentity.Owner owner, String state) throws IOException {
    var payload = new com.google.gson.JsonObject();
    payload.addProperty("state", state);
    return escrow.store().create(owner, UUID.randomUUID(), payload);
  }

  @Test
  void realPluginLifecycleCreatesResourcesRegistersManagersAndReloads() throws Exception {
    for (String name : List.of("TLibs", "MMOItems", "MythicLib", "ItemsAdder"))
      MockBukkit.createMockPlugin(name);
    Recycler loaded = MockBukkit.load(Recycler.class);
    assertSame(loaded, Recycler.plugin);
    assertNotNull(loaded.getEscrowManager());
    assertNotNull(loaded.getInventoryManager());
    assertNotNull(loaded.getRecyclerManager());
    assertNotNull(loaded.getProviderChain());
    assertInstanceOf(CommandManager.class, loaded.getCommand("recycler").getExecutor());
    assertTrue(loaded.reloadAll());
    server.getScheduler().performTicks(2);
    loaded.onEnable();
    Files.writeString(loaded.getDataFolder().toPath().resolve("config.yml"), "bad: [");
    assertFalse(loaded.reloadAll());
    loaded.onEnable();
    loaded.onDisable();
  }

  @Test
  void lifecycleReportsMissingCommandResourcesAndCopyFailures() throws Exception {
    for (String name : List.of("TLibs", "MMOItems", "MythicLib", "ItemsAdder"))
      MockBukkit.createMockPlugin(name);
    var real = spy(MockBukkit.load(Recycler.class));
    doReturn(dir.resolve("new-plugin").toFile()).when(real).getDataFolder();
    doReturn(server).when(real).getServer();
    doReturn(java.util.logging.Logger.getAnonymousLogger()).when(real).getLogger();
    doReturn(null).when(real).getCommand("recycler");
    doReturn(null).when(real).getResource(anyString());
    real.onEnable();
    assertSame(real, Recycler.plugin);
    doReturn(
            new ByteArrayInputStream("x".getBytes()) {
              public int read(byte[] b, int off, int len) {
                throw new java.io.UncheckedIOException(new IOException("broken"));
              }
            })
        .when(real)
        .getResource("failed");
    // A directory target forces the real Files.copy failure without weakening production guards.
    doReturn(new ByteArrayInputStream("x".getBytes())).when(real).getResource("blocked/file.yml");
    Files.writeString(dir.resolve("new-plugin/blocked"), "file");
    invoke(real, "copyResourceIfMissing", new Class[] {String.class}, "blocked/file.yml");
    real.onDisable();
  }

  @Test
  void bundledResourceCopyClosesFailingStreams() throws Exception {
    for (String name : List.of("TLibs", "MMOItems", "MythicLib", "ItemsAdder"))
      MockBukkit.createMockPlugin(name);
    var loaded = spy(MockBukkit.load(Recycler.class));
    doReturn(dir.toFile()).when(loaded).getDataFolder();
    doReturn(
            new ByteArrayInputStream("x".getBytes()) {
              public void close() throws IOException {
                throw new IOException("close failure");
              }
            })
        .when(loaded)
        .getResource("close.yml");
    invoke(loaded, "copyResourceIfMissing", new Class[] {String.class}, "close.yml");
    assertEquals("x", Files.readString(dir.resolve("close.yml")));
  }

  @Test
  void copyReadAndCloseErrorsAreBothHandled() throws Exception {
    for (String name : List.of("TLibs", "MMOItems", "MythicLib", "ItemsAdder"))
      MockBukkit.createMockPlugin(name);
    var loaded = spy(MockBukkit.load(Recycler.class));
    doReturn(dir.toFile()).when(loaded).getDataFolder();
    var stream = mock(InputStream.class);
    when(stream.read(any(byte[].class), anyInt(), anyInt())).thenThrow(new IOException("read"));
    doThrow(new IOException("close")).when(stream).close();
    doReturn(stream).when(loaded).getResource("read.yml");
    invoke(loaded, "copyResourceIfMissing", new Class[] {String.class}, "read.yml");
  }
  @Test void optionalUnavailableIdentityAndMigrationFailureStayScheduledAndHeld() throws Exception {
    for(String name:List.of("TLibs","MMOItems","MythicLib","ItemsAdder"))MockBukkit.createMockPlugin(name);
    var loaded=MockBukkit.load(Recycler.class);var escrow=loaded.getEscrowManager();
    var identity=mock(net.tfminecraft.recycler.ownership.QueueIdentity.class);var field=EscrowManager.class.getDeclaredField("identity");field.setAccessible(true);field.set(escrow,identity);
    var store=mock(net.tfminecraft.recycler.ownership.ReturnStore.class);field=EscrowManager.class.getDeclaredField("store");field.setAccessible(true);field.set(escrow,store);
    doThrow(new IOException("migration blocked")).when(store).migrate(identity);
    loaded.onEnable();server.getScheduler().performTicks(2);verify(store,atLeast(2)).migrate(identity);
  }
}
