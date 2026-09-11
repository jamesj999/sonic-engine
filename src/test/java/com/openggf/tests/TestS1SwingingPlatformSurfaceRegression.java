package com.openggf.tests;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.objects.Sonic1SwingingPlatformObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestS1SwingingPlatformSurfaceRegression {

    @Test
    void ghzPlatformSeparatesLandingAndContinuedRideSurfaceHeights() {
        StubObjectServices services = new StubObjectServices();
        Sonic1SwingingPlatformObjectInstance platform = ObjectConstructionContext.construct(
                services,
                () -> new Sonic1SwingingPlatformObjectInstance(
                        new ObjectSpawn(0x1000, 0x0300, Sonic1ObjectIds.SWINGING_PLATFORM, 0x00, 0, false, 0)));
        platform.setServices(services);

        platform.update(0, null);

        SolidObjectParams params = platform.getSolidParams();
        assertEquals(0x18, params.halfWidth());
        assertEquals(8, params.airHalfHeight(),
                "Swing_SetSolid passes obHeight to Swing_Solid for initial Platform3 landing");
        assertEquals(9, params.groundHalfHeight(),
                "Swing_Action2 passes obHeight+1 to MvSonicOnPtfm for continued riding");
        assertFalse(platform.usesPlatformObjectLandingSnap(),
                "New landing must keep Platform3 geometry instead of using the continued-ride height");
    }
}
