package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.ParallaxManager;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestBalkiryBadnikInstance {
    @Test
    void initFrameSpawnsJetWithoutRunningObjectMove() {
        ObjectServices services = mock(ObjectServices.class);
        when(services.objectManager()).thenReturn(mock(ObjectManager.class));
        when(services.parallaxManager()).thenReturn(mock(ParallaxManager.class));
        BalkiryBadnikInstance balkiry;
        ObjectConstructionContext.setConstructionContext(services);
        try {
            balkiry = new BalkiryBadnikInstance(
                    new ObjectSpawn(0x0A20, 0x0040, 0xAC, 0, 0, false, 0));
            balkiry.setServices(services);
        } finally {
            ObjectConstructionContext.clearConstructionContext();
        }

        balkiry.update(1, null);

        assertEquals(0x0A20, balkiry.getX(),
                "ObjAC_Init sets up the Balkiry and jet child; ObjectMove starts on the next routine frame");
        assertEquals(0x0040, balkiry.getY());

        balkiry.update(2, null);

        assertEquals(0x0A1D, balkiry.getX(),
                "ObjAC_Main applies the default -$300 x_vel through ObjectMove after init");
        assertEquals(0x0040, balkiry.getY());
    }

    @Test
    void exposesStandardEnemyTouchResponseProfile() {
        ObjectServices services = mock(ObjectServices.class);
        when(services.objectManager()).thenReturn(mock(ObjectManager.class));
        when(services.parallaxManager()).thenReturn(mock(ParallaxManager.class));

        BalkiryBadnikInstance balkiry;
        ObjectConstructionContext.setConstructionContext(services);
        try {
            balkiry = new BalkiryBadnikInstance(
                    new ObjectSpawn(0x0A20, 0x0040, 0xAC, 0, 0, false, 0));
        } finally {
            ObjectConstructionContext.clearConstructionContext();
        }

        assertEquals(TouchResponseProfile.standardEnemy(), balkiry.getTouchResponseProfile());
        assertEquals(0x08, balkiry.getCollisionFlags());
    }
}
