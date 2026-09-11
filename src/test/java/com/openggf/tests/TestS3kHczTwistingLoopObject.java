package com.openggf.tests;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.HCZTwistingLoopObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(SingletonResetExtension.class)
class TestS3kHczTwistingLoopObject {
    private static final int OBJECT_X = 0x0880;
    private static final int OBJECT_Y = 0x03C0;
    private static final int ENTRY_X = 0x087E;
    private static final int ENTRY_Y = 0x03B8;

    private HCZTwistingLoopObjectInstance loop;
    private TestablePlayableSprite player;

    @BeforeEach
    void setUp() {
        TestEnvironment.activeGameplayMode();
        loop = new HCZTwistingLoopObjectInstance(new ObjectSpawn(
                OBJECT_X, OBJECT_Y, Sonic3kObjectIds.HCZ_TWISTING_LOOP,
                0x80, 0, false, OBJECT_Y));
        loop.setServices(TestEnvironment.objectServices());

        player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setCentreX((short) ENTRY_X);
        player.setCentreY((short) ENTRY_Y);
        player.move((short) 0x00A2, (short) 0x007D);
        player.setGSpeed((short) -0x0F0D);
        player.setXSpeed((short) -0x0863);
        player.setYSpeed((short) -0x0C85);
        player.setAir(false);
    }

    @Test
    void reverseCapturePreservesNativePositionAndSubpixels() {
        loop.update(0, player);

        assertTrue(player.isObjectControlled());
        assertEquals(ENTRY_X, player.getCentreX() & 0xFFFF);
        assertEquals(ENTRY_Y, player.getCentreY() & 0xFFFF);
        assertEquals(0xA200, player.getXSubpixelRaw());
        assertEquals(0x7D00, player.getYSubpixelRaw());
    }

    @Test
    void activePhaseWritesPositionWordsWithoutClearingSubpixels() {
        loop.update(0, player);
        loop.update(1, player);

        assertEquals(0x087A, player.getCentreX() & 0xFFFF);
        assertEquals(ENTRY_Y, player.getCentreY() & 0xFFFF);
        assertEquals(0xA200, player.getXSubpixelRaw());
        assertEquals(0x7D00, player.getYSubpixelRaw());
    }
}
