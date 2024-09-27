package uk.co.jamesj999.sonic.level;

/**
 * Sonic 2/3 Pattern descriptor
 *
 * A pattern descriptor is used to specify which pattern to draw, how it should be drawn, and which palette to use.
 *
 * Patterns may be horizontally and/or vertically flipped, and must be drawn using 1 of 4 palettes.
 *
 * A pattern descriptor is defined as a 16-bit bitmask, in the form:
 *   PCCVHIII IIIIIIII
 *     P    - priority flag         [0..1]
 *     CC   - palette row index     [0..3]
 *     V    - vertical flip flag    [0..1]
 *     H    - horizontal flip flag  [0..1]
 *     I... - pattern index         [0..2047]
 */
public class PatternDesc {
    private int index;  // Stored as int for bitwise operations, representing 16-bit value

    // Default constructor
    public PatternDesc() {
        this.index = 0;
    }

    // Copy constructor
    public PatternDesc(PatternDesc desc) {
        this.index = desc.index;
    }

    // Constructor with patternIndex and paletteIndex
    public PatternDesc(int patternIndex, int paletteIndex) {
        this.index = (paletteIndex & 0x3) << 13;
        this.index |= (patternIndex & 0x7FF);
    }

    // Getter for the raw index value
    public int get() {
        return index;
    }

    // Getter for palette index (2-bit value)
    public int getPaletteIndex() {
        return (index >> 13) & 0x3;
    }

    // Getter for pattern index (11-bit value)
    public int getPatternIndex() {
        return index & 0x7FF;
    }

    // Check for priority flag (bit 15)
    public boolean getPriority() {
        return (index & 0x8000) != 0;
    }

    // Check for horizontal flip flag (bit 11)
    public boolean getHFlip() {
        return (index & 0x800) != 0;
    }

    // Check for vertical flip flag (bit 12)
    public boolean getVFlip() {
        return (index & 0x1000) != 0;
    }

    // Setter for the raw index value (assumes correct bit layout)
    public void set(int newIndex) {
        this.index = newIndex;
    }

    // Static method to get the size of the index
    public static int getIndexSize() {
        return Short.BYTES;  // Java equivalent of C++ sizeof(unsigned short)
    }
}
