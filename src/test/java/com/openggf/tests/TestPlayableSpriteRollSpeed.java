package com.openggf.tests;

import com.openggf.game.GroundMode;
import com.openggf.game.rules.GameRules;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.Direction;
import com.openggf.physics.FrameCollisionPlan;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.managers.PlayableSpriteMovement;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Tails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@FullReset
@ExtendWith({
        RuntimeStateContaminationExtension.class,
        SingletonResetExtension.class
})
class TestPlayableSpriteRollSpeed {

    @Test
    void s3kTailsStopsRollingBelowMinimumRollSpeedThreshold() throws Exception {
        Tails tails = new Tails("tails", (short) 0, (short) 0);
        setGameRulesForTest(tails, GameRules.SONIC_3K);
        tails.setCpuControlled(true);
        tails.setGroundMode(GroundMode.GROUND);
        tails.setDirection(Direction.LEFT);
        tails.setAir(false);
        tails.setRolling(true);
        tails.setCentreX((short) 0x1DBD);
        tails.setCentreY((short) 0x0471);
        tails.setGSpeed((short) 0xFF7B);
        tails.setXSpeed((short) 0xFF7B);
        tails.setYSpeed((short) 0);

        PlayableSpriteMovement movement = new PlayableSpriteMovement(
                tails, new NoWallCollisionSystem(), null);
        Method doRollSpeed = PlayableSpriteMovement.class.getDeclaredMethod("doRollSpeed");
        doRollSpeed.setAccessible(true);
        doRollSpeed.invoke(movement);

        assertFalse(tails.getRolling(),
                "Tails_RollSpeed clears Status_Roll once abs(ground_vel) falls below $80");
        assertEquals(0x0470, tails.getCentreY() & 0xFFFF,
                "Unrolling restores Tails' default y_radius=$0F from rolling y_radius=$0E and shifts y_pos up one pixel");
        assertEquals(9, tails.getXRadius(),
                "Unrolling restores Tails' default x_radius");
        assertEquals(15, tails.getYRadius(),
                "Unrolling restores Tails' default y_radius");
        assertEquals(0xFF81, tails.getGSpeed() & 0xFFFF,
                "Roll-stop preserves the post-friction ground velocity used for velocity conversion");
    }

    @Test
    void s3kWallRollStopPreservesNativeCentreX() throws Exception {
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sonic.setGameRulesForTest(GameRules.SONIC_3K);
        sonic.setGroundMode(GroundMode.RIGHTWALL);
        sonic.setAir(false);
        sonic.setRolling(true);
        sonic.setCentreX((short) 0x2AB5);
        sonic.setCentreY((short) 0x060A);
        sonic.setGSpeed((short) 0);

        PlayableSpriteMovement movement = new PlayableSpriteMovement(
                sonic, new NoWallCollisionSystem(), null);
        invokeDoRollSpeed(movement);

        assertFalse(sonic.getRolling());
        assertEquals(0x2AB5, sonic.getCentreX() & 0xFFFF,
                "S3K roll-stop changes radii and y_pos, never native x_pos");
        assertEquals(0x0605, sonic.getCentreY() & 0xFFFF);
    }

    @Test
    void s2SonicPreservesRollingBelowMinimumRollSpeedUntilZeroInertia() throws Exception {
        TestablePlayableSprite sonic = s2Sonic((short) 0x0075);
        PlayableSpriteMovement movement = new PlayableSpriteMovement(
                sonic, new NoWallCollisionSystem(), null);

        invokeDoRollSpeed(movement);

        assertTrue(sonic.getRolling(),
                "S2 Sonic_CheckRollStop keeps Status_Roll while inertia is nonzero, even below $80");
        assertEquals(0x0571, sonic.getCentreY() & 0xFFFF,
                "S2 must not apply the 5 px unroll lift until inertia reaches zero");
        assertEquals(7, sonic.getXRadius(), "S2 keeps rolling x_radius while inertia is nonzero");
        assertEquals(14, sonic.getYRadius(), "S2 keeps rolling y_radius while inertia is nonzero");
        assertEquals(0x006F, sonic.getGSpeed() & 0xFFFF,
                "Natural roll friction still decays inertia before the zero-only stop check");
    }

    @Test
    void s2SonicStopsRollingAtZeroInertia() throws Exception {
        TestablePlayableSprite sonic = s2Sonic((short) 0);
        PlayableSpriteMovement movement = new PlayableSpriteMovement(
                sonic, new NoWallCollisionSystem(), null);

        invokeDoRollSpeed(movement);

        assertFalse(sonic.getRolling(),
                "S2 Sonic_CheckRollStop clears Status_Roll when inertia reaches zero");
        assertEquals(0x056C, sonic.getCentreY() & 0xFFFF,
                "S2 zero-inertia unroll applies the classic 5 px y_pos lift");
        assertEquals(9, sonic.getXRadius(), "S2 zero-inertia unroll restores standing x_radius");
        assertEquals(19, sonic.getYRadius(), "S2 zero-inertia unroll restores standing y_radius");
    }

