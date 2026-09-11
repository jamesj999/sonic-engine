package com.openggf.sprites.playable;

import com.openggf.game.rules.GameRules;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.sprites.managers.PlayableSpriteMovement;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestSidekickCpuManualFlight {
    private TestTails tails;
    private SidekickCpuController cpu;
    private PlayableSpriteMovement movement;

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        TestSonic sonic = new TestSonic("sonic", (short) 100, (short) 100);
        tails = new TestTails("tails_p2", (short) 80, (short) 100);
        sonic.useRules(GameRules.SONIC_3K);
        tails.useRules(GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        cpu = new SidekickCpuController(tails, sonic);
        cpu.forceStateForTest(SidekickCpuController.State.NORMAL, 20);
        movement = new PlayableSpriteMovement(tails);
        prepareReleasedAirJump();
    }

    @Test
    void publicManualControlQueryTracksOnlyTheExistingP2Window() throws Exception {
        assertFalse(cpu.isUnderManualControl());

        cpu.setController2Input(AbstractPlayableSprite.INPUT_JUMP,
                AbstractPlayableSprite.INPUT_JUMP);
        cpu.update(1);

        assertTrue(cpu.isUnderManualControl());
    }

    @Test
    void cpuTailsRejectsFlightOutsideManualP2ControlWindow() throws Exception {
        repressJump();

        assertFalse(tails.getTailsFlightController().isActive());
    }

    @Test
    void cpuTailsActivatesFlightDuringManualP2ControlWindow() throws Exception {
        cpu.setController2Input(AbstractPlayableSprite.INPUT_JUMP,
                AbstractPlayableSprite.INPUT_JUMP);
        cpu.update(1);

        repressJump();

        assertTrue(tails.getTailsFlightController().isActive());
    }

    @Test
    void finalManualControlFrameOwnsDispatchedRepressThenExpiresBeforeNextCpuTick() {
        cpu.hydrateFromRomCpuState(0x06, 1, 0, 0, true, 0, 0);
        cpu.setController2Input(0, AbstractPlayableSprite.INPUT_JUMP);

        cpu.update(1);
        dispatchCpuInputThroughMovement();

        assertEquals(0, cpu.getDiagnosticControlCounter(),
                "the final manual frame consumes the last counter tick");
        assertTrue(cpu.isUnderManualControl(),
                "manual ownership remains visible through movement on the frame that applied P2 input");
        assertTrue(tails.getTailsFlightController().isActive());
        assertEquals((short) 0x0008, tails.getYSpeed(),
                "the final-frame repress activates and updates flight exactly once");

        cpu.setController2Input(0, 0);
        cpu.update(2);

        assertFalse(cpu.isUnderManualControl(),
                "the prior frame's ownership latch must not extend manual control");
        assertNotEquals("manual_control", cpu.getLatestNormalStepDiagnostics().followBranch(),
                "normal CPU behavior resumes immediately after the owned frame");
    }

    private void dispatchCpuInputThroughMovement() {
        if (cpu.getInputJumpPress()) {
            tails.setForcedJumpPress(true);
        }
        movement.handleMovement(cpu.getInputUp(), cpu.getInputDown(),
                cpu.getInputLeft(), cpu.getInputRight(), cpu.getInputJump(),
                false, false, false);
    }

    private void prepareReleasedAirJump() {
        tails.setAir(true);
        tails.setJumping(true);
        tails.setRolling(true);
        tails.setRollingJump(true);
        tails.setDoubleJumpFlag(0);
    }

    private void repressJump() throws Exception {
        setField("jumpReleasedSinceJump", true);
        setField("inputJump", true);
        setField("inputJumpPress", true);
        Method jumpHeight = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
        jumpHeight.setAccessible(true);
        jumpHeight.invoke(movement);
    }

    private void setField(String name, boolean value) throws Exception {
        Field field = PlayableSpriteMovement.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(movement, value);
    }

    private static final class TestTails extends Tails {
        private TestTails(String code, short x, short y) {
            super(code, x, y);
        }

        private void useRules(GameRules rules) {
            setGameRulesForTest(rules);
        }
    }

    private static final class TestSonic extends Sonic {
        private TestSonic(String code, short x, short y) {
            super(code, x, y);
        }

        private void useRules(GameRules rules) {
            setGameRulesForTest(rules);
        }
    }
}
