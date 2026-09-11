package com.openggf.tools.audio.parity.s2;

import com.openggf.tests.SessionInvocationExtension;
import com.openggf.tests.trace.runs.S2RequestProjectionBk2TestBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.openggf.audio.smps.DacData;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measurement over the widened S2 driver-state span. The reference carries one
 * row per completed vertical interrupt, sampled at the driver's own service
 * return; the engine side carries one row per completed engine driver update.
 * They are aligned by ordinal, never by the frame field, and every compared
 * value comes from the engine. Nothing here fixes an engine divergence.
 */
class TestS2DriverStateOracle {

    @Test
    void committedReferenceMatchesItsPinnedDigestAndShape() throws Exception {
        assertEquals(S2DriverStateReference.GZIP_SHA256,
                S2DriverStateReference.gzipDigest());
        S2DriverStateReference.Result reference = S2DriverStateReference.read();
        assertEquals(S2DriverStateReference.TICKS, reference.ticks().size());
        assertEquals(S2DriverStateReference.EXCLUSIVE_END
                        - S2DriverStateReference.FIRST_ROW, reference.frames());
        assertEquals(S2DriverStateReference.ZERO_SERVICE_FRAMES,
                reference.zeroServiceFrames());
        assertEquals(S2DriverStateReference.MULTI_SERVICE_FRAMES,
                reference.multiServiceFrames());
        assertEquals(reference.ticks().size() + reference.zeroServiceFrames(),
                reference.frames(),
                "every frame either completed a service or was run past by one");
        for (S2DriverStateReference.Tick tick : reference.ticks()) {
            assertEquals(S2DriverStateReference.SNAPSHOT_EXCLUSIVE_END
                            - S2DriverStateReference.SNAPSHOT_START,
                    tick.state().length);
        }
    }

    @Test
    @ExtendWith(SessionInvocationExtension.class)
    void driverStateComparesAcrossTheWidenedSpan() throws Exception {
        String romProperty = System.getProperty("sonic2.rom.path");
        String bk2Property = System.getProperty("s2.request.bk2.path");
        assumeTrue(romProperty != null && bk2Property != null,
                "explicit ROM and BK2 paths are required");

        S2DriverStateReference.Result reference = S2DriverStateReference.read();
        S2RequestProjectionBk2TestBridge.Capture capture =
                S2RequestProjectionBk2TestBridge.capture(
                        Path.of(romProperty), Path.of(bk2Property),
                        S2DriverStateReference.FIRST_ROW,
                        S2DriverStateReference.EXCLUSIVE_END);

        S2AudioOracleComparator.Report report = compare(reference, capture, true);
        System.out.println("MEASUREMENT_ONLY s2-driver-state-w10150-12400 "
                + "state and writes: " + report.describe());
        // The DAC sample bytes are excused from the per-service partition and
        // compared as their own whole-window stream, so the two questions are
        // reported as two lines.
        S2DacStreamComparator.Report dac = compareDacStream(reference, capture);
        System.out.println("MEASUREMENT_ONLY s2-driver-state-w10150-12400 "
                + "DAC stream: " + dac.describe());
        // The two questions are separable and answered separately: whether the
        // driver's committed state agrees at each service return, and whether
        // the writes that service emitted agree.
        S2AudioOracleComparator.Report stateOnly =
                compare(reference, capture, false);
        System.out.println("MEASUREMENT_ONLY s2-driver-state-w10150-12400 "
                + "state only: " + stateOnly.describe());
        assertEquals(S2AudioOracleComparator.Kind.MATCH, report.kind(),
                report.describe());
        assertEquals(2198, report.comparedTicks(), report.describe());
        assertEquals(S2AudioOracleComparator.Kind.MATCH, stateOnly.kind(),
                stateOnly.describe());
        assertEquals(2198, stateOnly.comparedTicks(), stateOnly.describe());
    }

