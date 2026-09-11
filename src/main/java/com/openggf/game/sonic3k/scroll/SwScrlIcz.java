package com.openggf.game.sonic3k.scroll;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.runtime.IczZoneRuntimeState;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.DeformationPlan;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import com.openggf.level.scroll.compose.ScrollValueTable;

import static com.openggf.level.scroll.M68KMath.VISIBLE_LINES;
import static com.openggf.level.scroll.M68KMath.asrWord;
import static com.openggf.level.scroll.M68KMath.negWord;

/**
 * IceCap Zone scroll handler for Sonic 3K.
 *
 * <p>Ports ICZ1_IntroDeform / ICZ1_Deform from sonic3k.asm and
 * ICZ2_OutDeform / ICZ2_InDeform from Lockon S3/Screen Events.asm.
 */
public class SwScrlIcz extends AbstractZoneScrollHandler {
    private static final int ICZ1_INDOOR_X_THRESHOLD = 0x3940;
    private static final int ICZ1_INTRO_BG_PLANE_X = 0x1880;
    private static final int ICZ1_BG_X_OFFSET = 0x1D80;

    private static final int[] ICZ1_INTRO_BG_DEFORM = {
            0x44, 0x0C, 0x0B, 0x0D, 0x18, 0x50, 0x02, 0x06, 0x08, 0x10, 0x18, 0x20, 0x28, 0x7FFF
    };
    private static final int[] ICZ2_OUT_BG_DEFORM = {
            0x5A, 0x26, 0x8030, 0x7FFF
    };
    private static final int[] ICZ2_IN_BG_DEFORM = {
            0x1A0, 0x40, 0x20, 0x18, 0x40, 0x08, 0x08, 0x18, 0x7FFF
    };

    /**
     * ROM: AIZ2_ALZ_BGDeformDelta, reused by ICZ2_OutDeform for the outdoor
     * mountain shimmer.
     */
    private static final short[] AIZ2_ALZ_BG_DEFORM_DELTA = {
            -2,  1,  2,  2, -1,  2,  2,  1,  2, -1, -2, -2, -2,  1, -1, -1,
            -1,  0, -2,  0,  0,  0, -2,  0, -2,  2,  0, -2,  2,  2, -1, -2
    };

    private static final DeformationPlan.ScrollValueTransform NEGATE_WORD = value -> negWord(value);

    private final ScrollEffectComposer composer = new ScrollEffectComposer();
    private final ScrollValueTable icz1IntroTable = ScrollValueTable.ofLength(14);
    private final ScrollValueTable icz2OutTable = ScrollValueTable.ofLength(52);
    private final ScrollValueTable icz2InTable = ScrollValueTable.ofLength(9);

    private int lastActId = -1;
    private int introPreviousCameraY;
    private int introBgCameraYSource;
    // ICZ1 intro auto-scroll longword (ROM +$800/frame). Derived from the frame
    // counter (not the update-call count) so it rewinds correctly. Anchored at
    // the first intro frame after resetActState; offset 0 = read-then-increment.
    private int introAutoBaseFrame;
    private boolean introAutoBaseFrameSet;
    private boolean act2Indoor;
    private int lastBgCameraX = Integer.MIN_VALUE;
    // Foreground plane-A vertical scroll, published to the renderer with the
    // screen-shake offset folded in so the level tiles shake with the sprites
    // and background (ROM ShakeScreen_Setup adds Screen_shake_offset to
    // Camera_Y_pos_copy, the vscroll source for both planes and the sprites).
    private short foregroundVscroll;

