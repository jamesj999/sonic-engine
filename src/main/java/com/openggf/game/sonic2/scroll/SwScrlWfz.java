package com.openggf.game.sonic2.scroll;

import com.openggf.game.GameServices;
import com.openggf.game.sonic2.runtime.WfzRuntimeState;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.BgTilemapUpdateMode;
import com.openggf.level.scroll.FrameScrollAccumulator;
import com.openggf.level.scroll.M68KMath;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

import java.util.Arrays;

/**
 * ROM-accurate implementation of SwScrl_WFZ (Wing Fortress Zone scroll routine).
 * Reference: s2.asm SwScrl_WFZ at ROM $C82A (lines 15609-15682)
 *
 * WFZ uses a data-driven parallax system with 5 scroll layers stored in
 * TempArray_LayerDef as 32-bit (16.16 fixed-point) values:
 *
 * Offset 0x00: Camera_BG_X_pos (static background)
 * Offset 0x04: Camera_BG_X_pos copy (Eggman's getaway ship)
 * Offset 0x08: Large cloud accumulator (+0x8000/frame, ~0.5 px/frame)
 * Offset 0x0C: Medium cloud accumulator (+0x4000/frame, ~0.25 px/frame)
 * Offset 0x10: Small cloud accumulator (+0x2000/frame, ~0.125 px/frame)
 *
 * fixBugs (s2.asm:27 `fixBugs = 0`): two conditionals live in this routine and the
 * engine implements the SHIPPED (fixBugs=0) branch of both.
 * 1. s2.asm:15654-15672 — the cloud accumulators are advanced by a bare
 *    `addi.l #$8000/$4000/$2000` with no camera term. The fixBugs=1 branch first adds
 *    `Camera_X_pos_diff` sign-extended and shifted left 5 into each accumulator, so the
 *    clouds move relative to the camera instead of against it.
 * 2. s2.asm:15804-15808 — SwScrl_WFZ_Normal_Array is short by three (lineCount,
 *    layerIndex) pairs relative to the transition array, so an exhausted segment reader
 *    would run on into SwScrl_HTZ's code bytes. fixBugs=1 appends $20/$08, $30/$0C,
 *    $30/$10. The table is read from the ROM at its shipped size (ParallaxTables
 *    SWSCRL_WFZ_NORMAL_SIZE), so the shipped layout is what the engine sees.
 *
 * Note: The cloud accumulators are bugged in the original ROM - they only
 * tally cloud speeds without subtracting camera movement. This makes clouds
 * move faster when going right and slower when going left, opposite to what
 * should happen. We reproduce this bug for accuracy.
 *
 * Two data arrays define the background layout:
 * - Normal array: used when Camera_X_pos < $2700
 * - Transition array: used when Camera_X_pos >= $2700 (ship area)
 *
 * Each array contains (lineCount, layerByteIndex) pairs. The scroll fill
 * skips entries based on Camera_BG_Y_pos to find the first visible segment,
 * then fills 224 scanlines from the active segments.
 *
 * The normal array differs from the transition array and the disassembly notes
 * that the ROM would continue into SwScrl_HTZ if the segment reader exhausted
 * it. With the ROM's masked BG Y range and 224 visible lines, the shipped
 * normal table still covers every reachable visible span, so the guard path below
 * is only a malformed-table guard.
 */
public class SwScrlWfz extends AbstractZoneScrollHandler {

    private final ParallaxTables tables;
    private final BackgroundCamera bgCamera;

    // TempArray_LayerDef simulation - 5 longword values stored as 32-bit fixed-point
    // Layer 0: static BG (Camera_BG_X_pos)
    // Layer 1: ship (copy of Camera_BG_X_pos)
    // Layer 2: large clouds (accumulator)
    // Layer 3: medium clouds (accumulator)
    // Layer 4: small clouds (accumulator)
    //
    // The three cloud accumulators are pure per-frame accumulators (ROM adds a
    // fixed amount to each every frame). They are derived from the frame counter
    // rather than free-accumulated on the update-call count so they rewind
    // correctly — see FrameScrollAccumulator. Offset 1 = increment-then-read.
    private final FrameScrollAccumulator largeCloudAccum =
            new FrameScrollAccumulator(0x8000, 1);  // Offset 0x08: +0x8000/frame
    private final FrameScrollAccumulator mediumCloudAccum =
            new FrameScrollAccumulator(0x4000, 1);  // Offset 0x0C: +0x4000/frame
    private final FrameScrollAccumulator smallCloudAccum =
            new FrameScrollAccumulator(0x2000, 1);  // Offset 0x10: +0x2000/frame

