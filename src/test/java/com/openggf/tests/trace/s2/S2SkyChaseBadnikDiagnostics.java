package com.openggf.tests.trace.s2;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.rules.SonicGame;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only diagnostic string builder for Sky Chase (S2 zone 8) badniks. Lifted
 * verbatim out of the shared trace-replay base so the base no longer carries the
 * {@code SONIC_2}/{@code zone == 8} carve-out; emitted text is unchanged.
 */
public final class S2SkyChaseBadnikDiagnostics {

    private S2SkyChaseBadnikDiagnostics() {
    }

    public static String summarise(SonicGame game, int zone, ObjectManager om) {
        if (game != SonicGame.SONIC_2 || zone != 8 || om == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (ObjectInstance instance : om.getActiveObjects()) {
            if (!(instance instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            ObjectSpawn spawn = aoi.getSpawn();
            if (spawn == null) {
                continue;
            }
            int id = spawn.objectId() & 0xFF;
            if (id != 0x98 && id != 0x99 && id != 0x9A && id != 0x9B && id != 0x9C && id != 0xAC) {
                continue;
            }
            parts.add(String.format("sczobj s%d 0x%02X %s @%04X,%04X spawn=@%04X,%04X %s",
                    aoi.getSlotIndex(),
                    id,
                    aoi.getName(),
                    aoi.getX() & 0xFFFF,
                    aoi.getY() & 0xFFFF,
                    spawn.x() & 0xFFFF,
                    spawn.y() & 0xFFFF,
                    aoi.traceDebugDetails()));
        }
        parts.sort(String::compareTo);
        if (parts.size() > 16) {
            parts = new ArrayList<>(parts.subList(0, 16));
        }
        return parts.isEmpty() ? "sczobj none" : String.join(" | ", parts);
    }
}
