package com.openggf.game;

import com.openggf.game.rules.GameRules;
import com.openggf.timer.DisplayPhaseTimer;
import com.openggf.timer.timers.SpeedShoesTimer;

import com.openggf.game.sonic1.Sonic1PhysicsProvider;
import com.openggf.game.sonic2.Sonic2PhysicsProvider;
import com.openggf.game.sonic3k.Sonic3kPhysicsProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PhysicsProfile, PhysicsModifiers, and GameRules.
 * Verifies profile values match disassembly-verified constants and
 * modifier math produces correct results.
 */
public class TestPhysicsProfile {

    // ========================================
    // PhysicsProfile constants
    // ========================================

    @Test
    public void testSonic2SonicProfile_MatchesDisassembly() {
        PhysicsProfile p = PhysicsProfile.SONIC_2_SONIC;
        assertEquals(0x0C, p.runAccel(), "runAccel");
        assertEquals(0x80, p.runDecel(), "runDecel");
        assertEquals(0x0C, p.friction(), "friction");
        assertEquals(0x600, p.max(), "max");
        assertEquals(0x680, p.jump(), "jump");
        assertEquals(0x20, p.slopeRunning(), "slopeRunning");
        assertEquals(0x14, p.slopeRollingUp(), "slopeRollingUp");
        assertEquals(0x50, p.slopeRollingDown(), "slopeRollingDown");
        assertEquals(0x20, p.rollDecel(), "rollDecel");
        assertEquals(0x80, p.minStartRollSpeed(), "minStartRollSpeed");
        assertEquals(0x80, p.minRollSpeed(), "minRollSpeed");
        assertEquals(0x1000, p.maxRoll(), "maxRoll");
        assertEquals(28, p.rollHeight(), "rollHeight");
        assertEquals(38, p.runHeight(), "runHeight");
        assertEquals(9, p.standXRadius(), "standXRadius");
        assertEquals(19, p.standYRadius(), "standYRadius");
        assertEquals(7, p.rollXRadius(), "rollXRadius");
        assertEquals(14, p.rollYRadius(), "rollYRadius");
    }

    @Test
    public void testSonic2TailsProfile_DiffersFromSonic() {
        PhysicsProfile tails = PhysicsProfile.SONIC_2_TAILS;
        PhysicsProfile sonic = PhysicsProfile.SONIC_2_SONIC;

        // Shared values
        assertEquals(sonic.runAccel(), tails.runAccel(), "accel same");
        assertEquals(sonic.max(), tails.max(), "max same");
        assertEquals(sonic.jump(), tails.jump(), "jump same");
        // ROM Tails_Roll (s2.asm:39962) uses cmpi.w #$80,d0 — identical to Sonic_Roll (s2.asm:36983)
        assertEquals(0x80, tails.minStartRollSpeed(), "minStartRollSpeed same as Sonic");

        // Tails-specific differences
        assertEquals(30, tails.runHeight(), "runHeight");
        assertEquals(15, tails.standYRadius(), "standYRadius");
    }

    @Test
    public void testSuperSonicProfile_HigherSpeeds() {
        PhysicsProfile sup = PhysicsProfile.SONIC_3K_SUPER_SONIC;
        assertEquals(0xA00, sup.max(), "max");
        assertEquals(0x30, sup.runAccel(), "runAccel");
        assertEquals(0x100, sup.runDecel(), "runDecel");
    }

    @Test
    public void testSonic2SuperSonicProfile_MatchesDisassembly() {
        PhysicsProfile profile = PhysicsProfile.SONIC_2_SUPER_SONIC;
        assertEquals((short) 0x30, profile.runAccel(), "runAccel");
        assertEquals((short) 0x100, profile.runDecel(), "runDecel");
        assertEquals((short) 0x30, profile.friction(), "friction");
        assertEquals((short) 0xA00, profile.max(), "max");
        assertEquals((short) 0x800, profile.jump(), "jump");
        assertEquals((short) 19, profile.standYRadius(), "standYRadius");
        assertEquals((short) 14, profile.rollYRadius(), "rollYRadius");
    }

