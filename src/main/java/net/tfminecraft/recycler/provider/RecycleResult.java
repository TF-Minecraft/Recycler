package net.tfminecraft.recycler.provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.inventory.ItemStack;

import me.Plugins.TLibs.TLibs;
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
        Map<String, Integer> merged = new HashMap<>();
        double scale = ctx.combinedScale() * ctx.stackAmount();
        for (RecycleOutput line : baseOutputs) {
            int scaled = (int) Math.floor(line.baseAmount() * scale);
            if (scaled <= 0) {
                continue;
            }
            merged.merge(line.itemPath(), scaled, Integer::sum);
        }
        List<RecycleOutput> finalOutputs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            finalOutputs.add(new RecycleOutput(entry.getKey(), entry.getValue()));
        }
        return new RecycleResult(true, providerId, finalOutputs);
    }

    public boolean isHandled() {
        return handled;
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
