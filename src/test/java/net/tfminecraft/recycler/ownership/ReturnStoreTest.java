package net.tfminecraft.recycler.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReturnStoreTest {
  @TempDir Path root;
  final QueueIdentity.Owner from=new QueueIdentity.Owner(UUID.randomUUID(),UUID.randomUUID());
  final QueueIdentity.Owner to=new QueueIdentity.Owner(UUID.randomUUID(),UUID.randomUUID());
  ReturnStore store(){return new ReturnStore(root);}
  JsonObject payload(String state)throws IOException{return QueueJson.parse("{\"state\":\""+state+"\",\"item-bytes\":\"opaque-item-bytes\",\"remaining\":3,\"future\":{\"nested\":null}}");}
  JsonObject holding(ReturnStore store,String state)throws IOException{return store.create(from,UUID.randomUUID(),payload(state));}
  QueueIdentity identity(){var identity=mock(QueueIdentity.class);when(identity.heirsReady()).thenReturn(true);when(identity.heir(from.account())).thenReturn(Optional.of(from));return identity;}
  Path legacy(String kind,String name,String body)throws IOException{Path file=root.resolve(kind).resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,body);return file;}
  Path estate(UUID death){return root.resolve("ownership/estates/"+death+".json");}
  void save(Path file,JsonObject raw)throws IOException{QueueFiles.write(file,QueueJson.bytes(raw),true);}
  JsonObject journal(UUID death)throws IOException{return ReturnStore.read(estate(death));}
  String decision(UUID death,String version,QueueIdentity.Owner source,QueueIdentity.Owner target){
    var ref=new net.tfminecraft.tlibs.character.CharacterRef(source.account(),source.character());var recipient=new net.tfminecraft.tlibs.character.CharacterRef(target.account(),target.character());
    return new net.tfminecraft.tlibs.character.estate.EstateDecision(death,ref,Optional.empty(),java.time.Instant.EPOCH,Optional.empty(),Optional.empty(),Optional.empty(),net.tfminecraft.tlibs.character.estate.Recipient.FROZEN,net.tfminecraft.tlibs.character.estate.Recipient.character(recipient),net.tfminecraft.tlibs.character.estate.Recipient.FROZEN,net.tfminecraft.tlibs.character.estate.Recipient.SENDER,version.equals("one")?1:2).toString();
  }
  List<String> transfer(ReturnStore store,UUID death,String version,QueueIdentity.Owner source,QueueIdentity.Owner target,java.util.function.BooleanSupplier guard)throws IOException{
    return store.transfer(death,decision(death,version,source,target),source,target,guard);
  }
  @Test void migrationWaitsForReviewedHeirsPreservesMultiplicityNullsAndNeverReassignsAfterRestart()throws Exception{
    var store=store();var identity=identity();var first=legacy("pending_returns",from.account()+".json","{\"type\":\"DIAMOND\",\"extra\":{\"null\":null}}");
    legacy("pending_returns",from.account()+"-second.json","{\"type\":\"GOLD_INGOT\"}");legacy("escrow",from.account()+".json","{\"type\":\"IRON_INGOT\"}");
    when(identity.heirsReady()).thenReturn(false);store.migrate(identity);assertTrue(store.rows(from).isEmpty());
    when(identity.heirsReady()).thenReturn(true);when(identity.heir(from.account())).thenReturn(Optional.empty());store.migrate(identity);assertTrue(store.rows(from).isEmpty());
    when(identity.heir(from.account())).thenReturn(Optional.of(from));store.migrate(identity);assertEquals(3,store.rows(from).size());
    assertEquals(1,store.rows(from).stream().filter(r->"held".equals(ReturnStore.text(r,"state"))).count());
    when(identity.heir(from.account())).thenReturn(Optional.of(to));new ReturnStore(root).migrate(identity);assertTrue(store.rows(to).isEmpty());assertTrue(Files.exists(first));
    store.requireMigrated(from);store.requireWritable(from);
    JsonObject row=store.rows(from).stream().filter(r->r.has("legacy-item")&&r.getAsJsonObject("legacy-item").has("extra")).findFirst().orElseThrow();
    store.update(row,r->r.addProperty("remaining",1));assertTrue(store.rows(from).stream().anyMatch(r->r.has("legacy-item")&&r.getAsJsonObject("legacy-item").has("extra")));
  }
  @Test void unrecognizedUnreadableAndWrongAccountHeirsAreHeldWithoutDroppingSources()throws Exception{
    var store=store();var identity=identity();legacy("pending_returns","bad.json","raw");legacy("pending_returns",from.account()+".json","{broken");Files.createDirectories(root.resolve("escrow/directory"));
    when(identity.heir(from.account())).thenReturn(Optional.of(to));store.migrate(identity);assertTrue(store.rows(from).isEmpty());
    when(identity.heir(from.account())).thenReturn(Optional.of(from));store.migrate(identity);store.migrate(identity);var row=store.rows(from).getFirst();
    assertEquals("held",ReturnStore.text(row,"state"));assertEquals("{broken",new String(Base64.getDecoder().decode(ReturnStore.text(row,"legacy-bytes"))));
    assertEquals(1,Files.list(root.resolve("ownership/unassigned")).count());
  }
  @Test void interruptedMigrationPublishesOnlyItsFixedRefAndLocksUntilReplay()throws Exception{
    var store=store();var identity=identity();legacy("pending_returns",from.account()+".json","{\"type\":\"DIAMOND\"}");
    UUID id=UUID.nameUUIDFromBytes(("pending_returns/"+from.account()+".json").getBytes());Path destination=store.file(from,id);
    try(var disk=mockStatic(QueueFiles.class,CALLS_REAL_METHODS)){
      disk.when(()->QueueFiles.write(eq(destination),any(),eq(false))).thenAnswer(call->{call.callRealMethod();throw new IOException("after publication");});
      assertThrows(IOException.class,()->store.migrate(identity));
    }
    assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from));store.requireWritable(to);assertThrows(IOException.class,()->store.requireMigrated(from));
    when(identity.heir(from.account())).thenReturn(Optional.of(to));store.migrate(identity);store.requireWritable(from);assertTrue(store.rows(to).isEmpty());
  }
  @Test void estateTransfersPreserveUnknownFieldsAndCumulativeReceiptsThroughWaitingAndNewRevisions()throws Exception{
    var store=store();var one=holding(store,"escrow");var two=holding(store,"held");holding(store,"consumed");UUID death=UUID.randomUUID();
    assertTrue(store.retained(death,"waiting",from).isEmpty());var receipts=transfer(store,death,"one",from,to,()->true);assertEquals(2,receipts.size());
    assertEquals(receipts,transfer(store,death,"one",from,to,()->true));assertEquals(receipts,store.retained(death,"frozen",from));
    assertEquals(1,store.rows(from).size());assertEquals(2,store.rows(to).size());
    assertTrue(store.rows(to).getFirst().getAsJsonObject("future").get("nested").isJsonNull());
    store.create(from,ReturnStore.id(one),payload("pending"));byte[] original=Files.readAllBytes(store.file(from,ReturnStore.id(one)));byte[] saved=Files.readAllBytes(estate(death));assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"two",from,to,()->true));
    for(String retained:List.of("waiting","frozen"))assertThrows(ReturnStore.Conflict.class,()->new ReturnStore(root).retained(death,retained,from));
    assertArrayEquals(original,Files.readAllBytes(store.file(from,ReturnStore.id(one))));assertArrayEquals(saved,Files.readAllBytes(estate(death)));
    Files.delete(store.file(from,ReturnStore.id(one)));store.create(from,ReturnStore.id(one),payload("consumed"));assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"two",from,to,()->true));
  }
  @Test void ambiguousEffectsAndMalformedOwnersBlockEstateWithoutAnyDelivery()throws Exception{
    var store=store();var row=holding(store,"depositing");UUID death=UUID.randomUUID();assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true));
    store.update(row,r->r.addProperty("state","delivering"));assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true));
    assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,from,()->true));
    var wrongOwner=new QueueIdentity.Owner(UUID.randomUUID(),from.character());assertThrows(ReturnStore.Conflict.class,()->store.rows(wrongOwner));
  }
  @Test void guardsPrecedeEveryPublicationAndDeletionAndUntouchedPlansCanUseANewDecision()throws Exception{
    var store=store();var row=holding(store,"pending");UUID death=UUID.randomUUID();AtomicInteger checks=new AtomicInteger();
    assertThrows(IOException.class,()->transfer(store,death,"one",from,to,()->checks.incrementAndGet()==1));
    assertFalse(Files.exists(store.file(to,ReturnStore.id(row))));store.retained(death,"waiting",from);
    var another=new QueueIdentity.Owner(UUID.randomUUID(),UUID.randomUUID());
    assertEquals(1,transfer(store,death,"two",from,another,()->true).size());assertFalse(Files.exists(store.file(to,ReturnStore.id(row))));
    var otherStore=store();var next=holding(otherStore,"pending");UUID second=UUID.randomUUID();assertThrows(IOException.class,()->transfer(store,second,"one",from,to,()->false));
    checks.set(0);assertThrows(IOException.class,()->transfer(store,second,"one",from,to,()->checks.incrementAndGet()<3));
    assertTrue(Files.exists(store.file(from,ReturnStore.id(next))));assertTrue(Files.exists(store.file(to,ReturnStore.id(next))));
    assertThrows(ReturnStore.Conflict.class,()->transfer(store,second,"two",from,another,()->true));assertThrows(ReturnStore.Conflict.class,()->store.retained(second,"frozen",from));
  }
  @Test void fanoutRestartNeverCompletesAStalePartialDecisionAndMovedIdsRemainReceipts()throws Exception{
    var store=store();var first=holding(store,"pending");var second=holding(store,"pending");UUID death=UUID.randomUUID();AtomicInteger checks=new AtomicInteger();
    assertThrows(IOException.class,()->transfer(store,death,"one",from,to,()->checks.incrementAndGet()<5));
    var another=new QueueIdentity.Owner(UUID.randomUUID(),UUID.randomUUID());assertThrows(ReturnStore.Conflict.class,()->transfer(new ReturnStore(root),death,"two",from,another,()->true));
    assertEquals(2,transfer(store,death,"one",from,to,()->true).size());assertTrue(store.rows(from).isEmpty());assertEquals(2,store.rows(to).size());
    assertTrue(store.rows(to).stream().anyMatch(r->ReturnStore.id(r).equals(ReturnStore.id(first))));assertTrue(store.rows(to).stream().anyMatch(r->ReturnStore.id(r).equals(ReturnStore.id(second))));
  }
  @Test void sourceRemovalAndDestinationReceiptFailureCutsResumeOnlyForTheSameDecision()throws Exception{
    var store=store();var row=holding(store,"pending");UUID death=UUID.randomUUID();Path target=store.file(to,ReturnStore.id(row));
    try(var disk=mockStatic(QueueFiles.class,CALLS_REAL_METHODS)){
      disk.when(()->QueueFiles.write(eq(target),any(),eq(false))).thenAnswer(call->{call.callRealMethod();throw new IOException("after destination");});
      assertThrows(IOException.class,()->transfer(store,death,"one",from,to,()->true));
    }
    assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"two",from,to,()->true));
    byte[] beforeRemoval=Files.readAllBytes(store.file(from,ReturnStore.id(row)));
    try(var disk=mockStatic(QueueFiles.class,CALLS_REAL_METHODS)){
      disk.when(()->QueueFiles.delete(store.file(from,ReturnStore.id(row)))).thenAnswer(call->{call.callRealMethod();throw new IOException("after deletion");});
      Files.write(store.file(from,ReturnStore.id(row)),beforeRemoval);
      assertThrows(IOException.class,()->transfer(store,death,"one",from,to,()->true));
    }
    assertEquals(List.of(ReturnStore.id(row).toString()),transfer(store,death,"one",from,to,()->true));
  }
  @Test void changedOrMissingFilesAndClashingRecipientOperationsAreNeverOverwritten()throws Exception{
    var store=store();var row=holding(store,"pending");UUID death=UUID.randomUUID();var calls=new AtomicInteger();
    assertThrows(IOException.class,()->transfer(store,death,"one",from,to,()->calls.incrementAndGet()==1));Path source=store.file(from,ReturnStore.id(row));byte[] before=Files.readAllBytes(source);
    var changedRow=row.deepCopy();changedRow.addProperty("remaining",1);Files.write(source,QueueJson.bytes(changedRow));assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true));
    Files.delete(source);assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true));Files.write(source,before);
    Path target=store.file(to,ReturnStore.id(row));Files.createDirectories(target.getParent());Files.writeString(target,"rival");assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true));assertEquals("rival",Files.readString(target));
    Files.delete(target);transfer(store,death,"one",from,to,()->true);store.requireWritable(from);store.requireWritable(to);
  }
  @Test void rawNormalSavesRefuseStaleWritersAndRestartHoldsUnreadableDocuments()throws Exception{
    var store=store();var row=holding(store,"escrow");store.update(row,r->r.addProperty("remaining",2));assertThrows(ReturnStore.Conflict.class,()->store.update(row,r->r.addProperty("remaining",1)));
    store.recover();assertEquals("pending",ReturnStore.text(store.rows(from).getFirst(),"state"));
    Files.writeString(store.file(from,ReturnStore.id(row)),"null");store.recover();assertThrows(ReturnStore.Conflict.class,()->store.rows(from));
    Files.createDirectories(root.resolve("character-returns"));Files.writeString(root.resolve("character-returns/opaque"),"keep");assertEquals(2,store.allFiles().size());
  }

  void replacePayload(JsonObject plan,String part,JsonObject row){byte[] bytes=QueueJson.bytes(row);plan.addProperty(part,Base64.getEncoder().encodeToString(bytes));plan.addProperty(part+"-hash",ReturnStore.hash(bytes));}
  @Test void corruptOrHashConsistentAlteredEstatePlansCannotChangeRefsPathsPayloadOrState()throws Exception{
    var store=store();var row=holding(store,"pending");UUID death=UUID.randomUUID();var checks=new AtomicInteger();
    assertThrows(IOException.class,()->transfer(store,death,"one",from,to,()->checks.incrementAndGet()==1));var valid=journal(death);String id=ReturnStore.id(row).toString();byte[] before=Files.readAllBytes(store.file(from,ReturnStore.id(row)));
    for(String field:List.of("format","death","from-account","from-character")){
      JsonObject changed=valid.deepCopy();changed.addProperty(field,"invalid");save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true),field);
    }
    for(String field:List.of("kind","format","phase","before","after","before-hash","after-hash","from-account","from-character","to-account","to-character","decision","revision")){
      JsonObject changed=valid.deepCopy();JsonObject plan=changed.getAsJsonObject(id);if(field.equals("decision"))plan.remove(field);else plan.addProperty(field,"invalid");save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true),field);
    }
    for(String field:List.of("source","target"))for(String value:List.of("../outside.json","character-returns/"+UUID.randomUUID()+"/"+id+".json")){
      JsonObject changed=valid.deepCopy();changed.getAsJsonObject(id).addProperty(field,value);save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true));
    }
    for(String part:List.of("before","after"))for(String field:List.of("id","owner-account","owner-character","state","future")){
      JsonObject changed=valid.deepCopy();JsonObject plan=changed.getAsJsonObject(id);JsonObject raw=ReturnStore.readPayload(plan,part);raw.addProperty(field,UUID.randomUUID().toString());replacePayload(plan,part,raw);save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true),part+field);
    }
    var changed=valid.deepCopy();var plan=changed.getAsJsonObject(id);var raw=ReturnStore.readPayload(plan,"after");var foreign=new QueueIdentity.Owner(UUID.randomUUID(),UUID.randomUUID());ReturnStore.setRef(plan,"to",foreign);ReturnStore.setOwner(raw,foreign);replacePayload(plan,"after",raw);plan.addProperty("target",root.toAbsolutePath().relativize(store.file(foreign,ReturnStore.id(row))).toString());save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true));
    save(estate(death),valid);assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from));assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(to));store.requireWritable(foreign);
    assertThrows(ReturnStore.Conflict.class,()->store.retained(death,"one",foreign));assertArrayEquals(before,Files.readAllBytes(store.file(from,ReturnStore.id(row))));
    Files.writeString(estate(death),"{}");assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from));
  }
  @Test void migrationJournalAndSourceCorruptionAreHeldBeforePublishingAnyAlteredRecipient()throws Exception{
    var store=store();var identity=identity();Path source=legacy("pending_returns",from.account()+".json","{\"type\":\"DIAMOND\",\"future\":null}");UUID id=UUID.nameUUIDFromBytes(("pending_returns/"+from.account()+".json").getBytes());Path target=store.file(from,id);
    try(var disk=mockStatic(QueueFiles.class,CALLS_REAL_METHODS)){
      disk.when(()->QueueFiles.write(eq(target),any(),eq(false))).thenThrow(new IOException("before publication"));assertThrows(IOException.class,()->store.migrate(identity));
    }
    Path file=root.resolve("ownership/migration/"+id+".json");JsonObject valid=ReturnStore.read(file);assertThrows(IOException.class,()->store.requireMigrated(from));
    for(String field:List.of("source","target","from-account")){
      JsonObject changed=valid.deepCopy();changed.addProperty(field,field.equals("source")?"pending_returns/other.json":field.equals("target")?"../outside":UUID.randomUUID().toString());save(file,changed);assertThrows(ReturnStore.Conflict.class,()->store.migrate(identity),field);assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from),field);assertThrows(ReturnStore.Conflict.class,()->store.requireMigrated(from),field);
    }
    for(String field:List.of("id","owner-account","owner-character","legacy-source","legacy-item")){
      JsonObject changed=valid.deepCopy();JsonObject after=ReturnStore.readPayload(changed,"after");after.addProperty(field,UUID.randomUUID().toString());replacePayload(changed,"after",after);save(file,changed);assertThrows(ReturnStore.Conflict.class,()->store.migrate(identity),field);assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from),field);assertThrows(ReturnStore.Conflict.class,()->store.requireMigrated(from),field);
    }
    JsonObject changed=valid.deepCopy();changed.addProperty("from-account",UUID.randomUUID().toString());var after=ReturnStore.readPayload(changed,"after");after.addProperty("owner-account",ReturnStore.text(changed,"from-account"));replacePayload(changed,"after",after);save(file,changed);assertThrows(ReturnStore.Conflict.class,()->store.migrate(identity));
    save(file,valid);byte[] original=Files.readAllBytes(source);Files.writeString(source,"changed");assertThrows(ReturnStore.Conflict.class,()->store.migrate(identity));assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from));Files.write(source,original);
    Path backup=root.resolve("ownership/backups/"+ReturnStore.hash(original)+".bin");Files.writeString(backup,"changed");assertThrows(ReturnStore.Conflict.class,()->store.migrate(identity));Files.write(backup,original);
    store.migrate(identity);store.requireMigrated(from);
    Files.writeString(file,"{}");assertThrows(ReturnStore.Conflict.class,()->store.requireMigrated(from));
  }
  @Test void malformedNativeEnvelopesAndUnsupportedSchemaRemainRawAndRecoverable()throws Exception{
    var store=store();var row=holding(store,"pending");Path file=store.file(from,ReturnStore.id(row));byte[] valid=Files.readAllBytes(file);
    for(String field:List.of("format","id","state","owner-account","owner-character")){
      JsonObject raw=row.deepCopy();raw.addProperty(field,"invalid");save(file,raw);assertThrows(ReturnStore.Conflict.class,()->store.rows(from),field);
    }
    JsonObject raw=row.deepCopy();raw.addProperty("id",UUID.randomUUID().toString());save(file,raw);assertThrows(ReturnStore.Conflict.class,()->store.rows(from));
    raw=row.deepCopy();raw.addProperty("owner-character",UUID.randomUUID().toString());save(file,raw);assertThrows(ReturnStore.Conflict.class,()->store.rows(from));
    raw=row.deepCopy();raw.remove("format");save(file,raw);assertThrows(ReturnStore.Conflict.class,()->store.rows(from));
    Files.write(file,valid);assertThrows(FileAlreadyExistsException.class,()->store.create(from,ReturnStore.id(row),payload("pending")));
    var sameCharacterOtherAccount=new QueueIdentity.Owner(UUID.randomUUID(),from.character());assertThrows(ReturnStore.Conflict.class,()->transfer(store,UUID.randomUUID(),"one",from,sameCharacterOtherAccount,()->true));
  }
  @Test void missingLegacyPlanOpaquePayloadAndAmbiguousEscrowCannotBeAcknowledgedAsMigrated()throws Exception{
    var store=store();var identity=identity();Path source=legacy("pending_returns",from.account()+".json","opaque");
    legacy("pending_returns",UUID.randomUUID()+".json","opaque other account");
    assertThrows(IOException.class,()->store.requireMigrated(from));store.requireMigrated(to);
    store.migrate(identity);UUID id=UUID.nameUUIDFromBytes(("pending_returns/"+from.account()+".json").getBytes());Path file=root.resolve("ownership/migration/"+id+".json");JsonObject valid=ReturnStore.read(file);
    JsonObject changed=valid.deepCopy();JsonObject after=ReturnStore.readPayload(changed,"after");after.addProperty("legacy-bytes","different");replacePayload(changed,"after",after);save(file,changed);assertThrows(ReturnStore.Conflict.class,()->store.migrate(identity));
    changed=valid.deepCopy();after=ReturnStore.readPayload(changed,"after");after.remove("legacy-source");replacePayload(changed,"after",after);save(file,changed);assertThrows(ReturnStore.Conflict.class,()->store.migrate(identity));save(file,valid);
    Path escrow=legacy("escrow",from.account()+".json","{}");store.migrate(identity);id=UUID.nameUUIDFromBytes(("escrow/"+from.account()+".json").getBytes());file=root.resolve("ownership/migration/"+id+".json");valid=ReturnStore.read(file);
    changed=valid.deepCopy();after=ReturnStore.readPayload(changed,"after");after.addProperty("state","pending");replacePayload(changed,"after",after);save(file,changed);assertThrows(ReturnStore.Conflict.class,()->store.migrate(identity));
    assertEquals("{}",Files.readString(escrow));assertEquals("opaque",Files.readString(source));
  }
  @Test void syntacticallyValidConflictingRefsAndLostPublishedCopiesStayBlocked()throws Exception{
    var store=store();var row=holding(store,"pending");UUID death=UUID.randomUUID();var checks=new AtomicInteger();
    assertThrows(IOException.class,()->transfer(store,death,"one",from,to,()->checks.incrementAndGet()<3));var valid=journal(death);String id=ReturnStore.id(row).toString();
    JsonObject changed=valid.deepCopy();changed.addProperty("death",UUID.randomUUID().toString());save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->store.retained(death,"one",from));
    changed=valid.deepCopy();changed.addProperty("from-account",UUID.randomUUID().toString());save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->store.retained(death,"one",from));
    changed=valid.deepCopy();changed.getAsJsonObject(id).addProperty("from-account",UUID.randomUUID().toString());save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->store.retained(death,"one",from));
    changed=valid.deepCopy();var plan=changed.getAsJsonObject(id);var before=ReturnStore.readPayload(plan,"before");before.addProperty("owner-account",UUID.randomUUID().toString());replacePayload(plan,"before",before);save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->store.retained(death,"one",from));
    save(estate(death),valid);Files.delete(store.file(from,ReturnStore.id(row)));Files.delete(store.file(to,ReturnStore.id(row)));
    assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"one",from,to,()->true));
    Path relocated=root.resolve("ownership/estates/"+UUID.randomUUID()+".json");Files.move(estate(death),relocated);assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from));
  }
  @Test void sourceDerivedMigrationIdentityAndNamespaceAreValidatedBeforeWriterLocks()throws Exception{
    var store=store();var identity=identity();legacy("pending_returns",from.account()+".json","{}");store.migrate(identity);
    Path file=Files.list(root.resolve("ownership/migration")).findFirst().orElseThrow();JsonObject valid=ReturnStore.read(file);
    for(String relative:List.of("character-returns/"+from.account()+".json","escrow/"+from.account()+".json")){
      Path source=root.resolve(relative);Files.createDirectories(source.getParent());Files.writeString(source,"{}");var changed=valid.deepCopy();changed.addProperty("source",relative);save(file,changed);assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from));
    }
    save(file,valid);Files.move(file,file.resolveSibling(UUID.randomUUID()+".json"));assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from));
  }
  @Test void historicalReceiptsBindCapturedDeathRefsRecipientAndIntegralRevisionWithoutMutatingFiles()throws Exception{
    var store=store();var row=holding(store,"pending");UUID death=UUID.randomUUID();transfer(store,death,"one",from,to,()->true);var valid=journal(death);String id=ReturnStore.id(row).toString();Path target=store.file(to,ReturnStore.id(row));byte[] delivered=Files.readAllBytes(target);
    for(String field:List.of("to-account","to-character")){
      var changed=valid.deepCopy();var plan=changed.getAsJsonObject(id);var after=ReturnStore.readPayload(plan,"after");String value=UUID.randomUUID().toString();plan.addProperty(field,value);after.addProperty(field.replace("to-","owner-"),value);replacePayload(plan,"after",after);plan.addProperty("target","character-returns/"+ReturnStore.owner(after).character()+"/"+id+".json");save(estate(death),changed);byte[] evidence=Files.readAllBytes(estate(death));
      assertThrows(ReturnStore.Conflict.class,()->store.retained(death,"waiting",from));assertThrows(ReturnStore.Conflict.class,()->transfer(store,death,"two",from,to,()->true));assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(to));
      assertArrayEquals(evidence,Files.readAllBytes(estate(death)));assertArrayEquals(delivered,Files.readAllBytes(target));assertFalse(Files.exists(store.file(from,ReturnStore.id(row))));
    }
    for(String ending:List.of("-1","01","9223372036854775808")){
      var changed=valid.deepCopy();var plan=changed.getAsJsonObject(id);plan.addProperty("decision",ReturnStore.text(plan,"decision").replace(", revision=1]",", revision="+ending+"]"));save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->store.retained(death,"waiting",from));
    }
    var changed=valid.deepCopy();changed.getAsJsonObject(id).addProperty("revision",2);save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->store.retained(death,"waiting",from));
    changed=valid.deepCopy();var plan=changed.getAsJsonObject(id);plan.addProperty("decision",ReturnStore.text(plan,"decision").replace(death.toString(),UUID.randomUUID().toString()));save(estate(death),changed);assertThrows(ReturnStore.Conflict.class,()->store.retained(death,"waiting",from));
    save(estate(death),valid);assertEquals(List.of(id),store.retained(death,"waiting",from));
  }
  @Test void legacyRecipientRemainsItsSeparatelyCapturedFullReferenceEvenWithinOneAccount()throws Exception{
    var store=store();var identity=identity();legacy("pending_returns",from.account()+".json","{}");store.migrate(identity);Path file=Files.list(root.resolve("ownership/migration")).findFirst().orElseThrow();var valid=ReturnStore.read(file);var changed=valid.deepCopy();var after=ReturnStore.readPayload(changed,"after");var replacement=new QueueIdentity.Owner(from.account(),UUID.randomUUID());ReturnStore.setOwner(after,replacement);replacePayload(changed,"after",after);changed.addProperty("target","character-returns/"+replacement.character()+"/"+ReturnStore.id(after)+".json");save(file,changed);
    assertThrows(ReturnStore.Conflict.class,()->store.migrate(identity));assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from));assertTrue(store.rows(replacement).isEmpty());assertEquals(1,store.rows(from).size());save(file,valid);store.requireWritable(from);
  }
  @Test void migrationTargetPathMustMatchItsFixedFullRecipient()throws Exception{
    var store=store();var identity=identity();legacy("pending_returns",from.account()+".json","{}");store.migrate(identity);Path file=Files.list(root.resolve("ownership/migration")).findFirst().orElseThrow();var plan=ReturnStore.read(file);plan.addProperty("target","character-returns/"+UUID.randomUUID()+"/"+file.getFileName());save(file,plan);assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from));
  }
  @Test void migrationCannotLaunderAValidForeignAccountThroughItsFixedRecipient()throws Exception{
    var store=store();var identity=identity();legacy("pending_returns",from.account()+".json","{}");store.migrate(identity);Path file=Files.list(root.resolve("ownership/migration")).findFirst().orElseThrow();var plan=ReturnStore.read(file);var after=ReturnStore.readPayload(plan,"after");var foreign=new QueueIdentity.Owner(UUID.randomUUID(),from.character());ReturnStore.setOwner(after,foreign);ReturnStore.setRef(plan,"to",foreign);replacePayload(plan,"after",after);save(file,plan);assertThrows(ReturnStore.Conflict.class,()->store.requireWritable(from));
  }
}
