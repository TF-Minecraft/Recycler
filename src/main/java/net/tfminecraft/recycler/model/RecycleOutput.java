package net.tfminecraft.recycler.model;

/**
 * One output line before return-rate and durability scaling.
 */
public record RecycleOutput(String itemPath, int baseAmount) {

    public RecycleOutput {
        if (itemPath == null) {
            itemPath = "";
        }
    }
}
