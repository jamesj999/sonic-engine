package com.openggf.tests;

import com.openggf.LevelFrameResult;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tools.RecordingFrameDriver;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;
import com.openggf.game.GameServices;
import com.openggf.audio.presentation.PresentationMode;

/**
 * Headless test runner that simulates the full game loop update cycle.
 * Delegates to the shared engine-side {@link RecordingFrameDriver}, which
 * handles frame-level ordering via {@code LevelFrameStep} and team updates via
 * {@code SpriteManager.update(...)}, ensuring the test harness, the trace-replay
 * tests, and the headless trace-capture tool cannot drift from production
 * team behavior (or from each other).
 *
 * <p>Usage example:
 * <pre>
 * HeadlessTestRunner runner = new HeadlessTestRunner(sprite);
 * runner.stepFrame(false, false, true, false, false); // Walk left
 * </pre>
 *
 * <p>Important setup requirements for tests using this class:
 * <ul>
 *   <li>Reset test state: TestEnvironment.resetAll()</li>
 *   <li>Initialize headless graphics: GameServices.graphics().initHeadless()</li>
 *   <li>Load level: GameServices.level().loadZoneAndAct(zone, act)</li>
 *   <li>Fix GroundSensor: GroundSensor.setLevelManager(GameServices.level())</li>
 *   <li>Update camera position: GameServices.camera().updatePosition(true)</li>
 * </ul>
 */
public class HeadlessTestRunner {

    /**
     * Upper bound on consecutive SETUP_ONLY rows before a step is treated as divergent.
     * A structural boundary only ever costs a handful of rows; an unbounded retry turns a
     * frame-driver admission regression into a hung fork rather than a failing test.
     */
    private static final int SETUP_ONLY_RETRY_LIMIT = 4096;

    private final RecordingFrameDriver driver;

    /**
     * Creates a new HeadlessTestRunner for the given sprite.
     *
     * @param sprite The playable sprite to run physics updates on
     */
    public HeadlessTestRunner(AbstractPlayableSprite sprite) {
        this.driver = new RecordingFrameDriver(sprite);
    }

    /**
     * Steps one frame with the given input state.
     * Frame-level ordering is defined by {@code LevelFrameStep#execute};
     * playable/team ordering is defined by {@code SpriteManager.update(...)}.
     */
    public LevelFrameResult stepFrame(
            boolean up, boolean down, boolean left, boolean right, boolean jump) {
        return retrySetupOnly(() ->
                driver.stepFrame(up, down, left, right, jump));
    }

    /**
     * Convenience overload that also carries P1 Start, used by tests exercising
     * ROM in-game pause.
     */
    public LevelFrameResult stepFrame(
            boolean up, boolean down, boolean left, boolean right, boolean jump,
            boolean p1Start) {
        return retrySetupOnly(() ->
                driver.stepFrame(up, down, left, right, jump, p1Start));
    }

    /**
     * Steps one frame with both P1 and P2 input.
     */
    public LevelFrameResult stepFrame(
            boolean up, boolean down, boolean left, boolean right, boolean jump,
            int p2Mask, boolean p2Start) {
        return retrySetupOnly(() ->
                driver.stepFrame(up, down, left, right, jump, p2Mask, p2Start));
    }

    /**
     * Full-input step overload, including P1 Start so ROM in-game pause can be
     * exercised from both unit tests and BK2 replay.
     */
    public LevelFrameResult stepFrame(
            boolean up, boolean down, boolean left, boolean right, boolean jump,
            int p2Mask, boolean p2Start, boolean p1Start) {
        return retrySetupOnly(() -> driver.stepFrame(
                up, down, left, right, jump, p2Mask, p2Start, p1Start));
    }

    /**
     * Primes the synthetic input handler to the state at an already-restored
     * playback frame.
     */
    public void primeInputState(Bk2FrameInput frameInput) {
        driver.primeInputState(frameInput);
    }

    /**
     * Steps multiple frames with no input (idle).
     */
    public void stepIdleFrames(int frames) {
        for (int frame = 0; frame < frames; frame++) {
            stepFrame(false, false, false, false, false);
        }
    }

    /** Gets the current frame counter. */
    public int getFrameCounter() {
        return driver.getFrameCounter();
    }

    /** Gets the sprite being controlled. */
    public AbstractPlayableSprite getSprite() {
        return driver.getSprite();
    }

    // ---- BK2 recording playback ----

    /**
     * Sets the BK2 movie for recording playback.
     *
     * @param movie          The parsed BK2 movie
     * @param bk2FrameOffset The 0-based emulation frame index where trace recording began
     *                       (from BizHawk's emu.framecount() in the Lua script).
     */
    public void setBk2Movie(Bk2Movie movie, int bk2FrameOffset) {
        driver.setBk2Movie(movie, bk2FrameOffset);
    }

