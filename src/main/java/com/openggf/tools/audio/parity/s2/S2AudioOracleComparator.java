package com.openggf.tools.audio.parity.s2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Validation-first, no-realignment comparison of the committed S2 driver-oracle
 * reference (per-invocation Z80 RAM image + attributed YM/PSG write stream)
 * against the engine's {@link S2OracleEngineCapture}.
 *
 * <p><b>One oracle tick is one completed {@code zUpdateMusic} service</b>, not
 * one video frame: the observer's service stream marks each update (manifest
 * kind 9 completion), and the reference shows why the distinction matters —
 * a Saxman song load runs for several frames with interrupts masked, so the frames inside it
 * contain no driver update at all (e.g. rows 10196-10200 of this fixture hold
 * the half-initialised RAM of the in-progress load, and row 10202's V-int was
 * missed entirely while the Z80 caught up). Ticks are recovered from the
 * reference's own service markers; within a tick, the kind-9 writes since the
 * previous completion belong to that tick. No realignment
 * beyond this service-marker recovery is performed: tick {@code n} of the
 * reference is compared against engine update {@code n}, and the first
 * divergence is reported with its tick, movie row, field, and expected/actual
 * values.
 */
public final class S2AudioOracleComparator {

    private S2AudioOracleComparator() {
    }

    public enum Kind { MATCH, DIVERGENCE, INVALID }

    public record Report(Kind kind, int comparedTicks, int divergentTicks,
            int firstDivergenceTick, int firstDivergenceRow, String firstDivergenceField,
            String firstDivergenceDetail, String invalidReason) {

        public String describe() {
            return switch (kind) {
                case MATCH -> "S2 driver oracle: MATCH (" + comparedTicks + " ticks)";
                case DIVERGENCE -> "S2 driver oracle: DIVERGENCE at tick "
                        + firstDivergenceTick + " (movie row " + firstDivergenceRow
                        + "), field " + firstDivergenceField + ": " + firstDivergenceDetail
                        + " [" + divergentTicks + " of " + comparedTicks
                        + " ticks divergent]";
                case INVALID -> "S2 driver oracle: INVALID: " + invalidReason;
            };
        }
    }

    /**
     * One recovered driver update: the frame whose service stream completed a
     * zUpdateMusic, with the sequencer-owned writes accumulated since the
     * previous update (a long load spreads one invocation's writes over
     * several frames).
     */
    public record ReferenceTick(int ordinal, int row, byte[] state,
            List<S2OracleRawStream.ChipWrite> writes) {
        public ReferenceTick {
            state = state.clone();
            writes = List.copyOf(writes);
        }

        @Override
        public byte[] state() {
            return state.clone();
        }
    }

    /** Compares the committed fixture against a fresh engine capture. */
    public static Report compare(Path fixtureGz, Path romPath, boolean verifyDigest) {
        Objects.requireNonNull(fixtureGz, "fixtureGz");
        Objects.requireNonNull(romPath, "romPath");
        List<S2OracleRawStream.Frame> frames;
        try {
            if (verifyDigest) {
                String digest = sha256(fixtureGz);
                if (!S2OracleSchema.PAYLOAD_GZ_SHA256.equals(digest)) {
                    return invalid("fixture digest changed: " + digest);
                }
            }
            frames = readFrames(fixtureGz);
        } catch (IOException | RuntimeException error) {
            return invalid("fixture unreadable: " + error.getMessage());
        }

        List<ReferenceTick> ticks = buildTicks(frames, S2OracleSchema.ANCHOR_ROW);
        if (ticks.isEmpty()) {
            return invalid("fixture contains no driver update at or after the anchor row");
        }
        S2OracleDriverState anchor = S2OracleDriverState.decode(ticks.get(0).state());
        if (anchor.globals().curSong() != S2OracleSchema.ANCHOR_ROM_MUSIC_ID) {
            return invalid("anchor update does not hold the pinned song id");
        }
        int speedUpTick = ticks.size();
        for (ReferenceTick tick : ticks) {
            if (tick.row() >= S2OracleSchema.SPEED_UP_ROW) {
                speedUpTick = tick.ordinal();
                break;
            }
        }

        List<S2OracleEngineCapture.EngineTick> engine;
        try {
            engine = S2OracleEngineCapture.capture(romPath, ticks.size(), speedUpTick);
        } catch (RuntimeException error) {
            return invalid("engine capture failed: " + error.getMessage());
        }
        return compareWithEngine(ticks, engine);
    }

    /**
     * Recovers the driver-update ticks from the frame stream, starting at the
     * first frame at or after {@code fromRow}. Frames without an UpdateMusic
     * service contribute their sequencer-owned writes to the next tick; frames
     * after the final update are dropped.
     */
    public static List<ReferenceTick> buildTicks(List<S2OracleRawStream.Frame> frames,
            int fromRow) {
        List<ReferenceTick> ticks = new ArrayList<>();
        List<S2OracleRawStream.ChipWrite> pending = new ArrayList<>();
        for (S2OracleRawStream.Frame frame : frames) {
            if (frame.row() < fromRow) {
                continue;
            }
            for (S2OracleRawStream.ChipWrite write : frame.writes()) {
                if (write.updateMusicOwned()) {
                    pending.add(write);
                }
            }
            if (frame.updateMusicCompletions() > 0) {
                ticks.add(new ReferenceTick(ticks.size(), frame.row(), frame.state(),
                        pending));
                pending = new ArrayList<>();
            }
        }
        return ticks;
    }

