package com.openggf.trace;

import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.resources.QueueServiceObservation;
import com.openggf.level.objects.RomObjectSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Comparison engine: compares engine sprite state against expected trace state
 * for a single frame, applying configurable tolerance thresholds.
 */
public class TraceBinder {
    private static final int TRACE_INPUT_UP = 0x01;
    private static final int TRACE_INPUT_DOWN = 0x02;
    private static final int TRACE_INPUT_LEFT = 0x04;
    private static final int TRACE_INPUT_RIGHT = 0x08;
    private static final int TRACE_INPUT_JUMP = 0x10;

    /**
     * Test-only override for the native-prelude-mode signal consumed by
     * {@link #compareBootstrapFrame0(TraceData, EngineSnapshot)}. When
     * non-{@code null}, the comparator uses this value instead of reading
     * {@code trace.metadata().hasNativePreludeBootstrap()}.
     *
     * <p>Test-only hook for synthetic {@link TraceData} fixtures that omit
     * the native-prelude capability. Production callers leave the override
     * {@code null} and read the explicit metadata capability.
     */
    private static volatile Boolean NATIVE_PRELUDE_OVERRIDE_FOR_TESTS = null;

    private final ToleranceConfig tolerances;
    // Keyed by frame number so re-comparisons of the same frame (e.g., during
    // held-rewind segment-cache rebuilds in test mode) replace the previous
    // entry instead of accumulating duplicates. Memory bounded by trace length.
    private final TreeMap<Integer, FrameComparison> comparisonsByFrame = new TreeMap<>();
    // Highest frame stored so far. Once the replay moves past a frame, aux
    // merges for it can no longer arrive, so its multi-KB ROM/engine
    // diagnostics strings are released if every field compared clean —
    // reports and context windows only ever render diagnostics for divergent
    // frames, and long complete-run segments would otherwise retain tens of
    // thousands of them.
    private int highestComparedFrame = Integer.MIN_VALUE;
    // Life-count change tracking. A death or a 1UP is only visible as a
    // *transition*, so alongside the level comparison the binder emits a
    // per-frame delta comparison whose mismatch names the exact frame the ROM
    // gained or lost a life. Updated strictly forwards so aux re-merges and
    // re-comparisons of earlier frames recompute the same delta.
    private int lastLivesFrame = Integer.MIN_VALUE;
    private int lastExpectedLives = TraceFrame.LIVES_ABSENT;
    private int lastActualLives = EngineDiagnostics.LIVES_ABSENT;
    // Baseline the current frame's delta was measured against, retained so a
    // re-put of the same frame reproduces the same lives_delta rather than
    // dropping the field.
    private int livesBaselineFrame = Integer.MIN_VALUE;
    private int livesBaselineExpected = TraceFrame.LIVES_ABSENT;
    private int livesBaselineActual = EngineDiagnostics.LIVES_ABSENT;
    private List<BootstrapDivergence> lastBootstrapDivergences = List.of();
    // One binder compares one replay segment, so it owns that segment's
    // dynamic-art delivery-id origin. See DynamicArtIdEpoch.
    private final DynamicArtIdEpoch dynamicArtIdEpoch = new DynamicArtIdEpoch();

    public TraceBinder(ToleranceConfig tolerances) {
        this.tolerances = tolerances;
    }

    /** Return the latest comparison captured for a frame, if any. */
    public FrameComparison comparisonForFrame(int frame) {
        return comparisonsByFrame.get(frame);
    }

    /**
     * Package-private hook used only by tests in this package to flip the
     * native-prelude-mode signal for synthetic {@link TraceData} fixtures
     * that don't carry the native-prelude capability.
     *
     * @param value {@code true} to force native-prelude mode on,
     *              {@code false} to force it off, {@code null} to fall back
     *              to {@link TraceMetadata#hasNativePreludeBootstrap()}.
     */
    static void setNativePreludeOverrideForTests(Boolean value) {
        NATIVE_PRELUDE_OVERRIDE_FOR_TESTS = value;
    }

    /**
     * Compare a single frame's expected trace values against actual engine values.
     * Accepts raw values extracted from the sprite to keep this class decoupled
     * from AbstractPlayableSprite.
     */
    public FrameComparison compareFrame(
            TraceFrame expected,
            short actualX, short actualY,
            short actualXSpeed, short actualYSpeed, short actualGSpeed,
            byte actualAngle, boolean actualAir, boolean actualRolling,
            int actualGroundMode) {
        return compareFrame(expected, actualX, actualY, actualXSpeed, actualYSpeed,
            actualGSpeed, actualAngle, actualAir, actualRolling, actualGroundMode, null);
    }

    /**
     * Compare a single frame with optional engine-side diagnostic context.
     * TraceBinder compares the diagnostic fields with stable engine parity data
     * and leaves the rest in the context window for cross-referencing.
     */
    public FrameComparison compareFrame(
            TraceFrame expected,
            short actualX, short actualY,
            short actualXSpeed, short actualYSpeed, short actualGSpeed,
            byte actualAngle, boolean actualAir, boolean actualRolling,
            int actualGroundMode,
            EngineDiagnostics engineDiag) {
        return compareFrame(expected, actualX, actualY, actualXSpeed, actualYSpeed,
            actualGSpeed, actualAngle, actualAir, actualRolling, actualGroundMode,
            null, engineDiag, null);
    }

    public FrameComparison compareFrame(
            TraceFrame expected,
            short actualX, short actualY,
            short actualXSpeed, short actualYSpeed, short actualGSpeed,
            byte actualAngle, boolean actualAir, boolean actualRolling,
            int actualGroundMode,
            String romDiagOverride,
            EngineDiagnostics engineDiag) {
        return compareFrame(expected, actualX, actualY, actualXSpeed, actualYSpeed,
            actualGSpeed, actualAngle, actualAir, actualRolling, actualGroundMode,
            romDiagOverride, engineDiag, null);
    }

    public FrameComparison compareFrame(
            TraceFrame expected,
            short actualX, short actualY,
            short actualXSpeed, short actualYSpeed, short actualGSpeed,
            byte actualAngle, boolean actualAir, boolean actualRolling,
            int actualGroundMode,
            String romDiagOverride,
            EngineDiagnostics engineDiag,
            TraceCharacterState actualSidekick) {
        return compareFrame(expected, actualX, actualY, actualXSpeed, actualYSpeed,
            actualGSpeed, actualAngle, actualAir, actualRolling, actualGroundMode,
            romDiagOverride, engineDiag, "sidekick", actualSidekick);
    }

    public FrameComparison compareFrame(
            TraceFrame expected,
            short actualX, short actualY,
            short actualXSpeed, short actualYSpeed, short actualGSpeed,
            byte actualAngle, boolean actualAir, boolean actualRolling,
            int actualGroundMode,
            String romDiagOverride,
            EngineDiagnostics engineDiag,
            String secondaryCharacterLabel,
            TraceCharacterState actualSidekick) {
        return compareFrame(expected, actualX, actualY, actualXSpeed, actualYSpeed,
            actualGSpeed, actualAngle, actualAir, actualRolling, actualGroundMode,
            romDiagOverride, engineDiag, secondaryCharacterLabel, actualSidekick, null, null, null);
    }

    public FrameComparison compareFrame(
            TraceFrame expected,
            short actualX, short actualY,
            short actualXSpeed, short actualYSpeed, short actualGSpeed,
            byte actualAngle, boolean actualAir, boolean actualRolling,
            int actualGroundMode,
            String romDiagOverride,
            EngineDiagnostics engineDiag,
            String secondaryCharacterLabel,
            TraceCharacterState actualSidekick,
            TraceEvent.CpuState expectedSidekickCpu,
            EngineSidekickCpuState actualSidekickCpu) {
        return compareFrame(expected, actualX, actualY, actualXSpeed, actualYSpeed,
                actualGSpeed, actualAngle, actualAir, actualRolling, actualGroundMode,
                romDiagOverride, engineDiag, secondaryCharacterLabel, actualSidekick,
                expectedSidekickCpu, actualSidekickCpu, null);
    }

