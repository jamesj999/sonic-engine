package com.openggf.trace;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseDebugHit;

import java.util.List;

public final class TouchResponseDebugHitFormatter {

    private TouchResponseDebugHitFormatter() {
    }

    public static String summariseOverlaps(List<TouchResponseDebugHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (TouchResponseDebugHit hit : hits) {
            if (!hit.overlapping()) {
                continue;
            }
            if (shown > 0) {
                sb.append(" | ");
            }
            appendPrefix(sb, "touch", hit);
            appendObjectPosition(sb, hit);
            appendDebugDetails(sb, hit);
            shown++;
            if (shown >= 3) {
                break;
            }
        }
        return sb.toString();
    }

    public static String summariseNearbyScans(List<TouchResponseDebugHit> hits, int centreX, int centreY) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (TouchResponseDebugHit hit : hits) {
            if (!isNearby(hit, centreX, centreY)) {
                continue;
            }
            if (shown > 0) {
                sb.append(" | ");
            }
            appendPrefix(sb, "scan", hit);
            sb.append(String.format(" ov=%d sz=%dx%d",
                    hit.overlapping() ? 1 : 0,
                    hit.width(),
                    hit.height()));
            appendObjectPosition(sb, hit);
            appendDebugDetails(sb, hit);
            shown++;
            if (shown >= 4) {
                break;
            }
        }
        return sb.toString();
    }

    private static boolean isNearby(TouchResponseDebugHit hit, int centreX, int centreY) {
        int dx = Math.abs(hit.objectX() - centreX);
        int dy = Math.abs(hit.objectY() - centreY);
        return dx <= 160 && dy <= 160;
    }

    private static void appendPrefix(StringBuilder sb, String prefix, TouchResponseDebugHit hit) {
        sb.append(prefix);
        if (hit.slotIndex() >= 0) {
            sb.append(' ').append('s').append(hit.slotIndex());
        }
        ObjectSpawn spawn = hit.spawn();
        if (spawn != null) {
            sb.append(String.format(" 0x%02X", spawn.objectId() & 0xFF));
        } else {
            sb.append(" 0x??");
        }
        sb.append(' ').append(hit.category());
    }

    private static void appendObjectPosition(StringBuilder sb, TouchResponseDebugHit hit) {
        sb.append(String.format(" obj=@%04X,%04X",
                hit.objectX() & 0xFFFF,
                hit.objectY() & 0xFFFF));
        ObjectSpawn spawn = hit.spawn();
        if (spawn != null && (spawn.x() != hit.objectX() || spawn.y() != hit.objectY())) {
            sb.append(String.format(" sp=@%04X,%04X",
                    spawn.x() & 0xFFFF,
                    spawn.y() & 0xFFFF));
        }
    }

    private static void appendDebugDetails(StringBuilder sb, TouchResponseDebugHit hit) {
        if (hit.debugDetails() != null && !hit.debugDetails().isBlank()) {
            sb.append(' ').append(hit.debugDetails());
        }
    }
}
