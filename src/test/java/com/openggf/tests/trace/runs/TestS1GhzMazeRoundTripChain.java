package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator.AdmitDestination;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator.BeginTerminalTail;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator.CloseSegment;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator.CompleteRun;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator.EnterTransitionGap;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chain integration test for the committed {@code s1-ghz-maze-roundtrip} run
 * (3 segments: ghz1 -> ss -> ghz2, a {@code giant_ring} entry and
 * {@code stage_exit} return). Drives ONE continuous {@code GameLoop} via the
 * shared {@link AbstractRunChainTest} base and asserts the S1 special-stage
 * return-boundary shape: a NEXT-ACT advance (no positional restore) plus the
 * emerald-count increment.
 *
 * <h2>History: the {@code run_tail} dynamic-art edge</h2>
 *
 * <p>This test is GREEN. The terminal-tail residual described below was closed
 * by excusing {@code movie_logical_frame} — and only that field — inside spans
 * the manifest itself declares unrepresented and unclosed; the tail's work
 * identity, order and ledger are still compared.
 *
 * <p>{@code dynamicArtGapJournal.verifyTerminal} compares the run's LAST
 * dynamic-art gap edge against {@code run_manifest.json}'s
 * {@code dynamic_art_gap_transitions} tail entry. The recorded tail edge is
 * {@code edge_ordinal 4698/4699}, {@code transfer_id 2349},
 * {@code movie_logical_frame 9071}. Ordinals, transfer ids and both ledger
 * fingerprints now match; only {@code movie_logical_frame} still diverges
 * (engine 9035, delta 36).
 *
 * <p>Two earlier theories are REFUTED and must not be reopened: the
 * row-stamping deficit ({@code stateMovieLogicalRow}), and the returning
 * level's load sitting on the wrong side of the results bridge. A third — a
 * {@code ghz1} rolling-animation divergence at local frame 2083 — was real and
 * has been fixed (the landing at 0x822 is an angled-CEILING landing routed
 * through {@code CollisionSystem.doCeilingCollision} ->
 * {@code resetWallCeilingLandingState}, so the walk animation was never set).
 *
 * <p>The former {@code +12} ordinal / {@code +6} transfer skew that fix left
 * behind resolved to a single missing transfer at the run's FIRST level load.
 * A fresh playable's art is established through
 * {@code DynamicArtLifecycleService.primePlayerDplc}, which deliberately
 * publishes no edge because a segment-scoped replay starts at
 * {@code Level_MainLoop} and never owns that transfer. A run's movie DOES span
 * that load: the recorder's transfer 0 is exactly it — {@code mapping_frame 1},
 * {@code submission_origin run_gap}, {@code movie_logical_frame 748}, i.e. the
 * {@code ghz1} offset 774 minus the ROM-counted pre-main-loop tail of 26
 * ({@code Level_Delay} 4 + {@code PalFadeIn_Alt} 22,
 * docs/s1disasm/sonic.asm:2956-2969). Priming now stages that bank and the run
 * publishes it before opening its first segment, which advances the counters by
 * the one transfer / two ordinals the whole run was short.
 *
 * <p>The remaining {@code -36} movie rows are the un-modelled S1 level-load
 * span. Instrumenting the terminal tail gives the engine's phase lengths:
 * {@code SPECIAL_STAGE_RESULTS} 22 rows (8861-8882), {@code TITLE_CARD} 151
 * rows (8883-9033), then {@code LEVEL} to the movie's last row 9092. The ROM's
 * stamp of 9071 implies its first main-loop row is 9097 — BEYOND the movie's
 * end — so on hardware the level was still inside its pre-main-loop load when
 * the recording stopped. The engine spends ZERO rows on {@code ClearScreen} /
 * {@code LevelDataLoad} / {@code LoadTilesFromStart} / {@code ObjPosLoad} /
 * {@code NemDec}; only the trailing {@code Level_Delay} 4 +
 * {@code PalFadeIn_Alt} 22 are modelled. That is the level-load-span strand of
 * docs/architecture/plans/trace/2026-08-06-trace-validation-roadmap.md (section
 * 4), whose stated prerequisite is a RECORDED level-load span segment plus an
 * engine-counted load model. It cannot be closed from frame-granularity ROM
 * control flow — the span's length is cycle cost — and any constant fitted to
 * this fixture's 36 would desync the first different recording.
 *
 * <h2>Why the inter-segment gaps carry no dynamic-art edge</h2>
 *
 * <p>Measured from the committed fixture, the run's dynamic-art edge ordinals
 * are exactly contiguous with nothing missing: {@code run_manifest.json}
 * ordinals 0-1 (movie row 748, the pre-first-segment load), {@code ghz1}
 * 2-3041, {@code ss} 3042-4665, {@code ghz2} 4666-4697, then manifest ordinals
 * 4698-4699 (movie row 9071, the terminal tail). Neither inter-segment slice
 * contains an edge, and none is missing from the recording.
 *
 * <p>That is structural, not accidental. The segments tile the movie almost
 * exhaustively -- {@code ghz1} covers rows 774-4955, {@code ss} 4957-8047,
 * {@code ghz2} 8049-8860 -- so each inter-segment gap slice, which
 * {@code TraceRunDynamicArtGapComparator.gapSlice} defines as
 * {@code [sourceOffset + traceFrameCount, destinationOffset)}, is exactly ONE
 * movie row wide. A dynamic-art transfer spans a submitted edge and a completed
 * edge on separate rows, so a one-row slice cannot hold a transfer at all. The
 * returning level's first player DPLC is consequently published on the
 * destination segment's own row 0 (recorded {@code ghz2} frame 0, ordinal 4666,
 * {@code submission_origin: segment}), not in the gap.
 *
 * <p>The boundary assertions below therefore take their expectation from the
 * manifest slice rather than asserting that the journal must GROW. The former
 * growth assertions were unsatisfiable by this recording -- and would have been
 * contradicted by the shared gap comparator, which compares the same slice for
 * equality -- so they were corrected, not removed.
 *
 * <p>Note the level segments' {@code LiveTraceComparator} does not compare
 * {@code dynamic_art.edges} (only {@code DynamicArtSpecialStageComparator}
 * does), which is why a transfer-stream divergence surfaces only here.
 */
