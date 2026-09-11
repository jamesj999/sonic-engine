package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.objects.MonitorContentsObjectInstance;
import com.openggf.game.sonic2.objects.MonitorObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

@RequiresRom(SonicGame.SONIC_2)
class TestLiveRewindMonitorState {
    private SonicConfigurationService config;
    private boolean originalLiveRewindEnabled;
    private boolean originalTapeCoastEnabled;

    @BeforeEach
    void setUp() {
        config = SonicConfigurationService.getInstance();
        originalLiveRewindEnabled = config.getBoolean(SonicConfiguration.LIVE_REWIND_ENABLED);
        originalTapeCoastEnabled = config.getBoolean(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED, false);
    }

    @AfterEach
    void tearDown() {
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, originalLiveRewindEnabled);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED, originalTapeCoastEnabled);
        SessionManager.clear();
    }

    @Test
    void heldLiveRewindRestoresIntactMonitorBeforeRelease() throws Exception {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        GraphicsManager.getInstance().initHeadless();

        Sonic sonic = new Sonic(config.getString(SonicConfiguration.MAIN_CHARACTER_CODE),
                (short) 0, (short) 0);
        GameServices.sprites().addSprite(sonic);
        Camera camera = gameplayMode.getCamera();
        camera.setFocusedSprite(sonic);
        camera.setFrozen(false);
        GameServices.level().loadZoneAndAct(0, 0);
        GroundSensor.setLevelManager(GameServices.level());

        ObjectManager objects = GameServices.level().getObjectManager();
        ObjectSpawn monitorSpawn = objects.getAllSpawns().stream()
                .filter(spawn -> spawn.objectId() == 0x26)
                .findFirst()
                .orElseThrow();

        // Establish the complete deterministic break trajectory before rewind owns
        // frame zero. Sonic begins above the real placed monitor and falls into it.
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

        MonitorObjectInstance initialMonitor = firstLive(objects, MonitorObjectInstance.class);
        assertNotNull(initialMonitor, "the first EHZ monitor must be materialized by the real placement path");
        assertIntact(objects, initialMonitor);

        InputHandler rewindInput = new InputHandler();
        LiveRewindManager manager = new LiveRewindManager(config);
        int rewindKey = config.getInt(SonicConfiguration.LIVE_REWIND_KEY);
        float heldIntensity = 0.0f;
        try {
            assertFalse(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, rewindInput));
            assertEquals(0, gameplayMode.getRewindController().currentFrame());

            HeadlessTestRunner runner = new HeadlessTestRunner(sonic);
            int intactFrame = -1;
            int brokenFrame = -1;
            for (int row = 1; row <= 40; row++) {
                runner.stepFrame(false, false, false, false, false);
                manager.recordExternalFrame(GameMode.LEVEL, false, rewindInput);
                MonitorObjectInstance monitor = firstLive(objects, MonitorObjectInstance.class);
                if (monitor.getCollisionFlags() == 0x46) {
                    intactFrame = gameplayMode.getRewindController().currentFrame();
                } else {
                    brokenFrame = gameplayMode.getRewindController().currentFrame();
                    break;
                }
            }

            assertTrue(intactFrame > 0, "the history must contain an intact row before collision");
            assertTrue(brokenFrame > intactFrame, "production collision must create the break row");
            assertBroken(objects);

            runner.stepFrame(false, false, false, false, false);
            manager.recordExternalFrame(GameMode.LEVEL, false, rewindInput);
            assertTrue(gameplayMode.getRewindController().currentFrame() > brokenFrame,
                    "history must include a later row after the break");

            rewindInput.handleKeyEvent(rewindKey, GLFW_PRESS);
            while (gameplayMode.getRewindController().currentFrame() >= brokenFrame) {
                int before = gameplayMode.getRewindController().currentFrame();
                assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, rewindInput));
                assertTrue(manager.effectIntensity() > 0.0f);
                assertTrue(gameplayMode.getRewindController().currentFrame() < before,
                        "every held call must move the live context controller backward");
            }

            assertTrue(gameplayMode.getRewindController().currentFrame() < brokenFrame);
            heldIntensity = manager.effectIntensity();
            MonitorObjectInstance restoredMonitor = firstLive(objects, MonitorObjectInstance.class);
            assertNotNull(restoredMonitor, "rewind must restore the intact monitor shell");
            assertIntact(objects, restoredMonitor);
        } finally {
            try {
                rewindInput.handleKeyEvent(rewindKey, GLFW_RELEASE);
                assertFalse(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, rewindInput),
                        "release must leave live rewind inactive");
                if (heldIntensity > 0.0f) {
                    assertTrue(manager.effectIntensity() < heldIntensity,
                            "release must begin decaying the rewind presentation envelope");
                }
                assertFalse(GameServices.audio().isReverseAudioPresentationActive(),
                        "release must end global reverse audio presentation before session teardown");
                assertFalse(gameplayMode.getFadeManager().isReversePresentationActive(),
                        "release must end reverse fade presentation before session teardown");
                config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
                assertFalse(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, rewindInput));
                assertEquals(0.0f, manager.effectIntensity(),
                        "disabling live rewind must clear the presentation envelope");
            } finally {
                config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, originalLiveRewindEnabled);
                config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED, originalTapeCoastEnabled);
            }
        }
    }

    private static void assertBroken(ObjectManager objects) {
        assertEquals(0, firstLive(objects, MonitorObjectInstance.class).getCollisionFlags());
        assertNotNull(firstLive(objects, MonitorContentsObjectInstance.class));
        assertNotNull(firstLive(objects, ExplosionObjectInstance.class));
    }

    private static void assertIntact(ObjectManager objects, MonitorObjectInstance monitor) {
        assertEquals(0x46, monitor.getCollisionFlags());
        assertFalse(hasLive(objects, MonitorContentsObjectInstance.class));
        assertFalse(hasLive(objects, ExplosionObjectInstance.class));
    }

    private static boolean hasLive(ObjectManager objects, Class<?> type) {
        return objects.getActiveObjects().stream()
                .anyMatch(object -> type.isInstance(object) && !object.isDestroyed());
    }

    private static <T extends ObjectInstance> T firstLive(ObjectManager objects, Class<T> type) {
        return objects.getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(object -> !object.isDestroyed())
                .findFirst()
                .orElse(null);
    }
}
