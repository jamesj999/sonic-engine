package com.openggf.game.sonic3k.scroll;

import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.DeformationPlan;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import com.openggf.level.scroll.compose.ScrollValueTable;
import com.openggf.level.scroll.compose.WaterlineBlendComposer;

import static com.openggf.level.scroll.M68KMath.VISIBLE_LINES;
import static com.openggf.level.scroll.M68KMath.asrWord;
import static com.openggf.level.scroll.M68KMath.negWord;

/**
 * Hydrocity Zone (HCZ) scroll handler for Sonic 3K.
 *
 * <p>Ports HCZ1_Deform and HCZ2_Deform from the S3K disassembly.
 */
public class SwScrlHcz extends AbstractZoneScrollHandler {

    private static final int[] HCZ1_DEFORM_HEIGHTS = {
            0x40, 8, 8, 5, 5, 6, 0xF0, 6, 5, 5, 8, 8, 0x30, 0x80C0, 0x7FFF
    };
    private static final int EQUILIBRIUM_Y = 0x610;
    private static final int BG_Y_OFFSET = 0x190;
    private static final int WATERLINE_THRESHOLD = 0x60;
    private static final int HCZ1_HSCROLL_SIZE = 206;
    private static final int HCZ1_WATERLINE_START = 13;
    private static final int HCZ1_WATERLINE_MIDPOINT = 109;
    private static final int HCZ1_WATERLINE_END = 205;

    private static final int[] HCZ2_DEFORM_HEIGHTS = {
            8, 8, 0x90, 0x10, 8, 0x30, 0x18, 8, 8, 0xA8, 0x30, 0x18,
            8, 8, 0xA8, 0x30, 0x18, 8, 8, 0xB0, 0x10, 8, 0x7FFF
    };
    private static final int[] HCZ2_DEFORM_INDEX = {
            4 - 1, 0x0A, 0x14, 0x1E, 0x2C,
            3 - 1, 0x0C, 0x16, 0x20,
            6 - 1, 0x00, 0x08, 0x0E, 0x18, 0x22, 0x2A,
            4 - 1, 0x02, 0x10, 0x1A, 0x24,
            2 - 1, 0x12, 0x1C,
            2 - 1, 0x06, 0x28,
            2 - 1, 0x04, 0x26,
            0xFF
    };
    private static final int HCZ2_HSCROLL_SIZE = 24;

    private static final int WALL_CHASE_BG_Y_OFFSET = 0x500;
    private static final int WALL_CHASE_BG_X_OFFSET = 0x200;

    private static final DeformationPlan.ScrollValueTransform NEGATE_WORD = value -> negWord(value);
    private static final WaterlineBlendComposer WATERLINE_BLEND = new WaterlineBlendComposer(
            HCZ1_WATERLINE_START,
            HCZ1_WATERLINE_MIDPOINT,
            HCZ1_WATERLINE_END,
            WATERLINE_THRESHOLD
    );

    public enum Hcz2BgPhase {
        WALL_CHASE,
        NORMAL
    }

    private final ScrollEffectComposer composer = new ScrollEffectComposer();
    private final ScrollValueTable hcz1HScroll = ScrollValueTable.ofLength(HCZ1_HSCROLL_SIZE);
    private final ScrollValueTable hcz2HScroll = ScrollValueTable.ofLength(HCZ2_HSCROLL_SIZE);
    private final byte[] waterlineData;

    private Hcz2BgPhase hcz2Phase = Hcz2BgPhase.NORMAL;
    private int screenShakeOffset;
    private int wallChaseOffsetX;
    private int lastBgCameraX = Integer.MIN_VALUE;
    private short vscrollFactorFG;

    public SwScrlHcz(byte[] waterlineData) {
        this.waterlineData = waterlineData;
    }

    public SwScrlHcz() {
        this(null);
    }

    public void setHcz2BgPhase(Hcz2BgPhase phase) {
        this.hcz2Phase = phase;
    }

    public Hcz2BgPhase getHcz2BgPhase() {
        return hcz2Phase;
    }

    public void setScreenShakeOffset(int offset) {
        this.screenShakeOffset = offset;
    }

    public void setWallChaseOffsetX(int offset) {
        this.wallChaseOffsetX = offset;
    }

    /**
     * Prime the collision-facing BG camera state before physics runs.
     *
     * <p>ROM: HCZ2_WallMove writes Camera_X/Y_pos_BG_copy before the dual-path
     * BG collision routines read Camera_X/Y_diff. The engine's normal scroll
     * update runs later in the frame, so HCZ2 needs this pre-physics cache.
     */
    public void primeBgCollisionState(int cameraX, int cameraY) {
        if (hcz2Phase == Hcz2BgPhase.WALL_CHASE) {
            lastBgCameraX = cameraX - WALL_CHASE_BG_X_OFFSET + wallChaseOffsetX;
            vscrollFactorBG = (short) (cameraY - WALL_CHASE_BG_Y_OFFSET);
            return;
        }
        lastBgCameraX = Integer.MIN_VALUE;
    }

    @Override
    public short getVscrollFactorFG() {
        return vscrollFactorFG;
    }

    @Override
    public int getBgCameraX() {
        return lastBgCameraX;
    }

