package net.tfminecraft.recycler.ownership;

import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Optional character APIs stay outside queue files and deferred tokens. */
public interface QueueIdentity {
    record Owner(UUID account, UUID character) {}
    record Token(Owner owner, long session) {}
    Optional<Token> current(Player player);
    boolean current(Token token, Player player);
    boolean heirsReady();
    Optional<Owner> heir(UUID account);

    static QueueIdentity load() {
        try {
            Class<?> api = Class.forName("net.tfminecraft.tlibs.character.Characters");
            api.getMethod("legacyHeir", UUID.class);
            api.getMethod("legacyHeirsReady");
            return (QueueIdentity) Class.forName("net.tfminecraft.recycler.ownership.CharacterQueueBridge")
                    .getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return new QueueIdentity() {
                public Optional<Token> current(Player player) { return Optional.empty(); }
                public boolean current(Token token, Player player) { return false; }
                public boolean heirsReady() { return false; }
                public Optional<Owner> heir(UUID account) { return Optional.empty(); }
            };
        }
    }
}
