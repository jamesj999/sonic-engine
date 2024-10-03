package uk.co.jamesj999.sonic.level;

/**
 * Sonic 2/3 chunk descriptor
 *
 * A Sonic chunk descriptor is used to specify which chunk to draw and how it should be drawn. A chunk may be
 * horizontally and/or vertically flipped.
 *
 * A chunk descriptor is defined as a 16-bit bitmask, in the form:
 *
 *  SSTT YXII IIII IIII
 *
 *  Masks:
 *   0x03FF chunk index
 *   0x0400 X flip
 *   0x0800 Y flip
 *   0x3000 Primary Collision layer mode (T) - for vertical collision
 *   0xC000 Secondary Collision layer mode (S) - for horizontal collision
 *
 */
public final class ChunkDesc {
    private int index;  // 16-bit stored as an int to handle bitmask operations

    private int chunkIndex;  // Cached chunk index
    private boolean xFlip;  // Cached X flip
    private boolean yFlip;  // Cached Y flip
    private CollisionMode primaryCollisionMode;  // Cached primary collision mode
    private CollisionMode secondaryCollisionMode;  // Cached secondary collision mode
    private SolidTile solidTile;

    //A Chunk Descriptor that is empty (the default state)
    public static ChunkDesc EMPTY = new ChunkDesc();

    private ChunkDesc() {
        this.index = 0;
    }

    public ChunkDesc(int index, SolidTile solidTile) {
        this.index = index;
        this.solidTile = solidTile;
        updateFields();
    }

    public int get() {
        return index;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public CollisionMode getPrimaryCollisionMode() {
        return primaryCollisionMode;
    }

    public CollisionMode getSecondaryCollisionMode() {
        return secondaryCollisionMode;
    }

    public boolean getHFlip() {
        return xFlip;
    }

    public boolean getVFlip() {
        return yFlip;
    }

    // Update all cached fields from index
    private void updateFields() {
        this.chunkIndex = index & 0x3FF;  // Extract chunk index (lower 10 bits)
        this.xFlip = (index & 0x0400) != 0;  // Extract X flip (11th bit)
        this.yFlip = (index & 0x0800) != 0;  // Extract Y flip (12th bit)

        int primaryBits = (index >> 12) & 0x3;  // Extract primary collision layer mode (13th-14th bits)
        this.primaryCollisionMode = CollisionMode.fromVal(primaryBits);

        int secondaryBits = (index >> 14) & 0x3;  // Extract secondary collision layer mode (15th-16th bits)
        this.secondaryCollisionMode = CollisionMode.fromVal(secondaryBits);
    }

    // Set the index and update cached fields
    public void set(int value) {
        this.index = value;
        updateFields();  // Update cached fields whenever index changes
    }

    public static int getIndexSize() {
        return Short.BYTES;  // Java equivalent of C++ sizeof(uint16_t)
    }

    public SolidTile getSolidTile() {
        return solidTile;
    }

    public void setSolidTile(SolidTile solidTile) {
        this.solidTile = solidTile;
    }
}
