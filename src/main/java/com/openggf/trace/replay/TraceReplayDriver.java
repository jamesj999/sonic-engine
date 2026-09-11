package com.openggf.trace.replay;

import com.openggf.GameLoop;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.session.GameplayTeamBootstrap;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.live.LiveTraceComparator;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * UI-agnostic deterministic trace-replay drive. Extracted verbatim from
 * {@code TraceSessionLauncher.finishLaunchAfterGameBootstrap()} so the same
 * bootstrap can be reused by the live Trace Test Mode launcher and the headless
 * trace-capture tool.
 *
 * <p>{@link #start(int, int)} runs the reusable bootstrap steps 1–9 in order.
 * The fragile invariants (team-after-reset-before-zone-load,
 * title-card-before-first-step, startPosition-before-applyBootstrap,
 * align-counters-before-comparator, cursor sync) live in this one method now.
 * UI-specific concerns (fade/teardown, completion-hold, camera focus, HUD
 * overlay, rewind controllers, Esc handling, the {@code activeSession} static)
 * remain in the caller.
 */
public final class TraceReplayDriver {

    private final TraceData trace;
    private final Bk2Movie movie;
    private final TraceReplayFixture fixture;
    private final GameLoop loop;
    private final Supplier<AbstractPlayableSprite> spriteSupplier;
    private final Runnable onComparatorPause;
    private final boolean forceHardwareTimingReplay;
    private final Consumer<FrameComparison> comparisonObserver;

    private LiveTraceComparator comparator;
    private int initialCursor;

    /**
     * Live Trace Test Mode constructor: a comparator desync pauses gameplay via
     * {@link GameLoop#toggleUserPause()} (the interactive UX).
     */
    public TraceReplayDriver(TraceData trace, Bk2Movie movie, TraceReplayFixture fixture,
                             GameLoop loop, Supplier<AbstractPlayableSprite> spriteSupplier) {
        this(trace, movie, fixture, loop, spriteSupplier, loop::toggleUserPause);
    }

    /**
     * General constructor. Headless capture passes a no-op {@code onComparatorPause}
     * so it records <em>through</em> desyncs (the diverging ghost is the content)
     * and runs to trace completion instead of freezing on the first mismatch.
     */
    public TraceReplayDriver(TraceData trace, Bk2Movie movie, TraceReplayFixture fixture,
                             GameLoop loop, Supplier<AbstractPlayableSprite> spriteSupplier,
                             Runnable onComparatorPause) {
        this(trace, movie, fixture, loop, spriteSupplier, onComparatorPause, false);
    }

    public TraceReplayDriver(
            TraceData trace,
            Bk2Movie movie,
            TraceReplayFixture fixture,
            GameLoop loop,
            Supplier<AbstractPlayableSprite> spriteSupplier,
            Runnable onComparatorPause,
            boolean forceHardwareTimingReplay) {
        this(trace, movie, fixture, loop, spriteSupplier, onComparatorPause,
                forceHardwareTimingReplay, null);
    }

    public TraceReplayDriver(
            TraceData trace,
            Bk2Movie movie,
            TraceReplayFixture fixture,
            GameLoop loop,
            Supplier<AbstractPlayableSprite> spriteSupplier,
            Runnable onComparatorPause,
            boolean forceHardwareTimingReplay,
            Consumer<FrameComparison> comparisonObserver) {
        this.trace = trace;
        this.movie = movie;
        this.fixture = fixture;
        this.loop = loop;
        this.spriteSupplier = spriteSupplier;
        this.onComparatorPause = onComparatorPause;
        this.forceHardwareTimingReplay = forceHardwareTimingReplay;
        this.comparisonObserver = comparisonObserver;
    }

    /**
     * Runs the reusable bootstrap steps 1–9 in order. Throws on failure (the
     * caller restores config and routes back to its idle/picker path).
     */
    public void start(int zone, int act) throws Exception {
        PlaybackDebugManager playback = GameServices.playbackDebug();

        // prepareConfiguration already ran inside launch() before
        // launchGameByEntry fired — the master-title exit handler
        // needs the recorded team config in place when it calls
        // GameplayTeamBootstrap.registerActiveTeam.
        //
        // Mirror TestEnvironment.resetPerTest so the replay starts
        // from the same zero state the headless trace tests do.
        // Without this, state left by initializeGame() (title
        // screen, default level, residual objects) leaks in and
        // causes subpixel drift from frame 0 that surfaces at the
        // first enemy destruction.
        //
        // The reset zaps the sprite manager, so we need to
        // re-register the active team BEFORE loadZoneAndAct runs
        // (loadZoneAndAct's spawnPlayerAtStartPosition expects the
        // main sprite to already exist — otherwise the camera's
        // focusedSprite ends up null on the next frame).
        TraceReplaySessionBootstrap.resetLevelSubsystemsForReplay();
        GameplayTeamBootstrap.registerActiveTeam(
                GameServices.module(),
                GameServices.sprites(),
                GameServices.configuration());
        GameServices.level().loadZoneAndAct(zone, act);
        loop.setGameMode(GameMode.LEVEL);

        // Swallow every title-card request the level load raised —
        // both the main `consumeTitleCardRequest` (fired by
        // requestTitleCardIfNeeded during profile load steps; if
        // left armed, GameLoop.stepInternal flips the mode to
        // TITLE_CARD on the next step, freezing the sprite and
        // desyncing the BK2 cursor from the comparator) and the
        // in-level variant. Headless trace tests bypass both via
        // the headless graphics mode; we do it explicitly.
        //
        // Swallowing the requests is enough: nothing here may wipe the
        // provider's post-slide-in phase. The native game's title-card pieces
        // outlive the locked loop and run Obj34_WaitAndGoAway on ordinary
        // gameplay frames, loading the standard-water and per-zone animal art
        // as they leave (docs/s2disasm/s2.asm:4914-4925, 5066-5080,
        // 27605-27637); resetting the provider here deleted that tail and the
        // PLC queue never saw the append.
        GameServices.level().skipPendingInitialTitleCardPresentation();
        GameServices.level().consumeInLevelTitleCardRequest();

        startPlayback(playback, false);
    }

    /**
     * Adopts the level and title-card state already owned by the visual game
     * session. This path never resets, registers a team, loads a level, or
     * clears the production title-card provider.
     */
    public void startPreparedLevel() throws Exception {
        PlaybackDebugManager playback = GameServices.playbackDebug();
        if (loop.getCurrentGameMode() != GameMode.LEVEL) {
            throw new IllegalStateException(
                    "prepared visual trace requires released LEVEL mode");
        }
        if (forceHardwareTimingReplay
                || trace.hardwareTimingSchedule().hasRecordedInput()) {
            fixture.gameplayMode().activateRecordedHardwareAdmission();
        }
        startPlayback(playback, true);
    }

    private void startPlayback(
            PlaybackDebugManager playback, boolean preparedLevel)
            throws Exception {
        int startIndex = TraceReplayBootstrap
                .recordingStartFrameForTraceReplay(trace);
        playback.startSession(movie, startIndex);

        // For standalone replay, HeadlessTestFixture.Builder.build() always performs an unconditional
        // ground/angle/sensor snap (step 12: GameServices.collision()
        // .resolveGroundAttachment(sprite, 14, ...)) and does it BEFORE any
        // trace-data bootstrap runs -- crucially, BEFORE
        // applyStartPositionAndGroundSnap's own S3K complete-run/bonus-stage
        // post-title-card player-state calls (Sonic3kLevelEventManager
        // .applyCompleteRunSegmentPlayerStateAfterTitleCard /
        // armCarryIntroHandoffAfterTitleCard), because build() runs its
        // ground snap (step 12) internally, and the test's own
        // applyStartPositionAndGroundSnap call always comes strictly after
        // build() returns. applyStartPositionAndGroundSnap's own terrain snap
        // is gated off for S3K complete-run/bonus-stage segments (see
        // TraceReplayBootstrap.shouldGroundSnapMetadataStartForTraceReplay)
        // on the assumption that a PRIOR unconditional snap already ran at
        // the metadata start centre -- true for every fixture-backed
        // standalone trace test via build()'s step 12.
        //
        // This driver has no build() step, so standalone replay must reproduce
        // the same "set metadata centre, then snap" pair BEFORE calling
        // applyStartPositionAndGroundSnap, not after. A prepared visual
        // session instead adopts the production title-card state that already
        // owns its position and ground attachment.
        if (!preparedLevel) {
            if (TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace)
                    && !TraceReplaySessionBootstrap
                            .shouldPreserveFreshGroundedStatusUntilFirstDispatch(trace)) {
                AbstractPlayableSprite preSnapSprite = fixture.sprite();
                if (preSnapSprite != null) {
                    TraceMetadata meta = trace.metadata();
                    preSnapSprite.setCentreX(meta.startX());
                    preSnapSprite.setCentreY(meta.startY());
                    GameServices.collision().resolveGroundAttachment(preSnapSprite, 14, () -> false);
                }
            }
            TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
        }
        TraceReplaySessionBootstrap.BootstrapResult boot = preparedLevel
                ? TraceReplaySessionBootstrap.applyPreparedLevelBootstrap(
                        trace, fixture, forceHardwareTimingReplay)
                : TraceReplaySessionBootstrap.applyBootstrap(
                        trace, fixture, -1, forceHardwareTimingReplay);

        this.initialCursor = boot.replayStart().startingTraceIndex();
        TraceFrame previousDriveFrame = boot.replayStart().hasSeededTraceState()
                ? trace.getFrame(boot.replayStart().seededTraceIndex())
                : initialCursor > 0 ? trace.getFrame(initialCursor - 1) : null;
        TraceReplaySessionBootstrap.alignFrameCountersForReplayStart(
                trace,
                boot.replayStart(),
                previousDriveFrame,
                initialCursor < trace.frameCount() ? trace.getFrame(initialCursor) : null);
        this.comparator = new LiveTraceComparator(
                trace,
                ToleranceConfig.DEFAULT,
                initialCursor,
                spriteSupplier::get,
                onComparatorPause,
                comparisonObserver);
        comparator.compareBootstrap(
                TraceReplayEngineSnapshot.capture(spriteSupplier.get()));
        playback.setFrameObserver(comparator);
    }

    /** Trace index where replay starts (recording start frame for this trace). */
    public int recordingStartFrame() {
        return TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace);
    }

    /** Trace cursor the comparator started at (used as the rewind trace base frame). */
    public int initialCursor() {
        return initialCursor;
    }

    public LiveTraceComparator comparator() {
        return comparator;
    }

    public boolean isComplete() {
        return comparator != null && comparator.isComplete();
    }

    public TraceFrame currentVisualFrame() {
        return comparator != null ? comparator.currentVisualFrame() : null;
    }
}
