package com.openggf.game.sonic1.objects;

import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1TeleporterObjectInstance {

    @Test
    public void holdsRollAnimationWhileTeleportingAndClearsOnRelease() {
        Sonic1TeleporterObjectInstance teleporter = createTeleporter(0x00, 0, 0x300, 0x300);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x300);
        player.setCentreY((short) 0x300);

        // Capture on first update.
        teleporter.update(1, player);
        assertTrue(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
        assertTrue(player.isTouchResponseSuppressedByObjectControl());
        assertTrue(player.isControlLocked());
        assertEquals(Sonic1AnimationIds.ROLL.id(), player.getForcedAnimationId());

        // Continue until release; roll animation must remain forced during transport.
        int frame = 2;
        while (player.isObjectControlled() && frame < 800) {
            teleporter.update(frame, player);
            if (player.isObjectControlled()) {
                assertEquals(Sonic1AnimationIds.ROLL.id(), player.getForcedAnimationId());
            }
            frame++;
        }

        assertFalse(player.isObjectControlled(), "teleporter should release within expected frame budget");
        assertFalse(player.isObjectControlAllowsCpu());
        assertFalse(player.isObjectControlSuppressesMovement());
        assertFalse(player.isTouchResponseSuppressedByObjectControl());
        assertFalse(player.isControlLocked());
        assertEquals(-1, player.getForcedAnimationId());
    }

    @Test
    public void unloadWhileActiveReleasesControlAndForcedAnimation() {
        Sonic1TeleporterObjectInstance teleporter = createTeleporter(0x00, 0, 0x300, 0x300);
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x300);
        player.setCentreY((short) 0x300);

        teleporter.update(1, player);
        assertTrue(player.isObjectControlled());
        assertTrue(player.isTouchResponseSuppressedByObjectControl());
        assertEquals(Sonic1AnimationIds.ROLL.id(), player.getForcedAnimationId());

        teleporter.onUnload();

        assertFalse(player.isObjectControlled());
        assertFalse(player.isObjectControlAllowsCpu());
        assertFalse(player.isObjectControlSuppressesMovement());
        assertFalse(player.isTouchResponseSuppressedByObjectControl());
        assertFalse(player.isControlLocked());
        assertEquals(-1, player.getForcedAnimationId());
    }

    private static Sonic1TeleporterObjectInstance createTeleporter(int subtype, int renderFlags, int x, int y) {
        Sonic1TeleporterObjectInstance instance = new Sonic1TeleporterObjectInstance(
                new ObjectSpawn(x, y, 0x72, subtype, renderFlags, false, 0));
        instance.setServices(new TestObjectServices());
        return instance;
    }

}


