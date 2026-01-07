package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.data.RomByteReader;

public class TouchResponseTable {
    private final int[] widths;
    private final int[] heights;

    public TouchResponseTable(RomByteReader rom, int touchSizesAddr, int entryCount) {
        int count = Math.max(1, entryCount);
        this.widths = new int[count];
        this.heights = new int[count];
        for (int i = 0; i < count; i++) {
            int addr = touchSizesAddr + i * 2;
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
