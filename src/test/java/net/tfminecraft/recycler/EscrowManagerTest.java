package net.tfminecraft.recycler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import net.tfminecraft.recycler.manager.*;
import net.tfminecraft.recycler.ownership.*;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;

class EscrowManagerTest extends TestSupport {
  @Test void exactItemBytesAndUnknownNullsSurviveDepositSaveRestartAndReturn() throws Exception {
    var e = new EscrowManager(); e.loadPending(); var player = server.addPlayer(); var token = token(player);
    ItemStack original = new ItemStack(Material.DIAMOND_SWORD); var meta = original.getItemMeta();
    meta.setDisplayName("Old blade"); meta.setLore(List.of("Preserve me")); meta.setUnbreakable(true);
    meta.getPersistentDataContainer().set(new NamespacedKey("test", "item"), org.bukkit.persistence.PersistentDataType.STRING, "original"); original.setItemMeta(meta);
    player.getInventory().setItem(4, original); e.deposit(player, token, original, 4);
    assertNull(player.getInventory().getItem(4)); assertEquals(original, e.getEscrow(token.owner()));
    var row = e.store().rows(token.owner()).getFirst(); String encoded = row.get("item-bytes").getAsString();
    e.store().update(row, copy -> copy.add("future", com.google.gson.JsonParser.parseString("{\"kept\":null,\"nested\":[null,1]}")));
    e.savePending(); var restarted = new EscrowManager(); restarted.loadPending();
    assertTrue(restarted.hasPending(player.getUniqueId())); restarted.deliverPending(player);
    assertEquals(original, player.getInventory().getItem(0));
    var after = restarted.store().rows(token.owner()).getFirst();
    assertEquals(encoded, after.get("item-bytes").getAsString()); assertTrue(after.getAsJsonObject("future").has("kept"));
    assertTrue(after.getAsJsonObject("future").get("kept").isJsonNull()); assertEquals("delivered", after.get("state").getAsString());
    restarted.deliverPending(player); assertEquals(1, player.getInventory().all(Material.DIAMOND_SWORD).size());
  }
  @Test void charactersOnOneAccountCannotReceiveEachOthersQueueAndOutgoingRefundRemainsOriginal() throws Exception {
    var e = new EscrowManager(); var player = server.addPlayer(); var original = token(player);
    e.putEscrow(player.getUniqueId(), new ItemStack(Material.DIAMOND, 3));
    e.pending(original.owner()); var second = new net.tfminecraft.tlibs.character.CharacterRef(player.getUniqueId(), UUID.randomUUID());
    infos.put(second.characterId(), new net.tfminecraft.tlibs.character.CharacterInfo(second,"Second",net.tfminecraft.tlibs.character.CharacterStatus.ALIVE,null,Set.of(),0,null));
    sessions.put(player.getUniqueId(), new net.tfminecraft.tlibs.character.CharacterSession(second,2));
    e.deliverPending(player); assertTrue(player.getInventory().isEmpty()); assertFalse(e.hasPending(player.getUniqueId()));
    sessions.put(player.getUniqueId(), new net.tfminecraft.tlibs.character.CharacterSession(new net.tfminecraft.tlibs.character.CharacterRef(original.owner().account(),original.owner().character()),3));
    e.deliverPending(player); assertEquals(3, player.getInventory().getItem(0).getAmount());
    assertFalse(e.current(original, player)); assertFalse(e.current(null, player)); assertFalse(e.current(original, null));
    var other = server.addPlayer(); assertFalse(e.current(token(player), other));
  }
  @Test void intentBeforeEffectAndInterruptedDepositOrDeliveryAreHeldWithoutReplay() throws Exception {
    var e = new EscrowManager(); var player = server.addPlayer(); var token = token(player); var item = item(Material.DIAMOND);
    player.getInventory().setItem(2,item);
    try (var disk = mockStatic(QueueFiles.class, CALLS_REAL_METHODS)) {
      disk.when(() -> QueueFiles.write(any(), any(), eq(false))).thenThrow(new IOException("full before deposit"));
      assertThrows(IOException.class, () -> e.deposit(player, token, item, 2)); assertEquals(item,player.getInventory().getItem(2));
    }
    try (var disk = mockStatic(QueueFiles.class, CALLS_REAL_METHODS)) {
      disk.when(() -> QueueFiles.write(any(), any(), eq(true))).thenThrow(new IOException("after inventory removal"));
      assertThrows(IOException.class, () -> e.deposit(player, token, item, 2)); assertNull(player.getInventory().getItem(2));
    }
    e.loadPending(); e.deliverPending(player); assertTrue(player.getInventory().isEmpty());
    assertEquals("depositing",e.store().rows(token.owner()).getFirst().get("state").getAsString());
    e.putEscrow(player.getUniqueId(),item); e.pending(token.owner());
    try (var disk = mockStatic(QueueFiles.class, CALLS_REAL_METHODS)) {
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      disk.when(() -> QueueFiles.write(any(), any(), eq(true))).thenAnswer(call -> { if(calls.incrementAndGet()==2) throw new IOException("after give before acknowledgment"); return call.callRealMethod(); });
      e.deliverPending(player);
    }
    assertEquals(item,player.getInventory().getItem(0)); var restarted = new EscrowManager(); restarted.loadPending(); restarted.deliverPending(player);
    assertEquals(1,player.getInventory().all(Material.DIAMOND).size());
    assertTrue(restarted.store().rows(token.owner()).stream().anyMatch(row -> "delivering".equals(row.get("state").getAsString())));
  }
  @Test void fullInventoryKeepsRemainderAndBatchesOnlyIntoFreeSlots() throws Exception {
    var e = new EscrowManager(); var player=server.addPlayer(); var token=token(player);
    ItemStack stack=new ItemStack(Material.DIAMOND,130); e.putEscrow(player.getUniqueId(),stack); e.pending(token.owner());
    for(int i=0;i<36;i++)player.getInventory().setItem(i,new ItemStack(Material.STONE,64));
    e.deliverPending(player); assertEquals(130,e.store().rows(token.owner()).getFirst().get("remaining").getAsInt());
    player.getInventory().setItem(3,null); e.deliverPending(player); assertEquals(64,player.getInventory().getItem(3).getAmount());
    assertEquals(66,e.store().rows(token.owner()).getFirst().get("remaining").getAsInt());
    player.getInventory().setItem(7,item(Material.AIR)); player.getInventory().setItem(8,null); e.deliverPending(player);
    assertFalse(e.hasPending(player.getUniqueId())); assertEquals(2,player.getInventory().getItem(8).getAmount());
  }
  @Test void unknownLegacyAndUnreadableItemsArePreservedAndNeverFallBackToTheCurrentCharacter() throws Exception {
    var e=new EscrowManager();e.loadPending();var player=server.addPlayer();var token=token(player);
    Files.createDirectories(dir.resolve("data/pending_returns"));
    var path=dir.resolve("data/pending_returns/"+player.getUniqueId()+".json");
    var legacy=QueueJson.GSON.toJsonTree(new ItemStack(Material.DIAMOND,2).serialize()).getAsJsonObject();
    legacy.add("extension",com.google.gson.JsonParser.parseString("{\"kept\":null}"));Files.write(path,QueueJson.bytes(legacy));
    when(characters.legacyHeirsReady()).thenReturn(false);e.loadPending();e.deliverPending(player);assertTrue(player.getInventory().isEmpty());
    when(characters.legacyHeirsReady()).thenReturn(true);when(characters.legacyHeir(player.getUniqueId())).thenReturn(Optional.empty());e.loadPending();assertTrue(e.store().rows(token.owner()).isEmpty());
    when(characters.legacyHeir(player.getUniqueId())).thenReturn(Optional.of(reviewed(player.getUniqueId())));e.loadPending();e.deliverPending(player);
    assertEquals(2,player.getInventory().getItem(0).getAmount());assertTrue(Files.exists(path));
    var row=e.store().rows(token.owner()).getFirst();assertTrue(row.getAsJsonObject("legacy-item").getAsJsonObject("extension").get("kept").isJsonNull());
    Files.writeString(dir.resolve("data/pending_returns/"+player.getUniqueId()+"-corrupt.json"),"broken");
    Files.createDirectories(dir.resolve("data/escrow"));Files.writeString(dir.resolve("data/escrow/"+player.getUniqueId()+".json"),"{\"type\":\"GOLD_INGOT\"}");
    e.loadPending();e.adminReturn(player);assertEquals(3,e.store().rows(token.owner()).size());
    assertEquals(2,e.store().rows(token.owner()).stream().filter(r->"held".equals(r.get("state").getAsString())).count());
    assertEquals(3,e.describeQueues().stream().filter(s->s.contains("legacy source")).count());
  }
  @Test void cancelledCommitDoesNotRemoveAnInventoryStackAndStaleTokensCannotDeposit() throws Exception {
    var e=new EscrowManager();var player=server.addPlayer();var token=token(player);var item=item(Material.DIAMOND);
    player.getInventory().setItem(1,item(Material.GOLD_INGOT));assertThrows(ReturnStore.Conflict.class,()->e.deposit(player,token,item,1));
    assertEquals(Material.GOLD_INGOT,player.getInventory().getItem(1).getType());assertTrue(ReturnStore.terminal(e.store().rows(token.owner()).getFirst()));
    sessions.put(player.getUniqueId(),null);assertThrows(ReturnStore.Conflict.class,()->e.deposit(player,token,item,1));
    assertThrows(IllegalArgumentException.class,()->e.token(player));e.returnEscrow(player);e.deliverPending(player);
    e.giveBackEscrow(null,true);e.adminReturn(null);e.deliverPending((org.bukkit.entity.Player)null);e.deliverPending(UUID.randomUUID());
    e.putEscrow(null,item);e.putEscrow(player.getUniqueId(),null);e.putEscrow(player.getUniqueId(),item(Material.AIR));
  }
  @Test void terminalRecyclingRetainsOutputEvidenceAndOldAccountCompatibilityNeverReadsLegacyFiles() throws Exception {
    var e=new EscrowManager();var player=server.addPlayer();var token=token(player);e.loadPending();
    assertFalse(e.hasEscrow(player.getUniqueId()));assertNull(e.getEscrow(player.getUniqueId()));
    e.putEscrow(player.getUniqueId(),item(Material.DIAMOND));assertTrue(e.hasEscrow(player.getUniqueId()));
    e.getEscrow(player.getUniqueId()).setAmount(9);assertEquals(1,e.getEscrow(token.owner()).getAmount());
    e.consume(token.owner(),List.of(item(Material.GOLD_INGOT)));e.clearEscrow(player.getUniqueId());
    assertEquals("consumed",e.store().rows(token.owner()).getFirst().get("state").getAsString());
    assertEquals(1,e.store().rows(token.owner()).getFirst().getAsJsonArray("planned-outputs").size());
    assertThrows(ReturnStore.Conflict.class,()->e.consume(token.owner(),List.of()));
    assertTrue(e.listMemoryEscrowPlayerIds().isEmpty());assertEquals(0,e.countMemoryEscrow());
    e.putEscrow(player.getUniqueId(),item(Material.EMERALD));e.flushAllOnline();assertFalse(e.hasEscrow(player.getUniqueId()));
    assertEquals(List.of(),e.listPendingPlayerIds());assertEquals(0,e.countPendingFiles());
  }
  @Test void unreadableOwnershipBlocksOrdinarySavesAndAccountConvenienceMethodsWithoutDiscardingBytes() throws Exception {
    var e=new EscrowManager();var player=server.addPlayer();var token=token(player);e.putEscrow(player.getUniqueId(),item(Material.DIAMOND));
    var row=e.store().rows(token.owner()).getFirst();var path=e.store().file(token.owner(),ReturnStore.id(row));
    Files.writeString(path,"{broken");byte[] before=Files.readAllBytes(path);
    e.savePending();assertFalse(e.hasEscrow(player.getUniqueId()));assertNull(e.getEscrow(player.getUniqueId()));assertFalse(e.hasPending(player.getUniqueId()));
    assertTrue(e.describeQueues().getFirst().contains("unreadable"));assertTrue(e.listPendingPlayerIds().isEmpty());
    assertArrayEquals(before,Files.readAllBytes(path));
    Files.delete(path);e.putEscrow(player.getUniqueId(),item(Material.EMERALD));e.clearEscrow(player.getUniqueId());
    assertEquals("consumed",e.store().rows(token.owner()).getFirst().get("state").getAsString());e.savePending();
    sessions.remove(player.getUniqueId());e.clearEscrow(player.getUniqueId());
  }
  @Test void unparseableOrEmptyItemHoldingsStayRawAndPendingAccountEnumerationDeduplicates() throws Exception {
    var e=new EscrowManager();var player=server.addPlayer();var token=token(player);
    for(String encoded:List.of("broken","air","empty")){
      var row=new com.google.gson.JsonObject();row.addProperty("state","pending");
      if(encoded.equals("broken"))row.addProperty("item-bytes","invalid-base64");
      else if(encoded.equals("air"))row.add("legacy-item",QueueJson.GSON.toJsonTree(item(Material.AIR).serialize()));
      else{row.addProperty("item-bytes",Base64.getEncoder().encodeToString(item(Material.DIAMOND).serializeAsBytes()));row.addProperty("remaining",0);}
      e.store().create(token.owner(),UUID.randomUUID(),row);
    }
    e.deliverPending(player);assertTrue(player.getInventory().isEmpty());assertEquals(3,e.store().rows(token.owner()).size());
    assertEquals(List.of(player.getUniqueId()),e.listPendingPlayerIds());assertEquals(3,e.countPendingFiles());
  }
  @Test void tokenChangesAfterDurableIntentNeverRemoveOrGiveToAnIncomingSession() throws Exception {
    var player=server.addPlayer();var token=token(player);var identity=mock(QueueIdentity.class);
    when(identity.current(player)).thenReturn(Optional.of(token));when(identity.current(token,player)).thenReturn(true,false);
    var e=new EscrowManager(dir.resolve("injected"),identity);var input=item(Material.DIAMOND);player.getInventory().setItem(2,input);
    assertThrows(ReturnStore.Conflict.class,()->e.deposit(player,token,input,2));assertEquals(input,player.getInventory().getItem(2));
    assertEquals("cancelled",e.store().rows(token.owner()).getFirst().get("state").getAsString());
    when(identity.current(token,player)).thenReturn(true);e.putEscrow(player.getUniqueId(),input);e.pending(token.owner());
    when(identity.current(token,player)).thenReturn(false);e.deliverPending(player);assertNull(player.getInventory().getItem(0));
    when(identity.current(token,player)).thenReturn(true,false);e.deliverPending(player);assertNull(player.getInventory().getItem(0));
    assertTrue(e.store().rows(token.owner()).stream().anyMatch(r->"delivering".equals(r.get("state").getAsString())));
    when(identity.current(token,player)).thenReturn(true);e.putEscrow(player.getUniqueId(),input);e.giveBackEscrow(player,false);
    assertEquals(input,player.getInventory().getItem(0));
  }
  @Test void inventoryCheckpointFailureKeepsAmbiguousDepositEvidenceAcrossRestart() throws Exception {
    var e=new EscrowManager();var player=server.addPlayer();var token=token(player);var input=item(Material.DIAMOND);player.getInventory().setItem(2,input);
    inventoryCheckpoint.when(()->InventoryCheckpoint.save(player)).thenThrow(new IllegalStateException("inventory save failed"));
    assertThrows(IllegalStateException.class,()->e.deposit(player,token,input,2));assertNull(player.getInventory().getItem(2));
    var restarted=new EscrowManager();restarted.loadPending();restarted.deliverPending(player);assertTrue(player.getInventory().isEmpty());
    assertEquals("depositing",restarted.store().rows(token.owner()).getFirst().get("state").getAsString());
  }
  @Test void explicitAirStorageSlotReceivesABoundedBatchBeforeItsReceipt()throws Exception{
    var player=mock(org.bukkit.entity.Player.class);var inventory=mock(org.bukkit.inventory.PlayerInventory.class);var owner=new QueueIdentity.Owner(UUID.randomUUID(),UUID.randomUUID());var token=new QueueIdentity.Token(owner,1);var identity=mock(QueueIdentity.class);
    when(player.getUniqueId()).thenReturn(owner.account());when(player.getInventory()).thenReturn(inventory);when(identity.current(player)).thenReturn(Optional.of(token));when(identity.current(token,player)).thenReturn(true);
    ItemStack[] storage={item(Material.AIR)};when(inventory.getStorageContents()).thenReturn(storage);
    var e=new EscrowManager(dir.resolve("air-slot"),identity);var row=new com.google.gson.JsonObject();row.addProperty("state","pending");row.addProperty("item-bytes",Base64.getEncoder().encodeToString(item(Material.DIAMOND).serializeAsBytes()));row.addProperty("remaining",1);e.store().create(owner,UUID.randomUUID(),row);
    e.deliverPending(player);verify(inventory).setItem(eq(0),argThat(value->value.getType()==Material.DIAMOND&&value.getAmount()==1));
    assertEquals("delivered",e.store().rows(owner).getFirst().get("state").getAsString());
  }
  @Test void zeroQuantityNonAirItemFromAnUnreadableFuturePayloadCannotProduceInventoryEffects()throws Exception{
    var e=new EscrowManager();var player=server.addPlayer();var token=token(player);var row=new com.google.gson.JsonObject();row.addProperty("state","pending");row.addProperty("item-bytes","AQ==");e.store().create(token.owner(),UUID.randomUUID(),row);
    var empty=mock(ItemStack.class);when(empty.getType()).thenReturn(Material.DIAMOND);when(empty.getAmount()).thenReturn(0);
    try(var decoding=mockStatic(ItemStack.class,CALLS_REAL_METHODS)){
      decoding.when(()->ItemStack.deserializeBytes(new byte[]{1})).thenReturn(empty);e.deliverPending(player);
      when(empty.getType()).thenReturn(Material.AIR);e.deliverPending(player);
    }
    assertTrue(player.getInventory().isEmpty());assertEquals("pending",e.store().rows(token.owner()).getFirst().get("state").getAsString());
  }
  @Test void unloadableEscrowItemBecomesARecoveryConflictWithoutChangingItsRawRecord()throws Exception{
    var e=new EscrowManager();var player=server.addPlayer();var token=token(player);var raw=new com.google.gson.JsonObject();raw.addProperty("state","escrow");raw.addProperty("item-bytes","bad binary");raw.add("future",com.google.gson.JsonParser.parseString("{\"null\":null}"));var row=e.store().create(token.owner(),UUID.randomUUID(),raw);var file=e.store().file(token.owner(),ReturnStore.id(row));byte[] before=Files.readAllBytes(file);
    assertThrows(ReturnStore.Conflict.class,()->e.getEscrow(token.owner()));assertNull(e.getEscrow(player.getUniqueId()));assertArrayEquals(before,Files.readAllBytes(file));
    e.savePending();new EscrowManager().loadPending();e.deliverPending(player);assertTrue(player.getInventory().isEmpty());assertTrue(e.store().rows(token.owner()).getFirst().getAsJsonObject("future").get("null").isJsonNull());
  }
}
