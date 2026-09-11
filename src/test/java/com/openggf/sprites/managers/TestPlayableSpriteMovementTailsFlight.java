package com.openggf.sprites.managers;

import com.openggf.game.rules.CrossGameRuleComposer;
import com.openggf.game.rules.GameRules;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestPlayableSpriteMovementTailsFlight {
    private TestTails tails;
    private PlayableSpriteMovement movement;

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        tails = new TestTails("tails", (short) 100, (short) 100);
        tails.useRules(GameRules.SONIC_3K);
        movement = new PlayableSpriteMovement(tails);
        prepareReleasedAirJump(tails);
    }

    @Test
    void nativeS3kMainTailsActivatesFlightOnAirborneJumpRepress() throws Exception {
        repressJump(movement);

        assertTrue(tails.getTailsFlightController().isActive());
        assertEquals(TailsFlightController.FLIGHT_TIME, tails.getDoubleJumpProperty() & 0xFF);
        assertFalse(tails.getRolling(), "Tails_Fly restores standing form on activation");
        assertFalse(tails.getRollingJump(), "the activating repress is consumed by flight");
    }

    @Test
    void nativeSonic2RulesDoNotActivateTailsFlight() throws Exception {
        tails.useRules(GameRules.SONIC_2);

        repressJump(movement);

        assertFalse(tails.getTailsFlightController().isActive());
    }

    @Test
    void sonic3kDonorRulesEnableTailsFlightInSonic2Host() throws Exception {
        tails.useRules(CrossGameRuleComposer.compose(
                GameRules.SONIC_2, GameRules.SONIC_3K,
                new Sonic3kGameModule().getDonorCapabilities()));

        repressJump(movement);

        assertTrue(tails.getTailsFlightController().isActive());
    }

    @Test
    void sonic2DonorRulesDisableTailsFlightInSonic3kHost() throws Exception {
        tails.useRules(CrossGameRuleComposer.compose(
                GameRules.SONIC_3K, GameRules.SONIC_2,
                new Sonic2GameModule().getDonorCapabilities()));

        repressJump(movement);

        assertFalse(tails.getTailsFlightController().isActive());
    }

    @Test
    void sonicNeverActivatesTailsFlightEvenWithFlightCapability() throws Exception {
        TestSonic sonic = new TestSonic("sonic", (short) 100, (short) 100);
        sonic.useRules(GameRules.SONIC_3K);
        prepareReleasedAirJump(sonic);
        PlayableSpriteMovement sonicMovement = new PlayableSpriteMovement(sonic);

        repressJump(sonicMovement);

        assertEquals(0, sonic.getDoubleJumpProperty() & 0xFF,
                "Sonic may consume the repress with his own ability but never enters Tails flight");
    }

    @Test
    void activeManualFlightUpdatesVerticalVelocityExactlyOncePerAirborneFrame() {
        tails.setYSpeed((short) 0);
        tails.getTailsFlightController().activate();

        movement.handleMovement(false, false, false, false,
                false, false, false, false);

        assertEquals((short) 0x0008, tails.getYSpeed(),
                "Tails_Move_FlySwim runs once and replaces normal +$38 air gravity");
    }

    @Test
    void activeManualSwimmingWithoutInputGentlySinksAtFlightGravity() {
        tails.setInWater(true);
        tails.setYSpeed((short) 0);
        tails.getTailsFlightController().activate();

        movement.handleMovement(false, false, false, false,
                false, false, false, false);
        assertEquals((short) 0x0008, tails.getYSpeed());

        movement.handleMovement(false, false, false, false,
                false, false, false, false);
        assertEquals((short) 0x0010, tails.getYSpeed());
    }

    @Test
    void landingClearsFlight() {
        tails.getTailsFlightController().activate();
        tails.setAir(false);

        assertFalse(tails.getTailsFlightController().isActive(), "landing clears flight");
    }

    @Test
    void deathClearsFlight() {
        tails.getTailsFlightController().activate();
        tails.setDead(true);

        assertFalse(tails.getTailsFlightController().isActive(), "death clears flight");
    }

    @Test
    void playerResetClearsFlight() {
        tails.getTailsFlightController().activate();
        tails.resetState();

        assertFalse(tails.getTailsFlightController().isActive(), "player reset clears flight");
    }

    @Test
    void objectControlTakeoverClearsFlight() {
        tails.getTailsFlightController().activate();
        tails.setObjectControlled(true);

        assertFalse(tails.getTailsFlightController().isActive(), "object-control takeover clears flight");
    }

    private static void prepareReleasedAirJump(AbstractPlayableSprite sprite) {
        sprite.setAir(true);
        sprite.setJumping(true);
        sprite.setRolling(true);
        sprite.setRollingJump(true);
        sprite.setDoubleJumpFlag(0);
    }

    private static void repressJump(PlayableSpriteMovement movement) throws Exception {
        setField(movement, "jumpReleasedSinceJump", true);
        setField(movement, "inputJump", true);
        setField(movement, "inputJumpPress", true);
        Method jumpHeight = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
        jumpHeight.setAccessible(true);
        jumpHeight.invoke(movement);
    }

    private static void setField(PlayableSpriteMovement movement, String name, boolean value) throws Exception {
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
