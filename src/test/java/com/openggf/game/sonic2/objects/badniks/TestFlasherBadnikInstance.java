package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TestFlasherBadnikInstance {

    @Test
    void flasherTouchResponseDoesNotRequireRenderFlag() {
        FlasherBadnikInstance flasher = new FlasherBadnikInstance(
                new ObjectSpawn(0x12A8, 0x06CA, Sonic2ObjectIds.FLASHER, 0, 0, false, 0));

        assertFalse(flasher.requiresRenderFlagForTouch(),
                "S2 Touch_Loop scans ObjA3 collision_flags directly; render_flags off-screen must not hide an electrified Flasher from CPU Tails");
        assertFalse(flasher.getTouchResponseProfile().requiresRenderFlagForTouch());
        assertFalse(flasher.getTouchResponseProfile(false).requiresRenderFlagForTouch());
    }
}
