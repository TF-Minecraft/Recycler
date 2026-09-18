package net.tfminecraft.recycler.util;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import net.tfminecraft.recycler.Cache;
import net.tfminecraft.recycler.Recycler;

public final class ResultSpawnEffects {

    private ResultSpawnEffects() {}

    /**
     * Spawns the result item above the station block with upward velocity and a crit trail.
     */
    public static boolean spawnAtStation(Location blockLoc, ItemStack item) {
        if (blockLoc == null || blockLoc.getWorld() == null || item == null || item.getType().isAir()) {
            return false;
        }

        World world = blockLoc.getWorld();
        Location base = blockLoc.clone().add(0.5, 0.8, 0.5);
        Location pop = base.clone().add(0, 0.15, 0);

        if (Cache.resultSpawnBurstParticles) {
            world.spawnParticle(Particle.END_ROD, base, 6, 0.2, 0.15, 0.2, 0.02);
        }

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        Item ent = world.dropItem(pop, item.clone());
        ent.setPickupDelay(0);
        ent.setCustomName(entityNameFor(item));
        ent.setCustomNameVisible(true);
        kickUp(ent, rng);
        startCritTrail(ent, Cache.resultSpawnTrailTicks, Cache.resultSpawnTrailIntervalTicks);
        return true;
    }

    private static void kickUp(Item ent, ThreadLocalRandom rng) {
        double vx = randomSigned(rng, Cache.resultSpawnKickHorizontalMin, Cache.resultSpawnKickHorizontalMax);
        double vz = randomSigned(rng, Cache.resultSpawnKickHorizontalMin, Cache.resultSpawnKickHorizontalMax);
        double vy = rng.nextDouble(Cache.resultSpawnKickVelocityMin, Cache.resultSpawnKickVelocityMax);
        ent.setVelocity(new Vector(vx, vy, vz));
    }

    private static double randomSigned(ThreadLocalRandom rng, double min, double max) {
        double v = rng.nextDouble(min, max);
        return rng.nextBoolean() ? v : -v;
    }

    private static void startCritTrail(Entity entity, int maxTicks, int intervalTicks) {
        int interval = Math.max(1, intervalTicks);
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (entity == null || !entity.isValid() || entity.isDead() || t++ >= maxTicks) {
                    cancel();
                    return;
                }
                Location p = entity.getLocation().add(0, 0.1, 0);
                p.getWorld().spawnParticle(Particle.END_ROD, p, 2, 0.04, 0.04, 0.04, 0.01);
            }
        }.runTaskTimer(Recycler.plugin, 0L, interval);
    }

    private static String entityNameFor(ItemStack item) {
        return "§f" + item.getAmount() + "x " + displayNameOf(item);
    }

    private static String displayNameOf(ItemStack item) {
        if (item == null) {
            return "Item";
        }
        var meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }
        String raw = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
}