@RequiresRom(SonicGame.SONIC_1)
class TestS1GhzMazeRoundTripChain extends AbstractRunChainTest {

    private static final Path DEFAULT_RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s1", "runs", "s1-ghz-maze-roundtrip");
    private static final String EXTERNAL_RUN_DIR_PROPERTY = "openggf.trace.s1.run.dir";

    private Path activeRunDir;

    @Test
    void ghzMazeSpecialStageReturnPresentationBridge() throws Exception {
        auditHeadlessPayloadReleaseAtBoundaries();
        assertChainReplayThroughSegmentRow(
                DEFAULT_RUN_DIR, 2, 812);
        assertHeadlessPayloadReleased();
    }

    @Test
    void ghzMazeRoundTrip() throws Exception {
        auditHeadlessPayloadReleaseAtBoundaries();
        String configuredRunDir = System.getProperty(EXTERNAL_RUN_DIR_PROPERTY);
        activeRunDir = configuredRunDir == null || configuredRunDir.isBlank()
                ? DEFAULT_RUN_DIR
                : Path.of(configuredRunDir).toAbsolutePath().normalize();
        DynamicArtGapJournalEvidence evidence = assertChainReplay(activeRunDir);
        assertHeadlessPayloadReleased();
        assertEquals(List.of(
                        AdmitDestination.class,
                        CloseSegment.class, EnterTransitionGap.class,
                        AdmitDestination.class,
                        CloseSegment.class, EnterTransitionGap.class,
                        AdmitDestination.class,
                        CloseSegment.class, BeginTerminalTail.class,
                        CompleteRun.class),
                evidence.coordinatorActions().stream()
                        .map(Object::getClass).toList(),
                "the real headless chain must follow the shared coordinator transcript");
        DynamicArtStructuralGapEvidence returnGap =
                evidence.structuralGap("ss", "ghz2");
        TraceRunManifest manifest = TraceRunManifest.load(
                activeRunDir.resolve("run_manifest.json"));
        // The boundary's expectation is whatever the RECORDING carries for that
        // movie-row slice -- never a hardcoded direction of growth. In this run
        // the slice is empty (see the class comment), so this pins "the engine
        // must not invent a gap edge here"; in a run whose inter-segment slice
        // does carry edges it pins their ordinals and their containment.
        List<Long> recordedOrdinals = recordedGapEdgeOrdinals(manifest, 1);
        assertEquals(recordedOrdinals,
                returnGap.transitionsAddedAcrossBoundary().stream()
                        .map(transition -> transition.edge().edgeOrdinal())
                        .toList(),
                "the real S1 ss -> ghz2 structural gap must append exactly the "
                        + "dynamic-art edges run_manifest.json records for that "
                        + "movie-row slice");
        assertTrue(returnGap.transitionsAddedAcrossBoundary().stream()
                        .map(transition -> transition.edge())
                        .allMatch(edge -> edge.movieLogicalFrame()
                                >= returnGap.gapStartMovieLogicalFrame()
                                && edge.movieLogicalFrame()
                                <= returnGap.nextSegmentArmMovieLogicalFrame()),
                "every production art edge the real S1 named-run boundary adds "
                        + "must be stamped inside its structural gap");
        // The journal is append-only, so a boundary may leave it unchanged but
        // must never drop an edge or rewind the ordinal.
        assertTrue(returnGap.transitionCountAfterNextArm()
                        >= returnGap.transitionCountAtGapStart()
                        && returnGap.transitionCountAfterNextArm()
                        >= evidence.transitionCountAfterFirstArm(),
                "the real S1 ss -> ghz2 boundary must not shrink the gap journal");
        assertTrue(returnGap.lastEdgeOrdinalAfterNextArm()
                        >= returnGap.lastEdgeOrdinalAtGapStart()
                        && returnGap.lastEdgeOrdinalAfterNextArm()
                        >= evidence.lastEdgeOrdinalAfterFirstArm(),
                "the real S1 ss -> ghz2 boundary must not rewind the edge ordinal");
        // First-arm bootstrap is the run's pre-first-segment load, which this
        // recording carries as the manifest's transfer-0 submitted/completed
        // pair at movie row 748.
        List<Long> headOrdinals = recordedHeadEdgeOrdinals(manifest);
        assertEquals(headOrdinals.size(),
                evidence.transitionCountAfterFirstArm(),
                "first-arm bootstrap must reproduce the manifest's "
                        + "pre-first-segment gap edges");
        assertEquals(headOrdinals.isEmpty() ? -1L : headOrdinals.getLast(),
                evidence.lastEdgeOrdinalAfterFirstArm(),
                "first-arm bootstrap must end on the manifest's last "
                        + "pre-first-segment edge ordinal");
    }

