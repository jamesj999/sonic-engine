package com.openggf.tests.trace.s3k;

import com.openggf.trace.TraceEvent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class S3kReplayCheckpointDetector {

    private static final List<String> REQUIRED_ORDER = List.of(
            "intro_begin",
            "gameplay_start",
            "aiz1_fire_transition_begin",
            "aiz2_reload_resume",
            "aiz2_main_gameplay",
            "hcz_handoff_complete");

    private final Set<String> emitted = new LinkedHashSet<>();

    public void seedCheckpoint(String checkpointName) {
        if (checkpointName == null || checkpointName.isBlank()) {
            return;
        }
        emitted.add(checkpointName);
    }

    public TraceEvent.Checkpoint observe(S3kCheckpointProbe probe) {
        if (!emitted.contains("intro_begin") && probe.replayFrame() == 0) {
            return emit(probe, "intro_begin");
        }
        if (!emitted.contains("gameplay_start")
                && probe.levelStarted()
                && isLevelGameplay(probe)
                && probe.moveLock() == 0
                && !probe.ctrlLocked()
                && !probe.objectControlled()
                && !probe.hidden()
                && probe.titleCardOverlayActive()) {
            return emit(probe, "gameplay_start");
        }
        if (!emitted.contains("aiz1_fire_transition_begin")
                && zoneActMatches(probe, 0, 0)
                && probe.eventsFg5()
                && probe.fireTransitionActive()) {
            return emit(probe, "aiz1_fire_transition_begin");
        }
        if (!emitted.contains("aiz2_reload_resume")
                && probe.actualZoneId() != null
                && probe.actualZoneId() == 0
                && probe.actualAct() != null
                && probe.actualAct() == 1
                && probe.apparentAct() != null
                && probe.apparentAct() == 0) {
            return emit(probe, "aiz2_reload_resume");
        }
        if (!emitted.contains("aiz2_main_gameplay")
                && probe.actualZoneId() != null
                && probe.actualZoneId() == 0
                && probe.actualAct() != null
                && probe.actualAct() == 1
                && probe.moveLock() == 0
                && !probe.ctrlLocked()) {
            return emit(probe, "aiz2_main_gameplay");
        }
        if (!emitted.contains("hcz_handoff_complete")
                && zoneActMatches(probe, 1, 0)
                && probe.moveLock() == 0
                && !probe.ctrlLocked()) {
            return emit(probe, "hcz_handoff_complete");
        }
        if (!emitted.contains("aiz2_signpost_begin")
                && probe.signpostActive()) {
            return emit(probe, "aiz2_signpost_begin");
        }
        if (!emitted.contains("aiz2_results_begin")
                && probe.resultsActive()) {
            return emit(probe, "aiz2_results_begin");
        }
        return null;
    }

    public Set<String> requiredCheckpointNamesReached() {
        Set<String> required = new LinkedHashSet<>();
        for (String checkpointName : REQUIRED_ORDER) {
            if (emitted.contains(checkpointName)) {
                required.add(checkpointName);
            }
        }
        return required;
    }

    private static boolean isLevelGameplay(S3kCheckpointProbe probe) {
        return probe.gameMode() != null && probe.gameMode() == 0x0C;
    }

    private static boolean zoneActMatches(S3kCheckpointProbe probe, int zoneId, int act) {
        return probe.actualZoneId() != null
                && probe.actualZoneId() == zoneId
                && probe.actualAct() != null
                && probe.actualAct() == act;
    }

    private TraceEvent.Checkpoint emit(S3kCheckpointProbe probe, String name) {
        emitted.add(name);
        return new TraceEvent.Checkpoint(
                probe.replayFrame(),
                name,
                probe.actualZoneId(),
                probe.actualAct(),
                probe.apparentAct(),
                probe.gameMode(),
                null);
    }
}
