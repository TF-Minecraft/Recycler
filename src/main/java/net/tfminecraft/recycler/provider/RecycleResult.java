package net.tfminecraft.recycler.provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.recycler.model.RecycleOutput;

/**
 * Final scaled outputs ready for preview or spawn.
 */
public final class RecycleResult {

    private final boolean handled;
    private final String providerId;
    private final List<RecycleOutput> outputs;

    private RecycleResult(boolean handled, String providerId, List<RecycleOutput> outputs) {
        this.handled = handled;
        this.providerId = providerId;
        this.outputs = outputs;
    }

    public static RecycleResult notHandled() {
        return new RecycleResult(false, "", List.of());
    }

    public static RecycleResult of(String providerId, List<RecycleOutput> baseOutputs, RecycleContext ctx) {
        return of(providerId, baseOutputs, ctx, false, null);
    }

    public static RecycleResult of(String providerId, List<RecycleOutput> baseOutputs, RecycleContext ctx,
            boolean roll, java.util.function.DoubleSupplier random) {
        Map<String, Integer> merged = new HashMap<>();
        List<RecycleOutput> finalChanceOutputs = new ArrayList<>();
        double scale = ctx.combinedScale() * ctx.stackAmount();
        for (RecycleOutput line : baseOutputs) {
            if (line.returnChance() >= 0) {
                int amount = line.baseAmount() * ctx.stackAmount();
                double chance = line.returnChance();
                if (chance == 0) continue;
                int returned = 0;
                if (roll) {
                    for (int unit = 0; unit < amount; unit++) {
                        if (random.getAsDouble() < chance) returned++;
                    }
                } else {
                    returned = amount;
                }
                if (returned > 0) finalChanceOutputs.add(new RecycleOutput(line.itemPath(), returned,
                        roll ? -1 : chance));
                continue;
            }
            int scaled = (int) Math.floor(line.baseAmount() * scale);
            if (scaled <= 0) {
                continue;
            }
            merged.merge(line.itemPath(), scaled, Integer::sum);
        }
        List<RecycleOutput> finalOutputs = new ArrayList<>(finalChanceOutputs);
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            finalOutputs.add(new RecycleOutput(entry.getKey(), entry.getValue()));
        }
        return new RecycleResult(true, providerId, finalOutputs);
    }

    public boolean isHandled() {
        return handled;
    }

    /** Roll only after confirmation; a failed roll still consumes the scrap. */
    public RecycleResult roll() {
        return of(providerId, outputs, new RecycleContext(1, 1, 1), true,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    public String getProviderId() {
        return providerId;
    }

    public List<RecycleOutput> getOutputs() {
        return outputs;
    }

    public boolean hasYield() {
        return !outputs.isEmpty();
    }

    public List<ItemStack> buildItemStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        for (RecycleOutput output : outputs) {
            ItemStack stack = TLibs.getItemAPI().getCreator().getItemFromPath(output.itemPath());
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            stack.setAmount(Math.min(64, output.baseAmount()));
            stacks.add(stack);
            int remaining = output.baseAmount() - stack.getAmount();
            while (remaining > 0) {
                ItemStack extra = stack.clone();
                extra.setAmount(Math.min(64, remaining));
                stacks.add(extra);
                remaining -= extra.getAmount();
            }
        }
        return stacks;
    }
}
