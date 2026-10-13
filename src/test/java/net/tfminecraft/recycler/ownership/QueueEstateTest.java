package net.tfminecraft.recycler.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.nio.file.*;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.manager.*;
import net.tfminecraft.tlibs.character.*;
import net.tfminecraft.tlibs.character.estate.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueueEstateTest {
  @TempDir Path root;
  final CharacterRef from=new CharacterRef(UUID.randomUUID(),UUID.randomUUID()),to=new CharacterRef(UUID.randomUUID(),UUID.randomUUID());
  QueueIdentity.Owner owner(CharacterRef ref){return new QueueIdentity.Owner(ref.ownerId(),ref.characterId());}
  EstateDecision decision(UUID death,Recipient property,long revision){return new EstateDecision(death,from,Optional.empty(),Instant.EPOCH,Optional.of(to),Optional.empty(),Optional.empty(),Recipient.FROZEN,property,Recipient.WAITING,Recipient.SENDER,revision);}
  Recycler plugin(ReturnStore store){var plugin=mock(Recycler.class);var escrow=mock(EscrowManager.class);var manager=mock(RecyclerManager.class);when(plugin.getEscrowManager()).thenReturn(escrow);when(plugin.getRecyclerManager()).thenReturn(manager);when(escrow.store()).thenReturn(store);when(manager.quiescent(any())).thenReturn(true);when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());return plugin;}
  com.google.gson.JsonObject row(String state)throws IOException{return QueueJson.parse("{\"state\":\""+state+"\",\"unknown\":null,\"item-bytes\":\"not runtime loadable\"}");}
  @Test void exactGuardsIdleSessionsAmbiguousEffectsAndFrozenReceiptsHaveDifferentOutcomes()throws Exception{
    var store=new ReturnStore(root);var plugin=plugin(store);var participant=new CharacterQueueEstate(plugin);UUID death=UUID.randomUUID();var decision=decision(death,Recipient.character(to),1);
    try(var estates=mockStatic(Estates.class)){
      assertEquals(Outcome.retry("Current Recycler estate decision does not hold"),participant.settle(decision));
      estates.when(()->Estates.guardHolds(any())).thenReturn(true);when(plugin.getRecyclerManager().quiescent(owner(from))).thenReturn(false);
      assertEquals(Outcome.Kind.RETRY,participant.settle(decision).kind());when(plugin.getRecyclerManager().quiescent(owner(from))).thenReturn(true);
      when(plugin.getRecyclerManager().quiescent(owner(to))).thenReturn(false);assertEquals(Outcome.Kind.RETRY,participant.settle(decision).kind());when(plugin.getRecyclerManager().quiescent(owner(to))).thenReturn(true);
      var original=store.create(owner(from),UUID.randomUUID(),row("pending"));var outcome=participant.settle(decision);assertEquals(Outcome.done(Map.of("returns",1),List.of(ReturnStore.id(original).toString())),outcome);
      assertEquals(outcome,participant.settle(decision));assertEquals(outcome,participant.settle(decision(death,Recipient.FROZEN,2)));assertEquals(outcome,participant.settle(decision(death,Recipient.WAITING,3)));
      store.create(owner(from),UUID.randomUUID(),row("depositing"));assertEquals(Outcome.Kind.BLOCKED,participant.settle(decision).kind());
    }
  }
  @Test void corruptAndUnavailableStorageAreBlockedAndRetryAndRawHoldingsIncludeUnloadableRows()throws Exception{
    var store=spy(new ReturnStore(root));var plugin=plugin(store);var participant=new CharacterQueueEstate(plugin);var decision=decision(UUID.randomUUID(),Recipient.character(to),1);
    try(var estates=mockStatic(Estates.class);var characters=mockStatic(Characters.class)){
      estates.when(()->Estates.guardHolds(any())).thenReturn(true);
      doThrow(new IOException("temporary unavailable")).when(store).requireMigrated(any());assertEquals(Outcome.Kind.RETRY,participant.settle(decision).kind());
      doThrow(new ReturnStore.Conflict("corrupt metadata")).when(store).requireMigrated(any());assertEquals(Outcome.Kind.BLOCKED,participant.settle(decision).kind());doCallRealMethod().when(store).requireMigrated(any());
      assertEquals(Map.of("heldQueues",1),participant.describe(from.characterId()).counts());
      characters.when(()->Characters.info(from.characterId())).thenReturn(Optional.of(new CharacterInfo(from,"Original",CharacterStatus.DEAD,null,Set.of(),0,null)));
      assertEquals(Holdings.NONE,participant.describe(from.characterId()));var row=store.create(owner(from),UUID.randomUUID(),row("held"));assertEquals(Map.of("returns",1),participant.describe(from.characterId()).counts());
      store.update(row,r->r.addProperty("state","delivered"));assertEquals(Holdings.NONE,participant.describe(from.characterId()));
      characters.when(()->Characters.info(from.characterId())).thenReturn(Optional.of(new CharacterInfo(to,"Wrong",CharacterStatus.ALIVE,null,Set.of(),0,null)));assertEquals(Map.of("heldQueues",1),participant.describe(from.characterId()).counts());
      characters.when(()->Characters.info(from.characterId())).thenReturn(Optional.of(new CharacterInfo(from,"Original",CharacterStatus.DEAD,null,Set.of(),0,null)));
      doThrow(new IOException("disk unavailable")).when(store).rows(any());assertEquals(Map.of("heldQueues",1),participant.describe(from.characterId()).counts());
    }
  }
  @Test void bootHourlyReconcileReportsChangedRevisionsAndOutcomesOnlyAndIsolatesOneEstateFailure()throws Exception{
    var store=new ReturnStore(root);var plugin=plugin(store);var participant=spy(new CharacterQueueEstate(plugin));var one=decision(UUID.randomUUID(),Recipient.WAITING,1);var two=decision(UUID.randomUUID(),Recipient.FROZEN,1);
    try(var estates=mockStatic(Estates.class)){
      estates.when(Estates::decided).thenReturn(List.of(one,two));estates.when(()->Estates.guardHolds(one)).thenReturn(true);estates.when(()->Estates.guardHolds(two)).thenReturn(false);
      estates.when(()->Estates.report(any(),anyString(),anyLong(),any())).thenReturn(false);participant.reconcile();
      estates.when(()->Estates.report(any(),anyString(),anyLong(),any())).thenReturn(true);participant.reconcile();participant.reconcile();
      estates.verify(()->Estates.report(eq(one.deathId()),eq("recycler"),eq(1L),any()),times(2));
      var revised=decision(one.deathId(),Recipient.FROZEN,2);estates.when(Estates::decided).thenReturn(List.of(revised,two));estates.when(()->Estates.guardHolds(revised)).thenReturn(true);estates.when(()->Estates.guardHolds(two)).thenReturn(true);
      doThrow(new IllegalStateException("one bad estate")).when(participant).settle(two);participant.reconcile();
      estates.verify(()->Estates.report(eq(revised.deathId()),eq("recycler"),eq(2L),any()));
    }
    var server=mock(org.bukkit.Server.class);var services=mock(org.bukkit.plugin.ServicesManager.class);var scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);
    when(plugin.getServer()).thenReturn(server);when(server.getServicesManager()).thenReturn(services);when(server.getScheduler()).thenReturn(scheduler);
    QueueEstate.start(plugin);verify(services).register(eq(EstateParticipant.class),any(CharacterQueueEstate.class),eq(plugin),eq(org.bukkit.plugin.ServicePriority.Normal));verify(scheduler).runTaskTimer(eq(plugin),any(Runnable.class),eq(1L),eq(72_000L));
  }
}
