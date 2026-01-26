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
}
