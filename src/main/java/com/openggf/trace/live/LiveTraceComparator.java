package com.openggf.trace.live;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.PlaybackDebugManager.PlaybackFrameObserver;
import com.openggf.game.GameServices;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.BootstrapDivergence;
import com.openggf.trace.EngineDiagnostics;
import com.openggf.trace.EngineSnapshot;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.LoadQueueComparisonProjection;
import com.openggf.trace.Severity;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceBinder;
import com.openggf.trace.TraceCharacterState;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.VerificationGroup;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.replay.TraceReplayRowPolicy;
import com.openggf.trace.TraceHudModel;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Engine-side per-frame trace comparator. Attached to
 * {@link com.openggf.debug.playback.PlaybackDebugManager} as a
 * {@link PlaybackFrameObserver}; gates ROM lag frames and accumulates
 * divergences into a ring buffer plus counters.
 */
public final class LiveTraceComparator implements PlaybackFrameObserver, TraceHudModel {
    private static final int RING_CAPACITY = 5;

    private final TraceData trace;
    private final TraceBinder binder;
    private final MismatchRingBuffer mismatches = new MismatchRingBuffer(RING_CAPACITY);
    private final Supplier<AbstractPlayableSprite> spriteProvider;
    private final Runnable firstErrorCallback;
    private final Consumer<FrameComparison> perFrameObserver;
    private final Supplier<DynamicArtDiagnosticsSnapshot> dynamicArtSnapshots;

    private int cursor;
    private int errorCount;
    private int warningCount;
    private int laggedFrames;
    private boolean firstErrorLogged;
    private boolean firstWarningLogged;
    private MismatchEntry firstNonCameraPhysicsMismatch;
    private int lastActionMask;
    private int lastInputMask;
    private boolean lastStartPressed;
    private boolean complete;
    private TraceFrame currentVisualFrame;
    private TraceFrame deferredTerminalDynamicArtFrame;
    private TraceFrame pendingDynamicArtFrame;
    private FrameComparison pendingDynamicArtBaseComparison;
    private boolean pendingDynamicArtRequiresPublication;
    private boolean pendingPlayableAnimationOnly;
    private Bk2FrameInput pendingPlayableAnimationInput;
    private List<BootstrapDivergence> bootstrapDivergences = List.of();
    private TraceReplayRowPolicy preparedRowPolicy;
    private boolean preparedRowActivated;
    private boolean preparedProductionMarkerActivated;
    private boolean pendingVblankStarvedProductionMarker;

    public LiveTraceComparator(TraceData trace,
                               ToleranceConfig tolerances,
                               int initialCursor,
                               Supplier<AbstractPlayableSprite> spriteProvider) {
        this(trace, tolerances, initialCursor, spriteProvider, null, null);
    }

    public LiveTraceComparator(TraceData trace,
                               ToleranceConfig tolerances,
                               int initialCursor,
                               Supplier<AbstractPlayableSprite> spriteProvider,
                               Runnable firstErrorCallback) {
        this(trace, tolerances, initialCursor, spriteProvider, firstErrorCallback, null);
    }

    public LiveTraceComparator(TraceData trace,
                               ToleranceConfig tolerances,
                               int initialCursor,
                               Supplier<AbstractPlayableSprite> spriteProvider,
                               Runnable firstErrorCallback,
                               Consumer<FrameComparison> perFrameObserver) {
        this(trace, tolerances, initialCursor, spriteProvider,
                firstErrorCallback, perFrameObserver,
                GameServices::captureDynamicArtDiagnostics);
    }

    LiveTraceComparator(
            TraceData trace,
            ToleranceConfig tolerances,
            int initialCursor,
            Supplier<AbstractPlayableSprite> spriteProvider,
            Runnable firstErrorCallback,
            Consumer<FrameComparison> perFrameObserver,
            Supplier<DynamicArtDiagnosticsSnapshot> dynamicArtSnapshots) {
        this.trace = trace;
        this.binder = new TraceBinder(tolerances);
        this.cursor = initialCursor;
        this.spriteProvider = spriteProvider;
        this.firstErrorCallback = firstErrorCallback;
        this.perFrameObserver = perFrameObserver;
        this.dynamicArtSnapshots = dynamicArtSnapshots;
    }

