package com.openggf.game.sonic2.objects;

import com.openggf.game.JoypadPressSnapshot;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.level.objects.AbstractGameOverCardObjectInstance;

/**
 * Sonic 2 {@code Obj39} (docs/s2disasm/s2.asm:27670-27774).
 *
 * <ul>
 *   <li>{@code Obj39_Init}: {@code tst.l (Plc_Buffer).w} holds until the
 *       {@code PLCID_GameOver} load has drained (:27682-27685).</li>
 *   <li>{@code Obj39_SetTimer}: {@code move.w #$2D0,anim_frame_duration(a0)}
 *       (:27713).</li>
 *   <li>{@code Obj39_Wait}: bit 0 of the frame is tested first, so OVER only
 *       displays; then {@code Ctrl_1_Press | Ctrl_2_Press} against A/B/C
 *       (:27724-27736). The two-player results branch of {@code Obj39_Dismiss}
 *       is unreachable here: the engine has no {@code Two_player_mode}.</li>
 * </ul>
 */
public final class Sonic2GameOverCardObjectInstance extends AbstractGameOverCardObjectInstance {

    /** {@code move.w #$2D0,anim_frame_duration(a0)}. */
    public static final int WAIT_FRAMES = 0x2D0;

    public Sonic2GameOverCardObjectInstance(int mappingFrame) {
        super(mappingFrame);
    }

    /** Headless probe constructor: the GAME word. */
    public Sonic2GameOverCardObjectInstance() {
        this(FRAME_GAME);
    }

    @Override
    protected boolean isArtPending() {
        Sonic2PlcService plcService = services().gameService(Sonic2PlcService.class);
        return plcService != null && plcService.isBusy();
    }

    @Override
    protected int waitFrames() {
        return WAIT_FRAMES;
    }

    /** {@code move.b (Ctrl_1_Press).w,d0; or.b (Ctrl_2_Press).w,d0; andi.b #button_B_mask|button_C_mask|button_A_mask,d0}. */
    @Override
    protected boolean isDismissPressed(JoypadPressSnapshot presses) {
        return presses.eitherAnyActionPressed();
    }

    @Override
    protected boolean overElementPollsDismissButton() {
        return false;
    }
}
