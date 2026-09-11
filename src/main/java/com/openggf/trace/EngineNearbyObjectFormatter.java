package com.openggf.trace;

import java.util.List;

public final class EngineNearbyObjectFormatter {

    private EngineNearbyObjectFormatter() {
    }

    public static String summarise(List<EngineNearbyObject> objects) {
        if (objects == null || objects.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (EngineNearbyObject object : objects) {
            if (shown > 0) {
                sb.append(" | ");
            }
            sb.append(String.format("eng-near s%d 0x%02X %s @%04X,%04X",
                    object.slot(),
                    object.objectId() & 0xFF,
                    object.name(),
                    object.currentX() & 0xFFFF,
                    object.currentY() & 0xFFFF));
            if (object.touchResponseProvider()) {
                sb.append(String.format(" col=%02X", object.collisionFlags() & 0xFF));
            } else {
                sb.append(" no-touch");
            }
            if (object.debugDetails() != null && !object.debugDetails().isBlank()) {
                sb.append(' ').append(object.debugDetails());
            }
            if (object.spawnX() != object.currentX() || object.spawnY() != object.currentY()) {
                sb.append(String.format(" spawn=@%04X,%04X",
                        object.spawnX() & 0xFFFF,
                        object.spawnY() & 0xFFFF));
            }
            if (object.touchResponseProvider()
                    && object.preUpdateCollisionFlags() >= 0
                    && object.preUpdateCollisionFlags() != object.collisionFlags()) {
                sb.append(String.format(" preCol=%02X", object.preUpdateCollisionFlags() & 0xFF));
            }
            if (object.preUpdateX() != object.currentX() || object.preUpdateY() != object.currentY()) {
                sb.append(String.format(" pre=@%04X,%04X",
                        object.preUpdateX() & 0xFFFF,
                        object.preUpdateY() & 0xFFFF));
            }
            String gateSummary = buildGateSummary(object);
            if (!gateSummary.isEmpty()) {
                sb.append(" gate=").append(gateSummary);
            }
            shown++;
            if (shown >= 12) {
                break;
            }
        }
        return sb.toString();
    }

    private static String buildGateSummary(EngineNearbyObject object) {
        if (!object.touchResponseProvider()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (object.skipTouchThisFrame()) {
            sb.append("skipTouch");
        }
        if (object.skipSolidThisFrame()) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append("skipSolid");
        }
        if (!object.onScreenForTouch()) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append("offscreenTouch");
        }
        return sb.toString();
    }
}
