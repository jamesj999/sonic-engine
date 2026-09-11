package com.openggf.game.resources;

import com.openggf.game.GameId;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.TileLoadRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Session-owned production lifecycle for player DPLC decisions.
 *
 * <p>The service derives facts only from runtime owner calls. It has no trace
 * parser, expected-event, or recorded-authority input.
 */
public final class DynamicArtLifecycleService
        implements DynamicArtDiagnosticsProvider,
        RewindSnapshottable<DynamicArtLifecycleService.RewindState> {

    /** Graceful close cannot publish buffered edges without an observed row. */
    public static final class UnpublishedComparisonRowException
            extends IllegalStateException {
        public UnpublishedComparisonRowException() {
            super("cannot forward dynamic-art edges without a production row");
        }
    }

    public static final String REWIND_KEY = "dynamic-art-diagnostics";
    private static final Set<String> OWNERS = Set.of(
            "sonic", "tails", "tails-tails",
            "ss-sonic", "ss-tails", "ss-tails-tails");
    private static final Map<GameId, Map<String, ProductionArtProfile>>
            PLAYER_PROFILES = Map.of(
                    GameId.S1, Map.of(
                            "sonic", new ProductionArtProfile(
                                    Sonic1Constants.ART_UNC_SONIC_ADDR,
                                    0xF000, 0xC800, 0x2E0)),
                    GameId.S2, Map.of(
                            "sonic", new ProductionArtProfile(
                                    Sonic2Constants.ART_UNC_SONIC_ADDR,
                                    0xF000, -1, 0),
                            "tails", new ProductionArtProfile(
                                    Sonic2Constants.ART_UNC_TAILS_ADDR,
                                    0xF400, -1, 0),
                            "tails-tails", new ProductionArtProfile(
                                    Sonic2Constants.ART_UNC_TAILS_ADDR,
                                    0xF600, -1, 0)));

    private final Map<String, Integer> lastMappingFrames = new HashMap<>();
    private final LinkedHashMap<Long, Descriptor> ledger = new LinkedHashMap<>();
    private final List<BufferedEdge> bufferedEdges = new ArrayList<>();
    private final List<Long> pendingS2TransferIds = new ArrayList<>();
    private final List<DynamicArtGapTransition> gapTransitions = new ArrayList<>();
    private Preparation s1Preparation;
    /**
     * A player staging taken while an earlier one is still held for the
     * level's pre-main-loop tail.
     *
     * <p>{@code Sonic_LoadGfx} decodes at most one frame per call and hands it
     * to the V-blank DMA via {@code f_sonframechg}
     * (docs/s1disasm/_incObj/01 Sonic.asm:2394-2409), so a staging is always
     * separated from the next one by a V-int. Around a level load the ROM
     * makes two such calls with the whole {@code Level_Delay} /
     * {@code PalFadeIn_Alt} tail between them: the {@code Level_LoadObj}
     * {@code ExecuteObjects} pass (docs/s1disasm/sonic.asm:2895-2897), served
     * by the tail's first V-int (docs/s1disasm/sonic.asm:2956-2961), and then
     * the first {@code Level_MainLoop} pass (docs/s1disasm/sonic.asm:2998),
     * served by the V-int after it. The engine does not spend the tail's rows,
     * so both stagings arrive on one engine row; letting the second replace
     * the first would drop a DMA the ROM performs. Holding it here keeps both
     * transfers, each on its own row.
     */
    private Preparation s1PreparationAfterPreMainLoopTail;
    /**
     * Level-load player bank staged by a fresh playable's priming, held
     * until a run whose movie spans the load publishes it.
     */
    private Preparation initialLoadPreparation;
    /**
     * Whether the level-entry load that creates the playables is still
     * running, so a playable art decision taken now belongs to the row that
     * load finishes on rather than to the row the engine happened to take it.
     *
     * <p>The ROM does not own playables during its level-entry load at all:
     * {@code Level:} reaches {@code InitPlayers} only after
     * {@code LoadZoneTiles}, {@code loadZoneBlockMaps},
     * {@code LoadAnimatedBlocks}, {@code DrawInitialBG},
     * {@code ConvertCollisionArray}, {@code LoadCollisionIndexes} and
     * {@code WaterEffects} (docs/s2disasm/s2.asm:4938-4946), so the players'
     * art is submitted at the END of that load, not at its start. The engine
     * performs the same load with no frame cost, so its playables exist -- and
     * take their first art decision -- while the ROM was still loading. Held
     * decisions stage here and are released by whoever knows the load has
     * finished; nothing arms this in production, where the hold is never set
     * and the path below is exactly as it was.
     */
    private boolean levelEntryPlayerArtHeld;
    private final List<Preparation> heldLevelEntryPlayerArt = new ArrayList<>();
    private List<Long> publishedOutstanding = List.of();
    private DynamicArtDiagnosticsSnapshot latest =
            DynamicArtDiagnosticsSnapshot.empty();
    private long deliverySerial;
    private long segmentGeneration;
    private boolean runActive;
    private boolean comparisonSegmentOpen;
    private boolean comparisonSegmentReserved;
    private long nextTransferId;
    private long nextEdgeOrdinal;
    private int logicalFrame;
    private int nextLogicalEdgeIndex;
    private int nextPublicationFrame;
    private int movieLogicalFrame;
    private boolean movieLogicalFrameSuppliedExternally;
    /**
     * Rows the movie advanced without anyone announcing them.
     *
     * <p>A run advances the movie one row per driven frame, and a driver that
     * knows the row announces it ({@link #setMovieLogicalFrame}). Some spans
     * are driven with no announcement at all: a harness that pre-seeks the
     * shared cursor for an uncompared interior keeps re-announcing the same
     * frozen row for the whole interior and the transition gap after it, so
     * every gap edge is stamped with one stale row however many rows apart
     * they were emitted. Across such a span the engine's own production
     * iterations are the only evidence rows are passing, and one iteration is
     * one row.
     *
     * <p>Counting them makes a stale stamp recoverable: an edge whose stamp
     * was followed by {@code n} unannounced iterations is {@code n} rows
     * earlier than it says. Where rows are announced this count never moves
     * and the stamp is already right.
     */
    private int unannouncedRows;
    /** Last row announced, so a re-announced row is not a row advance. */
    private int lastAnnouncedMovieRow = -1;
    /** Whether a NEW row was announced since the last iteration closed. */
    private boolean newMovieRowAnnouncedSinceIteration;
    /**
     * Gap-ledger length, movie row and outstanding ledger as they stood the
     * instant the comparison segment closed — the state a transition gap
     * opens on.
     *
     * <p>A close can itself emit gap edges: the ROM mode-change boundary
     * re-stamps the iteration's whole buffered batch {@code run_gap} and emits
     * it here, because that iteration is no longer a row of the level. A
     * comparator that snapshots when the harness later announces the close
     * therefore starts counting past those edges and never compares them, and
     * their transfers have already left the ledger that resolves an edge's
     * submission origin. Recording the state at the close itself makes the
     * boundary batch the first edges of the gap it belongs to.
     *
     * <p>Recorded at both ends of a comparison segment, so a source that never
     * opened one — an uncompared interior segment — inherits the state of the
     * boundary that armed it rather than a stale earlier gap's.
     */
    private int gapOpeningTransitionCount;
    private int gapOpeningMovieLogicalFrame;
    private List<Descriptor> gapOpeningLedger = List.of();
    /**
     * Outstanding ledger as it stood before the currently buffered edge batch
     * began — the state the batch's own {@code before} membership was taken
     * against.
     *
     * <p>A batch accumulates across one production iteration and is flushed
     * whole, either onto a published row or, at the ROM mode-change boundary,
     * into the transition gap. In the boundary case the batch's edges are the
     * gap's first edges, so the ledger a comparator replays them onto has to
     * precede them; the live ledger at that moment already has them applied.
     */
    private List<Descriptor> ledgerBeforeBufferedBatch = List.of();
    /**
     * Counted V-int rows separating a staged pre-main-loop player transfer
     * from the level's first main-loop row, or {@code -1} when no staged
     * transfer is waiting on that tail.
     */
    private int preMainLoopTailRows = -1;
    /** Movie row the held transfer was staged on, i.e. the tail's lower bound. */
    private int preMainLoopHoldBoundaryRow;
    /**
     * Gap-edge numbering is per LOGICAL FRAME, not per gap. The recorder keys
     * its counter on the edge's frame
     * ("tools/tracechaser/bizhawk-headless/src/Recording/S1DynamicArtObserver.cs":247-252),
     * so two edges sharing a frame are 0 and 1 while a later frame in the same
     * gap restarts at 0.
     */
    private final Map<Integer, Integer> nextGapEdgeIndexByFrame =
            new HashMap<>();
    /**
     * Gap-ledger size observed at the start of the last COMPLETED production
     * iteration, and at the start of the one now running. Together they name
     * the gap edges a single completed iteration emitted, which is what
     * {@link #adoptGapResidentOpeningRow()} needs: admission is polled between
     * host steps, so the iteration that satisfied the destination's readiness
     * has already finished by the time the destination's comparison window
     * opens.
     */
    private int gapEdgesBeforeLastIteration;
    private int gapEdgesBeforeCurrentIteration;

    private record ProductionArtProfile(
            int romArtBase,
            int vramDestination,
            int stagingRamAddress,
            int stagingByteLength) {
    }

    public record ArtUpdate(
            boolean mappingChanged,
            long transferId,
            List<TileLoadRequest> tileRequests) {
        public ArtUpdate {
            tileRequests = List.copyOf(tileRequests);
        }

        public boolean submitted() {
            return transferId >= 0;
        }
    }

    /** Native S2 entry class for the semantic owner decision. */
    public enum DecisionKind {
        NORMAL_OBJECT,
        DIRECT_PART2
    }

    public record Descriptor(
            long transferId,
            String owner,
            int mappingFrame,
            String submissionOrigin,
            List<DynamicArtDiagnosticsSnapshot.Request> requests) {
        public Descriptor {
            requests = List.copyOf(requests);
        }
    }

    public record Preparation(
            String owner,
            int mappingFrame,
            List<DynamicArtDiagnosticsSnapshot.Request> requests) {
        public Preparation {
            requests = List.copyOf(requests);
        }
    }

    public record BufferedEdge(
            long edgeOrdinal,
            long transferId,
            String phase,
            String owner,
            int mappingFrame,
            int logicalFrame,
            int logicalEdgeIndex,
            List<DynamicArtDiagnosticsSnapshot.Request> requests,
            String submissionOrigin,
            List<Long> beforeOutstanding,
            List<Long> afterOutstanding) {
        public BufferedEdge {
            requests = List.copyOf(requests);
            submissionOrigin = submissionOrigin == null
                    ? "segment" : submissionOrigin;
            beforeOutstanding = List.copyOf(beforeOutstanding);
            afterOutstanding = List.copyOf(afterOutstanding);
        }
    }

    public record RewindState(
            Map<String, Integer> lastMappingFrames,
            List<Descriptor> ledger,
            List<BufferedEdge> bufferedEdges,
            List<Long> pendingS2TransferIds,
            List<DynamicArtGapTransition> gapTransitions,
            List<Long> publishedOutstanding,
            int latestFrame,
            List<DynamicArtDiagnosticsSnapshot.Edge> latestEdges,
            List<Long> latestOutstandingTransferIds,
            boolean latestPublished,
            Preparation s1Preparation,
            Preparation s1PreparationAfterPreMainLoopTail,
            Preparation initialLoadPreparation,
            boolean levelEntryPlayerArtHeld,
            List<Preparation> heldLevelEntryPlayerArt,
            boolean runActive,
            boolean comparisonSegmentOpen,
            boolean comparisonSegmentReserved,
            long nextTransferId,
            long nextEdgeOrdinal,
            int logicalFrame,
            int nextLogicalEdgeIndex,
            int nextPublicationFrame,
            int movieLogicalFrame,
            int preMainLoopTailRows,
            int nextGapEdgeIndex,
            int gapOpeningTransitionCount,
            int gapOpeningMovieLogicalFrame,
            int gapEdgesBeforeLastIteration,
            int gapEdgesBeforeCurrentIteration,
            int unannouncedRows,
            int lastAnnouncedMovieRow,
            boolean newMovieRowAnnouncedSinceIteration,
            List<Descriptor> gapOpeningLedger,
            List<Descriptor> ledgerBeforeBufferedBatch) {
        public RewindState {
            lastMappingFrames = Map.copyOf(lastMappingFrames);
            ledger = List.copyOf(ledger);
            bufferedEdges = List.copyOf(bufferedEdges);
            pendingS2TransferIds = List.copyOf(pendingS2TransferIds);
            gapTransitions = List.copyOf(gapTransitions);
            publishedOutstanding = List.copyOf(publishedOutstanding);
            latestEdges = List.copyOf(latestEdges);
            latestOutstandingTransferIds =
                    List.copyOf(latestOutstandingTransferIds);
            heldLevelEntryPlayerArt = List.copyOf(heldLevelEntryPlayerArt);
            gapOpeningLedger = List.copyOf(gapOpeningLedger);
            ledgerBeforeBufferedBatch =
                    List.copyOf(ledgerBeforeBufferedBatch);
        }
    }

    /** Starts a fresh buffered edge batch from the live ledger. */
    private void beginBufferedEdgeBatch() {
        bufferedEdges.clear();
        ledgerBeforeBufferedBatch = List.copyOf(ledger.values());
    }

    /** Records the state a transition gap opens on, at the segment's close. */
    private void recordGapOpeningState() {
        gapOpeningTransitionCount = gapTransitions.size();
        gapOpeningMovieLogicalFrame = movieLogicalFrame;
        gapOpeningLedger = bufferedEdges.isEmpty()
                ? List.copyOf(ledger.values())
                : ledgerBeforeBufferedBatch;
    }

    @Override
    public DynamicArtGapDiagnosticsSnapshot gapOpeningSnapshot() {
        return new DynamicArtGapDiagnosticsSnapshot(
                gapOpeningMovieLogicalFrame,
                unannouncedRows,
                gapTransitions.subList(0, Math.min(
                        gapOpeningTransitionCount, gapTransitions.size())),
                gapOpeningLedger.stream()
                        .map(descriptor ->
                                new DynamicArtGapDiagnosticsSnapshot.Descriptor(
                                        descriptor.transferId(),
                                        descriptor.owner(),
                                        descriptor.mappingFrame(),
                                        descriptor.submissionOrigin(),
                                        descriptor.requests()))
                        .toList());
    }

    @Override
    public DynamicArtGapDiagnosticsSnapshot gapSnapshot() {
        return new DynamicArtGapDiagnosticsSnapshot(
                movieLogicalFrame,
                unannouncedRows,
                gapTransitions,
                ledger.values().stream()
                        .map(descriptor ->
                                new DynamicArtGapDiagnosticsSnapshot.Descriptor(
                                        descriptor.transferId(),
                                        descriptor.owner(),
                                        descriptor.mappingFrame(),
                                        descriptor.submissionOrigin(),
                                        descriptor.requests()))
                        .toList());
    }

    public void beginRun() {
        if (runActive) {
            throw new IllegalStateException("dynamic-art run is already active");
        }
        resetState();
        runActive = true;
    }

    /**
     * Restarts an already-active run so the ledger holds only work the
     * session's own level load produced.
     *
     * <p>A replay session boots on top of whatever the host had already
     * loaded — the master title screen live, a throwaway engine-init level
     * load headless — and that load's player DPLC priming submits through the
     * same service. Nothing resets it, because
     * {@link com.openggf.game.session.GameplayModeContext#attachGameplayManagers}
     * only calls {@link #beginRun()} when no run is active, so the pre-boot
     * transfers keep their ids and every id the replay mints afterwards is
     * displaced by that count. The recorder's ledger starts at the movie, so
     * the replay's must start at the replay's own load: this is the
     * dynamic-art half of the same leakage
     * {@code TraceReplaySessionBootstrap.resetLevelSubsystemsForReplay}
     * already zeroes for the RNG seed and the level subsystems.
     *
     * <p>Reached only through
     * {@link com.openggf.game.session.GameplayModeContext#restartDynamicArtRunForFreshSession()}:
     * the session owns this lifecycle, so callers that must not hold
     * mutation authority over it (trace and ghost code) never take a
     * reference to this service. It takes no arguments and resets to
     * compile-time constants, so no caller can steer it.
     */
    public void restartRunForFreshSession() {
        resetState();
        runActive = true;
    }

    public void openComparisonSegment() {
        requireRunActive();
        if (comparisonSegmentOpen || comparisonSegmentReserved) {
            throw new IllegalStateException(
                    "dynamic-art comparison segment is already open or reserved");
        }
        validateComparisonSegmentActivation();
        recordGapOpeningState();
        comparisonSegmentOpen = true;
        initializeComparisonSegment();
    }

    /**
     * Allocates the unpublished generation observed immediately before an
     * externally managed row zero. Production remains gap-owned until the
     * coordinator services the opening V-blank and activates the reservation.
     */
    public void reserveComparisonSegment() {
        requireRunActive();
        if (comparisonSegmentOpen || comparisonSegmentReserved) {
            throw new IllegalStateException(
                    "dynamic-art comparison segment is already open or reserved");
        }
        if (!bufferedEdges.isEmpty()) {
            throw new IllegalStateException(
                    "cannot open dynamic-art segment with unpublished production work");
        }
        comparisonSegmentReserved = true;
        initializeComparisonSegment();
    }

    /** Activates a reserved window without changing its observed generation. */
    public void activateReservedComparisonSegment() {
        requireRunActive();
        if (!comparisonSegmentReserved || comparisonSegmentOpen) {
            throw new IllegalStateException(
                    "dynamic-art comparison segment is not reserved");
        }
        validateComparisonSegmentActivation();
        comparisonSegmentReserved = false;
        comparisonSegmentOpen = true;
    }

    /**
     * Advances a newly opened comparison window past destination rows already
     * produced while the run was still structurally in the preceding gap.
     * This moves only the segment-local publication/edge cursors: the shared
     * movie clock already advanced when those production rows ran and must not
     * be counted twice.
     */
    public void advanceComparisonCursor(int consumedRows) {
        requireComparisonSegmentOpen();
        if (consumedRows < 0) {
            throw new IllegalArgumentException("consumedRows must be nonnegative");
        }
        if (nextPublicationFrame != 0 || latest.published()
                || !bufferedEdges.isEmpty()) {
            throw new IllegalStateException(
                    "comparison cursor can advance only before its first publication");
        }
        logicalFrame = Math.addExact(logicalFrame, consumedRows);
        nextPublicationFrame = Math.addExact(
                nextPublicationFrame, consumedRows);
        nextLogicalEdgeIndex = 0;
    }

    /**
     * Adopts the one destination iteration that already ran while the run was
     * structurally still in the preceding transition gap, publishing it as the
     * opening segment's row zero.
     *
     * <p>This is the exact inverse of
     * {@link #endComparisonSegmentAtRomModeChange()}. There the ROM's mode
     * write happens inside {@code RunObjects}, so an iteration the engine had
     * treated as a segment row turns out to belong to the gap and its whole
     * edge batch is re-stamped {@code run_gap}. Here the destination's
     * readiness is only observable after the row it belongs to has run —
     * {@code TraceRunPlaybackCoordinator.destinationReady} requires
     * {@code observation.mode() == LEVEL}, which cannot be true until the row
     * executed, and production carries the same one-row drop through
     * {@code DestinationAdmissionReceipt.rowsConsumed()} — so an iteration the
     * engine had treated as a gap row turns out to be the segment's row zero
     * and its whole edge batch is re-stamped {@code segment}.
     *
     * <p>The recorder agrees on the boundary objectively: the gap's edge
     * ordinals end contiguously with the destination segment's row-zero
     * ordinal. Measured on {@code s2-ehz-halfpipe-roundtrip}'s
     * {@code ss_2 -> seg3_ehz1} gap, the sole edge this iteration emitted is
     * ordinal 22634 / transfer 11317 / submitted / sonic / mapping frame 15,
     * which is exactly and only what the fixture's {@code seg3_ehz1} row 0
     * carries.
     *
     * <p>Adopting rather than skipping is what makes row zero COMPARED: the
     * previous {@code advanceComparisonCursor(1)} left the row unpublished, so
     * its edges stayed gap-resident and the transfer they opened completed on
     * row 1 still carrying the {@code run_gap} stamp its gap-time submission
     * gave it. An iteration that emitted no art adopts nothing and publishes
     * an empty row zero, which is equally what the fixture records.
     */
    public void adoptGapResidentOpeningRow() {
        requireComparisonSegmentOpen();
        if (nextPublicationFrame != 0 || latest.published()
                || !bufferedEdges.isEmpty()) {
            throw new IllegalStateException(
                    "an opening row can be adopted only before the first publication");
        }
        int watermark = gapEdgesBeforeLastIteration;
        if (watermark < 0 || watermark > gapTransitions.size()) {
            throw new IllegalStateException(
                    "gap iteration watermark " + watermark
                            + " is outside the gap ledger of size "
                            + gapTransitions.size());
        }
        List<DynamicArtGapTransition> adopted = List.copyOf(
                gapTransitions.subList(watermark, gapTransitions.size()));
        gapTransitions.subList(watermark, gapTransitions.size()).clear();
        Set<Long> adoptedSubmissions = new java.util.LinkedHashSet<>();
        for (DynamicArtGapTransition transition : adopted) {
            DynamicArtGapTransition.GapEdge edge = transition.edge();
            nextGapEdgeIndexByFrame.computeIfPresent(
                    edge.movieLogicalFrame(), (frame, count) -> count - 1);
            if ("submitted".equals(edge.phase())) {
                adoptedSubmissions.add(edge.transferId());
                Descriptor descriptor = ledger.get(edge.transferId());
                if (descriptor != null) {
                    ledger.put(edge.transferId(), new Descriptor(
                            descriptor.transferId(), descriptor.owner(),
                            descriptor.mappingFrame(), "segment",
                            descriptor.requests()));
                }
            }
        }
        for (DynamicArtGapTransition transition : adopted) {
            DynamicArtGapTransition.GapEdge edge = transition.edge();
            // A completion whose submission stayed in the gap keeps the gap's
            // stamp: the origin follows where the transfer was SUBMITTED.
            String origin = adoptedSubmissions.contains(edge.transferId())
                    ? "segment" : "run_gap";
            bufferedEdges.add(new BufferedEdge(
                    edge.edgeOrdinal(), edge.transferId(), edge.phase(),
                    edge.owner(), edge.mappingFrame(), 0,
                    nextLogicalEdgeIndex++, edge.requests(), origin,
                    transition.beforeOutstandingTransferIds(),
                    transition.afterOutstandingTransferIds()));
        }
        publishRow(nextPublicationFrame++, false);
    }

    /** Cancels an unpublished reservation without publishing a synthetic row. */
    public void cancelReservedComparisonSegment() {
        requireRunActive();
        if (!comparisonSegmentReserved || comparisonSegmentOpen) {
            throw new IllegalStateException(
                    "dynamic-art comparison segment is not reserved");
        }
        comparisonSegmentReserved = false;
        publishedOutstanding = List.copyOf(ledger.keySet());
        latest = DynamicArtDiagnosticsSnapshot.unpublished(
                deliverySerial, segmentGeneration);
        logicalFrame = 0;
        nextLogicalEdgeIndex = 0;
        nextPublicationFrame = 0;
    }

    private void validateComparisonSegmentActivation() {
        if (!bufferedEdges.isEmpty()) {
            throw new IllegalStateException(
                    "cannot open dynamic-art segment with unpublished production work");
        }
        if (!ledger.isEmpty() && pendingS2TransferIds.isEmpty()) {
            throw new IllegalStateException(
                    "cannot open dynamic-art segment with pending production work");
        }
    }

    private void initializeComparisonSegment() {
        segmentGeneration++;
        // Gap edges mutate the ledger without buffering, so a window's first
        // batch has to start from the ledger the window opens on rather than
        // from whatever the previous window's last batch stood on.
        ledgerBeforeBufferedBatch = List.copyOf(ledger.values());
        publishedOutstanding = List.copyOf(ledger.keySet());
        latest = new DynamicArtDiagnosticsSnapshot(
                -1, List.of(), publishedOutstanding,
                deliverySerial, segmentGeneration, false);
        logicalFrame = 0;
        nextLogicalEdgeIndex = 0;
        nextPublicationFrame = 0;
    }

    /**
     * Ends the comparison window on the ROM iteration that writes the next
     * game mode from inside {@code RunObjects}: S2 {@code Obj79_Star} does
     * {@code move.b #GameModeID_SpecialStage,(Game_Mode).w}
     * (docs/s2disasm/s2.asm:44877) and S3K {@code SSEntryFlash_GoSS} the same.
     * That iteration still runs to completion -- the mode test that leaves
     * {@code Level_MainLoop} is at its tail (docs/s2disasm/s2.asm:5122-5125),
     * after {@code BuildSprites} (:5108) -- but it is no longer a row of the
     * level: nothing samples it, so its whole edge batch belongs to the
     * transition gap, whichever object produced it and in whatever order.
     * Player art queued earlier in the same iteration (Sonic runs before the
     * star post's higher slot) is therefore re-stamped {@code run_gap} too.
     * No row is published for the iteration.
     */
    public void endComparisonSegmentAtRomModeChange() {
        if (!runActive || !comparisonSegmentOpen) {
            return;
        }
        List<BufferedEdge> boundaryEdges = List.copyOf(bufferedEdges);
        // Recorded before the batch is discarded, so the gap opens on the
        // ledger its own first edges were observed against.
        recordGapOpeningState();
        beginBufferedEdgeBatch();
        comparisonSegmentOpen = false;
        Set<Long> boundarySubmissions = new java.util.LinkedHashSet<>();
        for (BufferedEdge edge : boundaryEdges) {
            if ("submitted".equals(edge.phase())) {
                boundarySubmissions.add(edge.transferId());
            }
        }
        for (long transferId : boundarySubmissions) {
            Descriptor descriptor = ledger.get(transferId);
            if (descriptor != null) {
                ledger.put(transferId, new Descriptor(
                        descriptor.transferId(), descriptor.owner(),
                        descriptor.mappingFrame(), "run_gap",
                        descriptor.requests()));
            }
        }
        for (BufferedEdge edge : boundaryEdges) {
            emitGapEdge(edge.edgeOrdinal(), edge.transferId(), edge.phase(),
                    edge.owner(), edge.mappingFrame(), edge.requests(),
                    edge.beforeOutstanding(), edge.afterOutstanding());
        }
        publishedOutstanding = List.copyOf(ledger.keySet());
    }

    public void closeComparisonSegment() {
        requireComparisonSegmentOpen();
        if (!bufferedEdges.isEmpty()) {
            if (nextPublicationFrame == 0) {
                throw new UnpublishedComparisonRowException();
            }
            int terminalFrame = nextPublicationFrame - 1;
            // The forwarded edges land on a row that was already published,
            // so they extend that row rather than replacing it: the recorder
            // reports one row per publication frame carrying both its own
            // edges and anything the following main-loop iteration forwarded
            // back onto it.
            List<DynamicArtDiagnosticsSnapshot.Edge> alreadyPublished =
                    latest.published() && latest.frame() == terminalFrame
                            ? latest.edges()
                            : List.of();
            latest = publishBuffered(terminalFrame, true, alreadyPublished);
        }
        recordGapOpeningState();
        comparisonSegmentOpen = false;
    }

    public void finishRun() {
        requireRunActive();
        if (comparisonSegmentOpen) {
            closeComparisonSegment();
        } else if (comparisonSegmentReserved) {
            cancelReservedComparisonSegment();
        }
        runActive = false;
    }

    /**
     * Abandons an observer window that never published its first row.
     * Production mapping decisions, preparations, outstanding transfers, and
     * monotonic identities survive so ordinary lifecycle ownership can resume.
     */
    public void abandonComparisonSegment() {
        requireComparisonSegmentOpen();
        if (nextPublicationFrame != 0) {
            throw new IllegalStateException(
                    "only an unpublished dynamic-art segment may be abandoned");
        }
        beginBufferedEdgeBatch();
        recordGapOpeningState();
        comparisonSegmentOpen = false;
        publishedOutstanding = List.copyOf(ledger.keySet());
        latest = DynamicArtDiagnosticsSnapshot.unpublished(
                deliverySerial, segmentGeneration);
        logicalFrame = 0;
        nextLogicalEdgeIndex = 0;
        nextPublicationFrame = 0;
    }

    public ArtUpdate observeRomDplc(
            String owner,
            int mappingFrame,
            List<TileLoadRequest> tileRequests,
            int romArtBase,
            int vramDestination) {
        return observeDplc(owner, mappingFrame, tileRequests,
                romArtBase, -1, vramDestination);
    }

    /**
     * Applies the typed per-game player-art profile to one ROM-loaded DPLC
     * decision. Unsupported games/owners remain on their existing renderer
     * path and emit no S1/S2 audit fact.
     */
    public ArtUpdate observePlayerDplc(
            GameId gameId,
            String owner,
            int mappingFrame,
            SpriteDplcFrame dplcFrame) {
        return observePlayerDplc(gameId, owner, mappingFrame, dplcFrame,
                DecisionKind.NORMAL_OBJECT);
    }

    public ArtUpdate observePlayerDplc(
            GameId gameId,
            String owner,
            int mappingFrame,
            SpriteDplcFrame dplcFrame,
            DecisionKind decisionKind) {
        requireRunActive();
        Objects.requireNonNull(decisionKind, "decisionKind");
        Map<String, ProductionArtProfile> gameProfiles =
                PLAYER_PROFILES.get(gameId);
        ProductionArtProfile profile =
                gameProfiles != null ? gameProfiles.get(owner) : null;
        if (profile == null) {
            return new ArtUpdate(false, -1, List.of());
        }
        List<TileLoadRequest> requests = dplcFrame != null
                && dplcFrame.requests() != null
                ? dplcFrame.requests() : List.of();
        if (gameId == GameId.S1) {
            return prepareS1(owner, mappingFrame, requests, profile);
        }
        if (levelEntryPlayerArtHeld) {
            return holdLevelEntryDecision(owner, mappingFrame, requests,
                    profile);
        }
        ArtUpdate update = observeRomDplc(owner, mappingFrame, requests,
                profile.romArtBase(), profile.vramDestination());
        if (gameId == GameId.S2 && update.submitted()) {
            pendingS2TransferIds.add(update.transferId());
        }
        return update;
    }

    /**
     * Primes a freshly loaded playable's ROM-backed art without publishing a
     * runtime transfer edge. Native level setup establishes this initial bank
     * before the compared presentation/main-loop stream begins.
     */
    public ArtUpdate primePlayerDplc(
            GameId gameId,
            String owner,
            int mappingFrame,
            SpriteDplcFrame dplcFrame) {
        requireRunActive();
        validateOwner(owner);
        if (mappingFrame < 0) {
            throw new IllegalArgumentException(
                    "mappingFrame must be nonnegative");
        }
        Map<String, ProductionArtProfile> gameProfiles =
                PLAYER_PROFILES.get(gameId);
        ProductionArtProfile profile =
                gameProfiles != null ? gameProfiles.get(owner) : null;
        if (profile == null) {
            return new ArtUpdate(false, -1, List.of());
        }
        List<TileLoadRequest> requests = dplcFrame != null
                && dplcFrame.requests() != null
                ? List.copyOf(dplcFrame.requests()) : List.of();
        Integer previous = lastMappingFrames.put(dedupRegister(owner), mappingFrame);
        if (previous != null && previous == mappingFrame) {
            return new ArtUpdate(false, -1, List.of());
        }
        if (gameId == GameId.S1 && !requests.isEmpty()) {
            // The ROM does not skip this transfer: Level_LoadObj's
            // ExecuteObjects pass stages the fresh player's tiles and sets
            // f_sonframechg (docs/s1disasm/_incObj/01 Sonic.asm:2391-2398), and
            // the first V-int of the Level_Delay / PalFadeIn_Alt tail performs
            // it (docs/s1disasm/sonic.asm:2956-2969). A segment-scoped replay
            // starts at Level_MainLoop, so that transfer happened before its
            // recorded window and stays unpublished; a run whose movie spans
            // the load owns the row it lands on. Keep the staged bank here and
            // let a run publish it explicitly.
            initialLoadPreparation = new Preparation(owner, mappingFrame,
                    toDiagnosticRequests(requests, profile.romArtBase(), -1,
                            profile.vramDestination()));
        }
        return new ArtUpdate(true, -1, requests);
    }

    /**
     * Publishes the level-load player transfer a fresh playable's priming
     * staged, for a run whose movie clock spans the load that produced it.
     *
     * <p>The transfer's row is the level's first main-loop row minus the
     * game's counted pre-main-loop tail
     * ({@link com.openggf.game.LevelInitProfile#preLevelMainLoopDelayFrames()}),
     * the same relation {@link #settlePendingPlayerPreparationBeforeLevelMainLoop(int)}
     * settles a mid-run load on. Nothing is published when no priming staged a
     * bank, so a segment-scoped replay is unaffected.
     *
     * @param firstMainLoopRow movie row of the level's first main-loop row
     * @param tailRows         counted V-int rows between the transfer and it
     */
    public void publishInitialLevelLoadPlayerTransfer(
            int firstMainLoopRow, int tailRows) {
        requireRunActive();
        if (firstMainLoopRow < 0 || tailRows < 0) {
            throw new IllegalArgumentException(
                    "row and tail counts must be nonnegative");
        }
        if (initialLoadPreparation == null || s1Preparation != null) {
            return;
        }
        s1Preparation = initialLoadPreparation;
        initialLoadPreparation = null;
        preMainLoopTailRows = tailRows;
        flushS1PreparationIfPending(firstMainLoopRow);
    }

    public void completePlayerDplc(
            GameId gameId,
            String owner,
            ArtUpdate update) {
        if (gameId == GameId.S1 || !update.submitted()) {
            return;
        }
        ProductionArtProfile profile =
                PLAYER_PROFILES.getOrDefault(gameId, Map.of()).get(owner);
        if (profile == null) {
            throw new IllegalArgumentException(
                    "missing player dynamic-art profile for "
                            + gameId + "/" + owner);
        }
        if (gameId == GameId.S2) {
            throw new IllegalStateException(
                    "S2 dynamic art completes only at the production VBlank boundary");
        }
        if (profile.stagingRamAddress() >= 0) {
            completeApplied(update,
                    List.of(DynamicArtDiagnosticsSnapshot.Request.ram(
                            profile.stagingRamAddress(),
                            profile.vramDestination(),
                            profile.stagingByteLength())));
        } else {
            completeApplied(update);
        }
    }

    public ArtUpdate observeRamDplc(
            String owner,
            int mappingFrame,
            List<TileLoadRequest> tileRequests,
            int ramArtBase,
            int vramDestination) {
        return observeDplc(owner, mappingFrame, tileRequests,
                -1, ramArtBase, vramDestination);
    }

    public ArtUpdate observeRamDplc(
            GameId gameId,
            String owner,
            int mappingFrame,
            List<TileLoadRequest> tileRequests,
            int ramArtBase,
            int vramDestination) {
        ArtUpdate update = observeRamDplc(owner, mappingFrame, tileRequests,
                ramArtBase, vramDestination);
        if (gameId == GameId.S2 && update.submitted()) {
            pendingS2TransferIds.add(update.transferId());
        }
        return update;
    }

    /**
     * Seeds an owner's last-loaded-DPLC dedup baseline the way the owning ROM
     * object's init routine writes its own dedup register, without publishing
     * or staging any transfer.
     *
     * <p>The dedup register is object-owned state that the object's init
     * rewrites, so a value left behind by an earlier visit to the same object
     * never suppresses the new instance's first transfer.
     *
     * @param owner        dynamic-art owner whose baseline is being seeded
     * @param mappingFrame value the ROM init routine writes to the register
     */
    public void primeDplcDedupBaseline(String owner, int mappingFrame) {
        validateOwner(owner);
        if (mappingFrame < 0) {
            throw new IllegalArgumentException(
                    "mappingFrame must be nonnegative");
        }
        lastMappingFrames.put(dedupRegister(owner), mappingFrame);
    }

    /**
     * Zeroes every player last-loaded-DPLC register the way a level load does.
     *
     * <p>The registers are level-scoped RAM, not persistent state. Sonic 2's
     * {@code Level_ClrRam} runs {@code clearRAM Misc_Variables,Misc_Variables_End}
     * (docs/s2disasm/s2.asm, {@code Level_ClrRam}), and all three registers live
     * inside that block — {@code Sonic_LastLoadedDPLC}
     * (docs/s2disasm/s2.constants.asm:1556), {@code Tails_LastLoadedDPLC} and
     * {@code TailsTails_LastLoadedDPLC} (:1625-1626), between
     * {@code Misc_Variables} (:1484) and {@code Misc_Variables_End} (:1629).
     * Sonic 1 is the same shape: {@code Level_ClrRam} runs
     * {@code clearRAM v_levelvariables} (docs/s1disasm/sonic.asm:2742) and
     * {@code v_sonframenum} sits inside it
     * (docs/s1disasm/_Variables.asm:179, 230, 301).
     *
     * <p>Consequently a level entered after a special stage starts with the
     * registers at zero, not at the values the stage left behind: the stage
     * writes the very same bytes (docs/s2disasm/s2.asm:69095, 69205, 70378,
     * 70403, 70504, 70586-70588), so without the clear the returned level
     * suppresses the player's first mapping-frame transfer against a special
     * stage's frame number.
     *
     * <p>Modelled as an absent register rather than a stored zero: the engine
     * treats "no value yet" as the fresh-level state its own level-load
     * priming path expects, and the ROM's cleared byte is only ever read
     * again after that priming has rewritten it.
     */
    public void clearPlayerDplcDedupRegistersForLevelLoad() {
        lastMappingFrames.clear();
    }

    private ArtUpdate observeDplc(
            String owner,
            int mappingFrame,
            List<TileLoadRequest> tileRequests,
            int romArtBase,
            int ramArtBase,
            int vramDestination) {
        requireRunActive();
        validateOwner(owner);
        if (mappingFrame < 0) {
            throw new IllegalArgumentException("mappingFrame must be nonnegative");
        }
        List<TileLoadRequest> checked =
                List.copyOf(Objects.requireNonNull(tileRequests, "tileRequests"));
        Integer previous = lastMappingFrames.put(dedupRegister(owner), mappingFrame);
        if (previous != null && previous == mappingFrame) {
            return new ArtUpdate(false, -1, List.of());
        }
        if (checked.isEmpty()) {
            return new ArtUpdate(true, -1, List.of());
        }

        List<DynamicArtDiagnosticsSnapshot.Request> requests =
                toDiagnosticRequests(checked, romArtBase, ramArtBase,
                        vramDestination);
        long transferId = nextTransferId++;
        List<Long> before = List.copyOf(ledger.keySet());
        Descriptor descriptor =
                new Descriptor(transferId, owner, mappingFrame,
                        comparisonSegmentOpen ? "segment" : "run_gap", requests);
        ledger.put(transferId, descriptor);
        buffer(transferId, "submitted", owner, mappingFrame, requests, before,
                descriptor.submissionOrigin());
        return new ArtUpdate(true, transferId, checked);
    }

    private ArtUpdate prepareS1(
            String owner,
            int mappingFrame,
            List<TileLoadRequest> tileRequests,
            ProductionArtProfile profile) {
        validateOwner(owner);
        if (mappingFrame < 0) {
            throw new IllegalArgumentException("mappingFrame must be nonnegative");
        }
        List<TileLoadRequest> checked =
                List.copyOf(Objects.requireNonNull(tileRequests, "tileRequests"));
        Integer previous = lastMappingFrames.put(dedupRegister(owner), mappingFrame);
        if (previous != null && previous == mappingFrame) {
            return new ArtUpdate(false, -1, List.of());
        }
        if (checked.isEmpty()) {
            return new ArtUpdate(true, -1, List.of());
        }
        Preparation prepared = new Preparation(owner, mappingFrame,
                toDiagnosticRequests(checked, profile.romArtBase(), -1,
                        profile.vramDestination()));
        if (s1Preparation != null && preMainLoopTailRows >= 0) {
            // The held staging belongs to the tail's first V-int; this one is
            // the main loop's. Queue it rather than replacing the held one.
            s1PreparationAfterPreMainLoopTail = prepared;
        } else {
            s1Preparation = prepared;
        }
        return new ArtUpdate(true, -1, checked);
    }

    /**
     * Holds playable art decisions taken while the level-entry load is still
     * running, so they surface on the row that load finishes on.
     *
     * <p>Arming this is a scheduling decision about work the engine creates
     * itself: it changes only WHEN a submission the engine already made
     * becomes visible, never what is submitted, and it reads no gameplay
     * value. Nothing arms it in production.
     */
    public void holdPlayerArtDuringLevelEntryLoad() {
        requireRunActive();
        levelEntryPlayerArtHeld = true;
    }

    /** Whether a level-entry hold is currently armed. */
    public boolean isPlayerArtHeldDuringLevelEntryLoad() {
        return levelEntryPlayerArtHeld;
    }

    /**
     * Releases the level-entry hold, submitting every held decision in the
     * order it was taken, on the row this is called from -- the ROM's
     * {@code InitPlayers} (docs/s2disasm/s2.asm:4946).
     *
     * <p>Releasing is idempotent and safe when nothing was held: a level entry
     * whose playables took no art decision publishes nothing.
     */
    public void releasePlayerArtHeldDuringLevelEntryLoad() {
        levelEntryPlayerArtHeld = false;
        if (heldLevelEntryPlayerArt.isEmpty()) {
            return;
        }
        List<Preparation> held = List.copyOf(heldLevelEntryPlayerArt);
        heldLevelEntryPlayerArt.clear();
        for (Preparation preparation : held) {
            submitPreparedTransfer(preparation);
        }
    }

    private ArtUpdate holdLevelEntryDecision(
            String owner,
            int mappingFrame,
            List<TileLoadRequest> tileRequests,
            ProductionArtProfile profile) {
        validateOwner(owner);
        if (mappingFrame < 0) {
            throw new IllegalArgumentException("mappingFrame must be nonnegative");
        }
        List<TileLoadRequest> checked =
                List.copyOf(Objects.requireNonNull(tileRequests, "tileRequests"));
        // The dedup register is the ROM's own per-owner last-frame byte and is
        // maintained here exactly as the unheld path maintains it; only the
        // edge's row changes.
        Integer previous = lastMappingFrames.put(dedupRegister(owner), mappingFrame);
        if (previous != null && previous == mappingFrame) {
            return new ArtUpdate(false, -1, List.of());
        }
        if (checked.isEmpty()) {
            return new ArtUpdate(true, -1, List.of());
        }
        heldLevelEntryPlayerArt.add(new Preparation(owner, mappingFrame,
                toDiagnosticRequests(checked, profile.romArtBase(), -1,
                        profile.vramDestination())));
        // The tiles still reach the renderer, as a priming does; only the
        // ledger edge waits for the load to finish.
        return new ArtUpdate(true, -1, checked);
    }

    private void submitPreparedTransfer(Preparation preparation) {
        long transferId = nextTransferId++;
        List<Long> before = List.copyOf(ledger.keySet());
        Descriptor descriptor = new Descriptor(
                transferId, preparation.owner(), preparation.mappingFrame(),
                comparisonSegmentOpen ? "segment" : "run_gap",
                preparation.requests());
        ledger.put(transferId, descriptor);
        buffer(transferId, "submitted", descriptor.owner(),
                descriptor.mappingFrame(), descriptor.requests(), before,
                descriptor.submissionOrigin());
        pendingS2TransferIds.add(transferId);
    }

    public void completeApplied(ArtUpdate update) {
        Objects.requireNonNull(update, "update");
        if (!update.submitted()) {
            return;
        }
        Descriptor descriptor = requirePending(update.transferId());
        completeApplied(update, descriptor.requests());
    }

    public void completeApplied(
            ArtUpdate update,
            List<DynamicArtDiagnosticsSnapshot.Request> completionRequests) {
        requireRunActive();
        Objects.requireNonNull(update, "update");
        if (!update.submitted()) {
            return;
        }
        Descriptor descriptor = requirePending(update.transferId());
        List<DynamicArtDiagnosticsSnapshot.Request> requests =
                List.copyOf(Objects.requireNonNull(
                        completionRequests, "completionRequests"));
        List<Long> before = List.copyOf(ledger.keySet());
        ledger.remove(update.transferId());
        buffer(update.transferId(), "completed", descriptor.owner(),
                descriptor.mappingFrame(), requests, before,
                descriptor.submissionOrigin());
    }

    /** Retires the S2 DMA FIFO at the production ProcessDMAQueue boundary. */
    /**
     * Supplies the physical movie row a gap edge should carry. The recorder
     * stamps gap edges with the BK2 row it has consumed
     * ("tools/tracechaser/bizhawk-headless/src/Recording/S1RunCaptureRunner.cs":199-215,
     * whose {@code rowsConsumed} counts every movie row from zero, and
     * S1DynamicArtObserver:483, which replaces a gap edge's frame with it).
     * Counting production iterations instead loses a row for every suppressed
     * one, so a driver that knows the row states it.
     */
    public void setMovieLogicalFrame(int movieRow) {
        if (movieRow < 0) {
            throw new IllegalArgumentException("movieRow must be nonnegative");
        }
        movieLogicalFrame = movieRow;
        movieLogicalFrameSuppliedExternally = true;
        if (movieRow != lastAnnouncedMovieRow) {
            lastAnnouncedMovieRow = movieRow;
            newMovieRowAnnouncedSinceIteration = true;
        }
    }

    public void serviceProductionVBlank() {
        requireRunActive();
        // A held tail is released by the level's first main-loop row, which is
        // announced, not observed here. An ordinary service therefore keeps
        // its own row: whatever is still pending belongs to this V-blank.
        preMainLoopTailRows = -1;
        flushS1PreparationIfPending(movieLogicalFrame);
        retireSubmittedTransfers();
    }

    /**
     * Holds a staged S1 player DPLC preparation for the level routine's
     * counted pre-main-loop tail.
     *
     * <p>The presentation boundary's object prelude staged the player's tiles
     * and set {@code f_sonframechg}
     * (docs/s1disasm/_incObj/01 Sonic.asm:2391-2398), but the V-int that
     * performs the transfer is the first row of the {@code Level_Delay} /
     * {@code PalFadeIn_Alt} tail that ends on the frame before
     * {@code Level_MainLoop} (docs/s1disasm/sonic.asm:2956-2969). Every load
     * step between the two — {@code Hud_Base}, {@code LevelDataLoad},
     * {@code LoadTilesFromStart}, {@code ObjPosLoad} — is straight-line code
     * with no wait of its own, so the transfer's row is fixed relative to the
     * main loop and not to the boundary that prepared it. The engine reaches
     * the main loop without spending those load rows, so the preparation waits
     * here until the level's first main-loop V-int settles it.
     *
     * @param tailRows counted V-int rows between the transfer and the first
     *                 main-loop row; zero settles at the boundary itself
     */
    public void holdPendingPlayerPreparationForPreMainLoopTail(int tailRows) {
        requireRunActive();
        if (tailRows < 0) {
            throw new IllegalArgumentException("tailRows must be nonnegative");
        }
        if (s1Preparation == null) {
            return;
        }
        if (tailRows == 0) {
            flushS1PreparationIfPending(movieLogicalFrame);
            return;
        }
        preMainLoopTailRows = tailRows;
        preMainLoopHoldBoundaryRow = movieLogicalFrame;
    }

    /**
     * Settles a held pre-main-loop player transfer from the tail that precedes
     * the level's first main-loop row.
     *
     * <p>The caller supplies the row the destination was admitted on, which is
     * that level's first main-loop row: a level segment's recording begins at
     * {@code Level_MainLoop} (docs/s1disasm/sonic.asm:2998), after the counted
     * {@code Level_Delay} / {@code PalFadeIn_Alt} tail has already run. The
     * transfer therefore belongs {@code tailRows} rows earlier.
     *
     * <p>This row must be observed, not projected from the movie clock. An
     * admission that consumes no destination row leaves the shared clock on the
     * gap's last row, but an admission that consumes one has already advanced
     * it onto the destination's first row; a {@code movieLogicalFrame + 1}
     * projection is right for the former and one row late for the latter.
     *
     * @param firstMainLoopRow movie row the destination was admitted on
     */
    public void settlePendingPlayerPreparationBeforeLevelMainLoop(
            int firstMainLoopRow) {
        requireRunActive();
        if (firstMainLoopRow < 0) {
            throw new IllegalArgumentException(
                    "firstMainLoopRow must be nonnegative");
        }
        if (preMainLoopTailRows < 0) {
            // Nothing was staged for a tail: an ordinary submission still
            // belongs to the V-blank that services it, not to this boundary.
            return;
        }
        flushS1PreparationIfPending(firstMainLoopRow);
    }

    /**
     * Settles a held pre-main-loop player transfer for a run that ends before
     * the level reaches its main loop, so the tail's length can never be
     * measured back from it. The transfer then takes the earliest row its tail
     * can occupy: the row after the one the prelude staged it on.
     */
    public void releaseUnclaimedPreMainLoopPlayerTransfer() {
        if (!runActive || preMainLoopTailRows < 0) {
            return;
        }
        int boundaryRow = preMainLoopHoldBoundaryRow;
        preMainLoopTailRows = -1;
        flushS1PreparationIfPending(Math.addExact(boundaryRow, 1));
    }

    private void flushS1PreparationIfPending(int firstMainLoopRow) {
        if (s1Preparation == null) {
            preMainLoopTailRows = -1;
            return;
        }
        int tailRows = Math.max(0, preMainLoopTailRows);
        preMainLoopTailRows = -1;
        int restore = movieLogicalFrame;
        movieLogicalFrame = Math.max(0, firstMainLoopRow - tailRows);
        try {
            flushS1Preparation();
        } finally {
            movieLogicalFrame = restore;
        }
    }

    private void flushS1Preparation() {
        Preparation preparation = s1Preparation;
        s1Preparation = s1PreparationAfterPreMainLoopTail;
        s1PreparationAfterPreMainLoopTail = null;
        long transferId = nextTransferId++;
        List<Long> before = List.copyOf(ledger.keySet());
        Descriptor descriptor = new Descriptor(
                transferId, preparation.owner(),
                preparation.mappingFrame(),
                comparisonSegmentOpen ? "segment" : "run_gap",
                preparation.requests());
        ledger.put(transferId, descriptor);
        buffer(transferId, "submitted", descriptor.owner(),
                descriptor.mappingFrame(), descriptor.requests(), before,
                descriptor.submissionOrigin());
        completeApplied(new ArtUpdate(true, transferId, List.of()),
                List.of(DynamicArtDiagnosticsSnapshot.Request.ram(
                        PLAYER_PROFILES.get(GameId.S1).get("sonic")
                                .stagingRamAddress(),
                        PLAYER_PROFILES.get(GameId.S1).get("sonic")
                                .vramDestination(),
                        PLAYER_PROFILES.get(GameId.S1).get("sonic")
                                .stagingByteLength())));
    }

    /**
     * Retires already-submitted transfers at the V-blank of the main-loop
     * iteration that follows the last sampled frame
     * (docs/s2disasm/s2.asm:5091 WaitForVint reaching ProcessDMAQueue at
     * docs/s2disasm/s2.asm:1769). It deliberately does not flush art that a
     * display pass has only staged into a RAM buffer: that art is not queued
     * work yet (docs/s1disasm/_incObj/01 Sonic.asm:2392 Sonic_LoadGfx copies
     * tiles into v_sgfx_buffer and merely sets f_sonframechg; the transfer
     * itself is issued by the V-int at docs/s1disasm/sonic.asm:831), and the
     * boundary this models ends before that issue.
     */
    public void serviceTerminalProductionVBlank() {
        requireRunActive();
        retireSubmittedTransfers();
    }

    /**
     * Retires already-submitted transfers for a ROM object pass that finished
     * BEFORE the V-int of the observation it is bound to. On hardware the
     * pass ran during the preceding (lag) frame, so the V-blank that opens
     * the bound observation had already run {@code ProcessDMAQueue}
     * (docs/s2disasm/s2.asm:1769) over its queued work: the pass's
     * submissions and completions surface on the same observation (stage-1
     * special-stage fixture rows 435-436, pass 5 — completion cursor 435,
     * bound row 436). Work queued by a later pass in the same observation
     * stays pending, exactly as on hardware.
     */
    public void serviceVblankBeforeBoundObservation() {
        requireRunActive();
        retireSubmittedTransfers();
    }

    private void retireSubmittedTransfers() {
        List<Long> retiring = List.copyOf(pendingS2TransferIds);
        pendingS2TransferIds.clear();
        for (long transferId : retiring) {
            Descriptor descriptor = requirePending(transferId);
            completeApplied(new ArtUpdate(
                    true, transferId, List.of()), descriptor.requests());
        }
    }

    public DynamicArtDiagnosticsSnapshot publishRow(
            int publicationFrame,
            boolean lagged) {
        requireComparisonSegmentOpen();
        requirePublicationFrame(publicationFrame);
        // A ROM lag frame publishes a heartbeat with no edges, but the logical
        // row cursor still advances: VBlank still ran and dispatched its lag
        // handler (docs/s1disasm/sonic.asm:651-655 tst.b v_vblank_routine /
        // beq.s VBlank_Lag, :709 VBlank_Lag; docs/s2disasm/s2.asm:484,513,529
        // Vint_Lag) even though the main loop did not complete an iteration and
        // v_framecount was not bumped (docs/s1disasm/sonic.asm:2995-2999). The
        // logical frame is therefore the publication row index, not a count of
        // non-lag iterations.
        latest = lagged
                ? new DynamicArtDiagnosticsSnapshot(
                        publicationFrame, List.of(), publishedOutstanding,
                        ++deliverySerial, segmentGeneration, true)
                : publishBuffered(publicationFrame, false);
        logicalFrame++;
        nextLogicalEdgeIndex = 0;
        return latest;
    }

    public DynamicArtDiagnosticsSnapshot publishTerminal(int publicationFrame) {
        requireComparisonSegmentOpen();
        requirePublicationFrame(publicationFrame);
        latest = publishBuffered(publicationFrame, true);
        return latest;
    }

    /** Publishes one live/headless row from production phase state. */
    public void finishProductionIteration(boolean lagged) {
        requireRunActive();
        if (comparisonSegmentOpen) {
            publishRow(nextPublicationFrame++, lagged);
        }
        if (!movieLogicalFrameSuppliedExternally) {
            movieLogicalFrame++;
        }
        gapEdgesBeforeLastIteration = gapEdgesBeforeCurrentIteration;
        gapEdgesBeforeCurrentIteration = gapTransitions.size();
        if (newMovieRowAnnouncedSinceIteration) {
            newMovieRowAnnouncedSinceIteration = false;
        } else {
            unannouncedRows++;
        }
    }

    private DynamicArtDiagnosticsSnapshot publishBuffered(
            int publicationFrame,
            boolean terminalForwarded) {
        return publishBuffered(publicationFrame, terminalForwarded, List.of());
    }

    private DynamicArtDiagnosticsSnapshot publishBuffered(
            int publicationFrame,
            boolean terminalForwarded,
            List<DynamicArtDiagnosticsSnapshot.Edge> alreadyPublished) {
        List<DynamicArtDiagnosticsSnapshot.Edge> edges =
                new ArrayList<>(alreadyPublished.size() + bufferedEdges.size());
        edges.addAll(alreadyPublished);
        for (BufferedEdge edge : bufferedEdges) {
            edges.add(new DynamicArtDiagnosticsSnapshot.Edge(
                    edge.edgeOrdinal(), edge.transferId(), edge.phase(),
                    edge.owner(), edge.mappingFrame(), edge.logicalFrame(),
                    edge.logicalEdgeIndex(), publicationFrame,
                    terminalForwarded, edge.requests(),
                    edge.submissionOrigin()));
        }
        beginBufferedEdgeBatch();
        publishedOutstanding = List.copyOf(ledger.keySet());
        return new DynamicArtDiagnosticsSnapshot(
                publicationFrame, edges, publishedOutstanding,
                ++deliverySerial, segmentGeneration, true);
    }

    @Override
    public DynamicArtDiagnosticsSnapshot latestSnapshot() {
        return latest;
    }

    @Override
    public List<DynamicArtGapTransition> gapTransitions() {
        return List.copyOf(gapTransitions);
    }

    public List<DynamicArtGapTransition.GapEdge> gapEdges() {
        return gapTransitions.stream()
                .map(DynamicArtGapTransition::edge).toList();
    }

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public RewindState capture() {
        return new RewindState(lastMappingFrames,
                List.copyOf(ledger.values()), bufferedEdges,
                pendingS2TransferIds, gapTransitions,
                publishedOutstanding, latest.frame(), latest.edges(),
                latest.outstandingTransferIds(), latest.published(),
                s1Preparation, s1PreparationAfterPreMainLoopTail,
                initialLoadPreparation,
                levelEntryPlayerArtHeld,
                List.copyOf(heldLevelEntryPlayerArt), runActive,
                comparisonSegmentOpen,
                comparisonSegmentReserved,
                nextTransferId, nextEdgeOrdinal,
                logicalFrame, nextLogicalEdgeIndex, nextPublicationFrame,
                movieLogicalFrame, preMainLoopTailRows,
                nextGapEdgeIndexByFrame.getOrDefault(movieLogicalFrame, 0),
                gapOpeningTransitionCount, gapOpeningMovieLogicalFrame,
                gapEdgesBeforeLastIteration, gapEdgesBeforeCurrentIteration,
                unannouncedRows, lastAnnouncedMovieRow,
                newMovieRowAnnouncedSinceIteration,
                gapOpeningLedger, ledgerBeforeBufferedBatch);
    }

    @Override
    public void restore(RewindState snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        lastMappingFrames.clear();
        lastMappingFrames.putAll(snapshot.lastMappingFrames());
        ledger.clear();
        for (Descriptor descriptor : snapshot.ledger()) {
            ledger.put(descriptor.transferId(), descriptor);
        }
        bufferedEdges.clear();
        bufferedEdges.addAll(snapshot.bufferedEdges());
        pendingS2TransferIds.clear();
        pendingS2TransferIds.addAll(snapshot.pendingS2TransferIds());
        gapTransitions.clear();
        gapTransitions.addAll(snapshot.gapTransitions());
        publishedOutstanding = snapshot.publishedOutstanding();
        latest = new DynamicArtDiagnosticsSnapshot(
                snapshot.latestFrame(), snapshot.latestEdges(),
                snapshot.latestOutstandingTransferIds(), deliverySerial,
                segmentGeneration, snapshot.latestPublished());
        s1Preparation = snapshot.s1Preparation();
        s1PreparationAfterPreMainLoopTail =
                snapshot.s1PreparationAfterPreMainLoopTail();
        initialLoadPreparation = snapshot.initialLoadPreparation();
        levelEntryPlayerArtHeld = snapshot.levelEntryPlayerArtHeld();
        heldLevelEntryPlayerArt.clear();
        heldLevelEntryPlayerArt.addAll(snapshot.heldLevelEntryPlayerArt());
        runActive = snapshot.runActive();
        comparisonSegmentOpen = snapshot.comparisonSegmentOpen();
        comparisonSegmentReserved = snapshot.comparisonSegmentReserved();
        nextTransferId = snapshot.nextTransferId();
        nextEdgeOrdinal = snapshot.nextEdgeOrdinal();
        logicalFrame = snapshot.logicalFrame();
        nextLogicalEdgeIndex = snapshot.nextLogicalEdgeIndex();
        nextPublicationFrame = snapshot.nextPublicationFrame();
        movieLogicalFrame = snapshot.movieLogicalFrame();
        gapOpeningTransitionCount = snapshot.gapOpeningTransitionCount();
        gapOpeningMovieLogicalFrame = snapshot.gapOpeningMovieLogicalFrame();
        gapEdgesBeforeLastIteration = snapshot.gapEdgesBeforeLastIteration();
        gapEdgesBeforeCurrentIteration =
                snapshot.gapEdgesBeforeCurrentIteration();
        unannouncedRows = snapshot.unannouncedRows();
        lastAnnouncedMovieRow = snapshot.lastAnnouncedMovieRow();
        newMovieRowAnnouncedSinceIteration =
                snapshot.newMovieRowAnnouncedSinceIteration();
        gapOpeningLedger = List.copyOf(snapshot.gapOpeningLedger());
        ledgerBeforeBufferedBatch =
                List.copyOf(snapshot.ledgerBeforeBufferedBatch());
        preMainLoopTailRows = snapshot.preMainLoopTailRows();
        // The snapshot carries the counter for the frame it was taken on,
        // which is the only one a restore inside a gap can continue.
        nextGapEdgeIndexByFrame.clear();
        if (snapshot.nextGapEdgeIndex() > 0) {
            nextGapEdgeIndexByFrame.put(
                    movieLogicalFrame, snapshot.nextGapEdgeIndex());
        }
    }

    @Override
    public void resetForMissingSnapshot() {
        resetState();
    }

    private void resetState() {
        lastMappingFrames.clear();
        ledger.clear();
        bufferedEdges.clear();
        pendingS2TransferIds.clear();
        gapTransitions.clear();
        s1Preparation = null;
        s1PreparationAfterPreMainLoopTail = null;
        initialLoadPreparation = null;
        levelEntryPlayerArtHeld = false;
        heldLevelEntryPlayerArt.clear();
        publishedOutstanding = List.of();
        latest = DynamicArtDiagnosticsSnapshot.unpublished(
                deliverySerial, segmentGeneration);
        runActive = false;
        comparisonSegmentOpen = false;
        comparisonSegmentReserved = false;
        nextTransferId = 0;
        nextEdgeOrdinal = 0;
        logicalFrame = 0;
        nextLogicalEdgeIndex = 0;
        nextPublicationFrame = 0;
        movieLogicalFrame = 0;
        movieLogicalFrameSuppliedExternally = false;
        unannouncedRows = 0;
        lastAnnouncedMovieRow = -1;
        newMovieRowAnnouncedSinceIteration = false;
        gapOpeningTransitionCount = 0;
        gapOpeningMovieLogicalFrame = 0;
        gapEdgesBeforeLastIteration = 0;
        gapEdgesBeforeCurrentIteration = 0;
        gapOpeningLedger = List.of();
        ledgerBeforeBufferedBatch = List.of();
        preMainLoopTailRows = -1;
        nextGapEdgeIndexByFrame.clear();
    }

    public boolean isSegmentArmed() {
        return comparisonSegmentOpen;
    }

    public boolean isRunActive() {
        return runActive;
    }

    public boolean isComparisonSegmentOpen() {
        return comparisonSegmentOpen;
    }

    public boolean isComparisonSegmentReserved() {
        return comparisonSegmentReserved;
    }

    private void buffer(
            long transferId,
            String phase,
            String owner,
            int mappingFrame,
            List<DynamicArtDiagnosticsSnapshot.Request> requests,
            List<Long> beforeOutstanding,
            String submissionOrigin) {
        long edgeOrdinal = nextEdgeOrdinal++;
        if (comparisonSegmentOpen) {
            // The ledger snapshots ride along so an iteration later found to be
            // the ROM's mode-change boundary can be re-emitted into the gap
            // ledger with the same before/after membership it observed here.
            bufferedEdges.add(new BufferedEdge(edgeOrdinal, transferId,
                    phase, owner, mappingFrame, logicalFrame,
                    nextLogicalEdgeIndex++, requests, submissionOrigin,
                    beforeOutstanding, List.copyOf(ledger.keySet())));
            return;
        }
        emitGapEdge(edgeOrdinal, transferId, phase, owner, mappingFrame,
                requests, beforeOutstanding, List.copyOf(ledger.keySet()));
    }

    private void emitGapEdge(
            long edgeOrdinal,
            long transferId,
            String phase,
            String owner,
            int mappingFrame,
            List<DynamicArtDiagnosticsSnapshot.Request> requests,
            List<Long> beforeOutstanding,
            List<Long> afterOutstanding) {
        DynamicArtGapTransition.GapEdge edge =
                new DynamicArtGapTransition.GapEdge(
                        edgeOrdinal, transferId, phase, owner,
                mappingFrame, movieLogicalFrame,
                nextGapEdgeIndexByFrame.merge(movieLogicalFrame, 1, Integer::sum) - 1,
                // The edge's own iteration is itself an unannounced row
                // when nothing announced one for it.
                unannouncedRows
                        + (newMovieRowAnnouncedSinceIteration ? 0 : 1),
                requests);
        gapTransitions.add(new DynamicArtGapTransition(edge, beforeOutstanding,
                afterOutstanding));
    }

    private Descriptor requirePending(long transferId) {
        Descriptor descriptor = ledger.get(transferId);
        if (descriptor == null) {
            throw new IllegalStateException(
                    "unknown or completed dynamic-art transfer: " + transferId);
        }
        return descriptor;
    }

    private static List<DynamicArtDiagnosticsSnapshot.Request>
            toDiagnosticRequests(
                    List<TileLoadRequest> tileRequests,
                    int romArtBase,
                    int ramArtBase,
                    int vramDestination) {
        if ((romArtBase >= 0) == (ramArtBase >= 0)) {
            throw new IllegalArgumentException(
                    "dynamic art must select one production source domain");
        }
        List<DynamicArtDiagnosticsSnapshot.Request> requests =
                new ArrayList<>(tileRequests.size());
        int destination = vramDestination;
        for (TileLoadRequest tileRequest : tileRequests) {
            if (tileRequest == null || tileRequest.startTile() < 0
                    || tileRequest.count() <= 0) {
                throw new IllegalArgumentException(
                        "DPLC requests require nonnegative source and positive count");
            }
            int byteLength = Math.multiplyExact(tileRequest.count(), 0x20);
            int sourceOffset = Math.multiplyExact(tileRequest.startTile(), 0x20);
            if (romArtBase >= 0) {
                requests.add(DynamicArtDiagnosticsSnapshot.Request.rom(
                        Math.addExact(romArtBase, sourceOffset),
                        tileRequest.startTile(), destination, byteLength));
            } else {
                requests.add(DynamicArtDiagnosticsSnapshot.Request.ram(
                        Math.addExact(ramArtBase, sourceOffset),
                        destination, byteLength));
            }
            destination = (destination + byteLength) & 0xFFFF;
        }
        return List.copyOf(requests);
    }

    private void requireRunActive() {
        if (!runActive) {
            throw new IllegalStateException(
                    "dynamic-art production run is not active");
        }
    }

    private void requireComparisonSegmentOpen() {
        requireRunActive();
        if (!comparisonSegmentOpen) {
            throw new IllegalStateException(
                    "dynamic-art comparison segment is not open");
        }
    }

    /**
     * Resolves the ROM last-loaded-DPLC register an owner dedups against.
     *
     * <p>Sonic 2 has exactly three such registers — {@code Sonic_LastLoadedDPLC},
     * {@code Tails_LastLoadedDPLC} and {@code TailsTails_LastLoadedDPLC}
     * (docs/s2disasm/s2.constants.asm:1556, 1625-1626) — one per permanently
     * allocated player art bank, and the special stage shares them with the
     * level rather than owning private ones. {@code Obj09}'s init writes
     * {@code #1} to {@code Sonic_LastLoadedDPLC} (docs/s2disasm/s2.asm:69095)
     * and {@code LoadSSSonicDynPLC} keeps deduping against that same register
     * (docs/s2disasm/s2.asm:69205); {@code Obj10} and the {@code Obj88} tail do
     * the same to {@code Tails_LastLoadedDPLC} and
     * {@code TailsTails_LastLoadedDPLC} (docs/s2disasm/s2.asm:70378, 70403,
     * 70504, 70586-70588). The level's {@code LoadSonicDynPLC},
     * {@code LoadTailsDynPLC} and {@code LoadTailsTailsDynPLC} read and write
     * the very same bytes (docs/s2disasm/s2.asm:38834-38836, 41639-41641,
     * 41663-41665), and only a character swap clears them
     * (docs/s2disasm/s2.asm:26039-26041).
     *
     * <p>The engine keeps distinct comparison owners for the level and
     * special-stage submitters because the recorder labels their edges apart,
     * but the dedup state behind them is one ROM byte per bank. Keying it by
     * the bank rather than by the submitter makes a special stage leave the
     * level's register dirty exactly as the ROM does, so the returned level
     * re-submits the player's first mapping frame instead of suppressing it
     * against a value stale since before the stage.
     */
    private static String dedupRegister(String owner) {
        return owner.startsWith("ss-") ? owner.substring(3) : owner;
    }

    private static void validateOwner(String owner) {
        if (!OWNERS.contains(owner)) {
            throw new IllegalArgumentException(
                    "unknown dynamic-art owner: " + owner);
        }
    }

    private static void requirePublicationFrame(int publicationFrame) {
        if (publicationFrame < 0) {
            throw new IllegalArgumentException(
                    "publicationFrame must be nonnegative");
        }
    }
}
