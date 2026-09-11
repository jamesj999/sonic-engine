package com.openggf.tests;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.Sonic3kButtonObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kButtonObject {

    @Test
    void solidBoundsIncludeExactRightEdge() {
        Sonic3kButtonObjectInstance button = new Sonic3kButtonObjectInstance(
                new ObjectSpawn(0x6140, 0x0200, Sonic3kObjectIds.BUTTON,
                        0x04, 0, false, 0x0200));

        assertTrue(button.usesInclusiveRightEdge(),
                "S3K SolidObjectFull accepts the exact d1 right boundary");
    }
}
