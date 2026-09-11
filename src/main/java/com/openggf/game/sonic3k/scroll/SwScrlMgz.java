package com.openggf.game.sonic3k.scroll;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.runtime.MgzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.DeformationPlan;
import com.openggf.level.scroll.compose.ScatterFillPlan;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import com.openggf.level.scroll.compose.ScrollValueTable;

import static com.openggf.level.scroll.M68KMath.negWord;

/**
 * Marble Garden Zone (MGZ) scroll handler for Sonic 3K.
 *
 * Ports MGZ1_Deform / MGZ2_BGDeform (normal path) from the S3K disassembly to
 * produce real per-line parallax rather than a flat fallback ratio.
 */
public class SwScrlMgz extends AbstractZoneScrollHandler {
    // Screen shake support (ROM: Screen_shake_flag, used by Tunnelbot / MGZ Miniboss).
    // Applied to FG and BG VScroll, while getShakeOffsetY() propagates the same
    // vertical delta to sprites. MGZ2_BGDeform compensates BG parallax math so
    // the shake lands 1:1 on the plane instead of being scaled away.
    private int screenShakeOffset;
    private int pendingScreenShakeOffset;
    /**
     * ROM {@code Screen_shake_offset}. {@code ShakeScreen_Setup} runs at the
     * tail of the zone's background event ({@code MGZ1BGE_Normal},
     * docs/skdisasm/sonic3k.asm:106308; routine at :104188-104210), while
     * {@code MGZ1_ScreenEvent}/{@code MGZ2_ScreenEvent} add the offset into
     * {@code Camera_Y_pos_copy} at the *start* of the same
     * {@code ScreenEvents} pass (sonic3k.asm:102232-102253, :106257-106260,
     * :106390-106392). The value {@code Render_Sprites} sees on a frame is
     * therefore the one this routine computed on the previous frame, which is
     * what {@link #screenShakeOffset} publishes; this field holds the freshly
     * computed sample waiting for the next frame.
     */
    private int nextScreenShakeOffset;
    private short vscrollFactorFG;

    // ROM: MGZ2_BGDeform (Lockon S3/Screen Events.asm:1090-1145) switches the BG
    // scroll formula per Events_bg+$00 state. State 0 uses 3/16 parallax (cloud
    // layer); state 8 (Sonic rise) locks the BG 1:1 to the FG at a fixed
    // ROM-defined offset so the pre-placed terrain rows in the BG layout line
    // up with the pit and become standable via Background_collision_flag.
    // State C shifts the parallax origin by $500.
    private static final int BG_RISE_NORMAL_STATE = 0;
    private static final int BG_RISE_SONIC_STATE = 8;
    private static final int BG_RISE_AFTER_MOVE_STATE = 0xC;
    /** ROM: loc_23D1EA — d1 = $8F0, d2 = $3200 for Sonic rise. */
    private static final int MGZ2_SONIC_RISE_Y_BASE = 0x8F0;
    private static final int MGZ2_SONIC_RISE_X_BASE = 0x3200;
    private static final int MGZ2_AFTER_MOVE_Y_BASE = 0x500;

    private int bgRiseRoutine;
    private int bgRiseOffset;
    private int bossBgScrollOffset = Integer.MIN_VALUE;
    /**
     * Cached BG camera X for {@link #getBgCameraX()}. {@link Integer#MIN_VALUE}
     * means "no override" — the dual-path collision uses the FG cameraX as the
     * BG reference, which is the correct default for normal MGZ play. During
     * state 8 this is set to {@code cameraX - $3200} so ground collision probes
     * land inside the 24-col BG layout.
     */
    private int lastBgCameraX = Integer.MIN_VALUE;

    /**
     * ROM {@code ScreenShakeArray2} (docs/skdisasm/sonic3k.asm:104233-104236) —
     * the 64-entry table {@code ShakeScreen_Setup} indexes with
     * {@code Level_frame_counter & $3F} while {@code Screen_shake_flag} is
     * negative (sonic3k.asm:104200-104209).
     */
    private static final int[] SCREEN_SHAKE_CONTINUOUS = {
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3
    };

    public void setScreenShakeOffset(int offset) {
        if (offset > pendingScreenShakeOffset) {
            pendingScreenShakeOffset = offset;
        }
    }

