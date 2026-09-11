package com.openggf.game.sonic3k;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;

import com.openggf.Engine;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.objects.CnzCannonInstance;
import com.openggf.game.sonic3k.objects.CnzCylinderInstance;
import com.openggf.game.sonic3k.objects.CnzTeleporterInstance;
import com.openggf.game.sonic3k.objects.CnzHoverFanInstance;
import com.openggf.game.sonic3k.objects.CnzSpiralTubeInstance;
import com.openggf.game.sonic3k.objects.CnzVacuumTubeInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RgbaImage;
import com.openggf.graphics.ScreenshotCapture;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.HeadlessTestRunner;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_MODELVIEW;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_PROJECTION;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glLoadIdentity;
import static org.lwjgl.opengl.GL11.glLoadMatrixf;
import static org.lwjgl.opengl.GL11.glMatrixMode;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Manual CNZ visual-capture utility for Task 9.
 *
 * <p>This is intentionally bounded and ROM-grounded:
 * <ul>
 *   <li>Act 1 captures use the CNZ opening gameplay state after the title-card
 *   fade has been skipped in the engine harness.</li>
 *   <li>Act 2 captures use the same start-sequence pattern at the Act 2 entry
 *   point, then a short idle settle to give the renderer time to stabilize.</li>
 *   <li>The optional teleporter capture uses the real CNZ route seam
 *   ({@code beginKnucklesTeleporterRoute()}) and a manual {@code Obj_CNZTeleporter}
 *   spawn, matching the bounded object/event path validated by the headless
 *   tests.</li>
 * </ul>
 *
 * <p>PNG outputs are written to the session diagnostics
 * {@code s3k-cnz-visual/engine/} namespace.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kCnzVisualCapture {

    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;
    private static final Path OUTPUT_DIR = TestSessionOutputPaths.diagnostics("s3k-cnz-visual")
            .resolve("engine");

    private static long window = NULL;
    private static boolean initialized;
    private static Rom rom;
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final float[] MATRIX_BUFFER = new float[16];

    @BeforeAll
    static void setUpClass() {
        try {
            EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

            File romFile = RomTestUtils.ensureSonic3kRomAvailable();
            if (romFile == null) {
                System.err.println("S3K ROM not available; CNZ visual capture skipped.");
                initialized = false;
                return;
            }

            Files.createDirectories(OUTPUT_DIR);

            GLFWErrorCallback.createPrint(System.err).set();
            if (!glfwInit()) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }

            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
            glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR, 2);
            glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR, 1);

            window = glfwCreateWindow(SCREEN_WIDTH, SCREEN_HEIGHT, "CNZ Visual Capture", NULL, NULL);
            if (window == NULL) {
                throw new IllegalStateException("Failed to create hidden GLFW window");
            }

            glfwMakeContextCurrent(window);
            GL.createCapabilities();

            GraphicsManager.destroyForReinit();
            GraphicsManager graphics = GraphicsManager.getInstance();
            graphics.init(Engine.RESOURCES_SHADERS_PIXEL_SHADER_GLSL);

            glViewport(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            PROJECTION.identity().ortho2D(0, SCREEN_WIDTH, 0, SCREEN_HEIGHT);
            PROJECTION.get(MATRIX_BUFFER);
            glLoadMatrixf(MATRIX_BUFFER);
            graphics.setProjectionMatrixBuffer(MATRIX_BUFFER.clone());

            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            graphics.setViewport(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

            rom = new Rom();
            assertTrue(rom.open(romFile.getAbsolutePath()), "Failed to open S3K ROM");
            GameModuleRegistry.detectAndSetModule(rom);
            RomManager.getInstance().setRom(rom);

            SonicConfigurationService config = SonicConfigurationService.getInstance();
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
            config.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, false);
            config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

            TestEnvironment.activeGameplayMode();
            initialized = true;
        } catch (Exception e) {
            System.err.println("CNZ visual capture init failed: " + e.getMessage());
            e.printStackTrace();
            initialized = false;
        }
    }

    @AfterAll
    static void tearDownClass() {
        if (window != NULL) {
            try {
                glfwDestroyWindow(window);
            } catch (Exception ignored) {
            }
        }
        glfwTerminate();
        GraphicsManager.getInstance().resetState();
        SessionManager.clear();
        if (rom != null) {
            rom.close();
        }
        rom = null;
    }

    /**
     * Captures the bounded CNZ visual beats needed for Task 9:
     * Act 1 start, Act 2 start, and an optional Knuckles teleporter-route frame.
     */
    @Test
    void captureCnzReferenceFrames() throws Exception {
        assumeTrue(initialized, "CNZ visual capture environment was not initialized");

        captureStartSequence("sonic", 0, "cnz_a1");
        captureStartSequence("sonic", 1, "cnz_a2");
        captureKnucklesTeleporterRouteFrame();
    }

    /**
     * Captures bounded traversal-object beats for the follow-on CNZ object phase.
     *
     * <p>These frames intentionally use manual object spawns in a stable camera
     * window so the engine output is deterministic and easy to compare against
     * ROM/emulator references or to mark as likely/skip when no exact ROM beat
     * is available in the current capture inventory.
     */
    @Test
    void captureTraversalObjectReferenceFrames() throws Exception {
        assumeTrue(initialized, "CNZ visual capture environment was not initialized");

        captureHoverFanFrame();
        captureCannonFrame();
        captureCylinderFrame();
        captureVacuumTubeFrame();
        captureSpiralTubeFrame();
    }

    /**
     * Act 1 opening state.
     *
     * <p>ROM anchor: CNZ Act 1 start sequence. This is the lowest-risk visual
     * comparison point because it exercises the zone load, the opening parallax
     * state, and the renderer's steady-state frame after a short idle settle.
     */
    private void captureStartSequence(String mainCharacterCode, int act, String prefix) throws Exception {
        AbstractPlayableSprite player = prepareScenario(mainCharacterCode, act);
        HeadlessTestRunner runner = new HeadlessTestRunner(player);

        saveCurrentFrame(OUTPUT_DIR.resolve(prefix + "_start.png"));

        // Short settle interval for animation/palette initialization.
        runner.stepIdleFrames(24);
        saveCurrentFrame(OUTPUT_DIR.resolve(prefix + "_settle.png"));
    }

    /**
     * Optional route beat for Task 9 validation.
     *
     * <p>ROM anchors: {@code CNZ2_ScreenEvent}, {@code Obj_CNZTeleporter}, and
     * {@code Obj_TeleporterBeam}. This is intentionally bounded to the existing
     * event seam and a manual teleporter spawn; it does not attempt to replay
     * the full cutscene choreography.
     */
    private void captureKnucklesTeleporterRouteFrame() throws Exception {
        AbstractPlayableSprite player = prepareScenario("knuckles", 1);
        HeadlessTestRunner runner = new HeadlessTestRunner(player);

        Sonic3kLevelEventManager levelEvents =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        Sonic3kCNZEvents cnzEvents = levelEvents.getCnzEvents();
        cnzEvents.beginKnucklesTeleporterRoute();

        CnzTeleporterInstance teleporter = new CnzTeleporterInstance(
                new com.openggf.level.objects.ObjectSpawn(0x4A40, 0x0A38, 0, 0, 0, false, 0));
        teleporter.setServices(TestEnvironment.objectServices());
        GameServices.level().getObjectManager().addDynamicObject(teleporter);

        player.setCentreX((short) 0x4A50);
        player.setCentreY((short) 0x0A30);
        player.setAir(true);
        player.setJumping(true);
        player.setXSpeed((short) 0x0180);
        player.setGSpeed((short) 0x0200);

        runner.stepIdleFrames(1);

        // Let the route stabilize enough for the beam and clamp artwork to be visible.
        player.setAir(false);
        player.setJumping(false);
        runner.stepIdleFrames(1);

        saveCurrentFrame(OUTPUT_DIR.resolve("cnz_a2_knuckles_route.png"));
    }

    private void captureHoverFanFrame() throws Exception {
        AbstractPlayableSprite player = prepareScenario("sonic", 0);
        HeadlessTestRunner runner = new HeadlessTestRunner(player);
        CnzHoverFanInstance fan = registerObject(new CnzHoverFanInstance(
                new ObjectSpawn(0x1340, 0x02D0, 0x46, 0x90, 0, false, 0)));

        player.setCentreX((short) 0x1360);
        player.setCentreY((short) 0x0290);
        player.setAir(false);
        runner.stepIdleFrames(2);

        saveCurrentFrame(OUTPUT_DIR.resolve("cnz_hover_fan.png"));
    }

    private void captureCannonFrame() throws Exception {
        AbstractPlayableSprite player = prepareScenario("sonic", 0);
        HeadlessTestRunner runner = new HeadlessTestRunner(player);
        CnzCannonInstance cannon = registerObject(new CnzCannonInstance(
                new ObjectSpawn(0x13C0, 0x02E0, 0x42, 0x00, 0, false, 0)));

        player.setCentreX((short) 0x13C0);
        player.setCentreY((short) 0x02C0);
        runner.stepIdleFrames(1);
        setCannonLaunchDelayForCapture(cannon, 0);
        runner.stepFrame(false, false, false, false, true);

        saveCurrentFrame(OUTPUT_DIR.resolve("cnz_cannon.png"));
    }

    private void captureCylinderFrame() throws Exception {
        AbstractPlayableSprite player = prepareScenario("sonic", 0);
        HeadlessTestRunner runner = new HeadlessTestRunner(player);
        registerObject(new CnzCylinderInstance(
                new ObjectSpawn(0x1440, 0x02F0, 0x47, 0x01, 0, false, 0)));

        player.setCentreX((short) 0x1440);
        player.setCentreY((short) 0x02E0);
        runner.stepIdleFrames(3);

        saveCurrentFrame(OUTPUT_DIR.resolve("cnz_cylinder.png"));
    }

    private void captureVacuumTubeFrame() throws Exception {
        AbstractPlayableSprite player = prepareScenario("sonic", 0);
        HeadlessTestRunner runner = new HeadlessTestRunner(player);
        registerObject(new CnzVacuumTubeInstance(
                new ObjectSpawn(0x14C0, 0x0308, 0x48, 0x10, 0, false, 0)));

        player.setCentreX((short) 0x14C8);
        player.setCentreY((short) 0x02E0);
        player.setAir(false);
        player.setJumping(true);
        runner.stepIdleFrames(6);

        saveCurrentFrame(OUTPUT_DIR.resolve("cnz_vacuum_tube.png"));
    }

    private void captureSpiralTubeFrame() throws Exception {
        AbstractPlayableSprite player = prepareScenario("sonic", 0);
        HeadlessTestRunner runner = new HeadlessTestRunner(player);
        registerObject(new CnzSpiralTubeInstance(
                new ObjectSpawn(0x13C0, 0x02D0, 0x4C, 0x00, 0, false, 0)));

        player.setRingCount(1);
        player.setCentreX((short) 0x13D0);
        player.setCentreY((short) 0x02D0);
        player.setAir(false);
        player.setJumping(true);
        runner.stepIdleFrames(220);

        saveCurrentFrame(OUTPUT_DIR.resolve("cnz_spiral_tube.png"));
    }

    /**
     * Rebuilds gameplay state for a single capture scenario.
     *
     * <p>The runtime is recreated per capture so the saved PNGs represent
     * isolated frames rather than accumulated state from earlier scenarios.
     */
    private AbstractPlayableSprite prepareScenario(String mainCharacterCode, int act) throws Exception {
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, mainCharacterCode);

        GameServices.camera().resetState();
        GameServices.sprites().resetState();

        Sonic player = new Sonic(mainCharacterCode, (short) 100, (short) 624);
        GameServices.sprites().addSprite(player);

        Camera camera = GameServices.camera();
        camera.setFocusedSprite(player);
        camera.setFrozen(false);

        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0x03, act);

        GroundSensor.setLevelManager(levelManager);
        camera.updatePosition(true);
        levelManager.updateObjectPositions();
        // Do not pre-step the playable slot here. The level load publishes the
        // one-shot initial Process_Sprites pass and zeroes the sprite counter to
        // match the level counter; the first driven frame consumes that pass and
        // validates both counters against its epoch, so a manual
        // updateWithoutInput() would desync the sprite clock by one.

        return player;
    }

    private <T extends AbstractObjectInstance> T registerObject(T object) {
        object.setServices(TestEnvironment.objectServices());
        GameServices.level().getObjectManager().addDynamicObject(object);
        GameServices.camera().updatePosition(true);
        return object;
    }

    private void setCannonLaunchDelayForCapture(CnzCannonInstance cannon, int frames) throws Exception {
        Method method = CnzCannonInstance.class.getDeclaredMethod("setLaunchDelayFramesForTest", int.class);
        method.setAccessible(true);
        method.invoke(cannon, frames);
    }

    /**
     * Renders the current frame to a PNG under the Task 9 output directory.
     */
    private void saveCurrentFrame(Path outputFile) throws Exception {
        LevelManager levelManager = GameServices.level();

        levelManager.setClearColor();
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        levelManager.drawWithSpritePriority(GameServices.sprites());
        GraphicsManager.getInstance().flush();
        glFinish();

        RgbaImage image = ScreenshotCapture.captureFramebuffer(SCREEN_WIDTH, SCREEN_HEIGHT);
        ScreenshotCapture.savePNG(image, outputFile);
        System.out.println("Saved " + outputFile.toAbsolutePath());
    }
}
