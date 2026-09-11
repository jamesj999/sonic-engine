package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidRoutineProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1InvisibleBarrierObjectInstance {

    @Test
    public void invisibleBarrierUsesNoRenderCheckSolidObjectProfile() {
        Sonic1InvisibleBarrierObjectInstance barrier = new Sonic1InvisibleBarrierObjectInstance(
                new ObjectSpawn(0x1800, 0x02D8, Sonic1ObjectIds.INVISIBLE_BARRIER, 0x11, 0, false, 0));

        SolidRoutineProfile profile = barrier.getSolidRoutineProfile();

        assertTrue(profile.inclusiveRightEdge(),
                "SolidObject_NoRenderChk uses BHI for right-edge rejection, so the exact edge is solid");
        assertTrue(profile.bypassesOffscreenSolidGate(),
                "Obj71 branches straight to SolidObject_NoRenderChk after its visibility check");
    }
}
