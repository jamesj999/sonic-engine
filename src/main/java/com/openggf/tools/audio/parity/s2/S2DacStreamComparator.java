package com.openggf.tools.audio.parity.s2;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The S2 driver oracle's DAC byte stream, compared as its own whole-window
 * stream rather than inside the per-service write partition.
 *
 * <h2>Why the sample bytes leave the partition</h2>
 * Which service window a given {@code 2Ah} byte falls in is not a property of
 * the driver's logic at all. {@code zWriteToDAC} streams a sample from outside
 * the interrupt, bracketing each byte with {@code di} / {@code ei} and spending
 * the rest of its time in two {@code djnz $} busy waits
 * (s2.sounddriver.asm:682-726). Those waits are the only window a V-int can
 * land in, so how many bytes precede a given service is a function of how long
 * the Z80 spent inside {@code zUpdateEverything} with interrupts masked. That
 * is a cycle-level quantity the engine does not model: it advances the chip by
 * a whole V-int of sample clock per service and charges nothing for the service
 * itself. Partitioning the bytes by service therefore compares Z80 duration,
 * not driver behaviour. This is the same limitation the S3K oracle already
 * excuses; see docs/status/known-discrepancies.md, "S2 music DAC byte stream
 * partition".
 *
 * <h2>What a run is, and why the boundary is the ROM's own</h2>
 * Sonic 2's DAC loop writes nothing at all between samples: {@code zWaitLoop}
 * spins on the remaining length {@code de} being zero and touches no register
 * until a sample is queued (:647-650). So a completed service that carries no
 * {@code 2Ah} byte is a real gap between samples in the driver's own terms, and
 * it is visible from the write stream alone on both sides. A run is therefore a
 * maximal group of {@code 2Ah} bytes across consecutive services, closed by a
 * service that emitted none.
 *
 * <p>Unlike S3K, Sonic 2's playback loop never writes the DAC enable register
 * per sample: every {@code 2Bh} write in this driver belongs to a song load, a
 * fade or the SEGA chant (:1613, :1662, :1936, :2555, :3158), so {@code 2Bh}
 * stays inside the ordinary per-service partition and is not part of this
 * stream.
 *
 * <h2>What is asserted and what is only reported</h2>
 * The run count is asserted exactly, and so is every byte the two sides share
 * within each run, in order. A run's <em>length</em> is not asserted, for the
 * same unmodelled quantity as above: a run ends when the sample is exhausted,
 * and how many bytes the engine got through before the boundary reflects the
 * service cost it does not charge. The cumulative difference is reported as
 * {@code run-length delta} so a regression in it stays visible.
 */
public final class S2DacStreamComparator {

    /** {@code 2Ah}, the DAC sample-byte register {@code zWriteToDAC} writes. */
    static final int DAC_DATA_REGISTER = 0x2A;

    private S2DacStreamComparator() {
    }

    /** True for a write this comparison owns, and the service partition excludes. */
    public static boolean isDacSampleByte(S2OracleRawStream.ChipWrite write) {
        return write.ym() && write.port() == 0
                && write.register() == DAC_DATA_REGISTER;
    }

    /** The writes of one service with the DAC sample bytes removed. */
    public static List<S2OracleRawStream.ChipWrite> withoutDacSampleBytes(
            List<S2OracleRawStream.ChipWrite> writes) {
        List<S2OracleRawStream.ChipWrite> kept =
                new ArrayList<>(writes.size());
        for (S2OracleRawStream.ChipWrite write : writes) {
            if (!isDacSampleByte(write)) {
                kept.add(write);
            }
        }
        return kept;
    }

    /**
     * One completed service as this comparison sees it: the writes it emitted,
     * the driver's current DAC sample selector at its return, and the decoded
     * length of that sample.
     *
     * <p>The two sides number their selectors differently and are never
     * compared to each other. The reference carries {@code zCurDAC}, which
     * {@code zUpdateDAC} has already rebased by {@code 81h}
     * (s2.sounddriver.asm:505-518); the engine carries the note its DAC track
     * last played. Each side's length comes from the ROM's own DAC table
     * through that side's own selector: {@code zDACLenTbl}, the second half of
     * each four-byte entry the same routine indexes (:513-518, :528-529), which
     * the engine loads as {@code DacData}. A length of -1 means the selector
     * names no sample and imposes no bound.
     */
    public record Service(List<S2OracleRawStream.ChipWrite> writes, int sample,
            int sampleLength) {
        public Service {
            writes = List.copyOf(writes);
        }
    }

