package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kTwistedRampObjectInstance {
    private static final int TWISTED_RAMP = 0x0E;

    @AfterEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void nativePlayerTwoPassCanLaunchSidekickWhenMainPlayerMissesTrigger() {
        Sonic3kTwistedRampObjectInstance ramp = new Sonic3kTwistedRampObjectInstance(new ObjectSpawn(
                0x1800, 0x0600, TWISTED_RAMP, 0, 0, false, 0));
        TestablePlayableSprite main = playerAt(0x1600, 0x0600);
        main.setXSpeed((short) 0x0400);
        TestablePlayableSprite sidekick = playerAt(0x17F0, 0x0600);
        sidekick.setXSpeed((short) 0x0400);
        ramp.setServices(new StubObjectServices().withPlayerQuery(new ObjectPlayerQuery(
                () -> main,
                () -> List.of(sidekick))));

        ramp.update(0, main);

        assertEquals(0x0400, main.getXSpeed() & 0xFFFF,
                "Player_1 outside the trigger window should remain unchanged");
        assertFalse(main.getAir());
        assertEquals(0x0800, sidekick.getXSpeed() & 0xFFFF,
                "Obj_TwistedRamp runs sub_24D9A for Player_2 after Player_1");
        assertEquals((short) -0x700, sidekick.getYSpeed());
        assertTrue(sidekick.getAir());
        assertEquals(1, sidekick.getGSpeed());
        assertEquals(1, sidekick.getFlipAngle());
        assertEquals(0, sidekick.getAnimationId());
        assertEquals(0, sidekick.getFlipsRemaining());
        assertEquals(4, sidekick.getFlipSpeed());
        assertEquals(5, sidekick.getFlipType(),
                "Obj_TwistedRamp loc_24DFC writes flip_type=5 for the corkscrew launch");
    }

    @Test
    void outOfRangeRampDeletesRespawnablyLikeRomDeleteCurrentSpritePath() {
        Sonic3kTwistedRampObjectInstance ramp = new Sonic3kTwistedRampObjectInstance(new ObjectSpawn(
                0x1800, 0x0600, TWISTED_RAMP, 0, 0, false, 0));
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);

        ramp.update(0, null);

        assertTrue(ramp.isDestroyed(),
                "Obj_TwistedRamp deletes when its chunk-aligned X is outside the camera range");
        assertTrue(ramp.isDestroyedRespawnable(),
                "Delete_Current_Sprite via respawn_addr clears the loaded bit so the ramp can respawn");
    }

    private static TestablePlayableSprite playerAt(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        player.setAir(false);
        return player;
    }
}
