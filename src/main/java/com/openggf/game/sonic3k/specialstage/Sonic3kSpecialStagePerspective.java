package com.openggf.game.sonic3k.specialstage;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;

/**
 * Perspective map system for the S3K Blue Ball special stage.
 * <p>
 * Manages the pre-computed perspective maps that control how the 32x32 grid
 * is projected into the pseudo-3D view seen from behind the player.
 * <p>
 * The perspective system uses 16 animation frames (0-15 for moving, 16+ for
 * turning). Each frame is a table of 6-byte entries defining screen positions
 * for visible grid cells. The {@code word_98B0} direction table controls
 * which grid cells are visible based on the current angle quadrant.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm Draw_SSSprites (line 12266)
 */
public class Sonic3kSpecialStagePerspective {

    /**
     * Decompressed perspective maps from ROM.
     * Indexed by animation frame (0-15 for moving, 16-22 for turning).
     * Each frame contains data for a 16x16 grid of visible cells.
     */
    private byte[] perspectiveMaps;

    /**
     * Pointers into perspectiveMaps for each frame.
     * Set up after decompression from the pointer table at RAM_start.
     */
    private int[] framePointers;

    /**
     * Current animation frame (0-15 moving, 16+ turning).
     * ROM: Special_stage_anim_frame
     */
    private int animFrame;

    /**
     * Current palette frame (same as animFrame for moving, 0 while turning).
     * ROM: Special_stage_palette_frame
     */
    private int paletteFrame;

    /**
     * Load perspective maps from decompressed ROM data.
     *
     * @param maps raw decompressed perspective map data
     */
    public void loadMaps(byte[] maps) {
        this.perspectiveMaps = maps;
        // The first 96 bytes (24 longs) are the frame pointer table
        // Each pointer is relative to RAM_start
        if (maps != null && maps.length >= 96) {
            framePointers = new int[24];
            for (int i = 0; i < 24; i++) {
                int offset = i * 4;
                framePointers[i] = ((maps[offset] & 0xFF) << 24)
                        | ((maps[offset + 1] & 0xFF) << 16)
                        | ((maps[offset + 2] & 0xFF) << 8)
                        | (maps[offset + 3] & 0xFF);
            }
        }
    }

    /**
     * Calculate the animation frame based on player position and angle.
     * ROM: Draw_SSSprites (sonic3k.asm:12266-12307)
     *
     * @param player the player state
     */
    public void updateAnimFrame(Sonic3kSpecialStagePlayer player) {
        int angle = player.getAngle();
        int xPos = player.getXPos();
        int yPos = player.getYPos();

        // Determine which position axis to use based on angle bit 6
        int relevantPos;
        if ((angle & ANGLE_AXIS_BIT) != 0) {
            // Bit 6 set: use X position for forward axis
            relevantPos = xPos;
            int yBit = yPos & 0x100;
            relevantPos += 0x100 + yBit;
        } else {
            // Bit 6 clear: use Y position for forward axis
            relevantPos = yPos;
            int xBit = xPos & 0x100;
            relevantPos += xBit; // Note: ROM also adds 0x100 in the X path
        }

        // Apply direction-dependent offset
        if ((angle & 0x80) == 0) {
            // Positive direction: negate and add 0x1F
            relevantPos = (-relevantPos + 0x1F) & 0xFFFF;
            int check = relevantPos & 0xE0;
            if (check != 0) {
                // Not aligned - add 1 to the grid position counter
                // (this is used for subpixel position within cell)
            }
        }

        // Extract frame index from position bits 5-8
        // ROM: andi.w #$1E0,d0 / lsr.w #5,d0
        int moveFrame = (relevantPos & 0x1E0) >> 5;
        animFrame = moveFrame;
        paletteFrame = moveFrame & 0xFF;

        // Check if turning (angle bits 3-5 non-zero)
        int turnBits = angle & 0x38;
        if (turnBits != 0) {
            // During turn: frame = 0x0F + (turnBits >> 3)
            int turnFrame = 0x0F + (turnBits >> 3);
            animFrame = turnFrame;
            // paletteFrame stays as the movement-based value
        }
    }

    /**
     * Get the current animation frame index.
     */
    public int getAnimFrame() {
        return animFrame;
    }

    /**
     * Get the current palette frame.
     */
    public int getPaletteFrame() {
        return paletteFrame;
    }

    Sonic3kSpecialStageSnapshot.PerspectiveSnapshot captureRewindSnapshot() {
        return new Sonic3kSpecialStageSnapshot.PerspectiveSnapshot(animFrame, paletteFrame);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.PerspectiveSnapshot snapshot) {
        animFrame = snapshot.animFrame();
        paletteFrame = snapshot.paletteFrame();
    }

    /**
     * Get the direction table for the current angle quadrant.
     * ROM: word_98B0 (sonic3k.asm:12229)
     *
     * @param angle current player angle (0-255)
     * @return 6-element direction table for this quadrant
     */
    public static int[] getDirectionTable(int angle) {
        int quadrant = (angle & ANGLE_QUADRANT_MASK) >> 6;
        return PERSPECTIVE_DIRECTION_TABLES[quadrant];
    }

    /**
     * Get the perspective data pointer for the current animation frame.
     *
     * @return offset into perspectiveMaps for current frame, or -1 if not loaded
     */
    public int getFrameDataOffset() {
        if (framePointers == null || animFrame >= framePointers.length) {
            return -1;
        }
        return framePointers[animFrame];
    }

    /**
     * Read a 6-byte perspective entry from the maps at the given offset.
     * Each entry contains screen position data for one grid cell.
     *
     * @param entryOffset byte offset into the current frame data
     * @return array of [xOffset, yOffset, ..., ...] or null if data unavailable
     */
    public int[] readPerspectiveEntry(int frameOffset, int entryOffset) {
        if (perspectiveMaps == null) {
            return null;
        }
        int offset = frameOffset + entryOffset;
        if (offset + 6 > perspectiveMaps.length) {
            return null;
        }
        return new int[]{
                perspectiveMaps[offset] & 0xFF,
                perspectiveMaps[offset + 1] & 0xFF,
                (perspectiveMaps[offset + 2] & 0xFF) << 8 | (perspectiveMaps[offset + 3] & 0xFF),
                (perspectiveMaps[offset + 4] & 0xFF) << 8 | (perspectiveMaps[offset + 5] & 0xFF)
        };
    }

    /**
     * Check if perspective map data has been loaded.
     */
    public boolean isLoaded() {
        return perspectiveMaps != null && perspectiveMaps.length > 0;
    }
}