    /**
     * Core comparison, exposed for the deliberate-corruption tests: recovered
     * reference ticks against pre-captured engine ticks.
     */
    static Report compareWithEngine(List<ReferenceTick> reference,
            List<S2OracleEngineCapture.EngineTick> engine) {
        if (engine.size() != reference.size()) {
            return invalid("engine tick count " + engine.size()
                    + " does not cover the reference window " + reference.size());
        }
        int divergentTicks = 0;
        int firstTick = -1;
        int firstRow = -1;
        String firstField = null;
        String firstDetail = null;
        for (int tick = 0; tick < reference.size(); tick++) {
            List<String> differences = compareTick(reference.get(tick), engine.get(tick));
            if (!differences.isEmpty()) {
                divergentTicks++;
                if (firstTick < 0) {
                    firstTick = tick;
                    firstRow = reference.get(tick).row();
                    String first = differences.get(0);
                    int split = first.indexOf(' ');
                    firstField = split < 0 ? first : first.substring(0, split);
                    firstDetail = first;
                }
            }
        }
        if (firstTick < 0) {
            return new Report(Kind.MATCH, reference.size(), 0, -1, -1, null, null, null);
        }
        return new Report(Kind.DIVERGENCE, reference.size(), divergentTicks,
                firstTick, firstRow, firstField, firstDetail, null);
    }

    private static List<String> compareTick(ReferenceTick referenceTick,
            S2OracleEngineCapture.EngineTick engine) {
        List<String> differences = new ArrayList<>();
        S2OracleDriverState reference = S2OracleDriverState.decode(referenceTick.state());

        if (reference.globals().currentTempo() != engine.currentTempo()) {
            differences.add("global.currentTempo expected=0x"
                    + Integer.toHexString(reference.globals().currentTempo())
                    + " actual=0x" + Integer.toHexString(engine.currentTempo()));
        }
        if (reference.globals().tempoTimeout() != engine.tempoTimeout()) {
            differences.add("global.tempoTimeout expected=0x"
                    + Integer.toHexString(reference.globals().tempoTimeout())
                    + " actual=0x" + Integer.toHexString(engine.tempoTimeout()));
        }

        List<S2OracleDriverState.TrackState> tracks = reference.musicTracks();
        for (int slot = 0; slot < tracks.size(); slot++) {
            String name = S2OracleDriverState.MUSIC_SLOTS.get(slot);
            boolean psg = name.startsWith("PSG");
            boolean dac = slot == 0;
            S2OracleComparison.MappedTrack expected = S2OracleComparison.MappedTrack
                    .fromReference(tracks.get(slot), psg, dac);
            S2OracleComparison.MappedTrack actual = engine.musicSlots().get(slot);
            for (String difference : expected.differences(actual)) {
                differences.add("track." + name + "." + difference);
            }
        }

        // The DAC sample bytes leave the per-service partition: which service
        // a 2Ah byte lands in is Z80 duration, not driver logic. They are
        // compared as their own whole-window stream by
        // S2DacStreamComparator, which carries the derivation.
        List<S2OracleRawStream.ChipWrite> expectedWrites =
                S2DacStreamComparator.withoutDacSampleBytes(
                        referenceTick.writes());
        List<S2OracleRawStream.ChipWrite> actualWrites =
                S2DacStreamComparator.withoutDacSampleBytes(engine.writes());
        int shared = Math.min(expectedWrites.size(), actualWrites.size());
        for (int index = 0; index < shared; index++) {
            S2OracleRawStream.ChipWrite expected = expectedWrites.get(index);
            S2OracleRawStream.ChipWrite actual = actualWrites.get(index);
            if (expected.ym() != actual.ym() || expected.port() != actual.port()
                    || expected.register() != actual.register()
                    || expected.value() != actual.value()) {
                differences.add("writes[" + index + "] expected=" + describe(expected)
                        + " actual=" + describe(actual));
                break;
            }
        }
        if (expectedWrites.size() != actualWrites.size()) {
            differences.add("writes.count expected=" + expectedWrites.size()
                    + " actual=" + actualWrites.size());
        }
        return differences;
    }

    private static String describe(S2OracleRawStream.ChipWrite write) {
        if (write.ym()) {
            return "ym" + write.port() + "[0x" + Integer.toHexString(write.register())
                    + "]=0x" + Integer.toHexString(write.value());
        }
        return "psg=0x" + Integer.toHexString(write.value());
    }

    private static List<S2OracleRawStream.Frame> readFrames(Path fixtureGz)
            throws IOException {
        List<S2OracleRawStream.Frame> frames = new ArrayList<>();
        S2OracleRawStream.scan(fixtureGz, new S2OracleRawStream.Sink() {
            @Override
            public void header(S2OracleRawStream.Header header) {
            }

            @Override
            public void baseline(S2OracleRawStream.Baseline baseline) {
            }

            @Override
            public void frame(S2OracleRawStream.Frame frame) {
                frames.add(frame);
            }

            @Override
            public void cutoff(int exclusiveEnd) {
            }
        });
        return frames;
    }

    private static Report invalid(String reason) {
        return new Report(Kind.INVALID, 0, 0, -1, -1, null, null, reason);
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