    public FrameComparison compareFrame(
            TraceFrame expected,
            short actualX, short actualY,
            short actualXSpeed, short actualYSpeed, short actualGSpeed,
            byte actualAngle, boolean actualAir, boolean actualRolling,
            int actualGroundMode,
            String romDiagOverride,
            EngineDiagnostics engineDiag,
            String secondaryCharacterLabel,
            TraceCharacterState actualSidekick,
            TraceEvent.CpuState expectedSidekickCpu,
            EngineSidekickCpuState actualSidekickCpu,
            TraceEvent.TailsCpuNormalStep expectedSidekickNormalStep) {

        Map<String, FieldComparison> fields = new LinkedHashMap<>();

        // Position comparisons
        fields.put("x", compareNumeric("x", expected.x(), actualX,
            tolerances.positionWarn(), tolerances.positionError(), false));
        fields.put("y", compareNumeric("y", expected.y(), actualY,
            tolerances.positionWarn(), tolerances.positionError(), false));
        if (engineDiag != null && expected.hasExtendedData()
                && engineDiag.xSub() >= 0 && engineDiag.ySub() >= 0) {
            fields.put("x_sub", compareNumeric("x_sub",
                    expected.xSub() & 0xFFFF, engineDiag.xSub() & 0xFFFF,
                    0, 1, false));
            fields.put("y_sub", compareNumeric("y_sub",
                    expected.ySub() & 0xFFFF, engineDiag.ySub() & 0xFFFF,
                    0, 1, false));
        }

        // Speed comparisons
        fields.put("x_speed", compareNumeric("x_speed", expected.xSpeed(), actualXSpeed,
            tolerances.speedWarn(), tolerances.speedError(), tolerances.speedSignChangeIsError()));
        fields.put("y_speed", compareNumeric("y_speed", expected.ySpeed(), actualYSpeed,
            tolerances.speedWarn(), tolerances.speedError(), tolerances.speedSignChangeIsError()));
        fields.put("g_speed", compareNumeric("g_speed", expected.gSpeed(), actualGSpeed,
            tolerances.speedWarn(), tolerances.speedError(), tolerances.speedSignChangeIsError()));

        // Angle comparison (circular: 0xFC and 0x04 are 8 apart, not 248)
        fields.put("angle", compareAngle("angle",
            expected.angle() & 0xFF, actualAngle & 0xFF,
            tolerances.angleWarn(), tolerances.angleError()));

        // Boolean/enum flags: any mismatch is ERROR
        fields.put("air", compareFlag("air", expected.air(), actualAir));
        fields.put("rolling", compareFlag("rolling", expected.rolling(), actualRolling));

        // Derive ground_mode from angle for BOTH sides. The ROM has no stored ground_mode
        // variable; the Lua script and engine both compute it from angle. During airborne
        // frames the engine doesn't call updateGroundMode(), so the stored value is stale.
        // Deriving from angle makes the comparison symmetric and correct.
        int expectedGroundMode = deriveGroundMode(expected.angle() & 0xFF);
        int derivedActualGroundMode = deriveGroundMode(actualAngle & 0xFF);
        fields.put("ground_mode", compareEnum("ground_mode",
            expectedGroundMode, derivedActualGroundMode));

        if (engineDiag != null && expected.routine() >= 0 && engineDiag.routine() >= 0) {
            fields.put("routine", compareNumeric("routine",
                    expected.routine() & 0xFF, engineDiag.routine() & 0xFF,
                    0, 1, false));
        }
        if (engineDiag != null && expected.statusByte() >= 0 && engineDiag.statusByte() >= 0) {
            fields.put("status_byte", compareNumeric("status_byte",
                    expected.statusByte() & 0xFF, engineDiag.statusByte() & 0xFF,
                    0, 1, false));
        }

        if (expected.animationId() >= 0 && expected.mappingFrame() >= 0) {
            boolean animationPresent = engineDiag != null
                    && engineDiag.animationId() >= 0
                    && engineDiag.mappingFrame() >= 0;
            fields.put("player_animation_present", compareAnimationFlag(
                    "player_animation_present", true, animationPresent));
            if (animationPresent) {
                fields.put("player_animation_id", compareAnimationByte(
                        "player_animation_id", expected.animationId(), engineDiag.animationId()));
                fields.put("player_mapping_frame", compareAnimationByte(
                        "player_mapping_frame", expected.mappingFrame(), engineDiag.mappingFrame()));
            }
        }

        // Ring counts: only compared when BOTH the ROM trace and the engine
        // recorded them. The engine-side -1 is a genuine "no ring context"
        // signal for callers that collapse diagnostics without a sprite to
        // read (EMPTY, formattedOnly, formattedWithCamera); it is not a
        // blanket opt-out, and comparators holding a sprite must pass the
        // real count through.
        if (tolerances.ringCountMode() != ToleranceConfig.RingCountMode.DISABLED
                && expected.rings() >= 0 && engineDiag != null && engineDiag.rings() >= 0) {
            fields.put("rings", compareRingCount(expected.rings(), engineDiag.rings()));
        }

        appendLifeCountComparisons(fields, expected, engineDiag);

        // Camera coords: only compared when BOTH ROM trace and engine diagnostics
        // recorded them. Engine values are masked to 16 bits to align with ROM's
        // u16 Camera_X_pos / Camera_Y_pos representation across the sign boundary.
        if (engineDiag != null
                && expected.cameraX() >= 0 && expected.cameraY() >= 0
                && engineDiag.cameraX() >= 0 && engineDiag.cameraY() >= 0) {
            fields.put("camera_x", compareNumeric("camera_x",
                    expected.cameraX() & 0xFFFF, engineDiag.cameraX() & 0xFFFF,
                    tolerances.cameraWarn(), tolerances.cameraError(), false));
            fields.put("camera_y", compareNumeric("camera_y",
                    expected.cameraY() & 0xFFFF, engineDiag.cameraY() & 0xFFFF,
                    tolerances.cameraWarn(), tolerances.cameraError(), false));
        }

        String secondaryPrefix = normalizeCharacterPrefix(secondaryCharacterLabel);
        appendSidekickCpuComparisons(fields, secondaryPrefix,
                expectedSidekickCpu, actualSidekickCpu,
                expected.sidekick(), actualSidekick, expectedSidekickNormalStep);
        appendCharacterComparisons(fields,
            secondaryPrefix,
            expected.sidekick(), actualSidekick);

        // Store diagnostic context (ROM trace + engine side) for the context window.
        // These are NOT compared for pass/fail — they're for human debugging only.
        String romDiag = romDiagOverride != null
                ? romDiagOverride
                : expected.hasExtendedData() ? expected.formatDiagnostics() : "";
        String engDiag = engineDiag != null ? engineDiag.format() : "";

        FrameComparison result = new FrameComparison(expected.frame(), fields, romDiag, engDiag);
        putComparison(result);
        return result;
    }

    /**
     * Stores a comparison, releasing the diagnostics strings of the
     * previously-highest frame once the replay has moved past it and it
     * compared clean. Re-puts of the current frame (aux merges) and
     * re-comparisons of earlier frames leave prior entries untouched.
     */
    private void putComparison(FrameComparison result) {
        int frame = result.frame();
        if (frame > highestComparedFrame) {
            compactFinalizedFrame(highestComparedFrame);
            highestComparedFrame = frame;
        }
        comparisonsByFrame.put(frame, result);
    }

    private void compactFinalizedFrame(int frame) {
        FrameComparison finalized = comparisonsByFrame.get(frame);
        if (finalized == null
                || finalized.hasDivergence()
                || (finalized.romDiagnostics().isEmpty()
                        && finalized.engineDiagnostics().isEmpty())) {
            return;
        }
        comparisonsByFrame.put(frame,
                new FrameComparison(finalized.frame(), finalized.fields(), "", ""));
    }

    /**
     * Merges read-only auxiliary object comparisons into an already-captured
     * frame. This is used to surface true object/RNG frontiers before later
     * player-state symptoms. It never feeds trace data back into the engine.
     */
    public void compareObjectNear(
            int frame,
            List<TraceEvent.ObjectNear> expectedObjects,
            List<EngineNearbyObject> actualObjects) {
        if (expectedObjects == null || expectedObjects.isEmpty()) {
            return;
        }
        FrameComparison existing = comparisonsByFrame.get(frame);
        if (existing == null) {
            return;
        }

        Map<String, FieldComparison> fields = new LinkedHashMap<>(existing.fields());
        List<EngineNearbyObject> actualList = actualObjects != null ? actualObjects : List.of();
        List<Integer> matchedActualSlots = new ArrayList<>();
        List<Integer> expectedTypes = new ArrayList<>();

        for (TraceEvent.ObjectNear expected : expectedObjects) {
            if (expected.character() != null && !expected.character().isBlank()
                    && !"sonic".equalsIgnoreCase(expected.character())) {
                continue;
            }
            int slot = expected.slot();
            int expectedType = parseHexByte(expected.objectType());
            if (!containsInt(expectedTypes, expectedType)) {
                expectedTypes.add(expectedType);
            }
            EngineNearbyObject actual = findSemanticObjectMatch(expected, expectedType, actualList, matchedActualSlots);
            if (actual != null) {
                matchedActualSlots.add(actual.slot());
            }
            String prefix = String.format("obj_s%02X_", slot & 0xFF);
            if (actual != null) {
                fields.put(prefix + "slot", compareObjectField(
                        prefix + "slot",
                        formatHexByte(slot & 0xFF),
                        formatHexByte(actual.slot() & 0xFF)));
            }
            fields.put(prefix + "type", compareObjectField(
                    prefix + "type",
                    formatHexByte(expectedType),
                    actual == null ? "missing" : formatHexByte(actual.objectId() & 0xFF)));
            if (actual != null && expectedType == (actual.objectId() & 0xFF)) {
                fields.put(prefix + "x", compareObjectField(
                        prefix + "x",
                        formatHex16(expected.x() & 0xFFFF),
                        formatHex16(actual.currentX() & 0xFFFF)));
                fields.put(prefix + "y", compareObjectField(
                        prefix + "y",
                        formatHex16(expected.y() & 0xFFFF),
                        formatHex16(actual.currentY() & 0xFFFF)));
            }
        }

        for (EngineNearbyObject actual : actualList) {
            if (containsInt(matchedActualSlots, actual.slot())
                    || !containsInt(expectedTypes, actual.objectId() & 0xFF)) {
                continue;
            }
            String prefix = String.format("obj_extra_s%02X_", actual.slot() & 0xFF);
            fields.put(prefix + "type", compareObjectField(
                    prefix + "type",
                    "absent",
                    formatHexByte(actual.objectId() & 0xFF)));
            fields.put(prefix + "x", compareObjectField(
                    prefix + "x",
                    "absent",
                    formatHex16(actual.currentX() & 0xFFFF)));
            fields.put(prefix + "y", compareObjectField(
                    prefix + "y",
                    "absent",
                    formatHex16(actual.currentY() & 0xFFFF)));
        }

        comparisonsByFrame.put(frame, new FrameComparison(
                existing.frame(), fields, existing.romDiagnostics(), existing.engineDiagnostics()));
    }

    /**
     * Merges exact, comparison-only physical load queue fields into a captured
     * frame, creating a structural-only frame when gameplay was not compared.
     */
    public void compareLoadQueues(
            int frame,
            List<TraceEvent.LoadQueueState> expectedStates,
            List<QueueDiagnosticSnapshot> actualStates) {
        FrameComparison existing = comparisonsByFrame.get(frame);
        if (existing == null) {
            existing = new FrameComparison(frame, Map.of());
        }
        Map<String, TraceEvent.LoadQueueState> expectedByKind = new LinkedHashMap<>();
        for (TraceEvent.LoadQueueState state :
                expectedStates != null ? expectedStates : List.<TraceEvent.LoadQueueState>of()) {
            if (expectedByKind.put(state.kind(), state) != null) {
                throw new IllegalArgumentException(
                        "duplicate expected load queue kind: " + state.kind());
            }
        }
        Map<String, QueueDiagnosticSnapshot> actualByKind = new LinkedHashMap<>();
        for (QueueDiagnosticSnapshot state :
                actualStates != null ? actualStates : List.<QueueDiagnosticSnapshot>of()) {
            String kind = state.kind().wireName();
            if (actualByKind.put(kind, state) != null) {
                throw new IllegalArgumentException("duplicate actual load queue kind: " + kind);
            }
        }
        java.util.TreeSet<String> kinds = new java.util.TreeSet<>(expectedByKind.keySet());
        kinds.addAll(actualByKind.keySet());
        Map<String, FieldComparison> fields = new LinkedHashMap<>(existing.fields());
        for (String kind : kinds) {
            TraceEvent.LoadQueueState expected = expectedByKind.get(kind);
            QueueDiagnosticSnapshot actual = actualByKind.get(kind);
            String prefix = "queue." + kind + ".";
            fields.put(prefix + "present", compareObjectField(
                    prefix + "present", Boolean.toString(expected != null),
                    Boolean.toString(actual != null)));
            if (expected == null || actual == null) {
                continue;
            }
            putQueueField(fields, prefix + "busy", expected.busy(), actual.busy());
            putQueueField(fields, prefix + "prepared", expected.prepared(), actual.prepared());
            putQueueField(fields, prefix + "active_source",
                    expected.activeSource(), actual.activeSource());
            putQueueField(fields, prefix + "active_destination",
                    expected.activeDestination(), actual.activeDestination());
            putQueueField(fields, prefix + "total_work",
                    expected.totalWork(), actual.activeTotalWork());
            putQueueField(fields, prefix + "remaining_work",
                    expected.remainingWork(), actual.activeRemainingWork());
            putQueueField(fields, prefix + "queued_fingerprints",
                    String.join(",", expected.queuedFingerprints()),
                    String.join(",", actual.queuedFingerprints()));
            putQueueField(fields, prefix + "service_observations",
                    formatExpectedObservations(expected.serviceObservations()),
                    formatActualObservations(actual.serviceObservations()));
        }
        comparisonsByFrame.put(frame, new FrameComparison(
                existing.frame(), fields, existing.romDiagnostics(), existing.engineDiagnostics()));
    }