    /**
     * Edge ordinals {@code run_manifest.json} records between the end of
     * segment {@code sourceIndex} and the start of the next segment -- the same
     * slice {@code TraceRunDynamicArtGapComparator} compares against.
     */
    private static List<Long> recordedGapEdgeOrdinals(
            TraceRunManifest manifest, int sourceIndex) {
        TraceRunManifest.Segment source = manifest.segments().get(sourceIndex);
        TraceRunManifest.Segment destination =
                manifest.segments().get(sourceIndex + 1);
        int sourceEnd = source.bk2FrameOffset() + source.traceFrameCount();
        return manifest.dynamicArtGapTransitions().stream()
                .map(DynamicArtTransfer.GapTransition::dynamicArtGapEdge)
                .filter(edge -> edge.movieLogicalFrame() >= sourceEnd
                        && edge.movieLogicalFrame()
                        < destination.bk2FrameOffset())
                .map(DynamicArtTransfer.GapEdge::edgeOrdinal)
                .toList();
    }

    private static List<Long> recordedHeadEdgeOrdinals(
            TraceRunManifest manifest) {
        int firstSegmentStart = manifest.segments().getFirst().bk2FrameOffset();
        return manifest.dynamicArtGapTransitions().stream()
                .map(DynamicArtTransfer.GapTransition::dynamicArtGapEdge)
                .filter(edge -> edge.movieLogicalFrame() < firstSegmentStart)
                .map(DynamicArtTransfer.GapEdge::edgeOrdinal)
                .toList();
    }

}