    @Override
    public void init(int actId, int cameraX, int cameraY) {
        resetActState(actId, cameraX, cameraY);
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        if (actId != lastActId || frameCounter == 0) {
            resetActState(actId, cameraX, cameraY);
        }

        composer.reset();
        short fgScroll = negWord(cameraX);
        // Fold the shake into the foreground vscroll on every path; the offset is
        // 0 outside the snowboard crash, so this equals the default camera Y and
        // is a no-op there.
        foregroundVscroll = (short) (cameraY + resolveShakeOffsetY());

        if (actId == 0) {
            if (((short) cameraX & 0xFFFF) >= ICZ1_INDOOR_X_THRESHOLD) {
                updateAct1Indoor(cameraX, cameraY, fgScroll);
            } else {
                updateAct1Intro(cameraX, cameraY, fgScroll, frameCounter);
            }
        } else if (isAct2Indoor(cameraX, cameraY)) {
            act2Indoor = true;
            updateAct2Indoor(cameraX, cameraY, fgScroll);
        } else {
            act2Indoor = false;
            updateAct2Outdoor(cameraX, frameCounter, fgScroll);
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    @Override
    public int getBgCameraX() {
        return lastBgCameraX;
    }

    @Override
    public int getShakeOffsetY() {
        return resolveShakeOffsetY();
    }

    @Override
    public short getVscrollFactorFG() {
        return foregroundVscroll;
    }

    /**
     * ICZ vertical screen-shake offset (ROM {@code Screen_shake_offset}). Sourced
     * from the ICZ event layer so the BG fold-in here and the shared
     * {@code ParallaxManager} -> {@code Camera} propagation (foreground + sprites)
     * stay in sync. The ICZ event {@code update()} ticks the shake before
     * rendering, so reading it multiple times in one frame is stable.
     */
    private int resolveShakeOffsetY() {
        if (!GameServices.hasRuntime()) {
            return 0;
        }
        return GameServices.zoneRuntimeRegistry()
                .currentAs(IczZoneRuntimeState.class)
                .map(IczZoneRuntimeState::screenShakeOffsetY)
                .orElse(0);
    }

    private void resetActState(int actId, int cameraX, int cameraY) {
        lastActId = actId;
        introPreviousCameraY = (short) cameraY;
        introBgCameraYSource = (short) cameraY;
        int x = ((short) cameraX) & 0xFFFF;
        if (actId == 0 && x >= 0x3580 && x < ICZ1_INDOOR_X_THRESHOLD) {
            introBgCameraYSource = (short) (introBgCameraYSource + 0x2800);
        }
        introAutoBaseFrameSet = false;
        act2Indoor = shouldAct2StartIndoors(cameraX, cameraY);
        lastBgCameraX = Integer.MIN_VALUE;
    }

    private void updateAct1Intro(int cameraX, int cameraY, short fgScroll, int frameCounter) {
        adjustIntroBgDuringLoop(cameraY);
        // ROM ShakeScreen_Setup folds Screen_shake_offset into Camera_Y_pos_copy
        // before ICZ1_Deform samples it, so the background shakes with the
        // foreground and sprites during the snowboard crash.
        int bgY = asrWord(introBgCameraYSource, 7) + resolveShakeOffsetY();
        composer.setVscrollFactorBG((short) bgY);
        buildIcz1IntroTable(cameraX, frameCounter);
        DeformationPlan.applyTableBands(
                composer,
                bgY,
                fgScroll,
                icz1IntroTable,
                ICZ1_INTRO_BG_DEFORM,
                0,
                NEGATE_WORD);
        lastBgCameraX = ICZ1_INTRO_BG_PLANE_X;
    }

    private void updateAct1Indoor(int cameraX, int cameraY, short fgScroll) {
        int bgY = asrWord(cameraY, 1);
        int bgX = asrWord(cameraX, 1) - ICZ1_BG_X_OFFSET;
        composer.setVscrollFactorBG((short) bgY);
        composer.fillPackedScrollWords(0, VISIBLE_LINES, fgScroll, negWord(bgX));
        lastBgCameraX = bgX;
    }

    private void updateAct2Outdoor(int cameraX, int frameCounter, short fgScroll) {
        composer.setVscrollFactorBG((short) 0);
        buildIcz2OutdoorTable(cameraX, frameCounter);
        DeformationPlan.applyFlaggedTableBands(
                composer,
                0,
                fgScroll,
                icz2OutTable,
                ICZ2_OUT_BG_DEFORM,
                0,
                NEGATE_WORD);
        lastBgCameraX = Integer.MIN_VALUE;
    }

    private void updateAct2Indoor(int cameraX, int cameraY, short fgScroll) {
        int bgY = asrWord(cameraY - 0x700, 2) + 0x118;
        composer.setVscrollFactorBG((short) bgY);
        int bgX = buildIcz2IndoorTable(cameraX);
        DeformationPlan.applyTableBands(
                composer,
                bgY,
                fgScroll,
                icz2InTable,
                ICZ2_IN_BG_DEFORM,
                0,
                NEGATE_WORD);
        lastBgCameraX = bgX;
    }

    /**
     * ROM: Adjust_BGDuringLoop with d2=$400, d3=$800, source Camera_Y_pos_copy.
     */
    private void adjustIntroBgDuringLoop(int cameraY) {
        int current = (short) cameraY;
        int delta = (short) (current - introPreviousCameraY);
        introPreviousCameraY = current;

        if (delta < 0) {
            int magnitude = (short) -delta;
            if ((magnitude & 0xFFFF) >= 0x400) {
                magnitude = (short) (magnitude - 0x800);
            }
            introBgCameraYSource = (short) (introBgCameraYSource - magnitude);
            return;
        }

        if ((delta & 0xFFFF) >= 0x400) {
            delta = (short) (delta - 0x800);
        }
        introBgCameraYSource = (short) (introBgCameraYSource + delta);
    }

    /**
     * ROM: ICZ1_IntroDeform, including the persistent auto-scroll longword
     * stored just after the 14 visible deformation entries in HScroll_table.
     */
    private void buildIcz1IntroTable(int cameraX, int frameCounter) {
        icz1IntroTable.clear();
        int d0 = ((short) cameraX) << 16;
        d0 >>= 5;
        int d1 = d0;
        int d3 = 0;
        int index = 0;

        for (int i = 0; i < 5; i++) {
            icz1IntroTable.set(index++, (short) (d0 >> 16));
            d0 += d1;
            d1 += d3;
            d3 += 0x800;
        }

        d0 += d1;
        d1 += d1 >> 1;
        if (!introAutoBaseFrameSet) {
            introAutoBaseFrame = frameCounter;
            introAutoBaseFrameSet = true;
        }
        d3 = (frameCounter - introAutoBaseFrame) * 0x800;

        for (int i = 0; i < 9; i++) {
            icz1IntroTable.set(index++, (short) (d0 >> 16));
            d0 += d1;
            d1 += d3;
            d3 += 0x800;
        }
    }

    /**
     * ROM: ICZ2_OutDeform (Lockon S3/Screen Events.asm:1257-1307).
     */
    private void buildIcz2OutdoorTable(int cameraX, int frameCounter) {
        icz2OutTable.clear();

        int d0 = (short) (cameraX + asrWord(frameCounter, 1));
        d0 <<= 16;
        d0 >>= 1;
        d0 &= 0x7FFFFF;
        int d1 = d0 >> 6;

        int index = 50;
        for (int i = 0; i < 0x28; i++) {
            icz2OutTable.set(--index, (short) (d0 >> 16));
            d0 -= d1;
        }

        d0 = ((short) cameraX) << 16;
        d0 >>= 1;
        d1 = d0;
        d0 >>= 1;
        d1 += d0;
        icz2OutTable.set(50, (short) (d1 >> 16));
        icz2OutTable.set(51, (short) d1);

        d0 >>= 2;
        d1 = d0;
        int base = (short) (d0 >> 16);
        icz2OutTable.set(0, (short) base);

        d0 += d1;
        base = (short) (d0 >> 16);
        icz2OutTable.set(1, (short) base);

        int deltaIndex = (asrWord(frameCounter, 2) & 0x3E) >> 1;
        for (int i = 0; i < 8; i++) {
            short delta = AIZ2_ALZ_BG_DEFORM_DELTA[(deltaIndex + i) & 0x1F];
            icz2OutTable.set(2 + i, (short) (base + delta));
        }
    }

    /**
     * ROM: ICZ2_InDeform (Lockon S3/Screen Events.asm:1310-1348).
     *
     * @return Camera_X_pos_BG_copy, the center visual band.
     */
    private int buildIcz2IndoorTable(int cameraX) {
        icz2InTable.clear();
        int d0 = ((short) cameraX) << 16;
        d0 >>= 1;
        int d1 = d0 >> 3;

        int value = d0 >> 16;
        icz2InTable.set(0, (short) value);
        icz2InTable.set(8, (short) value);

        d0 -= d1;
        value = d0 >> 16;
        icz2InTable.set(1, (short) value);
        icz2InTable.set(7, (short) value);

        d0 -= d1;
        value = d0 >> 16;
        icz2InTable.set(2, (short) value);
        icz2InTable.set(6, (short) value);

        d0 -= d1;
        value = d0 >> 16;
        icz2InTable.set(3, (short) value);
        icz2InTable.set(5, (short) value);

        d0 -= d1;
        int bgX = d0 >> 16;
        icz2InTable.set(4, (short) bgX);

        return (short) bgX;
    }

    private boolean shouldAct2StartIndoors(int cameraX, int cameraY) {
        int x = ((short) cameraX) & 0xFFFF;
        int y = ((short) cameraY) & 0xFFFF;
        if (x >= 0x3600) {
            return false;
        }
        if (y >= 0x720) {
            return true;
        }
        return x >= 0x1000 && y >= 0x580;
    }

    private boolean isAct2Indoor(int cameraX, int cameraY) {
        int x = ((short) cameraX) & 0xFFFF;
        int y = ((short) cameraY) & 0xFFFF;
        if (!act2Indoor) {
            return x >= 0x1000 && x < 0x3600 && y >= 0x720;
        }
        if (x >= 0x1900 && x < 0x1B80) {
            return true;
        }
        return !(x < 0x1000 || x >= 0x3600 || y < 0x720);
    }
}