    @Test
    void rollingLeftPressingRightClampsToPositiveMinimumOnZeroCrossing() throws Exception {
        // ROM Sonic_RollRight.changedirection (s1:01 Sonic.asm:895-899): the player is
        // rolling left (inertia=-$20) and presses right. add.w d4($20),d0 yields exactly
        // 0 with the carry SET, so bcc is not taken and inertia is clamped to +$80; the
        // following roll friction (-$06) leaves +$7A and the player keeps rolling.
        // Reproduces S1 LZ1 complete-run frame 12097.
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sonic.setGameRulesForTest(GameRules.SONIC_1);
        sonic.setGroundMode(GroundMode.GROUND);
        sonic.setDirection(Direction.LEFT);
        sonic.setAir(false);
        sonic.setRolling(true);
        sonic.setCentreX((short) 0x16EE);
        sonic.setCentreY((short) 0x00F1);
        sonic.setGSpeed((short) -0x20);
        sonic.setXSpeed((short) -0x20);
        sonic.setYSpeed((short) 0);

        PlayableSpriteMovement movement = new PlayableSpriteMovement(
                sonic, new NoWallCollisionSystem(), null);
        setInputs(movement, false, true);
        invokeDoRollSpeed(movement);

        assertTrue(sonic.getRolling(),
                "Right turnaround clamps inertia to +$80, so it never reaches zero and the player keeps rolling");
        assertEquals(0x007A, sonic.getGSpeed() & 0xFFFF,
                "add-carry clamp to +$80 then roll friction -$06 leaves +$7A");
        assertEquals(0x00F1, sonic.getCentreY() & 0xFFFF,
                "Still rolling: no unroll y-radius lift is applied");
    }

    @Test
    void rollingRightPressingLeftStaysZeroOnExactZeroCrossing() throws Exception {
        // ROM Sonic_RollLeft.changeddirection (s1:01 Sonic.asm:871-878) mirrors the right
        // case but uses sub.w d4,d0 / bcc / move.w #-$80. Subtraction borrows (carry set)
        // only when the result is strictly negative, so inertia $20 - $20 = 0 does NOT
        // clamp to -$80; it stays 0 and the player unrolls. This asymmetry between the
        // add-carry and sub-borrow boundaries must be preserved.
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sonic.setGameRulesForTest(GameRules.SONIC_1);
        sonic.setGroundMode(GroundMode.GROUND);
        sonic.setDirection(Direction.RIGHT);
        sonic.setAir(false);
        sonic.setRolling(true);
        sonic.setCentreX((short) 0x16EE);
        sonic.setCentreY((short) 0x00F1);
        sonic.setGSpeed((short) 0x20);
        sonic.setXSpeed((short) 0x20);
        sonic.setYSpeed((short) 0);

        PlayableSpriteMovement movement = new PlayableSpriteMovement(
                sonic, new NoWallCollisionSystem(), null);
        setInputs(movement, true, false);
        invokeDoRollSpeed(movement);

        assertEquals(0x0000, sonic.getGSpeed() & 0xFFFF,
                "Left turnaround sub-borrow boundary leaves an exact-zero crossing at 0, not -$80");
        assertFalse(sonic.getRolling(),
                "S1 unrolls once inertia reaches zero");
    }

    private static void setInputs(PlayableSpriteMovement movement,
            boolean inputLeft, boolean inputRight) throws Exception {
        Field left = PlayableSpriteMovement.class.getDeclaredField("inputLeft");
        Field right = PlayableSpriteMovement.class.getDeclaredField("inputRight");
        left.setAccessible(true);
        right.setAccessible(true);
        left.set(movement, inputLeft);
        right.set(movement, inputRight);
    }

    private static TestablePlayableSprite s2Sonic(short gSpeed) {
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        sonic.setGameRulesForTest(GameRules.SONIC_2);
        sonic.setGroundMode(GroundMode.GROUND);
        sonic.setDirection(Direction.RIGHT);
        sonic.setAir(false);
        sonic.setRolling(true);
        sonic.setCentreX((short) 0x02E8);
        sonic.setCentreY((short) 0x0571);
        sonic.setGSpeed(gSpeed);
        sonic.setXSpeed(gSpeed);
        sonic.setYSpeed((short) 0);
        return sonic;
    }

    private static void invokeDoRollSpeed(PlayableSpriteMovement movement) throws Exception {
        Method doRollSpeed = PlayableSpriteMovement.class.getDeclaredMethod("doRollSpeed");
        doRollSpeed.setAccessible(true);
        doRollSpeed.invoke(movement);
    }

    private static void setGameRulesForTest(AbstractPlayableSprite sprite, GameRules featureSet) throws Exception {
        Field field = AbstractPlayableSprite.class.getDeclaredField("gameRules");
        field.setAccessible(true);
        field.set(sprite, featureSet);
    }

    private static final class NoWallCollisionSystem extends CollisionSystem {
        private NoWallCollisionSystem() {
            super(new TerrainCollisionManager());
        }

        @Override
        public void resolveGroundWallCollision(FrameCollisionPlan plan, AbstractPlayableSprite sprite) {
        }
    }
}
