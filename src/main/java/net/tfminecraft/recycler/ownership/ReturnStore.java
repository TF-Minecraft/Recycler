package net.tfminecraft.recycler.ownership;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Raw queues and immutable ownership intents. Inventory effects are deliberately separate. */
public final class ReturnStore {
    public static final class Conflict extends IOException {
        public Conflict(String message) { super(message); }
    }
    private static final List<String> STATES = List.of("depositing", "escrow", "pending", "delivering", "held", "delivered", "consumed", "cancelled");
    private final Path root;
    private final Map<Path, UUID> pendingOwners = new java.util.LinkedHashMap<>();
    private Consumer<List<UUID>> pendingOwnersChanged = accounts -> {};
    private final java.util.Map<Path, java.util.Map.Entry<String, java.util.Map<UUID, List<QueueIdentity.Owner>>>> validated = new java.util.HashMap<>();
    public ReturnStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path root() { return root; }
    public Path file(QueueIdentity.Owner owner, UUID id) { return root.resolve("character-returns").resolve(owner.character().toString()).resolve(id + ".json"); }
    public static boolean terminal(JsonObject row) { return List.of("delivered", "consumed", "cancelled").contains(text(row, "state")); }
    public static boolean ambiguous(JsonObject row) { return List.of("depositing", "delivering").contains(text(row, "state")); }
    public static UUID id(JsonObject row) { return UUID.fromString(text(row, "id")); }
    public static QueueIdentity.Owner owner(JsonObject row) {
        return new QueueIdentity.Owner(UUID.fromString(text(row, "owner-account")), UUID.fromString(text(row, "owner-character")));
    }
    public static String text(JsonObject row, String key) { return row.get(key).getAsString(); }

    /** Recovery seeds this index; successful queue publications keep it current without discovery. */
    public List<UUID> pendingPlayerIds() { return pendingOwners.values().stream().distinct().toList(); }
    public void onPendingOwnersChanged(Consumer<List<UUID>> listener) {
        pendingOwnersChanged = listener;
        listener.accept(pendingPlayerIds());
    }
    private void index(Path path, JsonObject row) {
        if (terminal(row)) forget(path);
        else {
            UUID account = owner(row).account();
            if (!account.equals(pendingOwners.put(path, account))) pendingOwnersChanged.accept(pendingPlayerIds());
        }
    }
    private void forget(Path path) {
        if (pendingOwners.remove(path) != null) pendingOwnersChanged.accept(pendingPlayerIds());
    }
    private JsonObject readRow(Path path) throws IOException {
        try {
            JsonObject row = read(path); validate(path, row); index(path, row); return row;
        } catch (IOException ex) { forget(path); throw ex; }
    }

    public List<JsonObject> rows(QueueIdentity.Owner owner) throws IOException {
        requireDirectory(root.resolve("character-returns"));
        Path folder = root.resolve("character-returns").resolve(owner.character().toString());
        requireDirectory(folder);
        List<JsonObject> rows = new ArrayList<>();
        for (Path path : files(folder)) {
            JsonObject row = readRow(path);
            if (!owner.equals(owner(row))) throw new Conflict("Return owner differs from its character folder: " + path);
            rows.add(row);
        }
        return rows;
    }
    public List<Path> allFiles() throws IOException {
        requireDirectory(root.resolve("character-returns"));
        List<Path> result = new ArrayList<>();
        for (Path folder : files(root.resolve("character-returns"))) {
            if (Files.isRegularFile(folder)) result.add(folder);
            else result.addAll(files(folder));
        }
        return result;
    }
    public JsonObject create(QueueIdentity.Owner owner, UUID id, JsonObject payload) throws IOException {
        requireWritable(owner);
        JsonObject row = payload.deepCopy();
        row.addProperty("format", 1); row.addProperty("id", id.toString()); setOwner(row, owner);
        validate(file(owner, id), row);
        QueueFiles.write(file(owner, id), QueueJson.bytes(row), false);
        index(file(owner, id), row);
        return row;
    }
    public JsonObject update(JsonObject expected, Consumer<JsonObject> change) throws IOException {
        QueueIdentity.Owner owner = owner(expected); requireWritable(owner);
        Path file = file(owner, id(expected));
        JsonObject current = readRow(file);
        if (!current.equals(expected)) throw new Conflict("Return changed before its effect: " + id(expected));
        change.accept(current); validate(file, current);
        write(file, current); index(file, current); return current;
    }
    public void recover() throws IOException {
        pendingOwners.clear();
        pendingOwnersChanged.accept(pendingPlayerIds());
        for (Path path : allFiles()) {
            try {
                JsonObject row = readRow(path);
                // No in-memory GUI survived a restart. Ambiguous inventory intents stay untouched.
                if ("escrow".equals(text(row, "state"))) update(row, copy -> copy.addProperty("state", "pending"));
            } catch (Conflict ex) { /* Retain unloadable documents for staff; other queues remain readable. */ }
        }
    }

