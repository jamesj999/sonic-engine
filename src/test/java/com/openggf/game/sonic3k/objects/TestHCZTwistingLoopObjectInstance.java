package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic1.objects.TestPlayableSprite;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHCZTwistingLoopObjectInstance {
    @Test
    void persistenceMatchesPerPlayerCapturePhases() {
        HCZTwistingLoopObjectInstance loop = new HCZTwistingLoopObjectInstance(
                new ObjectSpawn(0x0840, 0x0120, 0x2A, 0x00, 0, false, 0));
        TestPlayableSprite main = playerAtLoopEntry();
        loop.setServices(new TestObjectServices());

        assertFalse(loop.isPersistent(),
                "loc_3909C sends an idle loop through Delete_Sprite_If_Not_In_Range");

        loop.update(0, main);

        assertTrue(loop.isPersistent(),
                "an active per-player phase must keep the controller alive while carrying the player");
    }

    @Test
    void sidekickProcessingUsesNativeP2QueryOnly() {
        HCZTwistingLoopObjectInstance loop = new HCZTwistingLoopObjectInstance(
                new ObjectSpawn(0x0840, 0x0120, 0x2A, 0x00, 0, false, 0));
        TestPlayableSprite nativeP2 = playerAtLoopEntry();
        TestPlayableSprite extraSidekick = playerAtLoopEntry();
        loop.setServices(new TestObjectServices().withSidekicks(List.of(nativeP2, extraSidekick)));

        TestPlayableSprite main = playerAtLoopEntry();

        loop.update(0, main);

        assertTrue(main.isObjectControlled(),
                "The direct update player should remain the P1 fallback when services cannot resolve main");
        assertNativeBits0To6Control(nativeP2);
        assertFalse(extraSidekick.isObjectControlled(),
                "HCZ Twisting Loop has one ROM Player_2 state block and must not process extra engine sidekicks");
    }

    private static TestPlayableSprite playerAtLoopEntry() {
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x0840);
        player.setCentreY((short) 0x0128);
        player.setGSpeed((short) 0x0800);
        player.setXSpeed((short) 0x0800);
        player.setObjectControlled(false);
        return player;
    }

    private static void assertNativeBits0To6Control(TestPlayableSprite player) {
        assertTrue(player.isObjectControlled());
        assertTrue(player.isObjectControlAllowsCpu());
        assertTrue(player.isObjectControlSuppressesMovement());
        assertFalse(player.isTouchResponseSuppressedByObjectControl());
    }
}
