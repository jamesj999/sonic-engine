package com.openggf.game.sonic1.objects;

import com.openggf.game.GameServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.Direction;
import com.openggf.physics.FrameCollisionPlan;
import com.openggf.physics.GroundSensor;
import com.openggf.physics.Sensor;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.managers.PlayableSpriteMovement;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
@FullReset
class TestSonic1SpringObjectInstance {
    @Test
    void horizontalSpringTogglesExistingFacingInsteadOfFacingLaunchVelocity() throws Exception {
        Sonic1SpringObjectInstance spring = new Sonic1SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x10, 0, false, 0));
        spring.setServices(new StubObjectServices().withIsolatedObjectManager());
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x100, (short) 0x100);
        Method applyHorizontalSpring = Sonic1SpringObjectInstance.class
                .getDeclaredMethod("applyHorizontalSpring", AbstractPlayableSprite.class);
        applyHorizontalSpring.setAccessible(true);

        player.setDirection(Direction.RIGHT);
        applyHorizontalSpring.invoke(spring, player);

        assertEquals(0x1000, player.getXSpeed());
        assertEquals(Direction.LEFT, player.getDirection(),
                "Spring_BounceLR bchg toggles the prior status bit even for a rightward launch");
    }

    @Test
    void exposesFullSolidRoutineProfileForVerticalAndHorizontalSprings() {
        Sonic1SpringObjectInstance vertical = new Sonic1SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x00, 0, false, 0));
        Sonic1SpringObjectInstance horizontal = new Sonic1SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x10, 0, false, 0));

        SolidRoutineProfile verticalProfile = vertical.getSolidRoutineProfile();
        SolidRoutineProfile horizontalProfile = horizontal.getSolidRoutineProfile();

        assertEquals(SolidRoutineKind.FULL_SOLID, verticalProfile.kind());
        // ROM Spring routines call SolidObject, whose x-range check rejects only when
        // d0 > 2*halfWidth (Solid_ChkCollision `cmp d3,d0; bhi`), so the right edge
        // (Sonic flush against the object's right face) still collides — inclusive.
        // Required for the LR spring to register the side contact that fires its push
        // bounce when Sonic falls flush against it (S1 SYZ1 f502 -> f816).
        assertTrue(verticalProfile.inclusiveRightEdge());
        assertFalse(verticalProfile.bypassesOffscreenSolidGate());
        assertTrue(verticalProfile.stickyContactBuffer());
        assertEquals(SolidRoutineKind.FULL_SOLID, horizontalProfile.kind());
        assertTrue(horizontalProfile.inclusiveRightEdge());
        assertFalse(horizontalProfile.bypassesOffscreenSolidGate());
        assertTrue(horizontalProfile.stickyContactBuffer());
    }

    @Test
    void upSpringSuppressesSolidContactUntilAnimationResetCompletes() {
        Sonic1SpringObjectInstance spring = new Sonic1SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x00, 0, false, 0));
        spring.setServices(new StubObjectServices().withIsolatedObjectManager());
        spring.update(0, null);

        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x100, (short) 0x100);
        SolidContact standing = new SolidContact(true, false, false, true, false);

        assertTrue(spring.isSolidFor(player));
        spring.onSolidContact(player, standing, 0);
        assertEquals(-0x1000, player.getYSpeed());
        assertFalse(spring.isSolidFor(player));

        for (int frame = 1; frame <= 11; frame++) {
            player.setYSpeed((short) 0);
            spring.update(frame, player);
            assertFalse(spring.isSolidFor(player), "spring should ignore contact during ROM animation/reset frame " + frame);
            spring.onSolidContact(player, standing, frame);
            assertEquals(0, player.getYSpeed(), "spring relaunched while ROM routine does not call SolidObject at frame " + frame);
        }

        spring.update(12, player);
        assertTrue(spring.isSolidFor(player));
        spring.onSolidContact(player, standing, 12);
        assertEquals(-0x1000, player.getYSpeed());
    }

    /**
     * ROM: Spring_BounceUp (docs/s1disasm/_incObj/41 Springs.asm:96)
     * {@code move.b #2,obRoutine(a1)} unconditionally forces Sonic's OWN object
     * routine to 2 (Sonic_Control) the instant an up-spring fires — regardless
     * of what routine he was previously in. Routine 4 is the hurt/knockback
     * routine (see AbstractPlayableSprite.hurt: "Mirrors ROM routine=4 check").
     * So a spring landing during the hurt knockback overrides routine 4 back to
     * 2 immediately, restoring player control the same frame the spring fires
     * — it does not wait for a normal grounded landing to clear hurt the way
     * Sonic_HurtStop/Sonic_ResetOnFloor would.
     * <p>
     * Beyond the {@code hurt} flag itself, this drives a real
     * {@link PlayableSpriteMovement#handleMovement} tick before and after the
     * bounce to prove input is FUNCTIONALLY restored (D-pad reaches air
     * acceleration), not just that the internal flag flipped.
     */
    @Test
    void upSpringRestoresControlImmediatelyEvenWhileHurt() {
        Sonic1SpringObjectInstance spring = new Sonic1SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x00, 0, false, 0));
        spring.setServices(new StubObjectServices().withIsolatedObjectManager());
        spring.update(0, null);

        HurtCapableSprite player = newHurtCapableAirborneSprite();
        PlayableSpriteMovement movement = newMovementManagerForAirborneTicks(player);

        // Control: while still hurt, held-left input must be ignored entirely
        // (PlayableSpriteMovement.handleMovement gates left/right on isHurt()).
        movement.handleMovement(false, false, true, false, false, false, false, false);
        assertEquals(0, player.getXSpeed(),
                "hurt player must ignore held D-pad input before the spring fires (control locked)");

        SolidContact standing = new SolidContact(true, false, false, true, false);
        spring.onSolidContact(player, standing, 0);

        assertEquals(-0x1000, player.getYSpeed(), "spring must still impart its bounce velocity");
        assertFalse(player.isHurt(),
                "ROM Spring_BounceUp pokes obRoutine(a1)=2 unconditionally, ending the hurt "
                        + "routine on contact; the engine must restore control (D-pad/jump) the "
                        + "same frame instead of waiting for a normal grounded hurt-stop landing");

        // Functional check: the SAME held-left input now reaches air acceleration.
        movement.handleMovement(false, false, true, false, false, false, false, false);
        assertTrue(player.getXSpeed() < 0,
                "control must be functionally restored: held-left input should now apply air "
                        + "acceleration instead of being silently dropped");
    }

    /**
     * ROM: Spring_BounceDwn (docs/s1disasm/_incObj/41 Springs.asm:203) mirrors
     * Spring_BounceUp's unconditional {@code move.b #2,obRoutine(a1)}.
     */
    @Test
    void downSpringRestoresControlImmediatelyEvenWhileHurt() {
        Sonic1SpringObjectInstance spring = new Sonic1SpringObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0x41, 0x20, 0, false, 0));
        spring.setServices(new StubObjectServices().withIsolatedObjectManager());
        spring.update(0, null);

        HurtCapableSprite player = newHurtCapableAirborneSprite();
        PlayableSpriteMovement movement = newMovementManagerForAirborneTicks(player);

        movement.handleMovement(false, false, true, false, false, false, false, false);
        assertEquals(0, player.getXSpeed(),
                "hurt player must ignore held D-pad input before the spring fires (control locked)");

        SolidContact touchBottom = new SolidContact(false, false, true, false, false);
        spring.onSolidContact(player, touchBottom, 0);

        assertEquals(0x1000, player.getYSpeed(), "down spring bounces downward (negated strength)");
        assertFalse(player.isHurt(), "down spring must also restore control per Spring_BounceDwn");

        movement.handleMovement(false, false, true, false, false, false, false, false);
        assertTrue(player.getXSpeed() < 0,
                "control must be functionally restored: held-left input should now apply air "
                        + "acceleration instead of being silently dropped");
    }

    /**
     * Builds a hurt-and-airborne sprite with real (if unscanned) sensor lines.
     * {@code handleMovement}'s airborne path calls {@code updateSensors()},
     * which NPEs against {@link TestablePlayableSprite}'s no-op
     * {@code createSensorLines()} (mirrors the established pattern in
     * {@code TestPlayableSpriteMovement#driveHurtFromAngleAndReturnFinalHurtState}).
     */
    private static HurtCapableSprite newHurtCapableAirborneSprite() {
        HurtCapableSprite player = new HurtCapableSprite("sonic", (short) 0x100, (short) 0x100);
        player.setHurt(true);
        player.setAirForTest(true);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        return player;
    }

    /**
     * Wires a {@link PlayableSpriteMovement} that never lands (no terrain is
     * loaded in this unit test), so held D-pad input during the airborne ticks
     * this test drives can only be explained by control-lock state, not by a
     * surprise landing changing branches.
     */
    private static PlayableSpriteMovement newMovementManagerForAirborneTicks(AbstractPlayableSprite sprite)
            throws AssertionError {
        TestEnvironment.activeGameplayMode();
        // No level is loaded in this unit test, so the camera's default max-Y
        // boundary is far below the sprite's test position. With
        // isLevelStarted()==true, PlayableSpriteMovement's bottom pit-death
        // check ("ROM fixBugs" / Sonic_Boundary_CheckBottom) would fire on the
        // very first airborne tick and kill the player, which has nothing to
        // do with what this test is exercising. Suppress it the same way ROM
        // does during the level intro (Level_started_flag clear).
        GameServices.camera().setLevelStarted(false);
        CollisionSystem neverLandsCollisionSystem = new CollisionSystem(new TerrainCollisionManager()) {
            @Override
            public void resolveAirCollision(FrameCollisionPlan plan, AbstractPlayableSprite s,
                    Consumer<AbstractPlayableSprite> landingHandler, boolean forceFloorCheck) {
                // Never invoke landingHandler: simulates open air for every airborne tick.
            }

            @Override
            public void resolveAirCollision(AbstractPlayableSprite s,
                    Consumer<AbstractPlayableSprite> landingHandler, boolean forceFloorCheck) {
                // Never invoke landingHandler: simulates open air for every airborne tick.
            }
        };
        PlayableSpriteMovement movement =
                new PlayableSpriteMovement(sprite, neverLandsCollisionSystem, GameServices.gameState());
        try {
            GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
            Field field = GameplayModeContext.class.getDeclaredField("collisionSystem");
            field.setAccessible(true);
            field.set(gameplayMode, neverLandsCollisionSystem);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return movement;
    }

    private static final class HurtCapableSprite extends AbstractPlayableSprite {
        HurtCapableSprite(String code, short x, short y) {
            super(code, x, y);
        }

        @Override
        protected void defineSpeeds() {
            runAccel = 12;
            runDecel = 128;
            friction = 12;
            max = 1536;
            jump = 1664;
            slopeRunning = 32;
            slopeRollingDown = 80;
            slopeRollingUp = 20;
            rollDecel = 32;
            minStartRollSpeed = 128;
            minRollSpeed = 128;
            maxRoll = 4096;
            rollHeight = 28;
            runHeight = 38;
            standXRadius = 9;
            standYRadius = 19;
            rollXRadius = 7;
            rollYRadius = 14;
        }

        @Override
        protected void createSensorLines() {
            groundSensors = new Sensor[]{
                    new GroundSensor(this, Direction.DOWN, (byte) -9, (byte) 19, true),
                    new GroundSensor(this, Direction.DOWN, (byte) 9, (byte) 19, true)};
            ceilingSensors = new Sensor[]{
                    new GroundSensor(this, Direction.UP, (byte) -9, (byte) -19, false),
                    new GroundSensor(this, Direction.UP, (byte) 9, (byte) -19, false)};
            pushSensors = new Sensor[]{
                    new GroundSensor(this, Direction.LEFT, (byte) -10, (byte) 0, false),
                    new GroundSensor(this, Direction.RIGHT, (byte) 10, (byte) 0, false)};
        }

        @Override
        public void draw() {
        }

        void setAirForTest(boolean air) {
            this.air = air;
        }
    }
}
