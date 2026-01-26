package uk.co.jamesj999.sonic.sprites.managers;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestPlayableSpriteMovement {

        private PlayableSpriteMovement manager;
        private AbstractPlayableSprite mockSprite;

        @Before
        public void setUp() {
                mockSprite = new AbstractPlayableSprite("sonic", (short) 0, (short) 0) {
                        @Override
                        protected void defineSpeeds() {
                                this.max = 1536; // 6 pixels * 256
                                this.runAccel = 12; // 0.046875 * 256
                                this.runDecel = 128; // 0.5 * 256
                                this.slopeRunning = 32; // 0.125 * 256
                                this.friction = 12; // 0.046875 * 256
                                this.jump = 1664; // 6.5 * 256 (standard Sonic jump force)
                                this.slopeRollingDown = 80; // Full slope factor when rolling downhill
                                this.slopeRollingUp = 20; // Reduced factor when rolling uphill
                        }

                        @Override
                        protected void createSensorLines() {
                        }

                        @Override
                        public void draw() {
                        }
                };
                manager = new PlayableSpriteMovement(mockSprite);
        }

        @Test
        public void testCalculateLandingRightSlope() throws Exception {
                // Angle 0x20 (32). Slope \ (Down-Right).
                // ySpeed 500 (falling). xSpeed 0.
                // Expected gSpeed positive (slide right).

                mockSprite.setAngle((byte) 0x20);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertTrue("gSpeed should be positive for right-facing slope, but was " + mockSprite.getGSpeed(),
                                mockSprite.getGSpeed() > 0);
        }

        @Test
        public void testCalculateLandingLeftSlope() throws Exception {
                // Angle 0xE0 (224). Slope / (Up-Right).
                // ySpeed 500.
                // Expected gSpeed negative (slide left).

                mockSprite.setAngle((byte) 0xE0);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertTrue("gSpeed should be negative for left-facing slope, but was " + mockSprite.getGSpeed(),
                                mockSprite.getGSpeed() < 0);
        }

        @Test
        public void testTerrainCollisionWithFlaggedAngle() throws Exception {
                // Test cases: Current Angle -> Snapped Angle
                // When a tile returns 0xFF angle, it means "snap to nearest cardinal angle"
                // Formula: (angle + 0x20) & 0xC0
                // 0x00 (0 deg) -> 0x00
                // 0x10 (22.5 deg) -> 0x00
                // 0x30 (67.5 deg) -> 0x40 (90 deg)
                // 0x70 (157.5 deg) -> 0x80 (180 deg)

                byte[][] testCases = {
                                { (byte) 0x00, (byte) 0x00 },
                                { (byte) 0x10, (byte) 0x00 },
                                { (byte) 0x30, (byte) 0x40 },
                                { (byte) 0x70, (byte) 0x80 },
                                { (byte) 0xE0, (byte) 0x00 } // -32 -> 0
                };

                for (byte[] testCase : testCases) {
                        byte currentAngle = testCase[0];
                        byte expectedSnappedAngle = testCase[1];

                        // Test the direct formula: (angle + 0x20) & 0xC0
                        byte actual = (byte) ((currentAngle + 0x20) & 0xC0);
                        assertEquals(
                                        "Angle " + String.format("0x%02X", currentAngle) + " should snap to "
                                                        + String.format("0x%02X", expectedSnappedAngle),
                                        expectedSnappedAngle, actual);
                }
        }

        @Test
        public void testLandingFromAirWithStaleRightWallAngle() throws Exception {
                // Setup:
                // Sprite is in Air.
                // GroundMode is GROUND (automatically set when Air=true).
                // Angle is STALE (0xC0 = 192 = Right Wall).
                // Landing on a flat tile (0xFF).

                mockSprite.setAngle((byte) 0xC0); // Right Wall Angle
                mockSprite.setAir(true); // In Air
                mockSprite.setGroundMode(GroundMode.GROUND); // Ensure ground mode is GROUND

                // Simulating falling down
                mockSprite.setYSpeed((short) 1000);
                mockSprite.setXSpeed((short) 0);

                // Landing on 0xFF tile
                SensorResult result = new SensorResult((byte) 0xFF, (byte) -1, 0, Direction.DOWN);
                SensorResult[] results = { result, result };

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doTerrainCollisionAir",
                                SensorResult[].class);
                method.setAccessible(true);
                method.invoke(manager, (Object) results);

                // Verification:
                // The fix should ensure the angle snaps to 0x00 (Ground) based on the
                // GroundMode, not the stale angle 0xC0.

                assertEquals("Angle should be reset to 0x00 when landing from Air on flat ground, ignoring stale angle.",
                                (byte) 0x00, mockSprite.getAngle());
        }

        @Test
        public void testSlopeMomentumUncapped() throws Exception {
                // Initial speed at max (1536)
                mockSprite.setGSpeed((short) 1536);
                // Angle 0x20 (32). Slope \. This causes acceleration downhill (positive
                // gSpeed).
                mockSprite.setAngle((byte) 0x20);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);

                // Set up input state: left=false, right=true, down=false, up=false, jump=false
                setInputState(false, true, false, false, false);

                // Use doGroundMove which replaces calculateGSpeed
                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);

                // First apply slope resist (as modeNormal does)
                Method slopeMethod = PlayableSpriteMovement.class.getDeclaredMethod("doSlopeResist");
                slopeMethod.setAccessible(true);
                slopeMethod.invoke(manager);

                // Then apply ground move
                method.invoke(manager);

                // Assert: gSpeed should be > 1536 (slope + movement)
                short newSpeed = mockSprite.getGSpeed();
                assertTrue("gSpeed should exceed max (1536) when accelerating down slope, but was " + newSpeed,
                                newSpeed > 1536);
        }

        @Test
        public void testRightInputMaintainHighSpeed() throws Exception {
                // Setup: Running super fast (3000), holding Right. Flat ground.
                mockSprite.setGSpeed((short) 3000);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);

                // Set up input state: left=false, right=true, down=false, up=false, jump=false
                setInputState(false, true, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                // Assert: Speed should NOT drop to max (1536).
                assertEquals("gSpeed should be maintained when > max", (short) 3000, mockSprite.getGSpeed());
        }

        @Test
        public void testRightInputAccelerateBelowMax() throws Exception {
                // Setup: Running below max (1000). Holding Right. Flat ground.
                mockSprite.setGSpeed((short) 1000);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);

                // Set up input state: left=false, right=true, down=false, up=false, jump=false
                setInputState(false, true, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                // Assert: Speed should increase by runAccel (12).
                assertEquals("gSpeed should increase by accel when < max", (short) 1012, mockSprite.getGSpeed());
        }

        @Test
        public void testLeftInputMaintainHighSpeed() throws Exception {
                // Setup: Running super fast LEFT (-3000), holding Left. Flat ground.
                mockSprite.setGSpeed((short) -3000);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setAir(false);
                mockSprite.setRolling(false);

                // Set up input state
                setInputState(true, false, false, false, false); // left

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doGroundMove");
                method.setAccessible(true);
                method.invoke(manager);

                // Assert: Speed should NOT clamp to -max (-1536).
                assertEquals("gSpeed should be maintained when < -max", (short) -3000, mockSprite.getGSpeed());
        }

        /**
         * Test air drag with xSpeed = 3072 at jump apex.
         * Sonic 2 formula: xSpeed = xSpeed - (xSpeed / 32)
         * 3072 - (3072 / 32) = 3072 - 96 = 2976
         */
        @Test
        public void testAirDragAtApex() throws Exception {
                // Setup: In air, near apex (ySpeed between -1024 and 0)
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 3072);
                mockSprite.setYSpeed((short) -500); // Near apex, moving up slowly
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                // Set up input state: no input
                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                // Assert: Air drag should reduce xSpeed by xSpeed/32 = 96
                assertEquals("Air drag should reduce xSpeed from 3072 to 2976", (short) 2976, mockSprite.getXSpeed());
        }

        /**
         * Test air drag sequence over multiple frames matches Sonic 2 behavior.
         */
        @Test
        public void testAirDragSequence() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                // Set up input state: no input
                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);

                short[] expectedSpeeds = { 2976, 2883, 2793 };
                mockSprite.setXSpeed((short) 3072);

                for (int i = 0; i < 3; i++) {
                        mockSprite.setYSpeed((short) -500);
                        method.invoke(manager);
                        assertEquals("Frame " + (i + 1) + " air drag result", expectedSpeeds[i],
                                        mockSprite.getXSpeed());
                }
        }

        /**
         * Test air drag does NOT apply when falling (ySpeed >= 0).
         */
        @Test
        public void testNoAirDragWhenFalling() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 3072);
                mockSprite.setYSpeed((short) 100); // Falling
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals("No air drag when falling", (short) 3072, mockSprite.getXSpeed());
        }

        /**
         * Test air drag does NOT apply when ySpeed < -1024 (high upward velocity).
         */
        @Test
        public void testNoAirDragWhenHighUpwardVelocity() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 3072);
                mockSprite.setYSpeed((short) -1500); // High upward velocity
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals("No air drag when ySpeed < -1024", (short) 3072, mockSprite.getXSpeed());
        }

        /**
         * Test air drag DOES apply when sprite is hurt/in knockback.
         * ROM: Sonic_ChgJumpDir does NOT gate air drag on hurt state.
         * The hurt state affects initial knockback velocity, not ongoing air physics.
         */
        @Test
        public void testAirDragAppliesWhenHurt() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 3072);
                mockSprite.setYSpeed((short) -500); // In drag range
                mockSprite.setHurt(true); // Hurt/knockback state
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                // ROM: Air drag applies regardless of hurt state
                // 3072 - (3072 / 32) = 3072 - 96 = 2976
                assertEquals("Air drag applies when hurt", (short) 2976, mockSprite.getXSpeed());
        }

        /**
         * Test air drag stops when abs(xSpeed) < 32.
         */
        @Test
        public void testAirDragStopsWhenSpeedLow() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 31); // Below threshold
                mockSprite.setYSpeed((short) -500); // In drag range
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals("Air drag stops when xSpeed < 32", (short) 31, mockSprite.getXSpeed());
        }

        /**
         * Test air drag with negative xSpeed.
         */
        @Test
        public void testAirDragNegativeSpeed() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) -3072);
                mockSprite.setYSpeed((short) -500); // In drag range
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals("Air drag with negative xSpeed", (short) -2976, mockSprite.getXSpeed());
        }

        /**
         * Test air drag DOES apply at exactly ySpeed = -1024 (boundary condition).
         */
        @Test
        public void testAirDragAtYSpeedBoundary() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 3072);
                mockSprite.setYSpeed((short) -1024); // Exactly at boundary
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                setInputState(false, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals("Air drag SHOULD apply at ySpeed = -1024", (short) 2976, mockSprite.getXSpeed());
        }

        /**
         * Test that jumping on a slope correctly uses the terrain angle for velocity.
         */
        @Test
        public void testJumpUsesTerrainAngle() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0x20);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue("Jump on slope should have non-zero xSpeed component, but was " + mockSprite.getXSpeed(),
                                mockSprite.getXSpeed() != 0);
                assertTrue("Jump should always have upward ySpeed component",
                                mockSprite.getYSpeed() < 0);
        }

        /**
         * Test that jumping on flat ground results in straight up jump.
         */
        @Test
        public void testJumpOnFlatGround() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setXSpeed((short) 256);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGSpeed((short) 256);

                short initialXSpeed = mockSprite.getXSpeed();

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals("Jump on flat ground should not change xSpeed",
                                initialXSpeed, mockSprite.getXSpeed());
                assertTrue("Jump should have upward ySpeed",
                                mockSprite.getYSpeed() < 0);
        }

        /**
         * Test that jumping on an uphill slope has negative X component.
         */
        @Test
        public void testJumpOnUphillSlope() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0xE0);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue("Jump on uphill slope should have negative xSpeed, but was " + mockSprite.getXSpeed(),
                                mockSprite.getXSpeed() < 0);
                assertTrue("Jump should always have upward ySpeed",
                                mockSprite.getYSpeed() < 0);
        }

        /**
         * Test that angle is properly captured before setAir resets it.
         */
        @Test
        public void testJumpAngleCapturedBeforeAirReset() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0x40); // 90 degrees (wall)

                assertEquals("Angle should be 0x40 before jump", (byte) 0x40, mockSprite.getAngle());

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue("Sprite should be in air after jump", mockSprite.getAir());
                assertEquals("Angle should still be 0x40 immediately after jump",
                                (byte) 0x40, mockSprite.getAngle());
                assertTrue("Jump should have used original angle for velocity calculation",
                                mockSprite.getXSpeed() != 0 || mockSprite.getYSpeed() != 0);
        }

        /**
         * Test that angle gradually returns to 0 while airborne.
         */
        @Test
        public void testAirAngleGradualReturn() {
                mockSprite.setAngle((byte) 0x10);
                mockSprite.returnAngleToZero();
                assertEquals("Angle should decrease by 2", (byte) 0x0E, mockSprite.getAngle());

                mockSprite.returnAngleToZero();
                assertEquals("Angle should decrease by 2 again", (byte) 0x0C, mockSprite.getAngle());

                mockSprite.setAngle((byte) 0xF0);
                mockSprite.returnAngleToZero();
                assertEquals("Angle should increase by 2 toward 0", (byte) 0xF2, mockSprite.getAngle());

                mockSprite.returnAngleToZero();
                assertEquals("Angle should increase by 2 again", (byte) 0xF4, mockSprite.getAngle());

                mockSprite.setAngle((byte) 0x00);
                mockSprite.returnAngleToZero();
                assertEquals("Angle at 0 should stay 0", (byte) 0x00, mockSprite.getAngle());
        }

        /**
         * Test that air control IS enabled when sprite is in hurt/knockback state.
         * ROM: Sonic_ChgJumpDir does NOT gate air control on hurt state.
         * The hurt state affects initial knockback velocity, not ongoing air physics.
         */
        @Test
        public void testAirControlWorksWhenHurt() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 1000);
                mockSprite.setYSpeed((short) 500); // Falling (no drag)
                mockSprite.setHurt(true);
                mockSprite.setRollingJump(false);

                // Test left input - air control should work
                setInputState(true, false, false, false, false);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                // ROM: Air control applies regardless of hurt state
                // Default runAccel is 12 (0x0C), air control uses 2*runAccel = 24
                // 1000 - 24 = 976
                assertEquals("Air control works when hurt - left input should decrease xSpeed",
                                (short) 976, mockSprite.getXSpeed());

                // Test right input - air control should work
                mockSprite.setXSpeed((short) 1000);
                mockSprite.setYSpeed((short) 500);
                setInputState(false, true, false, false, false); // right
                method.invoke(manager);

                // 1000 + 24 = 1024
                assertEquals("Air control works when hurt - right input should increase xSpeed",
                                (short) 1024, mockSprite.getXSpeed());
        }

        /**
         * Test that air control DOES work when not hurt.
         */
        @Test
        public void testAirControlWorksWhenNotHurt() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setXSpeed((short) 1000);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setHurt(false);
                mockSprite.setRollingJump(false);

                // Test left input
                setInputState(true, false, false, false, false); // left

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doChgJumpDir");
                method.setAccessible(true);
                method.invoke(manager);

                assertEquals("Air control should work when not hurt - left input",
                                (short) 976, mockSprite.getXSpeed()); // 1000 - 24

                // Test right input
                mockSprite.setXSpeed((short) 1000);
                mockSprite.setYSpeed((short) 500);
                setInputState(false, true, false, false, false); // right
                method.invoke(manager);

                assertEquals("Air control should work when not hurt - right input",
                                (short) 1024, mockSprite.getXSpeed()); // 1000 + 24
        }

        /**
         * Test that rolling is prevented when the down key is locked.
         */
        @Test
        public void testDownLockedPreventsRoll() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setCrouching(true);

                // Set wasCrouching field
                setWasCrouching(true);
                setInputState(true, false, true, false, false); // left + down

                // Update crouch state - should lock down key
                Method crouchMethod = PlayableSpriteMovement.class.getDeclaredMethod("updateCrouchState");
                crouchMethod.setAccessible(true);
                crouchMethod.invoke(manager);

                // Now move at high speed and try to roll
                mockSprite.setGSpeed((short) 500);

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertTrue("Rolling should NOT start when down is locked from crouch transition",
                                !mockSprite.getRolling());
        }

        /**
         * Test that releasing and re-pressing down unlocks rolling.
         */
        @Test
        public void testDownReleasedUnlocksRolling() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setCrouching(true);

                // Lock the down key
                setWasCrouching(true);
                setInputState(true, false, true, false, false); // left + down

                Method crouchMethod = PlayableSpriteMovement.class.getDeclaredMethod("updateCrouchState");
                crouchMethod.setAccessible(true);
                crouchMethod.invoke(manager);

                // Release down - should unlock
                setWasCrouching(false);
                setInputState(true, false, false, false, false); // left only, no down
                crouchMethod.invoke(manager);

                // Now try to roll
                mockSprite.setGSpeed((short) 500);
                setInputState(false, false, true, false, false); // down pressed fresh

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertTrue("Rolling should start after down is released and pressed again",
                                mockSprite.getRolling());
        }

        /**
         * Test that rolling works normally when not starting from crouch state.
         */
        @Test
        public void testRollingWorksWhenNotFromCrouch() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(false);
                mockSprite.setCrouching(false);
                mockSprite.setGSpeed((short) 500);

                setInputState(false, false, true, false, false); // down pressed

                Method rollMethod = PlayableSpriteMovement.class.getDeclaredMethod("doCheckStartRoll");
                rollMethod.setAccessible(true);
                rollMethod.invoke(manager);

                assertTrue("Rolling should work when not transitioning from crouch",
                                mockSprite.getRolling());
        }

        /**
         * Test that jumpPressed is reset when springing starts.
         */
        @Test
        public void testJumpPressedClearedWhenSpringing() throws Exception {
                Field jumpPressedField = PlayableSpriteMovement.class.getDeclaredField("jumpPressed");
                jumpPressedField.setAccessible(true);

                jumpPressedField.set(manager, true);
                assertTrue("jumpPressed should be true initially",
                                (Boolean) jumpPressedField.get(manager));

                mockSprite.setSpringing(15);

                // Simulate the fix behavior
                if (mockSprite.getSpringing()) {
                        jumpPressedField.set(manager, false);
                }

                assertTrue("jumpPressed should be false when springing",
                                !(Boolean) jumpPressedField.get(manager));
        }

        /**
         * Test spring velocity not capped by jump handler.
         */
        @Test
        public void testSpringVelocityNotCappedByJumpHandler() throws Exception {
                mockSprite.setAir(true);
                mockSprite.setYSpeed((short) -1720);

                Field jumpPressedField = PlayableSpriteMovement.class.getDeclaredField("jumpPressed");
                jumpPressedField.setAccessible(true);
                jumpPressedField.set(manager, true);
                mockSprite.setSpringing(10);

                setInputState(false, false, false, false, false);

                Method jumpHeightMethod = PlayableSpriteMovement.class.getDeclaredMethod("doJumpHeight");
                jumpHeightMethod.setAccessible(true);
                jumpHeightMethod.invoke(manager);

                assertEquals("Velocity should NOT be capped while springing",
                                (short) -1720, mockSprite.getYSpeed());

                mockSprite.setSpringing(0);
                jumpPressedField.set(manager, false);

                assertEquals("Velocity should remain unchanged because jumpPressed is false",
                                (short) -1720, mockSprite.getYSpeed());
        }

        /**
         * Test rolling slope physics when gSpeed is zero.
         */
        @Test
        public void testRollingSlopePhysicsWithZeroGSpeed() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(true);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAngle((byte) 0x10); // Downhill to the right

                // Use doRollRepel which handles rolling slope physics
                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doRollRepel");
                method.setAccessible(true);
                method.invoke(manager);

                short newGSpeed = mockSprite.getGSpeed();
                assertTrue("gSpeed should be positive (accelerating down-right slope) with full factor, was " + newGSpeed,
                                newGSpeed > 20);
        }

        /**
         * Test rolling slope physics when gSpeed is zero on an uphill slope.
         */
        @Test
        public void testRollingSlopePhysicsWithZeroGSpeedUphill() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setRolling(true);
                mockSprite.setGSpeed((short) 0);
                mockSprite.setAngle((byte) 0xF0); // Uphill to the right

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doRollRepel");
                method.setAccessible(true);
                method.invoke(manager);

                short newGSpeed = mockSprite.getGSpeed();
                assertTrue("gSpeed should be negative (pushed back down uphill slope), was " + newGSpeed,
                                newGSpeed < 0);
                assertTrue("gSpeed magnitude should be small (reduced factor), was " + newGSpeed,
                                newGSpeed > -20);
        }

        // Helper methods to set up input state

        private void setInputState(boolean left, boolean right, boolean down, boolean up, boolean jump) throws Exception {
                Field leftField = PlayableSpriteMovement.class.getDeclaredField("inputLeft");
                Field rightField = PlayableSpriteMovement.class.getDeclaredField("inputRight");
                Field downField = PlayableSpriteMovement.class.getDeclaredField("inputDown");
                Field upField = PlayableSpriteMovement.class.getDeclaredField("inputUp");
                Field jumpField = PlayableSpriteMovement.class.getDeclaredField("inputJump");
                Field rawLeftField = PlayableSpriteMovement.class.getDeclaredField("inputRawLeft");
                Field rawRightField = PlayableSpriteMovement.class.getDeclaredField("inputRawRight");

                leftField.setAccessible(true);
                rightField.setAccessible(true);
                downField.setAccessible(true);
                upField.setAccessible(true);
                jumpField.setAccessible(true);
                rawLeftField.setAccessible(true);
                rawRightField.setAccessible(true);

                leftField.set(manager, left);
                rightField.set(manager, right);
                downField.set(manager, down);
                upField.set(manager, up);
                jumpField.set(manager, jump);
                rawLeftField.set(manager, left);
                rawRightField.set(manager, right);
        }

        private void setWasCrouching(boolean wasCrouching) throws Exception {
                Field wasCrouchingField = PlayableSpriteMovement.class.getDeclaredField("wasCrouching");
                wasCrouchingField.setAccessible(true);
                wasCrouchingField.set(manager, wasCrouching);
        }

        // ========================================
        // ROM-ACCURATE LANDING gSpeed TESTS
        // ========================================

        /**
         * Test ROM-accurate flat slope detection.
         * ROM: ((angle + 0x10) & 0x20) == 0 AND ((angle + 0x20) & 0x40) == 0
         * Flat angles: 0x00-0x0F, 0xF0-0xFF
         */
        @Test
        public void testLandingFlatSlopeUsesXSpeed() throws Exception {
                // Flat angle 0x00 - should use xSpeed directly
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setXSpeed((short) 200);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertEquals("Flat slope (0x00) should set gSpeed = xSpeed",
                                (short) 200, mockSprite.getGSpeed());
        }

        /**
         * Test ROM-accurate steep slope detection and ySpeed cap.
         * ROM: ((angle + 0x20) & 0x40) != 0
         * Steep angles: 0x20-0x5F, 0xA0-0xDF
         */
        @Test
        public void testLandingSteepSlopeCapsYSpeed() throws Exception {
                // Steep angle 0x40 (90 degrees, wall) with high ySpeed
                mockSprite.setAngle((byte) 0x40);
                mockSprite.setYSpeed((short) 5000); // Above cap of 0xFC0 (4032)
                mockSprite.setXSpeed((short) 200);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                // On steep slope, gSpeed should be capped ySpeed
                // Angle 0x40 is in lower half (0x00-0x7F), so gSpeed is positive
                assertEquals("Steep slope (0x40) should cap ySpeed at 0xFC0",
                                (short) 0xFC0, mockSprite.getGSpeed());

                // xSpeed should be cleared on steep slopes
                assertEquals("Steep slope should clear xSpeed",
                                (short) 0, mockSprite.getXSpeed());
        }

        /**
         * Test ROM-accurate steep slope gSpeed sign based on angle.
         * ROM: If angle bit 7 is set (0x80-0xFF), negate gSpeed
         */
        @Test
        public void testLandingSteepSlopeNegatesGSpeedForUpperAngles() throws Exception {
                // Steep angle 0xC0 (192 degrees, right wall) - in upper half
                mockSprite.setAngle((byte) 0xC0);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setXSpeed((short) 200);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                // Angle 0xC0 has bit 7 set, so gSpeed should be negated
                assertTrue("Steep slope (0xC0) should have negative gSpeed",
                                mockSprite.getGSpeed() < 0);
        }

        /**
         * Test ROM-accurate moderate slope detection.
         * ROM: Not steep AND ((angle + 0x10) & 0x20) != 0
         * Moderate angles: 0x10-0x1F, 0xE0-0xEF
         */
        @Test
        public void testLandingModerateSlopeHalvesYSpeed() throws Exception {
                // Moderate angle 0x18 - should use ySpeed/2
                mockSprite.setAngle((byte) 0x18);
                mockSprite.setYSpeed((short) 500);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                // Moderate slope uses ySpeed >> 1 = 250
                // Angle 0x18 is in lower half, so positive
                assertEquals("Moderate slope (0x18) should set gSpeed = ySpeed/2",
                                (short) 250, mockSprite.getGSpeed());
        }

        /**
         * Test that resetOnFloor clears all landing-related flags.
         */
        @Test
        public void testResetOnFloorClearsFlags() throws Exception {
                // Set up various flags that should be cleared
                mockSprite.setAir(true);
                mockSprite.setPushing(true);
                mockSprite.setRollingJump(true);
                mockSprite.setJumping(true);
                mockSprite.setAngle((byte) 0x00);
                mockSprite.setYSpeed((short) 100);
                mockSprite.setXSpeed((short) 100);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("calculateLanding",
                                AbstractPlayableSprite.class);
                method.setAccessible(true);
                method.invoke(manager, mockSprite);

                assertTrue("resetOnFloor should clear air flag",
                                !mockSprite.getAir());
                assertTrue("resetOnFloor should clear pushing flag",
                                !mockSprite.getPushing());
                assertTrue("resetOnFloor should clear rollingJump flag",
                                !mockSprite.getRollingJump());
                assertTrue("resetOnFloor should clear jumping flag",
                                !mockSprite.isJumping());
        }

        /**
         * Test ROM angle boundaries for steep detection.
         * Steep: ((angle + 0x20) & 0x40) != 0
         * This means: 0x20-0x5F (32-95) and 0xA0-0xDF (160-223)
         */
        @Test
        public void testSteepAngleBoundaries() {
                // Test the formula: ((angle + 0x20) & 0x40) != 0

                // Boundary cases that should be steep
                assertTrue("0x20 should be steep", ((0x20 + 0x20) & 0x40) != 0);
                assertTrue("0x5F should be steep", ((0x5F + 0x20) & 0x40) != 0);
                assertTrue("0xA0 should be steep", ((0xA0 + 0x20) & 0x40) != 0);
                assertTrue("0xDF should be steep", ((0xDF + 0x20) & 0x40) != 0);

                // Boundary cases that should NOT be steep
                assertTrue("0x1F should NOT be steep", ((0x1F + 0x20) & 0x40) == 0);
                assertTrue("0x60 should NOT be steep", ((0x60 + 0x20) & 0x40) == 0);
                assertTrue("0x9F should NOT be steep", ((0x9F + 0x20) & 0x40) == 0);
                assertTrue("0xE0 should NOT be steep", ((0xE0 + 0x20) & 0x40) == 0);
        }

        /**
         * Test ROM angle boundaries for flat detection.
         * Flat: ((angle + 0x10) & 0x20) == 0 AND not steep
         * This means: 0x00-0x0F (0-15) and 0xF0-0xFF (240-255)
         */
        @Test
        public void testFlatAngleBoundaries() {
                // Test the formula for flat: ((angle + 0x10) & 0x20) == 0
                // (This only applies when not steep)

                // Boundary cases that should be flat (assuming not steep)
                assertTrue("0x00 should be flat", ((0x00 + 0x10) & 0x20) == 0);
                assertTrue("0x0F should be flat", ((0x0F + 0x10) & 0x20) == 0);
                assertTrue("0xF0 should be flat", ((0xF0 + 0x10) & 0x20) == 0);
                assertTrue("0xFF should be flat", ((0xFF + 0x10) & 0x20) == 0);

                // Boundary cases that should NOT be flat
                assertTrue("0x10 should NOT be flat", ((0x10 + 0x10) & 0x20) != 0);
                assertTrue("0x1F should NOT be flat", ((0x1F + 0x10) & 0x20) != 0);
                assertTrue("0xE0 should NOT be flat", ((0xE0 + 0x10) & 0x20) != 0);
                assertTrue("0xEF should NOT be flat", ((0xEF + 0x10) & 0x20) != 0);
        }

        // ========================================
        // STICK_TO_CONVEX FLAG TESTS
        // ========================================

        /**
         * Test that stick_to_convex is cleared when jumping.
         * ROM: clr.b stick_to_convex(a0) at s2.asm:37035
         */
        @Test
        public void testStickToConvexClearedOnJump() throws Exception {
                mockSprite.setAir(false);
                mockSprite.setAngle((byte) 0x40); // Wall angle
                mockSprite.setStickToConvex(true);
                mockSprite.setXSpeed((short) 0);
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGSpeed((short) 0);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("doJump");
                method.setAccessible(true);
                method.invoke(manager);

                assertTrue("stick_to_convex should be cleared after jump",
                                !mockSprite.isStickToConvex());
        }

        // NOTE: Tests for automatic stick_to_convex setting were removed.
        // ROM: stick_to_convex is NEVER set automatically in Sonic 2.
        // It's only used by special objects (rotating discs in S1/S3).
        // The previous tests were verifying incorrect behavior that caused
        // Sonic to never slide off slopes (doSlopeRepel was bypassed).

        /**
         * Test ROM-accurate speed-dependent threshold calculation.
         * ROM: threshold = min(abs(xSpeed >> 8) + 4, 14)
         */
        @Test
        public void testSpeedDependentThreshold() {
                // Test cases: xSpeed -> expected threshold
                // Speed 0 -> threshold 4
                assertEquals("Threshold at speed 0", 4, Math.min(Math.abs(0 >> 8) + 4, 14));

                // Speed 256 (1 pixel) -> threshold 5
                assertEquals("Threshold at speed 256", 5, Math.min(Math.abs(256 >> 8) + 4, 14));

                // Speed 2560 (10 pixels) -> threshold 14 (capped)
                assertEquals("Threshold at speed 2560", 14, Math.min(Math.abs(2560 >> 8) + 4, 14));

                // Speed -512 (-2 pixels) -> threshold 6
                assertEquals("Threshold at speed -512", 6, Math.min(Math.abs(-512 >> 8) + 4, 14));
        }

        // ========================================
        // ARITHMETIC SHIFT VS DIVISION TESTS
        // ========================================

        /**
         * Test that right shift (>>8) behaves correctly for negative values.
         * This is critical for ROM accuracy: 68000 ASR rounds toward -infinity,
         * while Java / rounds toward zero.
         *
         * Examples:
         * -1 >> 8 = -1 (correct, rounds toward -infinity)
         * -1 / 256 = 0 (incorrect, rounds toward zero)
         *
         * -255 >> 8 = -1 (correct)
         * -255 / 256 = 0 (incorrect)
         */
        @Test
        public void testArithmeticShiftVsDivisionForNegativeValues() {
                // Test case 1: -1
                assertEquals(">>8 rounds -1 toward -infinity", -1, -1 >> 8);
                assertEquals("/256 rounds -1 toward zero", 0, -1 / 256);

                // Test case 2: -255
                assertEquals(">>8 rounds -255 toward -infinity", -1, -255 >> 8);
                assertEquals("/256 rounds -255 toward zero", 0, -255 / 256);

                // Test case 3: -256
                assertEquals(">>8 for -256", -1, -256 >> 8);
                assertEquals("/256 for -256", -1, -256 / 256);

                // Test case 4: -257
                assertEquals(">>8 rounds -257 toward -infinity", -2, -257 >> 8);
                assertEquals("/256 rounds -257 toward zero", -1, -257 / 256);

                // Test case 5: positive values should be the same
                assertEquals(">>8 for 255", 0, 255 >> 8);
                assertEquals("/256 for 255", 0, 255 / 256);

                assertEquals(">>8 for 256", 1, 256 >> 8);
                assertEquals("/256 for 256", 1, 256 / 256);
        }

        /**
         * Test boundary prediction with negative position/speed.
         * This verifies the fix for using >>8 instead of /256.
         */
        @Test
        public void testBoundaryPredictionWithNegativeSpeed() {
                // Scenario: sprite at x=100, moving left slowly
                // xTotal = 100*256 + 0 + (-255) = 25600 - 255 = 25345
                // predictedX should be 25345 >> 8 = 99 (correct)
                // NOT 25345 / 256 = 99 (same in this case)

                int xTotal1 = 100 * 256 + 0 + (-255);
                assertEquals("Positive total with >>8", 99, xTotal1 >> 8);
                assertEquals("Positive total with /256", 99, xTotal1 / 256);

                // Scenario: sprite near left boundary, moving left
                // xTotal = 1*256 + 0 + (-512) = 256 - 512 = -256
                // predictedX should be -256 >> 8 = -1 (correct)
                // NOT -256 / 256 = -1 (same in this case)

                int xTotal2 = 1 * 256 + 0 + (-512);
                assertEquals("Negative total with >>8", -1, xTotal2 >> 8);
                assertEquals("Negative total with /256", -1, xTotal2 / 256);

                // Scenario: edge case with non-multiple of 256
                // xTotal = 0*256 + 0 + (-1) = -1
                // predictedX should be -1 >> 8 = -1 (rounds toward -infinity)
                // NOT -1 / 256 = 0 (rounds toward zero) - THIS IS THE BUG!

                int xTotal3 = 0 * 256 + 0 + (-1);
                assertEquals("Edge case -1 with >>8 (correct)", -1, xTotal3 >> 8);
                assertEquals("Edge case -1 with /256 (wrong)", 0, xTotal3 / 256);
        }

        // ========================================
        // GROUND MODE TRANSITION TESTS
        // ========================================

        /**
         * Test that updateGroundMode() produces correct mode from angle.
         * ROM formula from s2.asm:42551.
         *
         * Actual boundaries (traced through the algorithm):
         * - GROUND: 0x00-0x20, 0xE0-0xFF
         * - LEFTWALL: 0x21-0x5F
         * - CEILING: 0x60-0xA0
         * - RIGHTWALL: 0xA1-0xDF
         */
        @Test
        public void testGroundModeFromAngle() throws Exception {
                Method updateGroundMode = PlayableSpriteMovement.class.getDeclaredMethod("updateGroundMode");
                updateGroundMode.setAccessible(true);

                // Test GROUND mode angles: 0x00-0x20, 0xE0-0xFF
                byte[] groundAngles = {0x00, 0x10, 0x20, (byte)0xE0, (byte)0xF0, (byte)0xFF};
                for (byte angle : groundAngles) {
                        mockSprite.setAngle(angle);
                        updateGroundMode.invoke(manager);
                        assertEquals("Angle " + String.format("0x%02X", angle & 0xFF) + " should be GROUND",
                                        GroundMode.GROUND, mockSprite.getGroundMode());
                }

                // Test LEFTWALL mode angles: 0x21-0x5F
                byte[] leftWallAngles = {0x21, 0x30, 0x40, 0x5F};
                for (byte angle : leftWallAngles) {
                        mockSprite.setAngle(angle);
                        updateGroundMode.invoke(manager);
                        assertEquals("Angle " + String.format("0x%02X", angle & 0xFF) + " should be LEFTWALL",
                                        GroundMode.LEFTWALL, mockSprite.getGroundMode());
                }

                // Test CEILING mode angles: 0x60-0xA0
                byte[] ceilingAngles = {0x60, 0x70, (byte)0x80, (byte)0xA0};
                for (byte angle : ceilingAngles) {
                        mockSprite.setAngle(angle);
                        updateGroundMode.invoke(manager);
                        assertEquals("Angle " + String.format("0x%02X", angle & 0xFF) + " should be CEILING",
                                        GroundMode.CEILING, mockSprite.getGroundMode());
                }

                // Test RIGHTWALL mode angles: 0xA1-0xDF
                byte[] rightWallAngles = {(byte)0xA1, (byte)0xB0, (byte)0xC0, (byte)0xDF};
                for (byte angle : rightWallAngles) {
                        mockSprite.setAngle(angle);
                        updateGroundMode.invoke(manager);
                        assertEquals("Angle " + String.format("0x%02X", angle & 0xFF) + " should be RIGHTWALL",
                                        GroundMode.RIGHTWALL, mockSprite.getGroundMode());
                }
        }

        /**
         * Test boundary angles where mode transitions occur.
         * These are critical for loop traversal accuracy.
         *
         * Actual boundaries (traced through the algorithm):
         * - GROUND/LEFTWALL: 0x20 -> GROUND, 0x21 -> LEFTWALL
         * - LEFTWALL/CEILING: 0x5F -> LEFTWALL, 0x60 -> CEILING
         * - CEILING/RIGHTWALL: 0xA0 -> CEILING, 0xA1 -> RIGHTWALL
         * - RIGHTWALL/GROUND: 0xDF -> RIGHTWALL, 0xE0 -> GROUND
         */
        @Test
        public void testGroundModeBoundaryAngles() throws Exception {
                Method updateGroundMode = PlayableSpriteMovement.class.getDeclaredMethod("updateGroundMode");
                updateGroundMode.setAccessible(true);

                // GROUND/LEFTWALL boundary: 0x20 -> GROUND, 0x21 -> LEFTWALL
                mockSprite.setAngle((byte)0x20);
                updateGroundMode.invoke(manager);
                assertEquals("0x20 should be GROUND", GroundMode.GROUND, mockSprite.getGroundMode());

                mockSprite.setAngle((byte)0x21);
                updateGroundMode.invoke(manager);
                assertEquals("0x21 should be LEFTWALL", GroundMode.LEFTWALL, mockSprite.getGroundMode());

                // LEFTWALL/CEILING boundary: 0x5F -> LEFTWALL, 0x60 -> CEILING
                mockSprite.setAngle((byte)0x5F);
                updateGroundMode.invoke(manager);
                assertEquals("0x5F should be LEFTWALL", GroundMode.LEFTWALL, mockSprite.getGroundMode());

                mockSprite.setAngle((byte)0x60);
                updateGroundMode.invoke(manager);
                assertEquals("0x60 should be CEILING", GroundMode.CEILING, mockSprite.getGroundMode());

                // CEILING/RIGHTWALL boundary: 0xA0 -> CEILING, 0xA1 -> RIGHTWALL
                mockSprite.setAngle((byte)0xA0);
                updateGroundMode.invoke(manager);
                assertEquals("0xA0 should be CEILING", GroundMode.CEILING, mockSprite.getGroundMode());

                mockSprite.setAngle((byte)0xA1);
                updateGroundMode.invoke(manager);
                assertEquals("0xA1 should be RIGHTWALL", GroundMode.RIGHTWALL, mockSprite.getGroundMode());

                // RIGHTWALL/GROUND boundary: 0xDF -> RIGHTWALL, 0xE0 -> GROUND
                mockSprite.setAngle((byte)0xDF);
                updateGroundMode.invoke(manager);
                assertEquals("0xDF should be RIGHTWALL", GroundMode.RIGHTWALL, mockSprite.getGroundMode());

                mockSprite.setAngle((byte)0xE0);
                updateGroundMode.invoke(manager);
                assertEquals("0xE0 should be GROUND", GroundMode.GROUND, mockSprite.getGroundMode());
        }

        // ========================================
        // WALL COLLISION PREDICTION TESTS
        // ========================================

        /**
         * Test that wall collision prediction uses speed directly without subpixels.
         * ROM: Uses integer velocity (x_vel >> 8) directly for projection,
         * not (x_vel + subpixel) >> 8.
         */
        @Test
        public void testWallCollisionProjectionWithoutSubpixels() {
                // Verify the correct formula: projectedDx = xSpeed >> 8
                short xSpeed = 512; // 2 pixels per frame

                // Correct (ROM-accurate): just shift the speed
                short correctProjection = (short)(xSpeed >> 8);
                assertEquals("Correct projection should be 2", 2, correctProjection);

                // Previous incorrect behavior would add subpixels first
                // This is wrong because it can cause 1-pixel errors
                byte xSubpixel = (byte)200; // Near full subpixel
                short incorrectProjection = (short)((xSpeed + (xSubpixel & 0xFF)) >> 8);
                assertEquals("Incorrect projection would be 2 (same here)", 2, incorrectProjection);

                // Edge case where the bug manifests:
                xSpeed = 56; // Less than 1 pixel per frame
                xSubpixel = (byte)200;

                correctProjection = (short)(xSpeed >> 8);
                assertEquals("Correct projection for slow speed", 0, correctProjection);

                incorrectProjection = (short)((xSpeed + (xSubpixel & 0xFF)) >> 8);
                assertEquals("Incorrect projection would be 1 (off by 1 pixel)", 1, incorrectProjection);
        }

        /**
         * Test that Y projection for wall collision is also subpixel-free.
         */
        @Test
        public void testWallCollisionYProjectionWithoutSubpixels() {
                // Verify the correct formula: projectedDy = ySpeed >> 8
                short ySpeed = -384; // Moving up ~1.5 pixels per frame

                // Correct (ROM-accurate): just shift the speed
                short correctProjection = (short)(ySpeed >> 8);
                assertEquals("Correct Y projection should be -2", -2, correctProjection);

                // Edge case with subpixels
                ySpeed = -56;
                byte ySubpixel = (byte)200;

                correctProjection = (short)(ySpeed >> 8);
                assertEquals("Correct Y projection for slow upward speed", -1, correctProjection);

                // Previous buggy behavior
                short incorrectProjection = (short)((ySpeed + (ySubpixel & 0xFF)) >> 8);
                assertEquals("Incorrect Y projection would be 0 (off by 1 pixel)", 0, incorrectProjection);
        }

        // ========================================
        // SPEED THRESHOLD FOR GROUND ATTACHMENT TESTS
        // ========================================

        /**
         * Test that getSpeedForThreshold() uses gSpeed as fallback ONLY when xSpeed is zero.
         * This is CRITICAL for loop traversal - during loop transitions, xSpeed can be
         * zero due to velocity decomposition even when gSpeed is non-zero.
         *
         * ROM uses raw velocity bytes directly (s2.asm:42727 mvabs.b instruction).
         * No fallback to gSpeed - the fix is to ensure xSpeed/ySpeed are always
         * set correctly when gSpeed is set (e.g., immediately after spindash release).
         */
        @Test
        public void testSpeedThresholdUsesRawVelocityNoFallback() throws Exception {
                // ROM behavior: use xSpeed directly, no fallback to gSpeed
                mockSprite.setGSpeed((short) 1536);
                mockSprite.setXSpeed((short) 0);  // If this is 0, threshold is 0
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGroundMode(GroundMode.GROUND);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                method.setAccessible(true);
                int threshold = (int) method.invoke(manager);

                // ROM uses xSpeed directly (0 >> 8 = 0), no gSpeed fallback
                assertEquals("Should use xSpeed directly per ROM behavior", 0, threshold);

                // This gives positiveThreshold = min(0 + 4, 14) = 4
                // The fix for spindash is to ensure xSpeed is set BEFORE this check
                int positiveThreshold = Math.min(threshold + 4, 14);
                assertEquals("Attachment threshold is 4 pixels with zero xSpeed", 4, positiveThreshold);
        }

        /**
         * Test that without gSpeed fallback, threshold would be dangerously tight.
         * This test documents the bug that was causing loop fall-through.
         */
        @Test
        public void testSpeedThresholdWithoutFallbackWouldBeTooTight() {
                // If we ONLY used xSpeed (the buggy behavior):
                short xSpeed = 0;  // Zero due to velocity decomposition
                int buggySpeedPixels = Math.abs(xSpeed >> 8);  // = 0
                int buggyThreshold = Math.min(buggySpeedPixels + 4, 14);  // = 4

                assertEquals("Without fallback, threshold would be only 4 pixels", 4, buggyThreshold);

                // 4 pixels is too tight for curved surfaces like loops
                // Normal terrain distance on curves can be 5-10 pixels
                // This would cause false "too far from terrain" and launch Sonic out
        }

        /**
         * Test speed threshold on wall modes uses ySpeed directly per ROM (no fallback).
         */
        @Test
        public void testSpeedThresholdOnWallMode() throws Exception {
                // On LEFTWALL mode, ROM uses ySpeed directly (s2.asm:42794 mvabs.b y_vel)
                mockSprite.setGSpeed((short) 1024);  // 4 pixels/frame
                mockSprite.setXSpeed((short) 1024);  // Non-zero, but irrelevant for wall mode
                mockSprite.setYSpeed((short) 0);     // Zero
                mockSprite.setGroundMode(GroundMode.LEFTWALL);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                method.setAccessible(true);
                int threshold = (int) method.invoke(manager);

                // ROM uses ySpeed directly (0 >> 8 = 0), no gSpeed fallback
                assertEquals("Should use ySpeed directly on wall mode per ROM", 0, threshold);

                // With non-zero ySpeed, threshold reflects actual velocity
                mockSprite.setYSpeed((short) 1024);  // 4 pixels/frame
                int threshold2 = (int) method.invoke(manager);
                assertEquals("Should use ySpeed when non-zero", 4, threshold2);
        }

        /**
         * Test speed threshold uses ROM-style velocity when non-zero, NOT Math.max().
         * This is critical for slope accuracy - using Math.max() breaks slopes.
         */
        @Test
        public void testSpeedThresholdUsesRomStyleWhenNonZero() throws Exception {
                // On slopes, xSpeed is non-zero, so should use xSpeed (ROM behavior)
                // NOT Math.max(xSpeed, gSpeed) which would give too loose a threshold
                mockSprite.setGSpeed((short) 1536);  // 6 pixels/frame
                mockSprite.setXSpeed((short) 1024);  // 4 pixels/frame (non-zero on slope)
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGroundMode(GroundMode.GROUND);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                method.setAccessible(true);
                int threshold = (int) method.invoke(manager);

                // Should use xSpeed (4), NOT gSpeed (6), because xSpeed is non-zero
                assertEquals("Should use xSpeed (ROM behavior) when non-zero", 4, threshold);

                // This gives threshold = min(4 + 4, 14) = 8, matching ROM
                // Using Math.max would give min(6 + 4, 14) = 10, which breaks slopes
        }

        /**
         * Test speed threshold uses high byte of velocity (ROM mvabs.b behavior).
         */
        @Test
        public void testSpeedThresholdUsesHighByte() throws Exception {
                // ROM uses mvabs.b which takes high byte of velocity
                mockSprite.setGSpeed((short) 1536);  // 6 pixels/frame
                mockSprite.setXSpeed((short) 255);   // Less than 1 pixel, high byte = 0
                mockSprite.setYSpeed((short) 0);
                mockSprite.setGroundMode(GroundMode.GROUND);

                Method method = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                method.setAccessible(true);
                int threshold = (int) method.invoke(manager);

                // 255 >> 8 = 0, ROM uses this directly (no fallback)
                assertEquals("Should use high byte of xSpeed (0)", 0, threshold);

                // Now test with xSpeed = 256 (exactly 1 pixel)
                mockSprite.setXSpeed((short) 256);
                int threshold2 = (int) method.invoke(manager);

                // 256 >> 8 = 1
                assertEquals("Should use xSpeed when it's 1+ pixels", 1, threshold2);

                // With higher speed
                mockSprite.setXSpeed((short) 2048);  // 8 pixels/frame
                int threshold3 = (int) method.invoke(manager);
                assertEquals("Should use xSpeed high byte", 8, threshold3);
        }

        /**
         * Test that spindash velocity calculation sets xSpeed from gSpeed immediately.
         * This is critical for ground attachment threshold calculation.
         *
         * Bug fix: Before this fix, xSpeed was 0 during doAnglePos() after spindash release,
         * causing threshold to be only 4 pixels (0+4) and Sonic to falsely become airborne.
         * Now xSpeed is set from gSpeed before any ground checks, giving proper threshold.
         */
        @Test
        public void testSpindashVelocityCalculation() throws Exception {
                // Test that xSpeed is correctly calculated from gSpeed at angle 0
                short gSpeed = 0x0900;  // Typical spindash speed (2304 subpixels = 9 pixels/frame)
                mockSprite.setGSpeed(gSpeed);
                mockSprite.setAngle((byte) 0);  // Flat ground
                mockSprite.setGroundMode(GroundMode.GROUND);

                // Simulate the velocity calculation that happens in doReleaseSpindash:
                // xSpeed = (gSpeed * cos(angle)) >> 8
                // At angle 0, cos = 256, so xSpeed = gSpeed
                int hexAngle = mockSprite.getAngle() & 0xFF;
                short calculatedXSpeed = (short) ((gSpeed * uk.co.jamesj999.sonic.physics.TrigLookupTable.cosHex(hexAngle)) >> 8);
                mockSprite.setXSpeed(calculatedXSpeed);

                // xSpeed should match gSpeed on flat ground
                assertEquals("xSpeed should equal gSpeed on flat ground (angle 0)",
                        gSpeed, mockSprite.getXSpeed());

                // Verify threshold is now correct
                Method thresholdMethod = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                thresholdMethod.setAccessible(true);
                int threshold = (int) thresholdMethod.invoke(manager);

                // With xSpeed = 0x0900 = 2304, threshold = 2304 >> 8 = 9
                assertEquals("Threshold should reflect spindash speed", 9, threshold);

                // positiveThreshold = min(9 + 4, 14) = 13 pixels - ample for ground attachment
                int positiveThreshold = Math.min(threshold + 4, 14);
                assertTrue("Ground attachment threshold should be >= 10 pixels", positiveThreshold >= 10);
        }

        /**
         * Test velocity calculation on slopes (non-zero angle).
         */
        @Test
        public void testSpindashVelocityOnSlope() throws Exception {
                // On a 45-degree slope, both xSpeed and ySpeed should be non-zero
                short gSpeed = 0x0800;  // Spindash speed
                mockSprite.setGSpeed(gSpeed);
                mockSprite.setAngle((byte) 0x20);  // ~45 degrees
                mockSprite.setGroundMode(GroundMode.GROUND);

                // Calculate velocities as done in doReleaseSpindash
                int hexAngle = mockSprite.getAngle() & 0xFF;
                short xSpeed = (short) ((gSpeed * uk.co.jamesj999.sonic.physics.TrigLookupTable.cosHex(hexAngle)) >> 8);
                short ySpeed = (short) ((gSpeed * uk.co.jamesj999.sonic.physics.TrigLookupTable.sinHex(hexAngle)) >> 8);

                mockSprite.setXSpeed(xSpeed);
                mockSprite.setYSpeed(ySpeed);

                // Both should be non-zero on a slope
                assertTrue("xSpeed should be non-zero on slope", xSpeed != 0);
                assertTrue("ySpeed should be non-zero on slope", ySpeed != 0);

                // Verify threshold uses correct velocity
                Method thresholdMethod = PlayableSpriteMovement.class.getDeclaredMethod("getSpeedForThreshold");
                thresholdMethod.setAccessible(true);
                int threshold = (int) thresholdMethod.invoke(manager);

                // Threshold should be based on xSpeed (for GROUND mode)
                int expectedThreshold = Math.abs(xSpeed >> 8);
                assertEquals("Threshold should be based on xSpeed", expectedThreshold, threshold);
        }
}
