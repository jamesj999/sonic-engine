package com.openggf.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests S3K per-character Super form physics profiles.
 * <p>ROM refs:
 * <ul>
 *   <li>Super Sonic: sonic3k.asm:22084-22086 (max=$A00, accel=$30, decel=$100)</li>
 *   <li>Super Tails: sonic3k.asm:26325-26327,28538-28541 (max=$800, accel=$18, decel=$C0, jump=$680)</li>
 *   <li>Super Knuckles: sonic3k.asm:32611-32613,32454-32457 (max=$800, accel=$18, decel=$C0, jump=$600)</li>
 *   <li>Super Sonic water: sonic3k.asm:22230-22232 ($500/$18/$80)</li>
 *   <li>Super Tails water: sonic3k.asm:27445-27447 ($400/$C/$60)</li>
 * </ul>
 */
class TestS3kSuperProfiles {

    // --- Super Sonic Profile ---

    @Test
    void superSonic_matchesRom() {
        PhysicsProfile p = PhysicsProfile.SONIC_3K_SUPER_SONIC;
        assertEquals(0xA00, p.max(), "Super Sonic max");
        assertEquals(0x30, p.runAccel(), "Super Sonic accel");
        assertEquals(0x100, p.runDecel(), "Super Sonic decel");
        assertEquals(0x800, p.jump(), "Super Sonic jump");
    }

    // --- Super Tails Profile ---

    @Test
    void superTails_matchesRom() {
        PhysicsProfile p = PhysicsProfile.SONIC_3K_SUPER_TAILS;
        assertEquals(0x800, p.max(), "Super Tails max");
        assertEquals(0x18, p.runAccel(), "Super Tails accel");
        assertEquals(0xC0, p.runDecel(), "Super Tails decel");
        assertEquals(0x680, p.jump(), "Super Tails jump");
    }

    @Test
    void superKnuckles_matchesRom() {
        PhysicsProfile p = PhysicsProfile.SONIC_3K_SUPER_KNUCKLES;
        assertEquals(0x800, p.max(), "Super Knuckles max");
        assertEquals(0x18, p.runAccel(), "Super Knuckles accel");
        assertEquals(0xC0, p.runDecel(), "Super Knuckles decel");
        assertEquals(0x600, p.jump(), "Super Knuckles jump");
    }

    @Test
    void superTails_retainsTailsSizes() {
        PhysicsProfile p = PhysicsProfile.SONIC_3K_SUPER_TAILS;
        assertEquals(264, p.minStartRollSpeed(), "Super Tails minStartRollSpeed");
        assertEquals(30, p.runHeight(), "Super Tails runHeight");
        assertEquals(15, p.standYRadius(), "Super Tails standYRadius");
    }

    @Test
    void superSonic_retainsSonicSizes() {
        PhysicsProfile p = PhysicsProfile.SONIC_3K_SUPER_SONIC;
        assertEquals(128, p.minStartRollSpeed(), "Super Sonic minStartRollSpeed");
        assertEquals(38, p.runHeight(), "Super Sonic runHeight");
        assertEquals(19, p.standYRadius(), "Super Sonic standYRadius");
    }

    // --- Water Modifier Produces Correct Per-Character Values ---

    @Test
    void superSonic_waterModifier_matchesRom() {
        // Super Sonic water: $500/$18/$80 = 0.5x of $A00/$30/$100
        PhysicsProfile p = PhysicsProfile.SONIC_3K_SUPER_SONIC;
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(0x500, m.effectiveMax(p.max(), true, false),
                "Super Sonic underwater max");
        assertEquals(0x18, m.effectiveAccel(p.runAccel(), true, false),
                "Super Sonic underwater accel");
        assertEquals(0x80, m.effectiveDecel(p.runDecel(), true, false),
                "Super Sonic underwater decel");
    }

    @Test
    void superTails_waterModifier_matchesRom() {
        // Super Tails water: $400/$C/$60 = 0.5x of $800/$18/$C0
        PhysicsProfile p = PhysicsProfile.SONIC_3K_SUPER_TAILS;
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(0x400, m.effectiveMax(p.max(), true, false),
                "Super Tails underwater max");
        assertEquals(0x0C, m.effectiveAccel(p.runAccel(), true, false),
                "Super Tails underwater accel");
        assertEquals(0x60, m.effectiveDecel(p.runDecel(), true, false),
                "Super Tails underwater decel");
    }

    // --- Canonical Normal Profiles ---

    @Test
    void normalSonic_canonical_waterModifier_matchesRom() {
        // Normal water: $300/$6/$40 = 0.5x of $600/$C/$80
        PhysicsProfile p = PhysicsProfile.SONIC_2_SONIC;
        PhysicsModifiers m = PhysicsModifiers.STANDARD;
        assertEquals(0x300, m.effectiveMax(p.max(), true, false),
                "Normal Sonic underwater max");
        assertEquals(0x06, m.effectiveAccel(p.runAccel(), true, false),
                "Normal Sonic underwater accel");
        assertEquals(0x40, m.effectiveDecel(p.runDecel(), true, false),
                "Normal Sonic underwater decel");
    }
}

