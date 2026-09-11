package com.openggf.tools.audio.parity.s2;

import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The S2 driver oracle's comparison machinery, including the deliberate
 * break-it vectors: a comparison that never ran looks identical to a green
 * one, so both a corrupted reference byte and a corrupted engine write must
 * visibly move the report before any result is trusted.
 */
class TestS2AudioOracleComparator {

    @Test
    void recoversUpdateTicksAcrossTheSongLoadStall() throws Exception {
        List<S2AudioOracleComparator.ReferenceTick> ticks = anchorTicks();
        // The Saxman EHZ load masks interrupts across rows 10195-10200 and the
        // first update begins in row 10201 but does not complete until row
        // 10202. The recovered tick must use the completion-frame RAM image,
        // then resume with the next complete update in row 10203.
        assertEquals(10_202, ticks.get(0).row());
        assertEquals(10_203, ticks.get(1).row());
        assertEquals(10_204, ticks.get(2).row());
        S2OracleDriverState anchor = S2OracleDriverState.decode(ticks.get(0).state());
        assertEquals(S2OracleSchema.ANCHOR_ROM_MUSIC_ID, anchor.globals().curSong());
        assertEquals(0x9e, anchor.globals().currentTempo());
        assertEquals(0x1428, anchor.musicTracks().get(2).dataPointer());
        assertTrue(ticks.get(0).writes().stream().allMatch(write ->
                write.serviceKind()
                        == S2OracleRawStream.ChipWrite.SERVICE_UPDATE_MUSIC));
    }

    @Test
    void firstUpdatesFollowS2PsgAndFmWriteSemantics() {
        File rom = RomTestUtils.ensureSonic2RomAvailable();
        assumeTrue(rom != null && rom.isFile(), "S2 REV01 ROM unavailable");

        List<S2OracleEngineCapture.EngineTick> ticks = S2OracleEngineCapture.capture(
                rom.toPath(), 2, 2);
        S2OracleEngineCapture.EngineTick tick = ticks.get(0);

        // zPSGUpdateTrack calls zPSGDoVolFX after parsing a new note, including
        // a rest. zPSGDoVolFX advances VolFlutter before the resting bit
        // suppresses the chip write (sd:1123-1131, 1276-1312).
        assertEquals(1, tick.musicSlots().get(7).volFlutter());
        assertEquals(1, tick.musicSlots().get(8).volFlutter());
        assertEquals(1, tick.musicSlots().get(9).volFlutter());
        assertEquals(4, tick.writes().stream()
                .filter(S2OracleRawStream.ChipWrite::ym)
                .filter(write -> write.register() >= 0xb4 && write.register() <= 0xb6)
                .count(), "zFMPrepareNote must not repeat the voice's pan write");
        assertEquals(2, tick.writes().stream()
                .filter(S2OracleRawStream.ChipWrite::ym)
                .filter(write -> write.port() == 1 && write.register() == 0x40)
                .count(), "E6 must rewrite unchanged non-carrier TLs too");
        assertTrue(ticks.get(1).writes().isEmpty(),
                "resting PSG envelopes advance without writing attenuation");
    }

    @Test
    void explicitDriverRequestsResolveAndAdmitTheFirstRingBeforeTheTargetUpdate() {
        File rom = RomTestUtils.ensureSonic2RomAvailable();
        assumeTrue(rom != null && rom.isFile(), "S2 REV01 ROM unavailable");

        List<S2OracleEngineCapture.EngineTick> musicOnly =
                S2OracleEngineCapture.capture(rom.toPath(), 2, 2);
        List<S2OracleEngineCapture.EngineTick> withRing =
                S2OracleEngineCapture.capture(rom.toPath(), 2, 2,
                        List.of(new S2OracleEngineCapture.DriverRequest(
                                0, 0xb5)));
        List<S2OracleEngineCapture.EngineTick> withExplicitLeftRing =
                S2OracleEngineCapture.capture(rom.toPath(), 2, 2,
                        List.of(new S2OracleEngineCapture.DriverRequest(
                                0, 0xce)));

        assertNotEquals(musicOnly.get(0).writes(), withRing.get(0).writes(),
                "a source-owned SFX request must affect its target driver update");
        assertEquals(withExplicitLeftRing.get(0).writes(), withRing.get(0).writes(),
                "zRingSpeaker=0 must resolve the first raw B5h request to CEh");
    }

    @Test
    void corruptedReferenceByteMovesTheFirstDivergence() throws Exception {
        List<S2AudioOracleComparator.ReferenceTick> ticks = anchorTicks();
        // A self-comparison built from the reference's own decoded ticks is the
        // clean baseline: it must MATCH, proving the comparator can go green.
        List<S2OracleEngineCapture.EngineTick> mirrored = mirror(ticks);
        S2AudioOracleComparator.Report clean =
                S2AudioOracleComparator.compareWithEngine(ticks, mirrored);
        assertEquals(S2AudioOracleComparator.Kind.MATCH, clean.kind(), clean.describe());

        // Corrupt one byte of one reference tick's driver state (EHZ FM1
        // DurationTimeout at tick 40) in a copy; the comparator must report a
        // divergence at exactly that tick and field.
        int corruptTick = 40;
        List<S2AudioOracleComparator.ReferenceTick> corrupted = new ArrayList<>(ticks);
        byte[] state = corrupted.get(corruptTick).state();
        state[0x1b98 + 0x2a + 0x0b] ^= 0x55;
        corrupted.set(corruptTick, new S2AudioOracleComparator.ReferenceTick(
                corruptTick, ticks.get(corruptTick).row(), state,
                ticks.get(corruptTick).writes()));
        S2AudioOracleComparator.Report report =
                S2AudioOracleComparator.compareWithEngine(corrupted, mirrored);
        assertEquals(S2AudioOracleComparator.Kind.DIVERGENCE, report.kind());
        assertEquals(corruptTick, report.firstDivergenceTick(), report.describe());
        assertEquals("track.FM1.durationTimeout", report.firstDivergenceField(),
                report.describe());
        assertEquals(1, report.divergentTicks());
    }

