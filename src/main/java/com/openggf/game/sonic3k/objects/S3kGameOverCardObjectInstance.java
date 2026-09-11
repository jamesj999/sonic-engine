package com.openggf.game.sonic3k.objects;

import com.openggf.game.JoypadPressSnapshot;
import com.openggf.game.save.SaveReason;
import com.openggf.level.objects.AbstractGameOverCardObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;

/**
 * Sonic 3&amp;K {@code Obj_GameOver} (docs/skdisasm/sonic3k.asm:62020-62101).
 *
 * <ul>
 *   <li>{@code tst.l (Nem_decomp_queue).w} (:62021-62023): the ROM holds until
 *       the Nemesis queue that {@code Load_PLC_2 #3} filled has drained. The
 *       engine has no per-frame S3K Nemesis drain (see
 *       {@code Sonic3kTitleCardManager}), so the card starts sliding on its
 *       first frame; recorded in docs/S3K_KNOWN_DISCREPANCIES.md.</li>
 *   <li>{@code loc_2D5CE}: the GAME word (frame 0 only, {@code tst.b
 *       mapping_frame}) calls {@code SaveGame_LivesContinues} before setting
 *       up (:62027-62030; routine at :15975-15997), so the save slot records
 *       the zero life count and the continues in hand.</li>
 *   <li>{@code loc_2D62A}: {@code move.w #8*60,anim_frame_timer(a0)} (:62059).</li>
 *   <li>{@code loc_2D638}: {@code Collision_response_list} is zeroed every
 *       wait frame (:62065); bit 0 of the frame is tested first, then
 *       {@code Ctrl_1_pressed | Ctrl_2_pressed} against A/B/C/Start
 *       (:62066-62075).</li>
 * </ul>
 */
public final class S3kGameOverCardObjectInstance extends AbstractGameOverCardObjectInstance {

    /** {@code move.w #8*60,anim_frame_timer(a0)}. */
    public static final int WAIT_FRAMES = 8 * 60;

    public S3kGameOverCardObjectInstance(int mappingFrame) {
        super(mappingFrame);
    }

    /** Headless probe constructor: the GAME word. */
    public S3kGameOverCardObjectInstance() {
        this(FRAME_GAME);
    }

    /** The engine keeps ArtNem_GameOver resident; no Nemesis queue exists to wait on. */
    @Override
    protected boolean isArtPending() {
        return false;
    }

    @Override
    protected void onArtReady() {
        if (getMappingFrame() == FRAME_GAME && !ObjectConstructionContext.isRewindActiveRestore()) {
            // SaveGame_LivesContinues: no-op for S&K alone / no save pointer, which
            // the session save request reproduces by ignoring sessions without a slot.
            services().requestSessionSave(SaveReason.LIVES_CONTINUES_SAVE);
        }
    }

    @Override
    protected int waitFrames() {
        return WAIT_FRAMES;
    }

    /**
     * {@code andi.b #button_A_mask|button_B_mask|button_C_mask|button_start_mask,d0}
     * over both controllers' press bytes.
     */
    @Override
    protected boolean isDismissPressed(JoypadPressSnapshot presses) {
        return presses.eitherAnyActionPressed() || presses.eitherStartPressed();
    }

    @Override
    protected boolean overElementPollsDismissButton() {
        return false;
    }
}