    public void migrate(QueueIdentity identity) throws IOException {
        if (!identity.heirsReady()) return;
        requireJournalDirectories();
        for (String kind : List.of("escrow", "pending_returns")) requireDirectory(root.resolve(kind));
        Conflict failures = null;
        for (String kind : List.of("escrow", "pending_returns")) for (Path source : files(root.resolve(kind))) {
            if (!Files.isRegularFile(source)) continue;
            String relative = root.relativize(source).toString();
            UUID operation = UUID.nameUUIDFromBytes(relative.getBytes(StandardCharsets.UTF_8));
            try {
                Path journal = root.resolve("ownership/migration").resolve(operation + ".json");
                byte[] before = Files.readAllBytes(source);
                UUID account = legacyAccount(source);
                if (account == null) {
                    JsonObject held = new JsonObject(); held.addProperty("source", relative);
                    held.addProperty("original", Base64.getEncoder().encodeToString(before)); held.addProperty("reason", "Unrecognized legacy account path");
                    Path evidence = root.resolve("ownership/unassigned").resolve(operation + ".json");
                    if (!Files.exists(evidence)) QueueFiles.write(evidence, QueueJson.bytes(held), false);
                    continue;
                }
                JsonObject intent;
                if (Files.exists(journal)) {
                    intent = read(journal);
                    validateIntent(intent, "migration");
                    if (!relative.equals(text(intent, "source")) || !hash(before).equals(text(intent, "before-hash")))
                        throw new Conflict("Legacy return changed after its fixed plan: " + source);
                } else {
                    QueueIdentity.Owner to = identity.heir(account).filter(owner -> owner.account().equals(account)).orElse(null);
                    if (to == null) continue;
                    JsonObject after = new JsonObject();
                    after.addProperty("format", 1); after.addProperty("id", operation.toString()); setOwner(after, to);
                    after.addProperty("legacy-source", relative);
                    after.addProperty("state", "pending");
                    try { after.add("legacy-item", QueueJson.parse(new String(before, StandardCharsets.UTF_8))); }
                    catch (IOException ex) {
                        after.addProperty("state", "held"); after.addProperty("reason", ex.getMessage());
                        after.addProperty("legacy-bytes", Base64.getEncoder().encodeToString(before));
                    }
                    // Legacy escrow has no durable evidence proving its inventory removal completed.
                    if ("escrow".equals(kind)) { after.addProperty("state", "held"); after.addProperty("reason", "Legacy deposit outcome needs staff review"); }
                    intent = intent("migration", source, file(to, operation), before, QueueJson.bytes(after));
                    intent.addProperty("from-account", account.toString()); setRef(intent, "to", to);
                    backup(before); write(journal, intent);
                }
                validateMigration(intent, operation, source);
                if (!"moved".equals(text(intent, "phase"))) apply(journal, intent, () -> true, false);
            } catch (Conflict ex) {
                Conflict held = new Conflict("Legacy return migration " + operation + " needs review: " + ex.getMessage());
                held.initCause(ex);
                if (failures == null) failures = held;
                else failures.addSuppressed(held);
            }
        }
        if (failures != null) throw failures;
    }

