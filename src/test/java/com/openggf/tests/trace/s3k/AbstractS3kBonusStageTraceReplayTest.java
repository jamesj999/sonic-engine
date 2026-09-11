package com.openggf.tests.trace.s3k;

import com.openggf.game.BonusStageProvider;
import com.openggf.game.GameServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent.ZoneActState;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared base for S3K bonus-stage trace replay (gumball/pachinko/slots).
 * Bonus zones run on the LEVEL pipeline, so the entire level replay stack
 * applies; the only addition is the bonus-entry bootstrap after load.
 */
public abstract class AbstractS3kBonusStageTraceReplayTest extends AbstractTraceReplayTest {

    @Override
    protected void afterFixtureBuild(TraceData trace) {
        TraceReplaySessionBootstrap.applyBonusStageEntry(trace);
    }

    @Override
    protected Integer semanticTimingPrefixLastRawFrame(TraceData trace) {
        return deriveLastBonusRawFrame(trace);
    }

    static int deriveLastBonusRawFrame(TraceData trace) {
        List<TraceFrame> representedFrames = new ArrayList<>();
        List<ZoneActState> zoneActStates = new ArrayList<>();
        for (int traceIndex = 0; traceIndex < trace.frameCount(); traceIndex++) {
            TraceFrame frame = trace.getFrame(traceIndex);
            representedFrames.add(frame);
            for (var event : trace.getEventsForFrame(frame.frame())) {
                if (event instanceof ZoneActState state) {
                    zoneActStates.add(state);
                }
            }
        }
        return deriveLastBonusRawFrame(representedFrames, zoneActStates);
    }

    /**
     * The recorder captured $B000 — the object the ROM camera tracks — so the
     * comparator must follow the same object. Gumball and pachinko never
     * change which sprite the camera focuses on, but the slot runtime swaps
     * the tracked player onto the dedicated slot-machine playable
     * ({@code S3kSlotBonusStageRuntime.bootstrap()} calls
     * {@code getCamera().setFocusedSprite(slotPlayer)}). Reading the
     * camera-focused sprite here is engine-state-keyed, not bonus-type-keyed:
     * it stays a no-op for gumball/pachinko (focus never leaves the original
     * player) and picks up the swap automatically for slots.
     */
    @Override
    protected AbstractPlayableSprite comparedSprite(HeadlessTestFixture fixture) {
        AbstractPlayableSprite focused = GameServices.camera().getFocusedSprite();
        return selectComparedSprite(focused, fixture.sprite());
    }

    @Override
    protected boolean replayTerminalReached() {
        return hasReachedTerminalBoundary(GameServices.bonusStageOrNull());
    }

    static boolean hasReachedTerminalBoundary(BonusStageProvider provider) {
        return provider != null && provider.isStageComplete();
    }

    static int deriveLastBonusRawFrame(
            List<TraceFrame> representedFrames, List<ZoneActState> zoneActStates) {
        if (zoneActStates.isEmpty()
                || zoneActStates.getFirst().frame() != 0
                || zoneActStates.getFirst().gameMode() == null
                || zoneActStates.getFirst().gameMode() != 12) {
            throw new IllegalArgumentException(
                    "standalone bonus trace must start in game_mode=12");
        }

        List<ZoneActState> departures = zoneActStates.stream()
                .filter(state -> state.frame() > 0)
                .filter(state -> state.gameMode() == null || state.gameMode() != 12)
                .toList();
        if (departures.isEmpty()) {
            throw new IllegalArgumentException(
                    "standalone bonus trace has no departure from game_mode=12");
        }
        if (departures.size() != 1) {
            throw new IllegalArgumentException(
                    "standalone bonus trace has ambiguous departures from game_mode=12: "
                            + departures.size());
        }

        int departureFrame = departures.getFirst().frame();
        return representedFrames.stream()
                .mapToInt(TraceFrame::frame)
                .filter(rawFrame -> rawFrame < departureFrame)
                .max()
                .orElseThrow(() -> new IllegalArgumentException(
                        "standalone bonus departure has no represented predecessor: raw_frame="
                                + departureFrame));
    }

    static TimingPrefixDecision decideTimingPrefixClose(
            int currentRawFrame, int lastBonusRawFrame, boolean ignoredLiveStageComplete) {
        if (currentRawFrame < lastBonusRawFrame) {
            return TimingPrefixDecision.CONTINUE;
        }
        if (currentRawFrame > lastBonusRawFrame) {
            throw new IllegalStateException(
                    "bonus replay advanced beyond semantic timing prefix boundary: "
                            + "current_raw_frame=" + currentRawFrame
                            + ", last_bonus_raw_frame=" + lastBonusRawFrame);
        }
        return TimingPrefixDecision.CLOSE_PREFIX;
    }

    @Override
    protected boolean validateSemanticTimingPrefix(
            int currentRawFrame, int lastPrefixRawFrame) {
        return decideTimingPrefixClose(
                currentRawFrame, lastPrefixRawFrame, false)
                == TimingPrefixDecision.CLOSE_PREFIX;
    }

    enum TimingPrefixDecision {
        CONTINUE,
        CLOSE_PREFIX
    }

    /**
     * Pure selection rule extracted for unit testing without an engine:
     * prefer the camera-focused sprite when present, else fall back to the
     * fixture's primary sprite.
     */
    static AbstractPlayableSprite selectComparedSprite(
            AbstractPlayableSprite focused, AbstractPlayableSprite fixtureSprite) {
        return focused != null ? focused : fixtureSprite;
    }
}
