package com.openggf.game.sonic1.specialstage;

import static com.openggf.game.sonic1.constants.Sonic1Constants.*;

/**
 * Block type metadata for Sonic 1 Special Stage rendering.
 *
 * Transcribed from "_inc/Special Stage Mappings & VRAM Pointers.asm".
 * Each entry defines: mapping type, animation frame, palette index, and VRAM base tile.
 *
 * The SS_MapIndex table has 78 entries (block IDs 0x01 through 0x4E).
 * Block ID 0x00 is empty (no block). Each entry in ROM is 6 bytes:
 *   dc.l mappings|(frame<<24), dc.w make_art_tile(vram,palette,0)
 *
 * For rendering, we use a simplified model: each block is a 3x3 tile sprite
 * (24x24 pixels) positioned at the grid cell's rotated screen coordinates.
 */
public final class Sonic1SpecialStageBlockType {

    private Sonic1SpecialStageBlockType() {}

    /**
     * Mapping type determines sprite layout.
     */
    public enum MappingType {
        WALLS,      // Map_SSWalls: 16 rotation frames, 3x3 tiles each
        BUMPER,     // Map_Bump: 3 animation frames
        BLOCK_3X3,  // Map_SS_R: generic 3x3 block (GOAL, 1UP, R, W, zones, ghost)
        GLASS,      // Map_SS_Glass: 3x3 with multi-frame animation
        UP,         // Map_SS_Up: UP arrow block
        DOWN,       // Map_SS_Down: DOWN arrow block
        RING,       // Map_Ring: ring with 8-frame animation
        EMERALD_3,  // Map_SS_Chaos3: 3x3 emerald (4 palette variants)
        EMERALD_1,  // Map_SS_Chaos1: single-tile emerald
        EMERALD_2   // Map_SS_Chaos2: 2-tile emerald
    }

    /**
     * Rendering info for a single block type.
     */
    public record BlockRenderInfo(
            MappingType mappingType,
            int artTileBase,
            int paletteIndex,
            int animFrame      // base animation frame (walls use dynamic rotation)
    ) {}

