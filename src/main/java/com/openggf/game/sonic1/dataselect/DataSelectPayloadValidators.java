package com.openggf.game.sonic1.dataselect;

import java.util.List;
import java.util.Map;

public final class DataSelectPayloadValidators {

    private DataSelectPayloadValidators() {
    }

    public static boolean validateCommonPayload(Map<String, Object> payload, int maxZoneInclusive, int maxEmeraldCount) {
        if (!(payload.get("zone") instanceof Number zoneNumber)
                || !(payload.get("act") instanceof Number actNumber)
                || !(payload.get("mainCharacter") instanceof String)
                || !(payload.get("sidekicks") instanceof List<?>)) {
            return false;
        }
        int zone = zoneNumber.intValue();
        int act = actNumber.intValue();
        if (zone < 0 || zone > maxZoneInclusive || act < 0 || act > 2) {
            return false;
        }
        Object lives = payload.get("lives");
        if (lives instanceof Number number && number.intValue() < 0) {
            return false;
        }
        List<Integer> chaosEmeralds = readEmeraldList(payload.get("chaosEmeralds"), maxEmeraldCount);
        if (chaosEmeralds == null) {
            return false;
        }
        List<Integer> superEmeralds = readEmeraldList(payload.get("superEmeralds"), maxEmeraldCount);
        if (superEmeralds == null) {
            return false;
        }
        if (!chaosEmeralds.containsAll(superEmeralds)) {
            return false;
        }
        Object clear = payload.get("clear");
        if (!(clear == null || clear instanceof Boolean)) {
            return false;
        }
        Object progressCode = payload.get("progressCode");
        if (progressCode instanceof Number number) {
            int progress = number.intValue();
            if (progress < 0 || progress > 14) {
                return false;
            }
        }
        Object clearState = payload.get("clearState");
        if (clearState instanceof Number number) {
            int state = number.intValue();
            if (state < 0 || state > 3) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> readEmeraldList(Object raw, int maxEmeraldCount) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Object entry : list) {
            if (!(entry instanceof Number number)) {
                return null;
            }
            int index = number.intValue();
            if (index < 0 || index >= maxEmeraldCount || !seen.add(index)) {
                return null;
            }
        }
        return List.copyOf(seen);
    }
}
