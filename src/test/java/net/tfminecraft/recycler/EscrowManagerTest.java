package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.tfminecraft.recycler.manager.*;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;

class EscrowManagerTest extends TestSupport {
  @Test
  void escrowMemoryCopiesAndReturnDelivery() throws Exception {
    var e = new EscrowManager();
    assertTrue(e.listPendingPlayerIds().isEmpty());
    e.savePending();
    e.loadPending();
    var player = server.addPlayer();
    UUID id = player.getUniqueId();
    assertFalse(e.hasEscrow(id));
    assertFalse(e.hasPending(id));
    assertNull(e.getEscrow(id));
    e.putEscrow(null, item(Material.DIAMOND));
    e.putEscrow(id, null);
    e.putEscrow(id, item(Material.AIR));
    assertEquals(0, e.countMemoryEscrow());
    var stack = item(Material.DIAMOND);
    stack.setAmount(3);
    e.putEscrow(id, stack);
    assertTrue(e.hasEscrow(id));
    assertEquals(List.of(id), e.listMemoryEscrowPlayerIds());
    assertEquals(1, e.countMemoryEscrow());
    e.getEscrow(id).setAmount(10);
    assertEquals(3, e.getEscrow(id).getAmount());
    e.returnEscrow(player);
    assertFalse(e.hasEscrow(id));
    assertEquals(3, player.getInventory().getItem(0).getAmount());
    e.giveBackEscrow(null, true);
    e.adminReturn(null);
    e.deliverPending((org.bukkit.entity.Player) null);
    e.deliverPending(UUID.randomUUID());
    e.returnEscrow(player);
    e.deliverPending(player);
    e.putEscrow(id, stack);
    e.clearEscrow(id);
    assertFalse(e.hasEscrow(id));
    e.putEscrow(id, stack);
    e.flushAllOnline();
    assertFalse(e.hasEscrow(id));
    e.flushAllOnline();
  }

  @Test
  void namedEnchantedEscrowSurvivesRestartExactly() {
    var e = new EscrowManager();
    e.loadPending();
    var player = server.addPlayer();
    var stack = item(Material.DIAMOND_SWORD);
    var meta = stack.getItemMeta();
    meta.setDisplayName("My blade");
    meta.setLore(List.of("preserve me"));
    meta.setUnbreakable(true);
    meta.getPersistentDataContainer()
        .set(
            new NamespacedKey("test", "identity"),
            org.bukkit.persistence.PersistentDataType.STRING,
            "original");
    stack.setItemMeta(meta);
    e.putEscrow(player.getUniqueId(), stack);
    e.savePending();
    var restarted = new EscrowManager();
    restarted.loadPending();
    assertTrue(restarted.hasPending(player.getUniqueId()));
    restarted.deliverPending(player);
    assertEquals(stack, player.getInventory().getItem(0));
    assertFalse(restarted.hasPending(player.getUniqueId()));
  }

  @Test
  void corruptPendingFileIsRetainedForRecovery() throws Exception {
    var e = new EscrowManager();
    e.loadPending();
    var player = server.addPlayer();
    var file = dir.resolve("data/pending_returns/" + player.getUniqueId() + ".json");
    Files.writeString(file, "{broken");
    e.deliverPending(player);
    assertTrue(Files.exists(file));
    assertEquals("{broken", Files.readString(file));
  }

  @Test
  void pendingCollisionPreservesBothStacks() {
    var e = new EscrowManager();
    e.loadPending();
    var player = server.addPlayer();
    e.putEscrow(player.getUniqueId(), new ItemStack(Material.DIAMOND, 2));
    e.savePending();
    e.putEscrow(player.getUniqueId(), new ItemStack(Material.GOLD_INGOT, 3));
    e.savePending();
    assertEquals(2, e.countPendingFiles());
    e.deliverPending(player);
    assertEquals(
        2,
        player.getInventory().all(Material.DIAMOND).values().stream()
            .mapToInt(ItemStack::getAmount)
            .sum());
    assertEquals(
        3,
        player.getInventory().all(Material.GOLD_INGOT).values().stream()
            .mapToInt(ItemStack::getAmount)
            .sum());
  }

