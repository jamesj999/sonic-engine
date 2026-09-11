package com.openggf.game.sonic3k.scroll;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.runtime.CnzZoneRuntimeState;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.DeformationPlan;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import com.openggf.level.scroll.compose.ScrollValueTable;

import static com.openggf.level.scroll.M68KMath.VISIBLE_LINES;
import static com.openggf.level.scroll.M68KMath.negWord;

/**
 * Carnival Night Zone scroll handler for S3K.
 *
 * <p>Ports the shared CNZ1 deform logic used by both acts and switches to the
 * boss/background scroll path when the CNZ event layer reports a boss refresh
 * phase.
 */
public class SwScrlCnz extends AbstractZoneScrollHandler {

    /** CNZ1_BGDeformArray: four finite bands followed by the remainder band. */
    private static final int[] CNZ1_BG_DEFORM = {0x80, 0x30, 0x60, 0xC0, 0x7FFF};
    private static final DeformationPlan.ScrollValueTransform NEGATE_WORD = value -> negWord(value);

    /** Boss arena BG X offset from CNZ1_BossLevelScroll2. */
    private static final int BOSS_BG_X_OFFSET = 0x2F80;

    /** Boss arena BG Y offset from CNZ1_BossLevelScroll2. */
    private static final int BOSS_BG_Y_OFFSET = 0x100;

    private final ScrollEffectComposer composer = new ScrollEffectComposer();
    private final ScrollValueTable hScrollTable = ScrollValueTable.ofLength(5);
    private int bossBgCameraX = Integer.MIN_VALUE;
    private short foregroundVscroll;

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        resetScrollTracking();
        composer.reset();

        short fgScroll = negWord(cameraX);
        int shakeY = resolveShakeOffsetY();
        // CNZ2_ScreenEvent adds Screen_shake_offset to Camera_Y_pos_copy before
        // Plane A is rendered. Keep Plane A on the same sample exposed to the
        // camera/sprite path; CNZ1_Deform folds it into Plane B separately.
        foregroundVscroll = (short) (cameraY + shakeY);

        if (shouldUseBossScroll()) {
            writeBossScroll(horizScrollBuf, fgScroll, cameraX, cameraY, shakeY);
            return;
        }

