package com.openggf.game.sonic3k.scroll;

import com.openggf.game.GameServices;
import com.openggf.level.scroll.compose.DeformationPlan;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import com.openggf.level.scroll.compose.ScrollValueTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import static com.openggf.level.scroll.M68KMath.negWord;

/**
 * Hidden Palace Zone (sanctuary) scroll handler for S3K.
 *
 * <p>Ports {@code HPZ_BackgroundInit} / {@code HPZ_BackgroundEvent} and their two
 * scroll-parameter subroutines {@code sub_5A32C} / {@code sub_5A334}
 * (sonic3k.asm:120069-120280). Both subroutines converge on {@code loc_5A33C},
 * which derives:
 * <ul>
 *   <li>{@code Camera_Y_pos_BG_copy} = 3/16 of (camera Y - shake + Y offset), with
 *       the shake added back afterwards;</li>
 *   <li>{@code HScroll_table} words 0 and 4 = 3/16 of (camera X - X offset);</li>
 *   <li>{@code HScroll_table} words 13 down to 5 = a descending gradient from 3/4
 *       to 1/4 of (camera X - X offset), stepping by 1/16 per band, with word 2
 *       mirroring word 13.</li>
 * </ul>
 *
 * <p>The offsets switch on the ROM's {@code Player_1+x_pos} test against
 * {@code $EC0}: the Master Emerald chamber to the left of the seam uses
 * ({@code $348}, {@code $000}), and the special-stage ring hall to the right uses
 * ({@code $E00}, {@code $700}). {@code HPZ_BackgroundEvent}'s routine index only
 * sequences the multi-frame plane redraw around that seam; the scroll parameters
 * it selects are the same either side, so this handler consumes the seam
 * predicate directly.
 *
 * <p>{@code ApplyDeformation} is entered with {@code a5 = HScroll_table+$008}
 * (word 4), so the topmost band scrolls at the full 3/16 rate and the bands below
 * it walk the 1/4-to-3/4 gradient.
 */
public class SwScrlHpz extends SwScrlS3kDefault {

    /** {@code HPZ_BGDeformArray}: nine finite bands followed by the remainder band. */
    private static final int[] HPZ_BG_DEFORM =
            {0x198, 0x008, 0x004, 0x004, 0x008, 0x008, 0x010, 0x008, 0x030, 0x7FFF};

    /** {@code ApplyDeformation} is entered with {@code a5 = HScroll_table+$008}. */
    private static final int DEFORM_TABLE_START_INDEX = 4;

    /** ROM {@code cmpi.w #$EC0,(Player_1+x_pos).w} seam between the two BG framings. */
    private static final int BACKGROUND_SEAM_X = 0xEC0;

    /** {@code sub_5A32C}: Master Emerald chamber framing. */
    private static final int NEAR_X_OFFSET = 0x348;
    private static final int NEAR_Y_OFFSET = 0x000;

    /** {@code sub_5A334}: special-stage ring hall framing. */
    private static final int FAR_X_OFFSET = 0x0E00;
    private static final int FAR_Y_OFFSET = 0x0700;

    /** Number of gradient words {@code loc_5A388} writes, ROM {@code moveq #9-1,d1}. */
    private static final int GRADIENT_WORD_COUNT = 9;

    /** {@code HScroll_table+$01A} is the highest word the gradient loop writes. */
    private static final int GRADIENT_TOP_INDEX = 13;

    /** {@code HScroll_table+$004} mirrors the first gradient word. */
    private static final int GRADIENT_MIRROR_INDEX = 2;

    private static final DeformationPlan.ScrollValueTransform NEGATE_WORD = value -> negWord(value);

    private final ScrollEffectComposer composer = new ScrollEffectComposer();
    private final ScrollValueTable hScrollTable = ScrollValueTable.ofLength(GRADIENT_TOP_INDEX + 1);

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        // ScreenEvents assigns HPZS_BackgroundEvent to $1701; $1700 is
        // DEZ3. Keep the existing fallback for the paired boss act.
        if (actId == 0) {
            super.update(horizScrollBuf, cameraX, cameraY, frameCounter, actId);
            return;
        }
        resetScrollTracking();
        composer.reset();