  @Test
  void diskFallbackPendingListingAndCorruptEscrowRemainRecoverable() throws Exception {
    var e = new EscrowManager();
    e.loadPending();
    assertTrue(e.listPendingPlayerIds().isEmpty());
    e.clearEscrow(UUID.randomUUID());
    var player = server.addPlayer();
    var id = player.getUniqueId();
    var ef = dir.resolve("data/escrow/" + id + ".json");
    Files.writeString(
        ef, new com.google.gson.Gson().toJson(new ItemStack(Material.DIAMOND, 2).serialize()));
    assertTrue(e.hasEscrow(id));
    assertEquals(2, e.getEscrow(id).getAmount());
    e.giveBackEscrow(player, false);
    assertFalse(Files.exists(ef));
    Files.writeString(ef, "null");
    e.giveBackEscrow(player, true);
    assertTrue(Files.exists(ef));
    Files.writeString(ef, new com.google.gson.Gson().toJson(item(Material.AIR).serialize()));
    e.giveBackEscrow(player, true);
    assertTrue(Files.exists(ef));
    Files.delete(ef);
    var pending = dir.resolve("data/pending_returns");
    Files.writeString(pending.resolve("bad.json"), "bad");
    Files.writeString(pending.resolve("ignore.txt"), "ignore");
    Files.writeString(pending.resolve(id + "-ignore.txt"), "ignore");
    Files.writeString(
        pending.resolve("not-a-uuid-but-long-enough-to-parse-00000000.json"), "invalid");
    assertTrue(e.listPendingPlayerIds().isEmpty());
    Files.writeString(
        pending.resolve(id + ".json"),
        new com.google.gson.Gson().toJson(item(Material.AIR).serialize()));
    e.deliverPending(player);
    assertTrue(e.hasPending(id));
    Files.writeString(
        pending.resolve(id + ".json"),
        new com.google.gson.Gson().toJson(new ItemStack(Material.DIAMOND, 3).serialize()));
    assertEquals(1, e.countPendingFiles());
    assertEquals(List.of(id), e.listPendingPlayerIds());
    e.deliverPending(id);
    assertFalse(e.hasPending(id));
    e.putEscrow(id, item(Material.GOLD_INGOT));
    e.savePending();
    e.adminReturn(player);
    assertFalse(e.hasPending(id));
    Files.writeString(dir.resolve("data/escrow/ignore.txt"), "ignore");
    e.savePending();
  }

  @Test
  void filesystemFailuresAreLoggedWithoutThrowing() throws Exception {
    var e = new EscrowManager();
    e.loadPending();
    var id = UUID.randomUUID();
    Files.createDirectory(dir.resolve("data/escrow/" + id + ".json"));
    e.putEscrow(id, item(Material.DIAMOND));
    assertEquals(1, e.countMemoryEscrow());
    e.clearEscrow(id);
    File unavailable = mock(File.class);
    when(unavailable.isDirectory()).thenReturn(true);
    var pending = EscrowManager.class.getDeclaredField("pendingFolder");
    pending.setAccessible(true);
    pending.set(e, unavailable);
    assertTrue(e.listPendingPlayerIds().isEmpty());
    assertFalse(e.hasPending(id));
    when(unavailable.isDirectory()).thenReturn(false);
    assertTrue(e.listPendingPlayerIds().isEmpty());
    var escrowField = EscrowManager.class.getDeclaredField("escrowFolder");
    escrowField.setAccessible(true);
    escrowField.set(e, unavailable);
    e.savePending();
    when(unavailable.isDirectory()).thenReturn(true);
    e.savePending();
    invoke(e, "moveFileToPending", new Class[] {File.class}, (Object) null);
    invoke(e, "moveFileToPending", new Class[] {File.class}, dir.toFile());
    invoke(EscrowManager.class, "deleteFile", new Class[] {File.class}, (Object) null);
  }

  @Test
  void moveFallbackCopiesAndRetainsSourceWhenCopyFails() throws Exception {
    var e = new EscrowManager();
    e.loadPending();
    var source = yaml("source.json", "{\"type\":\"DIAMOND\"}");
    File renameFailure = spy(source.toFile());
    doReturn(false).when(renameFailure).renameTo(any(File.class));
    invoke(e, "moveFileToPending", new Class[] {File.class}, renameFailure);
    assertFalse(Files.exists(source));
    assertTrue(Files.exists(dir.resolve("data/pending_returns/source.json")));
    Files.writeString(source, "original");
    var pending = EscrowManager.class.getDeclaredField("pendingFolder");
    pending.setAccessible(true);
    pending.set(e, dir.resolve("not-there/child").toFile());
    invoke(e, "moveFileToPending", new Class[] {File.class}, renameFailure);
    assertEquals("original", Files.readString(source));
  }

  @Test
  void emptyItemFilesAreRetainedWithoutDelivery() throws Exception {
    var e = new EscrowManager();
    e.loadPending();
    var p = server.addPlayer();
    var id = p.getUniqueId();
    var empty = item(Material.AIR);
    Files.writeString(dir.resolve("data/escrow/" + id + ".json"), "{}");
    Files.writeString(dir.resolve("data/pending_returns/" + id + ".json"), "{}");
    // Paper accepts deserialized AIR stacks; MockBukkit normalizes these differently.
    try (var items = mockStatic(ItemStack.class, CALLS_REAL_METHODS)) {
      items.when(() -> ItemStack.deserialize(anyMap())).thenReturn(empty);
      e.giveBackEscrow(p, true);
      e.deliverPending(p);
      assertTrue(e.hasEscrow(id));
      assertTrue(e.hasPending(id));
    }
  }
}
