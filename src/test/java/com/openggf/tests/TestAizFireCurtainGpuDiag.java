package com.openggf.tests;

import com.openggf.Engine;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.FireCurtainRenderState;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.objects.AizHollowTreeObjectInstance;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RgbaImage;
import com.openggf.graphics.ScreenshotCapture;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.GroundMode;
import com.openggf.sprites.playable.Sonic;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * GPU rendering diagnostic for the AIZ fire curtain.
 * Captures framebuffer screenshots at key frames during the fire transition
 * to verify the BG tilemap renderer natively shows fire tiles via scroll.
 *
 * <p>Run manually:
 * <pre>
 * mvn test -Dtest=TestAizFireCurtainGpuDiag -Ds3k.rom.path="Sonic and Knuckles &amp; Sonic 3 (W) [!].gen"
 * </pre>
 * Output PNGs go to the session diagnostics {@code fire-curtain-gpu/} namespace.
 */
public class TestAizFireCurtainGpuDiag {

    private static final int W = 320;
    private static final int H = 224;
    private static final Path OUT_DIR = TestSessionOutputPaths.diagnostics("fire-curtain-gpu");

    private static long window;
    private static boolean initialized;
    private static final Matrix4f projectionMatrix = new Matrix4f();
    private static final float[] matrixBuffer = new float[16];

    @BeforeAll
    public static void setUpClass() {
        try {
            SonicConfigurationService config = SonicConfigurationService.getInstance();
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
            config.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, false);

            GLFWErrorCallback.createPrint(System.err).set();
            if (!glfwInit()) throw new IllegalStateException("GLFW init failed");

            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);

            window = glfwCreateWindow(W, H, "Fire Curtain GPU Diag", NULL, NULL);
            if (window == NULL) throw new RuntimeException("GLFW window creation failed");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();

            // Full GL teardown required here: prior tests may leave the singleton in headless mode
            // with a different GL context. resetState() only clears per-level resources and does not
            // reinitialize shaders or the tilemap renderer; this GPU test needs a completely fresh
            // GraphicsManager to ensure fire tiles are rendered correctly.
            GraphicsManager.destroyForReinit(); // full GL teardown needed, not resetState()
            GameServices.camera().resetState();
            GameServices.sprites().resetState();

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

            // Load S3K ROM
            String romPath = System.getProperty("s3k.rom.path",
                    "s3k.gen");
            File romFile = new File(romPath);
            if (!romFile.exists()) {
                System.err.println("S3K ROM not found at " + romFile.getAbsolutePath());
                initialized = false;
                return;
            }
            Rom rom = new Rom();
            assertTrue(rom.open(romFile.getAbsolutePath()), "Failed to open S3K ROM");
            GameModuleRegistry.detectAndSetModule(rom);
            RomManager.getInstance().setRom(rom);

            // Create player and load AIZ1
            String mainCode = config.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
            AbstractPlayableSprite player = new Sonic(mainCode, (short) 12002, (short) 872);
            GameServices.sprites().addSprite(player);
            Camera camera = GameServices.camera();
            camera.setFocusedSprite(player);
            camera.setFrozen(false);
            camera.updatePosition(true);

            LevelManager lm = GameServices.level();
            lm.loadZoneAndAct(0, 0); // AIZ act 1

            // Position near miniboss area
            player.setX((short) 12002);
            player.setY((short) 872);
            camera.updatePosition(true);

            AizHollowTreeObjectInstance.resetTreeRevealCounter();

