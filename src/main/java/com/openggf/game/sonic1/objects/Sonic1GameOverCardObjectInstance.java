package com.openggf.game.sonic1.objects;

import com.openggf.game.JoypadPressSnapshot;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.level.objects.AbstractGameOverCardObjectInstance;

/**
 * Sonic 1 Obj39 {@code GameOverCard} (docs/s1disasm/_incObj/39 Game Over.asm).
 *
 * <ul>
 *   <li>{@code Over_ChkPLC}: {@code tst.l (v_plc_buffer).w} &mdash; holds until
 *       the PLC queue that {@code Sonic_HandleDeath} filled with
 *       {@code plcid_GameOver} has drained (:17-20).</li>
 *   <li>{@code .conjoined}: {@code move.w #12*60,obTimeFrame(a0)} (:48).</li>
 *   <li>{@code Over_Wait}: {@code v_jpadpress1 & btnABC} is tested before the
 *       OVER-object test, so both halves answer a press (:57-66).</li>
 * </ul>
 */
public final class Sonic1GameOverCardObjectInstance extends AbstractGameOverCardObjectInstance {

    /** {@code move.w #12*60,obTimeFrame(a0)}. */
    public static final int WAIT_FRAMES = 12 * 60;

    public Sonic1GameOverCardObjectInstance(int mappingFrame) {
        super(mappingFrame);
    }

    /** Headless probe constructor: the GAME word. */
    public Sonic1GameOverCardObjectInstance() {
        this(FRAME_GAME);
    }

    @Override
    protected boolean isArtPending() {
        Sonic1PlcService plcService = services().gameService(Sonic1PlcService.class);
        return plcService != null && plcService.isBusy();
    }

    @Override
    protected int waitFrames() {
        return WAIT_FRAMES;
    }

    /** {@code move.b (v_jpadpress1).w,d0; andi.b #btnABC,d0}: controller 1 only. */
    @Override
    protected boolean isDismissPressed(JoypadPressSnapshot presses) {
        return presses.player1AnyActionPressed();
    }

    @Override
    protected boolean overElementPollsDismissButton() {
        return true;
    }
}
