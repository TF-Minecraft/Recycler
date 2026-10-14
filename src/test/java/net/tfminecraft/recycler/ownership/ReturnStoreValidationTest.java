package net.tfminecraft.recycler.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReturnStoreValidationTest {
    @TempDir Path root;
    final QueueIdentity.Owner a = owner(), b = owner(), c = owner();

    static QueueIdentity.Owner owner() { return new QueueIdentity.Owner(UUID.randomUUID(), UUID.randomUUID()); }
    static String reference(QueueIdentity.Owner owner) { return "CharacterRef[ownerId=" + owner.account() + ", characterId=" + owner.character() + "]"; }
    static String decision(UUID death, QueueIdentity.Owner from, QueueIdentity.Owner to) {
        return "EstateDecision[deathId=" + death + ", testator=" + reference(from) + ", diedAt=Optional.empty, property=Character[ref=" + reference(to) + "], guildLocks=FROZEN, revision=1]";
    }
    JsonObject create(ReturnStore store, QueueIdentity.Owner owner) throws IOException {
        return store.create(owner, UUID.randomUUID(), QueueJson.parse("{\"state\":\"pending\",\"item-bytes\":\"opaque\",\"future\":{\"kept\":null},\"remaining\":3}"));
    }
    Map<Path, String> files() throws IOException {
        Map<Path, String> files = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList())
                files.put(root.relativize(file), ReturnStore.hash(Files.readAllBytes(file)));
        }
        return files;
    }

    @Test void missingRetainedLegacySourceHoldsEveryWriterUntilItsExactBytesReturn() throws Exception {
        ReturnStore store = new ReturnStore(root);
        QueueIdentity.Owner heir = new QueueIdentity.Owner(a.account(), UUID.randomUUID());
        QueueIdentity identity = mock(QueueIdentity.class);
        when(identity.heirsReady()).thenReturn(true);
        when(identity.heir(a.account())).thenReturn(Optional.of(heir));
        Path source = root.resolve("pending_returns/" + a.account() + ".json");
        Files.createDirectories(source.getParent());
        byte[] original = "{\"type\":\"DIAMOND\",\"future\":null}".getBytes();
        Files.write(source, original);
        store.migrate(identity);
        UUID operation = UUID.nameUUIDFromBytes(("pending_returns/" + a.account() + ".json").getBytes());
        JsonObject row = store.rows(heir).getFirst();
        store.requireWritable(c);

        // An older JAR deletes delivered legacy files; the remaining character copy must not become a second delivery.
        Files.delete(source);
        Map<Path, String> before = files();
        for (QueueIdentity.Owner owner : new QueueIdentity.Owner[] {c, heir}) {
            ReturnStore.Conflict held = assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(owner));
            assertTrue(held.getMessage().contains("Retained legacy source is missing for migration " + operation), held.getMessage());
        }
        assertThrows(ReturnStore.Conflict.class, () -> create(store, c));
        assertThrows(ReturnStore.Conflict.class, () -> store.update(row, copy -> copy.addProperty("state", "delivered")));
        store.migrate(identity);
        assertEquals(before, files());

        // Staff restore the immutable original after review; writers resume with the same fixed plan.
        Files.write(source, Files.readAllBytes(root.resolve("ownership/backups/" + ReturnStore.hash(original) + ".bin")));
        store.requireWritable(c);
        assertEquals("delivered", ReturnStore.text(store.update(row, copy -> copy.addProperty("state", "delivered")), "state"));
    }

    @Test void estateValidationIsReusedOnlyForIdenticalJournalBytes() throws Exception {
        ReturnStore store = new ReturnStore(root);
        JsonObject moved = create(store, a);
        UUID death = UUID.randomUUID();
        assertEquals(java.util.List.of(ReturnStore.id(moved).toString()), store.transfer(death, decision(death, a, b), a, b, () -> true));
        Path journal = root.resolve("ownership/estates/" + death + ".json");
        byte[] completed = Files.readAllBytes(journal);
        store.requireWritable(b);
        store.requireWritable(c);

        // An external writer reopens the completed plan: the writer hold and the reservation return at once.
        JsonObject reopened = ReturnStore.read(journal);
        reopened.getAsJsonObject(ReturnStore.id(moved).toString()).addProperty("phase", "published");
        Files.write(journal, QueueJson.bytes(reopened));
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(a));
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(b));
        store.requireWritable(c);
        UUID later = UUID.randomUUID();
        Map<Path, String> before = files();
        ReturnStore.Conflict reserved = assertThrows(ReturnStore.Conflict.class, () -> store.transfer(later, decision(later, b, c), b, c, () -> true));
        assertTrue(reserved.getMessage().contains("Another death still reserves"), reserved.getMessage());
        assertEquals(before, files());

        // An invalid header blocks everyone; restoring the exact completed bytes releases every owner again.
        Files.writeString(journal, new String(completed).replace("\"format\":1", "\"format\":2"));
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(c));
        assertThrows(ReturnStore.Conflict.class, () -> store.transfer(later, decision(later, b, c), b, c, () -> true));
        Files.write(journal, completed);
        store.requireWritable(a);
        store.requireWritable(b);
        assertEquals(java.util.List.of(ReturnStore.id(moved).toString()), store.transfer(later, decision(later, b, c), b, c, () -> true));

        // Reintroducing identical invalid bytes must not reuse a failed validation from before the repair.
        byte[] invalid = new String(completed).replace("\"format\":1", "\"format\":2").getBytes();
        Files.write(journal, invalid);
        before = files();
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(c));
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(c));
        assertEquals(before, files());
        Files.write(journal, completed);
        store.requireWritable(c);
    }
}
