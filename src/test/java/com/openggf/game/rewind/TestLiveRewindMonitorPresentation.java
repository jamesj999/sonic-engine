package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2ObjectArtProvider;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.objects.MonitorContentsObjectInstance;
import com.openggf.game.sonic2.objects.MonitorObjectInstance;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteFramePiece;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

@RequiresRom(SonicGame.SONIC_2)
class TestLiveRewindMonitorPresentation {
    private SonicConfigurationService config;
    private boolean originalLiveRewindEnabled;
    private boolean originalTapeCoastEnabled;
    private GameModule originalModule;

    @BeforeEach
    void setUp() {
        config = SonicConfigurationService.getInstance();
        originalLiveRewindEnabled = config.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED);
        originalTapeCoastEnabled = config.getBoolean(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED);
        originalModule = GameModuleRegistry.getCurrent();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED, false);
    }

    @AfterEach
    void tearDown() {
        try {
            SessionManager.clear();
            GameModuleRegistry.setCurrent(originalModule);
        } finally {
            config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, originalLiveRewindEnabled);
            config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED, originalTapeCoastEnabled);
        }
    }

    @Test
    void heldLiveRewindRestoresIntactMonitorPresentationBeforeRelease() throws Exception {
        GraphicsManager graphics = GraphicsManager.getInstance();
        graphics.initHeadless();

        GameModule productionModule = originalModule;
        Sonic2ObjectArtProvider provider = spy((Sonic2ObjectArtProvider) productionModule.getObjectArtProvider());
        RecordingRenderer monitorRenderer = new RecordingRenderer();
        RecordingRenderer explosionRenderer = new RecordingRenderer();
        doAnswer(invocation -> switch ((String) invocation.getArgument(0)) {
            case ObjectArtKeys.MONITOR -> monitorRenderer;
            case ObjectArtKeys.EXPLOSION -> explosionRenderer;
            default -> invocation.callRealMethod();
        }).when(provider).getRenderer(org.mockito.ArgumentMatchers.anyString());
        GameModule recordingModule = spy(productionModule);
        doReturn(provider).when(recordingModule).getObjectArtProvider();

        SessionManager.clear();
        GameModuleRegistry.setCurrent(recordingModule);
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();

        Sonic sonic = new Sonic(config.getString(SonicConfiguration.MAIN_CHARACTER_CODE), (short) 0, (short) 0);
        GameServices.sprites().addSprite(sonic);
        Camera camera = gameplayMode.getCamera();
        camera.setFocusedSprite(sonic);
        camera.setFrozen(false);
        GameServices.level().loadZoneAndAct(0, 0);
        GroundSensor.setLevelManager(GameServices.level());

        ObjectManager objects = GameServices.level().getObjectManager();
        ObjectSpawn monitorSpawn = objects.getAllSpawns().stream()
                .filter(spawn -> spawn.objectId() == 0x26).findFirst().orElseThrow();
        sonic.setCentreX((short) monitorSpawn.x());
        sonic.setCentreY((short) (monitorSpawn.y() - 48));
        sonic.setAnimationId(Sonic2AnimationIds.ROLL.id());
        sonic.setRolling(true);
        sonic.setAir(true);
        sonic.setYSpeed((short) 0x180);
        sonic.setXSpeed((short) 0);
        sonic.setGSpeed((short) 0);
        camera.updatePosition(true);
        objects.reset(camera.getX());

        InputHandler rewindInput = new InputHandler();
        LiveRewindManager manager = new LiveRewindManager(config);
        int rewindKey = config.getInt(SonicConfiguration.LIVE_REWIND_KEY);
        try {
            assertFalse(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, rewindInput));
            HeadlessTestRunner runner = new HeadlessTestRunner(sonic);
            int brokenFrame = -1;
            for (int row = 1; row <= 40; row++) {
                runner.stepFrame(false, false, false, false, false);
                manager.recordExternalFrame(GameMode.LEVEL, false, rewindInput);
                if (firstLive(objects, MonitorObjectInstance.class).getCollisionFlags() == 0) {
                    brokenFrame = gameplayMode.getRewindController().currentFrame();
                    break;
                }
            }
            assertTrue(brokenFrame > 0);
            runner.stepFrame(false, false, false, false, false);
            manager.recordExternalFrame(GameMode.LEVEL, false, rewindInput);
            assertNotNull(firstLive(objects, MonitorContentsObjectInstance.class),
                    "broken row must retain live monitor contents in the production object graph");
            assertNotNull(firstLive(objects, ExplosionObjectInstance.class),
                    "broken row must retain a live explosion in the production object graph");

            renderAll(objects, graphics, monitorRenderer, explosionRenderer);
            assertTrue(monitorRenderer.frames.stream().anyMatch(call -> call.frame() == 0x0B
                    && call.x() == monitorSpawn.x() && call.y() == monitorSpawn.y()));
            assertFalse(monitorRenderer.pieces.isEmpty(), "broken monitor contents must reach its draw sink");
            assertFalse(explosionRenderer.frames.isEmpty(), "broken monitor explosion must reach its draw sink");

            rewindInput.handleKeyEvent(rewindKey, GLFW_PRESS);
            int heldSteps = 0;
            while (gameplayMode.getRewindController().currentFrame() >= brokenFrame) {
                assertTrue(heldSteps++ < 4096,
                        "held rewind must reach a frame before the break within a bounded"
                                + " number of steps");
                assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, rewindInput));
            }
            assertNull(firstLive(objects, MonitorContentsObjectInstance.class),
                    "held rewind must remove monitor contents from the restored live graph");
            assertNull(firstLive(objects, ExplosionObjectInstance.class),
                    "held rewind must remove the explosion from the restored live graph");

            renderAll(objects, graphics, monitorRenderer, explosionRenderer);
            assertTrue(monitorRenderer.frames.stream().anyMatch(call -> call.frame() != 0x0B
                    && call.x() == monitorSpawn.x() && call.y() == monitorSpawn.y()));
            assertTrue(monitorRenderer.pieces.isEmpty(), "restored intact row must not render monitor contents");
            assertTrue(explosionRenderer.frames.isEmpty(), "restored intact row must not render an explosion");
        } finally {
            rewindInput.handleKeyEvent(rewindKey, GLFW_RELEASE);
            manager.handleRealtimeRewindInput(GameMode.LEVEL, false, rewindInput);
        }
    }

    private static void renderAll(ObjectManager objects, GraphicsManager graphics,
                                  RecordingRenderer monitor, RecordingRenderer explosion) {
        monitor.clear();
        explosion.clear();
        graphics.flushWithCamera((short) 0, (short) 0, (short) 0, (short) 0);
        for (int bucket = RenderPriority.MIN; bucket <= RenderPriority.MAX; bucket++) {
            objects.drawUnifiedBucketWithPriority(bucket, graphics);
        }
    }

    private static <T extends ObjectInstance> T firstLive(ObjectManager objects, Class<T> type) {
        return objects.getActiveObjects().stream().filter(type::isInstance).map(type::cast)
                .filter(object -> !object.isDestroyed()).findFirst().orElse(null);
    }

    private record FrameCall(int frame, int x, int y) {}

    private static final class RecordingRenderer extends PatternSpriteRenderer {
        private final List<FrameCall> frames = new ArrayList<>();
        private final List<List<? extends SpriteFramePiece>> pieces = new ArrayList<>();

        private RecordingRenderer() {
            super(dummySheet());
        }

        @Override public boolean isReady() { return true; }

        @Override
        public void drawFrameIndex(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip) {
            frames.add(new FrameCall(frameIndex, originX, originY));
        }

        @Override
        public void drawPieces(List<? extends SpriteFramePiece> selectedPieces, int originX, int originY,
                               boolean hFlip, boolean vFlip) {
            pieces.add(List.copyOf(selectedPieces));
        }

        private void clear() {
            frames.clear();
            pieces.clear();
        }

        private static ObjectSpriteSheet dummySheet() {
            Pattern[] patterns = {new Pattern()};
            SpriteMappingPiece piece = new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false);
            return new ObjectSpriteSheet(patterns, List.of(new SpriteMappingFrame(List.of(piece))), 0, 1);
        }
    }
}