        short fgScroll = negWord(cameraX);
        boolean farFraming = isFarFraming();
        int xOffset = farFraming ? FAR_X_OFFSET : NEAR_X_OFFSET;
        int yOffset = farFraming ? FAR_Y_OFFSET : NEAR_Y_OFFSET;

        short bgY = backgroundY(cameraY, yOffset);
        composer.setVscrollFactorBG(bgY);
        buildHScrollTable(cameraX, xOffset);

        DeformationPlan.applyTableBands(
                composer,
                bgY,
                fgScroll,
                hScrollTable,
                HPZ_BG_DEFORM,
                DEFORM_TABLE_START_INDEX,
                NEGATE_WORD);

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    /**
     * ROM {@code cmpi.w #$EC0,(Player_1+x_pos).w}. The sanctuary reads the player
     * position rather than the camera, because the plane redraw has to start while
     * the camera is still clamped behind the seam.
     */
    protected boolean isFarFraming() {
        AbstractPlayableSprite player = mainPlayable();
        if (player == null) {
            return false;
        }
        return (player.getCentreX() & 0xFFFF) >= BACKGROUND_SEAM_X;
    }

    private AbstractPlayableSprite mainPlayable() {
        if (!GameServices.hasRuntime() || GameServices.spritesOrNull() == null) {
            return null;
        }
        return GameServices.sprites().getMainPlayable();
    }

    /**
     * ROM {@code loc_5A33C}: {@code Camera_Y_pos_BG_copy} is 3/16 of the offset
     * camera Y with {@code Screen_shake_offset} removed before scaling and added
     * back afterwards.
     *
     * <p>The sanctuary's {@code Screen_shake_flag} countdown is not modelled yet
     * (the falling-crystal ceremony only publishes a boolean), so the shake term
     * is zero here; it folds in at exactly these two points once the ROM counter
     * exists.
     */
    private short backgroundY(int cameraY, int yOffset) {
        int shakeY = 0;
        int scaled = (((short) (cameraY - shakeY + yOffset)) << 16) >> 4;
        return (short) (((scaled * 3) >> 16) + shakeY);
    }

    /**
     * Builds the {@code HScroll_table} words {@code loc_5A33C} and
     * {@code loc_5A388} write: the full 3/16 rate in words 0 and 4, and a
     * descending 3/4-to-1/4 gradient in words 13 down to 5 with word 2 mirroring
     * word 13.
     */
    private void buildHScrollTable(int cameraX, int xOffset) {
        hScrollTable.clear();

        // ROM: move.w (Camera_X_pos_copy).w,d0 / sub.w d2,d0 / swap d0 / clr.w d0
        int offsetX = ((short) (cameraX - xOffset)) << 16;

        // ROM: asr.l #4,d0 / move.l d0,d1 / add.l d0,d0 / add.l d1,d0 -> 3/16
        short base = (short) (((offsetX >> 4) * 3) >> 16);
        hScrollTable.set(0, base);
        hScrollTable.set(DEFORM_TABLE_START_INDEX, base);

        // ROM: move.l d2,d0 / asr.l #2,d2 / sub.l d2,d0 -> 3/4 ; asr.l #2,d2 -> 1/16 step
        int value = offsetX;
        int step = offsetX >> 2;
        value -= step;
        step >>= 2;
        for (int i = 0; i < GRADIENT_WORD_COUNT; i++) {
            hScrollTable.set(GRADIENT_TOP_INDEX - i, (short) (value >> 16));
            value -= step;
        }

        // ROM: move.w (HScroll_table+$01A).w,(HScroll_table+$004).w
        hScrollTable.set(GRADIENT_MIRROR_INDEX, hScrollTable.get(GRADIENT_TOP_INDEX));
    }
}
