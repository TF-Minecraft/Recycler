package net.tfminecraft.recycler.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One output line. A nonnegative returnChance rolls per unit; -1 uses deterministic scaling.
 */
public record RecycleOutput(String itemPath, int baseAmount, double returnChance) {

    public RecycleOutput(String itemPath, int baseAmount) {
        this(itemPath, baseAmount, -1);
    }

    public RecycleOutput {
        if (itemPath == null) {
            itemPath = "";
        }
    }

    /**
     * Output lines for a recorded path-to-amount map, skipping blank paths and non-positive amounts.
     */
    public static List<RecycleOutput> fromAmounts(Map<String, Integer> amounts) {
        List<RecycleOutput> outputs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : amounts.entrySet()) {
            String path = entry.getKey();
            Integer amount = entry.getValue();
            if (path != null && !path.isBlank() && amount != null && amount > 0) {
                outputs.add(new RecycleOutput(path, amount));
            }
        }
        return outputs;
    }
}
