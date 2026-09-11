package com.openggf.tests;

import com.openggf.game.GroundMode;
import com.openggf.game.sonic3k.objects.Sonic3kSpringObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSpringObjectInstance {

    @BeforeEach
    void configureRuntime() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @Test
    void horizontalSpringPreservesExistingAirborneStatus() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 8);
        player.setAir(true);
        player.setRolling(true);
        player.setYSpeed((short) 0xF980);

        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0, 0, 0x07, 0x12, 0, false, 0));
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());

        spring.onSolidContact(player, new SolidContact(false, true, false, false, true), 0);

        assertTrue(player.getAir(),
                "ROM sub_23190 horizontal-spring launch does not clear Status_InAir");
        assertEquals((short) 0x0A00, player.getXSpeed(),
                "Subtype $12 yellow horizontal spring should apply +$0A00 x_vel");
        assertEquals((short) 0x0A00, player.getGSpeed(),
                "Horizontal spring mirrors x_vel to ground_vel even while airborne");
        assertEquals((short) 0xF980, player.getYSpeed(),
                "Horizontal spring only clears y_vel when subtype bit 7 is set");
    }

    @Test
    void horizontalSpringKeepsGroundedContactsGrounded() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) 8);
        player.setAir(false);
        player.setGroundMode(GroundMode.GROUND);
        player.setAngle((byte) 0x04);

        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0, 0, 0x07, 0x12, 0, false, 0));
        spring.setServices(new TestObjectServices().withIsolatedObjectManager());

        spring.onSolidContact(player, new SolidContact(false, true, false, false, true), 0);

        assertFalse(player.getAir(),
                "Grounded horizontal-spring contacts should remain grounded");
        assertEquals(0x04, player.getAngle() & 0xFF,
                "ROM sub_23190 does not write angle(a1); shallow floor angle should survive launch");
        assertEquals((short) 0x0A00, player.getXSpeed());
        assertEquals((short) 0x0A00, player.getGSpeed());
    }
}
