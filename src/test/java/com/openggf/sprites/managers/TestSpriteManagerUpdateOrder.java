package com.openggf.sprites.managers;

import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.physics.Sensor;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSpriteManagerUpdateOrder {

    @BeforeEach
    void configureRuntime() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void leaderUpdatesBeforeCpuSidekickChain() {
        TestPlayableSprite main = new TestPlayableSprite("main");
        TestPlayableSprite sidekick1 = new TestPlayableSprite("sidekick1");
        sidekick1.setCpuControlled(true);
        TestPlayableSprite sidekick2 = new TestPlayableSprite("sidekick2");
        sidekick2.setCpuControlled(true);

        List<Sprite> sprites = List.of(sidekick2, sidekick1, main);

        List<AbstractPlayableSprite> ordered = SpriteManager.buildPlayableUpdateOrder(
                sprites,
                List.of(sidekick1, sidekick2),
                false);

        assertEquals(List.of(main, sidekick1, sidekick2), ordered);
    }

    @Test
    void suppressedCpuSidekicksAreExcludedFromUpdateOrder() {
        TestPlayableSprite main = new TestPlayableSprite("main");
        TestPlayableSprite sidekick = new TestPlayableSprite("sidekick");
        sidekick.setCpuControlled(true);

        List<AbstractPlayableSprite> ordered = SpriteManager.buildPlayableUpdateOrder(
                List.of(sidekick, main),
                List.of(sidekick),
                true);

        assertEquals(List.of(main), ordered);
    }

    @Test
    void publishHeldInputForLevelEventsUsesLogicalPlayerOneDirections() {
        SpriteManager manager = new SpriteManager();
        TestPlayableSprite main = new TestPlayableSprite("main");
        manager.addSprite(main);

        InputHandler input = new InputHandler();
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(
                        AbstractPlayableSprite.INPUT_UP | AbstractPlayableSprite.INPUT_RIGHT,
                        0,
                        0,
                        0,
                        false,
                        false),
                PlayerInputState.neutral()));

        manager.publishHeldInputForLevelEvents(input);

        assertTrue(main.isUpPressed());
        assertTrue(main.isRightPressed());
        assertFalse(main.isDownPressed());
        assertFalse(main.isLeftPressed());
    }

    @Test
    void interruptedPlayablePrefixRecordsLeaderHistoryBeforeAnimation() {
        SpriteManager manager = new SpriteManager();
        TestPlayableSprite main = new TestPlayableSprite("main");
        main.setCentreX((short) 0x1234);
        main.setCentreY((short) 0x0567);
        manager.addSprite(main);
        int previousSlot = main.getHistorySlotIndex(0);

        manager.advancePlayableSlotPrefix();

        assertEquals((previousSlot + 1) & 0x3F, main.getHistorySlotIndex(0));
        assertEquals((short) 0x1234, main.getCentreX(0));
        assertEquals((short) 0x0567, main.getCentreY(0));
    }

    private static final class TestPlayableSprite extends AbstractPlayableSprite {
        private TestPlayableSprite(String code) {
            super(code, (short) 0, (short) 0);
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
