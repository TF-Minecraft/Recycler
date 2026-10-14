package net.tfminecraft.recycler.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReturnStoreLegacyBoundaryTest {
    @TempDir Path root;
    final UUID account = UUID.fromString("abcdef01-abcd-abcd-abcd-abcdef012345");
    final QueueIdentity.Owner heir = new QueueIdentity.Owner(account, UUID.randomUUID());

    QueueIdentity identity() {
        QueueIdentity identity = mock(QueueIdentity.class);
        when(identity.heirsReady()).thenReturn(true);
        when(identity.heir(account)).thenReturn(Optional.of(heir));
        return identity;
    }
    Path legacy(Path data, String kind, String name, String bytes) throws IOException {
        Path source = data.resolve(kind).resolve(name);
        Files.createDirectories(source.getParent());
        Files.writeString(source, bytes);
        return source;
    }
    UUID operation(String kind, String name) {
        return UUID.nameUUIDFromBytes((kind + "/" + name).getBytes(StandardCharsets.UTF_8));
    }
    Path journal(UUID id) { return root.resolve("ownership/migration/" + id + ".json"); }
    static Map<String, String> snapshot(Path data) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (var paths = Files.walk(data)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) snapshot.put(data.relativize(path).toString(), "link:" + Files.readSymbolicLink(path));
                else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) snapshot.put(data.relativize(path).toString(), ReturnStore.hash(Files.readAllBytes(path)));
            }
        }
        return snapshot;
    }

    @Test void backupAndNoncanonicalNamesRemainUnassignedAndDoNotBlockAnOwnersReadiness() throws Exception {
        QueueIdentity identity = identity();
        List<String> names = List.of(account + ".json.bak", account + "_copy.json", account.toString().toUpperCase() + ".json",
                account + ".txt", account + "-copy.txt", account + ".json.extra.json", "short.json", "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.json");
        String payload = "{\"type\":\"DIAMOND\",\"future\":{\"kept\":null}}";
        for (String kind : List.of("escrow", "pending_returns"))
            for (String name : names) legacy(root, kind, name, payload);
        ReturnStore store = new ReturnStore(root);
        store.migrate(identity);
        assertTrue(store.rows(heir).isEmpty());
        verify(identity, never()).heir(any());
        store.requireMigrated(heir);
        store.requireWritable(heir);
        for (String kind : List.of("escrow", "pending_returns")) for (String name : names) {
            assertEquals(payload, Files.readString(root.resolve(kind).resolve(name)));
            JsonObject held = ReturnStore.read(root.resolve("ownership/unassigned/" + operation(kind, name) + ".json"));
            assertEquals(payload, new String(Base64.getDecoder().decode(ReturnStore.text(held, "original")), StandardCharsets.UTF_8));
            assertEquals(kind + "/" + name, ReturnStore.text(held, "source"));
        }
        Map<String, String> before = snapshot(root);
        new ReturnStore(root).migrate(identity);
        assertEquals(before, snapshot(root));
    }

    @Test void canonicalAndOldCollisionSuffixesStillMigrateOpaqueAndUnknownPayloadsExactly() throws Exception {
        List<String> names = List.of(account + ".json", account + "-collision.json", account + "-.json", account + "-collision.json.json");
        for (String kind : List.of("escrow", "pending_returns")) for (String name : names)
            legacy(root, kind, name, name.endsWith("-collision.json") ? "unreadable item\n" : "{\"type\":\"DIAMOND\",\"future\":null}");
        ReturnStore store = new ReturnStore(root);
        store.migrate(identity());
        assertEquals(8, store.rows(heir).size());
        for (JsonObject row : store.rows(heir)) {
            String source = ReturnStore.text(row, "legacy-source");
            String original = Files.readString(root.resolve(source));
            if (row.has("legacy-bytes")) assertEquals(original, new String(Base64.getDecoder().decode(ReturnStore.text(row, "legacy-bytes")), StandardCharsets.UTF_8));
            else assertTrue(row.getAsJsonObject("legacy-item").get("future").isJsonNull());
            assertEquals(source.startsWith("escrow/") || row.has("legacy-bytes") ? "held" : "pending", ReturnStore.text(row, "state"));
        }
        store.requireMigrated(heir);
        Map<String, String> before = snapshot(root);
        new ReturnStore(root).migrate(identity());
        assertEquals(before, snapshot(root));
    }

    @Test void anExistingBackupNameJournalCannotAuthorizeItsPreviouslyCreatedCopy() throws Exception {
        String name = account + ".json", invalid = name + ".bak";
        Path source = legacy(root, "pending_returns", name, "{\"type\":\"DIAMOND\",\"future\":null}");
        ReturnStore store = new ReturnStore(root);
        store.migrate(identity());
        UUID originalOp = operation("pending_returns", name), invalidOp = operation("pending_returns", invalid);
        JsonObject plan = ReturnStore.read(journal(originalOp));
        JsonObject after = ReturnStore.readPayload(plan, "after");
        after.addProperty("id", invalidOp.toString());
        after.addProperty("legacy-source", "pending_returns/" + invalid);
        byte[] changed = QueueJson.bytes(after);
        plan.addProperty("source", "pending_returns/" + invalid);
        plan.addProperty("target", "character-returns/" + heir.character() + "/" + invalidOp + ".json");
        plan.addProperty("after", Base64.getEncoder().encodeToString(changed));
        plan.addProperty("after-hash", ReturnStore.hash(changed));
        Files.move(source, source.resolveSibling(invalid));
        Files.delete(store.file(heir, originalOp));
        Files.delete(journal(originalOp));
        ReturnStore.write(journal(invalidOp), plan);
        Files.write(store.file(heir, invalidOp), changed);
        Map<String, String> before = snapshot(root);
        assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(heir));
        store.requireMigrated(heir); // An ignored backup name is not an unresolved deliverable source.
        assertEquals(before, snapshot(root));
        store.migrate(identity());
        assertArrayEquals(changed, Files.readAllBytes(store.file(heir, invalidOp)));
        assertEquals(1, store.rows(heir).size());
        assertThrows(ReturnStore.Conflict.class, () -> new ReturnStore(root).requireWritable(heir));
        assertEquals("pending_returns/" + invalid, ReturnStore.text(ReturnStore.read(root.resolve("ownership/unassigned/" + invalidOp + ".json")), "source"));
    }

    @Test void multipleKnownPublicationConflictsRemainHeldWhileLaterAccountsMigrateAfterRestart() throws Exception {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID later = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
        QueueIdentity identity = mock(QueueIdentity.class);
        when(identity.heirsReady()).thenReturn(true);
        Map<UUID, QueueIdentity.Owner> owners = Map.of(first, new QueueIdentity.Owner(first, UUID.randomUUID()),
                second, new QueueIdentity.Owner(second, UUID.randomUUID()), later, new QueueIdentity.Owner(later, UUID.randomUUID()));
        when(identity.heir(any())).thenAnswer(call -> Optional.ofNullable(owners.get(call.getArgument(0))));
        ReturnStore store = new ReturnStore(root);
        for (UUID id : List.of(first, second)) legacy(root, "pending_returns", id + ".json", "{\"type\":\"DIAMOND\"}");
        store.migrate(identity);
        Map<Path, byte[]> evidence = new java.util.HashMap<>();
        for (UUID id : List.of(first, second)) {
            UUID op = operation("pending_returns", id + ".json");
            JsonObject plan = ReturnStore.read(journal(op));
            plan.addProperty("phase", "published");
            ReturnStore.write(journal(op), plan);
            Files.delete(store.file(owners.get(id), op));
            evidence.put(journal(op), Files.readAllBytes(journal(op)));
            evidence.put(root.resolve("pending_returns/" + id + ".json"), Files.readAllBytes(root.resolve("pending_returns/" + id + ".json")));
        }
        legacy(root, "pending_returns", later + ".json", "{\"type\":\"EMERALD\",\"future\":null}");
        ReturnStore.Conflict failure = assertThrows(ReturnStore.Conflict.class, () -> new ReturnStore(root).migrate(identity));
        assertTrue(failure.getMessage().contains(operation("pending_returns", first + ".json").toString()));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains(operation("pending_returns", second + ".json").toString()));
        assertEquals(1, store.rows(owners.get(later)).size());
        store.requireWritable(owners.get(later));
        for (UUID id : List.of(first, second)) {
            assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(owners.get(id)));
            assertTrue(store.rows(owners.get(id)).isEmpty());
        }
        for (var entry : evidence.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
        Map<String, String> before = snapshot(root);
        assertThrows(ReturnStore.Conflict.class, () -> new ReturnStore(root).migrate(identity));
        assertEquals(before, snapshot(root));
    }

    @Test void wrongShapedJournalContainersBlockWritersAndMigrationBeforeAnyPublication() throws Exception {
        for (String name : List.of("ownership", "ownership/migration", "ownership/estates")) for (boolean dangling : List.of(false, true)) {
            Path data = root.resolve(name.replace('/', '-') + "-" + dangling);
            ReturnStore store = new ReturnStore(data);
            JsonObject row = store.create(heir, UUID.randomUUID(), QueueJson.parse("{\"state\":\"pending\",\"future\":null}"));
            Path malformed = data.resolve(name);
            Files.createDirectories(malformed.getParent());
            if (dangling) Files.createSymbolicLink(malformed, data.resolve("missing"));
            else Files.writeString(malformed, "retained invalid container");
            legacy(data, "pending_returns", account + ".json", "{\"type\":\"DIAMOND\"}");
            Map<String, String> before = snapshot(data);
            assertThrows(ReturnStore.Conflict.class, () -> store.requireWritable(heir), name);
            assertThrows(ReturnStore.Conflict.class, () -> store.update(row, copy -> fail("Must not run an inventory effect")), name);
            assertThrows(ReturnStore.Conflict.class, () -> store.create(heir, UUID.randomUUID(), row), name);
            assertThrows(ReturnStore.Conflict.class, () -> store.migrate(identity()), name);
            assertEquals(before, snapshot(data));
        }
    }

    @Test void wrongShapedLegacyContainersDoNotLetOtherSourcesBeReportedAsSuccessfullyMigrated() throws Exception {
        for (String kind : List.of("escrow", "pending_returns")) for (boolean dangling : List.of(false, true)) {
            Path data = root.resolve(kind + "-" + dangling);
            legacy(data, kind.equals("escrow") ? "pending_returns" : "escrow", account + ".json", "{\"type\":\"DIAMOND\"}");
            Path invalid = data.resolve(kind);
            if (dangling) Files.createSymbolicLink(invalid, data.resolve("missing"));
            else Files.writeString(invalid, "raw invalid legacy folder");
            Map<String, String> before = snapshot(data);
            ReturnStore store = new ReturnStore(data);
            assertThrows(ReturnStore.Conflict.class, () -> store.migrate(identity()));
            assertThrows(ReturnStore.Conflict.class, () -> store.requireMigrated(heir));
            assertEquals(before, snapshot(data));
        }
    }

    @Test void wrongShapedCharacterContainersCannotMasqueradeAsAnEmptyOwnersQueue() throws Exception {
        for (boolean parent : List.of(false, true)) for (boolean dangling : List.of(false, true)) {
            Path data = root.resolve(parent + "-" + dangling);
            Path invalid = parent ? data.resolve("character-returns") : data.resolve("character-returns/" + heir.character());
            Files.createDirectories(invalid.getParent());
            if (dangling) Files.createSymbolicLink(invalid, data.resolve("missing"));
            else Files.writeString(invalid, "raw invalid character container");
            ReturnStore store = new ReturnStore(data);
            Map<String, String> before = snapshot(data);
            assertThrows(ReturnStore.Conflict.class, () -> store.rows(heir));
            if (parent) assertThrows(ReturnStore.Conflict.class, store::allFiles);
            assertEquals(before, snapshot(data));
        }
        ReturnStore empty = new ReturnStore(root.resolve("missing-namespaces"));
        assertTrue(empty.rows(heir).isEmpty());
        assertTrue(empty.allFiles().isEmpty());
        empty.requireWritable(heir);
        empty.requireMigrated(heir);
    }
}
