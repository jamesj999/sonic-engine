package com.openggf;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.GameMode;
import com.openggf.game.GameStateManager;
import com.openggf.game.NoOpResultsScreen;
import com.openggf.game.ResultsScreen;
import com.openggf.game.SpecialStageAccessType;
import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.rewind.InMemoryKeyframeStore;
import com.openggf.game.rewind.InputSource;
import com.openggf.game.rewind.LiveRewindManager;
import com.openggf.game.rewind.RewindController;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.graphics.FadeManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

/**
 * A bonus-stage entry sets a {@code GameLoop}-local "transition pending"
 * flag and starts a fade, but {@code currentGameMode} stays {@code GameMode.LEVEL}
 * until the fade's completion callback runs. Live rewind's engagement gate in
 * {@code GameLoop.stepInternal()} historically only checked
 * {@code currentGameMode == GameMode.LEVEL}, so a rewind already held when the
 * transition fires could keep walking backward through pre-transition history,
 * restoring a {@link FadeManager} snapshot whose completion callback is not
 * rewind-restorable -- orphaning the pending flag forever (softlock: everything
 * frozen except the music). See ssentry-rewind-report.md.
 *
 * <p>Special-stage entry no longer has that window at all: every ROM writes the
 * game mode in the level-side object tick with no fade of its own, so
 * {@code GameLoop.enterSpecialStage()} lands {@code GameMode.SPECIAL_STAGE}
 * within a single tick and the mode check alone covers it. What remains for the
 * special stage is the fade-with-pending-completion case below, which the
 * results card's act-advance path still produces.
 *
 * <p><strong>Known coverage gap:</strong> these tests prove the gate itself
 * (held rewind cleanly disengages once the pending flag is set) by reflectively
 * flipping {@code bonusStageTransitionPending} directly, rather than driving a
 * real transition all the way through its fade's completion callback to an
 * actual mode landing. That end-to-end path was attempted and
 * found infeasible in this lightweight fixture: {@code GameLoop.step()} reaches
 * {@code Camera.updatePosition()} inside the normal (non-frozen) gameplay tick,
 * which NPEs on a null focused sprite because no level/player is loaded here (this
 * fixture only calls {@code TestEnvironment.configureGameModuleFixture}, not a real
 * zone/act load). Verifying "the transition still completes once rewind is
 * correctly blocked" end-to-end would require the heavier level-loading machinery
 * used by trace-replay/{@code HeadlessTestFixture}-style tests (real ROM, real
 * zone/act, spawned player sprite) -- tracked as an open follow-up in
 * {@code docs/architecture/plans/s1-bug-batch-ledger-2026-07-05.md}, not attempted here.
 */
class TestGameLoopSpecialStageRewindGate {

