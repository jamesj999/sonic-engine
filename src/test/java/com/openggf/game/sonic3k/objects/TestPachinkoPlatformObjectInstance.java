package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPachinkoPlatformObjectInstance {
    @Test
    void exposesStaticTopSolidRoutineProfile() {
        PachinkoPlatformObjectInstance platform = new PachinkoPlatformObjectInstance(
                new ObjectSpawn(0x140, 0x180, 0xEA, 0, 0, false, 0));

        SolidObjectParams params = platform.getSolidParams();
        SolidRoutineProfile profile = platform.getSolidRoutineProfile();

        assertEquals(0x2B, params.halfWidth());
        assertEquals(0x0C, params.airHalfHeight());
        assertEquals(0x0D, params.groundHalfHeight());
        assertEquals(SolidRoutineKind.TOP_SOLID_ONLY, profile.kind());
        assertTrue(profile.topSolidOnly());
        assertTrue(profile.stickyContactBuffer());
    }
}
