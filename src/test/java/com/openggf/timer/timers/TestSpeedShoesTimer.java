package com.openggf.timer.timers;

import com.openggf.game.rules.GameRules;
import com.openggf.timer.TimerManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase-alignment tests for the speed-shoes decrement cadence.
 *
 * <p>S1/S2 (decimation 1) decrement every frame. S3K (decimation 8) decrements
 * only on frames where {@code (frame + LEVEL_FRAME_PHASE_OFFSET) & 7 == 0}. The
 * offset is zero: {@code (Level_frame_counter+1).w} reads the low byte at the
 * word label's second address and does not arithmetically add one, and the
 * engine advances its counter where {@code LevelLoop} does, before
 * {@code Process_Sprites} ({@code docs/skdisasm/sonic3k.asm:7919-7925}), so the
 * display step reads the ROM's own value. The timer therefore decrements when
 * {@code frameCounter & 7 == 0}.
 */
class TestSpeedShoesTimer {

    @Test
    void decimationOneDecrementsEveryFrame() {
        for (int frame = 0; frame < 16; frame++) {
            assertTrue(SpeedShoesTimer.isDecrementFrame(frame, 1),
                    "decimation 1 must decrement on frame " + frame);
        }
    }

    @Test
    void decimationEightDecrementsExactlyOncePerEightFrameWindow() {
        for (int base = 0; base < 24; base += 8) {
            int hits = 0;
            int alignedFrame = -1;
            for (int i = 0; i < 8; i++) {
                int frame = base + i;
                if (SpeedShoesTimer.isDecrementFrame(frame, 8)) {
                    hits++;
                    alignedFrame = frame;
                }
            }
            assertEquals(1, hits, "exactly one decrement per 8-frame window starting at " + base);
            assertEquals(0, (alignedFrame + SpeedShoesTimer.LEVEL_FRAME_PHASE_OFFSET) & 7,
                    "aligned frame must satisfy (frame + offset) & 7 == 0");
        }
    }

    @Test
    void decimationEightAlignsToTheRomCounterTheLoopTopAdvanced() {
        for (int frame = 0; frame < 16; frame++) {
            boolean expected = (frame & 7) == 0;
            assertEquals(expected, SpeedShoesTimer.isDecrementFrame(frame, 8),
                    "frame " + frame + " decrement gate");
        }
    }

    @Test
    void constructorUsesS3kDecimationRules() {
        AbstractPlayableSprite sprite = mock(AbstractPlayableSprite.class);
        when(sprite.getGameRules()).thenReturn(GameRules.SONIC_3K);

        SpeedShoesTimer timer = new SpeedShoesTimer("speed-shoes", sprite);

        assertEquals(SpeedShoesTimer.ROM_DURATION_FRAMES
                        / GameRules.SONIC_3K.powerUp().speedShoesTimerDecimation(),
                timer.getTicks());
    }

    @Test
    void hurtRoutineFreezesCountdownUntilNormalControlResumes() {
        AbstractPlayableSprite sprite = mock(AbstractPlayableSprite.class);
        when(sprite.getGameRules()).thenReturn(GameRules.SONIC_3K);
        when(sprite.isHurt()).thenReturn(true, false);
        SpeedShoesTimer timer = new SpeedShoesTimer("speed-shoes", sprite);

        int initialTicks = timer.getTicks();
        timer.decrementTick();
        assertEquals(initialTicks, timer.getTicks(),
                "native hurt routine bypasses Sonic_ChkShoes");

        timer.decrementTick();
        assertEquals(initialTicks - 1, timer.getTicks(),
                "countdown resumes with the normal control routine");
    }

    @Test
    void s1AndS2CountTheRomWordTimerWithNoPhaseCompensation() {
        // Obj01_ChkShoes counts speedshoes_time down from $4B0 and does the
        // physics restore and the slow-down music on the frame it reaches zero
        // (docs/s2disasm/s2.asm:36307-36326). The countdown runs from
        // Sonic_Display, so the engine's duration is the ROM's exactly, with no
        // tick added to compensate for where the engine's timer pass sits.
        for (GameRules rules : new GameRules[] { GameRules.SONIC_1, GameRules.SONIC_2 }) {
            AbstractPlayableSprite sprite = mock(AbstractPlayableSprite.class);
            when(sprite.getGameRules()).thenReturn(rules);
            assertEquals(SpeedShoesTimer.ROM_DURATION_FRAMES,
                    new SpeedShoesTimer("speed-shoes", sprite).getTicks(),
                    "S1/S2 speed shoes run the ROM's own $4B0 countdown");
        }
    }

    @Test
    void theLevelLoopTimerPassLeavesTheDisplayPhaseCountdownAlone() {
        // TimerManager.update() is the pre-physics pass. The shoes countdown
        // belongs to the character's display step, which the ROM reaches only
        // after the movement modes have run, so the pre-physics pass must not
        // advance it; only updateDisplayPhaseTimersFor(owner) may.
        AbstractPlayableSprite sprite = mock(AbstractPlayableSprite.class);
        when(sprite.getGameRules()).thenReturn(GameRules.SONIC_2);
        SpeedShoesTimer timer = new SpeedShoesTimer("speed-shoes", sprite);
        int initialTicks = timer.getTicks();

        TimerManager timers = new TimerManager();
        timers.registerTimer(timer);

        timers.update();
        assertEquals(initialTicks, timer.getTicks(),
                "the level loop's pre-physics timer pass must not run a display-phase countdown");

        timers.updateDisplayPhaseTimersFor(new Object());
        assertEquals(initialTicks, timer.getTicks(),
                "another character's display step must not run this character's countdown");

        timers.updateDisplayPhaseTimersFor(sprite);
        assertEquals(initialTicks - 1, timer.getTicks(),
                "the owning character's display step runs the countdown");
        assertNotNull(timers.getTimerForCode("speed-shoes"));
    }
}
