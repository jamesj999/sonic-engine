package com.openggf.trace.replay.runs;

import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageTraceData;
import com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceData;
import com.openggf.trace.DynamicArtSpillNormalization;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.SpecialStageRunObjectsPassBinder;
import com.openggf.trace.SpecialStageTraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.HardwareTimingStreamLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Read-only, profile-specific pacing view for a complete-run special-stage
 * segment. Recorded rows choose input timing and comparison lifecycle only;
 * they never carry gameplay values into the engine.
 */
public sealed interface TraceRunSpecialStageRows
        permits TraceRunSpecialStageRows.S1Rows,
                TraceRunSpecialStageRows.S2Rows,
                TraceRunSpecialStageRows.BlueSphereRows {

    /** One represented special-stage row's execution/lifecycle policy. */
    record SpecialStageRowAdmission(
            boolean executeGameplay,
            Optional<PlcLifecyclePhase> syntheticPlcPhase,
            boolean advancePreservedVblankIfUnchanged,
            boolean admitHardwareTiming) {

        public SpecialStageRowAdmission {
            syntheticPlcPhase = Objects.requireNonNull(
                    syntheticPlcPhase, "syntheticPlcPhase");
        }
    }

    TraceMetadata metadata();

    int rowCount();

    SpecialStageRowAdmission admission(int localRow);

    HardwareTimingSchedule hardwareTimingSchedule();

    default OptionalInt terminalRow() {
        return OptionalInt.of(rowCount() - 1);
    }

    /**
     * A fresh pass cursor over this segment's recorded ROM {@code RunObjects}
     * completions, when the segment recorded them.
     *
     * <p>The S2 special-stage 68K loop is <em>not</em> paced one VBlank per
     * object pass: {@code SS_MainLoop} sets {@code VintID_S2SS}, waits for the
     * V-int, then runs {@code RunObjects} (docs/s2disasm/s2.asm:6694-6721), so
     * a slow pass spans several V-blanks while the observation stream keeps
     * ticking. Replay must therefore step the runtime once per completed pass,
     * not once per recorded row; the standalone
     * {@code TestS2SpecialStageTraceReplay} already does exactly this. The
     * binder is a stateful cursor, so each replay takes its own instance.
     *
     * <p>Comparison-only: a pass record contributes execution ordering and the
     * identity of the BK2 row the ROM's {@code ReadJoypads} sampled. No player,
     * object, track, ring or checkpoint value is copied into the engine.
     *
     * @return empty when the segment carries no {@code run_objects_end} records
     */
    default Optional<SpecialStageRunObjectsPassBinder> newRunObjectsPassBinder() {
        return Optional.empty();
    }

    /**
     * First local row from which {@link #newRunObjectsPassBinder()} owns pacing.
     *
     * <p>Before ROM {@code SpecialStage_Started} rises, main copies the pad
     * words <em>before</em> {@code WaitForVint} and the recurring object pass is
     * not yet the pacing authority (docs/s2disasm/s2.asm:6674-6688), so those
     * rows stay one step per row. Defaults to "never pass-paced".
     */
    default int passPacedFromRow() {
        return Integer.MAX_VALUE;
    }

    /**
     * Per-row expected DPLC transfer states with the recorder's spilled
     * submission edges rebound to the object pass that produced them, keyed by
     * local row. An empty map means "compare the raw recorded rows".
     *
     * <p>One ROM {@code RunObjects} pass submits every changed player DPLC in
     * one burst, but a pass can overrun its displayed frame: {@code
     * SS_MainLoop} waits for the V-int and only then runs the pass
     * (docs/s2disasm/s2.asm:6694-6721), so a slow pass is bisected by the next
     * V-blank and the recorder stamps the objects after the split with the
     * later (lag) frame. The engine publishes each pass atomically, and no
     * frame-granularity ROM state predicts where the 68K ran out of time, so
     * the split is an observation artifact compared per pass rather than per
     * publication row. See {@link DynamicArtSpillNormalization}.
     *
     * <p>Comparison-only: this rewrites the expectation, never engine state.
     */
    default Map<Integer, TraceEvent.DynamicArtTransferState>
            normalizedDynamicArtRows() {
        return Map.of();
    }

    static TraceRunSpecialStageRows load(String profile, Path directory)
            throws IOException {
        return load(profile, directory, java.util.List.of());
    }

    static TraceRunSpecialStageRows load(
            String profile,
            Path directory,
            java.util.List<DynamicArtTransfer.Descriptor> openingLedger)
            throws IOException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(directory, "directory");
        return switch (profile) {
            case "s1_special_stage" -> {
                Sonic1SpecialStageTraceData trace =
                        Sonic1SpecialStageTraceData.load(directory, openingLedger);
                yield new S1Rows(trace, HardwareTimingStreamLoader.load(
                        directory, trace.metadata()));
            }
            case "s2_special_stage" ->
                    new S2Rows(SpecialStageTraceData.load(directory, openingLedger));
            case "s3k_special_stage" -> {
                S3kSpecialStageTraceData trace =
                        S3kSpecialStageTraceData.load(directory);
                yield new BlueSphereRows(trace, HardwareTimingStreamLoader.load(
                        directory, trace.metadata()));
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported special-stage trace profile '" + profile + "'");
        };
    }

    static TraceRunSpecialStageRows forS2(SpecialStageTraceData trace) {
        return new S2Rows(Objects.requireNonNull(trace, "trace"));
    }

    final class S1Rows implements TraceRunSpecialStageRows {
        private final Sonic1SpecialStageTraceData trace;
        private final HardwareTimingSchedule hardwareTimingSchedule;

        private S1Rows(Sonic1SpecialStageTraceData trace,
                HardwareTimingSchedule hardwareTimingSchedule) {
            this.trace = trace;
            this.hardwareTimingSchedule = hardwareTimingSchedule;
        }

        @Override
        public TraceMetadata metadata() {
            return trace.metadata();
        }

        @Override
        public int rowCount() {
            return trace.frameCount();
        }

        @Override
        public SpecialStageRowAdmission admission(int localRow) {
            boolean lagged = trace.getFrame(localRow).lag();
            return new SpecialStageRowAdmission(
                    !lagged,
                    syntheticLagPhase(lagged, trace.metadata()),
                    true,
                    true);
        }

        @Override
        public HardwareTimingSchedule hardwareTimingSchedule() {
            return hardwareTimingSchedule;
        }
    }

    final class S2Rows implements TraceRunSpecialStageRows {
        private final SpecialStageTraceData trace;

        private S2Rows(SpecialStageTraceData trace) {
            this.trace = trace;
        }

        @Override
        public TraceMetadata metadata() {
            return trace.metadata();
        }

        @Override
        public int rowCount() {
            return trace.frameCount();
        }

        @Override
        public SpecialStageRowAdmission admission(int localRow) {
            boolean lagged = trace.getFrame(localRow).lag();
            return new SpecialStageRowAdmission(
                    !lagged,
                    syntheticLagPhase(lagged, trace.metadata()),
                    false,
                    true);
        }

        @Override
        public HardwareTimingSchedule hardwareTimingSchedule() {
            return trace.hardwareTimingSchedule();
        }

        @Override
        public Optional<SpecialStageRunObjectsPassBinder> newRunObjectsPassBinder() {
            var snapshots = trace.runObjectsEndSnapshots();
            if (snapshots.isEmpty()) {
                return Optional.empty();
            }
            OptionalInt finishObserved = trace.stageFinishedObservedFrame();
            // Same eligibility rule the standalone S2 special-stage replay uses:
            // a pass binds forward to the first non-lag observation at or after
            // its return cursor, except the single terminal pass, which the ROM
            // publishes at the raw finish observation because it exits before a
            // later non-lag row.
            return Optional.of(new SpecialStageRunObjectsPassBinder(
                    snapshots,
                    trace.frameCount(),
                    row -> !trace.getFrame(row).lag()
                            || (finishObserved.isPresent()
                                    && row == finishObserved.getAsInt())));
        }

        @Override
        public Map<Integer, TraceEvent.DynamicArtTransferState>
                normalizedDynamicArtRows() {
            return DynamicArtSpillNormalization.forSpecialStage(trace);
        }

        /**
         * The publication row of the recurring loop's first object pass.
         *
         * <p>{@code SpecialStage_MainLoop} is two loops (s2.asm:6674-6721) and
         * both run {@code RunObjects}. The pre-start loop tests
         * {@code SpecialStage_Started} only after its pass returns
         * (s2.asm:6691-6692), so the flag Obj5F set (s2.asm:9745) is first seen
         * by the <em>next</em> iteration's {@code Vint_S2SS ReadJoypads}
         * sample. The first recorded pass whose {@code started_at_input_sample}
         * is set is therefore, by construction, the recurring loop's first
         * pass. Rows before it stay one step per row: there the ROM copies the
         * pad words before {@code WaitForVint} (s2.asm:6675-6676), so the
         * recurring pass is not yet the input-binding authority.
         */
        @Override
        public int passPacedFromRow() {
            return trace.runObjectsEndSnapshots().stream()
                    .sorted(java.util.Comparator.comparingInt(
                            snapshot -> passField(snapshot, "pass_sequence")))
                    .filter(snapshot -> passField(snapshot, "started_at_input_sample") != 0)
                    .mapToInt(TraceEvent.StateSnapshot::frame)
                    .findFirst()
                    .orElse(Integer.MAX_VALUE);
        }

        private static int passField(TraceEvent.StateSnapshot snapshot, String name) {
            Object raw = snapshot.fields().get(name);
            if (raw == null) {
                throw new IllegalStateException(
                        "run_objects_end is missing " + name + " at frame "
                                + snapshot.frame());
            }
            if (raw instanceof Number number) {
                return number.intValue();
            }
            String text = String.valueOf(raw);
            return text.startsWith("0x") || text.startsWith("0X")
                    ? Integer.parseUnsignedInt(text.substring(2), 16)
                    : Integer.parseInt(text);
        }

        @Override
        public OptionalInt terminalRow() {
            return trace.stageFinishedFrame();
        }
    }

    final class BlueSphereRows implements TraceRunSpecialStageRows {
        private final S3kSpecialStageTraceData trace;
        private final HardwareTimingSchedule hardwareTimingSchedule;

        private BlueSphereRows(S3kSpecialStageTraceData trace,
                HardwareTimingSchedule hardwareTimingSchedule) {
            this.trace = trace;
            this.hardwareTimingSchedule = hardwareTimingSchedule;
        }

        @Override
        public TraceMetadata metadata() {
            return trace.metadata();
        }

        @Override
        public int rowCount() {
            return trace.frameCount();
        }

        @Override
        public SpecialStageRowAdmission admission(int localRow) {
            boolean lagged = trace.getFrame(localRow).lag();
            return new SpecialStageRowAdmission(
                    !lagged,
                    syntheticLagPhase(lagged, trace.metadata()),
                    true,
                    true);
        }

        @Override
        public HardwareTimingSchedule hardwareTimingSchedule() {
            return hardwareTimingSchedule;
        }
    }

    private static Optional<PlcLifecyclePhase> syntheticLagPhase(
            boolean lagged, TraceMetadata metadata) {
        return lagged && metadata.hasPerFrameDynamicArtTransferState()
                ? Optional.of(PlcLifecyclePhase.LAG)
                : Optional.empty();
    }
}