    public void setBgRiseState(int routine, int offset) {
        if (this.bgRiseRoutine != BG_RISE_AFTER_MOVE_STATE && routine == BG_RISE_AFTER_MOVE_STATE) {
            mgz2CloudsFrozen = true;
        }
        this.bgRiseRoutine = routine;
        this.bgRiseOffset = offset;
        MgzZoneRuntimeState runtimeState = currentRuntimeState();
        if (runtimeState != null) {
            runtimeState.publishBgRiseState(routine, offset);
        }
        primeBgCollisionStateFromCurrentCamera();
    }

    /**
     * Syncs the local cache fields from the events-class state (the canonical
     * {@code Events_bg+$00 / +$02} source) without running a full parallax
     * update. Called by {@link com.openggf.game.sonic3k.events.Sonic3kMGZEvents}
     * after its state machine mutates the routine so collision probes that read
     * {@link #getBgCameraX()} and {@link #getVscrollFactorBG()} between event
     * tick and the next render see the post-transition state.
     */
    public void syncBgRiseFromEvents() {
        MgzZoneRuntimeState runtimeState = currentRuntimeState();
        if (runtimeState == null) {
            return;
        }
        int newRoutine = runtimeState.bgRiseRoutine();
        if (this.bgRiseRoutine != BG_RISE_AFTER_MOVE_STATE && newRoutine == BG_RISE_AFTER_MOVE_STATE) {
            mgz2CloudsFrozen = true;
        }
        this.bgRiseRoutine = newRoutine;
        this.bgRiseOffset = runtimeState.bgRiseOffset();
        primeBgCollisionStateFromCurrentCamera();
    }

    /**
     * ROM: MGZ2SE_MoveBG advances Events_bg+$0C after the boss floor collapse
     * and MGZ2_BGDeform substitutes it for Camera_X_pos_copy only while
     * building HScroll_table (loc_23D24C). Camera_X_pos_BG_copy remains owned
     * by the normal BG deformation path, so this must not relocate the
     * background tilemap or collision window.
     */
    public void setBossBgScrollOffset(int offset) {
        bossBgScrollOffset = offset & 0xFFFF;
    }

