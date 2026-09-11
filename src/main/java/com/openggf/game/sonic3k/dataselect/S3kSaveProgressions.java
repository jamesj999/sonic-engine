package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.dataselect.DataSelectDestination;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class S3kSaveProgressions {

    private S3kSaveProgressions() {
    }

    static int progressCodeForPayload(Map<String, Object> payload) {
        Object raw = payload.get("progressCode");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        int zone = readInt(payload, "zone", Sonic3kZoneIds.ZONE_AIZ);
        int act = readInt(payload, "act", 0);
        boolean clear = Boolean.TRUE.equals(payload.get("clear"));
        List<Integer> superEmeralds = readEmeraldList(payload, "superEmeralds");
        SelectedTeam team = selectedTeamFromPayload(payload);
        return progressCodeForState(zone, act, team, clear, superEmeralds);
    }

    static int progressCodeForState(int zone, int act, SelectedTeam team, boolean clear, List<Integer> superEmeralds) {
        if (clear) {
            if (isKnuckles(team)) {
                return 12;
            }
            return hasAllSuperEmeralds(superEmeralds) ? 14 : 13;
        }
        return switch (zone) {
            case Sonic3kZoneIds.ZONE_AIZ -> 1;
            case Sonic3kZoneIds.ZONE_HCZ -> 2;
            case Sonic3kZoneIds.ZONE_MGZ -> 3;
            case Sonic3kZoneIds.ZONE_CNZ -> 4;
            case Sonic3kZoneIds.ZONE_ICZ -> 5;
            case Sonic3kZoneIds.ZONE_LBZ -> 6;
            case Sonic3kZoneIds.ZONE_MHZ -> 7;
            case Sonic3kZoneIds.ZONE_FBZ -> 8;
            case Sonic3kZoneIds.ZONE_SOZ -> 9;
            case Sonic3kZoneIds.ZONE_LRZ -> 10;
            case Sonic3kZoneIds.ZONE_HPZ -> 11;
            case Sonic3kZoneIds.ZONE_SSZ -> 12;
            case Sonic3kZoneIds.ZONE_DEZ -> 13;
            case Sonic3kZoneIds.ZONE_DDZ -> 14;
            default -> Math.max(0, act) + 1;
        };
    }

    static int clearStateForPayload(Map<String, Object> payload) {
        Object raw = payload.get("clearState");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (!Boolean.TRUE.equals(payload.get("clear"))) {
            return 0;
        }
        return hasAllSuperEmeralds(readEmeraldList(payload, "superEmeralds")) ? 2 : 1;
    }

    static List<Integer> chaosEmeraldsForPayload(Map<String, Object> payload) {
        return readEmeraldList(payload, "chaosEmeralds");
    }

    static List<Integer> superEmeraldsForPayload(Map<String, Object> payload) {
        return readEmeraldList(payload, "superEmeralds");
    }

    static boolean hasAllSuperEmeralds(List<Integer> superEmeralds) {
        return superEmeralds.size() >= 7
                && superEmeralds.containsAll(List.of(0, 1, 2, 3, 4, 5, 6));
    }

    static List<DataSelectDestination> clearRestartDestinations(Map<String, Object> payload) {
        if (!Boolean.TRUE.equals(payload.get("clear"))) {
            return List.of();
        }

        SelectedTeam team = selectedTeamFromPayload(payload);
        int terminalIndex = terminalClearMarkerIndex(payload);
        if (terminalIndex <= 0) {
            return List.of();
        }
        List<DataSelectDestination> destinations = new ArrayList<>(terminalIndex);
        for (int index = 0; index < terminalIndex; index++) {
            destinations.add(destinationForIndex(index, team));
        }
        return destinations;
    }

    static int terminalClearMarkerIndex(Map<String, Object> payload) {
        if (!Boolean.TRUE.equals(payload.get("clear"))) {
            return -1;
        }
        SelectedTeam team = selectedTeamFromPayload(payload);
        int clearState = Math.max(0, clearStateForPayload(payload));
        return Math.max(0, maxSelectableIndex(team, clearState));
    }

    static int clearRestartSelectionCount(Map<String, Object> payload) {
        int terminalIndex = terminalClearMarkerIndex(payload);
        return terminalIndex < 0 ? 0 : terminalIndex + 1;
    }

    private static int maxSelectableIndex(SelectedTeam team, int clearState) {
        if (isKnuckles(team)) {
            return 11;
        }
        return clearState >= 2 ? 13 : 12;
    }

    private static DataSelectDestination destinationForIndex(int index, SelectedTeam team) {
        if (isKnuckles(team) && index == 11) {
            return new DataSelectDestination(Sonic3kZoneIds.ZONE_SSZ, 1);
        }
        return switch (index) {
            case 0 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_AIZ, 0);
            case 1 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_HCZ, 0);
            case 2 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_MGZ, 0);
            case 3 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_CNZ, 0);
            case 4 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_ICZ, 0);
            case 5 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_LBZ, 0);
            case 6 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_MHZ, 0);
            case 7 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_FBZ, 0);
            case 8 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_SOZ, 0);
            case 9 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_LRZ, 0);
            case 10 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_HPZ, 1);
            case 11 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_SSZ, 0);
            case 12 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_DEZ, 0);
            case 13 -> new DataSelectDestination(Sonic3kZoneIds.ZONE_DDZ, 0);
            default -> new DataSelectDestination(Sonic3kZoneIds.ZONE_AIZ, 0);
        };
    }

    private static boolean isKnuckles(SelectedTeam team) {
        return team != null && "knuckles".equalsIgnoreCase(team.mainCharacter());
    }

    private static SelectedTeam selectedTeamFromPayload(Map<String, Object> payload) {
        String main = String.valueOf(payload.getOrDefault("mainCharacter", "sonic"));
        Object sidekicksRaw = payload.get("sidekicks");
        List<String> sidekicks = sidekicksRaw instanceof List<?>
                ? ((List<?>) sidekicksRaw).stream().map(String::valueOf).toList()
                : List.of();
        return new SelectedTeam(main, sidekicks);
    }

    private static int readInt(Map<String, Object> payload, String key, int fallback) {
        Object value = payload.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static List<Integer> readEmeraldList(Map<String, Object> payload, String key) {
        Object raw = payload.get(key);
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (Object value : list) {
            if (value instanceof Number number) {
                int index = number.intValue();
                if (index >= 0 && index <= 6 && !result.contains(index)) {
                    result.add(index);
                }
            }
        }
        result.sort(Integer::compareTo);
        return List.copyOf(result);
    }
}