    @Override
    public int getShakeOffsetY() {
        return screenShakeOffset;
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        resetScrollTracking();
        composer.reset();
        composer.setVscrollFactorFG((short) 0);

        if (actId == 0) {
            lastBgCameraX = Integer.MIN_VALUE;
            updateHcz1(cameraX, cameraY);
        } else {
            updateHcz2(cameraX, cameraY);
            if (screenShakeOffset != 0) {
                composer.setVscrollFactorFG((short) (cameraY + screenShakeOffset));
            }
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
        if (minScrollOffset == Integer.MAX_VALUE) {
            minScrollOffset = 0;
            maxScrollOffset = 0;
        }
        vscrollFactorBG = composer.getVscrollFactorBG();
        vscrollFactorFG = composer.getVscrollFactorFG();
    }

    private void updateHcz1(int cameraX, int cameraY) {
        hcz1HScroll.clear();

        short delta = (short) (cameraY - EQUILIBRIUM_Y);
        short quarterDelta = (short) (delta >> 2);
        short equilibriumDelta = (short) (quarterDelta - delta);
        composer.setVscrollFactorBG((short) (quarterDelta + BG_Y_OFFSET));

        int scrollValue = ((short) cameraX) << 16;
        if (equilibriumDelta != 0) {
            int step = scrollValue >> 7;
            if (equilibriumDelta <= -WATERLINE_THRESHOLD) {
                fillMiddleForward(scrollValue, step);
            } else {
                fillMiddleBackward(scrollValue, step);
            }
        }

        fillCaveBands(scrollValue);
        short fastestCaveValue = hcz1HScroll.get(0);
        short slowestCaveValue = hcz1HScroll.get(6);
        WATERLINE_BLEND.apply(hcz1HScroll, equilibriumDelta, fastestCaveValue, slowestCaveValue, waterlineData);

        DeformationPlan.applyFlaggedTableBands(
                composer,
                composer.getVscrollFactorBG(),
                negWord(cameraX),
                hcz1HScroll,
                HCZ1_DEFORM_HEIGHTS,
                0,
                NEGATE_WORD);
    }

    private void fillMiddleForward(int value, int step) {
        int index = HCZ1_WATERLINE_START;
        for (int i = 0; i < 48; i++) {
            hcz1HScroll.set(index++, (short) (value >> 16));
            value -= step;
            hcz1HScroll.set(index++, (short) (value >> 16));
            value -= step;
        }
    }

    private void fillMiddleBackward(int value, int step) {
        int index = HCZ1_WATERLINE_END;
        for (int i = 0; i < 48; i++) {
            hcz1HScroll.set(--index, (short) (value >> 16));
            value -= step;
            hcz1HScroll.set(--index, (short) (value >> 16));
            value -= step;
        }
    }

    private void fillCaveBands(int cameraXFixed) {
        int value = cameraXFixed >> 2;
        int step = cameraXFixed >> 5;
        for (int level = 0; level < 7; level++) {
            short scroll = (short) (value >> 16);
            hcz1HScroll.set(level, scroll);
            hcz1HScroll.set(12 - level, scroll);
            value -= step;
        }
        hcz1HScroll.set(HCZ1_HSCROLL_SIZE - 1, hcz1HScroll.get(6));
    }

    private void updateHcz2(int cameraX, int cameraY) {
        short fgScroll = negWord(cameraX);

        if (hcz2Phase == Hcz2BgPhase.WALL_CHASE) {
            int bgCameraX = cameraX - WALL_CHASE_BG_X_OFFSET + wallChaseOffsetX;
            lastBgCameraX = bgCameraX;
            composer.setVscrollFactorBG((short) (cameraY - WALL_CHASE_BG_Y_OFFSET));
            short bgScroll = negWord(bgCameraX);
            composer.fillPackedScrollWords(0, VISIBLE_LINES, fgScroll, bgScroll);
            return;
        }

        hcz2HScroll.clear();
        lastBgCameraX = Integer.MIN_VALUE;
        short shakeY = (short) screenShakeOffset;
        composer.setVscrollFactorBG((short) (asrWord(cameraY - shakeY, 2) + shakeY));

        buildHcz2HScroll(cameraX);
        DeformationPlan.applyFlaggedTableBands(
                composer,
                composer.getVscrollFactorBG(),
                fgScroll,
                hcz2HScroll,
                HCZ2_DEFORM_HEIGHTS,
                0,
                NEGATE_WORD);
    }

    private void buildHcz2HScroll(int cameraX) {
        int value = ((short) cameraX) << 16;
        value >>= 1;
        int step = value >> 3;

        int position = 0;
        while (position < HCZ2_DEFORM_INDEX.length) {
            int count = HCZ2_DEFORM_INDEX[position++];
            if ((count & 0x80) != 0) {
                break;
            }

            short scroll = (short) (value >> 16);
            for (int i = 0; i <= count; i++) {
                int wordIndex = HCZ2_DEFORM_INDEX[position++] >> 1;
                if (wordIndex < hcz2HScroll.size()) {
                    hcz2HScroll.set(wordIndex, scroll);
                }
            }
            value -= step;
        }
    }
}