    /**
     * Table of block render info, indexed by block ID (1-based: index 0 = block ID 1).
     * Entries transcribed from Special Stage Mappings & VRAM Pointers.asm.
     */
    private static final BlockRenderInfo[] BLOCK_TABLE = {
            // Block IDs 0x01-0x09: Walls, palette 0
            info(MappingType.WALLS, ARTTILE_SS_WALL, 0, 0),  // 0x01
            info(MappingType.WALLS, ARTTILE_SS_WALL, 0, 0),  // 0x02
            info(MappingType.WALLS, ARTTILE_SS_WALL, 0, 0),  // 0x03
            info(MappingType.WALLS, ARTTILE_SS_WALL, 0, 0),  // 0x04
            info(MappingType.WALLS, ARTTILE_SS_WALL, 0, 0),  // 0x05
            info(MappingType.WALLS, ARTTILE_SS_WALL, 0, 0),  // 0x06
            info(MappingType.WALLS, ARTTILE_SS_WALL, 0, 0),  // 0x07
            info(MappingType.WALLS, ARTTILE_SS_WALL, 0, 0),  // 0x08
            info(MappingType.WALLS, ARTTILE_SS_WALL, 0, 0),  // 0x09

            // Block IDs 0x0A-0x12: Walls, palette 1
            info(MappingType.WALLS, ARTTILE_SS_WALL, 1, 0),  // 0x0A
            info(MappingType.WALLS, ARTTILE_SS_WALL, 1, 0),  // 0x0B
            info(MappingType.WALLS, ARTTILE_SS_WALL, 1, 0),  // 0x0C
            info(MappingType.WALLS, ARTTILE_SS_WALL, 1, 0),  // 0x0D
            info(MappingType.WALLS, ARTTILE_SS_WALL, 1, 0),  // 0x0E
            info(MappingType.WALLS, ARTTILE_SS_WALL, 1, 0),  // 0x0F
            info(MappingType.WALLS, ARTTILE_SS_WALL, 1, 0),  // 0x10
            info(MappingType.WALLS, ARTTILE_SS_WALL, 1, 0),  // 0x11
            info(MappingType.WALLS, ARTTILE_SS_WALL, 1, 0),  // 0x12

            // Block IDs 0x13-0x1B: Walls, palette 2
            info(MappingType.WALLS, ARTTILE_SS_WALL, 2, 0),  // 0x13
            info(MappingType.WALLS, ARTTILE_SS_WALL, 2, 0),  // 0x14
            info(MappingType.WALLS, ARTTILE_SS_WALL, 2, 0),  // 0x15
            info(MappingType.WALLS, ARTTILE_SS_WALL, 2, 0),  // 0x16
            info(MappingType.WALLS, ARTTILE_SS_WALL, 2, 0),  // 0x17
            info(MappingType.WALLS, ARTTILE_SS_WALL, 2, 0),  // 0x18
            info(MappingType.WALLS, ARTTILE_SS_WALL, 2, 0),  // 0x19
            info(MappingType.WALLS, ARTTILE_SS_WALL, 2, 0),  // 0x1A
            info(MappingType.WALLS, ARTTILE_SS_WALL, 2, 0),  // 0x1B

            // Block IDs 0x1C-0x24: Walls, palette 3
            info(MappingType.WALLS, ARTTILE_SS_WALL, 3, 0),  // 0x1C
            info(MappingType.WALLS, ARTTILE_SS_WALL, 3, 0),  // 0x1D
            info(MappingType.WALLS, ARTTILE_SS_WALL, 3, 0),  // 0x1E
            info(MappingType.WALLS, ARTTILE_SS_WALL, 3, 0),  // 0x1F
            info(MappingType.WALLS, ARTTILE_SS_WALL, 3, 0),  // 0x20
            info(MappingType.WALLS, ARTTILE_SS_WALL, 3, 0),  // 0x21
            info(MappingType.WALLS, ARTTILE_SS_WALL, 3, 0),  // 0x22
            info(MappingType.WALLS, ARTTILE_SS_WALL, 3, 0),  // 0x23
            info(MappingType.WALLS, ARTTILE_SS_WALL, 3, 0),  // 0x24

            // Block ID 0x25: Bumper (frame 0)
            info(MappingType.BUMPER, ARTTILE_SS_BUMPER, 0, 0),

            // Block ID 0x26: W block (solid)
            info(MappingType.BLOCK_3X3, ARTTILE_SS_W_BLOCK, 0, 0),

            // Block ID 0x27: GOAL
            info(MappingType.BLOCK_3X3, ARTTILE_SS_GOAL, 0, 0),

            // Block ID 0x28: 1UP (Extra Life)
            info(MappingType.BLOCK_3X3, ARTTILE_SS_EXTRA_LIFE, 0, 0),

            // Block ID 0x29: UP
            info(MappingType.UP, ARTTILE_SS_UP_DOWN, 0, 0),

            // Block ID 0x2A: DOWN
            info(MappingType.DOWN, ARTTILE_SS_UP_DOWN, 0, 0),

            // Block ID 0x2B: R block
            info(MappingType.BLOCK_3X3, ARTTILE_SS_R_BLOCK, 1, 0),

            // Block ID 0x2C: Red-White solid block (formerly ghost)
            info(MappingType.GLASS, ARTTILE_SS_RED_WHITE, 0, 0),

            // Block ID 0x2D: Glass block state 1
            info(MappingType.GLASS, ARTTILE_SS_GLASS, 0, 0),
            // Block ID 0x2E: Glass block state 2
            info(MappingType.GLASS, ARTTILE_SS_GLASS, 3, 0),
            // Block ID 0x2F: Glass block state 3
            info(MappingType.GLASS, ARTTILE_SS_GLASS, 1, 0),
            // Block ID 0x30: Glass block state 4 (about to break)
            info(MappingType.GLASS, ARTTILE_SS_GLASS, 2, 0),

            // Block ID 0x31: R block (variant)
            info(MappingType.BLOCK_3X3, ARTTILE_SS_R_BLOCK, 0, 0),

            // Block ID 0x32: Bumper (frame 1)
            info(MappingType.BUMPER, ARTTILE_SS_BUMPER, 0, 1),
            // Block ID 0x33: Bumper (frame 2)
            info(MappingType.BUMPER, ARTTILE_SS_BUMPER, 0, 2),

            // Block IDs 0x34-0x39: Zone number blocks
            info(MappingType.BLOCK_3X3, ARTTILE_SS_ZONE_1, 0, 0), // 0x34
            info(MappingType.BLOCK_3X3, ARTTILE_SS_ZONE_2, 0, 0), // 0x35
            info(MappingType.BLOCK_3X3, ARTTILE_SS_ZONE_3, 0, 0), // 0x36
            info(MappingType.BLOCK_3X3, ARTTILE_SS_ZONE_4, 0, 0), // 0x37
            info(MappingType.BLOCK_3X3, ARTTILE_SS_ZONE_5, 0, 0), // 0x38
            info(MappingType.BLOCK_3X3, ARTTILE_SS_ZONE_6, 0, 0), // 0x39

            // Block ID 0x3A: Ring (animated, 8 frames cycle)
            info(MappingType.RING, ARTTILE_RING, 1, 0),

            // Block IDs 0x3B-0x40: Chaos Emeralds
            info(MappingType.EMERALD_3, ARTTILE_SS_EMERALD, 0, 0), // 0x3B
            info(MappingType.EMERALD_3, ARTTILE_SS_EMERALD, 1, 0), // 0x3C
            info(MappingType.EMERALD_3, ARTTILE_SS_EMERALD, 2, 0), // 0x3D
            info(MappingType.EMERALD_3, ARTTILE_SS_EMERALD, 3, 0), // 0x3E
            info(MappingType.EMERALD_1, ARTTILE_SS_EMERALD, 0, 0), // 0x3F
            info(MappingType.EMERALD_2, ARTTILE_SS_EMERALD, 0, 0), // 0x40

            // Block ID 0x41: Ghost block
            info(MappingType.BLOCK_3X3, ARTTILE_SS_GHOST, 0, 0),

            // Block IDs 0x42-0x45: Ring animation (sparkle frames)
            info(MappingType.RING, ARTTILE_RING, 1, 4), // 0x42
            info(MappingType.RING, ARTTILE_RING, 1, 5), // 0x43
            info(MappingType.RING, ARTTILE_RING, 1, 6), // 0x44
            info(MappingType.RING, ARTTILE_RING, 1, 7), // 0x45

            // Block IDs 0x46-0x49: Emerald sparkle
            info(MappingType.GLASS, ARTTILE_SS_EMERALD_SPARKLE, 1, 0), // 0x46
            info(MappingType.GLASS, ARTTILE_SS_EMERALD_SPARKLE, 1, 1), // 0x47
            info(MappingType.GLASS, ARTTILE_SS_EMERALD_SPARKLE, 1, 2), // 0x48
            info(MappingType.GLASS, ARTTILE_SS_EMERALD_SPARKLE, 1, 3), // 0x49

            // Block ID 0x4A: Ghost switch (ghostState trigger)
            info(MappingType.BLOCK_3X3, ARTTILE_SS_GHOST, 0, 2),

            // Block IDs 0x4B-0x4E: Glass animation (breaking)
            info(MappingType.GLASS, ARTTILE_SS_GLASS, 0, 0), // 0x4B
            info(MappingType.GLASS, ARTTILE_SS_GLASS, 3, 0), // 0x4C
            info(MappingType.GLASS, ARTTILE_SS_GLASS, 1, 0), // 0x4D
            info(MappingType.GLASS, ARTTILE_SS_GLASS, 2, 0), // 0x4E
    };

