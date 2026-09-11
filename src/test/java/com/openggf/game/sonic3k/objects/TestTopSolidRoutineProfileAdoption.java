package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTopSolidRoutineProfileAdoption {

    @Test
    void floatingPlatformDeclaresTopSolidRoutineProfile() throws Exception {
        FloatingPlatformObjectInstance platform = ObjectConstructionContext.construct(
                new StubObjectServices(),
                () -> new FloatingPlatformObjectInstance(
                        new ObjectSpawn(0x1000, 0x0300, 0x51, 0x00, 0, false, 0)));

        Method method = FloatingPlatformObjectInstance.class.getDeclaredMethod("getSolidRoutineProfile");

        assertEquals(SolidRoutineProfile.class, method.getReturnType());
        assertTrue(platform.isTopSolidOnly());

        // Independent expectations from Obj_FloatingPlatform's SolidObjectTop call:
        // d3 is height_pixels + 1 and landing keeps SolidObjectTop's relative
        // y_pos += d0 + 3 result instead of PlatformObject's absolute snap.
        SolidRoutineProfile profile = platform.getSolidRoutineProfile();
        assertEquals(0x21, platform.getSolidParams().groundHalfHeight());
        assertEquals(new SolidRoutineProfile(
                SolidRoutineKind.TOP_SOLID_ONLY,
                true,
                false,
                0,
                false,
                true,
                false,
                false,
                true,
                false,
                false,
                true,
                false,
                false), profile);

        // Concrete observable fields of the top-solid routine.
        assertEquals(SolidRoutineKind.TOP_SOLID_ONLY, profile.kind());
        assertTrue(profile.topSolidOnly());
        assertTrue(profile.stickyContactBuffer());
        assertFalse(profile.monitorSolidity());
        assertFalse(profile.usesCollisionHalfWidthForTopLanding());
    }
}
