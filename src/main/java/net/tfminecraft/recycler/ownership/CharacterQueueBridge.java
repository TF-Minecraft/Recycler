package net.tfminecraft.recycler.ownership;

import java.util.Optional;
import java.util.UUID;
import net.tfminecraft.recycler.Recycler;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;
import net.tfminecraft.tlibs.character.Characters;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Loaded only after probing the current optional API. */
public final class CharacterQueueBridge implements QueueIdentity {
    public CharacterQueueBridge() {}
    static Token token(CharacterSession session) {
        return new Token(new Owner(session.ref().ownerId(), session.ref().characterId()), session.sessionId());
    }
    public Optional<Token> current(Player player) {
        if (player == null) return Optional.empty();
        return Characters.currentSession(player)
                .filter(session -> session.ref().ownerId().equals(player.getUniqueId()))
                .filter(Characters::isCurrent)
                .filter(session -> Characters.info(session.ref().characterId())
                        .filter(info -> info.ref().equals(session.ref()) && info.alive()).isPresent())
                .map(CharacterQueueBridge::token);
    }
    public boolean current(Token token, Player player) {
        return token != null && current(player).filter(token::equals).isPresent();
    }
    public boolean heirsReady() { return Characters.legacyHeirsReady(); }
    public Optional<Owner> heir(UUID account) {
        if (account == null) return Optional.empty();
        return Characters.legacyHeir(account).filter(ref -> account.equals(ref.ownerId()))
                .map(ref -> new Owner(ref.ownerId(), ref.characterId()));
    }
    public static void start(Recycler plugin) {
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void end(CharacterSessionEndEvent event) {
                plugin.getRecyclerManager().end(event.getPlayer(), token(event.getSession()));
            }
            @EventHandler
            public void start(CharacterSessionStartEvent event) {
                QueueIdentity identity = plugin.getEscrowManager().identity();
                if (identity.current(token(event.getSession()), event.getPlayer()))
                    plugin.getEscrowManager().deliverPending(event.getPlayer());
            }
        }, plugin);
    }
}