    public void installHardwareTimingReplayObserver(
            TraceHardwareTimingBoundaryObserver observer) {
        driver.installHardwareTimingReplayObserver(observer);
    }

    public void clearHardwareTimingReplayObserver() {
        driver.clearHardwareTimingReplayObserver();
    }

    public void beginTraceRow(int traceIndex, int rawFrame) {
        driver.beginTraceRow(traceIndex, rawFrame);
    }

    public void enterHardwareTimingGap() {
        driver.enterHardwareTimingGap();
    }

    /**
     * Steps one frame using input from the BK2 movie recording.
     *
     * @return The raw input mask used (for trace input validation)
     */
    public int stepFrameFromRecording() {
        int input;
        int setupRetries = 0;
        do {
            if (setupRetries++ >= SETUP_ONLY_RETRY_LIMIT) {
                throw new IllegalStateException(
                        "stepFrameFromRecording kept returning SETUP_ONLY for "
                                + SETUP_ONLY_RETRY_LIMIT + " retries; the frame driver"
                                + " is no longer admitting a gameplay row");
            }
            input = driver.stepFrameFromRecording();
        } while (driver.getLastFrameResult() == LevelFrameResult.SETUP_ONLY);
        presentIfAdmitted(driver.getLastFrameResult());
        return input;
    }

    /**
     * Consumes the current BK2 row for validation but drives gameplay with the
     * previous BK2 row.
     */
    public int stepFrameFromRecordingUsingPreviousInput() {
        int input;
        int setupRetries = 0;
        do {
            if (setupRetries++ >= SETUP_ONLY_RETRY_LIMIT) {
                throw new IllegalStateException(
                        "stepFrameFromRecordingUsingPreviousInput kept returning SETUP_ONLY for "
                                + SETUP_ONLY_RETRY_LIMIT + " retries; the frame driver"
                                + " is no longer admitting a gameplay row");
            }
            input = driver.stepFrameFromRecordingUsingPreviousInput();
        } while (driver.getLastFrameResult() == LevelFrameResult.SETUP_ONLY);
        presentIfAdmitted(driver.getLastFrameResult());
        return input;
    }

    private LevelFrameResult presentIfAdmitted(LevelFrameResult result) {
        if (result != LevelFrameResult.SETUP_ONLY) {
            presentOuterFrame();
        }
        return result;
    }

    private LevelFrameResult retrySetupOnly(
            java.util.function.Supplier<LevelFrameResult> step) {
        LevelFrameResult result;
        int setupRetries = 0;
        do {
            if (setupRetries++ >= SETUP_ONLY_RETRY_LIMIT) {
                throw new IllegalStateException(
                        "a stepped frame kept returning SETUP_ONLY for "
                                + SETUP_ONLY_RETRY_LIMIT + " retries; the frame driver"
                                + " is no longer admitting a gameplay row");
            }
            result = step.get();
        } while (result == LevelFrameResult.SETUP_ONLY);
        return presentIfAdmitted(result);
    }

    static boolean hasNewP1ActionPressForLogicalInput(Bk2Movie movie, int bk2Index, boolean controlLocked) {
        return RecordingFrameDriver.hasNewP1ActionPressForLogicalInput(movie, bk2Index, controlLocked);
    }

    static boolean hasNewP1ActionPress(Bk2Movie movie, int bk2Index) {
        return RecordingFrameDriver.hasNewP1ActionPress(movie, bk2Index);
    }

    /**
     * Advances the BK2 movie by one frame without processing physics.
     *
     * @return The raw input mask for that frame (for trace input validation)
     */
    public int skipFrameFromRecording() {
        return driver.skipFrameFromRecording();
    }

    public void advancePlayableAnimationsOnly() {
        driver.advancePlayableAnimationsOnly();
    }

    public void advancePlayableFixedSlotsOnly() {
        driver.advancePlayableFixedSlotsOnly();
    }

    public void suppressFirstSidekickAnimationOnce() {
        driver.suppressFirstSidekickAnimationOnce();
    }

    /**
     * Consumes one BK2 input frame without mutating gameplay or timing counters.
     */
    public int consumeRecordingFrameInputOnly() {
        return driver.consumeRecordingFrameInputOnly();
    }

    /**
     * Returns the BK2 input mask at the given offset from the current cursor
     * without advancing the cursor or stepping gameplay.
     */
    public int peekRecordingInputAt(int offset) {
        return driver.peekRecordingInputAt(offset);
    }

    /**
     * Returns the number of BK2 recording frames remaining.
     */
    public int getRecordingFramesRemaining() {
        return driver.getRecordingFramesRemaining();
    }

    /**
     * Advances the BK2 cursor without mutating gameplay state.
     */
    public void advanceRecordingCursor(int frameCount) {
        driver.advanceRecordingCursor(frameCount);
    }

    private static void presentOuterFrame() {
        GameServices.audio().presentFrame(PresentationMode.FORWARD);
    }
}
