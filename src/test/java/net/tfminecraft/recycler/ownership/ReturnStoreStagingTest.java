package net.tfminecraft.recycler.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.Recipient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReturnStoreStagingTest {
    @TempDir Path root;
    final QueueIdentity.Owner a = owner(), b = owner(), c = owner(), d = owner();
    QueueIdentity.Owner owner() { return new QueueIdentity.Owner(UUID.randomUUID(), UUID.randomUUID()); }
    JsonObject create(ReturnStore store, QueueIdentity.Owner owner, String state) throws IOException {
        return store.create(owner, UUID.randomUUID(), QueueJson.parse("{\"state\":\"" + state + "\",\"remaining\":3,\"future\":{\"null\":null}}"));
    }
    EstateDecision decision(UUID death, QueueIdentity.Owner from, QueueIdentity.Owner to) {
        return new EstateDecision(death, new CharacterRef(from.account(), from.character()), Optional.empty(), Instant.EPOCH,
                Optional.empty(), Optional.empty(), Optional.empty(), Recipient.FROZEN,
                Recipient.character(new CharacterRef(to.account(), to.character())), Recipient.FROZEN, Recipient.SENDER, 1);
    }
    List<String> transfer(ReturnStore store, EstateDecision decision, java.util.function.BooleanSupplier guard) throws IOException {
        CharacterRef to = decision.property().character().orElseThrow();
        return store.transfer(decision.deathId(), decision.toString(), new QueueIdentity.Owner(decision.testator().ownerId(), decision.testator().characterId()),
                new QueueIdentity.Owner(to.ownerId(), to.characterId()), guard);
    }
    Map<Path, byte[]> stage(String... folders) throws IOException {
        Map<Path, byte[]> buffers = new LinkedHashMap<>();
        for (String folder : folders) {
            Path directory = root.resolve(folder); Files.createDirectories(directory);
            for (byte[] bytes : List.of(new byte[]{0, -1, 7}, "{\"format\":1,\"partial\":".getBytes(StandardCharsets.UTF_8))) {
                Path buffer = Files.createTempFile(directory, "recycler-", ".tmp");
                Files.write(buffer, bytes); buffers.put(buffer, bytes);
            }
        }
        return buffers;
    }
    void preserved(Map<Path, byte[]> buffers) throws IOException {
        for (var entry : buffers.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()), entry.getKey().toString());
    }
    Map<Path, String> snapshot() throws IOException {
        Map<Path, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) result.put(path, Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        }
        return result;
    }

    @Test void actualInterruptedBuffersNeverEnterQueuesWritersMigrationOrCompletedEstateReplay() throws Exception {
        ReturnStore store = new ReturnStore(root);
        create(store, a, "pending");
        Path legacy = root.resolve("pending_returns/" + a.account() + ".json");
        Files.createDirectories(legacy.getParent()); Files.writeString(legacy, "{\"type\":\"DIAMOND\",\"future\":null}");
        QueueIdentity identity = mock(QueueIdentity.class);
        when(identity.heirsReady()).thenReturn(true); when(identity.heir(a.account())).thenReturn(Optional.of(a));
        store.migrate(identity);
        EstateDecision estate = decision(UUID.randomUUID(), a, b);
        List<String> receipts = transfer(store, estate, () -> true);
        assertEquals(2, receipts.size());
        Map<Path, byte[]> buffers = stage("character-returns", "character-returns/" + a.character(), "character-returns/" + b.character(),
                "ownership/migration", "ownership/estates", "pending_returns", "escrow");
        assertTrue(store.rows(a).isEmpty()); assertEquals(2, store.rows(b).size());
        assertTrue(Collections.disjoint(store.allFiles(), buffers.keySet()));
        store.requireWritable(a); store.requireWritable(b); store.requireMigrated(a); new ReturnStore(root).migrate(identity);
        assertEquals(receipts, transfer(new ReturnStore(root), estate, () -> true));
        assertEquals(receipts, new ReturnStore(root).retained(estate.deathId(), "waiting", a));
        JsonObject pending = create(store, a, "escrow");
        store.recover();
        JsonObject recovered = store.rows(a).getFirst();
        assertEquals(ReturnStore.id(pending), ReturnStore.id(recovered)); assertEquals("pending", ReturnStore.text(recovered, "state"));
        assertEquals(1, store.update(recovered, row -> row.addProperty("remaining", 1)).get("remaining").getAsInt());
        List<Path> rows = ReturnStore.files(store.file(b, UUID.randomUUID()).getParent());
        assertEquals(rows.stream().sorted().toList(), rows);
        assertEquals(2, rows.size());
        preserved(buffers);
    }

    @Test void stagingBuffersCannotHideOtherDeathReservationsOrRestoredCompletedSources() throws Exception {
        ReturnStore store = new ReturnStore(root); JsonObject original = create(store, a, "pending");
        UUID id = ReturnStore.id(original);
        EstateDecision first = decision(UUID.randomUUID(), a, b), independent = decision(UUID.randomUUID(), c, d);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IOException.class, () -> transfer(store, first, () -> checks.incrementAndGet() < 3));
        Map<Path, byte[]> buffers = stage("character-returns/" + a.character(), "character-returns/" + b.character(), "ownership/estates", "ownership/migration");
        JsonObject unrelated = create(store, c, "pending");
        assertEquals(List.of(ReturnStore.id(unrelated).toString()), transfer(store, independent, () -> true));
        EstateDecision rival = decision(UUID.randomUUID(), b, c);
        Map<Path, String> before = snapshot();
        assertThrows(ReturnStore.Conflict.class, () -> transfer(new ReturnStore(root), rival, () -> true));
        assertEquals(before, snapshot());
        assertEquals(List.of(id.toString()), transfer(new ReturnStore(root), first, () -> true));
        assertEquals(List.of(id.toString()), new ReturnStore(root).retained(first.deathId(), "frozen", a));
        QueueFiles.write(store.file(a, id), QueueJson.bytes(original), false);
        before = snapshot();
        assertThrows(ReturnStore.Conflict.class, () -> transfer(new ReturnStore(root), first, () -> true));
        assertThrows(ReturnStore.Conflict.class, () -> new ReturnStore(root).retained(first.deathId(), "waiting", a));
        assertEquals(before, snapshot()); preserved(buffers);
    }

    @Test void unknownNonStagingNamesStayVisibleAndHoldWithEveryBytePreserved() throws Exception {
        ReturnStore store = new ReturnStore(root); create(store, a, "pending");
        Map<Path, byte[]> buffers = stage("character-returns/" + a.character(), "ownership/estates", "ownership/migration");
        Path unknown = root.resolve("character-returns/" + a.character() + "/recycler-unknown.json");
        Files.writeString(unknown, "preserve unknown record");
        Map<Path, String> before = snapshot();
        assertTrue(store.allFiles().contains(unknown)); assertThrows(ReturnStore.Conflict.class, () -> store.rows(a));
        assertEquals(before, snapshot());
        for (String folder : List.of("ownership/migration", "ownership/estates")) {
            Path journal = root.resolve(folder + "/unknown.tmp"); Files.writeString(journal, "malformed unattributable journal");
            before = snapshot();
            assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(a));
            assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(c));
            assertEquals(before, snapshot());
            // Only this fixture moves evidence out of the authoritative directory for its next control.
            Files.move(journal, root.resolve(folder.replace('/', '-') + "-saved.bin"));
        }
        preserved(buffers);
    }

    @Test void directoriesAndSymlinksWithReservedNamesAreStillUnknownRatherThanIgnored() throws Exception {
        ReturnStore store = new ReturnStore(root); create(store, a, "pending");
        Path folder = root.resolve("ownership/estates"); Files.createDirectories(folder);
        Path directory = folder.resolve("recycler-directory.tmp"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("evidence.bin"), "directory evidence");
        assertTrue(ReturnStore.files(folder).contains(directory));
        Map<Path, String> before = snapshot();
        assertThrows(IOException.class, () -> transfer(store, decision(UUID.randomUUID(), a, b), () -> true));
        assertEquals(before, snapshot());
        Files.move(directory, root.resolve("preserved-directory"));
        Path evidence = root.resolve("evidence.bin"); Files.writeString(evidence, "symlink evidence");
        Path link = folder.resolve("recycler-link.tmp"); Files.createSymbolicLink(link, evidence);
        assertTrue(ReturnStore.files(folder).contains(link));
        before = snapshot();
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(a));
        assertEquals(before, snapshot()); assertEquals(evidence, Files.readSymbolicLink(link));
    }
}