    @Override
    public void prepareFrame(Bk2FrameInput frame) {
        if (cursor >= trace.frameCount()) {
            preparedRowPolicy = null;
            preparedRowActivated = false;
            preparedProductionMarkerActivated = false;
            return;
        }
        if (preparedRowPolicy != null
                && preparedRowPolicy.traceIndex() == cursor
                && preparedRowPolicy.validationBk2Index() == frame.frameIndex()) {
            return;
        }
        preparedRowPolicy = TraceReplayRowPolicy.resolve(
                trace, cursor, frame.frameIndex());
        preparedRowActivated = false;
        preparedProductionMarkerActivated = false;
    }

    @Override
    public int appliedInputOffset(Bk2FrameInput frame) {
        prepareFrame(frame);
        return preparedRowPolicy != null
                ? preparedRowPolicy.appliedInputOffset() : 0;
    }

    /** Commits structural side effects once the prepared row is represented. */
    public void activatePreparedRow() {
        if (preparedRowPolicy == null || preparedRowActivated) {
            return;
        }
        preparedRowActivated = true;
        if (preparedRowPolicy.holdFirstSidekickAnimation()) {
            SpriteManager sprites = GameServices.spritesOrNull();
            if (sprites != null && !sprites.getSidekicks().isEmpty()) {
                sprites.getSidekicks().getFirst().getAnimationManager().suppressNextUpdate();
            }
        }
    }

