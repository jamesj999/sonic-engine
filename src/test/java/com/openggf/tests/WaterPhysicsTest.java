package com.openggf.tests;

import com.openggf.game.PhysicsProfile;
import com.openggf.game.rules.GameRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for water physics state transitions and velocity changes.
 * These tests verify the water entry/exit behavior matches original game logic.
 */
@ExtendWith(SingletonResetExtension.class)
public class WaterPhysicsTest {

    private TestablePlayableSprite sprite;

    @BeforeEach
    public void setUp() {
        sprite = new TestablePlayableSprite("test", (short) 100, (short) 100);
    }

    @Test
    public void testWaterEntry_HalvesHorizontalVelocity() {
        // Set initial velocity
        sprite.setXSpeed((short) 1000);
        sprite.setGSpeed((short) 1000);
        sprite.setYSpeed((short) 0);

        // Simulate water entry - move player below water level
        sprite.setTestY((short) 500); // Player at Y=500
        sprite.updateWaterState(400); // Water at Y=400, player is below

        assertTrue(sprite.isInWater(), "Player should be in water");
        assertEquals(500, sprite.getXSpeed(), "XSpeed should be halved");
        assertEquals(1000, sprite.getGSpeed(), "GSpeed should be unchanged on water entry");
    }

    @Test
    public void testWaterEntry_ReducesDownwardVelocity() {
        // Set initial downward velocity
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 400); // Moving down

        // Simulate water entry
        sprite.setTestY((short) 500);
        sprite.updateWaterState(400);