    private static BlockRenderInfo info(MappingType type, int artTile, int palette, int frame) {
        return new BlockRenderInfo(type, artTile, palette, frame);
    }

    /**
     * Gets rendering info for a block ID.
     * @param blockId Block ID (1-0x4E). ID 0 means empty.
     * @return BlockRenderInfo or null if blockId is 0 or out of range.
     */
    public static BlockRenderInfo getBlockInfo(int blockId) {
        if (blockId <= 0 || blockId > BLOCK_TABLE.length) {
            return null;
        }
        return BLOCK_TABLE[blockId - 1];
    }

    /**
     * Returns true if a block ID represents a solid collision block.
     * Matches sub_1BD30 from the disassembly:
     * - blockId == 0: not solid (empty)
     * - blockId == 0x28: not solid (1UP is collectible, not solid)
     * - blockId >= 0x3A and blockId < 0x4B: not solid (rings, emeralds, sparkles, ghost switch)
     * - Everything else: solid
     */
    public static boolean isSolid(int blockId) {
        if (blockId == 0) return false;
        if (blockId == 0x28) return false;
        if (blockId >= 0x3A && blockId < 0x4B) return false;
        return true;
    }

    /**
     * Returns true if a block ID represents a collectible item (ring, emerald, 1UP).
     */
    public static boolean isCollectible(int blockId) {
        return blockId == 0x3A || blockId == 0x28 || (blockId >= 0x3B && blockId <= 0x40);
    }

    /**
     * Gets the wall rotation frame (0-15) from the current angle.
     * From SS_AniWallsRings: d0 = (v_ssangle >> 2) & 0xF
     */
    public static int getWallRotationFrame(int ssAngle) {
        return ((ssAngle >> 8) >> 2) & 0xF;
    }

    /**
     * Returns the wall group (0-3) and position (0-8) for a wall block ID.
     * Wall blocks 0x01-0x24 are arranged in 4 groups of 9.
     * @return {group, position} or null if not a wall block
     */
    /** Allocation-free packed wall group/position metadata, or -1 for non-walls. */
    public static int getWallGroupAndPositionPacked(int blockId) {
        if (blockId < 0x01 || blockId > 0x24) return -1;
        int index = blockId - 1;
        return ((index / 9) << 8) | (index % 9);
    }

    /**
     * Legacy compatibility API. Hot render paths should use the packed accessor.
     */
    @Deprecated
    public static int[] getWallGroupAndPosition(int blockId) {
        int packed = getWallGroupAndPositionPacked(blockId);
        return packed < 0 ? null : new int[]{wallGroup(packed), wallPosition(packed)};
    }

    public static int wallGroup(int packed) {
        return (packed >>> 8) & 0xFF;
    }

    public static int wallPosition(int packed) {
        return packed & 0xFF;
    }

    /**
     * Maximum valid block ID.
     */
    public static final int MAX_BLOCK_ID = 0x4E;
}
