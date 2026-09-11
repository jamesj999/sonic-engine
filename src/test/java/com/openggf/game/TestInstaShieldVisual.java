package com.openggf.game;

import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.Engine;
import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameStep;
import com.openggf.LevelFrameTestStep;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RgbaImage;
import com.openggf.graphics.ScreenshotCapture;
import com.openggf.level.LevelManager;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
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
import static org.lwjgl.opengl.GL11.glClearColor;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL11.glLoadIdentity;
import static org.lwjgl.opengl.GL11.glLoadMatrixf;
import static org.lwjgl.opengl.GL11.glMatrixMode;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Visual diagnostic test for the insta-shield animation in S3K AIZ1.
 * Captures a screenshot of every frame from insta-shield activation for 20 frames,
 * allowing manual inspection of the rendered arc pieces.
 *
 * <p>Run:
 * <pre>
 * mvn test -Dtest=TestInstaShieldVisual -Ds3k.rom.path="Sonic and Knuckles &amp; Sonic 3 (W) [!].gen"
 * </pre>
 * Output PNGs go to the session diagnostics {@code insta-shield-visual/} namespace.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestInstaShieldVisual {

    private static final int W = 320;
    private static final int H = 224;
    private static final Path OUT_DIR = TestSessionOutputPaths.diagnostics("insta-shield-visual");

    private static long window;
    private static boolean initialized;
    private static final Matrix4f projectionMatrix = new Matrix4f();
    private static final float[] matrixBuffer = new float[16];

    private static AbstractPlayableSprite player;
    private static int frameCounter;

    @BeforeAll
    static void setUpClass() {
        try {
            EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
            SessionManager.clear();

            GLFWErrorCallback.createPrint(System.err).set();
            if (!glfwInit()) {
                throw new IllegalStateException("GLFW init failed");
            }

            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);

            window = glfwCreateWindow(W, H, "Insta-Shield Visual Test", NULL, NULL);
            if (window == NULL) {
                throw new RuntimeException("GLFW window creation failed");
            }
            glfwMakeContextCurrent(window);
            GL.createCapabilities();

            initialized = true;
        } catch (Exception e) {
            System.err.println("Init failed: " + e.getMessage());
            e.printStackTrace();
            initialized = false;
        }
    }

    @BeforeEach
    void setUpTest() throws Exception {
        assumeTrue(initialized, "GPU init failed");

        GraphicsManager.destroyForReinit();
        GraphicsManager gm = GraphicsManager.getInstance();
        gm.init(Engine.RESOURCES_SHADERS_PIXEL_SHADER_GLSL);
        glViewport(0, 0, W, H);

        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        projectionMatrix.identity().ortho2D(0, W, 0, H);
        projectionMatrix.get(matrixBuffer);
        glLoadMatrixf(matrixBuffer);
        gm.setProjectionMatrixBuffer(matrixBuffer.clone());

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        gm.setViewport(0, 0, W, H);

        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        assertSame(gm, GameServices.graphics(),
                "Visual test runtime must use the reinitialized GraphicsManager");

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, false);

        String mainCode = config.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        player = new Sonic(mainCode, (short) 0x80, (short) 0x3B0);
        GameServices.sprites().addSprite(player);

        Camera camera = GameServices.camera();
        camera.setFocusedSprite(player);
        camera.setFrozen(false);
        camera.updatePosition(true);

        LevelManager lm = GameServices.level();
        lm.loadZoneAndAct(0, 0);

        GroundSensor.setLevelManager(lm);

        player.setX((short) 0x80);
        player.setY((short) 0x3B0);
        camera.updatePosition(true);

        lm.initCameraForLevel();
        lm.initLevelEventsForLevel();
        lm.updateObjectPositions();

        frameCounter = 0;
    }

    @AfterAll
    static void tearDown() {
        if (window != NULL) {
            try {
                glfwDestroyWindow(window);
            } catch (Exception ignored) {
            }
        }
        glfwTerminate();
        SessionManager.clear();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void captureInstaShieldFrames() throws Exception {
        Files.createDirectories(OUT_DIR);

        LevelManager lm = GameServices.level();
        Camera camera = GameServices.camera();
        SpriteManager sm = GameServices.sprites();

        System.out.println("=== Phase 1: Jumping ===");
        for (int i = 0; i < 5; i++) {
            stepFrame(true, false, false, false, true);
        }
        for (int i = 0; i < 10; i++) {
            stepFrame(false, false, false, false, false);
        }

        boolean isAir = player.getAir();
        System.out.println("After jump: isAir=" + isAir
                + " y=" + player.getY()
                + " doubleJumpFlag=" + player.getDoubleJumpFlag());

        if (!isAir) {
            System.out.println("Not airborne yet, trying more jump frames...");
            for (int i = 0; i < 5; i++) {
                stepFrame(false, false, false, false, true);
            }
            for (int i = 0; i < 5; i++) {
                stepFrame(false, false, false, false, false);
            }
            isAir = player.getAir();
            System.out.println("After extended jump: isAir=" + isAir);
        }

        RgbaImage preFrame = renderFrame(lm, sm);
        ScreenshotCapture.savePNG(preFrame, OUT_DIR.resolve("frame_pre_activation.png"));
        System.out.println("Saved pre-activation frame");

        System.out.println("\n=== Phase 2: Activating insta-shield ===");
        System.out.println("Before activation: doubleJumpFlag=" + player.getDoubleJumpFlag()
                + " shieldType=" + player.getShieldType()
                + " secondaryAbility=" + player.getSecondaryAbility()
                + " instaShieldObj=" + (player.getInstaShieldObject() != null));

        stepFrame(false, false, false, false, true);

        System.out.println("After activation press: doubleJumpFlag=" + player.getDoubleJumpFlag()
                + " instaShieldObj=" + (player.getInstaShieldObject() != null));

        System.out.println("\n=== Phase 3: Capturing 20 frames ===");
        int framesWithContent = 0;

        for (int i = 0; i < 20; i++) {
            stepFrame(false, false, false, false, false);

            RgbaImage frame = renderFrame(lm, sm);
            assertNotNull(frame, "Framebuffer capture returned null at frame " + i);

            String filename = String.format("frame_%02d.png", i);
            Path outPath = OUT_DIR.resolve(filename);
            ScreenshotCapture.savePNG(frame, outPath);

            int centerX = player.getCentreX() - camera.getX();
            int centerY = player.getCentreY() - camera.getY();
            int shieldPixels = countChangedPixelsAround(preFrame, frame, centerX, centerY, 40);

            System.out.println("Frame " + i + ": " + filename
                    + " doubleJumpFlag=" + player.getDoubleJumpFlag()
                    + " playerCentre=(" + player.getCentreX() + "," + player.getCentreY() + ")"
                    + " screenCentre=(" + centerX + "," + centerY + ")"
                    + " shieldAreaPixels=" + shieldPixels);

            if (shieldPixels > 50) {
                framesWithContent++;
            }
        }

        System.out.println("\nFrames with insta-shield visual content: " + framesWithContent + "/20");
        System.out.println("Output directory: " + OUT_DIR.toAbsolutePath());

        assertTrue(framesWithContent > 0,
                "Expected at least one frame with insta-shield visual content. "
                        + "Check output PNGs in " + OUT_DIR.toAbsolutePath());
    }

    private void stepFrame(boolean up, boolean down, boolean left, boolean right, boolean jump) {
        frameCounter++;

        player.setJumpInputPressed(jump);
        player.setDirectionalInputPressed(up, down, left, right);

        LevelManager lm = GameServices.level();

        LevelFrameTestStep.execute(LevelFrameContext.from(SessionManager.getCurrentGameplayMode()),
                lm, GameServices.camera(), () -> {
            boolean controlLocked = player.isControlLocked();
            boolean forcedRight = player.isForcedInputActive(AbstractPlayableSprite.INPUT_RIGHT)
                    || player.isForceInputRight();
            boolean forcedLeft = player.isForcedInputActive(AbstractPlayableSprite.INPUT_LEFT);
            boolean forcedUp = player.isForcedInputActive(AbstractPlayableSprite.INPUT_UP);
            boolean forcedDown = player.isForcedInputActive(AbstractPlayableSprite.INPUT_DOWN);
            boolean forcedJump = player.isForcedInputActive(AbstractPlayableSprite.INPUT_JUMP);
            boolean effectiveRight = (!controlLocked && right) || forcedRight;
            boolean effectiveLeft = ((!controlLocked && left) || forcedLeft) && !forcedRight;
            boolean effectiveUp = (!controlLocked && up) || forcedUp;
            boolean effectiveDown = (!controlLocked && down) || forcedDown;
            boolean effectiveJump = (!controlLocked && jump) || forcedJump;

            SpriteManager.tickPlayablePhysics(player,
                    effectiveUp, effectiveDown, effectiveLeft, effectiveRight, effectiveJump,
                    false, false, false, lm, frameCounter);
        });
    }

    private RgbaImage renderFrame(LevelManager lm, SpriteManager sm) {
        lm.setClearColor();
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        GraphicsManager gm = GraphicsManager.getInstance();
        lm.drawWithSpritePriority(sm);

        gm.flush();
        glFinish();

        return ScreenshotCapture.captureFramebuffer(W, H);
    }

    private static int countChangedPixelsAround(RgbaImage baseline, RgbaImage img, int cx, int cy, int halfSize) {
        int count = 0;
        int startX = Math.max(0, cx - halfSize);
        int endX = Math.min(img.width(), cx + halfSize);
        int startY = Math.max(0, cy - halfSize);
        int endY = Math.min(img.height(), cy + halfSize);

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int before = baseline.argb(x, y);
                int after = img.argb(x, y);
                int dr = Math.abs(((before >> 16) & 0xFF) - ((after >> 16) & 0xFF));
                int dg = Math.abs(((before >> 8) & 0xFF) - ((after >> 8) & 0xFF));
                int db = Math.abs((before & 0xFF) - (after & 0xFF));
                if (dr > 20 || dg > 20 || db > 20) {
                    count++;
                }
            }
        }
        return count;
    }
}