    // Byte offset to layer index mapping (byteOffset / 4)
    private static final int LAYER_STATIC_BG = 0;    // byte offset 0x00
    private static final int LAYER_SHIP = 1;          // byte offset 0x04
    private static final int LAYER_LARGE_CLOUD = 2;   // byte offset 0x08
    private static final int LAYER_MEDIUM_CLOUD = 3;  // byte offset 0x0C
    private static final int LAYER_SMALL_CLOUD = 4;   // byte offset 0x10

    // Camera X threshold for array selection
    private static final int TRANSITION_THRESHOLD = 0x2700;

    // Pre-allocated array for per-frame layer scroll values
    private final int[] layerScrollWord = new int[5];

    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    public SwScrlWfz(ParallaxTables tables, BackgroundCamera bgCamera) {
        this.tables = tables;
        this.bgCamera = bgCamera;
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {

        resetScrollTracking();
        composer.reset();

        WfzRuntimeState runtimeState = currentRuntimeState();
        int bgYPos = runtimeState != null ? runtimeState.bgVscrollFactor() : bgCamera.getBgYPos();
        int bgXPos = runtimeState != null ? runtimeState.bgXPos() : bgCamera.getBgXPos();

        // ==================== Step 1: Update VScroll factor ====================
        // move.w (Camera_BG_Y_pos).w,(Vscroll_Factor_BG).w
        composer.setVscrollFactorBG((short) bgYPos);

        // ==================== Step 2: Build TempArray_LayerDef ====================
        // move.l (Camera_BG_X_pos).w,d0  -- reads 32-bit (integer.subpixel)
        // The bgCamera stores the integer part; we treat it as the high word of a 32-bit value
        int bgXPosLong = bgXPos << 16;

        // Layer 0 and 1: Camera_BG_X_pos (static BG and ship)
        // move.l d0,(a2)+  ; offset 0x00
        // move.l d0,(a2)+  ; offset 0x04 (originally d1 = d0)

        // Layer 2-4: Cloud accumulators (bugged: only accumulate, don't subtract camera)
        // addi.l #$8000,(a2)+ / #$4000 / #$2000 — derived from the frame counter.
        int largeCloud = largeCloudAccum.valueAt(frameCounter);
        int mediumCloud = mediumCloudAccum.valueAt(frameCounter);
        int smallCloud = smallCloudAccum.valueAt(frameCounter);

        // Build the 5 scroll word values (high word of each 32-bit longword)
        // The original reads with: move.w (a2,d3.w),d0 where d3 is the byte offset
        // This reads the HIGH word (integer part) of the longword at that offset
        Arrays.fill(layerScrollWord, 0);
        layerScrollWord[LAYER_STATIC_BG] = (bgXPosLong >> 16) & 0xFFFF;
        layerScrollWord[LAYER_SHIP] = (bgXPosLong >> 16) & 0xFFFF;
        layerScrollWord[LAYER_LARGE_CLOUD] = (largeCloud >> 16) & 0xFFFF;
        layerScrollWord[LAYER_MEDIUM_CLOUD] = (mediumCloud >> 16) & 0xFFFF;
        layerScrollWord[LAYER_SMALL_CLOUD] = (smallCloud >> 16) & 0xFFFF;

        // ==================== Step 3: Select array ====================
        // cmpi.w #$2700,(Camera_X_pos).w; bhs.s .got_array
        byte[] activeArray;
        if (cameraX >= TRANSITION_THRESHOLD) {
            activeArray = tables.getWfzTransArray();
        } else {
            activeArray = tables.getWfzNormalArray();
        }

        if (activeArray == null) {
            fillFallback(horizScrollBuf, cameraX);
            return;
        }

        // ==================== Step 4: Find first visible segment ====================
        // move.w (Camera_BG_Y_pos).w,d1
        // andi.w #$7FF,d1
        int bgY = bgYPos & 0x7FF;

        // .seg_loop:
        //   move.b (a3)+,d0      ; number of lines in segment
        //   addq.w #1,a3         ; skip index byte
        //   sub.w d0,d1          ; does this segment have visible lines?
        //   bcc.s .seg_loop      ; branch if not (d1 >= 0 unsigned after sub)
        int arrayPos = 0;
        while (arrayPos + 1 < activeArray.length) {
            int lineCount = activeArray[arrayPos] & 0xFF;
            // Skip the index byte
            bgY -= lineCount;
            if (bgY < 0) {
                // This segment has visible lines
                break;
            }
            arrayPos += 2; // Move to next pair
        }

        // neg.w d1  ->  d1 = number of lines to draw in this segment
        int linesInCurrentSeg = (-bgY) & 0xFFFF;

        // move.w #224-1,d2  ->  row counter (dbf loop)
        // move.w (Camera_X_pos).w,d0; neg.w d0; swap d0
        // FG scroll value goes in the high word
        short fgScroll = M68KMath.negWord(cameraX);

        // move.b -1(a3),d3    ; fetch TempArray_LayerDef index
        // The index byte is at arrayPos+1 (we're past the lineCount byte)
        int layerByteIndex = activeArray[arrayPos + 1] & 0xFF;
        int layerIndex = layerByteIndex >> 2;  // Convert byte offset to layer index
        if (layerIndex >= layerScrollWord.length) layerIndex = 0;

        // move.w (a2,d3.w),d0   ; fetch scroll value
        // neg.w d0              ; flip sign for VDP
        short bgScroll = M68KMath.negWord(layerScrollWord[layerIndex]);

        // Advance past the current entry's lineCount byte (index byte already consumed)
        arrayPos += 2;

        // ==================== Step 5: Fill 224 scanlines ====================
        // .row_loop:
        //   move.l d0,(a1)+         ; write packed FG|BG
        //   subq.w #1,d1            ; current segment finished?
        //   bne.s .next_row         ; branch if not
        //   move.b (a3)+,d1         ; new line count
        //   move.b (a3)+,d3         ; new layer index
        //   move.w (a2,d3.w),d0     ; new scroll value
        //   neg.w d0                ; flip sign
        // .next_row:
        //   dbf d2,.row_loop
        for (int screenLine = 0; screenLine < M68KMath.VISIBLE_LINES; screenLine++) {
            composer.writePackedScrollWord(screenLine, fgScroll, bgScroll);

            linesInCurrentSeg--;
            if (linesInCurrentSeg == 0) {
                // Fetch next segment
                if (arrayPos + 1 < activeArray.length) {
                    linesInCurrentSeg = activeArray[arrayPos] & 0xFF;
                    layerByteIndex = activeArray[arrayPos + 1] & 0xFF;
                    layerIndex = layerByteIndex >> 2;
                    if (layerIndex >= layerScrollWord.length) layerIndex = 0;
                    bgScroll = M68KMath.negWord(layerScrollWord[layerIndex]);
                    arrayPos += 2;
                } else {
                    // Malformed table guard. The ROM-backed WFZ arrays cover all
                    // reachable 224-line spans selected by BG Y & $7FF.
                    linesInCurrentSeg = M68KMath.VISIBLE_LINES; // Won't run out again
                    bgScroll = M68KMath.negWord(layerScrollWord[LAYER_STATIC_BG]);
                }
            }
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    private WfzRuntimeState currentRuntimeState() {
        return GameServices.hasRuntime()
                ? GameServices.zoneRuntimeRegistry().currentAs(WfzRuntimeState.class).orElse(null)
                : null;
    }

    private int currentBgXPos() {
        WfzRuntimeState runtimeState = currentRuntimeState();
        return runtimeState != null ? runtimeState.bgXPos() : bgCamera.getBgXPos();
    }

    @Override
    public int getBgCameraX() {
        return currentBgXPos();
    }

    @Override
    public BgTilemapUpdateMode getBgTilemapUpdateMode() {
        return BgTilemapUpdateMode.PERSISTENT_NAMETABLE_64X32;
    }

    private void fillFallback(int[] horizScrollBuf, int cameraX) {
        short fgScroll = M68KMath.negWord(cameraX);
        short bgScroll = M68KMath.negWord(cameraX >> 4);
        composer.fillPackedScrollWords(0, M68KMath.VISIBLE_LINES, fgScroll, bgScroll);
        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

}
