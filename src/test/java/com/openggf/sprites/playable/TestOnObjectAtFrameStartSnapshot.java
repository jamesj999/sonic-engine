package com.openggf.sprites.playable;

import com.openggf.game.session.EngineServices;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.openggf.game.session.EngineContext;
import com.openggf.physics.Sensor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for the {@link AbstractPlayableSprite#captureOnObjectAtFrameStart()}
 * / {@link AbstractPlayableSprite#getOnObjectAtFrameStart()} pair that mirrors
 * the ROM's mid-frame {@code Status_OnObj} read in {@code Tails_CPU_Control}
 * (sonic3k.asm:26688-26700) / {@code TailsCPU_Normal} (s2.asm:38933+).
 *
 * <p>The snapshot is the foundation for closing the OnObj timing gap between
 * engine and ROM: ROM reads {@code status(a1)} mid-frame, before solid-object
 * processing (sub_1FF1E sonic3k.asm:44306-44319, loc_1FFC4 sonic3k.asm:
 * 44369-44381) clears the bit; {@code Sonic_Jump} (sonic3k.asm:23288-23354)
 * sets {@code Status_InAir} but never touches {@code Status_OnObj}. The
 * engine's player tick clears the bit earlier (in
 * {@code PlayableSpriteMovement.doJump} and the air-unseat path in
 * {@code ObjectManager.processInlineObjectForPlayer}), so a live read after
 * the leader's tick would already see the post-tick state.
 */
class TestOnObjectAtFrameStartSnapshot {

    @BeforeEach
    void configureRuntime() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void snapshotPreservesValueAcrossLiveMutation() {
        TestSprite sprite = new TestSprite();
        sprite.setOnObject(true);
        sprite.captureOnObjectAtFrameStart();

        // Simulate the in-tick clear that PlayableSpriteMovement.doJump
        // (line 642) and ObjectManager.processInlineObjectForPlayer's
        // air-unseat path perform during the leader's player tick.
        sprite.setOnObject(false);

        assertFalse(sprite.isOnObject(), "live OnObj should reflect tick-clear");
        assertTrue(sprite.getOnObjectAtFrameStart(),
                "frame-start snapshot must persist after live clear");
    }

    @Test
    void snapshotInitiallyFalse() {
        TestSprite sprite = new TestSprite();
        assertFalse(sprite.getOnObjectAtFrameStart(),
                "snapshot defaults to false before any capture");
    }

    @Test
    void recaptureOverwritesSnapshot() {
        TestSprite sprite = new TestSprite();
        sprite.setOnObject(true);
        sprite.captureOnObjectAtFrameStart();
        assertTrue(sprite.getOnObjectAtFrameStart());

        sprite.setOnObject(false);
        sprite.captureOnObjectAtFrameStart();
        assertFalse(sprite.getOnObjectAtFrameStart(),
                "subsequent capture must reflect the new live OnObj");
    }

    @Test
    void snapshotIndependentOfLiveSetTrueAfterCapture() {
        TestSprite sprite = new TestSprite();
        sprite.setOnObject(false);
        sprite.captureOnObjectAtFrameStart();

        // Mid-tick set: e.g. SolidObjectTop attaching the player to a
        // platform. The frame-start view should still report false.
        sprite.setOnObject(true);

        assertTrue(sprite.isOnObject());
        assertFalse(sprite.getOnObjectAtFrameStart());
    }

    private static final class TestSprite extends AbstractPlayableSprite {
        private TestSprite() {
            super("test", (short) 0, (short) 0);
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 0;
            runDecel = 0;
            friction = 0;
            max = 0;
            jump = 0;
            angle = 0;
            slopeRunning = 0;
            slopeRollingDown = 0;
            slopeRollingUp = 0;
            rollDecel = 0;
            minStartRollSpeed = 0;
            minRollSpeed = 0;
            maxRoll = 0;
            rollHeight = 0;
            runHeight = 0;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[0];
            ceilingSensors = new Sensor[0];
            pushSensors = new Sensor[0];
        }

        @Override
        public void draw() {
            // No-op for tests.
        }
    }
}
