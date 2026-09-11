package com.openggf;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.game.GameMode;
import com.openggf.game.GameStateManager;
import com.openggf.game.NoOpResultsScreen;
import com.openggf.game.ResultsScreen;
import com.openggf.game.SpecialStageAccessType;
import com.openggf.game.SpecialStageDebugCapabilities;
import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.rewind.LiveRewindManager;
import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.graphics.FadeManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F3;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F4;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F6;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F7;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F12;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_X;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

class TestGameLoopSpecialStageRewindDebugBoundary {
    private SonicConfigurationService config;
    private GameplayModeContext context;
    private GameLoop loop;
    private InputHandler input;
    private LiveRewindManager liveRewindManager;
    private List<RewindBoundary> boundaries;
    private List<String> order;

    @BeforeEach
    void setUp() throws Exception {
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, true);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        DebugOverlayManager.getInstance().resetState();
        context = SessionManager.getCurrentGameplayMode();
        input = new InputHandler();
        loop = new GameLoop(input);
        liveRewindManager = (LiveRewindManager) getField(loop, "liveRewindManager");
        boundaries = new ArrayList<>();
        order = new ArrayList<>();
        loop.installLiveRewindBoundaryReporter(boundary -> {
            boundaries.add(boundary);
            order.add("boundary");
            liveRewindManager.markBoundary(boundary);
        });
    }

    @AfterEach
    void tearDown() {
        DebugOverlayManager.getInstance().resetState();
        config.resetToDefaults();
        SessionManager.clear();
    }

    @Test
    void liveOnlySpecialStageDebugControlsMarkBoundaryBeforeMutatingAndSuppressRecord() throws Exception {
        for (Shortcut shortcut : shortcuts()) {
            resetPerShortcut();
            RecordingProvider provider = new RecordingProvider(order);
            shortcut.setup().apply(provider);
            activateSpecialStage(provider);

            input.handleKeyEvent(shortcut.key(), GLFW_PRESS);

            loop.step();

            assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries, shortcut.name());
            assertEquals("boundary", order.get(0), shortcut.name() + " must sever rewind before mutating live-only state");
            assertTrue(order.contains(shortcut.expectedAction()),
                    shortcut.name() + " should still run its live-only action after severing rewind");
            assertNull(context.getRewindController(),
                    shortcut.name() + " must suppress same-frame special-stage rewind recording");
        }
    }

    @Test
    void unavailableSpecialStageDebugCapabilitiesDoNotRouteKeysOrSeverRewind() throws Exception {
        RecordingProvider provider = new RecordingProvider(order);
        provider.capabilities = SpecialStageDebugCapabilities.NONE;
        activateSpecialStage(provider);

        input.handleKeyEvent(GLFW_KEY_X, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_Z, GLFW_PRESS);
        input.handleKeyEvent(config.getInt(SonicConfiguration.SPECIAL_STAGE_SPRITE_DEBUG_KEY), GLFW_PRESS);
        input.handleKeyEvent(config.getInt(SonicConfiguration.SPECIAL_STAGE_PLANE_DEBUG_KEY), GLFW_PRESS);
        input.handleKeyEvent(config.getInt(SonicConfiguration.DEBUG_MODE_KEY), GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_F4, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_F1, GLFW_PRESS);

        loop.step();

        assertTrue(boundaries.isEmpty(), "unavailable controls must not create a rewind boundary");
        assertFalse(order.contains("debugNextStage"));
        assertFalse(order.contains("debugToggleLayoutSet"));
        assertFalse(order.contains("toggleSpriteDebugMode"));
        assertFalse(order.contains("cyclePlaneDebugMode"));
        assertFalse(order.contains("toggleGameplayDebugMode"));
        assertFalse(order.contains("toggleAlignmentTestMode"));
        assertFalse(order.contains("toggleLagCompensationDisplay"));
    }

    @Test
    void unavailableProviderControlsLeaveGlobalOverlayBindingsActive() throws Exception {
        RecordingProvider provider = new RecordingProvider(order);
        provider.capabilities = SpecialStageDebugCapabilities.NONE;
        activateSpecialStage(provider);
        DebugOverlayManager overlays = (DebugOverlayManager) getField(loop, "debugOverlayManager");
        boolean overlayBefore = overlays.isEnabled(DebugOverlayToggle.OVERLAY);
        boolean playerPanelBefore = overlays.isEnabled(DebugOverlayToggle.PLAYER_PANEL);
        boolean sensorLabelsBefore = overlays.isEnabled(DebugOverlayToggle.SENSOR_LABELS);
        boolean artViewerBefore = overlays.isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER);

        input.handleKeyEvent(GLFW_KEY_F1, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_F3, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_F4, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_F12, GLFW_PRESS);

        loop.step();

        assertEquals(!overlayBefore, overlays.isEnabled(DebugOverlayToggle.OVERLAY));
        assertEquals(!playerPanelBefore, overlays.isEnabled(DebugOverlayToggle.PLAYER_PANEL));
        assertEquals(!sensorLabelsBefore, overlays.isEnabled(DebugOverlayToggle.SENSOR_LABELS));
        assertEquals(!artViewerBefore, overlays.isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER));
        assertTrue(boundaries.isEmpty(), "global overlay toggles must not create a stage rewind boundary");
        assertFalse(order.contains("toggleAlignmentTestMode"));
        assertFalse(order.contains("toggleLagCompensationDisplay"));
    }

    @Test
    void nullSpecialStageDebugCapabilitiesFailClosedLikeNone() throws Exception {
        RecordingProvider provider = new RecordingProvider(order);
        provider.capabilities = null;
        activateSpecialStage(provider);

        input.handleKeyEvent(GLFW_KEY_X, GLFW_PRESS);
        input.handleKeyEvent(config.getInt(SonicConfiguration.DEBUG_MODE_KEY), GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_F4, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_F1, GLFW_PRESS);

        loop.step();

        assertTrue(boundaries.isEmpty(), "null capabilities must fail closed without a rewind boundary");
        assertFalse(order.contains("debugNextStage"));
        assertFalse(order.contains("toggleGameplayDebugMode"));
        assertFalse(order.contains("toggleAlignmentTestMode"));
        assertFalse(order.contains("toggleLagCompensationDisplay"));
    }

    @Test
    void globalSpecialStageKeyMarksBoundaryOnceAndStillRunsSharedResultsTail() throws Exception {
        RecordingProvider provider = new RecordingProvider(order);
        activateSpecialStage(provider);
        forceFadeHoldWhite();

        input.handleKeyEvent(config.getInt(SonicConfiguration.SPECIAL_STAGE_KEY), GLFW_PRESS);

        loop.step();

        assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries);
        assertEquals("boundary", order.get(0));
        assertEquals(1, count(order, "reset"),
                "SPECIAL_STAGE_KEY should call the special-stage debug handler exactly once");
        assertEquals(GameMode.SPECIAL_STAGE_RESULTS, loop.getCurrentGameMode());
        assertEquals(1, (int) getField(loop, "resultsFrameCounter"),
                "the global key branch must not skip the shared results-mode step tail");
        assertNull(context.getRewindController());
    }

    @Test
    void rewindHeldWithLiveOnlyShortcutDoesNotEngageRewindOnThatFrame() throws Exception {
        RecordingProvider provider = new RecordingProvider(order);
        activateSpecialStage(provider);

        input.handleKeyEvent(GLFW_KEY_X, GLFW_PRESS);
        input.handleKeyEvent(config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_PRESS);

        loop.step();

        assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries);
        assertFalse((boolean) getField(liveRewindManager, "rewinding"));
        assertNull(context.getRewindController(),
                "a live-only shortcut frame must reject rewind engagement even when rewind is held");
    }

    @Test
    void deterministicLagModelHasNoF6F7RuntimeToggle() throws Exception {
        for (int key : List.of(GLFW_KEY_F6, GLFW_KEY_F7)) {
            resetPerShortcut();
            RecordingProvider provider = new RecordingProvider(order);
            provider.lagDisplay = true;
            activateSpecialStage(provider);
            DebugOverlayManager overlays = (DebugOverlayManager) getField(loop, "debugOverlayManager");
            DebugOverlayToggle toggle = key == GLFW_KEY_F6
                    ? DebugOverlayToggle.CAMERA_BOUNDS
                    : DebugOverlayToggle.PLAYER_BOUNDS;
            boolean overlayBefore = overlays.isEnabled(toggle);

            input.handleKeyEvent(key, GLFW_PRESS);
            loop.step();

            assertFalse(order.contains("adjustLagCompensation"),
                    "the fixed lag model must not expose an F6/F7 runtime toggle");
            assertTrue(boundaries.isEmpty(),
                    "a removed lag toggle must not sever the rewind timeline");
            assertEquals(!overlayBefore, overlays.isEnabled(toggle),
                    "F6/F7 must retain their general debug-overlay behavior");
        }
    }

    private List<Shortcut> shortcuts() {
        int completeKey = config.getInt(SonicConfiguration.SPECIAL_STAGE_COMPLETE_KEY);
        int failKey = config.getInt(SonicConfiguration.SPECIAL_STAGE_FAIL_KEY);
        int spriteDebugKey = config.getInt(SonicConfiguration.SPECIAL_STAGE_SPRITE_DEBUG_KEY);
        int planeDebugKey = config.getInt(SonicConfiguration.SPECIAL_STAGE_PLANE_DEBUG_KEY);
        int gameplayDebugKey = config.getInt(SonicConfiguration.DEBUG_MODE_KEY);
        return List.of(
                new Shortcut("X next-stage debug", GLFW_KEY_X, p -> { }, "debugNextStage"),
                new Shortcut("Z layout-set debug", GLFW_KEY_Z, p -> { }, "debugToggleLayoutSet"),
                new Shortcut("complete-key debug", completeKey, p -> { }, "setEmeraldCollected"),
                new Shortcut("fail-key debug", failKey, p -> { }, "getCurrentStage"),
                new Shortcut("sprite-debug toggle", spriteDebugKey, p -> { }, "toggleSpriteDebugMode"),
                new Shortcut("plane-debug toggle", planeDebugKey, p -> { }, "cyclePlaneDebugMode"),
                new Shortcut("gameplay-debug toggle", gameplayDebugKey, p -> { }, "toggleGameplayDebugMode"),
                new Shortcut("F4 alignment toggle", GLFW_KEY_F4, p -> { }, "toggleAlignmentTestMode"),
                new Shortcut("F1 lag display toggle", GLFW_KEY_F1, p -> { }, "toggleLagCompensationDisplay"),
                new Shortcut("sprite-debug right", config.getInt(SonicConfiguration.RIGHT),
                        p -> p.spriteDebugMode = true, "nextPage"),
                new Shortcut("sprite-debug left", config.getInt(SonicConfiguration.LEFT),
                        p -> p.spriteDebugMode = true, "previousPage"),
                new Shortcut("sprite-debug down", config.getInt(SonicConfiguration.DOWN),
                        p -> p.spriteDebugMode = true, "nextSet"),
                new Shortcut("sprite-debug up", config.getInt(SonicConfiguration.UP),
                        p -> p.spriteDebugMode = true, "previousSet"),
                new Shortcut("alignment left", config.getInt(SonicConfiguration.LEFT),
                        p -> p.alignmentMode = true, "adjustAlignmentOffset"),
                new Shortcut("alignment right", config.getInt(SonicConfiguration.RIGHT),
                        p -> p.alignmentMode = true, "adjustAlignmentOffset"),
                new Shortcut("alignment up", config.getInt(SonicConfiguration.UP),
                        p -> p.alignmentMode = true, "adjustAlignmentSpeed"),
                new Shortcut("alignment down", config.getInt(SonicConfiguration.DOWN),
                        p -> p.alignmentMode = true, "adjustAlignmentSpeed"),
                new Shortcut("alignment space", GLFW_KEY_SPACE, p -> p.alignmentMode = true, "toggleAlignmentStepMode"));
    }

    private void resetPerShortcut() {
        boundaries.clear();
        order.clear();
        input = new InputHandler();
        loop.setInputHandler(input);
        loop.changeGameModeWithoutRewindBoundary(GameMode.LEVEL);
        liveRewindManager.markBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);
        context.deregisterSpecialStageAdapter();
    }

    private void activateSpecialStage(RecordingProvider provider) {
        context.registerSpecialStageAdapter(provider);
        setField(loop, "activeSpecialStageProvider", provider);
        loop.changeGameModeWithoutRewindBoundary(GameMode.SPECIAL_STAGE);
    }

    private void forceFadeHoldWhite() {
        FadeManager fadeManager = context.getFadeManager();
        fadeManager.startFadeToWhite(null, 999);
        for (int i = 0; i < 21; i++) {
            fadeManager.update();
        }
        assertEquals(FadeManager.FadeState.HOLD_WHITE, fadeManager.getState());
    }

    private static int count(List<String> values, String target) {
        int count = 0;
        for (String value : values) {
            if (target.equals(value)) {
                count++;
            }
        }
        return count;
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

    private record Shortcut(String name, int key, ProviderSetup setup, String expectedAction) {
    }

    @FunctionalInterface
    private interface ProviderSetup {
        void apply(RecordingProvider provider);
    }

    private static final class RecordingDebugProvider implements SpecialStageDebugProvider {
        private final List<String> order;

        private RecordingDebugProvider(List<String> order) {
            this.order = order;
        }

        @Override
        public void draw() {
        }

        @Override
        public void nextPage() {
            order.add("nextPage");
        }

        @Override
        public void previousPage() {
            order.add("previousPage");
        }

        @Override
        public void nextSet() {
            order.add("nextSet");
        }

        @Override
        public void previousSet() {
            order.add("previousSet");
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void toggle() {
        }
    }

    private static final class RecordingProvider implements SpecialStageProvider {
        private final List<String> order;
        private final RecordingDebugProvider debugProvider;
        private boolean spriteDebugMode;
        private boolean alignmentMode;
        private boolean lagDisplay;
        private SpecialStageDebugCapabilities capabilities = SpecialStageDebugCapabilities.LEGACY;

        private RecordingProvider(List<String> order) {
            this.order = order;
            this.debugProvider = new RecordingDebugProvider(order);
        }

        @Override
        public SpecialStageDebugCapabilities debugCapabilities() {
            return capabilities;
        }

        @Override
        public boolean supportsRewind() {
            return true;
        }

        @Override
        public Optional<RewindSnapshottable<?>> rewindAdapter() {
            return Optional.of(new RewindSnapshottable<Integer>() {
                @Override
                public String key() {
                    return SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY;
                }

                @Override
                public Integer capture() {
                    return 1;
                }

                @Override
                public void restore(Integer snapshot) {
                }
            });
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
            order.add("getCurrentStage");
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
            order.add("setEmeraldCollected");
        }

        @Override
        public void debugNextStage() {
            order.add("debugNextStage");
        }

        @Override
        public void debugToggleLayoutSet() {
            order.add("debugToggleLayoutSet");
        }

        @Override
        public boolean isGameplayDebugMode() {
            return false;
        }

        @Override
        public void toggleGameplayDebugMode() {
            order.add("toggleGameplayDebugMode");
        }

        @Override
        public boolean isSpriteDebugMode() {
            return spriteDebugMode;
        }

        @Override
        public void toggleSpriteDebugMode() {
            order.add("toggleSpriteDebugMode");
            spriteDebugMode = !spriteDebugMode;
        }

        @Override
        public void cyclePlaneDebugMode() {
            order.add("cyclePlaneDebugMode");
        }

        @Override
        public SpecialStageDebugProvider getDebugProvider() {
            return debugProvider;
        }

        @Override
        public boolean isAlignmentTestMode() {
            return alignmentMode;
        }

        @Override
        public void toggleAlignmentTestMode() {
            order.add("toggleAlignmentTestMode");
            alignmentMode = !alignmentMode;
        }

        @Override
        public void adjustAlignmentOffset(int delta) {
            order.add("adjustAlignmentOffset");
        }

        @Override
        public void adjustAlignmentSpeed(double delta) {
            order.add("adjustAlignmentSpeed");
        }

        @Override
        public void toggleAlignmentStepMode() {
            order.add("toggleAlignmentStepMode");
        }

        @Override
        public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {
        }

        @Override
        public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) {
        }

        @Override
        public boolean isLagCompensationDisplayEnabled() {
            return lagDisplay;
        }

        @Override
        public void toggleLagCompensationDisplay() {
            order.add("toggleLagCompensationDisplay");
            lagDisplay = !lagDisplay;
        }

        @Override
        public void setLagCompensation(double factor) {
        }

        @Override
        public ResultsScreen createResultsScreen(int ringsCollected, boolean gotEmerald,
                                                 int stageIndex, int totalEmeraldCount) {
            order.add("createResultsScreen");
            return NoOpResultsScreen.INSTANCE;
        }

        @Override
        public void initialize() throws IOException {
        }

        @Override
        public void update() {
            order.add("update");
        }

        @Override
        public void draw() {
        }

        @Override
        public void handleInput(int heldButtons, int pressedButtons) {
            order.add("handleInput");
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void reset() {
            order.add("reset");
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
