package net.tfminecraft.recycler.manager;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.tfminecraft.recycler.Messages;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.recycler.ownership.QueueIdentity;
import net.tfminecraft.recycler.ownership.QueueJson;
import net.tfminecraft.recycler.ownership.ReturnStore;
import net.tfminecraft.recycler.ownership.InventoryCheckpoint;

/** Character-addressed item property. Inventory intents are held across interruption. */
public final class EscrowManager {
    private final QueueIdentity identity;
    private ReturnStore store;
    public EscrowManager() { this.identity = QueueIdentity.load(); }
    public EscrowManager(Path data, QueueIdentity identity) { this.identity = identity; store = new ReturnStore(data); }
    public QueueIdentity identity() { return identity; }
    public ReturnStore store() {
        if (store == null) store = new ReturnStore(Recycler.plugin.getDataFolder().toPath().resolve("data"));
        return store;
    }
    public void loadPending() { run(() -> store().migrate(identity)); run(() -> store().recover()); }
    public void savePending() { run(() -> {
        for (Path path : store().allFiles()) {
            try { JsonObject row = ReturnStore.read(path); if ("escrow".equals(ReturnStore.text(row, "state"))) pending(ReturnStore.owner(row)); }
            catch (ReturnStore.Conflict | RuntimeException ex) { warning("Held return: " + path + " " + ex.getMessage()); }
        }
    }); }
    public QueueIdentity.Token token(Player player) {
        return identity.current(player).orElseThrow(() -> new IllegalArgumentException("Choose a living character before using the recycler"));
    }
    public boolean current(QueueIdentity.Token token, Player player) {
        return token != null && player != null && token.owner().account().equals(player.getUniqueId()) && identity.current(token, player);
    }
    private List<JsonObject> rows(QueueIdentity.Owner owner) throws IOException { return store().rows(owner); }
    private JsonObject escrow(QueueIdentity.Owner owner) throws IOException {
        return rows(owner).stream().filter(row -> "escrow".equals(ReturnStore.text(row, "state"))).findFirst().orElse(null);
    }
    public ItemStack getEscrow(QueueIdentity.Owner owner) throws IOException {
        JsonObject row = escrow(owner);
        if (row == null) return null;
        try { return item(row); }
        catch (RuntimeException ex) { throw new ReturnStore.Conflict("Unreadable escrow item retained: " + ReturnStore.id(row)); }
    }
    public boolean hasEscrow(UUID account) {
        Player player = Bukkit.getPlayer(account);
        return identity.current(player).map(token -> {
            try { return escrow(token.owner()) != null; } catch (IOException ex) { warning(ex.getMessage()); return false; }
        }).orElse(false);
    }
    public ItemStack getEscrow(UUID account) {
        return identity.current(Bukkit.getPlayer(account)).map(token -> {
            try { return getEscrow(token.owner()); } catch (IOException ex) { warning(ex.getMessage()); return null; }
        }).orElse(null);
    }
    /** Compatibility for a caller supplying already-deposited new property; never maps legacy files. */
    public void putEscrow(UUID account, ItemStack item) {
        if (account == null || item == null || item.getType().isAir()) return;
        Player player = Bukkit.getPlayer(account);
        QueueIdentity.Token token = token(player);
        run(() -> { pending(token.owner()); JsonObject row = payload(item); row.addProperty("state", "escrow"); store().create(token.owner(), UUID.randomUUID(), row); });
    }
    public void clearEscrow(UUID account) {
        identity.current(Bukkit.getPlayer(account)).ifPresent(token -> run(() -> consume(token.owner(), List.of())));
    }
    private static JsonObject payload(ItemStack item) {
        JsonObject row = new JsonObject(); row.addProperty("item-bytes", encode(item)); row.addProperty("remaining", item.getAmount()); return row;
    }
    private static String encode(ItemStack item) { return Base64.getEncoder().encodeToString(item.serializeAsBytes()); }
    private static ItemStack item(JsonObject row) {
        ItemStack item;
        if (row.has("item-bytes")) item = ItemStack.deserializeBytes(Base64.getDecoder().decode(ReturnStore.text(row, "item-bytes")));
        else {
            Map<String,Object> map = QueueJson.GSON.fromJson(row.get("legacy-item"), new TypeToken<Map<String,Object>>() {}.getType());
            item = ItemStack.deserialize(map);
        }
        if (row.has("remaining")) item.setAmount(row.get("remaining").getAsInt());
        return item;
    }
    public void deposit(Player player, QueueIdentity.Token token, ItemStack input, int slot) throws IOException {
        deposit(player, token, input, slot, UUID.randomUUID());
    }
    public void deposit(Player player, QueueIdentity.Token token, ItemStack input, int slot, UUID operation) throws IOException {
        if (!current(token, player)) throw new ReturnStore.Conflict("Recycler session changed before deposit");
        pending(token.owner());
        JsonObject row = payload(input); row.addProperty("state", "depositing");
        JsonObject evidence = new JsonObject(); evidence.addProperty("slot", slot); evidence.addProperty("session", token.session());
        evidence.addProperty("before", encode(input)); evidence.add("after", JsonNull.INSTANCE); row.add("deposit", evidence);
        row = store().create(token.owner(), operation, row);
        if (!current(token, player) || !input.equals(player.getInventory().getItem(slot))) {
            store().update(row, copy -> { copy.addProperty("state", "cancelled"); copy.addProperty("reason", "Deposit cancelled before its inventory effect"); });
            throw new ReturnStore.Conflict("Inventory/session changed before removal; deposit cancelled");
        }
        player.getInventory().setItem(slot, null);
        InventoryCheckpoint.save(player);
        store().update(row, copy -> copy.addProperty("state", "escrow"));
        deliverPending(player); // Previous escrow was queued before touching this slot.
    }
    public void pending(QueueIdentity.Owner owner) throws IOException {
        for (JsonObject row : rows(owner)) if ("escrow".equals(ReturnStore.text(row, "state")))
            store().update(row, copy -> copy.addProperty("state", "pending"));
    }
    public void consume(QueueIdentity.Owner owner, List<ItemStack> outputs) throws IOException {
        JsonObject row = escrow(owner);
        if (row == null) throw new ReturnStore.Conflict("No current escrow to recycle");
        store().update(row, copy -> {
            copy.addProperty("state", "consumed");
            copy.add("planned-outputs", QueueJson.GSON.toJsonTree(outputs.stream().map(EscrowManager::encode).toList()));
            copy.addProperty("output-recovery", "World output is not replayed automatically after interruption");
        });
    }
    public void returnEscrow(Player player) { giveBackEscrow(player, true); }
    public void giveBackEscrow(Player player, boolean notify) {
        if (player == null) return;
        identity.current(player).ifPresent(token -> run(() -> { pending(token.owner()); deliver(player, token, notify); }));
    }
    public void flushAllOnline() { for (Player player : Bukkit.getOnlinePlayers()) giveBackEscrow(player, true); }
    public void adminReturn(Player player) { giveBackEscrow(player, true); }
    public void deliverPending(UUID account) { deliverPending(Bukkit.getPlayer(account)); }
    public void deliverPending(Player player) {
        if (player == null) return;
        identity.current(player).ifPresent(token -> run(() -> deliver(player, token, true)));
    }
    private void deliver(Player player, QueueIdentity.Token token, boolean notify) throws IOException {
        store().requireWritable(token.owner());
        for (JsonObject row : rows(token.owner())) {
            if (!"pending".equals(ReturnStore.text(row, "state"))) continue;
            ItemStack original;
            try { original = item(row); }
            catch (RuntimeException ex) { warning("Unreadable item retained: " + ReturnStore.id(row) + " " + ex.getMessage()); continue; }
            if (original.getType().isAir() || original.getAmount() < 1) continue;
            int remaining = original.getAmount();
            while (remaining > 0 && current(token, player)) {
                ItemStack[] slots = player.getInventory().getStorageContents();
                int free = -1;
                for (int slot = 0; slot < slots.length; slot++) if (slots[slot] == null || slots[slot].getType().isAir()) { free = slot; break; }
                if (free < 0) return; // Remainder stays queued; never drop a refund at an incoming character's feet.
                ItemStack batch = original.clone(); batch.setAmount(Math.min(remaining, batch.getMaxStackSize()));
                remaining -= batch.getAmount();
                JsonObject delivery = new JsonObject(); delivery.addProperty("slot", free); delivery.addProperty("session", token.session());
                delivery.add("before", JsonNull.INSTANCE); delivery.addProperty("batch", encode(batch)); delivery.addProperty("remaining-after", remaining);
                row = store().update(row, copy -> { copy.addProperty("state", "delivering"); copy.add("delivery", delivery); });
                if (!current(token, player)) return; // The prepared effect stays held; it never targets a replacement session.
                player.getInventory().setItem(free, batch);
                InventoryCheckpoint.save(player);
                int rest = remaining;
                row = store().update(row, copy -> { copy.addProperty("remaining", rest); copy.addProperty("state", rest == 0 ? "delivered" : "pending"); });
                if (notify) player.sendMessage(Messages.get("escrow.returned"));
            }
        }
    }
    public List<String> describeQueues() {
        List<String> descriptions = new ArrayList<>();
        run(() -> {
            for (Path path : store().allFiles()) {
                try {
                    JsonObject row = ReturnStore.read(path);
                    if (!ReturnStore.terminal(row)) descriptions.add(ReturnStore.owner(row) + " / " + ReturnStore.id(row) + " (" + ReturnStore.text(row, "state") + ")");
                } catch (IOException | RuntimeException ex) { descriptions.add(path + " (unreadable, held)"); }
            }
            for (String kind : List.of("escrow", "pending_returns")) {
                Path folder = store().root().resolve(kind);
                if (java.nio.file.Files.isDirectory(folder)) try (var paths = java.nio.file.Files.list(folder)) {
                    paths.forEach(path -> descriptions.add(path + " (retained legacy source)"));
                }
            }
        });
        return descriptions;
    }
    public boolean hasPending(UUID account) {
        return identity.current(Bukkit.getPlayer(account)).map(token -> {
            try { return rows(token.owner()).stream().anyMatch(row -> "pending".equals(ReturnStore.text(row, "state"))); }
            catch (IOException ex) { warning(ex.getMessage()); return false; }
        }).orElse(false);
    }
    public List<UUID> listMemoryEscrowPlayerIds() { return List.of(); }
    public int countMemoryEscrow() { return 0; }
    public List<UUID> listPendingPlayerIds() {
        return store().pendingPlayerIds();
    }
    public int countPendingFiles() { return describeQueues().size(); }
    private interface Work { void run() throws IOException; }
    private void run(Work work) {
        try { work.run(); }
        catch (IOException | RuntimeException ex) {
            warning(ex.getMessage());
            for (Throwable held : ex.getSuppressed()) warning(held.getMessage());
        }
    }
    private void warning(String message) { Recycler.plugin.getLogger().warning("[Recycler] " + message); }
}