    /**
     * The comparison must be able to fail. A single corrupted RAM byte moves
     * the verdict; without this, a span that never compared would read exactly
     * like a span that agreed.
     */
    @Test
    void aCorruptedReferenceByteBreaksTheComparison() throws Exception {
        S2DriverStateReference.Result reference = S2DriverStateReference.read();
        List<S2AudioOracleComparator.ReferenceTick> clean = referenceTicks(reference);
        List<S2OracleEngineCapture.EngineTick> engine = engineFromReference(reference);
        assertEquals(S2AudioOracleComparator.Kind.MATCH,
                S2AudioOracleComparator.compareWithEngine(clean, engine).kind(),
                "the reference must agree with itself before the break is meaningful");

        List<S2AudioOracleComparator.ReferenceTick> corrupted =
                new ArrayList<>(clean);
        S2AudioOracleComparator.ReferenceTick victim = corrupted.get(10);
        byte[] state = victim.state();
        int tempo = 0x1b80 + 2;
        state[tempo] = (byte) (state[tempo] ^ 0xff);
        corrupted.set(10, new S2AudioOracleComparator.ReferenceTick(
                victim.ordinal(), victim.row(), state, victim.writes()));

        S2AudioOracleComparator.Report report =
                S2AudioOracleComparator.compareWithEngine(corrupted, engine);
        assertEquals(S2AudioOracleComparator.Kind.DIVERGENCE, report.kind(),
                report.describe());
        assertEquals(10, report.firstDivergenceTick(), report.describe());
    }

    /**
     * The frame field is provenance. Perturbing every frame value in the
     * reference must not move any compared value.
     */
    @Test
    void perturbingEveryFrameFieldChangesNothingCompared() throws Exception {
        StringBuilder perturbed = new StringBuilder();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                S2DriverStateReference.open(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                perturbed.append(line.replaceAll("\"frame\":\\d+", "\"frame\":999999"))
                        .append('\n');
            }
        }
        S2DriverStateReference.Result original = S2DriverStateReference.read();
        S2DriverStateReference.Result shifted = S2DriverStateReference.read(
                new ByteArrayInputStream(
                        perturbed.toString().getBytes(StandardCharsets.UTF_8)), true);

