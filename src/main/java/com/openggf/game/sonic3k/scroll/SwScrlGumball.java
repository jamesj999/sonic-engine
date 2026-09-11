package com.openggf.game.sonic3k.scroll;

import com.openggf.game.sonic3k.objects.GumballMachineObjectInstance;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

import static com.openggf.level.scroll.M68KMath.*;

/**
 * Gumball Machine bonus stage scroll handler.
 *
 * <p>Implements per-column FG VSCROLL so the machine body tiles drift with
 * the gumball machine object while the surrounding walls stay locked to
 * the camera.
 *
 * <p>ROM reference: {@code Gumball_SetUpVScroll} / {@code Gumball_VScroll}
 * (s3.asm lines 76130-76147) runs every frame in {@code Gumball_ScreenEvent}:
 * <pre>
 *   d0 = Camera_Y_pos_copy
 *   d1 = machineY_saved - $C8 - cameraY
 *   d1 = -d1                           ; = cameraY + $C8 - machineY_saved
 *   HScroll_table+$0  = d0   (strip 0 FG: camera Y)
 *   HScroll_table+$4  = d1   (strip 1 FG: machine-tracked Y)
 *   HScroll_table+$8  = d0   (strip 2 FG: camera Y)
 *   HScroll_table+$C  = d0   (strip 3 FG: camera Y)
 *   HScroll_table+$10 = d0   (strip 4 FG: camera Y)
 * </pre>
 *
 * <p>The scatter array {@code Gumball_VScrollArray = {$C0, $80, $7FFF}} (192px,
 * 128px, terminator) is combined with the 5 VScroll values via
 * {@code DrawTilesVDeform} so that the machine-tracked strip coincides with the
 * body of the machine at world X around $100.
 *
 * <p>This implementation approximates the ROM behavior column-by-column: each
 * 16-pixel column whose world X falls inside the machine body range uses the
 * machine-tracked VScroll; every other column uses {@code cameraY}. This
 * mirrors the visual effect of the ROM's strip scatter without having to
 * replicate the VDP VSRAM scatter layout.
 */
public class SwScrlGumball extends AbstractZoneScrollHandler {

    /** ROM H40 display: 40 cells wide, column pairs of 16px each (20 entries). */
    private static final int COLUMN_COUNT = 20;

    /** ROM Gumball_SetUpVScroll: d1 = cameraY + $C8 - machineY_saved. */
    private static final int Y_BASE_OFFSET = 0xC8;

    /**
     * ROM Gumball_VScroll writes machineTrackedY only to HScroll_table offset $4
     * (= VSRAM entry for Plane A column-pair 1 = screen pixels 32-63).
     * DrawTilesVDeform with scatter array {$C0, $80, $7FFF} then distributes
     * VSRAM across 6+4 column-pairs, but only column-pair 1 has the machine
     * value — all others get cameraY.
     *
     * In the engine's 20-column system (16px each), column-pair 1 = columns 2-3.
     * Rather than hardcoding column indices, we use the world X range that
     * maps to those columns. With camera at ~0x60, column 2 starts at
     * 0x60 + 32 = 0x80, column 3 ends at 0x60 + 63 = 0x9F.
     * But the machine body chunks span 2 chunks (256px) centered at 0x100.
     * The actual visible machine tiles = chunks at X=[0x80, 0x180).
     *
     * The ROM uses a SINGLE 32px column-pair for the per-strip VSCROLL.
     * This works because DrawTilesVDeform redraws tiles at shifted positions
     * within each strip. Our shader-based approach applies VSCROLL to the
     * entire visible column, so we need to apply it to ALL columns that
     * overlap the machine body — otherwise only 32px of the body moves.
     */
    // Machine body tiles: 4-chunk-wide layout, chunks 0+3 are walls, chunks 1+2
    // are the machine body. Each chunk is 128px, so machine body spans world X
    // [0x80, 0x180). Apply machine-tracked VSCROLL to any screen column whose
    // center falls within this range.
    // Tuned from chunk boundaries [0x80, 0x180) by narrowing 4 columns (64px)
    // on each side to match the visible machine body tile area.
    private static final int MACHINE_BODY_MIN_X = 0xC0;  // 0x80 + 0x40
    private static final int MACHINE_BODY_MAX_X = 0x140; // 0x180 - 0x40

    private final short[] fgColumns = new short[COLUMN_COUNT];
    private boolean fgColumnsActive;
    private short vscrollFactorFG;

    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        resetScrollTracking();
        composer.reset();

        // Standard flat deformation: FG tied to camera, BG at 1/2 vertical speed
        // (ROM Gumball_Deform at s3.asm:76172 sets Camera_Y_pos_BG_copy = cameraY/2).
        short fgScroll = negWord(cameraX);
        short bgScroll = negWord(cameraX);
        composer.setVscrollFactorBG(asrWord(cameraY, 1));
        vscrollFactorFG = (short) cameraY;

        composer.fillPackedScrollWords(0, VISIBLE_LINES, fgScroll, bgScroll);
        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();

        // ROM Gumball_SetUpVScroll: compute machine-tracked strip Y value.
        // d1 = cameraY + $C8 - machineY_saved.  (Use the drifted currentY so
        // the body tiles follow the machine as it sinks when gumballs drop.)
        LevelManager level = GameServices.levelOrNull();
        GumballMachineObjectInstance machine = level == null
                ? null
                : GumballMachineObjectInstance.current(level.getObjectManager());
        if (machine == null) {
            // Fall back to flat FG VScroll when no machine is present.
            fgColumnsActive = false;
            return;
        }

        // The shader adds columnVScroll as a DELTA on top of WorldOffsetY (which
        // is already cameraY). So non-machine columns must be 0 (no extra offset).
        // Machine-tracked columns use a DELTA: the difference between machine-tracked
        // scroll and normal camera scroll.
        //
        // ROM: d1 = cameraY + $C8 - machineY (absolute VSCROLL for the strip).
        // As a delta from cameraY: delta = d1 - cameraY = $C8 - machineY.
        // When machine is at savedY (no drift): delta = $C8 - savedY.
        // As machine drifts DOWN (machineY increases): delta decreases (tiles scroll up
        // relative to camera) — which is correct: the tiles "stay with" the machine
        // while the camera pans normally.
        int machineY = machine.getCurrentY();
        short machineDelta = (short) (Y_BASE_OFFSET - machineY);

        // Apply machine-tracked delta to columns whose CENTER overlaps the
        // machine body tile range. Use column center (worldX + 8) for clean
        // boundary alignment with 128px chunks.
        for (int col = 0; col < COLUMN_COUNT; col++) {
            int colCenterX = cameraX + col * 16 + 8;
            if (colCenterX >= MACHINE_BODY_MIN_X && colCenterX < MACHINE_BODY_MAX_X) {
                fgColumns[col] = machineDelta;
            } else {
                fgColumns[col] = 0;
            }
        }
        fgColumnsActive = true;
    }

    @Override
    public short[] getPerColumnVScrollFG() {
        return fgColumnsActive ? fgColumns : null;
    }

    @Override
    public short getVscrollFactorFG() {
        return vscrollFactorFG;
    }
}