    @Override
    public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
        prepareFrame(frame);
        activatePreparedProductionMarker();
        return preparedRowPolicy != null
                && (preparedRowPolicy.suppressedClosureCount() > 0
                || preparedRowPolicy.phase() == TraceExecutionPhase.ADVANCE_ONLY);
    }

    @Override
    public boolean shouldAdvanceVblankOnSkippedTick(Bk2FrameInput frame) {
        return vblankAdvanceCountOnSkippedTick(frame) > 0;
    }

    @Override
    public int vblankAdvanceCountOnSkippedTick(Bk2FrameInput frame) {
        prepareFrame(frame);
        return preparedRowPolicy != null
                ? preparedRowPolicy.suppressedClosureCount() : 1;
    }

    @Override
    public boolean hasUnconsumedRecordedRows() {
        return cursor < trace.frameCount();
    }

    @Override
    public void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped) {
        lastActionMask = frame.p1ActionMask();
        lastInputMask = frame.p1InputMask();
        lastStartPressed = frame.p1StartPressed();
        if (wasSkipped) {
            TraceFrame skipped = cursor < trace.frameCount() ? trace.getFrame(cursor) : null;
            TraceFrame previous = cursor > 0 ? trace.getFrame(cursor - 1) : null;
            TraceExecutionPhase skippedPhase = skipped != null
                    ? TraceReplayBootstrap.phaseForReplay(trace, previous, skipped)
                    : TraceExecutionPhase.VBLANK_ONLY;
            if (cursor == 0 && TraceReplayBootstrap.isS3kCompleteRunHandoffCounterTickRow(trace)) {
                TraceReplaySessionBootstrap.applyS3kCompleteRunHandoffNativePostRowEffects(trace);
            }
            if (cursor < trace.frameCount()) {
                currentVisualFrame = trace.getFrame(cursor);
            }
            if (skippedPhase == TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY) {
                pendingPlayableAnimationOnly = true;
                pendingPlayableAnimationInput = frame;
                return;
            } else {
                if (skippedPhase != TraceExecutionPhase.ADVANCE_ONLY) {
                    laggedFrames++;
                }
                FrameComparison skippedBase = skipped != null
                        ? binder.comparisonForFrame(skipped.frame()) : null;
                if (skipped != null) {
                    skippedBase = appendInputAlignment(skipped, frame, skippedBase);
                    compareDynamicArtOrQueueForPostProduction(
                            skipped, skippedBase, skippedPhase);
                }
                advanceComparisonCursor();
                checkComplete();
                return;
            }
        }
        compareCurrentRow(frame);
    }

    private void compareCurrentRow(Bk2FrameInput frame) {
        if (cursor >= trace.frameCount()) {
            checkComplete();
            return;
        }
        TraceFrame expected = trace.getFrame(cursor);
        currentVisualFrame = expected;
        TraceFrame previous = cursor > 0 ? trace.getFrame(cursor - 1) : null;
        TraceExecutionPhase phase =
                TraceReplayBootstrap.phaseForReplay(trace, previous, expected);
        if (!TraceReplayBootstrap.shouldCompareGameplayStateForReplay(phase)) {
            compareDynamicArtOrQueueForPostProduction(
                    expected, binder.comparisonForFrame(expected.frame()), phase);
            advanceComparisonCursor();
            checkComplete();
            return;
        }
        AbstractPlayableSprite sprite = spriteProvider.get();
        if (sprite == null) {
            FrameComparison inputComparison = appendInputAlignment(
                    expected, frame, binder.comparisonForFrame(expected.frame()));
            compareDynamicArtOrQueueForPostProduction(
                    expected, inputComparison, phase);
            advanceComparisonCursor();
            checkComplete();
            return;
        }
        // Pass the first sidekick's state too so the binder's
        // appendCharacterComparisons finds an actual value instead of
        // flagging every recorded sidekick field as divergent (EHZ1
        // etc. record Sonic+Tails).
        TraceCharacterState actualSidekick = captureFirstSidekickState();
        var diagnosticLevelManager = GameServices.levelOrNull();
        ObjectManager diagnosticObjectManager = diagnosticLevelManager != null
                ? diagnosticLevelManager.getObjectManager()
                : null;
        String engineFrameClock = diagnosticObjectManager != null
                ? String.format("vbc=%04X sub=(%04X,%04X)",
                        diagnosticObjectManager.getVblaCounter() & 0xFFFF,
                        sprite.getXSubpixelRaw(), sprite.getYSubpixelRaw())
                : String.format("sub=(%04X,%04X)",
                        sprite.getXSubpixelRaw(), sprite.getYSubpixelRaw());
        var diagnosticCamera = GameServices.cameraOrNull();
        int diagnosticCameraX = diagnosticCamera != null ? diagnosticCamera.getX() : -1;
        int diagnosticCameraY = diagnosticCamera != null ? diagnosticCamera.getY() : -1;
        EngineDiagnostics animationDiagnostics =
                EngineDiagnostics.formattedWithCameraAnimationSubpixelAndRings(
                        diagnosticCameraX, diagnosticCameraY,
                        sprite.getAnimationId(), sprite.getMappingFrame(),
                        sprite.getXSubpixelRaw(), sprite.getYSubpixelRaw(),
                        sprite.getRingCount(), engineFrameClock)
                        // Life count for the recorded life_count column. Absent
                        // outside a gameplay session, where -1 makes TraceBinder
                        // raise lives_present instead of dropping the comparison.
                        .withLives(GameServices.gameStateOrNull() != null
                                ? GameServices.gameStateOrNull().getLives()
                                : EngineDiagnostics.LIVES_ABSENT);
        TraceFrame comparisonExpected = "s3k".equals(trace.metadata().game())
                ? TraceReplayBootstrap.s3kFrameForGameplayComparison(
                        trace, cursor, previous, expected, phase)
                : TraceReplayBootstrap.frameForGameplayComparison(
                        trace, cursor, previous, expected, phase);
        FrameComparison result = binder.compareFrame(comparisonExpected,
                sprite.getCentreX(), sprite.getCentreY(),
                sprite.getXSpeed(), sprite.getYSpeed(), sprite.getGSpeed(),
                sprite.getAngle(), sprite.getAir(), sprite.getRolling(),
                sprite.getGroundMode().ordinal(),
                null, animationDiagnostics,
                "sidekick", actualSidekick);
        if (trace.metadata().hasPerFrameLoadQueueState()) {
            LoadQueueComparisonProjection projection =
                    LoadQueueComparisonProjection.project(
                            trace,
                            expected.frame(),
                            trace.loadQueueStatesForComparisonFrame(expected.frame()),
                            GameServices.captureQueueDiagnostics(),
                            GameServices.hardwareTiming().capture());
            binder.compareLoadQueues(
                    expected.frame(), projection.expected(), projection.actual());
            result = binder.comparisonForFrame(expected.frame());
        }
        result = appendInputAlignment(expected, frame, result);
        compareDynamicArtOrQueueForPostProduction(expected, result, phase);
        advanceComparisonCursor();
        checkComplete();
    }

    private void advanceComparisonCursor() {
        cursor++;
        preparedRowPolicy = null;
        preparedRowActivated = false;
        preparedProductionMarkerActivated = false;
    }

    /** Applies deferred no-VBlank state only when a real service closure is admitted. */
    public void activatePreparedProductionMarker() {
        if (preparedRowPolicy == null || preparedProductionMarkerActivated) {
            return;
        }
        preparedProductionMarkerActivated = true;
        TraceFrame current = trace.getFrame(preparedRowPolicy.traceIndex());
        TraceFrame previous = preparedRowPolicy.traceIndex() > 0
                ? trace.getFrame(preparedRowPolicy.traceIndex() - 1) : null;
        boolean vblankStarved =
                TraceReplayBootstrap.isVblankStarvedIterationForReplay(previous, current);
        TraceFrame next = preparedRowPolicy.traceIndex() + 1 < trace.frameCount()
                ? trace.getFrame(preparedRowPolicy.traceIndex() + 1) : null;
        TraceReplayBootstrap.markIterationHeldIntoNextRowForReplay(current, next);
        if (!preparedRowPolicy.productionPublicationClaim()) {
            pendingVblankStarvedProductionMarker |= vblankStarved;
            return;
        }
        if (pendingVblankStarvedProductionMarker || vblankStarved) {
            TraceReplayBootstrap.markReplayProductionIterationWithoutVblank();
        }
        pendingVblankStarvedProductionMarker = false;
    }

    private FrameComparison appendInputAlignment(
            TraceFrame expected,
            Bk2FrameInput frame,
            FrameComparison existing) {
        int validationMask = frame.p1InputMask();
        if (frame.p1ActionMask() != 0) {
            validationMask |= AbstractPlayableSprite.INPUT_JUMP;
        }
        if (binder.validateInput(expected, validationMask)) {
            return existing;
        }
        LinkedHashMap<String, FieldComparison> fields = new LinkedHashMap<>();
        if (existing != null) {
            fields.putAll(existing.fields());
        }
        fields.put("input_alignment", new FieldComparison(
                "input_alignment",
                String.format("0x%04X", expected.input() & 0xFFFF),
                String.format("0x%04X", validationMask & 0xFFFF),
                Severity.ERROR,
                Math.abs((expected.input() & 0xFFFF) - validationMask)));
        return new FrameComparison(
                expected.frame(), fields,
                existing != null ? existing.romDiagnostics() : "",
                existing != null ? existing.engineDiagnostics() : "");
    }

    private void compareDynamicArtOrQueueForPostProduction(
            TraceFrame expected,
            FrameComparison existing,
            TraceExecutionPhase phase) {
        if (!trace.metadata().hasPerFrameDynamicArtTransferState()) {
            publishComparison(existing, expected.frame());
            return;
        }
        if (expected.frame()
                == trace.getFrame(trace.frameCount() - 1).frame()) {
            deferredTerminalDynamicArtFrame = expected;
            publishComparison(existing, expected.frame());
            return;
        }
        if (pendingDynamicArtFrame != null) {
            throw new IllegalStateException(
                    "dynamic-art comparison row was not drained after production: "
                            + pendingDynamicArtFrame.frame());
        }
        pendingDynamicArtFrame = expected;
        pendingDynamicArtBaseComparison = existing;
        pendingDynamicArtRequiresPublication =
                phase != TraceExecutionPhase.ADVANCE_ONLY;
    }

    private void publishComparison(FrameComparison result, int frameNumber) {
        if (result == null) {
            return;
        }
        if (perFrameObserver != null) {
            perFrameObserver.accept(result);
        }
        absorbDivergentFields(result, frameNumber);
    }

    /**
     * Drains one nonterminal dynamic-art comparison after the production
     * lifecycle has finished publishing the represented row.
     */
    public FrameComparison publishPendingDynamicArtComparison(
            DynamicArtDiagnosticsSnapshot before,
            DynamicArtDiagnosticsSnapshot after) {
        TraceFrame expected = pendingDynamicArtFrame;
        if (expected == null) {
            return null;
        }
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (pendingDynamicArtRequiresPublication) {
            if (after.deliverySerial() <= before.deliverySerial()
                    || !after.published()
                    || after.segmentGeneration() != before.segmentGeneration()
                    || after.frame() != expected.frame()) {
                throw new IllegalStateException(
                        "dynamic-art row " + expected.frame()
                                + " was not published atomically after production"
                                + " (serial " + before.deliverySerial() + "->"
                                + after.deliverySerial()
                                + ", published=" + after.published()
                                + ", generation " + before.segmentGeneration()
                                + "->" + after.segmentGeneration()
                                + ", frame=" + after.frame() + ")");
            }
        } else if (!after.equals(before)) {
            throw new IllegalStateException(
                    "input-only trace row changed dynamic-art publication");
        }
        FrameComparison merged = binder.compareDynamicArt(
                trace.dynamicArtTransferStateForFrame(expected.frame()), after);
        // binder.compareDynamicArt merges with an existing binder comparison;
        // retain a synthetic base comparison when no binder row existed.
        if (merged == null) {
            merged = pendingDynamicArtBaseComparison;
        }
        pendingDynamicArtFrame = null;
        pendingDynamicArtBaseComparison = null;
        pendingDynamicArtRequiresPublication = false;
        publishComparison(merged, expected.frame());
        return merged;
    }

    /** Runs the S3K playable-prefix slice after the represented row published. */
    public boolean consumePostProductionPlayableAnimationAction() {
        if (!pendingPlayableAnimationOnly) {
            return false;
        }
        pendingPlayableAnimationOnly = false;
        Bk2FrameInput frame = pendingPlayableAnimationInput;
        pendingPlayableAnimationInput = null;
        advancePlayableAnimationsOnly();
        compareCurrentRow(Objects.requireNonNull(
                frame, "playable-animation row input"));
        return true;
    }

    /**
     * Compares the final advertised DPLC row after its structural owner has
     * closed the production comparison segment and published terminal edges.
     *
     * <p>This remains read-only: lifecycle closure is deliberately outside the
     * comparator. Repeated calls are no-ops, so terminal fields are counted and
     * reported exactly once.
     */
    public FrameComparison finalizeTerminalDynamicArtComparison() {
        TraceFrame expected = deferredTerminalDynamicArtFrame;
        if (expected == null) {
            return null;
        }
        TraceEvent.DynamicArtTransferState expectedDynamicArt =
                trace.dynamicArtTransferStateForFrame(expected.frame());
        DynamicArtDiagnosticsSnapshot actual = dynamicArtSnapshots.get();
        FrameComparison merged =
                binder.compareDynamicArt(expectedDynamicArt, actual);
        // Project the dynamic-art subset out of the binder's own comparison
        // rather than recomparing: a second comparator would carry a second
        // delivery-id origin (see DynamicArtIdEpoch) anchored on this terminal
        // row alone.
        java.util.Map<String, FieldComparison> dynamicFields =
                new java.util.LinkedHashMap<>();
        merged.fields().forEach((name, field) -> {
            if (name.startsWith("dynamic_art.")) {
                dynamicFields.put(name, field);
            }
        });
        FrameComparison dynamicOnly =
                new FrameComparison(expectedDynamicArt.frame(), dynamicFields);
        deferredTerminalDynamicArtFrame = null;
        if (perFrameObserver != null) {
            perFrameObserver.accept(dynamicOnly);
        }
        absorbDivergentFields(dynamicOnly, expected.frame());
        return merged;
    }

    public boolean hasDeferredTerminalDynamicArt() {
        return deferredTerminalDynamicArtFrame != null;
    }

    /** Compares native-prelude frame-zero state without advancing replay. */
    public List<BootstrapDivergence> compareBootstrap(EngineSnapshot snapshot) {
        bootstrapDivergences = binder.compareBootstrapFrame0(trace, snapshot);
        for (BootstrapDivergence divergence : bootstrapDivergences) {
            Severity severity = divergence.severity()
                    == BootstrapDivergence.Severity.ERROR
                    ? Severity.ERROR : Severity.WARNING;
            if (severity == Severity.ERROR) {
                errorCount++;
                bootstrapErrorCount++;
                if (!firstErrorLogged) {
                    firstErrorLogged = true;
                    if (firstErrorCallback != null) {
                        firstErrorCallback.run();
                    }
                }
            } else {
                warningCount++;
            }
            mismatches.push(new MismatchEntry(
                    0,
                    divergence.field(),
                    divergence.expected(),
                    divergence.actual(),
                    divergence.context(),
                    severity,
                    1));
        }
        return bootstrapDivergences;
    }

    /**
     * Compares an opening row this comparator will never reach live.
     *
     * <p>A destination admitted after its first row already ran attaches with
     * that row consumed, so live comparison starts at row one and the adopted
     * row zero would otherwise be published but never checked. The engine's
     * state at attach time IS that row's end state, so comparing the row-zero
     * publication against the fixture's row zero here is an ordinary
     * comparison-only check, not a reconstruction.
     */
    public void compareAdoptedOpeningRow(
            int row, DynamicArtDiagnosticsSnapshot published) {
        Objects.requireNonNull(published, "published");
        if (row < 0 || row >= trace.frameCount()) {
            throw new IllegalArgumentException(
                    "opening row out of range: " + row);
        }
        FrameComparison comparison = binder.compareDynamicArt(
                trace.dynamicArtTransferStateForFrame(
                        trace.getFrame(row).frame()),
                published);
        if (comparison != null) {
            ingestExternalComparison(comparison);
        }
    }

    /**
     * Publishes a comparison produced by an external, gameplay-uncompared
     * structural row through the same observer, counters, first-error callback,
     * and HUD mismatch ring as ordinary live frame comparisons.
     *
     * <p>The result is immutable comparison output; this method owns no
     * production lifecycle or expected trace data.
     */
    public void ingestExternalComparison(FrameComparison result) {
        FrameComparison checked = Objects.requireNonNull(result, "result");
        if (perFrameObserver != null) {
            perFrameObserver.accept(checked);
        }
        absorbDivergentFields(checked, checked.frame());
    }

    private static void advancePlayableAnimationsOnly() {
        SpriteManager sprites = GameServices.spritesOrNull();
        if (sprites == null) {
            return;
        }
        sprites.advancePlayableSlotPrefix();
    }

    private void absorbDivergentFields(FrameComparison result, int frameNumber) {
        List<FieldComparison> divergent = result.divergentFields();
        for (FieldComparison fc : divergent) {
            Severity sev = fc.severity();
            if (sev == Severity.ERROR) {
                errorCount++;
                errorCountByGroup[fc.verificationGroup().ordinal()]++;
            } else if (sev == Severity.WARNING) {
                warningCount++;
            } else {
                continue;
            }
            if (sev == Severity.ERROR && !firstErrorLogged) {
                firstErrorLogged = true;
                if (firstErrorCallback != null) {
                    firstErrorCallback.run();
                }
                System.err.printf(
                        "[LiveTraceComparator] FIRST ERROR at trace frame %d:%n"
                                + "  field=%s expected=%s actual=%s delta=%d%n"
                                + "  full frame comparison: %s%n"
                                + "  active objects near player:%n%s",
                        frameNumber,
                        fc.fieldName(),
                        fc.expected(),
                        fc.actual(),
                        fc.delta(),
                        result,
                        summariseNearbyObjects());
            }
            if (sev == Severity.WARNING && !firstWarningLogged) {
                firstWarningLogged = true;
                System.err.printf(
                        "[LiveTraceComparator] FIRST WARNING at trace frame %d:%n"
                                + "  field=%s expected=%s actual=%s delta=%d%n"
                                + "  full frame comparison: %s%n"
                                + "  active objects near player:%n%s",
                        frameNumber,
                        fc.fieldName(),
                        fc.expected(),
                        fc.actual(),
                        fc.delta(),
                        result,
                        summariseNearbyObjects());
            }
            MismatchEntry mismatch = new MismatchEntry(
                    frameNumber,
                    fc.fieldName(),
                    fc.expected(),
                    fc.actual(),
                    Integer.toString(fc.delta()),
                    sev,
                    1);
            if (firstNonCameraPhysicsMismatch == null
                    && sev == Severity.ERROR
                    && fc.verificationGroup() == VerificationGroup.PHYSICS
                    && !fc.fieldName().startsWith("camera_")) {
                firstNonCameraPhysicsMismatch = mismatch;
            }
            mismatches.push(mismatch);
        }
    }

    private String summariseNearbyObjects() {
        var level = GameServices.levelOrNull();
        ObjectManager om = level != null ? level.getObjectManager() : null;
        if (om == null) {
            return "    (no ObjectManager)";
        }
        AbstractPlayableSprite sprite = spriteProvider.get();
        int px = sprite != null ? sprite.getCentreX() & 0xFFFF : 0;
        int py = sprite != null ? sprite.getCentreY() & 0xFFFF : 0;
        StringBuilder sb = new StringBuilder();
        for (ObjectInstance inst : om.getActiveObjects()) {
            if (!(inst instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            // Dynamic effects/projectiles can legitimately have a null
            // spawn (see AbstractObjectInstance.snapshotPreUpdatePosition);
            // the interface getX()/getY() default would NPE on them. They
            // also have no meaningful placement coordinate to show in a
            // "nearby objects" summary, so skip them.
            if (aoi.getSpawn() == null) {
                continue;
            }
            int ox = aoi.getX() & 0xFFFF;
            int oy = aoi.getY() & 0xFFFF;
            int dx = Math.abs(ox - px);
            int dy = Math.abs(oy - py);
            // Include anything within a ~screen-width horizontal box —
            // the divergence is typically tied to badniks a few blocks
            // ahead of the player, not off-screen spawns.
            if (dx > 0x180 || dy > 0x100) {
                continue;
            }
            int id = aoi.getSpawn().objectId();
            sb.append(String.format(
                    "    slot=%3d id=0x%02X %s @%04X,%04X (dx=%d dy=%d)%n",
                    aoi.getSlotIndex(),
                    id & 0xFF,
                    aoi.getClass().getSimpleName(),
                    ox, oy,
                    ox - px, oy - py));
        }
        if (sb.length() == 0) {
            return "    (no active objects within a screen-width of the player)";
        }
        return sb.toString();
    }

    private static TraceCharacterState captureFirstSidekickState() {
        SpriteManager sprites = GameServices.spritesOrNull();
        if (sprites == null || sprites.getRegisteredSidekicks().isEmpty()) {
            return null;
        }
        return TraceCharacterState.fromSprite(sprites.getRegisteredSidekicks().getFirst());
    }

    private void checkComplete() {
        if (cursor >= trace.frameCount()) {
            complete = true;
        }
    }

    /**
     * Repositions the visual/comparison cursor after an engine-state rewind.
     * Existing mismatch counters are retained; no comparison is performed for
     * the seek itself.
     */
    public void seekForRewind(int targetCursor) {
        if (trace.frameCount() == 0) {
            cursor = 0;
            currentVisualFrame = null;
            complete = true;
            return;
        }
        cursor = Math.max(0, Math.min(targetCursor, trace.frameCount() - 1));
        preparedRowPolicy = null;
        preparedRowActivated = false;
        int visualCursor = targetCursor <= 0
                ? 0
                : Math.min(targetCursor - 1, trace.frameCount() - 1);
        currentVisualFrame = trace.getFrame(visualCursor);
        complete = false;
    }

    /**
     * Per-{@link VerificationGroup} error tallies, incremented in lockstep with
     * {@link #errorCount} so the two can never disagree.
     *
     * <p>Published so a chain report can be audited from its own output. Chain
     * reports previously carried only a flat count, which left "are some groups
     * uncounted?" unanswerable from the artefact -- a question that cost two
     * lanes a round on 2026-08-21 even though the answer was benign. The totals
     * themselves are untouched by this: these are additional readings of the
     * same increments, never a second source of truth.
     */
    private final int[] errorCountByGroup = new int[VerificationGroup.values().length];

    /**
     * Errors from {@link #compareBootstrap}, which compares native-prelude
     * frame-zero state and carries {@link BootstrapDivergence} rather than a
     * {@link FieldComparison}, so it has no verification group to attribute to.
     * Tracked separately so group + bootstrap accounts for the flat total
     * exactly.
     */
    private int bootstrapErrorCount;

    public int errorCount(VerificationGroup group) {
        return errorCountByGroup[group.ordinal()];
    }

    /** Errors with no verification group -- see {@link #bootstrapErrorCount}. */
    public int bootstrapErrorCount() { return bootstrapErrorCount; }

    public int errorCount() { return errorCount; }
    public int warningCount() { return warningCount; }
    public int laggedFrames() { return laggedFrames; }
    public int cursor() { return cursor; }
    public MismatchEntry firstNonCameraPhysicsMismatch() { return firstNonCameraPhysicsMismatch; }
    public boolean hasRecordingDesync() { return firstErrorLogged; }
    public boolean isComplete() { return complete; }
    public List<MismatchEntry> recentMismatches() { return mismatches.recent(); }
    public int recentActionMask() { return lastActionMask; }
    public int recentInputMask() { return lastInputMask; }
    public boolean recentStartPressed() { return lastStartPressed; }
    public TraceMetadata metadata() { return trace.metadata(); }
    public TraceFrame currentVisualFrame() { return currentVisualFrame; }
    public List<BootstrapDivergence> bootstrapDivergences() {
        return bootstrapDivergences;
    }
}
