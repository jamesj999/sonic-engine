package com.openggf.game.sonic2.scroll;

import com.openggf.game.GameServices;
import com.openggf.game.sonic2.runtime.HtzRuntimeState;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.M68KMath;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

/**
 * ROM-accurate implementation of SwScrl_HTZ (Hill Top Zone scroll routine).
 * Reference: s2.asm SwScrl_HTZ at ROM $C892 (lines 15779-15971)
 *
 * HTZ uses an elaborate parallax system with:
 * - First 128 lines: All scroll at the same rate (Camera_X >> 3)
 * - Cloud animation counter at TempArray_LayerDef+$22, incremented by 4/frame
 * - Remaining 96 lines: Complex gradient parallax for animated clouds
 *
 * The scroll routine calculates 16 values stored to TempArray_LayerDef (indices 0-15)
 * which Dynamic_HTZ uses to determine cloud tile offsets.
 *
 * Segment layout (lines 128-223, 96 lines total):
 * - 3 lines at scroll value 1
 * - 5 lines at scroll value 2
 * - 7 lines at scroll value 3
 * - 8 lines at scroll value 4
 * - 10 lines at scroll value 5
 * - 15 lines at scroll value 6
 * - 48 lines in 3 groups of 16 at values 7, 8, 9
 */
public class SwScrlHtz extends AbstractZoneScrollHandler {

    private final ParallaxTables tables;
    private final BackgroundCamera bgCamera;

    private short vscrollFactorFG;

    // Screen shake offsets for FG/sprite rendering (calculated during updateScreenShake)
    private int shakeOffsetX = 0;
    private int shakeOffsetY = 0;

    // Cloud animation counter (TempArray_LayerDef+$22 equivalent), +4 each frame.
    // Derived from the frame counter (not the update-call count) so it rewinds
    // correctly — see FrameScrollAccumulator. Offset 0 = read-then-increment.
    private final com.openggf.level.scroll.FrameScrollAccumulator cloudCounter =
            new com.openggf.level.scroll.FrameScrollAccumulator(4, 0);

    // TempArray_LayerDef values for Dynamic_HTZ cloud art streaming
    // 16 word values at offsets 0-30 (indices 0-15)
    private final short[] tempArrayLayerDef = new short[16];

    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    private static final int VISIBLE_LINES = 224;
    private static final int STATIC_LINES = 128;  // First 128 lines use constant scroll

    public SwScrlHtz(ParallaxTables tables, BackgroundCamera bgCamera) {
        this.tables = tables;
        this.bgCamera = bgCamera;
    }

    private HtzRuntimeState htzRuntimeState() {
        return GameServices.zoneRuntimeRegistry()
                .currentAs(HtzRuntimeState.class)
                .orElseThrow(() -> new IllegalStateException("HTZ runtime state not installed"));
    }

    /**
     * Initialize HTZ scroll state.
     * Called when entering HTZ to reset the cloud counter.
     */
    public void init() {
        cloudCounter.reset();
        for (int i = 0; i < 16; i++) {
            tempArrayLayerDef[i] = 0;
        }
    }

    /**
     * Set the screen shake mode flag.
     * Delegates to GameStateManager for global screen shake state.
     * When true, uses simplified scroll with ripple-based shake effect.
     * @deprecated Use GameServices.gameState().setScreenShakeActive() directly
     */
    @Deprecated
    public void setScreenShakeActive(boolean active) {
        GameServices.gameState().setScreenShakeActive(active);
    }

    /**
     * @deprecated Use GameServices.gameState().isScreenShakeActive() directly
     */
    @Deprecated
    public boolean isScreenShakeActive() {
        return GameServices.gameState().isScreenShakeActive();
    }

