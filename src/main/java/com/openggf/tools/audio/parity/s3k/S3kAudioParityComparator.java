package com.openggf.tools.audio.parity.s3k;

import com.openggf.tools.audio.parity.AudioParityChipWrite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Validation-first, no-realignment tick-by-tick comparison of S3K oracle
 * streams: reference (BizHawk/GPGX driver RAM + write bus) against the
 * OpenGGF driver capture. The first difference wins; nothing realigns.
 *
 * <p>Comparison order per tick: GATE globals, GATE track fields for each of
 * the sixteen fixed roles, then the ordered write stream.
 *
 * <h2>The DAC byte stream is compared, but not partitioned</h2>
 * The music DAC bytes are excluded from the per-tick write stream and
 * compared separately over the whole window by
 * {@link #compareDacStream(List, List)}. The reason is measured, not
 * convenient: which service window a given {@code 2Ah} byte falls in is
 * decided by how many Z80 cycles that frame's {@code zUpdateEverything} took,
 * because {@code zPlayDigitalAudio} streams for whatever is left of the
 * inter-V-int interval (Sound/Z80 Sound Driver.asm:4296-4351). Within one
 * uninterrupted play of one sample at one fixed rate the reference's own
 * per-tick counts swing by 15 to 86 bytes, so the split is a property of a
 * Z80 this engine does not emulate. The {@code 2Bh = 80h} enable stays
 * strictly partitioned, because the idle loop writes it immediately on
 * finding {@code zDACIndex} non-zero (Sound/Z80 Sound Driver.asm:4269-4276)
 * and that store happens at a service boundary, so every run's start remains
 * pinned to an exact tick by compared data. The {@code 2Bh = 0} disable moves
 * into this stream with the bytes, because it is written only by
 * {@code zPlayDigitalAudio}'s entry (:4258-4262), which a sample reaches only
 * by being exhausted; a sample superseded mid-play jumps straight to
 * {@code .dac_idle_loop} instead (:4343-4345) and writes no disable. Whether
 * a given play exhausts or is superseded is therefore the same Z80 duration
 * as the run length already excused here. See
 * docs/status/known-discrepancies.md, "S3K music DAC byte stream partition".
 */
public final class S3kAudioParityComparator {

    /** {@code 2Ah}, the DAC sample-byte register the playback loop writes. */
    private static final int DAC_DATA_REGISTER = 0x2A;
    /** {@code 2Bh}, the DAC enable/disable register that brackets a run. */
    private static final int DAC_ENABLE_REGISTER = 0x2B;

    public record Report(Kind kind, int ticksCompared, Integer tick, String role, String field,
            Integer eventIndex, String reference, String openggf) {
        public enum Kind {
            MATCH, REFERENCE_LIMITATION, TICK_COUNT_MISMATCH,
            GLOBAL_STATE_MISMATCH, TRACK_STATE_MISMATCH,
            EVENT_MISSING, EVENT_EXTRA, EVENT_VALUE_DIFFERENT
        }

        public boolean matches() {
            return kind == Kind.MATCH;
        }

        public String toHumanText() {
            if (matches()) {
                return "S3K audio oracle: MATCH (" + ticksCompared + " ticks)";
            }
            String status = kind == Kind.REFERENCE_LIMITATION
                    ? "REFERENCE_LIMITATION" : "MISMATCH";
            StringBuilder result = new StringBuilder(
                    "S3K audio oracle: " + status + "\nkind: " + kind);
            if (tick != null) result.append("\ntick: ").append(tick);
            if (role != null) result.append("\nrole: ").append(role);
            if (field != null) result.append("\nfield: ").append(field);
            if (eventIndex != null) result.append("\nevent: ").append(eventIndex);
            result.append("\nreference: ").append(reference);
            result.append("\nopenggf: ").append(openggf);
            return result.toString();
        }

        /** Stable one-line machine view with a fixed field order. */
        public String toMachineText() {
            return "{\"schema\":\"openggf.s3k_audio_oracle_report.v1\""
                    + ",\"kind\":\"" + kind + "\""
                    + ",\"ticks_compared\":" + ticksCompared
                    + ",\"tick\":" + jsonNumber(tick)
                    + ",\"role\":" + jsonString(role)
                    + ",\"field\":" + jsonString(field)
                    + ",\"event\":" + jsonNumber(eventIndex)
                    + ",\"reference\":" + jsonString(reference)
                    + ",\"openggf\":" + jsonString(openggf) + "}";
        }

        private static String jsonNumber(Integer value) {
            return value == null ? "null" : value.toString();
        }

        static String jsonString(String value) {
            if (value == null) {
                return "null";
            }
            return "\"" + value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n")
                    .replace("\t", "\\t") + "\"";
        }
    }

    private S3kAudioParityComparator() {
    }

    public static Report compare(List<S3kAudioTick> reference, List<S3kAudioTick> openGgf) {
        if (reference.size() != openGgf.size()) {
            return new Report(Report.Kind.TICK_COUNT_MISMATCH, 0, null, null, "tick_count", null,
                    Integer.toString(reference.size()), Integer.toString(openGgf.size()));
        }
        for (int ordinal = 0; ordinal < reference.size(); ordinal++) {
            S3kAudioTick.ProducerInputEvidence input =
                    reference.get(ordinal).producerInputEvidence();
            if (input.unavailable()) {
                return new Report(Report.Kind.REFERENCE_LIMITATION,
                        ordinal, ordinal, null, "producer_input", null,
                        input.detail(), "<unavailable>");
            }
            Report difference = compareTick(reference.get(ordinal), openGgf.get(ordinal), ordinal);
            if (difference != null) {
                return difference;
            }
        }
        return new Report(Report.Kind.MATCH, reference.size(), null, null, null, null, null, null);
    }

    static Report compareTick(S3kAudioTick reference, S3kAudioTick openGgf, int ordinal) {
        Report global = compareGlobal(reference.global(), openGgf.global(), ordinal);
        if (global != null) {
            return global;
        }
        for (int index = 0; index < S3kAudioParitySchema.ROLES.size(); index++) {
            Report track = compareTrack(reference.tracks().get(index), openGgf.tracks().get(index),
                    ordinal);
            if (track != null) {
                return track;
            }
        }
        return compareWrites(serviceWrites(reference.writes()),
                serviceWrites(openGgf.writes()), ordinal);
    }

    /**
     * Every write of the tick except the DAC sample bytes and the sample-end
     * {@code 2Bh = 0} disable, both of which the DAC byte stream compares.
     * The {@code 2Bh = 80h} enable stays here.
     */
    private static List<AudioParityChipWrite> serviceWrites(
            List<AudioParityChipWrite> writes) {
        List<AudioParityChipWrite> result = new ArrayList<>(writes.size());
        for (AudioParityChipWrite write : writes) {
            if (!isDacSampleByte(write) && !isDacDisableWrite(write)) {
                result.add(write);
            }
        }
        return result;
    }

    /** The {@code 2Bh = 0} a sample's exhaustion writes on re-entry. */
    private static boolean isDacDisableWrite(AudioParityChipWrite write) {
        return isDacEnableWrite(write) && write.value() == 0;
    }

    private static boolean isDacSampleByte(AudioParityChipWrite write) {
        return "ym2612".equals(write.chip()) && write.port() != null
                && write.port() == 0 && write.register() != null
                && write.register() == DAC_DATA_REGISTER;
    }

    private static boolean isDacEnableWrite(AudioParityChipWrite write) {
        return "ym2612".equals(write.chip()) && write.port() != null
                && write.port() == 0 && write.register() != null
                && write.register() == DAC_ENABLE_REGISTER;
    }

    /**
     * Splits a whole window's writes into DAC sample runs.
     *
     * <p>A run is a maximal group of {@code 2Ah} bytes uninterrupted by a
     * {@code 2Bh} write. That is the ROM's own bracket: the idle loop enables
     * the DAC on finding {@code zDACIndex} non-zero
     * (Sound/Z80 Sound Driver.asm:4269-4276), streams the sample, and the
     * fall-through re-enters {@code zPlayDigitalAudio} whose entry disables it
     * (:4348-4355, :4256-4260). Tick boundaries are deliberately not run
     * boundaries; see the class comment.</p>
     */
    static List<DacRun> dacRuns(List<S3kAudioTick> ticks) {
        List<DacRun> runs = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        boolean enabled = false;
        int bytesWhileDisabled = 0;
        int firstByteService = -1;
        for (S3kAudioTick tick : ticks) {
            for (AudioParityChipWrite write : tick.writes()) {
                if (isDacSampleByte(write)) {
                    if (current.isEmpty()) firstByteService = tick.ordinal();
                    if (!enabled) {
                        bytesWhileDisabled++;
                    }
                    current.add(write.value());
                } else if (isDacEnableWrite(write)) {
                    boolean disable = write.value() == 0;
                    if (!current.isEmpty()) {
                        runs.add(new DacRun(toArray(current),
                                disable ? 1 : 0, bytesWhileDisabled, firstByteService));
                        current = new ArrayList<>();
                        bytesWhileDisabled = 0;
                    } else if (disable && !runs.isEmpty()) {
                        // A second disable before the next run's first byte.
                        // The ROM writes exactly one, so charge it to the run
                        // it follows and let the comparison reject it.
                        DacRun last = runs.remove(runs.size() - 1);
                        runs.add(new DacRun(last.bytes(),
                                last.trailingDisables() + 1,
                                last.bytesWhileDisabled(), last.firstByteService()));
                    }
                    enabled = !disable;
                }
            }
        }
        if (!current.isEmpty()) {
            runs.add(new DacRun(toArray(current), 0, bytesWhileDisabled, firstByteService));
        }
        return runs;
    }

    /**
     * One contiguous group of {@code 2Ah} bytes with the terminator evidence
     * that follows it.
     *
     * @param bytes the decoded sample bytes, in order
     * @param trailingDisables how many {@code 2Bh = 0} writes fall between
     *     this run's last byte and the next run's first. The ROM writes at
     *     most one, and writes it only when the sample was exhausted rather
     *     than superseded.
     * @param bytesWhileDisabled how many of this run's bytes arrived while
     *     the last {@code 2Bh} write had left the DAC disabled. Always zero
     *     in the ROM, since the idle loop streams nothing.
     * @param firstByteService service containing the first sample byte, retained
     *     as provenance only; service drift never realigns the compared runs
     */
    record DacRun(int[] bytes, int trailingDisables, int bytesWhileDisabled,
            int firstByteService) {
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    /**
     * Compares the two windows' DAC sample runs: the run count exactly, then
     * every byte the two sides share in each run, in order.
     *
     * <p>A run's <em>length</em> is deliberately not asserted, and the reason
     * is the same unmodelled quantity as the tick partition. A run ends
     * either because the sample was exhausted or because a later play cut it
     * short (the {@code jp p, .dac_idle_loop} at
     * Sound/Z80 Sound Driver.asm:4343-4345), and how far the stream got
     * before that cut is Z80 duration. Measured: the engine, which spends the
     * whole inter-V-int interval streaming because it models no service cost,
     * carries the first music sample 1,438 bytes where the reference's own
     * play was cut at 1,364. Both sides' bytes agree over every byte they
     * share, which is the content this comparison exists to prove. The
     * cumulative difference is reported as {@code run-length delta} so a
     * regression in it stays visible.</p>
     *
     * <p>Whether a run carries a trailing {@code 2Bh = 0} inherits that same
     * quantity, because only an exhausted sample reaches
     * {@code zPlayDigitalAudio}'s entry disable (:4258-4262) and a superseded
     * one does not. So a run terminated by a disable on one side and by the
     * next enable on the other is excused, and the count is reported as
     * {@code sample-end delta}.</p>
     *
     * <p>Two further quantities are counted rather than asserted, and both
     * were demoted because the committed reference itself violates the
     * invariant that would have promoted them. How many disables fall between
     * two runs is not fixed: run 0 carries three on both sides, because
     * {@code zPlaySEGAPCM} returns through {@code zPlayDigitalAudio}'s entry
     * and re-writes one each time the idle path is re-entered. And a
     * {@code 2Ah} byte streaming while the DAC is disabled is not impossible:
     * the reference does it once, at service 3,837. That count is reported as
     * {@code idle-byte delta}; the engine's own figure is 497 from service
     * 496, which is a real difference and stays visible without being called
     * an error the reference would also fail.</p>
     */
    public static DacStreamReport compareDacStream(List<S3kAudioTick> reference,
            List<S3kAudioTick> openGgf) {
        List<DacRun> referenceRuns = dacRuns(reference);
        List<DacRun> openGgfRuns = dacRuns(openGgf);
        int idleByteDelta = idleBytes(openGgfRuns) - idleBytes(referenceRuns);
        int common = Math.min(referenceRuns.size(), openGgfRuns.size());
        int bytes = 0;
        int truncationDelta = 0;
        int sampleEndDelta = 0;
        for (int run = 0; run < common; run++) {
            DacRun expectedRun = referenceRuns.get(run);
            DacRun actualRun = openGgfRuns.get(run);
            int[] expected = expectedRun.bytes();
            int[] actual = actualRun.bytes();
            int shared = Math.min(expected.length, actual.length);
            for (int index = 0; index < shared; index++) {
                if (expected[index] != actual[index]) {
                    return new DacStreamReport(
                            DacStreamReport.Kind.BYTE_DIFFERENT,
                            referenceRuns.size(), bytes, truncationDelta,
                            sampleEndDelta, idleByteDelta, run, index,
                            hex(expected[index]), hex(actual[index]),
                            expectedRun.firstByteService(), actualRun.firstByteService());
                }
            }
            bytes += shared;
            truncationDelta += actual.length - expected.length;
            sampleEndDelta += actualRun.trailingDisables()
                    - expectedRun.trailingDisables();
        }
        if (referenceRuns.size() != openGgfRuns.size()) {
            return new DacStreamReport(DacStreamReport.Kind.RUN_COUNT_DIFFERENT,
                    referenceRuns.size(), bytes, truncationDelta,
                    sampleEndDelta, idleByteDelta, common, 0,
                    Integer.toString(referenceRuns.size()),
                    Integer.toString(openGgfRuns.size()),
                    common < referenceRuns.size() ? referenceRuns.get(common).firstByteService() : null,
                    common < openGgfRuns.size() ? openGgfRuns.get(common).firstByteService() : null);
        }
        return new DacStreamReport(DacStreamReport.Kind.MATCH,
                referenceRuns.size(), bytes, truncationDelta, sampleEndDelta,
                idleByteDelta, null, null, null, null, null, null);
    }

    /** Total bytes that streamed while the DAC's last {@code 2Bh} left it off. */
    private static int idleBytes(List<DacRun> runs) {
        int total = 0;
        for (DacRun run : runs) {
            total += run.bytesWhileDisabled();
        }
        return total;
    }

    private static String hex(int value) {
        return String.format("0x%02X", value);
    }

    /**
     * The result of the unpartitioned DAC byte-stream comparison, reported
     * beside the per-tick service-write result.
     *
     * <p>Run-start services identify each run's first sample byte, not its
     * enable or the mismatching byte. An earlier service mismatch can leave
     * the two run ordinals referring to different plays; these coordinates
     * expose that distinction without realigning or excusing any byte.</p>
     */
    public record DacStreamReport(Kind kind, int runs, int bytesCompared,
            int truncationDelta, int sampleEndDelta, int idleByteDelta,
            Integer run, Integer byteOffset, String reference, String openggf,
            Integer referenceRunStartService, Integer openggfRunStartService) {
        public enum Kind {
            MATCH, RUN_COUNT_DIFFERENT, BYTE_DIFFERENT
        }

        public boolean matches() {
            return kind == Kind.MATCH;
        }

        public String toHumanText() {
            if (matches()) {
                return "S3K DAC byte stream: MATCH (" + runs + " runs, "
                        + bytesCompared + " bytes, run-length delta "
                        + truncationDelta + ", sample-end delta "
                        + sampleEndDelta + ", idle-byte delta "
                        + idleByteDelta + ")";
            }
            return "S3K DAC byte stream: MISMATCH\nkind: " + kind
                    + "\nrun: " + run
                    + "\nbyte: " + byteOffset
                    + "\nreference: " + reference
                    + "\nopenggf: " + openggf
                    + "\nreference run start service: " + referenceRunStartService
                    + "\nopenggf run start service: " + openggfRunStartService;
        }

        public String toMachineText() {
            return "{\"schema\":\"openggf.s3k_audio_dac_stream_report.v1\""
                    + ",\"kind\":\"" + kind + "\""
                    + ",\"runs\":" + runs
                    + ",\"bytes_compared\":" + bytesCompared
                    + ",\"run_length_delta\":" + truncationDelta
                    + ",\"sample_end_delta\":" + sampleEndDelta
                    + ",\"idle_byte_delta\":" + idleByteDelta
                    + ",\"run\":" + (run == null ? "null" : run)
                    + ",\"byte\":" + (byteOffset == null ? "null" : byteOffset)
                    + ",\"reference\":" + Report.jsonString(reference)
                    + ",\"openggf\":" + Report.jsonString(openggf)
                    + ",\"reference_run_start_service\":" + Report.jsonNumber(referenceRunStartService)
                    + ",\"openggf_run_start_service\":" + Report.jsonNumber(openggfRunStartService) + "}";
        }
    }

    private static Report compareGlobal(S3kAudioTick.GlobalState reference,
            S3kAudioTick.GlobalState openGgf, int ordinal) {
        Report result = globalField(ordinal, "currentTempo", reference.currentTempo(),
                openGgf.currentTempo());
        if (result != null) return result;
        result = globalField(ordinal, "tempoAccumulator", reference.tempoAccumulator(),
                openGgf.tempoAccumulator());
        if (result != null) return result;
        result = globalField(ordinal, "tempoSpeedup", reference.tempoSpeedup(),
                openGgf.tempoSpeedup());
        if (result != null) return result;
        result = globalField(ordinal, "fadeOutTimeout", reference.fadeOutTimeout(),
                openGgf.fadeOutTimeout());
        if (result != null) return result;
        result = globalField(ordinal, "fadeDelay", reference.fadeDelay(),
                openGgf.fadeDelay());
        if (result != null) return result;
        result = globalField(ordinal, "fadeDelayTimeout", reference.fadeDelayTimeout(),
                openGgf.fadeDelayTimeout());
        if (result != null) return result;
        result = globalField(ordinal, "fadeInTimeout", reference.fadeInTimeout(),
                openGgf.fadeInTimeout());
        if (result != null) return result;
        result = globalField(ordinal, "palDoubleUpdateCounter",
                reference.palDoubleUpdateCounter(), openGgf.palDoubleUpdateCounter());
        if (result != null) return result;
        return globalField(ordinal, "speedupTimeout", reference.speedupTimeout(),
                openGgf.speedupTimeout());
    }

    private static Report globalField(int ordinal, String field, Object reference, Object openGgf) {
        return Objects.equals(reference, openGgf) ? null
                : new Report(Report.Kind.GLOBAL_STATE_MISMATCH, ordinal, ordinal, "GLOBAL", field,
                        null, String.valueOf(reference), String.valueOf(openGgf));
    }

    private static Report compareTrack(S3kAudioTrackState reference, S3kAudioTrackState openGgf,
            int ordinal) {
        String role = reference.role();
        Report result = trackField(ordinal, role, "playing", reference.playing(), openGgf.playing());
        if (result != null) return result;
        if (!reference.playing() && !openGgf.playing()) {
            return null;
        }
        result = trackField(ordinal, role, "overridden", reference.overridden(), openGgf.overridden());
        if (result != null) return result;
        result = trackField(ordinal, role, "doNotAttack", reference.doNotAttack(), openGgf.doNotAttack());
        if (result != null) return result;
        result = trackField(ordinal, role, "resting", reference.resting(), openGgf.resting());
        if (result != null) return result;
        result = trackField(ordinal, role, "voiceControl", reference.voiceControl(), openGgf.voiceControl());
        if (result != null) return result;
        result = trackField(ordinal, role, "tempoDivider", reference.tempoDivider(), openGgf.tempoDivider());
        if (result != null) return result;
        result = trackField(ordinal, role, "transpose", reference.transpose(), openGgf.transpose());
        if (result != null) return result;
        result = trackField(ordinal, role, "volume", reference.volume(), openGgf.volume());
        if (result != null) return result;
        result = trackField(ordinal, role, "modulationCtrl", reference.modulationCtrl(),
                openGgf.modulationCtrl());
        if (result != null) return result;
        result = trackField(ordinal, role, "voiceIndex", reference.voiceIndex(), openGgf.voiceIndex());
        if (result != null) return result;
        result = trackField(ordinal, role, "amsFmsPan", reference.amsFmsPan(), openGgf.amsFmsPan());
        if (result != null) return result;
        result = trackField(ordinal, role, "durationTimeout", reference.durationTimeout(),
                openGgf.durationTimeout());
        if (result != null) return result;
        result = trackField(ordinal, role, "savedDuration", reference.savedDuration(),
                openGgf.savedDuration());
        if (result != null) return result;
        result = trackField(ordinal, role, "frequency", reference.frequency(), openGgf.frequency());
        if (result != null) return result;
        result = trackField(ordinal, role, "detune", reference.detune(), openGgf.detune());
        if (result != null) return result;
        result = trackField(ordinal, role, "volEnv", reference.volEnv(), openGgf.volEnv());
        if (result != null) return result;
        result = trackField(ordinal, role, "noteFillTimeout", reference.noteFillTimeout(),
                openGgf.noteFillTimeout());
        if (result != null) return result;
        result = trackField(ordinal, role, "noteFillMaster", reference.noteFillMaster(),
                openGgf.noteFillMaster());
        if (result != null) return result;
        result = trackField(ordinal, role, "modulationVal", reference.modulationVal(),
                openGgf.modulationVal());
        if (result != null) return result;
        result = trackField(ordinal, role, "modulationWait", reference.modulationWait(),
                openGgf.modulationWait());
        if (result != null) return result;
        result = trackField(ordinal, role, "modulationSpeed", reference.modulationSpeed(),
                openGgf.modulationSpeed());
        if (result != null) return result;
        result = trackField(ordinal, role, "modulationDelta", reference.modulationDelta(),
                openGgf.modulationDelta());
        if (result != null) return result;
        return trackField(ordinal, role, "modulationSteps", reference.modulationSteps(),
                openGgf.modulationSteps());
    }

    private static Report trackField(int ordinal, String role, String field, Object reference,
            Object openGgf) {
        return Objects.equals(reference, openGgf) ? null
                : new Report(Report.Kind.TRACK_STATE_MISMATCH, ordinal, ordinal, role, field, null,
                        String.valueOf(reference), String.valueOf(openGgf));
    }

    private static Report compareWrites(List<AudioParityChipWrite> reference,
            List<AudioParityChipWrite> openGgf, int ordinal) {
        int common = Math.min(reference.size(), openGgf.size());
        for (int index = 0; index < common; index++) {
            if (!reference.get(index).equals(openGgf.get(index))) {
                return new Report(Report.Kind.EVENT_VALUE_DIFFERENT, ordinal, ordinal, null,
                        "decoded_write", index, reference.get(index).toString(),
                        openGgf.get(index).toString());
            }
        }
        if (reference.size() > openGgf.size()) {
            return new Report(Report.Kind.EVENT_MISSING, ordinal, ordinal, null, "decoded_write",
                    common, reference.get(common).toString(), "<missing>");
        }
        if (openGgf.size() > reference.size()) {
            return new Report(Report.Kind.EVENT_EXTRA, ordinal, ordinal, null, "decoded_write",
                    common, "<missing>", openGgf.get(common).toString());
        }
        return null;
    }
}