    @Test
    public void testSonic2SuperSonic_MatchesS3kSuperSonic() {
        // S2 and S3K Super Sonic have identical physics values
        PhysicsProfile s2 = PhysicsProfile.SONIC_2_SUPER_SONIC;
        PhysicsProfile s3k = PhysicsProfile.SONIC_3K_SUPER_SONIC;
        assertEquals(s3k.runAccel(), s2.runAccel(), "runAccel");
        assertEquals(s3k.runDecel(), s2.runDecel(), "runDecel");
        assertEquals(s3k.friction(), s2.friction(), "friction");
        assertEquals(s3k.max(), s2.max(), "max");
        assertEquals(s3k.jump(), s2.jump(), "jump");
    }

    // ========================================
    // PhysicsModifiers math
    // ========================================

    @Test
    public void testStandardModifiers_AccelNormal() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(12, m.effectiveAccel((short) 12, false, false), "Normal accel");
    }

    @Test
    public void testStandardModifiers_AccelWater() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(6, m.effectiveAccel((short) 12, true, false), "Water accel (halved)");
    }

    @Test
    public void testStandardModifiers_AccelSpeedShoes() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(24, m.effectiveAccel((short) 12, false, true), "Speed shoes accel (doubled)");
    }

    @Test
    public void testStandardModifiers_AccelWaterAndSpeedShoes() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        // ROM: water entry replaces speed constants with absolute underwater values;
        // shoes are irrelevant while submerged (s1:01 Sonic.asm:206-208)
        assertEquals(6, m.effectiveAccel((short) 12, true, true), "Water+shoes accel (water wins)");
    }

    @Test
    public void testStandardModifiers_DecelNormal() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(128, m.effectiveDecel((short) 128, false, false), "Normal decel");
    }

    @Test
    public void testStandardModifiers_DecelWater() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(64, m.effectiveDecel((short) 128, true, false), "Water decel (halved)");
    }

    @Test
    public void testStandardModifiers_DecelSpeedShoes_Unchanged() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        // Speed shoes don't affect decel (shoesDecelMul = 1.0)
        assertEquals(128, m.effectiveDecel((short) 128, false, true), "Speed shoes decel unchanged");
    }

    @Test
    public void testStandardModifiers_MaxNormal() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(0x600, m.effectiveMax((short) 0x600, false, false), "Normal max");
    }

    @Test
    public void testStandardModifiers_MaxWater() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(0x300, m.effectiveMax((short) 0x600, true, false), "Water max (halved)");
    }

    @Test
    public void testStandardModifiers_MaxSpeedShoes() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(0xC00, m.effectiveMax((short) 0x600, false, true), "Speed shoes max (doubled)");
    }

    @Test
    public void testStandardModifiers_FrictionNormal() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(12, m.effectiveFriction((short) 12, false, false), "Normal friction");
    }

    @Test
    public void testStandardModifiers_FrictionWater() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(6, m.effectiveFriction((short) 12, true, false), "Water friction (halved)");
    }

    @Test
    public void testStandardModifiers_FrictionSpeedShoes() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(24, m.effectiveFriction((short) 12, false, true), "Speed shoes friction (doubled)");
    }

    @Test
    public void testStandardModifiers_JumpNormal() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(0x680, m.effectiveJump((short) 0x680, false), "Normal jump");
    }

    @Test
    public void testStandardModifiers_JumpWater() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(0x380, m.effectiveJump((short) 0x680, true), "Water jump (absolute override)");
    }

    @Test
    public void testStandardModifiers_WaterGravityReduction() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(0x28, m.waterGravityReduction(), "Water gravity reduction");
    }

    @Test
    public void testStandardModifiers_WaterHurtGravityReduction() {
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        // ROM: Obj01_Hurt subtracts $20 when underwater (s2.asm:37802, s1:01 Sonic.asm:1410)
        // Net hurt underwater gravity = $30 - $20 = $10 (same as normal $38 - $28 = $10)
        assertEquals(0x20, m.waterHurtGravityReduction(), "Water hurt gravity reduction");
    }

    // ========================================
    // GameRules flags
    // ========================================

    @Test
    public void testSonic1_NoSpindash() {
        GameRules fs = GameRules.SONIC_1;
        assertFalse(fs.playerCapability().spindashEnabled(), "S1 spindash disabled");
        assertNull(fs.playerCapability().spindashSpeedTable(), "S1 no speed table");
        assertEquals(CollisionModel.UNIFIED, fs.collision().collisionModel(), "S1 unified collision");
        assertFalse((fs.collision().collisionModel() == CollisionModel.DUAL_PATH), "S1 no dual paths");
    }

    @Test
    public void testSonic2_HasSpindash() {
        GameRules fs = GameRules.SONIC_2;
        assertTrue(fs.playerCapability().spindashEnabled(), "S2 spindash enabled");
        assertNotNull(fs.playerCapability().spindashSpeedTable(), "S2 has speed table");
        assertEquals(9, fs.playerCapability().spindashSpeedTable().length, "S2 speed table length");
        assertEquals(0x0800, fs.playerCapability().spindashSpeedTable()[0], "S2 speed table[0]");
        assertEquals(0x0C00, fs.playerCapability().spindashSpeedTable()[8], "S2 speed table[8]");
        assertEquals(CollisionModel.DUAL_PATH, fs.collision().collisionModel(), "S2 dual path collision");
        assertTrue((fs.collision().collisionModel() == CollisionModel.DUAL_PATH), "S2 has dual paths");
    }

    @Test
    public void testSonic3K_HasSpindash() {
        GameRules fs = GameRules.SONIC_3K;
        assertTrue(fs.playerCapability().spindashEnabled(), "S3K spindash enabled");
        assertNotNull(fs.playerCapability().spindashSpeedTable(), "S3K has speed table");
        assertEquals(9, fs.playerCapability().spindashSpeedTable().length, "S3K speed table length");
        assertEquals(CollisionModel.DUAL_PATH, fs.collision().collisionModel(), "S3K dual path collision");
        assertTrue((fs.collision().collisionModel() == CollisionModel.DUAL_PATH), "S3K has dual paths");
    }

    @Test
    public void testPermanentRespawnTableLatch_PerGame() {
        // S3K only: ROM Touch_EnemyNormal sets bit 7 of Object_respawn_table
        // permanently after a player kill (sonic3k.asm:20953 bset #7,status(a1)).
        // S1/S2 ObjectsManager_Main only latches remembered spawns.
        assertFalse(GameRules.SONIC_1.objectInteraction().permanentRespawnTableLatch(),
                "S1 does not permanently latch respawn-table bits");
        assertFalse(GameRules.SONIC_2.objectInteraction().permanentRespawnTableLatch(),
                "S2 does not permanently latch respawn-table bits");
        assertTrue(GameRules.SONIC_3K.objectInteraction().permanentRespawnTableLatch(),
                "S3K permanently latches respawn-table bits after player kill");
    }


    @Test
    public void testObjectsExecuteAfterPlayerPhysics_PerGame() {
        // All three games use post-physics object execution per the
        // 2026-04-18-solid-ordering-rom-accuracy plan: S2/S3K via DUAL_PATH
        // collision model, S1 via the bridged inline-order path.
        assertTrue(GameRules.SONIC_1.objectInteraction().objectsExecuteAfterPlayerPhysics(),
                "S1 uses post-physics object ordering");
        assertTrue(GameRules.SONIC_2.objectInteraction().objectsExecuteAfterPlayerPhysics(),
                "S2 uses post-physics object ordering");
        assertTrue(GameRules.SONIC_3K.objectInteraction().objectsExecuteAfterPlayerPhysics(),
                "S3K uses post-physics object ordering");
    }

    @Test
    public void testSpeedShoesCountdownIsADisplayPhaseTimer() {
        // All three games decrement the shoes timer in Sonic_Display, which the
        // control routine calls after dispatching the movement modes, and do the
        // physics restore and the slow-down music command there in the one frame
        // (docs/s1disasm/_incObj/01 Sonic.asm:76,80,182-204;
        // docs/s2disasm/s2.asm:36242,36248,36307-36326;
        // docs/skdisasm/sonic3k.asm:22021,22031,22103-22127). The engine models
        // that by ticking the countdown from the character's display step rather
        // than from the level loop's pre-physics timer pass, so no per-game phase
        // compensation constant exists.
        assertTrue(DisplayPhaseTimer.class.isAssignableFrom(SpeedShoesTimer.class),
                "the speed-shoes countdown runs from the display step in all three games");
    }

    @Test
    public void testSpeedShoesTimerDecimation_PerGame() {
        // S1/S2 use a per-frame word timer; S3K a byte timer decremented every
        // 8th level frame (docs/skdisasm/sonic3k.asm:22108-22111).
        assertEquals(1, GameRules.SONIC_1.powerUp().speedShoesTimerDecimation(), "S1 per-frame word timer");
        assertEquals(1, GameRules.SONIC_2.powerUp().speedShoesTimerDecimation(), "S2 per-frame word timer");
        assertEquals(8, GameRules.SONIC_3K.powerUp().speedShoesTimerDecimation(),
                "S3K byte timer decremented every 8th level frame");
    }

    // ========================================
    // PhysicsProvider implementations
    // ========================================

    @Test
    public void testSonic1Provider_ReturnsSonicProfile() {
        var provider = new Sonic1PhysicsProvider();
        assertSame(PhysicsProfile.SONIC_2_SONIC, provider.getProfile("sonic"), "S1 returns SONIC_2_SONIC");
        // Any character type returns the same profile (only Sonic in S1)
        assertSame(PhysicsProfile.SONIC_2_SONIC, provider.getProfile("knuckles"), "S1 returns same for unknown");
    }

    @Test
    public void testSonic2Provider_ReturnsSonicOrTails() {
        var provider = new Sonic2PhysicsProvider();
        assertSame(PhysicsProfile.SONIC_2_SONIC, provider.getProfile("sonic"), "S2 sonic");
        assertSame(PhysicsProfile.SONIC_2_TAILS, provider.getProfile("tails"), "S2 tails");
        assertSame(PhysicsProfile.SONIC_2_TAILS, provider.getProfile("Tails"), "S2 Tails (uppercase)");
        assertSame(PhysicsProfile.SONIC_2_SONIC, provider.getProfile("knuckles"), "S2 default");
    }

    @Test
    public void testSonic3kProvider_ReturnsProfiles() {
        var provider = new Sonic3kPhysicsProvider();
        assertSame(PhysicsProfile.SONIC_2_SONIC, provider.getProfile("sonic"), "S3K sonic");
        assertSame(PhysicsProfile.SONIC_2_TAILS, provider.getProfile("tails"), "S3K tails");
        assertSame(PhysicsProfile.SONIC_3K_KNUCKLES, provider.getProfile("knuckles"), "S3K knuckles");
    }

    @Test
    public void testSonic1Provider_GameRules() {
        var provider = new Sonic1PhysicsProvider();
        assertSame(GameRules.SONIC_1, provider.getRules(), "S1 game rules");
        assertFalse(provider.getRules().playerCapability().spindashEnabled(), "S1 no spindash");
    }

    @Test
    public void testSonic2Provider_GameRules() {
        var provider = new Sonic2PhysicsProvider();
        assertSame(GameRules.SONIC_2, provider.getRules(), "S2 game rules");
        assertTrue(provider.getRules().playerCapability().spindashEnabled(), "S2 has spindash");
    }

    @Test
    public void testSonic3kProvider_GameRules() {
        var provider = new Sonic3kPhysicsProvider();
        assertSame(GameRules.SONIC_3K, provider.getRules(), "S3K game rules");
        assertTrue(provider.getRules().playerCapability().spindashEnabled(), "S3K has spindash");
    }
}

