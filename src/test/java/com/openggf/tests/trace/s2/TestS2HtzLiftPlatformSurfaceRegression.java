package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.objects.HTZLiftObjectInstance;
import com.openggf.game.sonic2.objects.RisingLavaObjectInstance;
import com.openggf.game.sonic2.objects.SeesawObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidRoutineKind;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestS2HtzLiftPlatformSurfaceRegression {

    @Test
    void htzLiftPlatformSurfaceMatchesObj16PlatformObjectHeight() {
        int objectY = 0x03BE;
        HTZLiftObjectInstance lift = new HTZLiftObjectInstance(
                new ObjectSpawn(0x01A0, objectY, 0x16, 0x14, 0, false, 0),
                "HTZLift");

        SolidObjectParams params = lift.getSolidParams();
        SolidRoutineProfile profile = lift.getSolidRoutineProfile();

        // S2 Obj16 passes d3 = -$28 before JmpTo3_PlatformObject
        // (docs/s2disasm/s2.asm:47384-47388). Platform riding and landing
        // therefore use surfaceY = y_pos - d3 = y_pos + $28.
        assertEquals(objectY + 0x28, objectY + params.offsetY() - params.groundHalfHeight());
        assertEquals(SolidRoutineKind.TOP_SOLID_ONLY, profile.kind());
        assertEquals(lift.isTopSolidOnly(), profile.topSolidOnly());
        assertEquals(lift.usesStickyContactBuffer(), profile.stickyContactBuffer());
    }

    @Test
    void htzLiftUsesObj16WidthPixelsForObjectEdgeBalance() {
        HTZLiftObjectInstance lift = new HTZLiftObjectInstance(
                new ObjectSpawn(0x01F8, 0x03EA, 0x16, 0x14, 0, false, 0),
                "HTZLift");

        // Obj16_Init writes width_pixels=$20 before PlatformObject
        // (docs/s2disasm/s2.asm:47763-47771). The player balance routine reads
        // that SST byte directly, so Tails at HTZ1 f192 is still inside the
        // platform instead of on its left balance edge.
        assertEquals(0x20, lift.getBalanceWidthPixels());
    }

    @Test
    void seesawUsesObj14WidthPixelsForObjectEdgeBalance() {
        SeesawObjectInstance seesaw = new SeesawObjectInstance(
                new ObjectSpawn(0x0DC0, 0x0448, 0x14, 0x00, 0, false, 0),
                "Seesaw");

        // Obj14_Init writes width_pixels=$30 before the player balance routines
        // read the standing object's SST byte. A 16px sprite default makes HTZ1
        // f1810 falsely enter the object-edge balance branch while centered on
        // the seesaw.
        assertEquals(0x30, seesaw.getBalanceWidthPixels());
    }

    @Test
    void risingLavaUsesObj30WidthPixelsForObjectEdgeBalance() {
        RisingLavaObjectInstance subtype6 = new RisingLavaObjectInstance(
                new ObjectSpawn(0x1760, 0x07D1, 0x30, 0x06, 0, false, 0),
                "RisingLava");
        RisingLavaObjectInstance subtype8 = new RisingLavaObjectInstance(
                new ObjectSpawn(0x1760, 0x07D1, 0x30, 0x08, 0, false, 0),
                "RisingLava");

        // Obj30_Init copies Obj30_Widths[subtype] into width_pixels(a0)
        // (docs/s2disasm/s2.asm:49545-49547). The player balance routine reads
        // that SST byte directly, so the wide lava surfaces must not fall back
        // to the shared 16px sprite footprint.
        assertEquals(0xE0, subtype6.getBalanceWidthPixels());
        assertEquals(0xC0, subtype8.getBalanceWidthPixels());
    }

    @Test
    void htzLiftFirstStandingFrameArmsSlideWithoutMovingUntilNextObjectUpdate() {
        ObjectManager objectManager = mock(ObjectManager.class);
        HTZLiftObjectInstance lift = new HTZLiftObjectInstance(
                new ObjectSpawn(0x01A0, 0x03BE, 0x16, 0x14, 0, false, 0),
                "HTZLift");
        lift.setServices(new TestObjectServices() {
            @Override
            public ObjectManager objectManager() {
                return objectManager;
            }
        });
        when(objectManager.isAnyPlayerRiding(lift)).thenReturn(true);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        lift.onSolidContact(player, new SolidContact(true, false, false, true, false), 147);

        lift.update(148, null);
        assertEquals(0x01A0, lift.getX());
        assertEquals(0x03BE, lift.getY());

        lift.update(149, null);
        assertEquals(0x01A2, lift.getX());
        assertEquals(0x03BF, lift.getY());
    }
}
