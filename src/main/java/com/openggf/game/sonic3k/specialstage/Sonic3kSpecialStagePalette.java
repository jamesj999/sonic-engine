package com.openggf.game.sonic3k.specialstage;

import com.openggf.level.Palette;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Palette management for the S3K Blue Ball special stage.
 * <p>
 * Manages the 4 palette lines used by the special stage:
 * <ul>
 *   <li>Line 0: Player character palette (overwritten for Knuckles)</li>
 *   <li>Line 1: Sphere/ring/icon palette (shared)</li>
 *   <li>Line 2: Background and secondary art palette</li>
 *   <li>Line 3: Checkerboard floor palette (rotated per-frame)</li>
 * </ul>
 * <p>
 * The floor palette (line 3) rotates based on the animation frame,
 * creating the color-cycling effect on the checkerboard as the player
 * moves forward.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm
 * Pal_SStage_Main (line 11154), Rotate_SSPal (line 11017)
 */
public class Sonic3kSpecialStagePalette {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kSpecialStagePalette.class.getName());

    /** The 4 active palette lines. */
    private final Palette[] palettes = new Palette[4];

    /** ROM address of the current stage's rotation palette. */
    private byte[] stagePaletteData;

    /** Whether the fade timer is active (suppresses rotation). */
    private boolean fadeActive;

    /**
     * Initialize palettes from ROM data.
     *
     * @param dataLoader the ROM data loader
     * @param stageIndex current stage (0-7)
     * @param isKnuckles true if player is Knuckles
     * @param skMode true for S&K stages (uses K palettes)
     */
    public void initialize(Sonic3kSpecialStageDataLoader dataLoader,
                           int stageIndex, boolean isKnuckles, boolean skMode)
            throws IOException {
        // Load main palette (4 lines, 128 bytes)
        Palette[] mainPalettes = dataLoader.createMainPalettes();
        for (int i = 0; i < 4; i++) {
            palettes[i] = mainPalettes[i];
        }

        // Knuckles: patch colors 8-15 of palette line 0 with Knux-specific colors.
        // ROM: lea (Target_palette+$10).w,a2 — copies 8 words starting at color 8.
        // This replaces Sonic's blue body colors (indices 8-11) with Knuckles' reds.
        if (isKnuckles) {
            byte[] knuxPatch = dataLoader.getKnuxPalettePatch();
            if (knuxPatch != null) {
                for (int c = 0; c < 8 && (c * 2 + 1) < knuxPatch.length; c++) {
                    palettes[0].getColor(c + 8).fromSegaFormat(knuxPatch, c * 2);
                }
            }
        }

        // Load stage-specific palette for floor rotation and BG tint
        stagePaletteData = dataLoader.getStagePalette(stageIndex & 7, skMode);

        if (stagePaletteData != null) {
            // Apply initial stage palette to line 3 (floor colors)
            applyRotationPalette(0);

            // Apply stage-specific colors to palette line 2 (background tint).
            // ROM: move.l $10(a1),$50(a2) / move.w $14(a1),$54(a2)
            // Reads bytes 32-37 of stage palette → line 2 colors 8-10.
            if (stagePaletteData.length >= 38) {
                for (int c = 0; c < 3; c++) {
                    int offset = 32 + c * 2;
                    palettes[2].getColor(c + 8).fromSegaFormat(stagePaletteData, offset);
                }
            }
        }

        fadeActive = false;

        LOGGER.fine("Initialized SS palettes for stage " + stageIndex
                + (isKnuckles ? " (Knuckles)" : "")
                + (skMode ? " (SK mode)" : ""));
    }

    /**
     * Update palette rotation based on the current animation frame.
     * ROM: Rotate_SSPal (sonic3k.asm:11017)
     * <p>
     * The stage palette data contains 16 bytes per frame, and the frame
     * index selects which 16 bytes to use for palette line 3.
     *
     * @param animFrame current animation frame (0-15 for moving)
     * @param paletteFrame palette-specific frame (0 while turning)
     * @param isTurningNeg true if turning with negative direction
     */
    public void updateRotation(int animFrame, int paletteFrame, boolean isTurningNeg) {
        if (fadeActive) {
            return;
        }

        int frameToUse;
        if (animFrame >= 0x10) {
            // Turning: use palette frame if turning negative, else suppress
            if (!isTurningNeg) {
                return;
            }
            frameToUse = paletteFrame & 0xF;
        } else {
            frameToUse = animFrame;
        }

        applyRotationPalette(frameToUse);
    }

    /**
     * Apply the rotation palette for the given frame index.
     * ROM: Rotate_SSPal (sonic3k.asm:11028)
     * <p>
     * The palette data is indexed in reverse: frame 0 uses offset 0x10,
     * frame 1 uses offset 0x0E, etc.
     */
    private void applyRotationPalette(int frameIndex) {
        if (stagePaletteData == null) {
            return;
        }

        // ROM: andi.w #$E,d0 / neg.w d0 / addi.w #$10,d0
        int palOffset = 0x10 - (frameIndex & 0xE);

        // Apply to the last 8 colors of palette line 3
        // ROM: lea (Normal_palette_line_4+$10).w,a2
        if (palOffset + 16 <= stagePaletteData.length) {
            for (int c = 0; c < 8 && (palOffset + c * 2 + 1) < stagePaletteData.length; c++) {
                palettes[3].getColor(c + 8).fromSegaFormat(stagePaletteData, palOffset + c * 2);
            }
        }
    }

    /**
     * Apply emerald palette colors.
     *
     * @param emeraldData raw emerald palette data (64 bytes = 8 stages x 8 bytes)
     * @param stageIndex current stage (0-7)
     */
    public void applyEmeraldPalette(byte[] emeraldData, int stageIndex) {
        if (emeraldData == null) return;
        int offset = (stageIndex & 7) * 8;
        // Apply to palette line 3, colors 2-5
        for (int c = 0; c < 4 && (offset + c * 2 + 1) < emeraldData.length; c++) {
            palettes[3].getColor(c + 2).fromSegaFormat(emeraldData, offset + c * 2);
        }
    }

    public void fadeTowardWhiteOneStep(int step) {
        for (Palette palette : palettes) {
            if (palette == null) {
                continue;
            }
            for (int c = 0; c < 16; c++) {
                Palette.Color color = palette.getColor(c);
                int r = color.r & 0xFF;
                int g = color.g & 0xFF;
                int b = color.b & 0xFF;
                if (r < 255) {
                    color.r = (byte) Math.min(255, r + step);
                } else if (g < 255) {
                    color.g = (byte) Math.min(255, g + step);
                } else if (b < 255) {
                    color.b = (byte) Math.min(255, b + step);
                }
            }
        }
    }

    public void setFadeActive(boolean active) {
        this.fadeActive = active;
    }

    public Palette[] getPalettes() {
        return palettes;
    }

    public Palette getPalette(int line) {
        return palettes[line & 3];
    }

    Sonic3kSpecialStageSnapshot.PaletteSnapshot captureRewindSnapshot() {
        return new Sonic3kSpecialStageSnapshot.PaletteSnapshot(
                palettes,
                stagePaletteData != null ? stagePaletteData.clone() : null,
                fadeActive);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.PaletteSnapshot snapshot) {
        Sonic3kSpecialStageSnapshot.copyPalettesInto(snapshot.palettes(), palettes);
        stagePaletteData = snapshot.stagePaletteData() != null ? snapshot.stagePaletteData().clone() : null;
        fadeActive = snapshot.fadeActive();
    }
}
