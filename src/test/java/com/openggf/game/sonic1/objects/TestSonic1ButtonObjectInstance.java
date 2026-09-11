package com.openggf.game.sonic1.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidRoutineKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1ButtonObjectInstance {

    @Test
    void buttonUsesFullSolidObjectContractWithNarrowTopLandingWidth() {
        Sonic1ButtonObjectInstance button = new Sonic1ButtonObjectInstance(
                new ObjectSpawn(0x0B30, 0x0078, 0x32, 0, 0, false, 0));

        SolidObjectParams params = button.getSolidParams();

        assertFalse(button.isTopSolidOnly());
        assertEquals(SolidRoutineKind.FULL_SOLID, button.getSolidRoutineProfile().kind());
        assertTrue(button.getSolidRoutineProfile().inclusiveRightEdge());
        assertEquals(0x1B, params.halfWidth());
        assertEquals(5, params.airHalfHeight());
        assertEquals(5, params.groundHalfHeight());
        assertEquals(0x10, button.getTopLandingHalfWidth(null, params.halfWidth()));
    }
}