        assertTrue(sprite.isInWater(), "Player should be in water");
        // Downward velocity should be quartered (400 / 4 = 100)
        assertEquals(100, sprite.getYSpeed(), "YSpeed should be quartered when positive");
    }

    @Test
    public void testWaterEntry_QuartersUpwardVelocity() {
        // Set initial upward velocity
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) -400); // Moving up

        // Simulate water entry
        sprite.setTestY((short) 500);
        sprite.updateWaterState(400);

        assertTrue(sprite.isInWater(), "Player should be in water");
        // ROM: asr.w y_vel(a0) twice - divides by 4 unconditionally (both up and down)
        assertEquals(-100, sprite.getYSpeed(), "YSpeed should be quartered (ROM-accurate)");
    }

    @Test
    public void testWaterExit_DoesNotModifyHorizontalVelocity() {
        // Start in water
        sprite.setInWater(true);
        sprite.setXSpeed((short) 500);
        sprite.setGSpeed((short) 500);
        sprite.setYSpeed((short) -200); // Moving up

        // Simulate water exit - move player above water level
        sprite.setTestY((short) 300); // Player at Y=300
        sprite.updateWaterState(400); // Water at Y=400, player is above

        assertFalse(sprite.isInWater(), "Player should be out of water");
        // ROM does NOT modify x_vel on water exit - only top_speed/accel/decel change
        assertEquals(500, sprite.getXSpeed(), "XSpeed should be unchanged (ROM-accurate)");
        assertEquals(500, sprite.getGSpeed(), "GSpeed should be unchanged (ROM-accurate)");
    }

    @Test
    public void testWaterExit_BoostsUpwardVelocity() {
        // Start in water moving up
        sprite.setInWater(true);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) -300); // Moving up

        // Simulate water exit
        sprite.setTestY((short) 300);
        sprite.updateWaterState(400);

        assertFalse(sprite.isInWater(), "Player should be out of water");
        // Upward velocity should be doubled (-300 * 2 = -600)
        assertEquals(-600, sprite.getYSpeed(), "YSpeed should be doubled when exiting upward");
    }

    @Test
    public void s2WaterExitBoostsFastUpwardVelocityAndCaps() {
        sprite.setGameRulesForTest(GameRules.SONIC_2);
        sprite.setInWater(true);
        sprite.setYSpeed((short) -0x990);

        sprite.setTestY((short) 300);
        sprite.updateWaterState(400);

        assertFalse(sprite.isInWater(), "Player should be out of water");
        assertEquals(-0x1000, sprite.getYSpeed(),
                "S2 Sonic_Water doubles fast upward exits before applying the -$1000 cap");
    }

    @Test
    public void s3kWaterExitSkipsFastUpwardBoost() {
        sprite.setGameRulesForTest(GameRules.SONIC_3K);
        sprite.setInWater(true);
        sprite.setYSpeed((short) -0x990);

        sprite.setTestY((short) 300);
        sprite.updateWaterState(400);

        assertFalse(sprite.isInWater(), "Player should be out of water");
        assertEquals(-0x990, sprite.getYSpeed(),
                "S3K Sonic_Water skips the exit boost when upward speed is already below -$400");
    }

    @Test
    public void testWaterExit_SkipsBoostWhenHurt() {
        // ROM: cmpi.b #4,routine(a0) - skip y_vel doubling if hurt (routine=4)
        // Start in water moving up, but in hurt state
        sprite.setInWater(true);
        sprite.setHurt(true);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) -300); // Moving up

        // Simulate water exit
        sprite.setTestY((short) 300);
        sprite.updateWaterState(400);

        assertFalse(sprite.isInWater(), "Player should be out of water");
        // When hurt, y velocity should NOT be doubled (ROM-accurate)
        assertEquals(-300, sprite.getYSpeed(), "YSpeed should be unchanged when hurt (ROM-accurate)");
    }

    @Test
    public void testUnderwaterConstants_ReducedValues() {
        // Not in water
        sprite.setInWater(false);
        short normalAccel = sprite.getRunAccel();
        short normalMax = sprite.getMax();
        short normalJump = sprite.getJump();
        // Use getEffectiveGravity() which returns the net gravity accounting for water state
        // getGravity() returns the BASE gravity used by ObjectMoveAndFall (underwater reduction
        // is applied separately in modeAirborne())
        short normalGravity = sprite.getEffectiveGravity();

        // In water
        sprite.setInWater(true);
        short waterAccel = sprite.getRunAccel();
        short waterMax = sprite.getMax();
        short waterJump = sprite.getJump();
        short waterGravity = sprite.getEffectiveGravity();

        // Verify underwater constants are reduced
        assertEquals(normalAccel / 2, waterAccel, "Underwater accel should be half");
        assertEquals(normalMax / 2, waterMax, "Underwater max should be half");
        assertTrue(waterJump < normalJump, "Underwater jump should be reduced");
        assertTrue(waterGravity < normalGravity, "Underwater gravity should be reduced");
    }

    @Test
    public void directStatusClearPreservesWaterSpeedConstantsUntilRealExit() {
        sprite.setInWater(true);
        short waterAccel = sprite.getRunAccel();
        short waterMax = sprite.getMax();

        sprite.clearUnderwaterStatusPreserveWaterPhysics();

        assertFalse(sprite.isInWater(), "Direct status write should clear Status_Underwater");
        assertEquals(waterAccel, sprite.getRunAccel(),
                "S3K sub_13ECA clears status without restoring Acceleration_P2");
        assertEquals(waterMax, sprite.getMax(),
                "S3K sub_13ECA clears status without restoring Max_speed_P2");
    }

    @Test
    public void directStatusClearDoesNotTriggerSyntheticWaterExit() {
        sprite.setInWater(true);
        sprite.setYSpeed((short) -300);

        sprite.clearUnderwaterStatusPreserveWaterPhysics();
        sprite.setTestY((short) 300);
        sprite.updateWaterState(400);

        assertFalse(sprite.isInWater(), "Player remains above water");
        assertEquals(-300, sprite.getYSpeed(),
                "Status-only clear must not run the water-exit y_vel doubling path");
        assertEquals(6, sprite.getRunAccel(),
                "Underwater Acceleration_P2 stays active until a later real water transition");
    }

    @Test
    public void waterEntryUsesCanonicalConstantsNotCurrentBoostedValues() {
        sprite.applyExternalPhysicsProfile(PhysicsProfile.SONIC_2_SONIC);
        sprite.setSpeedConstantsForTest((short) 24, (short) 128, (short) 0x0C00);
        sprite.setTestY((short) 500);

        sprite.updateWaterState(400);

        assertTrue(sprite.isInWater(), "Player should enter water");
        assertEquals(6, sprite.getRunAccel(),
                "S3K Sonic_Water/Tails_Water writes Acceleration=$6, not half of boosted $18");
        assertEquals(0x0300, sprite.getMax(),
                "S3K Sonic_Water/Tails_Water writes Max_speed=$300, not half of boosted $C00");
    }

    @Test
    public void objectControlledWaterEntrySetsStatusWithoutQuarteringVelocity() {
        sprite.setObjectControlled(true);
        sprite.setYSpeed((short) 0x38);
        sprite.setXSpeed((short) 0x200);
        sprite.setTestY((short) 500);

        sprite.updateWaterStateObjectControlled(400);

        assertTrue(sprite.isInWater(),
                "S3K Tails_Water still sets Status_Underwater while object_control bit 0 is set");
        assertEquals(0x38, sprite.getYSpeed(),
                "object_control path returns before Tails_Water quarters y_vel");
        assertEquals(0x200, sprite.getXSpeed(),
                "object_control path returns before Tails_Water halves x_vel");
        assertEquals(6, sprite.getRunAccel(),
                "Tails_Water writes underwater speed constants before the object_control return");
    }

    @Test
    public void objectControlledWaterExitClearsStatusWithoutDoublingVelocity() {
        sprite.setInWater(true);
        sprite.setObjectControlled(true);
        sprite.setYSpeed((short) -0x200);
        sprite.setTestY((short) 300);

        sprite.updateWaterStateObjectControlled(400);

        assertFalse(sprite.isInWater(),
                "S3K Tails_Water still clears Status_Underwater while object_control bit 0 is set");
        assertEquals(-0x200, sprite.getYSpeed(),
                "object_control path returns before Tails_Water doubles y_vel on exit");
        assertEquals(12, sprite.getRunAccel(),
                "Tails_Water restores normal speed constants before the object_control return");
    }

    @Test
    public void testNoTransition_WhenStayingAboveWater() {
        // Player above water
        sprite.setTestY((short) 300);
        sprite.setXSpeed((short) 1000);

        // Update water state - player stays above
        sprite.updateWaterState(400);
        sprite.updateWaterState(400); // Second update should not change anything

        assertFalse(sprite.isInWater(), "Player should not be in water");
        assertEquals(1000, sprite.getXSpeed(), "Velocity should be unchanged");
    }

    @Test
    public void testNoTransition_WhenStayingUnderwater() {
        // Player starts underwater
        sprite.setInWater(true);
        sprite.setTestY((short) 500);
        sprite.setXSpeed((short) 500);

        // Simulate frame update while staying underwater
        sprite.updateWaterState(400);

        assertTrue(sprite.isInWater(), "Player should still be in water");
        assertEquals(500, sprite.getXSpeed(), "Velocity should be unchanged");
    }

    @Test
    public void testHurtKnockback_NormalVelocities() {
        // Player is not underwater
        sprite.setInWater(false);
        sprite.setTestY((short) 300);

        // Apply hurt from the left (sourceX < player center)
        sprite.applyHurt(0);

        assertTrue(sprite.isHurt(), "Player should be hurt");
        // Normal knockback: xSpeed = 0x200 (512), ySpeed = -0x400 (-1024)
        assertEquals(0x200, sprite.getXSpeed(), "Normal hurt xSpeed should be 0x200");
        assertEquals(-0x400, sprite.getYSpeed(), "Normal hurt ySpeed should be -0x400");
    }

    @Test
    public void hurtResetClearsRollJumpAndJumpingBeforeAirborneRecoil() {
        sprite.setAir(true);
        sprite.setRollingJump(true);
        sprite.setJumping(true);
        sprite.setPushing(true);

        sprite.applyHurt(0);

        assertTrue(sprite.getAir(), "HurtCharacter sets Status_InAir after reset-on-floor cleanup");
        assertFalse(sprite.getRollingJump(), "Reset-on-floor tail clears Status_RollJump before hurt recoil");
        assertFalse(sprite.isJumping(), "Reset-on-floor tail clears the jumping latch before hurt recoil");
        assertFalse(sprite.getPushing(), "Reset-on-floor tail clears Status_Push before hurt recoil");
    }

    @Test
    public void testHurtKnockback_UnderwaterHalvedVelocities() {
        // Player is underwater (ROM s2.asm lines 84936-84941)
        sprite.setInWater(true);
        sprite.setTestY((short) 500);

        // Apply hurt from the left (sourceX < player center)
        sprite.applyHurt(0);

        assertTrue(sprite.isHurt(), "Player should be hurt");
        // Underwater knockback is halved: xSpeed = 0x100 (256), ySpeed = -0x200 (-512)
        assertEquals(0x100, sprite.getXSpeed(), "Underwater hurt xSpeed should be 0x100 (halved)");
        assertEquals(-0x200, sprite.getYSpeed(), "Underwater hurt ySpeed should be -0x200 (halved)");
    }

    @Test
    public void testHurtGravity_UnderwaterValue() {
        // According to ROM s2.asm: hurt gravity = 0x30, underwater subtracts 0x20
        // So underwater hurt gravity = 0x30 - 0x20 = 0x10 (same as normal underwater)
        // Use getEffectiveGravity() which returns the net gravity accounting for water state
        // (getGravity() returns the BASE gravity; underwater reduction is applied in modeAirborne())
        sprite.setInWater(true);
        sprite.setHurt(true);

        short gravity = sprite.getEffectiveGravity();
        assertEquals(0x10, gravity, "Underwater hurt gravity should be 0x10");
    }

}
