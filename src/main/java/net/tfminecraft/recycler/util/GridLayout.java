package net.tfminecraft.recycler.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 27-slot (9x3) recycling station GUI.
 *
 * <pre>
 * Slot 0: confirm
 * Slots 3, 12, 21: arrow decorators (icon_right_gray)
 * Slot 10: input display
 * Cols 0-2 except slot 10: filler
 * Cols 4-8: preview outputs
 * </pre>
 */
public final class GridLayout {

    public static final int COLUMNS = 9;
    public static final int ROWS = 3;
    public static final int SIZE = COLUMNS * ROWS;

    public static final int SLOT_CONFIRM = 0;
    public static final int SLOT_INPUT = 10;
    public static final int PREVIEW_COL_START = 4;
    public static final int PREVIEW_COL_END = 8;

    public static final List<Integer> ARROW_SLOTS = Collections.unmodifiableList(Arrays.asList(3, 12, 21));

    private static final Set<Integer> PREVIEW_SLOTS = new HashSet<>();
    private static final Set<Integer> RESERVED_SLOTS = new HashSet<>();

    private GridLayout() {}

    static {
        RESERVED_SLOTS.add(SLOT_CONFIRM);
        RESERVED_SLOTS.add(SLOT_INPUT);
        RESERVED_SLOTS.addAll(ARROW_SLOTS);
        for (int slot = 0; slot < SIZE; slot++) {
            int col = slot % COLUMNS;
            if (col >= PREVIEW_COL_START && col <= PREVIEW_COL_END) {
                PREVIEW_SLOTS.add(slot);
            }
        }
    }

    public static int slot(int row, int col) {
        return row * COLUMNS + col;
    }

    public static boolean isPreviewSlot(int slot) {
        return PREVIEW_SLOTS.contains(slot);
    }

    public static boolean isReservedSlot(int slot) {
        return RESERVED_SLOTS.contains(slot);
    }

    public static boolean isFillerSlot(int slot) {
        if (isReservedSlot(slot) || isPreviewSlot(slot)) {
            return false;
        }
        int col = slot % COLUMNS;
        return col <= 2;
    }

    public static List<Integer> previewSlots() {
        return List.copyOf(PREVIEW_SLOTS);
    }
}