    private SonicConfigurationService config;
    private GameLoop loop;
    private InputHandler input;
    private FadeManager fadeManager;

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        fadeManager = SessionManager.getCurrentGameplayMode().getFadeManager();
        input = new InputHandler();
        loop = new GameLoop(input);
    }

    @AfterEach
    void tearDown() {
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
        SessionManager.clear();
    }

    /**
     * Companion case for the S3K/S2-shared bonus-stage twin of the same bug
     * ({@code GameLoop.enterBonusStage}, same pending-flag/fade shape). Cheap to
     * cover here because {@code bonusStageTransitionPending} is a plain
     * {@code GameLoop} field usable under the same Sonic2 fixture -- no actual
     * S3K module/bonus-stage content is required to exercise the gate.
     */
    @Test
    void heldRewindDisengagesWhenBonusStageTransitionBecomesPending() throws Exception {
        assertHeldRewindDisengagesWhenPending("bonusStageTransitionPending");
    }

    /**
     * Wave 3, Fix 3: an object-owned transition fade
     * ({@code Sonic1ResultsScreenObjectInstance.triggerFadeToBlack()}, the results card's
     * act-advance path) starts its fade directly from object code with its OWN completion
     * callback, and sets no {@code GameLoop} pending flag at all. So unlike the case
     * above, this gap is NOT reproducible by flipping a {@code GameLoop} field -- it requires a real
     * {@link FadeManager} fade with a live completion callback in flight, which is exactly what
     * {@link FadeManager#hasPendingCompletion()} (folded into
     * {@code GameLoop.isRewindBlocked()}, NOT into {@code isNonRewindableTransitionPending()} --
     * see the companion test below) now detects game-agnostically instead of requiring every such
     * object-owned fade to separately thread a dedicated pending flag through {@code GameLoop}.
     * Per the same fixture limitation documented in this class's javadoc, this drives a real
     * {@code FadeManager} fade rather than the full {@code Sonic1ResultsScreenObjectInstance}
     * object/results-screen machinery.
     *
     * <p><strong>Additional fixture limitation (post-review split):</strong> unlike the case
     * above, a pending-completion fade correctly does NOT freeze the gameplay-tick block
     * (see the companion test below and {@code isRewindBlocked()}'s javadoc) -- so once rewind
     * is rejected on the second frame, {@code stepInternal()} now falls through into a REAL,
     * unfrozen gameplay tick, which this lightweight fixture cannot support (no level/player
     * loaded; same {@code Camera.updatePosition()} NPE this class's top javadoc documents for a
     * full {@code enterSpecialStage()} drive). So the disengagement check below calls
     * {@code LiveRewindManager.handleRealtimeRewindInput(...)} directly with the real,
     * reflectively-read {@code GameLoop.isRewindBlocked()} value, rather than a second
     * {@code loop.step()} -- still exercising the real gate method with the real composite
     * predicate, just without driving {@code GameLoop}'s own orchestration around it.
     */
    @Test
    void heldRewindDisengagesWhileAFadeHasAPendingCompletionCallback() throws Exception {
        LiveRewindManager liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
        RewindController controller = new RewindController(
                new RewindRegistry(), new InMemoryKeyframeStore(), new FakeInputSource(20),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME, 2);
        for (int i = 0; i < 5; i++) {
            controller.recordExternalStep();
        }
        installTestController(liveRewindManager, controller);

        int rewindKey = config.getInt(SonicConfiguration.LIVE_REWIND_KEY);
        input.handleKeyEvent(rewindKey, GLFW_PRESS);

        loop.step();
        assertEquals(4, controller.currentFrame(),
                "rewind should engage normally while genuinely rewindable (no fade pending)");
        assertTrue((boolean) getField(liveRewindManager, "rewinding"));
        assertTrue(fadeManager.isReversePresentationActive());

        // Mirrors Sonic1ResultsScreenObjectInstance.triggerFadeToWhiteForSpecialStage(): object
        // code starts a fade with its OWN completion callback, with no GameLoop pending flag set.
        fadeManager.startFadeToWhite(() -> { });
        assertTrue(fadeManager.hasPendingCompletion(),
                "precondition: the fade must have a live, not-yet-run completion callback");

        boolean rewindBlocked = (boolean) invokeNoArg(loop, "isRewindBlocked");
        assertTrue(rewindBlocked, "precondition: the pending-completion fade must block rewind");
        boolean engaged = liveRewindManager.handleRealtimeRewindInput(
                com.openggf.game.GameMode.LEVEL, rewindBlocked, input);

        assertFalse(engaged, "rewind must reject engagement while a fade has a pending completion "
                + "callback, not keep walking backward through pre-fade history and risk restoring "
                + "a FadeManager snapshot whose callback is not rewind-restorable");
        assertFalse((boolean) getField(liveRewindManager, "rewinding"));
        assertEquals(4, controller.currentFrame(),
                "no further backward stepping should happen once the pending fade preempts rewind");
        assertFalse(fadeManager.isReversePresentationActive(),
                "the reverse-presentation fade overlay must end when rewind is preempted mid-hold");
    }

    /**
     * Reviewer follow-up (post-Wave-3): {@code hasPendingCompletion()} must NEVER be folded into
     * {@code GameLoop.isNonRewindableTransitionPending()} itself, because that predicate ALSO
     * drives the gameplay-tick freeze block -- unlike a special/bonus/ending/zone-act transition,
     * an ordinary callback-bearing fade (death respawn, act-complete, the giant-ring entry here)
     * does not freeze ROM gameplay; objects keep ticking underneath the cosmetic fade overlay.
     * Only rewind ENGAGEMENT may be rejected during such a fade, via the separate
     * {@code isRewindBlocked()} superset. This asserts both predicates directly (reflectively,
     * since both are private and this fixture cannot drive a real gameplay tick to observe the
     * freeze block's effect end-to-end -- see this class's documented NPE limitation) while a
     * pending-completion fade is active and none of the four transition flags are set: the
     * four-flag predicate must read {@code false} (gameplay not frozen) while the rewind-block
     * superset reads {@code true} (rewind still rejected).
     */
    @Test
    void fadeWithPendingCompletionDoesNotFreezeGameplayButDoesBlockRewind() throws Exception {
        assertFalse((boolean) invokeNoArg(loop, "isNonRewindableTransitionPending"),
                "precondition: no transition flag should be pending yet");
        assertFalse((boolean) invokeNoArg(loop, "isRewindBlocked"),
                "precondition: rewind should not be blocked yet (no fade, no transition)");

        // Mirrors Sonic1ResultsScreenObjectInstance.triggerFadeToWhiteForSpecialStage() /
        // GameLoop.startRespawnFade(): a fade with a live completion callback and NONE of the
        // four GameLoop transition flags set.
        fadeManager.startFadeToWhite(() -> { });
        assertTrue(fadeManager.hasPendingCompletion(), "precondition: fade must have a pending callback");

        assertFalse((boolean) invokeNoArg(loop, "isNonRewindableTransitionPending"),
                "a pending-completion fade must NOT freeze ordinary gameplay ticks -- ROM keeps "
                        + "ticking objects underneath a cosmetic fade overlay (this predicate also "
                        + "drives the gameplay-tick freeze block, so it must stay scoped to only "
                        + "the four legitimate transition flags)");
        assertTrue((boolean) invokeNoArg(loop, "isRewindBlocked"),
                "rewind engagement must still be rejected while the fade has a pending completion "
                        + "callback, via the separate rewind-only superset predicate");
    }

    @Test
    void heldRewindEngagesWhileSupportedSpecialStageIsActive() throws Exception {
        setField(loop, "activeSpecialStageProvider", new RewindableSpecialStageProvider());
        loop.changeGameModeWithoutRewindBoundary(GameMode.SPECIAL_STAGE);

        LiveRewindManager liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
        RewindController controller = new RewindController(
                new RewindRegistry(), new InMemoryKeyframeStore(), new FakeInputSource(20),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME, 2);
        for (int i = 0; i < 5; i++) {
            controller.recordExternalStep();
        }
        installTestController(liveRewindManager, controller, "SPECIAL_STAGE");

        input.handleKeyEvent(config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_PRESS);

        loop.step();

        assertEquals(4, controller.currentFrame(),
                "top-level live rewind engagement should run while a rewind-supported special stage is active");
        assertTrue((boolean) getField(liveRewindManager, "rewinding"));
    }

    private static Object invokeNoArg(Object target, String methodName) throws Exception {
        Method m = target.getClass().getDeclaredMethod(methodName);
        m.setAccessible(true);
        return m.invoke(target);
    }

    private void assertHeldRewindDisengagesWhenPending(String pendingFlagField) throws Exception {
        LiveRewindManager liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
        RewindController controller = new RewindController(
                new RewindRegistry(), new InMemoryKeyframeStore(), new FakeInputSource(20),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME, 2);
        for (int i = 0; i < 5; i++) {
            controller.recordExternalStep();
        }
        installTestController(liveRewindManager, controller);

        int rewindKey = config.getInt(SonicConfiguration.LIVE_REWIND_KEY);
        input.handleKeyEvent(rewindKey, GLFW_PRESS);

        loop.step();
        assertEquals(4, controller.currentFrame(),
                "rewind should engage normally while genuinely rewindable (no transition pending)");
        assertTrue((boolean) getField(liveRewindManager, "rewinding"));
        assertTrue(fadeManager.isReversePresentationActive());

        // The transition fires mid-hold: currentGameMode is still LEVEL (the mode
        // only flips once the fade-to-white/black completion callback runs), but
        // the level is no longer in a rewindable sub-state.
        setField(loop, pendingFlagField, true);

        loop.step();

        assertFalse((boolean) getField(liveRewindManager, "rewinding"),
                "held rewind must cleanly disengage once a transition is pending, not keep walking "
                        + "backward through pre-transition history");
        assertEquals(4, controller.currentFrame(),
                "no further backward stepping should happen once the transition preempts rewind");
        assertFalse(fadeManager.isReversePresentationActive(),
                "the reverse-presentation fade overlay must end when rewind is preempted mid-hold");
    }

    private static void installTestController(LiveRewindManager manager, RewindController controller) throws Exception {
        installTestController(manager, controller, "LEVEL_FRAME");
    }

    private static void installTestController(LiveRewindManager manager, RewindController controller,
                                              String stepperKind) throws Exception {
        setField(manager, "installedGameplayMode", SessionManager.getCurrentGameplayMode());
        setInstalledStepperKind(manager, stepperKind);
        setField(manager, "inputSource", new com.openggf.game.rewind.LiveRewindInputSource());
        setField(manager, "rewindController", controller);
        // RewindSpeedController is package-private (com.openggf.game.rewind); reach its
        // static factory reflectively rather than widening its visibility for this test.
        Class<?> speedControllerClass = Class.forName("com.openggf.game.rewind.RewindSpeedController");
        Method fromConfig = speedControllerClass.getDeclaredMethod("fromConfig", SonicConfigurationService.class);
        fromConfig.setAccessible(true);
        setField(manager, "speedController", fromConfig.invoke(null, SonicConfigurationService.getInstance()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setInstalledStepperKind(LiveRewindManager manager, String kindName) throws Exception {
        Class<?> kindClass = Class.forName("com.openggf.game.rewind.LiveRewindManager$StepperKind");
        Object kind = Enum.valueOf((Class<? extends Enum>) kindClass.asSubclass(Enum.class), kindName);
        setField(manager, "installedStepperKind", kind);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FakeInputSource implements InputSource {
        private final int frames;

        FakeInputSource(int frames) {
            this.frames = frames;
        }

        @Override
        public int frameCount() {
            return frames;
        }

        @Override
        public Bk2FrameInput read(int frame) {
            return new Bk2FrameInput(frame, 0, 0, false, "fake");
        }
    }

    private static final class RewindableSpecialStageProvider implements SpecialStageProvider {
        @Override
        public boolean supportsRewind() {
            return true;
        }

        @Override
        public Optional<RewindSnapshottable<?>> rewindAdapter() {
            return Optional.empty();
        }

        @Override
        public boolean hasSpecialStages() {
            return true;
        }

        @Override
        public SpecialStageAccessType getAccessType() {
            return SpecialStageAccessType.GIANT_RING;
        }

        @Override
        public void initializeStage(int stageIndex) throws IOException {
        }

        @Override
        public int getCurrentStage() {
            return 0;
        }

        @Override
        public boolean isEmeraldCollected() {
            return false;
        }

        @Override
        public int getEmeraldIndex() {
            return -1;
        }

        @Override
        public int getRingsCollected() {
            return 0;
        }

        @Override
        public void setEmeraldCollected(boolean collected) {
        }

        @Override
        public boolean isSpriteDebugMode() {
            return false;
        }

        @Override
        public void toggleSpriteDebugMode() {
        }

        @Override
        public void cyclePlaneDebugMode() {
        }

        @Override
        public SpecialStageDebugProvider getDebugProvider() {
            return null;
        }

        @Override
        public boolean isAlignmentTestMode() {
            return false;
        }

        @Override
        public void toggleAlignmentTestMode() {
        }

        @Override
        public void adjustAlignmentOffset(int delta) {
        }

        @Override
        public void adjustAlignmentSpeed(double delta) {
        }

        @Override
        public void toggleAlignmentStepMode() {
        }

        @Override
        public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {
        }

        @Override
        public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) {
        }

        @Override
        public void setLagCompensation(double factor) {
        }

        @Override
        public ResultsScreen createResultsScreen(int ringsCollected, boolean gotEmerald,
                                                 int stageIndex, int totalEmeraldCount) {
            return NoOpResultsScreen.INSTANCE;
        }

        @Override
        public void initialize() throws IOException {
        }

        @Override
        public void update() {
        }

        @Override
        public void draw() {
        }

        @Override
        public void handleInput(int heldButtons, int pressedButtons) {
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void reset() {
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public int consumeStageIndexForEntry(GameStateManager gameState) {
            return 0;
        }
    }
}
