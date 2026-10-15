package net.tfminecraft.recycler.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.Recipient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReturnStoreIndexTest {
    @TempDir Path root;
    final QueueIdentity.Owner from = owner(), to = owner();

    QueueIdentity.Owner owner() { return new QueueIdentity.Owner(UUID.randomUUID(), UUID.randomUUID()); }
    JsonObject create(ReturnStore store, QueueIdentity.Owner owner, String state) throws IOException {
        JsonObject row = new JsonObject(); row.addProperty("state", state);
        return store.create(owner, UUID.randomUUID(), row);
    }

    @Test void anAccountRemainsPendingUntilItsLastNonterminalCharacterRecordFinishes() throws Exception {
        var store = new ReturnStore(root);
        var first = create(store, from, "pending");
        var otherCharacter = new QueueIdentity.Owner(from.account(), UUID.randomUUID());
        var second = create(store, otherCharacter, "held");
        create(store, to, "delivered");
        assertEquals(List.of(from.account()), store.pendingPlayerIds());
        first = store.update(first, row -> row.addProperty("state", "delivering"));
        store.update(first, row -> row.addProperty("state", "delivered"));
        assertEquals(List.of(from.account()), store.pendingPlayerIds());
        store.update(second, row -> row.addProperty("state", "cancelled"));
        assertTrue(store.pendingPlayerIds().isEmpty());
        assertEquals(1, store.rows(from).size());
        assertEquals(1, store.rows(otherCharacter).size());
    }

    @Test void recoverySeedsValidOwnersAndSnapshotReadsNeverDiscoverRetainedHistory() throws Exception {
        var original = new ReturnStore(root);
        create(original, from, "escrow");
        create(original, from, "delivering");
        create(original, to, "depositing");
        create(original, owner(), "consumed");
        var invalid = owner();
        var broken = create(original, invalid, "pending");
        Files.writeString(original.file(invalid, ReturnStore.id(broken)), "broken");
        var recovered = new ReturnStore(root);
        recovered.recover();
        assertEquals(Set.of(from.account(), to.account()), Set.copyOf(recovered.pendingPlayerIds()));
        assertEquals(Set.of("pending", "delivering"), recovered.rows(from).stream().map(row -> ReturnStore.text(row, "state")).collect(java.util.stream.Collectors.toSet()));
        assertEquals("depositing", ReturnStore.text(recovered.rows(to).getFirst(), "state"));
        try (var disk = mockStatic(ReturnStore.class, CALLS_REAL_METHODS)) {
            for (int i = 0; i < 20; i++) assertEquals(Set.of(from.account(), to.account()), Set.copyOf(recovered.pendingPlayerIds()));
            disk.verify(() -> ReturnStore.files(any(Path.class)), never());
            disk.verify(() -> ReturnStore.read(any(Path.class)), never());
        }
        for (var row : recovered.rows(from)) recovered.update(row, copy -> copy.addProperty("state", "consumed"));
        var last = recovered.rows(to).getFirst();
        Files.delete(recovered.file(to, ReturnStore.id(last)));
        recovered.recover();
        assertTrue(recovered.pendingPlayerIds().isEmpty());
    }

    @Test void migrationAndEstateRemovalUpdateOwnersWithoutRediscoveringLegacyOrTerminalRecords() throws Exception {
        Path legacy = root.resolve("pending_returns/" + from.account() + ".json");
        Files.createDirectories(legacy.getParent()); Files.writeString(legacy, "{\"type\":\"DIAMOND\"}");
        var identity = mock(QueueIdentity.class);
        when(identity.heirsReady()).thenReturn(true);
        when(identity.heir(from.account())).thenReturn(Optional.of(from));
        var store = new ReturnStore(root);
        store.migrate(identity);
        assertEquals(List.of(from.account()), store.pendingPlayerIds());
        var row = store.rows(from).getFirst();
        UUID death = UUID.randomUUID();
        var decision = new EstateDecision(death, new CharacterRef(from.account(), from.character()), Optional.empty(), Instant.EPOCH,
                Optional.empty(), Optional.empty(), Optional.empty(), Recipient.FROZEN,
                Recipient.character(new CharacterRef(to.account(), to.character())), Recipient.FROZEN, Recipient.SENDER, 1);
        store.transfer(death, decision.toString(), from, to, () -> true);
        assertEquals(List.of(to.account()), store.pendingPlayerIds());
        assertFalse(Files.exists(store.file(from, ReturnStore.id(row))));
        assertTrue(Files.exists(legacy));
        store.update(store.rows(to).getFirst(), copy -> copy.addProperty("state", "delivered"));
        store.migrate(identity);
        assertTrue(store.pendingPlayerIds().isEmpty());
    }

    @Test void failedWritesDoNotPublishOrTerminalizeAnIndexedOwner() throws Exception {
        var store = new ReturnStore(root);
        try (var disk = mockStatic(QueueFiles.class, CALLS_REAL_METHODS)) {
            disk.when(() -> QueueFiles.write(any(), any(), anyBoolean())).thenThrow(new IOException("full disk"));
            assertThrows(IOException.class, () -> create(store, from, "pending"));
            assertTrue(store.pendingPlayerIds().isEmpty());
        }
        var row = create(store, from, "pending");
        try (var disk = mockStatic(QueueFiles.class, CALLS_REAL_METHODS)) {
            disk.when(() -> QueueFiles.write(any(), any(), eq(true))).thenThrow(new IOException("full disk"));
            assertThrows(IOException.class, () -> store.update(row, copy -> copy.addProperty("state", "consumed")));
            assertEquals(List.of(from.account()), store.pendingPlayerIds());
        }
        assertEquals(row, store.rows(from).getFirst());
        store.update(row, copy -> copy.addProperty("state", "consumed"));
        assertTrue(store.pendingPlayerIds().isEmpty());
    }

    @Test void transientReadFailuresRetainPendingOwnersUntilLaterValidationAndRecovery() throws Exception {
        var store = new ReturnStore(root);
        List<List<UUID>> notifications = new ArrayList<>();
        store.onPendingOwnersChanged(notifications::add);
        var row = create(store, from, "pending");
        Path file = store.file(from, ReturnStore.id(row));
        byte[] before = Files.readAllBytes(file);
        try (var disk = mockStatic(ReturnStore.class, CALLS_REAL_METHODS)) {
            disk.when(() -> ReturnStore.read(file)).thenThrow(new IOException("temporarily unavailable"));
            assertThrows(IOException.class, () -> store.rows(from));
            assertEquals(List.of(from.account()), store.pendingPlayerIds());
            assertThrows(IOException.class, () -> store.update(row, copy -> copy.addProperty("state", "delivered")));
            assertEquals(List.of(from.account()), store.pendingPlayerIds());
            assertEquals(List.of(List.of(), List.of(from.account())), notifications);
        }
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(List.of(row), store.rows(from));
        assertEquals(List.of(List.of(), List.of(from.account())), notifications);
        store.recover();
        assertEquals(List.of(from.account()), store.pendingPlayerIds());
        store.update(row, copy -> copy.addProperty("state", "delivered"));
        assertTrue(store.pendingPlayerIds().isEmpty());
    }

    @Test void malformedInvalidAndDeletedRowsRemoveTheirIndexedOwner() throws Exception {
        for (String defect : List.of("malformed", "invalid", "deleted")) {
            var store = new ReturnStore(root.resolve(defect));
            var row = create(store, from, "pending");
            Path file = store.file(from, ReturnStore.id(row));
            if (defect.equals("malformed")) Files.writeString(file, "broken");
            else if (defect.equals("invalid")) {
                var invalid = row.deepCopy(); invalid.addProperty("state", "unknown");
                Files.write(file, QueueJson.bytes(invalid));
            } else Files.delete(file);
            assertEquals(List.of(from.account()), store.pendingPlayerIds());
            var failure = assertThrows(IOException.class, () -> store.update(row, copy -> fail("Unreadable rows must not be changed")));
            if (defect.equals("deleted")) assertInstanceOf(NoSuchFileException.class, failure);
            else assertInstanceOf(ReturnStore.Conflict.class, failure);
            assertTrue(store.pendingPlayerIds().isEmpty());
            store.recover();
            assertTrue(store.pendingPlayerIds().isEmpty());
        }
    }
}
