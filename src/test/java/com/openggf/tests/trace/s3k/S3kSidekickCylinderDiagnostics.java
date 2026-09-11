package com.openggf.tests.trace.s3k;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.objects.CnzCylinderInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Read-only diagnostic string builder for CNZ cylinders near the sidekick.
 * Lifted verbatim out of the shared trace-replay base so the base no longer
 * hard-references the S3K-specific {@link CnzCylinderInstance}; emitted text is
 * unchanged (non-S3K games never hold a cylinder, so the shared base's default
 * still yields the same "none" string those games always produced).
 */
public final class S3kSidekickCylinderDiagnostics {

    private S3kSidekickCylinderDiagnostics() {
    }

    public static String summarise(ObjectManager om) {
        SpriteManager spriteManager = GameServices.sprites();
        if (spriteManager == null || spriteManager.getSidekicks().isEmpty()) {
            return "eng-tails-cyl none sidekick=inactive";
        }
        AbstractPlayableSprite sidekick = spriteManager.getSidekicks().getFirst();
        List<String> parts = new ArrayList<>();
        for (ObjectInstance instance : om.getActiveObjects()) {
            if (!(instance instanceof CnzCylinderInstance)
                    || !(instance instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            int dx = Math.abs(aoi.getX() - sidekick.getCentreX());
            int dy = Math.abs(aoi.getY() - sidekick.getCentreY());
            if (dx > 2048 || dy > 2048) {
                continue;
            }
            parts.add(String.format("eng-tails-cyl d=%04X,%04X s%d @%04X,%04X %s",
                    dx & 0xFFFF,
                    dy & 0xFFFF,
                    aoi.getSlotIndex(),
                    aoi.getX() & 0xFFFF,
                    aoi.getY() & 0xFFFF,
                    aoi.traceDebugDetails()));
        }
        parts.sort(Comparator.naturalOrder());
        if (parts.size() > 4) {
            parts = new ArrayList<>(parts.subList(0, 4));
        }
        if (parts.isEmpty()) {
            return "eng-tails-cyl none";
        }
        return String.join(" | ", parts);
    }
}
