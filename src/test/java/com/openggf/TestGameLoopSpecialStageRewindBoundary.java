package com.openggf;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.GameStateManager;
import com.openggf.game.NoOpResultsScreen;
import com.openggf.game.ResultsScreen;
import com.openggf.game.SpecialStageAccessType;
import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.rewind.snapshot.CameraSnapshot;
import com.openggf.graphics.FadeManagerSnapshot;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.KeyframeStore;
import com.openggf.game.rewind.LiveRewindManager;
import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.rewind.RewindController;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.graphics.FadeManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestGameLoopSpecialStageRewindBoundary {
    private SonicConfigurationService config;
    private GameplayModeContext context;
    private GameLoop loop;
    private LiveRewindManager liveRewindManager;
    private List<RewindBoundary> boundaries;

    @BeforeEach
    void setUp() throws Exception {
        setActiveTraceSession(null);
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        context = SessionManager.getCurrentGameplayMode();
        loop = new GameLoop(new InputHandler());
        liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
        boundaries = new ArrayList<>();
        loop.installLiveRewindBoundaryReporter(boundary -> {
            boundaries.add(boundary);
            liveRewindManager.markBoundary(boundary);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        setActiveTraceSession(null);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
        SessionManager.clear();
    }

    private static void setActiveTraceSession(TraceSessionLauncher session)
            throws Exception {
        Field field = TraceSessionLauncher.class.getDeclaredField("activeSession");
        field.setAccessible(true);
        field.set(null, session);
    }

    @Test
    void supportedEntryFrameZeroCapturesSpecialStageSetupAndRuntimeAdapter() throws Exception {
        RewindableProvider provider = new RewindableProvider();
        context.getCamera().setX((short) 320);
        context.getCamera().setY((short) 224);

        doEnterSpecialStage(provider);

        assertEquals(List.of(
                RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE,
                RewindBoundary.MODE_ENTER_REWINDABLE), boundaries);
        CompositeSnapshot snapshot = frameZeroSnapshot();
        assertTrue(snapshot.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY),
                "supported special-stage entry must install the provider-owned adapter before the frame-zero rewind snapshot");
        CameraSnapshot camera = (CameraSnapshot) snapshot.get("camera");
        assertEquals(0, camera.x(), "special-stage frame zero must capture camera reset to screen-space origin");
        assertEquals(0, camera.y(), "special-stage frame zero must capture camera reset to screen-space origin");
        FadeManagerSnapshot fade = (FadeManagerSnapshot) snapshot.get("fademanager");
        assertEquals(FadeManager.FadeState.FADING_FROM_WHITE, fade.state(),
                "special-stage frame zero must capture the reveal fade after entry setup, not a pre-entry level fade");
    }

    @Test
    void supportedExitClearsLiveRewindControllerInputAndInstalledContext() throws Exception {
        doEnterSpecialStage(new RewindableProvider());
        assertNotNull(context.getRewindController(), "precondition: supported special-stage entry installs live rewind");
        boundaries.clear();

        loop.changeGameModeForBoundary(GameMode.TITLE_SCREEN);

        assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries);
        assertNull(getField(liveRewindManager, "rewindController"));
        assertNull(getField(liveRewindManager, "inputSource"));
        assertNull(getField(liveRewindManager, "installedGameplayMode"));
    }

    @Test
    void directSupportedSpecialStageToLevelClearsAdapterBeforeEnteringLevelRewind() throws Exception {
        doEnterSpecialStage(new RewindableProvider());
        boundaries.clear();

        loop.changeGameModeForBoundary(GameMode.LEVEL);

        assertEquals(List.of(
                RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE,
                RewindBoundary.MODE_ENTER_REWINDABLE), boundaries);
        assertFalse(frameZeroSnapshot().containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY),
                "direct SPECIAL_STAGE -> LEVEL must clear the special-stage adapter before level frame-zero capture");
    }

    @Test
    void resultsExitCleanupDeregistersSpecialStageAdapterIdempotently() throws Exception {
        RewindableProvider provider = new RewindableProvider();
        context.registerSpecialStageAdapter(provider);
        assertTrue(context.getRewindRegistry().capture().containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY));
        setField(loop, "activeSpecialStageProvider", provider);
        loop.changeGameModeWithoutRewindBoundary(GameMode.SPECIAL_STAGE_RESULTS);

        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getCurrentLevel()).thenReturn(null);
        setField(loop, "levelManager", levelManager);

        doExitResultsScreen();
        completeActiveFade();
        doExitResultsScreen();

        assertFalse(context.getRewindRegistry().capture().containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY));
    }

    @Test
    void unsupportedSpecialStageEntryEmitsOnlyExitAndDoesNotInstallSpecialStageRewind() {
        setField(loop, "activeSpecialStageProvider", new UnsupportedProvider());

        loop.changeGameModeForBoundary(GameMode.SPECIAL_STAGE);

        assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries);
        assertFalse(context.getRewindRegistry().capture().containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY));
        assertNull(context.getRewindController());
    }

    private void doEnterSpecialStage(SpecialStageProvider provider) {
        loop.doEnterSpecialStage(provider, 0, false);
    }

    private void doExitResultsScreen() throws Exception {
        Method method = GameLoop.class.getDeclaredMethod("doExitResultsScreen");
        method.setAccessible(true);
        method.invoke(loop);
    }

    private void completeActiveFade() {
        for (int frame = 0; frame < 64 && context.getFadeManager().isActive(); frame++) {
            context.getFadeManager().update();
        }
        assertFalse(context.getFadeManager().isActive(), "test must release the first native exit fade");
    }

    private CompositeSnapshot frameZeroSnapshot() throws Exception {
        RewindController controller = context.getRewindController();
        assertNotNull(controller, "live rewind controller should be installed");
        KeyframeStore store = (KeyframeStore) getField(controller, "keyframes");
        return store.latestAtOrBefore(0).orElseThrow().snapshot();
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static class UnsupportedProvider implements SpecialStageProvider {
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

    private static final class RewindableProvider extends UnsupportedProvider {
        private final RewindSnapshottable<Integer> adapter = new RewindSnapshottable<>() {
            @Override
            public String key() {
                return SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY;
            }

            @Override
            public Integer capture() {
                return 7;
            }

            @Override
            public void restore(Integer snapshot) {
            }
        };

        @Override
        public boolean supportsRewind() {
            return true;
        }

        @Override
        public Optional<RewindSnapshottable<?>> rewindAdapter() {
            return Optional.of(adapter);
        }
    }
}
