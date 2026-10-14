package net.tfminecraft.recycler.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.manager.EscrowManager;
import net.tfminecraft.recycler.manager.RecyclerManager;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.Outcome;
import net.tfminecraft.tlibs.character.estate.Recipient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReturnStoreReservationTest {
    @TempDir Path root;
    final QueueIdentity.Owner a = owner(), b = owner(), c = owner();

    static QueueIdentity.Owner owner() { return new QueueIdentity.Owner(UUID.randomUUID(), UUID.randomUUID()); }
    CharacterRef ref(QueueIdentity.Owner owner) { return new CharacterRef(owner.account(), owner.character()); }
    EstateDecision decision(UUID death, QueueIdentity.Owner from, QueueIdentity.Owner to) {
        return new EstateDecision(death, ref(from), Optional.empty(), Instant.EPOCH, Optional.empty(), Optional.empty(),
                Optional.empty(), Recipient.FROZEN, Recipient.character(ref(to)), Recipient.FROZEN, Recipient.SENDER, 1);
    }
    JsonObject create(ReturnStore store, QueueIdentity.Owner owner) throws IOException {
        return store.create(owner, UUID.randomUUID(), QueueJson.parse("{\"state\":\"pending\",\"item-bytes\":\"opaque\",\"future\":{\"kept\":null},\"remaining\":3}"));
    }
    Path journal(UUID death) { return root.resolve("ownership/estates/" + death + ".json"); }
    List<String> transfer(ReturnStore store, EstateDecision decision, java.util.function.BooleanSupplier guard) throws IOException {
        return store.transfer(decision.deathId(), decision.toString(), new QueueIdentity.Owner(decision.testator().ownerId(), decision.testator().characterId()),
                new QueueIdentity.Owner(decision.property().character().orElseThrow().ownerId(), decision.property().character().orElseThrow().characterId()), guard);
    }
    CharacterQueueEstate participant(ReturnStore store) {
        Recycler plugin = mock(Recycler.class);
        EscrowManager escrow = mock(EscrowManager.class);
        RecyclerManager manager = mock(RecyclerManager.class);
        when(plugin.getEscrowManager()).thenReturn(escrow);
        when(plugin.getRecyclerManager()).thenReturn(manager);
        when(escrow.store()).thenReturn(store);
        when(manager.quiescent(any())).thenReturn(true);
        return new CharacterQueueEstate(plugin);
    }
    Map<Path, String> files() throws IOException {
        Map<Path, String> files = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList())
                files.put(root.relativize(file), ReturnStore.hash(Files.readAllBytes(file)));
        }
        return files;
    }
    void assertBlockedWithoutPublication(ReturnStore store, EstateDecision decision) throws Exception {
        Map<Path, String> before = files();
        try (var estates = mockStatic(Estates.class); var disk = mockStatic(QueueFiles.class, CALLS_REAL_METHODS)) {
            estates.when(() -> Estates.guardHolds(any())).thenReturn(true);
            assertEquals(Outcome.Kind.BLOCKED, participant(store).settle(decision).kind());
            disk.verifyNoInteractions();
        }
        assertEquals(before, files());
    }

    @Test void publishedRecipientStaysHeldUntilExactResumeThenCompletedEstateChainCanMoveIt() throws Exception {
        ReturnStore store = new ReturnStore(root);
        JsonObject original = create(store, a);
        UUID id = ReturnStore.id(original);
        EstateDecision first = decision(UUID.randomUUID(), a, b), second = decision(UUID.randomUUID(), b, c);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(store, first, () -> checks.incrementAndGet() < 3));
        assertEquals("published", ReturnStore.text(ReturnStore.read(journal(first.deathId())).getAsJsonObject(id.toString()), "phase"));
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(b));
        byte[] source = Files.readAllBytes(store.file(a, id)), target = Files.readAllBytes(store.file(b, id));
        byte[] heldJournal = Files.readAllBytes(journal(first.deathId()));
        try (var estates = mockStatic(Estates.class)) {
            estates.when(() -> Estates.guardHolds(any())).thenReturn(true);
            assertEquals(Outcome.Kind.BLOCKED, participant(new ReturnStore(root)).settle(second).kind());
        }
        assertArrayEquals(source, Files.readAllBytes(store.file(a, id)));
        assertArrayEquals(target, Files.readAllBytes(store.file(b, id)));
        assertArrayEquals(heldJournal, Files.readAllBytes(journal(first.deathId())));
        assertFalse(Files.exists(journal(second.deathId())));
        assertFalse(Files.exists(store.file(c, id)));
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(b));
        assertEquals(List.of(id.toString()), transfer(new ReturnStore(root), first, () -> true));
        assertFalse(Files.exists(store.file(a, id)));
        assertTrue(Files.exists(store.file(b, id)));
        store.requireWritable(b);
        try (var estates = mockStatic(Estates.class)) {
            estates.when(() -> Estates.guardHolds(any())).thenReturn(true);
            assertEquals(Outcome.done(Map.of("returns", 1), List.of(id.toString())), participant(new ReturnStore(root)).settle(second));
        }
        assertFalse(Files.exists(store.file(b, id)));
        assertTrue(Files.exists(store.file(c, id)));
        store.requireWritable(c);
        assertEquals(List.of(id.toString()), transfer(new ReturnStore(root), first, () -> true));
        assertFalse(Files.exists(store.file(b, id)));
        assertTrue(store.rows(c).getFirst().getAsJsonObject("future").get("kept").isJsonNull());
    }

    @Test void plannedSourceReservationBlocksBothTransferAndRetainedWithoutAReceipt() throws Exception {
        ReturnStore store = new ReturnStore(root);
        JsonObject original = create(store, a);
        UUID id = ReturnStore.id(original);
        EstateDecision first = decision(UUID.randomUUID(), a, b), second = decision(UUID.randomUUID(), a, c);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(store, first, () -> checks.incrementAndGet() == 1));
        assertEquals("planned", ReturnStore.text(ReturnStore.read(journal(first.deathId())).getAsJsonObject(id.toString()), "phase"));
        assertBlockedWithoutPublication(new ReturnStore(root), second);
        Map<Path, String> before = files();
        assertThrows(ReturnStore.Conflict.class, () -> store.retained(second.deathId(), "waiting", a));
        assertEquals(before, files());
        assertEquals(List.of(id.toString()), transfer(new ReturnStore(root), first, () -> true));
    }

    @Test void publishedSourceDeletionCutResumesExactlyAndUnrelatedEstateStillCompletes() throws Exception {
        ReturnStore store = new ReturnStore(root);
        JsonObject original = create(store, a), unrelated = create(store, c);
        UUID id = ReturnStore.id(original), otherId = ReturnStore.id(unrelated);
        byte[] source = Files.readAllBytes(store.file(a, id));
        QueueIdentity.Owner d = owner();
        EstateDecision first = decision(UUID.randomUUID(), a, b), second = decision(UUID.randomUUID(), b, c);
        try (var disk = mockStatic(QueueFiles.class, CALLS_REAL_METHODS)) {
            disk.when(() -> QueueFiles.delete(store.file(a, id))).thenAnswer(call -> {
                call.callRealMethod(); throw new IOException("after source deletion, before moved receipt");
            });
            Files.write(store.file(a, id), source); // CALLS_REAL_METHODS evaluates the delete while configuring this fault.
            assertThrows(IOException.class, () -> transfer(store, first, () -> true));
        }
        assertFalse(Files.exists(store.file(a, id)));
        assertTrue(Files.exists(store.file(b, id)));
        assertBlockedWithoutPublication(new ReturnStore(root), second);
        assertEquals(List.of(otherId.toString()), transfer(new ReturnStore(root), decision(UUID.randomUUID(), c, d), () -> true));
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(b));
        assertEquals(List.of(id.toString()), transfer(new ReturnStore(root), first, () -> true));
        assertEquals(List.of(id.toString()), transfer(new ReturnStore(root), second, () -> true));
        assertTrue(Files.exists(store.file(d, otherId)));
    }

    @Test void reservationIncludesExistingOwnPlansAndPrecedesTheirRetirement() throws Exception {
        ReturnStore store = new ReturnStore(root);
        JsonObject original = create(store, a);
        UUID id = ReturnStore.id(original);
        EstateDecision own = decision(UUID.randomUUID(), a, b), rival = decision(UUID.randomUUID(), a, b);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(store, own, () -> checks.incrementAndGet() == 1));
        JsonObject other = ReturnStore.read(journal(own.deathId())).deepCopy();
        other.addProperty("death", rival.deathId().toString());
        other.getAsJsonObject(id.toString()).addProperty("decision", rival.toString());
        ReturnStore.write(journal(rival.deathId()), other);
        // Both valid fixed plans can already exist from the old implementation; no source survived this cut.
        Files.delete(store.file(a, id));
        Map<Path, String> before = files();
        assertThrows(ReturnStore.Conflict.class, () -> store.retained(own.deathId(), "new waiting decision", a));
        assertEquals(before, files());
        assertThrows(ReturnStore.Conflict.class, () -> transfer(new ReturnStore(root), own, () -> true));
        assertEquals(before, files());
    }

    @Test void malformedOrUnknownOtherJournalHoldsEvenAnUnrelatedEstateBeforeAnyWrites() throws Exception {
        ReturnStore store = new ReturnStore(root);
        JsonObject original = create(store, a);
        create(store, c);
        UUID id = ReturnStore.id(original);
        EstateDecision first = decision(UUID.randomUUID(), a, b), unrelated = decision(UUID.randomUUID(), c, owner());
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(store, first, () -> checks.incrementAndGet() < 3));
        JsonObject valid = ReturnStore.read(journal(first.deathId()));
        ReturnStore.write(journal(first.deathId()), new JsonObject());
        assertBlockedWithoutPublication(store, unrelated);
        JsonObject changed = valid.deepCopy();
        changed.getAsJsonObject(id.toString()).addProperty("phase", "future-phase");
        ReturnStore.write(journal(first.deathId()), changed);
        assertBlockedWithoutPublication(store, unrelated);
        ReturnStore.write(journal(first.deathId()), valid);
        Path wrongName = journal(UUID.randomUUID());
        Files.move(journal(first.deathId()), wrongName);
        assertBlockedWithoutPublication(store, unrelated);
        Files.move(wrongName, journal(first.deathId()));
        Files.writeString(journal(first.deathId()), "{\"death\":null,\"death\":\"duplicate\"}");
        assertBlockedWithoutPublication(store, unrelated);
    }

    @Test void unknownDirectoryShapeBlocksAndTransientListingFailureRetriesWithoutEffects() throws Exception {
        ReturnStore store = new ReturnStore(root);
        JsonObject original = create(store, a);
        EstateDecision decision = decision(UUID.randomUUID(), a, b);
        Path folder = root.resolve("ownership/estates");
        Files.createDirectories(folder.getParent());
        Files.writeString(folder, "preserve inaccessible journal directory shape");
        assertBlockedWithoutPublication(store, decision);
        Files.delete(folder);
        Files.createDirectories(folder);
        Map<Path, String> before = files();
        try (var estates = mockStatic(Estates.class); var disk = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            estates.when(() -> Estates.guardHolds(any())).thenReturn(true);
            disk.when(() -> Files.list(folder)).thenThrow(new IOException("temporary filesystem unavailable"));
            assertEquals(Outcome.Kind.RETRY, participant(store).settle(decision).kind());
        }
        assertEquals(before, files());
        assertEquals(original, store.rows(a).getFirst());
        assertFalse(Files.exists(journal(decision.deathId())));
    }

    void bytes(JsonObject plan, String part, byte[] bytes) {
        plan.addProperty(part, Base64.getEncoder().encodeToString(bytes));
        plan.addProperty(part + "-hash", ReturnStore.hash(bytes));
    }
    @Test void publishedMigrationRemainsFixedAndHeldWhenItsTargetHasDisappeared() throws Exception {
        ReturnStore store = new ReturnStore(root);
        QueueIdentity identity = mock(QueueIdentity.class);
        when(identity.heirsReady()).thenReturn(true);
        when(identity.heir(a.account())).thenReturn(Optional.of(a));
        Path source = root.resolve("pending_returns/" + a.account() + ".json");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "{\"type\":\"DIAMOND\",\"future\":{\"kept\":null}}");
        UUID id = UUID.nameUUIDFromBytes(root.relativize(source).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path migration = root.resolve("ownership/migration/" + id + ".json");
        try (var disk = mockStatic(QueueFiles.class, CALLS_REAL_METHODS)) {
            disk.when(() -> QueueFiles.write(eq(migration), any(byte[].class), eq(true))).thenAnswer(call -> {
                call.callRealMethod();
                JsonObject saved = QueueJson.parse(new String(call.getArgument(1, byte[].class), java.nio.charset.StandardCharsets.UTF_8));
                if ("published".equals(ReturnStore.text(saved, "phase"))) throw new IOException("after migration publication receipt");
                return null;
            });
            assertThrows(IOException.class, () -> store.migrate(identity));
        }
        assertEquals("published", ReturnStore.text(ReturnStore.read(migration), "phase"));
        assertTrue(Files.exists(store.file(a, id)));
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(a));
        Files.delete(store.file(a, id));
        when(identity.heir(a.account())).thenReturn(Optional.of(new QueueIdentity.Owner(a.account(), UUID.randomUUID())));
        Map<Path, String> before = files();
        assertThrows(ReturnStore.Conflict.class, () -> new ReturnStore(root).migrate(identity));
        assertEquals(before, files());
        try (var estates = mockStatic(Estates.class)) {
            estates.when(() -> Estates.guardHolds(any())).thenReturn(true);
            assertEquals(Outcome.Kind.RETRY, participant(store).settle(decision(UUID.randomUUID(), a, b)).kind());
        }
        assertEquals(before, files());
        assertFalse(Files.exists(store.file(a, id)));
    }
    @Test void oldCrossEstateDamageCannotRecreateMissingPublishedTargetButCompletedReceiptsRemainUsable() throws Exception {
        ReturnStore store = new ReturnStore(root);
        JsonObject original = create(store, a);
        UUID id = ReturnStore.id(original);
        EstateDecision first = decision(UUID.randomUUID(), a, b), second = decision(UUID.randomUUID(), b, c);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(store, first, () -> checks.incrementAndGet() < 3));
        byte[] before = Files.readAllBytes(store.file(b, id));
        JsonObject destination = ReturnStore.read(store.file(b, id));
        ReturnStore.setOwner(destination, c);
        byte[] after = QueueJson.bytes(destination);
        QueueFiles.write(store.file(c, id), after, false);
        Files.delete(store.file(b, id));
        // Model the completed d2 journal which the real old-code counterexample admitted.
        JsonObject journal = new JsonObject(), plan = new JsonObject();
        journal.addProperty("format", 1); journal.addProperty("death", second.deathId().toString()); ReturnStore.setRef(journal, "from", b);
        plan.addProperty("format", 1); plan.addProperty("kind", "estate"); plan.addProperty("phase", "moved");
        plan.addProperty("source", root.relativize(store.file(b, id)).toString()); plan.addProperty("target", root.relativize(store.file(c, id)).toString());
        bytes(plan, "before", before); bytes(plan, "after", after);
        ReturnStore.setRef(plan, "from", b); ReturnStore.setRef(plan, "to", c);
        plan.addProperty("decision", second.toString()); plan.addProperty("revision", 1); journal.add(id.toString(), plan);
        ReturnStore.write(journal(second.deathId()), journal);
        assertBlockedWithoutPublication(new ReturnStore(root), first);
        assertFalse(Files.exists(store.file(b, id)));
        assertTrue(Files.exists(store.file(a, id)));
        assertTrue(Files.exists(store.file(c, id)));
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(b));
        assertEquals(List.of(id.toString()), transfer(new ReturnStore(root), second, () -> true));
        assertEquals(List.of(id.toString()), store.retained(second.deathId(), "frozen", b));
        Files.delete(store.file(c, id)); // A completed receipt does not require an unconsumed live target forever.
        assertEquals(List.of(id.toString()), transfer(new ReturnStore(root), second, () -> true));
    }
}