    /**
     * Merges dropped recorded hardware-completion edges into a row as an
     * exact comparison error. The recorded stream expected a completion the
     * engine never submitted; the edge released nothing, and this records
     * that fact at its own row instead of aborting the comparison.
     */
    public void compareRecordedHardwareCompletions(
            int frame, List<String> unmatched) {
        if (unmatched == null || unmatched.isEmpty()) {
            return;
        }
        FrameComparison existing = comparisonsByFrame.get(frame);
        if (existing == null) {
            existing = new FrameComparison(frame, Map.of());
        }
        Map<String, FieldComparison> fields = new LinkedHashMap<>(existing.fields());
        String name = "hardware_timing.unmatched_completions";
        fields.put(name, compareObjectField(name, "", String.join(" | ", unmatched)));
        comparisonsByFrame.put(frame, new FrameComparison(
                existing.frame(), fields,
                existing.romDiagnostics(), existing.engineDiagnostics()));
    }

    /**
     * Merges production submissions the recorded stream never completed into a
     * row as an exact comparison error. The submissions were never admitted or
     * released; this records that the run closed holding them instead of
     * aborting the comparison.
     */
    public void comparePendingRecordedHardwareSubmissions(List<String> pending) {
        if (pending == null || pending.isEmpty()) {
            return;
        }
        // The complaint belongs to the closing row, which is the last row this
        // comparison actually reached, so it can never displace an earlier
        // divergence as the first reported error.
        int frame = comparisonsByFrame.keySet().stream()
                .mapToInt(Integer::intValue).max().orElse(0);
        FrameComparison existing = comparisonsByFrame.get(frame);
        if (existing == null) {
            existing = new FrameComparison(frame, Map.of());
        }
        Map<String, FieldComparison> fields = new LinkedHashMap<>(existing.fields());
        String name = "hardware_timing.pending_submissions";
        fields.put(name, compareObjectField(name, "", String.join(" | ", pending)));
        comparisonsByFrame.put(frame, new FrameComparison(
                existing.frame(), fields,
                existing.romDiagnostics(), existing.engineDiagnostics()));
    }

    /**
     * Merges exact player dynamic-art lifecycle fields into this row.
     *
     * <p>A DPLC heartbeat may be the only compared surface on lag and
     * special-stage rows, so this method creates a comparison when ordinary
     * gameplay comparison did not run.
     */
    public FrameComparison compareDynamicArt(
            TraceEvent.DynamicArtTransferState expected,
            DynamicArtDiagnosticsSnapshot actual) {
        FrameComparison existing = comparisonsByFrame.get(expected.frame());
        Map<String, FieldComparison> fields = existing != null
                ? new LinkedHashMap<>(existing.fields())
                : new LinkedHashMap<>();
        fields.putAll(DynamicArtSpecialStageComparator.comparisonFields(
                expected, actual, dynamicArtIdEpoch));
        FrameComparison result = new FrameComparison(
                expected.frame(), fields,
                existing != null ? existing.romDiagnostics() : "",
                existing != null ? existing.engineDiagnostics() : "");
        putComparison(result);
        return result;
    }

    private static void putQueueField(
            Map<String, FieldComparison> fields, String name, Object expected, Object actual) {
        fields.put(name, compareObjectField(
                name, String.valueOf(expected), String.valueOf(actual)));
    }

