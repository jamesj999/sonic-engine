package com.openggf.game.sonic2.dataselect;

import com.openggf.game.dataselect.DataSelectDestination;
import com.openggf.game.dataselect.DataSelectExitTransition;
import com.openggf.game.dataselect.DataSelectGameProfile;
import com.openggf.game.dataselect.HostSlotPreview;
import com.openggf.game.save.SaveSlotSummary;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.game.sonic2.audio.Sonic2Sfx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class S2DataSelectProfile implements DataSelectGameProfile {
    private static final List<DataSelectDestination> CLEAR_RESTARTS = List.of(
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_EHZ, 0),
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_CPZ, 0),
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_ARZ, 0),
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_CNZ, 0),
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_HTZ, 0),
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_MCZ, 0),
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_OOZ, 0),
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_MTZ, 0),
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_SCZ, 0),
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_WFZ, 0),
            new DataSelectDestination(Sonic2ZoneConstants.ZONE_DEZ, 0)
    );

    @Override public String gameCode() { return "s2"; }
    @Override public int slotCount() { return 8; }
    @Override public DataSelectExitTransition exitTransition() {
        return new DataSelectExitTransition(Sonic2Sfx.SPECIAL_STAGE_ENTRY.id, 0x28, 3, 1);
    }
    @Override public List<SelectedTeam> builtInTeams() {
        return List.of(
                new SelectedTeam("sonic", List.of()),
                new SelectedTeam("sonic", List.of("tails")),
                new SelectedTeam("knuckles", List.of())
        );
    }
    @Override public SaveSlotSummary summarizeFreshSlot(int slot) { return SaveSlotSummary.empty(slot); }

    @Override
    public List<SelectedTeam> parseExtraTeams(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<SelectedTeam> result = new ArrayList<>();
        for (String teamRaw : raw.split(";")) {
            List<String> parts = Arrays.stream(teamRaw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!parts.isEmpty()) {
                result.add(new SelectedTeam(parts.get(0), parts.subList(1, parts.size())));
            }
        }
        return result;
    }

    @Override
    public List<DataSelectDestination> clearRestartDestinations(Map<String, Object> payload) {
        return Boolean.TRUE.equals(payload.get("clear")) ? CLEAR_RESTARTS : List.of();
    }

    @Override
    public boolean isPayloadValid(Map<String, Object> payload) {
        return com.openggf.game.sonic1.dataselect.DataSelectPayloadValidators
                .validateCommonPayload(payload, 10, 7);
    }

    @Override
    public HostSlotPreview resolveSlotPreview(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object zoneObj = payload.get("zone");
        if (!(zoneObj instanceof Number zone)) {
            return null;
        }
        int zoneId = zone.intValue();
        return zoneId >= 0 && zoneId < CLEAR_RESTARTS.size()
                ? HostSlotPreview.numberedZone(zoneId + 1)
                : null;
    }

    @Override
    public int resolveSelectedSlotIconIndex(Map<String, Object> payload, DataSelectDestination clearDestination) {
        if (clearDestination != null) {
            return zoneToIconIndex(clearDestination.zone());
        }
        if (payload == null) {
            return -1;
        }
        Object zoneObj = payload.get("zone");
        if (!(zoneObj instanceof Number zone)) {
            return -1;
        }
        return zoneToIconIndex(zone.intValue());
    }

    private static int zoneToIconIndex(int zoneId) {
        return zoneId >= 0 && zoneId < CLEAR_RESTARTS.size() ? zoneId : -1;
    }
}