    @Test
    void corruptedEngineWriteIsReported() throws Exception {
        List<S2AudioOracleComparator.ReferenceTick> ticks = anchorTicks();
        List<S2OracleEngineCapture.EngineTick> mirrored = mirror(ticks);

        // Corrupt one engine-side chip write value at the first later tick
        // that carries sequencer writes (delay frames without envelope or
        // modulation writes can be empty).
        int corruptTick = -1;
        for (int candidate = 20; candidate < mirrored.size(); candidate++) {
            if (!mirrored.get(candidate).writes().isEmpty()) {
                corruptTick = candidate;
                break;
            }
        }
        assumeTrue(corruptTick >= 0, "no tick carries sequencer writes");
        S2OracleEngineCapture.EngineTick tick = mirrored.get(corruptTick);
        List<S2OracleRawStream.ChipWrite> writes = new ArrayList<>(tick.writes());
        S2OracleRawStream.ChipWrite original = writes.get(0);
        writes.set(0, new S2OracleRawStream.ChipWrite(original.ym(), original.port(),
                original.register(), original.value() ^ 0x40, original.serviceKind()));
        mirrored.set(corruptTick, new S2OracleEngineCapture.EngineTick(tick.ordinal(),
                tick.currentTempo(), tick.tempoTimeout(), tick.musicSlots(), writes));

        S2AudioOracleComparator.Report report =
                S2AudioOracleComparator.compareWithEngine(ticks, mirrored);
        assertEquals(S2AudioOracleComparator.Kind.DIVERGENCE, report.kind());
        assertEquals(corruptTick, report.firstDivergenceTick(), report.describe());
        assertEquals("writes[0]", report.firstDivergenceField(), report.describe());
    }

    @Test
    void engineComparisonRunsAndReportsDeterministically() throws Exception {
        File rom = RomTestUtils.ensureSonic2RomAvailable();
        assumeTrue(rom != null && rom.isFile(), "S2 REV01 ROM unavailable");
        S2AudioOracleComparator.Report first = S2AudioOracleComparator.compare(
                TestS2AudioOracleFixture.fixturePath(), rom.toPath(), true);
        assertNotEquals(S2AudioOracleComparator.Kind.INVALID, first.kind(),
                first.describe());
        S2AudioOracleComparator.Report second = S2AudioOracleComparator.compare(
                TestS2AudioOracleFixture.fixturePath(), rom.toPath(), true);
        assertEquals(first, second, "engine capture must be deterministic");
        // The current engine is expected to diverge (delay-frame cadence, GA
        // 1.2 #2); a MATCH here would itself be news worth investigating. The
        // pinned expectation is only that the report is well-formed.
        if (first.kind() == S2AudioOracleComparator.Kind.DIVERGENCE) {
            assertTrue(first.firstDivergenceTick() >= 0, first.describe());
            assertTrue(first.firstDivergenceRow() >= S2OracleSchema.ANCHOR_ROW,
                    first.describe());
        }
    }

    /** Reference ticks re-expressed as engine ticks — the identity capture. */
    private static List<S2OracleEngineCapture.EngineTick> mirror(
            List<S2AudioOracleComparator.ReferenceTick> ticks) {
        List<S2OracleEngineCapture.EngineTick> mirrored = new ArrayList<>(ticks.size());
        for (S2AudioOracleComparator.ReferenceTick tick : ticks) {
            S2OracleDriverState state = S2OracleDriverState.decode(tick.state());
            List<S2OracleComparison.MappedTrack> slots = new ArrayList<>();
            for (int slot = 0; slot < state.musicTracks().size(); slot++) {
                String name = S2OracleDriverState.MUSIC_SLOTS.get(slot);
                slots.add(S2OracleComparison.MappedTrack.fromReference(
                        state.musicTracks().get(slot), name.startsWith("PSG"), slot == 0));
            }
            mirrored.add(new S2OracleEngineCapture.EngineTick(tick.ordinal(),
                    state.globals().currentTempo(),
                    state.globals().tempoTimeout(),
                    slots,
                    tick.writes()));
        }
        return mirrored;
    }

    private static List<S2AudioOracleComparator.ReferenceTick> anchorTicks()
            throws Exception {
        return S2AudioOracleComparator.buildTicks(load(), S2OracleSchema.ANCHOR_ROW);
    }

    private static List<S2OracleRawStream.Frame> load() throws Exception {
        Path fixture = TestS2AudioOracleFixture.fixturePath();
        List<S2OracleRawStream.Frame> frames = new ArrayList<>();
        S2OracleRawStream.scan(fixture, new S2OracleRawStream.Sink() {
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
}