        assertEquals(original.ticks().size(), shifted.ticks().size());
        for (int index = 0; index < original.ticks().size(); index++) {
            S2DriverStateReference.Tick before = original.ticks().get(index);
            S2DriverStateReference.Tick after = shifted.ticks().get(index);
            assertNotEquals(before.frame(), after.frame(),
                    "the perturbation must actually change the frame field");
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    before.state(), after.state());
            assertEquals(before.writes(), after.writes());
        }
        assertEquals(S2AudioOracleComparator.Kind.MATCH,
                S2AudioOracleComparator.compareWithEngine(
                        referenceTicks(shifted), engineFromReference(original)).kind(),
                "a reference whose frames are all wrong still compares identically");
    }

    /**
     * The DAC stream comparison must be able to fail. Flipping one sample byte
     * in the reference moves the verdict; without this, a stream that never
     * compared would read exactly like a stream that agreed.
     */
    @Test
    void aCorruptedDacSampleByteBreaksTheStreamComparison() throws Exception {
        S2DriverStateReference.Result reference = S2DriverStateReference.read();
        List<S2AudioOracleComparator.ReferenceTick> clean = referenceTicks(reference);
        assertEquals(S2DacStreamComparator.Kind.MATCH,
                S2DacStreamComparator.compare(
                        dacServices(clean, null), dacServices(clean, null)).kind(),
                "the reference's DAC stream must agree with itself first");

        int victimTick = -1;
        int victimWrite = -1;
        for (int tick = 0; tick < clean.size() && victimTick < 0; tick++) {
            List<S2OracleRawStream.ChipWrite> writes = clean.get(tick).writes();
            for (int index = 0; index < writes.size(); index++) {
                if (S2DacStreamComparator.isDacSampleByte(writes.get(index))) {
                    victimTick = tick;
                    victimWrite = index;
                    break;
                }
            }
        }
        assertTrue(victimTick >= 0, "the fixture must carry a DAC sample byte");

        List<S2AudioOracleComparator.ReferenceTick> corrupted =
                new ArrayList<>(clean);
        S2AudioOracleComparator.ReferenceTick victim = corrupted.get(victimTick);
        List<S2OracleRawStream.ChipWrite> writes =
                new ArrayList<>(victim.writes());
        S2OracleRawStream.ChipWrite original = writes.get(victimWrite);
        writes.set(victimWrite, new S2OracleRawStream.ChipWrite(
                original.ym(), original.port(), original.register(),
                original.value() ^ 0xff, original.serviceKind()));
        corrupted.set(victimTick, new S2AudioOracleComparator.ReferenceTick(
                victim.ordinal(), victim.row(), victim.state(), writes));

        S2DacStreamComparator.Report report = S2DacStreamComparator.compare(
                dacServices(corrupted, null), dacServices(clean, null));
        assertEquals(S2DacStreamComparator.Kind.BYTE_DIFFERENT, report.kind(),
                report.describe());
    }

    private static S2DacStreamComparator.Report compareDacStream(
            S2DriverStateReference.Result reference,
            S2RequestProjectionBk2TestBridge.Capture capture) {
        Aligned aligned = align(reference, capture, true);
        List<S2DacStreamComparator.Service> referenceServices =
                dacServices(aligned.reference(), aligned.romDacData());
        List<S2DacStreamComparator.Service> engineServices = new ArrayList<>();
        for (int index = 0; index < aligned.engine().size(); index++) {
            int[] dac = aligned.engineDacSampleAndLength().get(index);
            engineServices.add(new S2DacStreamComparator.Service(
                    aligned.engine().get(index).writes(), dac[0], dac[1]));
        }
        return S2DacStreamComparator.compare(referenceServices, engineServices);
    }

    /**
     * The reference's services with its own selector and that sample's decoded
     * length. {@code zUpdateDAC} stores {@code zCurDAC} already rebased by
     * {@code 81h} (s2.sounddriver.asm:505-518), so the note that indexes the
     * ROM's DAC table is the stored value plus {@code 81h}.
     */
    private static List<S2DacStreamComparator.Service> dacServices(
            List<S2AudioOracleComparator.ReferenceTick> ticks,
            DacData romDacData) {
        List<S2DacStreamComparator.Service> services = new ArrayList<>();
        for (S2AudioOracleComparator.ReferenceTick tick : ticks) {
            int selector = S2OracleDriverState.decode(tick.state())
                    .globals().curDac();
            services.add(new S2DacStreamComparator.Service(tick.writes(),
                    selector,
                    S2Bk2DriverOracleComparator.decodedLength(
                            romDacData, (selector + 0x81) & 0xff)));
        }
        return services;
    }

    /** The two sides aligned service for service, ready to compare. */
    private record Aligned(
            List<S2AudioOracleComparator.ReferenceTick> reference,
            List<S2OracleEngineCapture.EngineTick> engine,
            List<int[]> engineDacSampleAndLength,
            DacData romDacData) {
    }

    private static Aligned align(
            S2DriverStateReference.Result reference,
            S2RequestProjectionBk2TestBridge.Capture capture,
            boolean compareWrites) {
        List<S2RequestProjectionBk2TestBridge.DriverUpdateTick> ticks =
                capture.updateTicks();
        // The window opens on a music reload, so its leading services run with
        // no music loaded on either side and cannot be paired. Both anchors are
        // driver state, not a frame: the engine's first tick carrying exactly
        // one EHZ music sequencer, and the reference's first tick whose music
        // tracks are playing. Neither side reads the other's row.
        int anchor = 0;
        while (anchor < ticks.size()
                && !S2Bk2DriverOracleComparator.hasSingleEhzMusicSequencer(
                        ticks.get(anchor).snapshot())) {
            anchor++;
        }
        List<S2AudioOracleComparator.ReferenceTick> allTicks = referenceTicks(reference);
        int referenceAnchor = 0;
        while (referenceAnchor < allTicks.size()
                && !hasPlayingMusicTrack(allTicks.get(referenceAnchor).state())) {
            referenceAnchor++;
        }
        List<S2OracleEngineCapture.EngineTick> engine = new ArrayList<>();
        List<int[]> engineDac = new ArrayList<>();
        DacData romDacData = null;
        int shared = Math.min(ticks.size() - anchor,
                allTicks.size() - referenceAnchor);
        for (int index = 0; index < shared; index++) {
            S2RequestProjectionBk2TestBridge.DriverUpdateTick tick =
                    ticks.get(anchor + index);
            engine.add(S2Bk2DriverOracleComparator.mapUpdateTick(
                    index, tick.snapshot(),
                    compareWrites ? tick.writes() : List.of()));
            engineDac.add(S2Bk2DriverOracleComparator.dacSampleAndLength(
                    tick.snapshot()));
            if (romDacData == null) {
                for (var entry : tick.snapshot().sequencers()) {
                    if (!entry.sfx() && entry.dacData() != null) {
                        romDacData = entry.dacData();
                        break;
                    }
                }
            }
        }
        List<S2AudioOracleComparator.ReferenceTick> referenceTicks =
                new ArrayList<>();
        for (int index = 0; index < shared; index++) {
            S2AudioOracleComparator.ReferenceTick tick =
                    allTicks.get(referenceAnchor + index);
            referenceTicks.add(new S2AudioOracleComparator.ReferenceTick(index,
                    tick.row(), tick.state(),
                    compareWrites ? tick.writes() : List.of()));
        }
        if (compareWrites) {
            StringBuilder pairing = new StringBuilder();
            for (int index = 0; index < Math.min(5, shared); index++) {
                pairing.append(' ').append(referenceTicks.get(index).row())
                        .append('/').append(ticks.get(anchor + index).row());
            }
            System.out.println("MEASUREMENT_ONLY s2-driver-state-w10150-12400"
                    + " first reference/engine rows:" + pairing);
            System.out.println("MEASUREMENT_ONLY s2-driver-state-w10150-12400"
                    + " reference ticks=" + reference.ticks().size()
                    + " engine ticks=" + ticks.size()
                    + " anchored at engine tick " + anchor
                    + " and reference tick " + referenceAnchor
                    + " comparing " + shared);
        }
        return new Aligned(referenceTicks, engine, engineDac, romDacData);
    }

    private static S2AudioOracleComparator.Report compare(
            S2DriverStateReference.Result reference,
            S2RequestProjectionBk2TestBridge.Capture capture,
            boolean compareWrites) {
        Aligned aligned = align(reference, capture, compareWrites);
        return S2AudioOracleComparator.compareWithEngine(
                aligned.reference(), aligned.engine());
    }


    /**
     * True when the reference's decoded music tracks show at least one track
     * playing, the driver-state counterpart of the engine carrying an EHZ music
     * sequencer. It reads driver RAM, never a frame.
     */
    private static boolean hasPlayingMusicTrack(byte[] state) {
        for (S2OracleDriverState.TrackState track
                : S2OracleDriverState.decode(state).musicTracks()) {
            if ((track.playbackControl() & 0x80) != 0) {
                return true;
            }
        }
        return false;
    }

    private static List<S2AudioOracleComparator.ReferenceTick> referenceTicks(
            S2DriverStateReference.Result reference) {
        List<S2AudioOracleComparator.ReferenceTick> ticks = new ArrayList<>();
        for (S2DriverStateReference.Tick tick : reference.ticks()) {
            ticks.add(new S2AudioOracleComparator.ReferenceTick(tick.index(),
                    tick.frame(), S2DriverStateReference.rebase(tick.state()),
                    tick.writes()));
        }
        return ticks;
    }

    /**
     * The reference decoded back into engine-shaped ticks. It is the identity
     * the comparison must report as a match, and the control the corruption
     * test breaks.
     */
    private static List<S2OracleEngineCapture.EngineTick> engineFromReference(
            S2DriverStateReference.Result reference) {
        List<S2OracleEngineCapture.EngineTick> engine = new ArrayList<>();
        for (S2DriverStateReference.Tick tick : reference.ticks()) {
            S2OracleDriverState state = S2OracleDriverState.decode(
                    S2DriverStateReference.rebase(tick.state()));
            List<S2OracleComparison.MappedTrack> slots = new ArrayList<>();
            List<S2OracleDriverState.TrackState> tracks = state.musicTracks();
            for (int slot = 0; slot < tracks.size(); slot++) {
                String name = S2OracleDriverState.MUSIC_SLOTS.get(slot);
                slots.add(S2OracleComparison.MappedTrack.fromReference(
                        tracks.get(slot), name.startsWith("PSG"), slot == 0));
            }
            engine.add(new S2OracleEngineCapture.EngineTick(tick.index(),
                    state.globals().currentTempo(), state.globals().tempoTimeout(),
                    slots, tick.writes()));
        }
        return engine;
    }
}