        short bgY = cnzBgY(cameraY, shakeY);
        composer.setVscrollFactorBG(bgY);
        buildNormalHScrollTable(cameraX);
        applyDeformation(horizScrollBuf, fgScroll, bgY);
        publishNormalDeformOutputs(cameraX);
    }

    private boolean shouldUseBossScroll() {
        CnzZoneRuntimeState state = cnzRuntimeState();
        if (state == null) {
            return false;
        }

        return state.bossBackgroundScrollActive();
    }

    private void writeBossScroll(int[] horizScrollBuf,
                                 short fgScroll,
                                 int cameraX,
                                 int cameraY,
                                 int shakeY) {
        // ROM CNZ1_BossLevelScroll2 writes Camera_X_pos_BG_copy = Camera_X_pos_copy - $2F80;
        // background collision probes use that copy through Camera_X_diff (sonic3k.asm:107721-107725).
        bossBgCameraX = cameraX - BOSS_BG_X_OFFSET;
        short bgScroll = negWord(bossBgCameraX);
        CnzZoneRuntimeState state = cnzRuntimeState();
        int bossScrollOffsetY = state != null ? state.bossScrollOffsetY() : 0;
        composer.setVscrollFactorBG((short) (cameraY - BOSS_BG_Y_OFFSET + bossScrollOffsetY + shakeY));

        composer.fillPackedScrollWords(0, VISIBLE_LINES, fgScroll, bgScroll);
        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    @Override
    public int getBgCameraX() {
        if (!shouldUseBossScroll()) {
            return Integer.MIN_VALUE;
        }
        if (GameServices.gameStateOrNull() != null
                && GameServices.gameState().isBackgroundCollisionFlag()) {
            Camera camera = GameServices.cameraOrNull();
            if (camera != null) {
                return camera.getX() - BOSS_BG_X_OFFSET;
            }
        }
        return bossBgCameraX;
    }

    /**
     * CNZ1_Deform BG Y is approximately 13/128 of camera Y with screen shake
     * folded in.
     */
    private short cnzBgY(int cameraY, int shakeY) {
        // The engine passes raw Camera_Y_pos. Native Camera_Y_pos_copy already
        // contains shake here, then CNZ1_Deform subtracts it before scaling.
        return (short) (((long) cameraY * 13 >> 7) + shakeY);
    }

    /**
     * Publishes the CNZ normal-deform outputs that later tile animation reads.
     *
     * <p>ROM-side CNZ derives the phase source from {@code Events_bg+$10} and
     * the BG X copy from {@code Camera_X_pos_BG_copy}. The current engine keeps
     * the same values in the zone runtime state so animated tiles can consume
     * them without reopening the event object boundary.
     */
    private void publishNormalDeformOutputs(int cameraX) {
        CnzZoneRuntimeState state = cnzRuntimeState();
        if (state == null) {
            return;
        }

        state.publishDeformOutputs(cnzPhaseSource(cameraX), cnzPublishedBgCameraX(cameraX));
    }

    /**
     * ROM-equivalent CNZ phase source used by AnimateTiles_CNZ.
     *
     * <p>This is the 5/16 camera-X fraction the disassembly reads from
     * {@code Events_bg+$10} when deriving the animated tile phase.
     */
    private int cnzPhaseSource(int cameraX) {
        // CNZ1_Deform extracts this after subtracting three 1/16 steps from
        // its 16.16 half-speed accumulator, so the 5/16 result rounds once.
        return (short) (((long) (short) cameraX * 5) >> 4);
    }

    /**
     * ROM-equivalent published BG camera X copy.
     *
     * <p>CNZ keeps this as the 7/16 camera-X fraction that later animation
     * logic treats as {@code Camera_X_pos_BG_copy}.
     */
    private int cnzPublishedBgCameraX(int cameraX) {
        // As above, preserve the ROM's single 16.16 rounding operation.
        return (short) (((long) (short) cameraX * 7) >> 4);
    }

    /**
     * Builds the five CNZ1_Deform HScroll_table words in their ROM order:
     * 1/32, 1/8, 1/4, 7/16, and 1/2 camera speed.
     */
    private void buildNormalHScrollTable(int cameraX) {
        int d0 = ((short) cameraX) << 16;
        d0 >>= 1;
        int d1 = d0 >> 3;

        hScrollTable.set(4, (short) (d0 >> 16));
        d0 -= d1;
        hScrollTable.set(3, (short) (d0 >> 16));
        d0 -= d1;
        d0 -= d1;
        d0 -= d1;
        hScrollTable.set(2, (short) (d0 >> 16));
        d0 -= d1;
        d0 -= d1;
        hScrollTable.set(1, (short) (d0 >> 16));
        d0 -= d1;
        d1 >>= 1;
        d0 -= d1;
        hScrollTable.set(0, (short) (d0 >> 16));
    }

    private void applyDeformation(int[] horizScrollBuf, short fgScroll, short bgY) {
        DeformationPlan.applyTableBands(
                composer,
                bgY,
                fgScroll,
                hScrollTable,
                CNZ1_BG_DEFORM,
                0,
                NEGATE_WORD);
        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    private CnzZoneRuntimeState cnzRuntimeState() {
        if (!GameServices.hasRuntime()) {
            return null;
        }
        return GameServices.zoneRuntimeRegistry().currentAs(CnzZoneRuntimeState.class).orElse(null);
    }

    /**
     * CNZ vertical screen-shake offset (ROM {@code Screen_shake_flag}). Sourced
     * from the CNZ event layer so it is in sync for the BG/FG scroll fold-in here
     * and the {@code ParallaxManager} -> {@code Camera} propagation (which moves
     * the foreground and sprites). The CNZ event {@code update()} ticks the shake
     * before rendering each frame, so reading it twice in one frame is stable.
     */
    private int resolveShakeOffsetY() {
        CnzZoneRuntimeState state = cnzRuntimeState();
        return state != null ? state.screenShakeOffsetY() : 0;
    }

    @Override
    public int getShakeOffsetY() {
        return resolveShakeOffsetY();
    }

    @Override
    public short getVscrollFactorFG() {
        return foregroundVscroll;
    }
}
