package net.tfminecraft.recycler.ownership;

import net.tfminecraft.recycler.Recycler;

/** Keeps optional estate types out of the plugin's baseline linkage. */
public final class QueueEstate {
    private QueueEstate() {}
    public static void start(Recycler plugin) {
        try {
            Class.forName("net.tfminecraft.tlibs.character.estate.EstateParticipant");
            Class.forName("net.tfminecraft.recycler.ownership.CharacterQueueEstate").getMethod("start", Recycler.class).invoke(null, plugin);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            plugin.getLogger().warning("Recycler estate API unavailable; character returns remain retained");
        }
    }
}
