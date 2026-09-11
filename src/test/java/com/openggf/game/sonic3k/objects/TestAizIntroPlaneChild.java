package com.openggf.game.sonic3k.objects;

import org.junit.jupiter.api.Test;
import com.openggf.level.objects.ObjectSpawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestAizIntroPlaneChild {

    @Test
    public void attachedChildStaysAtParentOffset() {
        AizPlaneIntroInstance parent = new AizPlaneIntroInstance(
                new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0));
        AizIntroPlaneChild child = new AizIntroPlaneChild(
                new ObjectSpawn(0x60 - 0x22, 0x30 + 0x2C, 0, 0, 0, false, 0),
                parent);

        for (int frame = 0; frame < 64; frame++) {
            child.update(frame, null);
            assertEquals(parent.getX() - 0x22, child.getX());
            assertEquals(parent.getY() + 0x2C, child.getY());
        }
    }

    @Test
    public void aircraftPiecesUseRomPriorityBehindWaterSplashes() {
        AizPlaneIntroInstance parent = new AizPlaneIntroInstance(
                new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0));
        AizIntroPlaneChild plane = new AizIntroPlaneChild(
                new ObjectSpawn(0x3E, 0x5C, 0, 0, 0, false, 0), parent);
        AizIntroEmeraldGlowChild propeller = new AizIntroEmeraldGlowChild(
                new ObjectSpawn(0, 0, 0, 0, 0, false, 0), plane, 0);
        AizIntroEmeraldGlowChild rocketBooster = new AizIntroEmeraldGlowChild(
                new ObjectSpawn(0, 0, 0, 1, 0, false, 0), plane, 1);
        AizIntroWaveChild splash = new AizIntroWaveChild(
                new ObjectSpawn(0x120, 0x48, 0, 0, 0, false, 0), parent);

        assertEquals(5, parent.getPriorityBucket(), "Obj_AIZPlaneIntro priority $280");
        assertEquals(5, plane.getPriorityBucket(), "plane child priority $280");
        assertEquals(5, propeller.getPriorityBucket(), "propeller priority $280");
        assertEquals(5, rocketBooster.getPriorityBucket(), "rocket booster priority $280");
        assertEquals(2, splash.getPriorityBucket(), "water splash priority $100");
        assertTrue(splash.getPriorityBucket() < propeller.getPriorityBucket(),
                "lower buckets draw later, so the splash must cover the aircraft parts");
    }
}