    @Override
    public int getShakeOffsetY() {
        return screenShakeOffset;
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
    public void init(int actId, int cameraX, int cameraY) {
        screenShakeOffset = 0;
        pendingScreenShakeOffset = 0;
        nextScreenShakeOffset = 0;
        vscrollFactorFG = 0;
        bgRiseRoutine = BG_RISE_NORMAL_STATE;
        bgRiseOffset = 0;
        bossBgScrollOffset = Integer.MIN_VALUE;
        lastBgCameraX = actId == 0 ? Integer.MIN_VALUE : 0;
        vscrollFactorBG = actId == 0 ? 0 : (short) computeMgz2BgY(cameraY);
        mgz1CloudAccumulator.reset();
        mgz2CloudAccumulator.reset();
        mgz2CloudsFrozen = false;
        lastActId = -1;
        MgzZoneRuntimeState runtimeState = currentRuntimeState();
        if (runtimeState != null && runtimeState.actIndex() == actId) {
            bgRiseRoutine = runtimeState.bgRiseRoutine();
            bgRiseOffset = runtimeState.bgRiseOffset();
            bossBgScrollOffset = runtimeState.hasBossBgScrollOffset()
                    ? runtimeState.bossBgScrollOffset()
                    : Integer.MIN_VALUE;
            if (bgRiseRoutine == BG_RISE_SONIC_STATE) {
                lastBgCameraX = ((short) cameraX) - MGZ2_SONIC_RISE_X_BASE;
                vscrollFactorBG = (short) (((short) cameraY) - MGZ2_SONIC_RISE_Y_BASE + bgRiseOffset);
            } else if (bgRiseRoutine == BG_RISE_AFTER_MOVE_STATE) {
                mgz2CloudsFrozen = true;
                lastBgCameraX = 0;
                vscrollFactorBG = (short) computeMgz2BgY(cameraY - MGZ2_AFTER_MOVE_Y_BASE);
            }
        }
    }

    private static final int HSCROLL_WORD_COUNT = 32;

    // MGZ1_BGDeformArray
    private static final int[] MGZ1_BG_DEFORM = {
            0x10, 0x04, 0x04, 0x08, 0x08, 0x08, 0x0D, 0x13, 0x08, 0x08, 0x08, 0x08, 0x18, 0x7FFF
    };

    // MGZ2_BGDeformArray
    private static final int[] MGZ2_BG_DEFORM = {
            0x10, 0x10, 0x10, 0x10, 0x10, 0x18, 0x08, 0x10, 0x08, 0x08, 0x10, 0x08,
            0x08, 0x08, 0x05, 0x2B, 0x0C, 0x06, 0x06, 0x08, 0x08, 0x18, 0xD8, 0x7FFF
    };

    // MGZ2_BGDeformIndex
    private static final int[] MGZ2_BG_DEFORM_INDEX = {
            0x1C, 0x18, 0x1A, 0x0C, 0x06, 0x14, 0x02, 0x10, 0x16, 0x12, 0x0A, 0x00, 0x08, 0x04, 0x0E
    };

    // MGZ2_BGDeformOffset
    private static final int[] MGZ2_BG_DEFORM_OFFSET = {
            -5, -8, 9, 10, 2, -12, 3, 16, -1, 13, -15, 6, -11, -4, 14,
            -8, 16, 8, 0, -8, 16, 8, 0
    };

    private static final ScatterFillPlan MGZ2_SCATTER_FILL = new ScatterFillPlan(
            18, 16, 17, 10, 7, 14, 5, 12, 15, 13, 9, 4, 8, 6, 11
    );
    private static final DeformationPlan.ScrollValueTransform NEGATE_WORD = value -> negWord(value);

    // ROM accumulators that live in HScroll_table longwords and persist frame-to-frame.
    private final ScrollEffectComposer composer = new ScrollEffectComposer();
    private final ScrollValueTable mgz1HScrollTable = ScrollValueTable.ofLength(HSCROLL_WORD_COUNT);
    private final ScrollValueTable mgz2HScrollTable = ScrollValueTable.ofLength(HSCROLL_WORD_COUNT);
    private final ScrollValueTable mgz2ScatterSource = ScrollValueTable.ofLength(MGZ2_BG_DEFORM_INDEX.length);
    // Cloud auto-scroll accumulators, derived from the frame counter (not the
    // update-call count) so they rewind correctly. MGZ1 advances every act-1
    // frame (offset 0, read-then-increment). MGZ2 advances only while the clouds
    // auto-move (offset 1, increment-then-read); on the BG-rise "after move"
    // freeze the ROM zeroes it, so the frozen value is 0 (handled at the call
    // site), not a held last value.
    private final com.openggf.level.scroll.FrameScrollAccumulator mgz1CloudAccumulator =
            new com.openggf.level.scroll.FrameScrollAccumulator(0x500, 0);
    private final com.openggf.level.scroll.FrameScrollAccumulator mgz2CloudAccumulator =
            new com.openggf.level.scroll.FrameScrollAccumulator(0x800, 1);

    private int lastActId = -1;
    private boolean mgz2CloudsFrozen;

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        if (frameCounter == 0 || actId != lastActId) {
            resetActState(actId);
        }
        MgzZoneRuntimeState runtimeState = currentRuntimeState();
        if (runtimeState != null) {
            bgRiseRoutine = runtimeState.bgRiseRoutine();
            bgRiseOffset = runtimeState.bgRiseOffset();
            bossBgScrollOffset = runtimeState.hasBossBgScrollOffset()
                    ? runtimeState.bossBgScrollOffset()
                    : Integer.MIN_VALUE;
        }

        composer.reset();
        // ROM ShakeScreen_Setup (sonic3k.asm:104188-104210): with
        // Screen_shake_flag negative (continuous — the only mode MGZ's
        // Tunnelbot/Robotnik raise, sonic3k.asm:184784/:184886/:184907) the
        // offset is ScreenShakeArray2[Level_frame_counter & $3F]. It is a
        // level-clock lookup owned by the zone's background event, not a
        // per-object one, so the requesters only raise the flag.
        int computedShakeOffset;
        if (runtimeState != null) {
            computedShakeOffset = runtimeState.consumeContinuousScreenShakeRequest()
                    ? SCREEN_SHAKE_CONTINUOUS[frameCounter & (SCREEN_SHAKE_CONTINUOUS.length - 1)]
                    : runtimeState.consumeScreenShakeOffset();
        } else {
            computedShakeOffset = pendingScreenShakeOffset;
            pendingScreenShakeOffset = 0;
        }
        // Publish the previous frame's sample; see nextScreenShakeOffset.
        screenShakeOffset = nextScreenShakeOffset;
        nextScreenShakeOffset = computedShakeOffset;
        short fgScroll = negWord(cameraX);

        if (actId == 0) {
            bossBgScrollOffset = Integer.MIN_VALUE;
            lastBgCameraX = Integer.MIN_VALUE;
            composer.setVscrollFactorBG((short) 0);
            buildMgz1HScrollTable(cameraX, frameCounter, mgz1HScrollTable);
            DeformationPlan.applyTableBands(
                    composer,
                    0,
                    fgScroll,
                    mgz1HScrollTable,
                    MGZ1_BG_DEFORM,
                    0,
                    NEGATE_WORD);
        } else if (bgRiseRoutine == BG_RISE_SONIC_STATE) {
            // State 8 (Sonic rise): MGZ2_BGDeform still runs the cloud/scatter
            // parallax builder, but it seeds the final deform slot with the
            // 1:1 terrain lock value (Camera_X_pos_BG_copy = cameraX - $3200).
            // That keeps the clouds drifting while the terrain band scrolls
            // 1:1 with the lift.
            int parallaxCameraX = getMgz2ParallaxCameraX(cameraX);
            int bgY = ((short) cameraY) - MGZ2_SONIC_RISE_Y_BASE + bgRiseOffset;
            int bgScrollBaseX = ((short) cameraX) - MGZ2_SONIC_RISE_X_BASE;
            // Expose to dual-path collision so probes at world X≈$3800
            // translate into the BG layout's populated 0..23 range.
            lastBgCameraX = bgScrollBaseX;
            composer.setVscrollFactorBG((short) bgY);
            buildMgz2StateEightHScrollTable(parallaxCameraX, bgScrollBaseX, shouldAutoMoveMgz2Clouds(),
                    frameCounter, mgz2HScrollTable, mgz2ScatterSource);
            DeformationPlan.applyTableBands(
                    composer,
                    bgY,
                    fgScroll,
                    mgz2HScrollTable,
                    MGZ2_BG_DEFORM,
                    4,
                    NEGATE_WORD);
        } else {
            // State 0 (normal MGZ2 play) uses the drifting cloud parallax.
            // State C (after-move) freezes cloud movement and shifts the
            // parallax origin by $500, matching MGZ2_BGDeform's d1 preload.
            int bgY = bgRiseRoutine == BG_RISE_AFTER_MOVE_STATE
                    ? computeMgz2BgY(cameraY - MGZ2_AFTER_MOVE_Y_BASE)
                    : computeMgz2BgY(cameraY);
            // loc_23D220 clears Camera_X_pos_BG_copy in both normal state 0
            // and after-move state $C. The foreground/boss cursor still feeds
            // HScroll_table separately through getMgz2ParallaxCameraX().
            lastBgCameraX = 0;
            composer.setVscrollFactorBG((short) bgY);
            buildMgz2HScrollTable(getMgz2ParallaxCameraX(cameraX), shouldAutoMoveMgz2Clouds(),
                    frameCounter, mgz2HScrollTable, mgz2ScatterSource);
            DeformationPlan.applyTableBands(
                    composer,
                    bgY,
                    fgScroll,
                    mgz2HScrollTable,
                    MGZ2_BG_DEFORM,
                    4,
                    NEGATE_WORD);
        }

        // Screen shake: apply the same camera rumble offset to both planes so the
        // BG cloud/floor strip tracks the shaken viewport instead of staying fixed.
        if (screenShakeOffset != 0) {
            composer.setVscrollFactorBG((short) (composer.getVscrollFactorBG() + screenShakeOffset));
            composer.setVscrollFactorFG((short) (cameraY + screenShakeOffset));
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
        vscrollFactorBG = composer.getVscrollFactorBG();
        vscrollFactorFG = composer.getVscrollFactorFG();
    }

    private void resetActState(int actId) {
        if (actId == 0) {
            mgz1CloudAccumulator.reset();
        } else {
            mgz2CloudAccumulator.reset();
            mgz2CloudsFrozen = bgRiseRoutine == BG_RISE_AFTER_MOVE_STATE;
        }
        lastActId = actId;
    }

    private MgzZoneRuntimeState currentRuntimeState() {
        if (!GameServices.hasRuntime()) {
            return null;
        }
        return S3kRuntimeStates.currentMgz(GameServices.zoneRuntimeRegistry()).orElse(null);
    }

    private int getMgz2ParallaxCameraX(int cameraX) {
        return bossBgScrollOffset == Integer.MIN_VALUE ? cameraX : bossBgScrollOffset;
    }

    private boolean shouldAutoMoveMgz2Clouds() {
        return !mgz2CloudsFrozen && bgRiseRoutine != BG_RISE_AFTER_MOVE_STATE;
    }

    /**
     * Port of MGZ1_Deform's HScroll_table generation.
     */
    private void buildMgz1HScrollTable(int cameraX, int frameCounter, ScrollValueTable table) {
        table.clear();
        int d0 = ((short) cameraX) << 16;
        d0 >>= 2;

        int d1 = d0 >> 4;

        int a1 = 14; // HScroll_table+$01C word index
        for (int i = 0; i < 9; i++) {
            table.set(--a1, (short) (d0 >> 16));
            d0 -= d1;
        }

        int d2 = mgz1CloudAccumulator.valueAt(frameCounter);

        d0 >>= 1;
        a1 = 0;
        for (int i = 0; i < 5; i++) {
            d0 += d2;
            d2 += 0x500;
            table.set(a1++, (short) (d0 >> 16));
            d0 += d1;
        }

        // move.w -2(a1),d0 / move.w -4(a1),-2(a1) / move.w d0,-4(a1)
        short swap = table.get(9);
        table.set(9, table.get(8));
        table.set(8, swap);
    }

    /**
     * Port of MGZ2_BGDeform normal-path HScroll_table generation.
     */
    private void buildMgz2HScrollTable(int cameraX,
                                       boolean autoMoveClouds,
                                       int frameCounter,
                                       ScrollValueTable table,
                                       ScrollValueTable scatterSource) {
        table.clear();
        scatterSource.clear();
        int d0 = ((short) cameraX) << 16;
        d0 >>= 1;

        int d1 = d0 >> 3;
        int d2 = d1 >> 2;

        int a1 = 27; // HScroll_table+$036 word index
        for (int i = 0; i < 8; i++) {
            table.set(--a1, (short) (d0 >> 16));
            d0 -= d1;
        }

        // While auto-moving, the accumulator advances with the frame counter;
        // on the BG-rise freeze the ROM zeroes it, so the frozen value is 0.
        int cloudAcc = autoMoveClouds ? mgz2CloudAccumulator.valueAt(frameCounter) : 0;
        int d0Cloud = d2;
        int d2Step = d2 >> 1;
        for (int i = 0; i < MGZ2_BG_DEFORM_INDEX.length; i++) {
            d0Cloud += cloudAcc;
            scatterSource.set(i, (short) (d0Cloud >> 16));
            d0Cloud += d2Step;
        }
        MGZ2_SCATTER_FILL.apply(scatterSource, table);

        for (int i = 0; i < MGZ2_BG_DEFORM_OFFSET.length; i++) {
            int idx = 4 + i;
            if (idx >= 0 && idx < table.size()) {
                table.set(idx, (short) (table.get(idx) + MGZ2_BG_DEFORM_OFFSET[i]));
            }
        }
    }

    /**
     * ROM: MGZ2_BGDeform state 8 still executes the cloud/scatter fill from
     * {@code loc_23D24C..loc_23D2B4}, but the state-8 prelude seeds
     * {@code HScroll_table+$036} with {@code Camera_X_pos_BG_copy}. That final
     * slot feeds the locked terrain band while the earlier slots retain the
     * normal cloud parallax.
     */
    private void buildMgz2StateEightHScrollTable(int cameraX,
                                                 int bgScrollBaseX,
                                                 boolean autoMoveClouds,
                                                 int frameCounter,
                                                 ScrollValueTable table,
                                                 ScrollValueTable scatterSource) {
        buildMgz2HScrollTable(cameraX, autoMoveClouds, frameCounter, table, scatterSource);
        table.set(27, (short) bgScrollBaseX);
    }

    private int computeMgz2BgY(int cameraY) {
        int d0 = ((short) cameraY) << 16;
        d0 >>= 4;
        int d1 = d0;
        d0 += d0;
        d0 += d1;
        return (short) (d0 >> 16);
    }

    private void primeBgCollisionStateFromCurrentCamera() {
        var camera = GameServices.cameraOrNull();
        if (camera == null) {
            return;
        }
        if (bgRiseRoutine == BG_RISE_SONIC_STATE) {
            lastBgCameraX = ((short) camera.getX()) - MGZ2_SONIC_RISE_X_BASE;
            vscrollFactorBG = (short) ((((short) camera.getY()) - MGZ2_SONIC_RISE_Y_BASE) + bgRiseOffset);
            return;
        }
        lastBgCameraX = 0;
        vscrollFactorBG = (short) (bgRiseRoutine == BG_RISE_AFTER_MOVE_STATE
                ? computeMgz2BgY(camera.getY() - MGZ2_AFTER_MOVE_Y_BASE)
                : computeMgz2BgY(camera.getY()));
    }
}