    /**
     * Splits a whole window's services into DAC sample runs.
     *
     * <p>Three boundaries, all of them the driver's own.
     *
     * <p>A run reaching its sample's decoded length ends there. That is the
     * bound {@code zWriteToDAC} itself enforces: it decrements {@code de},
     * loaded from {@code zDACLenTbl}, once per source byte and returns to
     * {@code zWaitLoop} when it hits zero (:528-529, :682-726). So a run is at
     * most one sample long, and two consecutive plays of one sample become two
     * runs of that length even with nothing between them.
     *
     * <p>A completed service that carried no sample byte ends a run, because
     * {@code zWaitLoop} touches no register while the remaining length is zero
     * (:647-650).
     *
     * <p>A change of the current sample selector ends a run, which is what a
     * play superseded by another looks like. Such a run is shorter than its
     * sample's length, and how much shorter is the service-duration quantity
     * this comparison excuses.
     */
    static List<Run> runs(List<Service> services) {
        List<Run> runs = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int previousSample = services.isEmpty() ? 0 : services.get(0).sample();
        int bound = -1;
        for (Service service : services) {
            int before = current.size();
            for (S2OracleRawStream.ChipWrite write : service.writes()) {
                if (!isDacSampleByte(write)) {
                    continue;
                }
                if (current.isEmpty()) {
                    // A run's bound is its own sample's length, read when its
                    // first byte lands. Reading it later would pick up the
                    // length of whatever superseded it.
                    bound = service.sampleLength();
                }
                current.add(write.value());
                if (bound > 0 && current.size() == bound) {
                    runs.add(new Run(toArray(current), bound));
                    current = new ArrayList<>();
                }
            }
            boolean silentService = current.size() == before;
            boolean sampleChanged = service.sample() != previousSample;
            previousSample = service.sample();
            if ((silentService || sampleChanged) && !current.isEmpty()) {
                runs.add(new Run(toArray(current), bound));
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            runs.add(new Run(toArray(current), bound));
        }
        return runs;
    }

    /**
     * One run and the decoded length of the sample it was playing. A run
     * shorter than its bound was ended by a supersession rather than by
     * exhausting its sample.
     */
    record Run(int[] bytes, int bound) {
        boolean superseded() {
            return bound > 0 && bytes.length < bound;
        }
    }

    private static int[] toArray(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    /**
     * Compares the reference's DAC stream against the engine's over the whole
     * compared window. The two lists must already be aligned service for
     * service, exactly as the per-service comparison requires.
     */
    public static Report compare(List<Service> reference, List<Service> engine) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(engine, "engine");
        List<Run> referenceRuns = runs(reference);
        List<Run> engineRuns = runs(engine);

        int common = Math.min(referenceRuns.size(), engineRuns.size());
        int bytes = 0;
        int runLengthDelta = 0;
        // Runs both sides played to their sample's full decoded length and
        // agreed on byte for byte. A difference inside one of these would be
        // sample data; a difference after a pair of short runs is the
        // supersession join instead.
        int completeRunsAgreed = 0;
        for (int run = 0; run < common; run++) {
            int[] expected = referenceRuns.get(run).bytes();
            int[] actual = engineRuns.get(run).bytes();
            int shared = Math.min(expected.length, actual.length);
            for (int index = 0; index < shared; index++) {
                if (expected[index] != actual[index]) {
                    return new Report(Kind.BYTE_DIFFERENT, referenceRuns.size(),
                            bytes + index, runLengthDelta, run, index,
                            hex(expected[index]), hex(actual[index]),
                            resync(expected, actual, index),
                            precedingJoin(referenceRuns, engineRuns, run),
                            completeRunsAgreed);
                }
            }
            bytes += shared;
            runLengthDelta += actual.length - expected.length;
            if (!referenceRuns.get(run).superseded()
                    && !engineRuns.get(run).superseded()
                    && expected.length == actual.length) {
                completeRunsAgreed++;
            }
        }
        if (referenceRuns.size() != engineRuns.size()) {
            return new Report(Kind.RUN_COUNT_DIFFERENT, referenceRuns.size(),
                    bytes, runLengthDelta, common, 0,
                    Integer.toString(referenceRuns.size()),
                    Integer.toString(engineRuns.size()), null, null,
                    completeRunsAgreed);
        }
        return new Report(Kind.MATCH, referenceRuns.size(), bytes,
                runLengthDelta, null, null, null, null, null, null,
                completeRunsAgreed);
    }

    /**
     * The supersession that ended the previous pair of runs, when one did.
     *
     * <p>A difference at or near the start of a run says less about that run
     * than about where the run before it stopped. A run shorter than its
     * sample's decoded length was cut off by another play rather than
     * exhausting its sample, and how far each side got before the cut is the
     * service-duration quantity this comparison excuses. Reporting it names
     * which of the two situations produced the difference; it decides nothing.
     */
    private static Join precedingJoin(List<Run> reference, List<Run> engine,
            int run) {
        if (run == 0) {
            return null;
        }
        Run before = reference.get(run - 1);
        Run engineBefore = engine.get(run - 1);
        if (!before.superseded() && !engineBefore.superseded()) {
            return null;
        }
        return new Join(before.bytes().length, engineBefore.bytes().length,
                before.bound());
    }

    /** Two short runs and the sample length they both fell short of. */
    public record Join(int referenceBytes, int engineBytes, int bound) {
    }

    /** Bytes of agreement required before a later offset counts as a resync. */
    private static final int RESYNC_WINDOW = 64;

    /**
     * Where the reference's remaining bytes pick up again in the engine's run,
     * when they do.
     *
     * <p>This decides nothing and suppresses nothing; it only says which of two
     * very different situations produced a byte difference. A run that merges
     * two plays, because one superseded the other with no silent service
     * between them, puts the join at whatever byte each side's Z80 had reached,
     * which is the service-duration quantity this comparison already excuses.
     * Such a difference resyncs: the reference's next sample appears intact
     * further along the engine's run. A genuine decode or sample-selection
     * error does not.
     */
    private static Resync resync(int[] expected, int[] actual, int from) {
        int remaining = expected.length - from;
        if (remaining < RESYNC_WINDOW) {
            return null;
        }
        for (int offset = from + 1; offset + RESYNC_WINDOW <= actual.length;
                offset++) {
            boolean windowAgrees = true;
            for (int index = 0; index < RESYNC_WINDOW; index++) {
                if (expected[from + index] != actual[offset + index]) {
                    windowAgrees = false;
                    break;
                }
            }
            if (!windowAgrees) {
                continue;
            }
            int agreeing = 0;
            int limit = Math.min(remaining, actual.length - offset);
            while (agreeing < limit
                    && expected[from + agreeing] == actual[offset + agreeing]) {
                agreeing++;
            }
            return new Resync(offset, agreeing);
        }
        return null;
    }

    /** The engine offset the reference's remaining bytes resume at. */
    public record Resync(int engineOffset, int agreeingBytes) {
    }

    public enum Kind { MATCH, RUN_COUNT_DIFFERENT, BYTE_DIFFERENT }

    /** The DAC stream result, reported beside the per-service result. */
    public record Report(Kind kind, int runs, int bytesCompared,
            int runLengthDelta, Integer run, Integer byteOffset,
            String reference, String engine, Resync resync,
            Join precedingJoin, int completeRunsAgreed) {

        public boolean matches() {
            return kind == Kind.MATCH;
        }

        public String describe() {
            return switch (kind) {
                case MATCH -> "S2 DAC byte stream: MATCH (" + runs + " runs, "
                        + bytesCompared + " bytes, run-length delta "
                        + runLengthDelta + ", " + completeRunsAgreed
                        + " complete runs agreed)";
                case RUN_COUNT_DIFFERENT -> "S2 DAC byte stream: RUN COUNT "
                        + "DIFFERENT: reference " + reference + " runs, engine "
                        + engine + " runs (" + bytesCompared
                        + " bytes agreed over " + run
                        + " shared runs, run-length delta " + runLengthDelta
                        + ")";
                case BYTE_DIFFERENT -> "S2 DAC byte stream: BYTE DIFFERENT in "
                        + "run " + run + " at byte " + byteOffset
                        + ": reference " + reference + ", engine " + engine
                        + " (" + runs + " runs, run-length delta "
                        + runLengthDelta
                        + (resync == null
                                ? ", no resync"
                                : ", reference resyncs at engine byte "
                                        + resync.engineOffset() + " for "
                                        + resync.agreeingBytes() + " bytes")
                        + (precedingJoin == null
                                ? ""
                                : ", previous run superseded: reference "
                                        + precedingJoin.referenceBytes()
                                        + " and engine "
                                        + precedingJoin.engineBytes()
                                        + " of " + precedingJoin.bound())
                        + ", " + completeRunsAgreed + " complete runs agreed)";
            };
        }
    }

    private static String hex(int value) {
        return String.format("0x%02X", value);
    }
}
