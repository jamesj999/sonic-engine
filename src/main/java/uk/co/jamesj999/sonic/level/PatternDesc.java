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
public final class PatternDesc {
    private int index;  // Stored as int for bitwise operations, representing 16-bit value

    private boolean priority;  // Cached priority flag
    private int paletteIndex;  // Cached palette index
    private boolean hFlip;  // Cached horizontal flip flag
    private boolean vFlip;  // Cached vertical flip flag
    private int patternIndex;  // Cached pattern index

    // Default instance of an empty pattern descriptor
    public static PatternDesc EMPTY = new PatternDesc();

    // Default constructor
    private PatternDesc() {
        this.index = 0;
        updateFields();  // Initialize cached fields
    }

    // Constructor with index
    public PatternDesc(int index) {
        this.index = index;
        updateFields();
    }

    // Getter for the raw index value
    public int get() {
        return index;
    }

    // Getter for palette index (2-bit value)
    public int getPaletteIndex() {
        return paletteIndex;
    }

    // Getter for pattern index (11-bit value)
    public int getPatternIndex() {
        return patternIndex;
    }

    // Check for priority flag (bit 15)
    public boolean getPriority() {
        return priority;
    }

    // Check for horizontal flip flag (bit 11)
    public boolean getHFlip() {
        return hFlip;
    }

    // Check for vertical flip flag (bit 12)
    public boolean getVFlip() {
        return vFlip;
    }

    // Update all cached fields from index
    private void updateFields() {
        this.priority = (index & 0x8000) != 0;  // Extract priority flag (bit 15)
        this.paletteIndex = (index >> 13) & 0x3;  // Extract palette index (2-bit value from bits 13-14)
        this.hFlip = (index & 0x800) != 0;  // Extract horizontal flip flag (bit 11)
        this.vFlip = (index & 0x1000) != 0;  // Extract vertical flip flag (bit 12)
        this.patternIndex = index & 0x7FF;  // Extract pattern index (lower 11 bits)
    }

    // Setter for the raw index value (assumes correct bit layout)
    public void set(int newIndex) {
        this.index = newIndex;
        updateFields();  // Update cached fields whenever index changes
    }

    // Static method to get the size of the index
    public static int getIndexSize() {
        return Short.BYTES;  // Java equivalent of C++ sizeof(unsigned short)
    }
}