    /** Matches the old pending delivery names, including escrow collision suffixes. */
    private static UUID legacyAccount(Path source) {
        String name = source.getFileName().toString();
        try {
            UUID account = UUID.fromString(name.substring(0, 36));
            if (!name.substring(0, 36).equals(account.toString())) return null;
            return name.equals(account + ".json") || name.startsWith(account + "-") && name.endsWith(".json") ? account : null;
        } catch (IllegalArgumentException | IndexOutOfBoundsException ex) { return null; }
    }

    public List<String> transfer(UUID death, String decision, QueueIdentity.Owner from, QueueIdentity.Owner to,
            BooleanSupplier guard) throws IOException {
        if (from.equals(to)) throw new Conflict("Recipient is the original owner");
        long revision = validateDecision(decision, death, from, to);
        Path file = root.resolve("ownership/estates").resolve(death + ".json");
        JsonObject journal = estate(file, death, from);
        List<JsonObject> rows = rows(from);
        requireUnreserved(file, journal, rows);
        reconcilePlans(journal, decision);
        for (JsonObject row : rows) {
            if (journal.has(id(row).toString()) && "moved".equals(text(journal.getAsJsonObject(id(row).toString()), "phase")))
                throw new Conflict("Transferred return reappeared: " + id(row));
            if (terminal(row)) continue;
            if (ambiguous(row)) throw new Conflict("Return has an ambiguous inventory effect: " + id(row));
            String key = id(row).toString();
            if (journal.has(key)) {
                continue;
            }
            JsonObject after = row.deepCopy(); setOwner(after, to);
            if ("escrow".equals(text(after, "state"))) after.addProperty("state", "pending");
            byte[] before = Files.readAllBytes(file(from, id(row)));
            JsonObject plan = intent("estate", file(from, id(row)), file(to, id(row)), before, QueueJson.bytes(after));
            plan.addProperty("decision", decision); plan.addProperty("revision", revision); setRef(plan, "from", from); setRef(plan, "to", to);
            backup(before); journal.add(key, plan);
        }
        if (!guard.getAsBoolean()) throw new IOException("Estate decision no longer holds");
        write(file, journal);
        for (String id : entries(journal)) {
            JsonObject plan = journal.getAsJsonObject(id);
            if (!"moved".equals(text(plan, "phase"))) apply(file, journal, plan, guard, true);
        }
        return receipts(journal);
    }
    public List<String> retained(UUID death, String decision, QueueIdentity.Owner from) throws IOException {
        Path file = root.resolve("ownership/estates").resolve(death + ".json");
        JsonObject journal = estate(file, death, from);
        List<JsonObject> rows = rows(from);
        requireUnreserved(file, journal, rows);
        for (JsonObject row : rows) if (journal.has(id(row).toString()) && "moved".equals(text(journal.getAsJsonObject(id(row).toString()), "phase")))
            throw new Conflict("Transferred return reappeared: " + id(row));
        reconcilePlans(journal, decision);
        if (Files.exists(file)) write(file, journal);
        return receipts(journal);
    }
    /** Another death must not move a row still needed by an interrupted publication. */
    private void requireUnreserved(Path current, JsonObject journal, List<JsonObject> rows) throws IOException {
        var operations = new HashSet<UUID>();
        for (JsonObject row : rows) operations.add(id(row));
        for (String id : entries(journal)) {
            JsonObject plan = journal.getAsJsonObject(id);
            if (!"moved".equals(text(plan, "phase"))) {
                requirePublishedTarget(plan);
                operations.add(UUID.fromString(id));
            }
        }
        Path folder = root.resolve("ownership/estates");
        requireDirectory(root.resolve("ownership"));
        requireDirectory(folder);
        try {
            for (Path file : files(folder)) {
                var plans = incomplete(file);
                if (file.equals(current)) continue;
                for (UUID id : plans.keySet())
                    if (operations.contains(id)) throw new Conflict("Another death still reserves return publication: " + id);
            }
        } catch (RuntimeException ex) { throw new Conflict("Malformed estate reservation needs review"); }
    }
    private JsonObject estate(Path file, UUID death, QueueIdentity.Owner from) throws IOException {
        if (!Files.exists(file)) {
            JsonObject journal = new JsonObject(); journal.addProperty("format", 1); journal.addProperty("death", death.toString()); setRef(journal, "from", from); return journal;
        }
        return estate(file, read(file), death, from);
    }
    private JsonObject estate(Path file, JsonObject journal, UUID death, QueueIdentity.Owner from) throws IOException {
        try {
            if (!file.getFileName().toString().equals(death + ".json") || !journal.get("format").toString().equals("1")
                    || !death.toString().equals(text(journal, "death")) || !from.equals(ref(journal, "from")))
                throw new Conflict("Estate journal differs from the current death/owner");
            for (String id : entries(journal)) {
                UUID.fromString(id); JsonObject intent = journal.getAsJsonObject(id); validateIntent(intent, "estate");
                JsonObject before = readPayload(intent, "before"), after = readPayload(intent, "after");
                if (!from.equals(ref(intent, "from")) || !from.equals(owner(before))
                        || !ref(intent, "to").equals(owner(after))) throw new Conflict("Intent full references differ from payloads");
                validatePaths(intent, from, ref(intent, "to"), UUID.fromString(id));
                validate(file(from, UUID.fromString(id)), before); validate(file(ref(intent, "to"), UUID.fromString(id)), after);
                JsonObject expected = before.deepCopy(); setOwner(expected, ref(intent, "to"));
                if ("escrow".equals(text(expected, "state"))) expected.addProperty("state", "pending");
                if (!expected.equals(after)) throw new Conflict("Estate plan changes queued payload or unrelated metadata");
                if (!intent.get("revision").toString().equals(Long.toString(validateDecision(text(intent, "decision"), death, from, ref(intent, "to")))))
                    throw new Conflict("Estate plan revision differs from its captured decision");
            }
        } catch (RuntimeException ex) { throw new Conflict("Malformed estate journal"); }
        return journal;
    }
    /** Incomplete plans of a fully validated journal. Validation depends only on its path and exact bytes. */
    private java.util.Map<UUID, List<QueueIdentity.Owner>> incomplete(Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String digest = hash(source.getBytes(StandardCharsets.UTF_8));
        var known = validated.get(file);
        if (known != null && known.getKey().equals(digest)) return known.getValue();
        JsonObject raw = parse(file, source);
        JsonObject journal = estate(file, raw, UUID.fromString(text(raw, "death")), ref(raw, "from"));
        var plans = new java.util.LinkedHashMap<UUID, List<QueueIdentity.Owner>>();
        for (String id : entries(journal)) {
            JsonObject plan = journal.getAsJsonObject(id);
            if (!"moved".equals(text(plan, "phase"))) plans.put(UUID.fromString(id), List.of(ref(plan, "from"), ref(plan, "to")));
        }
        var result = java.util.Collections.unmodifiableMap(plans);
        validated.put(file, java.util.Map.entry(digest, result));
        return result;
    }
    private static long validateDecision(String decision, UUID death, QueueIdentity.Owner from, QueueIdentity.Owner to) throws IOException {
        var suffix = java.util.regex.Pattern.compile(", revision=(0|[1-9][0-9]*)]$").matcher(decision);
        if (!decision.startsWith("EstateDecision[deathId=" + death + ", testator=" + reference(from) + ", diedAt=")
                || !decision.contains(", property=Character[ref=" + reference(to) + "], guildLocks=") || !suffix.find())
            throw new Conflict("Estate plan differs from its captured full decision");
        try { return Long.parseLong(suffix.group(1)); }
        catch (NumberFormatException ex) { throw new Conflict("Unsupported estate revision"); }
    }
    private static String reference(QueueIdentity.Owner owner) {
        return "CharacterRef[ownerId=" + owner.account() + ", characterId=" + owner.character() + "]";
    }
    private void reconcilePlans(JsonObject journal, String decision) throws IOException {
        for (String id : entries(journal)) {
            JsonObject plan = journal.getAsJsonObject(id);
            if ("moved".equals(text(plan, "phase"))) continue;
            if (!decision.equals(text(plan, "decision"))) {
                if (!"planned".equals(text(plan, "phase")) || Files.exists(path(text(plan, "target"))))
                    throw new Conflict("Stale partially published return needs staff recovery: " + id);
                journal.remove(id);
            }
        }
    }
    public void requireWritable(QueueIdentity.Owner owner) throws IOException {
        requireJournalDirectories();
        try {
        for (Path file : files(root.resolve("ownership/migration"))) {
            JsonObject plan = read(file); validateIntent(plan, "migration");
            validateMigration(plan, UUID.fromString(file.getFileName().toString().replace(".json", "")), path(text(plan, "source")));
            if (!"moved".equals(text(plan, "phase")) && owner.equals(owner(readPayload(plan, "after"))))
                throw new Conflict("Legacy return publication needs recovery");
        }
        for (Path file : files(root.resolve("ownership/estates")))
            for (var plan : incomplete(file).entrySet())
                if (plan.getValue().contains(owner)) throw new Conflict("Return ownership publication needs recovery: " + plan.getKey());
        } catch (RuntimeException ex) { throw new Conflict("Malformed ownership publication needs review"); }
    }
    public void requireMigrated(QueueIdentity.Owner owner) throws IOException {
        for (String kind : List.of("escrow", "pending_returns")) requireDirectory(root.resolve(kind));
        for (String kind : List.of("escrow", "pending_returns")) for (Path source : files(root.resolve(kind))) {
            if (owner.account().equals(legacyAccount(source))) {
                UUID op = UUID.nameUUIDFromBytes(root.relativize(source).toString().getBytes(StandardCharsets.UTF_8));
                Path file = root.resolve("ownership/migration").resolve(op + ".json");
                if (!Files.exists(file)) throw new IOException("Reviewed legacy queue migration is not complete");
                JsonObject plan = read(file); validateIntent(plan, "migration"); validateMigration(plan, op, source);
                if (!"moved".equals(text(plan, "phase"))) throw new IOException("Reviewed legacy queue migration is not complete");
            }
        }
    }
    private void validateMigration(JsonObject plan, UUID operation, Path source) throws IOException {
        try {
            if ((!source.getParent().equals(root.resolve("escrow")) && !source.getParent().equals(root.resolve("pending_returns")))
                    || !operation.equals(UUID.nameUUIDFromBytes(root.relativize(source).toString().getBytes(StandardCharsets.UTF_8))))
                throw new Conflict("Legacy source namespace/operation differs");
            JsonObject planned = readPayload(plan, "after");
            validate(file(owner(planned), operation), planned);
            if (!ref(plan, "to").equals(owner(planned))) throw new Conflict("Legacy fixed recipient differs from payload");
            UUID originalAccount = legacyAccount(source);
            if (originalAccount == null) throw new Conflict("Legacy plan filename was not an old deliverable queue");
            // Older JARs delete delivered legacy files, so absence never proves the character copy is the only one.
            if (!Files.isRegularFile(source)) throw new Conflict("Retained legacy source is missing for migration " + operation + ": " + root.relativize(source));
            if (!originalAccount.toString().equals(text(plan, "from-account"))
                    || !owner(planned).account().toString().equals(text(plan, "from-account"))
                    || !text(planned, "legacy-source").equals(root.relativize(source).toString())
                    || !path(text(plan, "source")).equals(source)
                    || !path(text(plan, "target")).equals(file(owner(planned), operation))
                    || !hash(Files.readAllBytes(source)).equals(text(plan, "before-hash")))
                throw new Conflict("Legacy plan differs from its source/account/operation");
            if (source.getParent().getFileName().toString().equals("escrow") && !text(planned, "state").equals("held"))
                throw new Conflict("Legacy deposit ambiguity cannot become an automatic return");
            backup(Base64.getDecoder().decode(text(plan, "before")));
            JsonObject original = null;
            try { original = QueueJson.parse(Files.readString(source, StandardCharsets.UTF_8)); }
            catch (IOException ex) { /* An opaque source must retain its exact bytes instead. */ }
            if (original != null) {
                if (!original.equals(planned.get("legacy-item"))) throw new Conflict("Legacy plan changes item payload");
            } else if (!text(plan, "before").equals(text(planned, "legacy-bytes"))) throw new Conflict("Legacy plan changes opaque payload");
        } catch (RuntimeException ex) { throw new Conflict("Malformed legacy ownership plan"); }
    }
    private JsonObject intent(String kind, Path source, Path target, byte[] before, byte[] after) {
        JsonObject intent = new JsonObject(); intent.addProperty("format", 1); intent.addProperty("kind", kind); intent.addProperty("phase", "planned");
        intent.addProperty("source", root.relativize(source).toString()); intent.addProperty("target", root.relativize(target).toString());
        for (var entry : java.util.Map.of("before", before, "after", after).entrySet()) {
            intent.addProperty(entry.getKey(), Base64.getEncoder().encodeToString(entry.getValue())); intent.addProperty(entry.getKey() + "-hash", hash(entry.getValue()));
        }
        return intent;
    }
    private void apply(Path file, JsonObject plan, BooleanSupplier guard, boolean remove) throws IOException { apply(file, plan, plan, guard, remove); }
    private void requirePublishedTarget(JsonObject plan) throws IOException {
        if ("published".equals(text(plan, "phase")) && !Files.exists(path(text(plan, "target"))))
            throw new Conflict("Published return target disappeared; recovery needs review");
    }
    private void apply(Path file, JsonObject journal, JsonObject plan, BooleanSupplier guard, boolean remove) throws IOException {
        Path source = path(text(plan, "source")), target = path(text(plan, "target"));
        requirePublishedTarget(plan);
        backup(Base64.getDecoder().decode(text(plan, "before")));
        if (source.equals(target)) throw new Conflict("Return source and target are the same");
        if (Files.exists(source) && !hash(Files.readAllBytes(source)).equals(text(plan, "before-hash"))) throw new Conflict("Planned return source changed");
        if (!Files.exists(source) && "planned".equals(text(plan, "phase"))) throw new Conflict("Planned return source disappeared");
        if (Files.exists(target)) {
            if (!hash(Files.readAllBytes(target)).equals(text(plan, "after-hash"))) throw new Conflict("Recipient return conflicts");
        } else {
            if (!guard.getAsBoolean()) throw new IOException("Estate changed before return publication");
            QueueFiles.write(target, Base64.getDecoder().decode(text(plan, "after")), false);
        }
        index(target, readPayload(plan, "after"));
        plan.addProperty("phase", "published"); write(file, journal);
        if (remove) {
            if (!guard.getAsBoolean()) throw new IOException("Estate changed before source removal");
            QueueFiles.delete(source);
            forget(source);
        }
        plan.addProperty("phase", "moved"); write(file, journal);
    }
    private void validatePaths(JsonObject plan, QueueIdentity.Owner from, QueueIdentity.Owner to, UUID id) throws IOException {
        if (!path(text(plan, "source")).equals(file(from, id)) || !path(text(plan, "target")).equals(file(to, id))) throw new Conflict("Return plan paths differ from full refs");
    }
    private static void validateIntent(JsonObject plan, String kind) throws IOException {
        try {
            if (!plan.get("format").toString().equals("1") || !kind.equals(text(plan, "kind"))
                    || !List.of("planned", "published", "moved").contains(text(plan, "phase"))) throw new Conflict("Invalid return publication schema");
            for (String part : List.of("before", "after")) if (!hash(Base64.getDecoder().decode(text(plan, part))).equals(text(plan, part + "-hash"))) throw new Conflict("Return intent payload hash differs");
        } catch (RuntimeException ex) { throw new Conflict("Malformed return publication"); }
    }
    private static void validate(Path file, JsonObject row) throws IOException {
        try {
            if (!row.get("format").toString().equals("1") || !file.getFileName().toString().equals(id(row) + ".json")
                    || !file.getParent().getFileName().toString().equals(owner(row).character().toString()) || !STATES.contains(text(row, "state")))
                throw new Conflict("Unresolved return identity/schema: " + file);
        } catch (RuntimeException ex) { throw new Conflict("Malformed return envelope: " + file); }
    }
    private Path path(String relative) throws IOException {
        Path path = root.resolve(relative).normalize(); if (!path.startsWith(root)) throw new Conflict("Return path escapes storage"); return path;
    }
    private void backup(byte[] bytes) throws IOException {
        Path file = root.resolve("ownership/backups").resolve(hash(bytes) + ".bin");
        if (!Files.exists(file)) QueueFiles.write(file, bytes, false);
        else if (!hash(Files.readAllBytes(file)).equals(hash(bytes))) throw new Conflict("Immutable return backup changed");
    }
    public static JsonObject read(Path path) throws IOException { return parse(path, Files.readString(path, StandardCharsets.UTF_8)); }
    private static JsonObject parse(Path path, String source) throws Conflict {
        try { return QueueJson.parse(source); }
        catch (IOException ex) { throw new Conflict("Return document needs recovery: " + path + " (" + ex.getMessage() + ")"); }
    }
    static JsonObject readPayload(JsonObject plan, String part) throws IOException { return QueueJson.parse(new String(Base64.getDecoder().decode(text(plan, part)), StandardCharsets.UTF_8)); }
    static void write(Path path, JsonObject raw) throws IOException { QueueFiles.write(path, QueueJson.bytes(raw), true); }
    static List<Path> files(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (var paths = Files.list(folder)) { return paths.filter(path -> !staging(path)).sorted().toList(); }
    }
    private void requireJournalDirectories() throws Conflict {
        requireDirectory(root.resolve("ownership"));
        requireDirectory(root.resolve("ownership/migration"));
        requireDirectory(root.resolve("ownership/estates"));
    }
    private static void requireDirectory(Path folder) throws Conflict {
        if (Files.exists(folder, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(folder))
            throw new Conflict("Return storage directory needs review: " + folder);
    }
    /** Interrupted publication buffers are retained evidence, never authoritative queue or journal rows. */
    private static boolean staging(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("recycler-") && name.endsWith(".tmp")
                && Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }
    static String hash(byte[] bytes) { return java.util.HexFormat.of().formatHex(digest(bytes)); }
    private static byte[] digest(byte[] bytes) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }
    static void setOwner(JsonObject row, QueueIdentity.Owner owner) { row.addProperty("owner-account", owner.account().toString()); row.addProperty("owner-character", owner.character().toString()); }
    static void setRef(JsonObject row, String prefix, QueueIdentity.Owner owner) { row.addProperty(prefix + "-account", owner.account().toString()); row.addProperty(prefix + "-character", owner.character().toString()); }
    static QueueIdentity.Owner ref(JsonObject row, String prefix) { return new QueueIdentity.Owner(UUID.fromString(text(row, prefix + "-account")), UUID.fromString(text(row, prefix + "-character"))); }
    static List<String> entries(JsonObject journal) { return journal.keySet().stream().filter(key -> !List.of("format", "death", "from-account", "from-character").contains(key)).sorted().toList(); }
    private static List<String> receipts(JsonObject journal) { return entries(journal).stream().filter(id -> "moved".equals(text(journal.getAsJsonObject(id), "phase"))).toList(); }
}