    /**
     * Get the TempArray_LayerDef values for Dynamic_HTZ cloud art streaming.
     * Returns a copy of the 16 word values.
     */
    public short[] getTempArrayLayerDef() {
        return tempArrayLayerDef.clone();
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {

        resetScrollTracking();
        composer.reset();

        // Default vertical factors for normal mode.
        composer.setVscrollFactorBG((short) bgCamera.getBgYPos());
        vscrollFactorFG = (short) cameraY;

        // Reset shake offsets - quake mode may overwrite.
        shakeOffsetX = 0;
        shakeOffsetY = 0;

        // ROM: SwScrl_HTZ branches on Screen_Shaking_Flag_HTZ, not Screen_Shaking_Flag.
        if (htzRuntimeState().earthquakeActive()) {
            updateEarthquakeMode(horizScrollBuf, cameraX, cameraY, frameCounter);
        } else {
            updateNormal(horizScrollBuf, cameraX, cameraY, frameCounter);
        }
    }

    /**
     * Normal HTZ parallax scrolling (no screen shake).
     * Reference: s2.asm SwScrl_HTZ lines 15779-15971
     *
     * This is a line-by-line translation of the 68000 assembly.
     */
    private void updateNormal(int[] horizScrollBuf, int cameraX, int cameraY, int frameCounter) {
        // Line 15795-15800: move.w (Camera_X_pos).w,d0 / neg.w d0 / move.w d0,d2 / swap d0 / move.w d2,d0 / asr.w #3,d0
        short d0 = (short) -cameraX;
        short d2 = d0;  // d2 = -Camera_X_pos (FG scroll)
        // d0 is now packed: high word = d2 (FG), low word = d2 >> 3 (BG)
        int d0_long = ((d2 & 0xFFFF) << 16) | ((d2 >> 3) & 0xFFFF);

        // Lines 15802-15805: Fill first 128 lines with constant scroll
        composer.fillPackedScrollWords(0, STATIC_LINES, d0_long);

        // Line 15808: move.l d0,d4 (save the packed value for later)
        int d4 = d0_long;

        // Line 15809-15810: Read cloud counter and increment by 4
        // move.w (TempArray_LayerDef+$22).w,d0 / addq.w #4,(TempArray_LayerDef+$22).w
        short cloudScrollValue = (short) cloudCounter.valueAt(frameCounter);

        // Line 15813: sub.w d0,d2 (delta = -cameraX - cloudScrollValue)
        d2 = (short) (d2 - cloudScrollValue);

        // Lines 15851-15866: reduce the delta to 44% (100/2 - 100/16).
        //
        // fixBugs=0 path -- the shipped REV01 behaviour, which is what the traces
        // record and therefore what the engine models. `d1` is NOT cleared first
        // (the `moveq #0,d1` lives inside the `if fixBugs`), but `move.w d0,d1`,
        // `asr.w #4,d1` and `sub.w d1,d0` only ever touch d1's low word, so the
        // stale upper word cannot reach the result.
        //
        //     move.w  d2,d0
        //     move.w  d0,d1
        //     asr.w   #1,d0     ; d0 = delta / 2
        //     asr.w   #4,d1     ; d1 = delta / 16, REMAINDER DISCARDED (word shift)
        //     sub.w   d1,d0     ; d0 = 44% of delta
        //
        // The fixBugs=1 path instead preserves that remainder, by widening the
        // divide to a longword so the fraction survives in the low half:
        //
        //     moveq   #0,d1
        //     move.w  d0,d1
        //     asr.w   #1,d0
        //     swap    d1        ; delta into the high half
        //     asr.l   #4,d1     ; long shift keeps the remainder in the low half
        //     swap    d1        ; d1.low = integer part, d1.high = fraction
        //     sub.w   d1,d0
        //
        // Taking the fixed path makes the clouds scroll smoothly instead of with
        // the ROM's periodic 2-frame stutter, which is why it was once chosen here
        // -- but the stutter is shipped behaviour and the fixed path desyncs any
        // trace column that observes this accumulator. See the FixBugs note in
        // CLAUDE.md / AGENTS.md.
        d0 = d2;
        // move.w d0,d1 (d1 takes the delta before d0 is halved)
        short d1w = d0;
        // asr.w #1,d0
        d0 = (short) (d0 >> 1);
        // asr.w #4,d1 -- remainder discarded
        d1w = (short) (d1w >> 4);
        // sub.w d1,d0
        d0 = (short) (d0 - d1w);
        // ext.l d0
        int d0_ext = d0;  // sign-extended to 32 bits
        // asl.l #8,d0
        d0_ext = d0_ext << 8;
        // divs.w #112,d0 (112 = 256*44/100)
        if (d0_ext != 0) {
            d0_ext = d0_ext / 112;
        }
        // ext.l d0
        d0_ext = (short) d0_ext;  // sign-extend the quotient
        // asl.l #8,d0 (multiply by 256 for fixed-point)
        d0_ext = d0_ext << 8;

        // Line 15847: lea (TempArray_LayerDef).w,a2
        int a2_idx = 0;

        // Lines 15881-15891: initialise the d3 fixed-point accumulator, whose upper
        // 16 bits are the integer part and lower 16 bits the fraction.
        //
        // fixBugs=0 (shipped): `moveq #0,d3 / move.w d1,d3` -- d1's low word is
        // zero-extended into d3, so the accumulator starts with NO fractional part.
        // The disassembly's own comment names this as the cause of the visible
        // cloud jerkiness; it is nevertheless the behaviour the ROM ships and the
        // traces record.
        //
        // fixBugs=1 would be `move.l d1,d3`, carrying the full 32-bit value whose
        // low half holds the fraction preserved by the long shift above.
        long d3 = d1w & 0xFFFFL;

        // Lines 15862-15867: First 3 entries (rept 3)
        // Each: swap d3; add.l d0,d3; swap d3; move.w d3,(a2)+
        for (int i = 0; i < 3; i++) {
            d3 = swap32(d3);
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = swap32(d3);
            tempArrayLayerDef[a2_idx++] = (short) (d3 & 0xFFFF);
        }

        // Line 15868: move.w d3,(a2)+ (4th entry, no add)
        tempArrayLayerDef[a2_idx++] = (short) (d3 & 0xFFFF);

        // Lines 15869-15872: swap d3; add.l d0,d3; add.l d0,d3; swap d3
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);

        // Lines 15874-15883: Loop 4 times, storing d3 3 times, then adding d0 3 times
        for (int loop = 0; loop < 4; loop++) {
            tempArrayLayerDef[a2_idx++] = (short) (d3 & 0xFFFF);
            tempArrayLayerDef[a2_idx++] = (short) (d3 & 0xFFFF);
            tempArrayLayerDef[a2_idx++] = (short) (d3 & 0xFFFF);
            d3 = swap32(d3);
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = swap32(d3);
        }

        // Now output to Horiz_Scroll_Buf for lines 128-223

        // Lines 15886-15887: add.l d0,d0 twice (multiply d0 by 4)
        d0_ext = d0_ext << 2;  // d0 *= 4

        int line = STATIC_LINES;  // Start at line 128

        // Lines 15888-15900: Do 8 lines (3 + 5)
        // move.w d3,d4; move.l d4,(a1)+ (3 times)
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 3 && line < VISIBLE_LINES; i++, line++) {
            composer.writePackedScrollWord(line, d4);
        }
        // swap d3; add.l d0,d3; swap d3; move.w d3,d4; move.l d4,(a1)+ (5 times)
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 5 && line < VISIBLE_LINES; i++, line++) {
            composer.writePackedScrollWord(line, d4);
        }

        // Lines 15902-15910: Do 7 lines
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 7 && line < VISIBLE_LINES; i++, line++) {
            composer.writePackedScrollWord(line, d4);
        }

        // Lines 15912-15921: Do 8 lines (2 adds)
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 8 && line < VISIBLE_LINES; i++, line++) {
            composer.writePackedScrollWord(line, d4);
        }

        // Lines 15923-15932: Do 10 lines (2 adds)
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 10 && line < VISIBLE_LINES; i++, line++) {
            composer.writePackedScrollWord(line, d4);
        }

        // Lines 15934-15944: Do 15 lines (3 adds)
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 15 && line < VISIBLE_LINES; i++, line++) {
            composer.writePackedScrollWord(line, d4);
        }

        // Lines 15946-15966: Do 48 lines in 3 groups of 16 (3 adds before first, 4 adds between)
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);

        for (int group = 0; group < 3; group++) {
            d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
            for (int i = 0; i < 16 && line < VISIBLE_LINES; i++, line++) {
                composer.writePackedScrollWord(line, d4);
            }
            // 4 adds between groups
            d3 = swap32(d3);
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = swap32(d3);
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    /**
     * Swap high and low words of a 32-bit value.
     * Equivalent to 68000 SWAP instruction.
     */
    private long swap32(long value) {
        int v = (int) value;
        return ((v & 0xFFFF) << 16) | ((v >> 16) & 0xFFFF);
    }

    /**
     * HTZ earthquake mode scrolling.
     * Reference: s2.asm HTZ_Screen_Shake lines 15975-16029
     *
     * ROM uses absolute Camera_BG_Y/X_pos for scroll factors:
     *   Vscroll_Factor_BG = Camera_BG_Y_pos (≈ Camera_Y_pos - Camera_BG_Y_offset)
     *   Horiz_Scroll_Buf BG = -Camera_BG_X_pos
     *
     * Our tilemap contains the full BG map, so these absolute positions correctly
     * address BG map rows 0-1 containing lava/cave tile data (256px); VDP wraps vertically.
     */
    private void updateEarthquakeMode(int[] horizScrollBuf, int cameraX, int cameraY, int frameCounter) {
        HtzRuntimeState htzState = htzRuntimeState();
        int bgYOffset = htzState.cameraBgYOffset();
        int bgXOffset = htzState.cameraBgXOffset();

        // Camera_BG positions used by HTZ_Screen_Shake.
        int bgXPos = cameraX - bgXOffset;
        int bgYPos = cameraY - bgYOffset;

        int shakeOffsetV = 0;
        int shakeOffsetH = 0;

        // Ripple is gated by general Screen_Shaking_Flag while HTZ mode remains active.
        if (GameServices.gameState().isScreenShakeActive() && tables != null) {
            int rippleIndex = frameCounter & 0x3F;
            shakeOffsetV = tables.getRippleSigned(rippleIndex);
            if (rippleIndex + 1 < tables.getRippleDataLength()) {
                shakeOffsetH = tables.getRippleSigned(rippleIndex + 1);
            }
        }

        // Store shake offsets for Camera to use for FG tiles and sprites.
        this.shakeOffsetX = shakeOffsetH;
        this.shakeOffsetY = shakeOffsetV;

        // Vscroll_Factor_FG = Camera_Y_pos (+ optional ripple)
        vscrollFactorFG = (short) (cameraY + shakeOffsetV);
        // Vscroll_Factor_BG = Camera_BG_Y_pos (+ optional ripple)
        composer.setVscrollFactorBG((short) (bgYPos + shakeOffsetV));

        // Horizontal scroll uses Camera_X_pos and Camera_BG_X_pos (+ optional ripple).
        short fgScroll = M68KMath.negWord(cameraX + shakeOffsetH);
        short bgScroll = M68KMath.negWord(bgXPos + shakeOffsetH);

        // Fill all 224 lines with same value (no parallax during earthquake mode).
        composer.fillPackedScrollWords(0, VISIBLE_LINES, fgScroll, bgScroll);

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    public short getVscrollFactorFG() {
        return vscrollFactorFG;
    }

    /**
     * @return Horizontal shake offset for this frame (pixels)
     */
    public int getShakeOffsetX() {
        return shakeOffsetX;
    }

    /**
     * @return Vertical shake offset for this frame (pixels)
     */
    public int getShakeOffsetY() {
        return shakeOffsetY;
    }
}
