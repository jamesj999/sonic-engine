package com.openggf.tests.trace.s3k;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.rings.LostRingObjectInstance;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import java.nio.file.Path;
import java.util.List;

/** S3K ICZ from the Sonic+Tails complete-run TAS. Per-zone segment: act1 -> seamless act1->act2 transition -> act2 -> the act2->next-zone exit handoff. zone()=5 (S3K zone_id == engine index); act()=0. */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kIczZoneSliceTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_3K; }
    @Override protected int zone() { return 5; }
    @Override protected int act() { return 0; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s3k/icz_completerun"); }

    @Override
    protected String additionalEngineObjectDiagnostics(ObjectManager om) {
        if (om == null) {
            return "";
        }
        StringBuilder sb = lowSlotDiagnostics(om);
        List<LostRingObjectInstance> rings = om.activeObjectsOfType(LostRingObjectInstance.class);
        if (rings.isEmpty()) {
            return sb.append(" | eng-obj37 none").toString();
        }
        sb.append(" | eng-obj37");
        int shown = 0;
        for (LostRingObjectInstance ring : rings) {
            if (shown >= 12) {
                sb.append(" ...");
                break;
            }
            sb.append(String.format(" s%d @%04X,%04X sub=%04X,%04X vel=%04X,%04X phase=%02X col=%d skip=%d dead=%d",
                    ring.getSlotIndex(),
                    ring.getX() & 0xFFFF,
                    ring.getY() & 0xFFFF,
                    ring.getXSubpixelForTest() & 0xFFFF,
                    ring.getYSubpixelForTest() & 0xFFFF,
                    ring.getXVelForTest() & 0xFFFF,
                    ring.getYVelForTest() & 0xFFFF,
                    ring.getPhaseOffset() & 0xFF,
                    ring.isCollected() ? 1 : 0,
                    ring.isSkipTouchThisFrame() ? 1 : 0,
                    ring.isDestroyed() ? 1 : 0));
            if (ring instanceof AbstractObjectInstance aoi && !aoi.traceDebugDetails().isBlank()) {
                sb.append(' ').append(aoi.traceDebugDetails());
            }
            shown++;
        }
        return sb.toString();
    }

    private static StringBuilder lowSlotDiagnostics(ObjectManager om) {
        StringBuilder sb = new StringBuilder("eng-low-slots");
        for (AbstractObjectInstance obj : om.activeObjectsOfType(AbstractObjectInstance.class)) {
            int slot = obj.getSlotIndex();
            if (slot < 4 || slot > 18) {
                continue;
            }
            int objectId = obj.getSpawn() != null ? obj.getSpawn().objectId() & 0xFF : 0;
            sb.append(String.format(" s%d:%02X@%04X,%04X/%s",
                    slot,
                    objectId,
                    obj.getX() & 0xFFFF,
                    obj.getY() & 0xFFFF,
                    obj.getClass().getSimpleName()));
        }
        return sb;
    }
}
