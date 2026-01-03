package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.data.RomByteReader;

public class TouchResponseTable {
    public static final int TOUCH_SIZES_ADDR = 0x3F600;
    public static final int ENTRY_COUNT = 0x33;

    private final int[] widths = new int[ENTRY_COUNT];
    private final int[] heights = new int[ENTRY_COUNT];

    public TouchResponseTable(RomByteReader rom) {
        for (int i = 0; i < ENTRY_COUNT; i++) {
            int addr = TOUCH_SIZES_ADDR + i * 2;
            widths[i] = rom.readU8(addr);
            heights[i] = rom.readU8(addr + 1);
        }
    }

    public int getWidthRadius(int index) {
        if (index < 0 || index >= widths.length) {
            return widths[0];
        }
        return widths[index];
    }

    public int getHeightRadius(int index) {
        if (index < 0 || index >= heights.length) {
            return heights[0];
        }
        return heights[index];
    }
}
