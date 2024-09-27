package uk.co.jamesj999.sonic.level;
/**
 * Sonic 2/3 chunk descriptor
 *
 * A Sonic chunk descriptor is used to specify which chunk to draw and how it should be drawn. A chunk may be
 * horizontally and/or vertically flipped.
 *
 * A pattern descriptor is defined as a 16-bit bitmask, in the form:
 *
 *  ???? YXII IIII IIII
 *
 *  Masks:
 *   0x3FF chunk index
 *   0x400 X flip
 *   0x800 Y flip
 */
public final class ChunkDesc {
    private int index;  // 16-bit stored as an int to handle bitmask operations

    public ChunkDesc() {
        this.index = 0;
    }

    public int get() {
        return index;
    }

    public int getChunkIndex() {
        return index & 0x3FF;
    }

    public boolean getHFlip() {
        return (index & 0x400) != 0;
    }

    public boolean getVFlip() {
        return (index & 0x800) != 0;
    }

    public void set(int value) {
        this.index = value;
    }

    public void set(ChunkDesc desc) {
        this.index = desc.index;
    }

    public static int getIndexSize() {
        return Short.BYTES;  // Java equivalent of C++ sizeof(uint16_t)
    }

}