            initialized = true;
            System.out.println("GPU fire curtain diagnostic initialized");
        } catch (Exception e) {
            System.err.println("Init failed: " + e.getMessage());
            e.printStackTrace();
            initialized = false;
        }
    }

    @AfterAll
    public static void tearDown() {
        if (window != NULL) {
            glfwDestroyWindow(window);
        }
        glfwTerminate();
        GraphicsManager.getInstance().resetState();
        if (TestEnvironment.activeGameplayMode() != null) {
            GameServices.camera().resetState();
        }
    }

    @Test
    public void captureFireCurtainFrames() throws Exception {
        assumeTrue(initialized, "GPU init failed or ROM not available");
        Files.createDirectories(OUT_DIR);

        LevelManager lm = GameServices.level();
        Camera camera = GameServices.camera();
        SpriteManager sm = GameServices.sprites();
        AbstractPlayableSprite player = camera.getFocusedSprite();

        // Trigger fire transition
        Sonic3kLevelEventManager lem =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        assertNotNull(lem, "LevelEventManager not initialized");
        Sonic3kAIZEvents events = lem.getAizEvents();
        assertNotNull(events, "AIZ events not initialized");

        events.setEventsFg5(true);

        // Capture frames at key fire transition points.
        // Frame milestones (from simulation):
        //   0   = fire just triggered
        //  30   = early lerp, fire barely visible
        //  80   = mid-lerp, small fire strip at bottom
        // 120   = acceleration phase, fire growing
        // 150   = fire roughly fills screen
        // 170   = full-screen fire, max speed
        // 190   = fire scrolling off top (FINISH phase)
        int[] captureFrames = {0, 30, 80, 120, 150, 170, 190};
        int captureIdx = 0;
        int firePixelsDetected = 0;

        for (int frame = 0; frame <= 200 && captureIdx < captureFrames.length; frame++) {
            // Update game state
            player.setControlLocked(false); // prevent lock from interfering
            lem.update();

            // Update parallax/scroll
            ParallaxManager pm = GameServices.parallax();
            if (pm != null) {
                pm.update(0, 0, camera, frame, 0);
            }

            if (frame == captureFrames[captureIdx]) {
                // Get fire state for logging
                FireCurtainRenderState state = events.getFireCurtainRenderState(H);

                // Diagnostic: check what the parallax manager actually has
                ParallaxManager pmDiag = GameServices.parallax();
                short bgVScroll = pmDiag != null ? pmDiag.getVscrollFactorBG() : -1;
                int bgCamX = pmDiag != null ? pmDiag.getBgCameraX() : -1;
                System.out.println("  [DIAG] pm.vscrollFactorBG=" + bgVScroll
                        + " pm.bgCameraX=" + bgCamX
                        + " fireScrollActive=" + events.isFireTransitionScrollActive()
                        + " fireBgX=0x" + Integer.toHexString(events.getFireTransitionBgX())
                        + " fireBgY=0x" + Integer.toHexString(events.getFireTransitionBgY()));

                // Render
                lm.setClearColor();
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                lm.drawWithSpritePriority(sm);
                GraphicsManager.getInstance().flush();
                glFinish();

                // Capture
                RgbaImage img = ScreenshotCapture.captureFramebuffer(W, H);
                assertNotNull(img, "Framebuffer capture returned null");

                // Count non-black pixels in bottom third (fire region)
                int firePixels = countFirePixels(img);
                String filename = String.format("fire_gpu_frame_%03d.png", frame);
                Path outPath = OUT_DIR.resolve(filename);
                ScreenshotCapture.savePNG(img, outPath);

                System.out.println("Frame " + frame + ": " + filename
                        + " firePixels=" + firePixels
                        + " bgY=0x" + Integer.toHexString(state.sourceWorldY())
                        + " cover=" + state.coverHeightPx()
                        + " stage=" + state.stage()
                        + " active=" + state.active());

                if (frame >= 120) {
                    firePixelsDetected = Math.max(firePixelsDetected, firePixels);
                }

                captureIdx++;
            }
        }

        System.out.println("\nMax fire pixels detected (frame 120+): " + firePixelsDetected);
        System.out.println("Output: " + OUT_DIR.toAbsolutePath());

        // Basic assertion: at frame 150+ there should be significant fire content
        // (at least 10% of the screen should be non-black in the fire region)
        assertTrue(firePixelsDetected > (W * H / 10), "No fire pixels detected in BG rendering at frame 150+. "
                        + "The BG tilemap renderer is not showing fire tiles. "
                        + "Max fire pixels: " + firePixelsDetected);
    }

    /**
     * Count pixels that look like fire (warm colors: red/orange/yellow).
     * Fire palette uses reds, oranges, and yellows.
     */
    private static int countFirePixels(RgbaImage img) {
        int count = 0;
        for (int y = 0; y < img.height(); y++) {
            for (int x = 0; x < img.width(); x++) {
                int rgb = img.argb(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // Fire colors: high red, moderate green, low blue
                if (r > 100 && r > b * 2 && (r + g) > 150) {
                    count++;
                }
            }
        }
        return count;
    }
}
