package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.SmallMetalPformObjectInstance.SmallMetalPformChildInstance;
import com.openggf.game.sonic2.objects.badniks.WFZStickBadnikInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestWfzObjectPriority {

    @Test
    void wfzObjectsWithRomArtPriorityBitRenderHighPriority() {
        List<AbstractObjectInstance> objects = List.of(
                new VPropellerObjectInstance(spawn(Sonic2ObjectIds.VPROPELLER, 0x64)),
                new HPropellerObjectInstance(spawn(Sonic2ObjectIds.HPROPELLER, 0x66)),
                new LaserObjectInstance(spawn(Sonic2ObjectIds.LASER, 0x76)),
                new VerticalLaserObjectInstance(spawn(Sonic2ObjectIds.TILTING_PLATFORM, 0x72), 0x1000, 0x0400),
                new WFZWheelObjectInstance(spawn(Sonic2ObjectIds.WFZ_WHEEL, 0x78)),
                new SmallMetalPformChildInstance(spawn(Sonic2ObjectIds.SMALL_METAL_PFORM, 0x7E), false),
                new LateralCannonObjectInstance(spawn(Sonic2ObjectIds.LATERAL_CANNON, 0x82), "LateralCannon"),
                new RivetObjectInstance(spawn(Sonic2ObjectIds.RIVET, 0x88), "Rivet"),
                new TiltingPlatformObjectInstance(spawn(Sonic2ObjectIds.TILTING_PLATFORM, 0x70)),
                new WFZStickBadnikInstance(spawn(Sonic2ObjectIds.WFZ_STICK, 0x86))
        );

        for (AbstractObjectInstance object : objects) {
            assertTrue(object.isHighPriority(),
                    object.getName() + " uses make_art_tile(..., ..., 1) in the Sonic 2 ROM");
        }
    }

    private static ObjectSpawn spawn(int objectId, int subtype) {
        return new ObjectSpawn(0x1000, 0x0400, objectId, subtype, 0, false, 0);
    }
}
