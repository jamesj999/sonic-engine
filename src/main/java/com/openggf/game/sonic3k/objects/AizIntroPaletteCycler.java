package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.graphics.GraphicsManager;
import java.util.logging.Logger;
import com.openggf.level.Level;
import com.openggf.level.objects.ObjectServices;

/**
 * Palette cycling for the AIZ1 intro's Super Sonic visual effect.
 * Port of sub_679B8 (sonic3k.asm:135904).
 *
 * This is NOT the SuperStateController palette cycling - it's a standalone
 * helper used only by the intro cutscene object. Cycles through
 * PalCycle_SuperSonic entries at 6-frame intervals.
 */
public class AizIntroPaletteCycler {
    private static final Logger LOG = Logger.getLogger(AizIntroPaletteCycler.class.getName());
    private static final int TIMER_PERIOD = 6;
    private static final int FRAME_ADVANCE = 6;   // bytes per cycle step
    private static final int CYCLE_MIN = 0x24;     // cycling range start
    private static final int CYCLE_MAX = 0x36;     // cycling range end (inclusive)
    private static final int MAPPING_FRAME_EVEN = 0x21;
    private static final int MAPPING_FRAME_ODD = 0x22;

    /** Sonic palette line index (line 0). */
    private static final int SONIC_PALETTE_INDEX = 0;

    /** First color index within the palette line to overwrite (colors 2, 3, 4). */
    private static final int FIRST_COLOR_INDEX = 2;

    /** Number of colors written per cycle step (3 MD colors = 6 bytes). */
    private static final int COLORS_PER_STEP = 3;

    private int paletteTimer;
    private int paletteFrame;
    private final ObjectServices services;

    public AizIntroPaletteCycler() {
        this(null);
    }

    public AizIntroPaletteCycler(ObjectServices services) {
        this.services = services;
    }

    public void init() {
        paletteTimer = TIMER_PERIOD;
        paletteFrame = CYCLE_MIN;
    }

    /**
     * Advance one frame. Decrements timer; on expiry, advances palette frame
     * and resets timer. Wraps frame index within cycling range.
     */
    public void advance() {
        paletteTimer--;
        if (paletteTimer < 0) {
            paletteTimer = TIMER_PERIOD;
            paletteFrame += FRAME_ADVANCE;
            if (paletteFrame > CYCLE_MAX) {
                paletteFrame = CYCLE_MIN;
            }
        }
    }

    /**
     * Writes the current palette cycle colors to the GPU.
     * Reads 3 Mega Drive colors from the cycle data at the current frame offset
     * and writes them to palette line 0, colors 2-4.
     *
     * Reference: Sonic2SuperStateController.applyPaletteFrame() pattern.
     */
    public void applyToGpu() {
        byte[] data = AizIntroArtLoader.getSuperSonicPaletteCycleData();
        if (data == null || data.length == 0) return;

        // paletteFrame is already the correct byte offset into cycle data (range 0x24..0x36)
        int offset = paletteFrame;
        if (offset + COLORS_PER_STEP * 2 > data.length) return;

        try {
            Level level = currentLevel();
            if (level == null) return;
            byte[] patch = new byte[COLORS_PER_STEP * 2];
            System.arraycopy(data, offset, patch, 0, patch.length);
            S3kPaletteWriteSupport.applyContiguousPatch(
                    services != null ? services.paletteOwnershipRegistryOrNull() : null,
                    level,
                    graphicsManager(),
                    S3kPaletteOwners.AIZ_INTRO_SUPER_PALETTE,
                    S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                    SONIC_PALETTE_INDEX,
                    FIRST_COLOR_INDEX,
                    patch);
        } catch (Exception e) {
            LOG.fine(() -> "AizIntroPaletteCycler.applyToGpu: " + e.getMessage());
        }
    }

    /** Get the Super Sonic mapping frame based on V-blank parity. */
    public int getMappingFrame(int frameCounter) {
        return (frameCounter & 1) != 0 ? MAPPING_FRAME_ODD : MAPPING_FRAME_EVEN;
    }

    private Level currentLevel() {
        return services != null ? services.currentLevel() : null;
    }

    private GraphicsManager graphicsManager() {
        return services != null ? services.graphicsManager() : null;
    }

    public int getPaletteFrame() { return paletteFrame; }
    public void setPaletteFrame(int frame) { this.paletteFrame = frame; }
}