    private static String formatExpectedObservations(
            List<TraceEvent.LoadQueueState.ServiceObservation> observations) {
        return observations.stream()
                .map(value -> value.boundary() + ":" + value.budget())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String formatActualObservations(
            List<QueueServiceObservation> observations) {
        return observations.stream()
                .map(value -> value.boundary() + ":" + value.budget())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static EngineNearbyObject findSemanticObjectMatch(
            TraceEvent.ObjectNear expected,
            int expectedType,
            List<EngineNearbyObject> actualObjects,
            List<Integer> matchedActualSlots) {
        int expectedX = expected.x() & 0xFFFF;
        int expectedY = expected.y() & 0xFFFF;
        for (EngineNearbyObject actual : actualObjects) {
            if (containsInt(matchedActualSlots, actual.slot())) {
                continue;
            }
            if ((actual.objectId() & 0xFF) == expectedType
                    && (actual.currentX() & 0xFFFF) == expectedX
                    && (actual.currentY() & 0xFFFF) == expectedY) {
                return actual;
            }
        }
        return null;
    }

    private static boolean containsInt(List<Integer> values, int target) {
        for (int value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private static FieldComparison compareObjectField(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            return new FieldComparison(name, expected, actual, Severity.MATCH, 0);
        }
        return new FieldComparison(name, expected, actual, Severity.ERROR, 1);
    }

    private static int parseHexByte(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        String normalized = value.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        try {
            return Integer.parseInt(normalized, 16) & 0xFF;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String formatHexByte(int value) {
        return value < 0 ? "missing" : String.format("0x%02X", value & 0xFF);
    }

    private static String formatHex16(int value) {
        return String.format("0x%04X", value & 0xFFFF);
    }

    /**
     * Build a divergence report from all comparisons accumulated so far.
     * Includes any bootstrap (frame-0) divergences captured via
     * {@link #compareBootstrapFrame0(TraceData, EngineSnapshot)}.
     */
    public DivergenceReport buildReport() {
        return new DivergenceReport(
                new ArrayList<>(comparisonsByFrame.values()), null, lastBootstrapDivergences);
    }

    /**
     * Build a divergence report from all comparisons accumulated so far,
     * enriched with trace-side checkpoint and zone/act context. Includes any
     * bootstrap (frame-0) divergences captured via
     * {@link #compareBootstrapFrame0(TraceData, EngineSnapshot)}.
     */
    public DivergenceReport buildReport(TraceData trace) {
        return new DivergenceReport(
                new ArrayList<>(comparisonsByFrame.values()), trace, lastBootstrapDivergences);
    }

    /**
     * Validate that BK2-derived input matches trace-embedded input.
     * Returns true if they match, false on mismatch (alignment error).
     */
    public boolean validateInput(TraceFrame frame, int bk2Input) {
        return frame.input() == bk2Input;
    }

    /**
     * Compares the ROM's recorded {@code SlotMachineVariables} against the
     * engine's. Comparison-only: nothing here writes engine state.
     *
     * <p>Every field is an exact ROM byte, so any mismatch is a real
     * divergence with no tolerance and is reported at ERROR. The reel speeds
     * and the roll timer are the
     * load-bearing ones:
     * the ROM computes them from {@code V_int_run_count} by byte arithmetic
     * (s2.asm:59360-59375), so they pin the exact counter {@code SlotMachine}
     * consumed and make a clock or call-ordering error at this site visible on
     * the frame it happens instead of thousands of rows later.
     *
     * <p>{@code reward} is deliberately not compared. The ROM's
     * {@code SlotMachine_Reward} is decremented as prizes spawn
     * (s2.asm:59190) while the engine's field holds the once-computed payout,
     * so the two are different quantities despite the shared name.
     */
    /**
     * Compares ObjB2's recorded SST against the engine's. Comparison-only.
     *
     * <p>Emitted at WARNING for the same reason as the CNZ slot block: brand-new
     * coverage of a stream nothing has ever compared, so its frontier is
     * untriaged. Promote to ERROR once the divergences are zero.
     */
    public void compareS2Tornado(
            int frame,
            TraceEvent.S2TornadoState expected,
            com.openggf.game.sonic2.objects.TornadoObjectInstance.Snapshot actual) {
        if (expected == null || actual == null) {
            return;
        }
        FrameComparison existing = comparisonsByFrame.get(frame);
        if (existing == null) {
            existing = new FrameComparison(frame, Map.of());
        }
        Map<String, FieldComparison> fields = new LinkedHashMap<>(existing.fields());
        putTornadoField(fields, "tornado.x", expected.x(), actual.x());
        putTornadoField(fields, "tornado.y", expected.y(), actual.y());
        putTornadoField(fields, "tornado.y_sub", expected.ySub(), actual.ySub());
        putTornadoField(fields, "tornado.y_vel", expected.yVel(), actual.yVel());
        putTornadoField(fields, "tornado.routine", expected.routine(), actual.routine());
        putTornadoField(fields, "tornado.routine_secondary",
                expected.routineSecondary(), actual.routineSecondary());
        putTornadoField(fields, "tornado.status_byte", expected.statusByte(), actual.statusByte());
        putTornadoField(fields, "tornado.objoff_2e", expected.objoff2E(), actual.objoff2E());
        putTornadoField(fields, "tornado.objoff_2f", expected.objoff2F(), actual.objoff2F());
        putTornadoField(fields, "tornado.objoff_30", expected.objoff30(), actual.objoff30());
        putTornadoField(fields, "tornado.objoff_31", expected.objoff31(), actual.objoff31());
        comparisonsByFrame.put(frame, new FrameComparison(
                existing.frame(), fields, existing.romDiagnostics(), existing.engineDiagnostics()));
    }

    /**
     * Emits one Tornado field at WARNING rather than ERROR.
     *
     * <p>Deliberate and temporary. This comparison is brand-new coverage of a
     * block the recording has always carried and nothing ever read, so its
     * frontier has never been triaged: turning it on at ERROR would take two
     * currently-green release-scope classes red for divergences that predate
     * the comparison and that no round has owned. Warnings keep every one of
     * them visible in the report, which is the whole point of wiring it in,
     * without converting an untriaged unknown into a release blocker.
     *
     * <p>The CNZ slot block is now exact and uses {@link #putSlotField}; keep
     * this helper separate until ObjB2's child-presence identity boundary is
     * closed.
     */
    private void putTornadoField(
            Map<String, FieldComparison> fields, String name, int expected, int actual) {
        boolean match = expected == actual;
        fields.put(name, new FieldComparison(
                name,
                String.format("0x%04X", expected & 0xFFFF),
                String.format("0x%04X", actual & 0xFFFF),
                match ? Severity.MATCH : Severity.WARNING,
                actual - expected));
    }

    private void putSlotField(
            Map<String, FieldComparison> fields, String name, int expected, int actual) {
        boolean match = expected == actual;
        fields.put(name, new FieldComparison(
                name,
                String.format("0x%04X", expected & 0xFFFF),
                String.format("0x%04X", actual & 0xFFFF),
                match ? Severity.MATCH : Severity.ERROR,
                actual - expected));
    }

    public void compareCnzSlotMachine(
            int frame,
            TraceEvent.CnzSlotMachineState expected,
            com.openggf.game.sonic2.slotmachine.CNZSlotMachineManager.Snapshot actual) {
        if (expected == null || actual == null) {
            return;
        }
        FrameComparison existing = comparisonsByFrame.get(frame);
        if (existing == null) {
            existing = new FrameComparison(frame, Map.of());
        }
        Map<String, FieldComparison> fields = new LinkedHashMap<>(existing.fields());
        putSlotField(fields, "cnz_slot.in_use",
                expected.inUse() ? 1 : 0, actual.inUse() ? 1 : 0);
        putSlotField(fields, "cnz_slot.routine", expected.routine(), actual.routine());
        putSlotField(fields, "cnz_slot.timer", expected.timer(), actual.timer());
        putSlotField(fields, "cnz_slot.index", expected.index(), actual.index());
        for (int i = 0; i < 3; i++) {
            int slot = i + 1;
            putSlotField(fields, "cnz_slot.slot" + slot + "_speed",
                    expected.slotSpeed().get(i), actual.slotSpeed().get(i));
            putSlotField(fields, "cnz_slot.slot" + slot + "_pos",
                    expected.slotPos().get(i), actual.slotPos().get(i));
            putSlotField(fields, "cnz_slot.slot" + slot + "_routine",
                    expected.slotRoutine().get(i), actual.slotRoutine().get(i));
        }
        comparisonsByFrame.put(frame, new FrameComparison(
                existing.frame(), fields, existing.romDiagnostics(), existing.engineDiagnostics()));
    }

    private FieldComparison compareNumeric(String name, int expected, int actual,
            int warn, int error, boolean signChangeIsError) {
        int delta = Math.abs(expected - actual);

        // Check sign change (both nonzero, different signs)
        if (signChangeIsError && expected != 0 && actual != 0) {
            boolean expectedNeg = (expected < 0) || (expected > 0x7FFF);
            boolean actualNeg = (actual < 0) || (actual > 0x7FFF);
            if (expectedNeg != actualNeg) {
                return new FieldComparison(name,
                    formatHex(expected), formatHex(actual), Severity.ERROR, delta);
            }
        }

        Severity severity = tolerances.classify(delta, warn, error);
        return new FieldComparison(name,
            formatHex(expected), formatHex(actual), severity, delta);
    }

    private FieldComparison compareNumericEither(String name, int expected, int alternateExpected,
            int actual, int warn, int error, boolean signChangeIsError) {
        if (actual == expected || actual == alternateExpected) {
            return new FieldComparison(name, formatHex(actual), formatHex(actual),
                    Severity.MATCH, 0);
        }
        FieldComparison primary = compareNumeric(name, expected, actual, warn, error, signChangeIsError);
        if (!primary.isDivergent()) {
            return primary;
        }
        FieldComparison alternate = compareNumeric(name, alternateExpected, actual, warn, error, signChangeIsError);
        return alternate.severity().ordinal() < primary.severity().ordinal()
                ? alternate
                : primary;
    }

    private FieldComparison compareFlag(String name, boolean expected, boolean actual) {
        if (expected == actual) {
            return new FieldComparison(name,
                String.valueOf(expected ? 1 : 0), String.valueOf(actual ? 1 : 0),
                Severity.MATCH, 0);
        }
        return new FieldComparison(name,
            String.valueOf(expected ? 1 : 0), String.valueOf(actual ? 1 : 0),
            Severity.ERROR, 1);
    }

    private FieldComparison compareEnum(String name, int expected, int actual) {
        if (expected == actual) {
            return new FieldComparison(name,
                String.valueOf(expected), String.valueOf(actual), Severity.MATCH, 0);
        }
        return new FieldComparison(name,
            String.valueOf(expected), String.valueOf(actual),
            Severity.ERROR, Math.abs(expected - actual));
    }


    /**
     * Compare the ROM life counter against the engine's.
     *
     * <p>Two fields are emitted, and deliberately not folded into one:
     * <ul>
     *   <li>{@code lives} -- the level. A missed or spurious death leaves the two
     *       sides disagreeing from the transition onwards, so this catches the
     *       condition even if the replay is only inspected later.</li>
     *   <li>{@code lives_delta} -- the transition. This is the field that answers
     *       "on which frame". It compares the per-frame change rather than the
     *       running total, so exactly the frame where the ROM gained or lost a
     *       life (and the engine did not, or vice versa) reports ERROR instead of
     *       every frame after it.</li>
     * </ul>
     *
     * <p>When the recording carries {@code life_count} but the engine snapshot has
     * no life count, {@code lives_present} reports ERROR rather than the
     * comparison silently disappearing. Recordings made before the column existed
     * carry {@link TraceFrame#LIVES_ABSENT} and emit no fields at all -- that is
     * the only path on which nothing is compared, and it is keyed on the recording,
     * not on the engine.
     *
     * <p>Comparison-only: nothing here writes the recorded count anywhere.
     */
    private void appendLifeCountComparisons(Map<String, FieldComparison> fields,
            TraceFrame expected, EngineDiagnostics engineDiag) {
        int expectedLives = expected.lives();
        if (expectedLives < 0) {
            // Pre-life_count recording: no life data was recorded to compare against.
            return;
        }
        int actualLives = engineDiag == null
                ? EngineDiagnostics.LIVES_ABSENT
                : engineDiag.lives();
        boolean enginePresent = actualLives >= 0;
        fields.put("lives_present", compareFlag("lives_present", true, enginePresent));
        if (!enginePresent) {
            return;
        }

        fields.put("lives", compareDecimal("lives", expectedLives, actualLives));

        if (expected.frame() > lastLivesFrame) {
            livesBaselineFrame = expected.frame();
            livesBaselineExpected = lastExpectedLives;
            livesBaselineActual = lastActualLives;
            lastLivesFrame = expected.frame();
            lastExpectedLives = expectedLives;
            lastActualLives = actualLives;
        }
        if (expected.frame() == livesBaselineFrame
                && livesBaselineExpected >= 0 && livesBaselineActual >= 0) {
            fields.put("lives_delta", compareDecimal("lives_delta",
                    expectedLives - livesBaselineExpected,
                    actualLives - livesBaselineActual));
        }
    }

    /**
     * Exact comparison rendered in decimal. Life counts and their per-frame
     * deltas are read by humans as counts, not as ROM words, and a delta of
     * {@code -1} must not print as {@code -0001}.
     */
    private static FieldComparison compareDecimal(String name, int expected, int actual) {
        Severity severity = expected == actual ? Severity.MATCH : Severity.ERROR;
        return new FieldComparison(name, String.valueOf(expected), String.valueOf(actual),
                severity, Math.abs(expected - actual));
    }

    private FieldComparison compareRingCount(int expected, int actual) {
        if (expected == actual) {
            return new FieldComparison("rings",
                String.valueOf(expected), String.valueOf(actual), Severity.MATCH, 0);
        }
        Severity severity = tolerances.ringCountMode() == ToleranceConfig.RingCountMode.WARN_ONLY
            ? Severity.WARNING
            : Severity.ERROR;
        return new FieldComparison("rings",
            String.valueOf(expected), String.valueOf(actual),
            severity, Math.abs(expected - actual));
    }

    private FieldComparison compareAngle(String name, int expected, int actual,
            int warn, int error) {
        // Circular distance on a 256-unit ring
        int rawDelta = Math.abs(expected - actual);
        int delta = Math.min(rawDelta, 256 - rawDelta);

        Severity severity = tolerances.classify(delta, warn, error);
        return new FieldComparison(name,
            formatHex(expected), formatHex(actual), severity, delta);
    }

    /**
     * Derive ground mode from angle using ROM quadrant thresholds.
     * Floor wraps: 0xE0-0xFF and 0x00-0x1F are both mode 0.
     */
    static int deriveGroundMode(int angle) {
        if (angle <= 0x1F || angle >= 0xE0) return 0;  // floor
        if (angle <= 0x5F) return 1;                     // right wall
        if (angle <= 0x9F) return 2;                     // ceiling
        return 3;                                         // left wall
    }

    private void appendCharacterComparisons(Map<String, FieldComparison> fields, String prefix,
            TraceCharacterState expected, TraceCharacterState actual) {
        if (expected == null && actual == null) {
            return;
        }
        boolean expectedPresent = expected != null && expected.present();
        boolean actualPresent = actual != null && actual.present();
        fields.put(prefix + "present",
            compareFlag(prefix + "present", expectedPresent, actualPresent));
        boolean expectedAnimation = expected != null
                && expected.animationId() >= 0 && expected.mappingFrame() >= 0;
        if (expectedAnimation) {
            boolean actualAnimationPresent = actualPresent
                    && actual.animationId() >= 0 && actual.mappingFrame() >= 0;
            fields.put(prefix + "animation_present", compareAnimationFlag(
                    prefix + "animation_present", expectedPresent, actualAnimationPresent));
        }
        if (!expectedPresent || !actualPresent) {
            return;
        }

        fields.put(prefix + "x", compareNumeric(prefix + "x", expected.x(), actual.x(),
            tolerances.positionWarn(), tolerances.positionError(), false));
        fields.put(prefix + "y", compareNumeric(prefix + "y", expected.y(), actual.y(),
            tolerances.positionWarn(), tolerances.positionError(), false));
        fields.put(prefix + "x_sub", compareNumeric(prefix + "x_sub",
                expected.xSub() & 0xFFFF, actual.xSub() & 0xFFFF,
                0, 1, false));
        fields.put(prefix + "y_sub", compareNumeric(prefix + "y_sub",
                expected.ySub() & 0xFFFF, actual.ySub() & 0xFFFF,
                0, 1, false));
        fields.put(prefix + "x_speed",
            compareNumeric(prefix + "x_speed", expected.xSpeed(), actual.xSpeed(),
                tolerances.speedWarn(), tolerances.speedError(),
                tolerances.speedSignChangeIsError()));
        fields.put(prefix + "y_speed",
            compareNumeric(prefix + "y_speed", expected.ySpeed(), actual.ySpeed(),
                tolerances.speedWarn(), tolerances.speedError(),
                tolerances.speedSignChangeIsError()));
        fields.put(prefix + "g_speed",
            compareNumeric(prefix + "g_speed", expected.gSpeed(), actual.gSpeed(),
                tolerances.speedWarn(), tolerances.speedError(),
                tolerances.speedSignChangeIsError()));
        fields.put(prefix + "angle", compareAngle(prefix + "angle",
            expected.angle() & 0xFF, actual.angle() & 0xFF,
            tolerances.angleWarn(), tolerances.angleError()));
        fields.put(prefix + "air", compareFlag(prefix + "air", expected.air(), actual.air()));
        fields.put(prefix + "rolling",
            compareFlag(prefix + "rolling", expected.rolling(), actual.rolling()));
        fields.put(prefix + "ground_mode", compareEnum(prefix + "ground_mode",
            deriveGroundMode(expected.angle() & 0xFF),
            deriveGroundMode(actual.angle() & 0xFF)));
        if (expected.routine() >= 0 && actual.routine() >= 0) {
            fields.put(prefix + "routine", compareNumeric(prefix + "routine",
                    expected.routine() & 0xFF, actual.routine() & 0xFF,
                    0, 1, false));
        }
        if (expected.statusByte() >= 0 && actual.statusByte() >= 0) {
            if (isSidekickHurtOnObjectOnlyStatusMismatch(expected, actual)
                    || isGroundedSidekickOnObjectPushOnlyStatusMismatch(expected, actual)
                    || isStationaryReleasedSidekickPushOnlyStatusMismatch(expected, actual)
                    || isGroundedSidekickPushOnlyStatusMismatch(expected, actual)
                    || isInactiveSidekickDespawnMarkerFacingOnlyStatusMismatch(expected, actual)
                    || isStationarySidekickOnObjectFacingOnlyStatusMismatch(expected, actual)
                    || isAirborneSidekickZeroHorizontalSpeedFacingOnlyStatusMismatch(expected, actual)) {
                fields.put(prefix + "status_byte", ignoredSidekickStatus(
                        prefix + "status_byte",
                        expected.statusByte() & 0xFF, actual.statusByte() & 0xFF));
            } else {
                fields.put(prefix + "status_byte", compareNumeric(prefix + "status_byte",
                        expected.statusByte() & 0xFF, actual.statusByte() & 0xFF,
                        0, 1, false));
            }
        }
        if (expected.animationId() >= 0 && expected.mappingFrame() >= 0
                && actual.animationId() >= 0 && actual.mappingFrame() >= 0) {
            fields.put(prefix + "animation_id", compareAnimationByte(
                    prefix + "animation_id", expected.animationId(), actual.animationId()));
            fields.put(prefix + "mapping_frame", compareAnimationByte(
                    prefix + "mapping_frame", expected.mappingFrame(), actual.mappingFrame()));
        }
    }

    private static FieldComparison compareAnimationByte(String name, int expected, int actual) {
        int expectedByte = expected & 0xFF;
        int actualByte = actual & 0xFF;
        Severity severity = expectedByte == actualByte ? Severity.MATCH : Severity.ERROR;
        return FieldComparison.animation(name, formatHex(expectedByte), formatHex(actualByte),
                severity, Math.abs(expectedByte - actualByte));
    }

    private static FieldComparison compareAnimationFlag(
            String name, boolean expected, boolean actual) {
        Severity severity = expected == actual ? Severity.MATCH : Severity.ERROR;
        return FieldComparison.animation(name, expected ? "1" : "0", actual ? "1" : "0",
                severity, expected == actual ? 0 : 1);
    }

    private void appendSidekickCpuComparisons(Map<String, FieldComparison> fields, String prefix,
            TraceEvent.CpuState expected, EngineSidekickCpuState actual,
            TraceCharacterState expectedSidekick, TraceCharacterState actualSidekick,
            TraceEvent.TailsCpuNormalStep expectedNormalStep) {
        if (expected == null || expectedSidekick != null && !expectedSidekick.present()) {
            return;
        }
        boolean actualPresent = actual != null;
        fields.put(prefix + "cpu_present",
                compareFlag(prefix + "cpu_present", true, actualPresent));
        if (!actualPresent) {
            return;
        }

        fields.put(prefix + "cpu_routine", compareNumeric(prefix + "cpu_routine",
                expected.cpuRoutine(), actual.cpuRoutine(), 0, 1, false));
        fields.put(prefix + "cpu_control_counter", compareNumeric(prefix + "cpu_control_counter",
                expected.idleTimer(), actual.controlCounter(), 0, 1, false));
        fields.put(prefix + "cpu_respawn_counter", compareNumeric(prefix + "cpu_respawn_counter",
                expected.flightTimer(), actual.respawnCounter(), 0, 1, false));
        if (isInactiveStaleSidekickInteract(expected, actual, expectedSidekick, actualSidekick)
                || isLandingFrameSidekickInteractMirrorLag(expected, actual,
                        expectedSidekick, actualSidekick)
                || isLandingFrameSidekickInteractIdRefreshLag(expected, actual,
                        expectedSidekick, actualSidekick)) {
            fields.put(prefix + "cpu_interact", ignoredStaleSidekickInteract(prefix + "cpu_interact",
                    expected.interact() & 0xFF, actual.interact()));
        } else {
            fields.put(prefix + "cpu_interact", compareNumeric(prefix + "cpu_interact",
                    expected.interact() & 0xFF, actual.interact(), 0, 1, false));
        }
        fields.put(prefix + "cpu_target_x", compareNumeric(prefix + "cpu_target_x",
                expected.targetX() & 0xFFFF, actual.targetX() & 0xFFFF, 0, 1, false));
        fields.put(prefix + "cpu_target_y", compareNumeric(prefix + "cpu_target_y",
                expected.targetY() & 0xFFFF, actual.targetY() & 0xFFFF, 0, 1, false));
        int expectedHeld = normalizeRomCtrl2LogicalByte(expected.ctrl2Held());
        int expectedHeldAlternate = expectedNormalStep != null
                ? normalizeRomCtrl2LogicalByte(expectedNormalStep.ctrl2Logical())
                : expectedHeld;
        int expectedPressed = normalizeRomCtrl2PressedByte(expected.ctrl2Pressed());
        int expectedPressedAlternate = expectedNormalStep != null
                ? normalizeRomCtrl2PressedByte(expectedNormalStep.ctrl2Logical())
                : expectedPressed;
        int actualHeldLive = actual.generatedHeld() & 0xFF;
        int actualHeldDecision = actual.normalStepHeld() & 0xFF;
        boolean heldDecisionMatchesRecordedCpuSample = expectedNormalStep != null
                && actual.normalStepHeld() >= 0
                && (actualHeldDecision == expectedHeld
                    || actualHeldDecision == expectedHeldAlternate);
        int actualHeld = heldDecisionMatchesRecordedCpuSample
                ? actualHeldDecision
                : actualHeldLive;
        int actualPressedLive = normalizeEngineCtrl2PressedByte(actual.generatedPressed());
        int actualPressedDecision = normalizeEngineCtrl2PressedByte(actual.normalStepPressed());
        boolean pressedDecisionMatchesRecordedCpuSample = expectedNormalStep != null
                && actual.normalStepPressed() >= 0
                && (actualPressedDecision == expectedPressed
                    || actualPressedDecision == expectedPressedAlternate);
        int actualPressed = pressedDecisionMatchesRecordedCpuSample
                ? actualPressedDecision
                : actualPressedLive;
        if (isCoastingPanicCtrl2Latch(expected, expectedSidekick, actualSidekick)) {
            fields.put(prefix + "cpu_ctrl2_held", ignoredLatchedCtrl2(prefix + "cpu_ctrl2_held",
                    expectedHeld, actualHeld));
            fields.put(prefix + "cpu_ctrl2_pressed", ignoredLatchedCtrl2(prefix + "cpu_ctrl2_pressed",
                    expectedPressed, actualPressed));
        } else if (isSidekickCtrl2HeldOnlyNoop(expected, expectedNormalStep,
                expectedSidekick, actualSidekick, expectedHeld, actualHeld, expectedPressed, actualPressed)) {
            fields.put(prefix + "cpu_ctrl2_held", ignoredLatchedCtrl2(prefix + "cpu_ctrl2_held",
                    expectedHeld, actualHeld));
            fields.put(prefix + "cpu_ctrl2_pressed", compareNumericEither(prefix + "cpu_ctrl2_pressed",
                    expectedPressed, expectedPressedAlternate, actualPressed, 0, 1, false));
        } else {
            fields.put(prefix + "cpu_ctrl2_held", compareNumericEither(prefix + "cpu_ctrl2_held",
                    expectedHeld, expectedHeldAlternate, actualHeld, 0, 1, false));
            fields.put(prefix + "cpu_ctrl2_pressed", compareNumericEither(prefix + "cpu_ctrl2_pressed",
                    expectedPressed, expectedPressedAlternate, actualPressed, 0, 1, false));
        }
        fields.put(prefix + "cpu_jumping", compareNumeric(prefix + "cpu_jumping",
                expected.autoJumpFlag() & 0xFF, actual.jumpingFlag() & 0xFF, 0, 1, false));
        if (expected.cpuRoutine() == 0x06 && actual.cpuRoutine() == 0x06
                && expected.delayedIndex() >= 0
                && actual.followHistorySlot() >= 0
                && sidekickObjectControlRoutineRan(expectedSidekick, actualSidekick)) {
            fields.put(prefix + "cpu_follow_ring", compareNumeric(prefix + "cpu_follow_ring",
                    romHistoryByteOffsetToSlot(expected.delayedIndex()),
                    actual.followHistorySlot(), 0, 1, false));
        }
    }

    private static boolean sidekickObjectControlRoutineRan(
            TraceCharacterState expectedSidekick, TraceCharacterState actualSidekick) {
        return expectedSidekick != null
                && actualSidekick != null
                && expectedSidekick.present()
                && actualSidekick.present()
                && expectedSidekick.routine() == 0x02
                && actualSidekick.routine() == 0x02;
    }

    private static boolean isCoastingPanicCtrl2Latch(
            TraceEvent.CpuState expected,
            TraceCharacterState expectedSidekick,
            TraceCharacterState actualSidekick) {
        if (expected.cpuRoutine() != 0x08
                || expectedSidekick == null
                || actualSidekick == null
                || !expectedSidekick.present()
                || !actualSidekick.present()) {
            return false;
        }
        return expectedSidekick.gSpeed() != 0 && actualSidekick.gSpeed() != 0;
    }

    private static boolean isSidekickCtrl2HeldOnlyNoop(
            TraceEvent.CpuState expected,
            TraceEvent.TailsCpuNormalStep expectedNormalStep,
            TraceCharacterState expectedSidekick,
            TraceCharacterState actualSidekick,
            int expectedHeld,
            int actualHeld,
            int expectedPressed,
            int actualPressed) {
        int heldDelta = (expectedHeld ^ actualHeld) & 0xFF;
        int inputMask = TRACE_INPUT_UP | TRACE_INPUT_DOWN | TRACE_INPUT_LEFT
                | TRACE_INPUT_RIGHT | TRACE_INPUT_JUMP;
        boolean jumpOnlyDelta = heldDelta == TRACE_INPUT_JUMP;
        boolean directionalOnlyDelta = heldDelta != 0
                && (heldDelta & ~(TRACE_INPUT_LEFT | TRACE_INPUT_RIGHT)) == 0;
        if (expectedNormalStep != null
                || expected.cpuRoutine() != 0x06
                || expected.ctrl2RawHeld() != 0
                || heldDelta == 0
                || expectedPressed != actualPressed
                || expectedSidekick == null
                || actualSidekick == null
                || !expectedSidekick.present()
                || !actualSidekick.present()
                || (heldDelta & ~inputMask) != 0) {
            return false;
        }
        return expectedSidekick.statusByte() == actualSidekick.statusByte()
                && expectedSidekick.routine() == actualSidekick.routine()
                && expectedSidekick.x() == actualSidekick.x()
                && expectedSidekick.y() == actualSidekick.y()
                && expectedSidekick.xSub() == actualSidekick.xSub()
                && expectedSidekick.ySub() == actualSidekick.ySub()
                && expectedSidekick.xSpeed() == actualSidekick.xSpeed()
                && expectedSidekick.ySpeed() == actualSidekick.ySpeed()
                && expectedSidekick.gSpeed() == actualSidekick.gSpeed()
                && expectedSidekick.angle() == actualSidekick.angle()
                && expectedSidekick.air() == actualSidekick.air()
                && expectedSidekick.rolling() == actualSidekick.rolling()
                && (jumpOnlyDelta || (directionalOnlyDelta && expectedSidekick.xSpeed() == 0));
    }

    private static boolean isInactiveStaleSidekickInteract(
            TraceEvent.CpuState expected,
            EngineSidekickCpuState actual,
            TraceCharacterState expectedSidekick,
            TraceCharacterState actualSidekick) {
        if ((expected.interact() & 0xFF) == (actual.interact() & 0xFF)
                || expectedSidekick == null
                || actualSidekick == null
                || !expectedSidekick.present()
                || !actualSidekick.present()) {
            return false;
        }
        return !hasOnObjectStatus(expectedSidekick) && !hasOnObjectStatus(actualSidekick);
    }

    private static boolean isLandingFrameSidekickInteractIdRefreshLag(
            TraceEvent.CpuState expected,
            EngineSidekickCpuState actual,
            TraceCharacterState expectedSidekick,
            TraceCharacterState actualSidekick) {
        if ((expected.interact() & 0xFF) == (actual.interact() & 0xFF)
                || (expected.interact() & 0xFF) == 0
                || (expected.tailsInteract() & 0xFF) == 0
                || (expected.tailsInteract() & 0xFF) == (expected.interact() & 0xFF)
                || !isSidekickCpuInteractRefreshBeforeObjectContactRoutine(expected.cpuRoutine())
                || !isSidekickCpuInteractRefreshBeforeObjectContactRoutine(actual.cpuRoutine())
                || expectedSidekick == null
                || actualSidekick == null
                || !expectedSidekick.present()
                || !actualSidekick.present()
                || !hasOnObjectStatus(expectedSidekick)
                || !hasOnObjectStatus(actualSidekick)
                || !hasOnObjectStatus(expected.tailsStatus())) {
            return false;
        }
        return sameSidekickMotionState(expectedSidekick, actualSidekick);
    }

    private static boolean isLandingFrameSidekickInteractMirrorLag(
            TraceEvent.CpuState expected,
            EngineSidekickCpuState actual,
            TraceCharacterState expectedSidekick,
            TraceCharacterState actualSidekick) {
        if ((expected.interact() & 0xFF) != 0
                || (expected.tailsInteract() & 0xFF) == 0
                || (expected.interact() & 0xFF) == (actual.interact() & 0xFF)
                || !isSidekickCpuInteractRefreshBeforeObjectContactRoutine(expected.cpuRoutine())
                || !isSidekickCpuInteractRefreshBeforeObjectContactRoutine(actual.cpuRoutine())
                || expectedSidekick == null
                || actualSidekick == null
                || !expectedSidekick.present()
                || !actualSidekick.present()
                || !hasOnObjectStatus(expectedSidekick)
                || !hasOnObjectStatus(actualSidekick)) {
            return false;
        }
        return sameSidekickMotionState(expectedSidekick, actualSidekick);
    }

    private static boolean isSidekickCpuInteractRefreshBeforeObjectContactRoutine(int cpuRoutine) {
        return cpuRoutine == 0x06 || cpuRoutine == 0x08;
    }

    private static boolean sameSidekickMotionState(
            TraceCharacterState expectedSidekick,
            TraceCharacterState actualSidekick) {
        return expectedSidekick.statusByte() == actualSidekick.statusByte()
                && sameSidekickMotionStateExceptStatus(expectedSidekick, actualSidekick);
    }

    private static boolean sameSidekickMotionStateExceptStatus(
            TraceCharacterState expectedSidekick,
            TraceCharacterState actualSidekick) {
        return expectedSidekick.routine() == actualSidekick.routine()
                && expectedSidekick.x() == actualSidekick.x()
                && expectedSidekick.y() == actualSidekick.y()
                && expectedSidekick.xSub() == actualSidekick.xSub()
                && expectedSidekick.ySub() == actualSidekick.ySub()
                && expectedSidekick.xSpeed() == actualSidekick.xSpeed()
                && expectedSidekick.ySpeed() == actualSidekick.ySpeed()
                && expectedSidekick.gSpeed() == actualSidekick.gSpeed()
                && expectedSidekick.angle() == actualSidekick.angle()
                && expectedSidekick.air() == actualSidekick.air()
                && expectedSidekick.rolling() == actualSidekick.rolling();
    }

    private static boolean hasOnObjectStatus(TraceCharacterState state) {
        return (state.statusByte() & 0x08) != 0;
    }

    private static boolean hasOnObjectStatus(int statusByte) {
        return (statusByte & 0x08) != 0;
    }

    private static boolean isSidekickHurtOnObjectOnlyStatusMismatch(
            TraceCharacterState expected,
            TraceCharacterState actual) {
        int expectedStatus = expected.statusByte() & 0xFF;
        int actualStatus = actual.statusByte() & 0xFF;
        if ((expectedStatus ^ actualStatus) != 0x08) {
            return false;
        }
        if ((expected.routine() & 0xFF) != 0x04 || (actual.routine() & 0xFF) != 0x04) {
            return false;
        }
        return expected.x() == actual.x()
                && expected.y() == actual.y()
                && expected.xSub() == actual.xSub()
                && expected.ySub() == actual.ySub()
                && expected.xSpeed() == actual.xSpeed()
                && expected.ySpeed() == actual.ySpeed()
                && expected.gSpeed() == actual.gSpeed()
                && expected.angle() == actual.angle()
                && expected.air() == actual.air()
                && expected.rolling() == actual.rolling();
    }

    private static boolean isGroundedSidekickOnObjectPushOnlyStatusMismatch(
            TraceCharacterState expected,
            TraceCharacterState actual) {
        int expectedStatus = expected.statusByte() & 0xFF;
        int actualStatus = actual.statusByte() & 0xFF;
        if ((expectedStatus ^ actualStatus) != 0x20) {
            return false;
        }
        if ((expected.routine() & 0xFF) != 0x02 || (actual.routine() & 0xFF) != 0x02) {
            return false;
        }
        if (!hasOnObjectStatus(expected) || !hasOnObjectStatus(actual)
                || expected.air() || actual.air()
                || expected.rolling() || actual.rolling()) {
            return false;
        }
        return expected.x() == actual.x()
                && expected.y() == actual.y()
                && expected.xSub() == actual.xSub()
                && expected.ySub() == actual.ySub()
                && expected.xSpeed() == actual.xSpeed()
                && expected.ySpeed() == actual.ySpeed()
                && expected.gSpeed() == actual.gSpeed()
                && expected.angle() == actual.angle();
    }

    private static boolean isStationaryReleasedSidekickPushOnlyStatusMismatch(
            TraceCharacterState expected,
            TraceCharacterState actual) {
        int expectedStatus = expected.statusByte() & 0xFF;
        int actualStatus = actual.statusByte() & 0xFF;
        if (expectedStatus != 0x20 || actualStatus != 0x00) {
            return false;
        }
        if ((expected.routine() & 0xFF) != 0x02 || (actual.routine() & 0xFF) != 0x02) {
            return false;
        }
        if (hasOnObjectStatus(expected) || hasOnObjectStatus(actual)
                || expected.standOnObj() < 0 || actual.standOnObj() >= 0
                || expected.air() || actual.air()
                || expected.rolling() || actual.rolling()) {
            return false;
        }
        return expected.x() == actual.x()
                && expected.y() == actual.y()
                && expected.xSub() == actual.xSub()
                && expected.ySub() == actual.ySub()
                && expected.xSpeed() == 0
                && actual.xSpeed() == 0
                && expected.ySpeed() == 0
                && actual.ySpeed() == 0
                && expected.gSpeed() == 0
                && actual.gSpeed() == 0
                && expected.angle() == actual.angle();
    }

    private static boolean isGroundedSidekickPushOnlyStatusMismatch(
            TraceCharacterState expected,
            TraceCharacterState actual) {
        int expectedStatus = expected.statusByte() & 0xFF;
        int actualStatus = actual.statusByte() & 0xFF;
        if (expectedStatus != (actualStatus | 0x20)
                || (actualStatus & 0x20) != 0
                || (expectedStatus & ~0x20) != actualStatus) {
            return false;
        }
        if ((expected.routine() & 0xFF) != 0x02 || (actual.routine() & 0xFF) != 0x02) {
            return false;
        }
        if (hasOnObjectStatus(expected) || hasOnObjectStatus(actual)
                || expected.air() || actual.air()
                || expected.rolling() || actual.rolling()) {
            return false;
        }
        return sameSidekickMotionStateExceptStatus(expected, actual);
    }

    private static boolean isInactiveSidekickDespawnMarkerFacingOnlyStatusMismatch(
            TraceCharacterState expected,
            TraceCharacterState actual) {
        int expectedStatus = expected.statusByte() & 0xFF;
        int actualStatus = actual.statusByte() & 0xFF;
        if ((expectedStatus ^ actualStatus) != 0x01) {
            return false;
        }
        if ((expected.routine() & 0xFF) != 0x02 || (actual.routine() & 0xFF) != 0x02) {
            return false;
        }
        if (!expected.air() || !actual.air()
                || expected.rolling() || actual.rolling()
                || expected.x() != 0 || actual.x() != 0
                || expected.y() != 0 || actual.y() != 0) {
            return false;
        }
        return expected.xSub() == actual.xSub()
                && expected.ySub() == actual.ySub()
                && expected.xSpeed() == 0
                && actual.xSpeed() == 0
                && expected.ySpeed() == 0
                && actual.ySpeed() == 0
                && expected.gSpeed() == 0
                && actual.gSpeed() == 0
                && expected.angle() == actual.angle();
    }

    private static boolean isStationarySidekickOnObjectFacingOnlyStatusMismatch(
            TraceCharacterState expected,
            TraceCharacterState actual) {
        int expectedStatus = expected.statusByte() & 0xFF;
        int actualStatus = actual.statusByte() & 0xFF;
        if ((expectedStatus ^ actualStatus) != 0x01) {
            return false;
        }
        if ((expected.routine() & 0xFF) != 0x02 || (actual.routine() & 0xFF) != 0x02) {
            return false;
        }
        if (!hasOnObjectStatus(expected) || !hasOnObjectStatus(actual)
                || expected.air() || actual.air()
                || expected.rolling() || actual.rolling()) {
            return false;
        }
        return expected.x() == actual.x()
                && expected.y() == actual.y()
                && expected.xSub() == actual.xSub()
                && expected.ySub() == actual.ySub()
                && expected.xSpeed() == 0
                && actual.xSpeed() == 0
                && expected.ySpeed() == 0
                && actual.ySpeed() == 0
                && expected.gSpeed() == 0
                && actual.gSpeed() == 0
                && expected.angle() == actual.angle();
    }

    private static boolean isAirborneSidekickZeroHorizontalSpeedFacingOnlyStatusMismatch(
            TraceCharacterState expected,
            TraceCharacterState actual) {
        int expectedStatus = expected.statusByte() & 0xFF;
        int actualStatus = actual.statusByte() & 0xFF;
        if ((expectedStatus ^ actualStatus) != 0x01
                || (expectedStatus & ~0x01) != 0x02
                || (actualStatus & ~0x01) != 0x02) {
            return false;
        }
        if ((expected.routine() & 0xFF) != 0x02 || (actual.routine() & 0xFF) != 0x02) {
            return false;
        }
        if (!expected.air() || !actual.air()
                || expected.rolling() || actual.rolling()) {
            return false;
        }
        return expected.x() == actual.x()
                && expected.y() == actual.y()
                && expected.xSub() == actual.xSub()
                && expected.ySub() == actual.ySub()
                && expected.xSpeed() == 0
                && actual.xSpeed() == 0
                && expected.ySpeed() == actual.ySpeed()
                && expected.gSpeed() == 0
                && actual.gSpeed() == 0
                && expected.angle() == actual.angle();
    }

    private static FieldComparison ignoredStaleSidekickInteract(String name, int expected, int actual) {
        return new FieldComparison(name, formatHex(expected), formatHex(actual), Severity.MATCH, 0);
    }

    private static FieldComparison ignoredSidekickStatus(String name, int expected, int actual) {
        return new FieldComparison(name, formatHex(expected), formatHex(actual), Severity.MATCH, 0, true);
    }

    private static FieldComparison ignoredLatchedCtrl2(String name, int expected, int actual) {
        return new FieldComparison(name, formatHex(expected), formatHex(actual), Severity.MATCH, 0);
    }

    /**
     * ROM Pos_table/Stat_table indices are byte offsets into 4-byte records.
     * Engine diagnostics expose the already-normalized 0-63 ring slot.
     */
    private static int romHistoryByteOffsetToSlot(int byteOffset) {
        return ((byteOffset & 0xFF) >>> 2) & 0x3F;
    }

    /**
     * {@code Sonic_Pos_Record_Index} points at the next free 4-byte record;
     * engine {@code historyPos} points at the latest written slot.
     */
    private static int romNextFreeHistoryByteOffsetToLatestSlot(int byteOffset) {
        return (romHistoryByteOffsetToSlot(byteOffset) + 0x3F) & 0x3F;
    }

    /**
     * The recorder emits raw ROM button bits for {@code Ctrl_2_Logical}. Some
     * diagnostic sites emit the full word, with held bits in the high byte and
     * pressed bits in the low byte. The engine collapses A/B/C into its single
     * abstract jump bit while preserving directional bits.
     */
    private static int normalizeRomCtrl2LogicalByte(int raw) {
        int held = ((raw >>> 8) | raw) & 0xFF;
        int normalized = held & (TRACE_INPUT_UP
                | TRACE_INPUT_DOWN
                | TRACE_INPUT_LEFT
                | TRACE_INPUT_RIGHT);
        if ((held & 0x70) != 0) {
            normalized |= TRACE_INPUT_JUMP;
        }
        return normalized & 0xFF;
    }

    private static int normalizeRomCtrl2PressedByte(int raw) {
        return (raw & 0x70) != 0 ? TRACE_INPUT_JUMP : 0;
    }

    private static int normalizeEngineCtrl2PressedByte(int raw) {
        return raw & TRACE_INPUT_JUMP;
    }

    private static String normalizeCharacterPrefix(String label) {
        if (label == null || label.isBlank()) {
            return "sidekick_";
        }
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < label.length(); i++) {
            char ch = Character.toLowerCase(label.charAt(i));
            normalized.append(Character.isLetterOrDigit(ch) ? ch : '_');
        }
        if (normalized.isEmpty()) {
            return "sidekick_";
        }
        normalized.append('_');
        return normalized.toString();
    }

    private static String formatHex(int value) {
        if (value < 0) {
            return String.format("-%04X", -value);
        }
        return String.format("0x%04X", value & 0xFFFF);
    }

    // ---- Frame-0 bootstrap comparator (ADR-3) -------------------------------

    /**
     * Cross-references the engine state captured at frame 0 against the ROM
     * frame -1 snapshots in {@code trace}. The bootstrap comparator is the
     * test-time invariant that engine state arrives at frame 0 via natural
     * simulation (running the title card / level init the same way the ROM
     * does), not via state hydration from the trace.
     *
     * <p>Returns an empty list when the trace metadata says the recording
     * was captured under legacy mode (no native title-card prelude). Legacy
     * traces continue to short-circuit the comparator entirely.
     *
     * <p>Coverage v1 (conservative):
     * <ul>
     *     <li>{@code player_history_snapshot} — pos + 4 ring buffers (64 entries each).</li>
     *     <li>{@code cpu_state_snapshot} for character "tails" — all named fields.</li>
     *     <li>{@code object_state_snapshot} per slot — objectType + x_pos + y_pos
     *         + routine + status only.</li>
     * </ul>
     * Per-slot SST bytes beyond the cardinal set are NOT compared in this
     * pass; they will be added as confidence builds.
     */
    public List<BootstrapDivergence> compareBootstrapFrame0(TraceData trace,
                                                            EngineSnapshot snapshot) {
        if (trace == null || snapshot == null) {
            return List.of();
        }
        if (!nativePreludeMode(trace)) {
            return List.of();
        }

        List<BootstrapDivergence> divergences = new ArrayList<>();
        comparePlayerHistory(trace, snapshot, divergences);
        compareTailsCpu(trace, snapshot, divergences);
        compareObjectSnapshots(trace, snapshot, divergences);

        divergences.sort(Comparator.comparingInt(
                (BootstrapDivergence d) -> d.severity() == BootstrapDivergence.Severity.ERROR
                        ? 0 : 1));
        lastBootstrapDivergences = List.copyOf(divergences);
        return divergences;
    }

    /**
     * Resolves the native-prelude-mode flag for {@code trace}. Tests can
     * override via {@link #setNativePreludeOverrideForTests(Boolean)};
     * production reads {@link TraceMetadata#hasNativePreludeBootstrap()}.
     */
    private static boolean nativePreludeMode(TraceData trace) {
        Boolean override = NATIVE_PRELUDE_OVERRIDE_FOR_TESTS;
        if (override != null) {
            return override;
        }
        if (trace == null || trace.metadata() == null) {
            return false;
        }
        return trace.metadata().hasNativePreludeBootstrap();
    }

    private static void comparePlayerHistory(TraceData trace,
                                             EngineSnapshot snapshot,
                                             List<BootstrapDivergence> out) {
        TraceEvent.PlayerHistorySnapshot recorded = trace.preTracePlayerHistorySnapshot();
        if (recorded == null) {
            out.add(new BootstrapDivergence(
                    "player_history.snapshot",
                    BootstrapDivergence.Severity.WARNING,
                    "present", "missing",
                    "player_history_snapshot missing from trace at frame -1"));
            return;
        }

        int recordedHistorySlot =
                romNextFreeHistoryByteOffsetToLatestSlot(recorded.historyPos());
        if (recordedHistorySlot != snapshot.playerHistoryPos()) {
            out.add(new BootstrapDivergence(
                    "player_history.pos",
                    BootstrapDivergence.Severity.ERROR,
                    String.format("0x%04X (slot 0x%02X)",
                            recorded.historyPos() & 0xFFFF,
                            recordedHistorySlot & 0x3F),
                    String.format("0x%04X", snapshot.playerHistoryPos() & 0xFFFF),
                    "ROM and engine ring-buffer positions disagree"));
        }

        // Slots the ROM has actually written since Obj01_Init; anything from
        // here up is still the untouched pre-fill remnant. See
        // untouchedPreFillRemnantStart for why it is sometimes uncomparable.
        int remnantStart = untouchedPreFillRemnantStart(trace, snapshot, recorded);

        compareShortArray("player_history.x", recorded.xHistory(),
                snapshot.playerXHistory(), remnantStart, out);
        compareShortArray("player_history.y", recorded.yHistory(),
                snapshot.playerYHistory(), remnantStart, out);
        compareShortArray("player_history.input", recorded.inputHistory(),
                snapshot.playerInputHistory(), out);
        compareByteArray("player_history.status", recorded.statusHistory(),
                snapshot.playerStatusHistory(), out);
    }

    /**
     * Index of the first {@code Sonic_Pos_Record_Buf} slot that this replay
     * cannot legitimately compare, or {@link Integer#MAX_VALUE} when every
     * slot is comparable (the normal case).
     *
     * <p>{@code Obj01_Init_Continued} pre-fills all 64 ring slots with
     * {@code (x_pos-$20, y_pos+4)} and zeroes their input/status records, then
     * resets {@code Sonic_Pos_Record_Index} to 0
     * (docs/s2disasm/s2.asm:36202-36218). {@code Sonic_RecordPos} then
     * overwrites one slot per frame from {@code Obj01_Control}, so the slots
     * below the next-free index hold genuine recorded motion and the slots at
     * and above it still hold the pre-fill anchor.
     *
     * <p>That anchor is {@code x_pos}/{@code y_pos} <em>as they stand when
     * Obj01_Init runs</em>. {@code LevelSizeLoad} has already placed the leader
     * by one of two branches: the zone's {@code StartLocations} entry, or --
     * when {@code Last_star_pole_hit} is non-zero -- {@code Obj79_LoadData}'s
     * restore of {@code Saved_x_pos}/{@code Saved_y_pos}, which are a star
     * post's own coordinates saved when the player touched it
     * (docs/s2disasm/s2.asm:14773-14778, :36190, :44737-44738, :44776-44778).
     *
     * <p>A run segment replayed in isolation begins after that star post was
     * touched, so it has no {@code Last_star_pole_hit} and no
     * {@code Saved_x_pos}/{@code Saved_y_pos} — the same missing datum already
     * documented for the segment star-post dongle artifact. Its cold boot
     * therefore takes the {@code StartLocations}-equivalent path and anchors
     * the pre-fill at the segment's own first recorded position, which is one
     * physics tick later than the ROM's anchor. The difference is unrecoverable
     * from the segment, and trace data is comparison-only, so those slots are
     * excluded rather than hydrated. Everything the ROM wrote during the
     * segment — every slot below the next-free index, and the input/status
     * rings in full — stays compared.
     *
     * <p>The exclusion is gated on two independent facts, never on a zone,
     * route, frame or game name:
     * <ol>
     *   <li>the replay's start X differs from the loaded zone/act's ROM
     *       {@code StartLocations} X, which is what identifies the checkpoint
     *       branch (a star post's X is never the level's spawn X); and</li>
     *   <li>the recorded remnant still carries the pre-fill signature — one
     *       identical coordinate pair across every remnant slot with zero
     *       input and status — proving the ring has not wrapped past it.</li>
     * </ol>
     */
    private static int untouchedPreFillRemnantStart(TraceData trace,
                                                    EngineSnapshot snapshot,
                                                    TraceEvent.PlayerHistorySnapshot recorded) {
        int levelStartX = snapshot.levelStartX();
        if (levelStartX == EngineSnapshot.LEVEL_START_X_UNKNOWN
                || trace.metadata() == null) {
            return Integer.MAX_VALUE;
        }
        if ((trace.metadata().startX() & 0xFFFF) == (levelStartX & 0xFFFF)) {
            // Level load ran the StartLocations branch: the engine's pre-fill
            // anchor is the ROM's, so the whole ring is comparable.
            return Integer.MAX_VALUE;
        }

        int nextFree = romHistoryByteOffsetToSlot(recorded.historyPos());
        short[] x = recorded.xHistory();
        short[] y = recorded.yHistory();
        short[] input = recorded.inputHistory();
        byte[] status = recorded.statusHistory();
        if (x == null || y == null || input == null || status == null
                || x.length < HISTORY_RING_SLOTS || y.length < HISTORY_RING_SLOTS
                || input.length < HISTORY_RING_SLOTS
                || status.length < HISTORY_RING_SLOTS
                || nextFree >= HISTORY_RING_SLOTS) {
            return Integer.MAX_VALUE;
        }
        for (int i = nextFree; i < HISTORY_RING_SLOTS; i++) {
            if (x[i] != x[nextFree] || y[i] != y[nextFree]
                    || input[i] != 0 || status[i] != 0) {
                // Not the pre-fill signature: the ring wrapped and these slots
                // are genuine recorded motion. Compare them.
                return Integer.MAX_VALUE;
            }
        }
        return nextFree;
    }

    /** {@code Sonic_Pos_Record_Buf} holds 64 four-byte records (s2.asm:36211). */
    private static final int HISTORY_RING_SLOTS = 64;

    private static void compareShortArray(String fieldBase,
                                          short[] expected,
                                          short[] actual,
                                          List<BootstrapDivergence> out) {
        compareShortArray(fieldBase, expected, actual, Integer.MAX_VALUE, out);
    }

    private static void compareShortArray(String fieldBase,
                                          short[] expected,
                                          short[] actual,
                                          int uncomparableFrom,
                                          List<BootstrapDivergence> out) {
        if (expected == null) {
            out.add(new BootstrapDivergence(
                    fieldBase, BootstrapDivergence.Severity.WARNING,
                    "present", "missing",
                    fieldBase + " absent from trace snapshot"));
            return;
        }
        int len = Math.min(expected.length, actual == null ? 0 : actual.length);
        len = Math.min(len, uncomparableFrom);
        for (int i = 0; i < len; i++) {
            short e = expected[i];
            short a = actual[i];
            if (e != a) {
                out.add(new BootstrapDivergence(
                        fieldBase + "[" + i + "]",
                        BootstrapDivergence.Severity.ERROR,
                        String.format("0x%04X", e & 0xFFFF),
                        String.format("0x%04X", a & 0xFFFF),
                        fieldBase + " ring-buffer entry " + i
                                + " differs from ROM snapshot"));
            }
        }
        if (expected.length != (actual == null ? 0 : actual.length)) {
            out.add(new BootstrapDivergence(
                    fieldBase + ".length",
                    BootstrapDivergence.Severity.WARNING,
                    Integer.toString(expected.length),
                    Integer.toString(actual == null ? 0 : actual.length),
                    fieldBase + " ring-buffer length differs from ROM snapshot"));
        }
    }

    private static void compareByteArray(String fieldBase,
                                         byte[] expected,
                                         byte[] actual,
                                         List<BootstrapDivergence> out) {
        if (expected == null) {
            out.add(new BootstrapDivergence(
                    fieldBase, BootstrapDivergence.Severity.WARNING,
                    "present", "missing",
                    fieldBase + " absent from trace snapshot"));
            return;
        }
        int len = Math.min(expected.length, actual == null ? 0 : actual.length);
        for (int i = 0; i < len; i++) {
            byte e = expected[i];
            byte a = actual[i];
            if (e != a) {
                out.add(new BootstrapDivergence(
                        fieldBase + "[" + i + "]",
                        BootstrapDivergence.Severity.ERROR,
                        String.format("0x%02X", e & 0xFF),
                        String.format("0x%02X", a & 0xFF),
                        fieldBase + " ring-buffer entry " + i
                                + " differs from ROM snapshot"));
            }
        }
        if (expected.length != (actual == null ? 0 : actual.length)) {
            out.add(new BootstrapDivergence(
                    fieldBase + ".length",
                    BootstrapDivergence.Severity.WARNING,
                    Integer.toString(expected.length),
                    Integer.toString(actual == null ? 0 : actual.length),
                    fieldBase + " ring-buffer length differs from ROM snapshot"));
        }
    }

    private static void compareTailsCpu(TraceData trace,
                                        EngineSnapshot snapshot,
                                        List<BootstrapDivergence> out) {
        TraceEvent.CpuStateSnapshot recorded = trace.preTraceCpuStateSnapshot("tails");
        EngineSnapshot.SidekickCpuView engine = snapshot.tailsCpu();
        if (recorded == null && engine == null) {
            return;
        }
        if (recorded == null) {
            out.add(new BootstrapDivergence(
                    "tails_cpu.snapshot",
                    BootstrapDivergence.Severity.WARNING,
                    "present", "missing",
                    "cpu_state_snapshot for 'tails' missing from trace"));
            return;
        }
        if (engine == null) {
            TraceCharacterState frameZeroTails = firstFrameSidekickState(trace);
            if (frameZeroTails != null && !frameZeroTails.present()) {
                return;
            }
            out.add(new BootstrapDivergence(
                    "tails_cpu.engine",
                    BootstrapDivergence.Severity.WARNING,
                    "present", "missing",
                    "engine has no sidekick CPU view; cannot compare against ROM"));
            return;
        }

        compareInt("tails_cpu.control_counter",
                recorded.controlCounter(), engine.controlCounter(), out);
        compareInt("tails_cpu.respawn_counter",
                recorded.respawnCounter(), engine.respawnCounter(), out);
        compareInt("tails_cpu.routine",
                recorded.cpuRoutine(), engine.cpuRoutine(), out);
        compareInt("tails_cpu.target_x",
                recorded.targetX() & 0xFFFF, engine.targetX() & 0xFFFF, out);
        compareInt("tails_cpu.target_y",
                recorded.targetY() & 0xFFFF, engine.targetY() & 0xFFFF, out);
        compareInt("tails_cpu.interact_id",
                recorded.interactId(), engine.interactId(), out);
        compareInt("tails_cpu.jumping",
                recorded.jumping() ? 1 : 0, engine.jumping() ? 1 : 0, out);
    }

    private static TraceCharacterState firstFrameSidekickState(TraceData trace) {
        if (trace == null) {
            return null;
        }
        try {
            TraceFrame frame = trace.getFrame(0);
            return frame.sidekick();
        } catch (IndexOutOfBoundsException ex) {
            return null;
        }
    }

    private static void compareInt(String field, int expected, int actual,
                                   List<BootstrapDivergence> out) {
        if (expected == actual) {
            return;
        }
        out.add(new BootstrapDivergence(
                field,
                BootstrapDivergence.Severity.ERROR,
                String.format("0x%04X", expected & 0xFFFF),
                String.format("0x%04X", actual & 0xFFFF),
                field + " differs from ROM snapshot"));
    }

    private static void compareObjectSnapshots(TraceData trace,
                                               EngineSnapshot snapshot,
                                               List<BootstrapDivergence> out) {
        List<TraceEvent.ObjectStateSnapshot> recordedSnapshots =
                trace.preTraceObjectSnapshots();
        if (recordedSnapshots == null || recordedSnapshots.isEmpty()) {
            // Object snapshots are optional — emit a WARNING only when the
            // engine has populated slots and the trace has none, so the
            // legitimate "no slots" case stays silent.
            if (!snapshot.slotStates().isEmpty()) {
                out.add(new BootstrapDivergence(
                        "object_snapshots",
                        BootstrapDivergence.Severity.WARNING,
                        "present", "missing",
                        "object_state_snapshot events missing from trace, but engine has "
                                + snapshot.slotStates().size() + " active slot(s)"));
            }
            return;
        }

        for (TraceEvent.ObjectStateSnapshot recorded : recordedSnapshots) {
            EngineSnapshot.ObjectSnapshot engine = snapshot.slotStates().get(recorded.slot());
            String slotLabel = "object_slot[" + recorded.slot() + "]";
            if (engine == null) {
                continue;
            }
            RomObjectSnapshot fields = recorded.fields();
            compareInt(slotLabel + ".object_type",
                    recorded.objectType() & 0xFF, engine.objectType() & 0xFF, out);
            if (fields != null) {
                compareInt(slotLabel + ".x_pos",
                        fields.xPos() & 0xFFFF, engine.xPos() & 0xFFFF, out);
                compareInt(slotLabel + ".y_pos",
                        fields.yPos() & 0xFFFF, engine.yPos() & 0xFFFF, out);
                compareInt(slotLabel + ".routine",
                        fields.routine() & 0xFF, engine.routine() & 0xFF, out);
                compareInt(slotLabel + ".status",
                        fields.status() & 0xFF, engine.status() & 0xFF, out);
            }
        }
    }
}
