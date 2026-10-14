package net.tfminecraft.recycler.ownership;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;
import org.bukkit.plugin.ServicePriority;

/** Transfers queued property only; inventory/world effects are never estate replay actions. */
public final class CharacterQueueEstate implements EstateParticipant {
    private final Recycler plugin;
    private final Map<UUID, Map.Entry<Long, Outcome>> reported = new java.util.HashMap<>();
    public CharacterQueueEstate(Recycler plugin) { this.plugin = plugin; }
    public static void start(Recycler plugin) {
        CharacterQueueEstate participant = new CharacterQueueEstate(plugin);
        plugin.getServer().getServicesManager().register(EstateParticipant.class, participant, plugin, ServicePriority.Normal);
        plugin.getServer().getScheduler().runTaskTimer(plugin, participant::reconcile, 1L, 72_000L);
    }
    public String id() { return "recycler"; }
    public Outcome settle(EstateDecision decision) {
        if (!Estates.guardHolds(decision)) return Outcome.retry("Current Recycler estate decision does not hold");
        QueueIdentity.Owner from = owner(decision.testator());
        if (!plugin.getRecyclerManager().quiescent(from)) return Outcome.retry("Original recycler session is still active");
        ReturnStore store = plugin.getEscrowManager().store();
        try {
            store.requireMigrated(from);
            for (var row : store.rows(from)) if (ReturnStore.ambiguous(row))
                return Outcome.blocked("Inventory intent needs staff recovery: " + ReturnStore.id(row));
            java.util.List<String> moved;
            if (decision.property().character().isEmpty()) moved = store.retained(decision.deathId(), decision.toString(), from);
            else {
                QueueIdentity.Owner to = owner(decision.property().character().orElseThrow());
                if (!plugin.getRecyclerManager().quiescent(to)) return Outcome.retry("Recipient recycler session is still active");
                moved = store.transfer(decision.deathId(), decision.toString(), from, to, () -> Estates.guardHolds(decision));
            }
            return Outcome.done(Map.of("returns", moved.size()), moved);
        } catch (ReturnStore.Conflict ex) { return Outcome.blocked(ex.getMessage()); }
        catch (IOException ex) { return Outcome.retry("Could not persist Recycler estate: " + ex.getMessage()); }
    }
    public Holdings describe(UUID character) {
        try {
            var ref = Characters.info(character).filter(info -> info.ref().characterId().equals(character)).orElseThrow();
            var rows = plugin.getEscrowManager().store().rows(owner(ref.ref())).stream().filter(row -> !ReturnStore.terminal(row)).toList();
            return rows.isEmpty() ? Holdings.NONE : new Holdings(Map.of("returns", rows.size()), rows.stream().map(row -> ReturnStore.id(row).toString()).toList());
        } catch (IOException | RuntimeException ex) { return new Holdings(Map.of("heldQueues", 1), java.util.List.of("Recycler queue needs review")); }
    }
    void reconcile() {
        for (EstateDecision decision : Estates.decided()) {
            try {
                if (!Estates.guardHolds(decision)) continue;
                Outcome outcome = settle(decision); var result = Map.entry(decision.revision(), outcome);
                if (!result.equals(reported.get(decision.deathId())) && Estates.report(decision.deathId(), id(), decision.revision(), outcome)) reported.put(decision.deathId(), result);
            } catch (RuntimeException ex) { plugin.getLogger().warning("Recycler estate held: " + decision.deathId() + " " + ex); }
        }
    }
    private static QueueIdentity.Owner owner(CharacterRef ref) { return new QueueIdentity.Owner(ref.ownerId(), ref.characterId()); }
}
