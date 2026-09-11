package com.openggf.game.sonic3k.objects;

import com.openggf.game.ShieldType;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGumballTriangleBumperObjectInstance {

    @Test
    void consumedBumper_stopsRenderingAndSolidityAfterBounce() {
        GumballTriangleBumperObjectInstance bumper =
                new GumballTriangleBumperObjectInstance(new ObjectSpawn(0, 0, 0x87, 0, 0, false, 0));
        bumper.setServices(new TestObjectServices());
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setShieldStateForTest(true, ShieldType.BASIC);

        bumper.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);

        assertFalse(bumper.isSolidFor(player));
        ArrayList<com.openggf.graphics.GLCommand> commands = new ArrayList<>();
        bumper.appendRenderCommands(commands);
        assertTrue(commands.isEmpty());
    }

    /**
     * ROM reference: sonic3k.asm:127681-127706 (sub_60F94). Mirrored bumpers
     * (render_flags bit 0 set) keep d0 = -0x300 instead of negating it, so a
     * side/standing SolidObjectFull contact on a mirrored placement bounces
     * the player leftward.
     */
    @Test
    void mirroredBumperOnSolidContactUsesLeftwardVelocity() {
        GumballTriangleBumperObjectInstance bumper =
                new GumballTriangleBumperObjectInstance(new ObjectSpawn(0, 0, 0x87, 0, 1, false, 0));
        bumper.setServices(new TestObjectServices());
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 8, (short) 8);
        player.setAir(true);
        player.setYSpeed((short) 0x100);

        bumper.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);

        assertEquals(-0x300, player.getXSpeed());
        assertEquals(-0x600, player.getYSpeed());
    }
}

