package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestSessionOutputPaths;

import com.openggf.Engine;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RgbaImage;
import com.openggf.graphics.ScreenshotCapture;

import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Visual tests for the S3K special stage results screen.
 * Creates the results screen directly and captures screenshots to verify
 * text, emerald indicators, and layout are rendering correctly.
 *
 * Screenshots are saved to the session diagnostics ss-results-visual namespace
 * for manual inspection.
 */
public class TestS3kSpecialStageResultsVisual {

    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;
    private static final Path OUTPUT_DIR = TestSessionOutputPaths.diagnostics("ss-results-visual");

    private static long window;
    private static boolean initialized;
    private static final Matrix4f projectionMatrix = new Matrix4f();
    private static final float[] matrixBuffer = new float[16];

    @BeforeAll
    public static void setUpClass() {
        try {
            EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
            SessionManager.clear();

            // Check for S3K ROM
            String romPath = System.getProperty("s3k.rom.path",
                    "s3k.gen");
            File romFile = new File(romPath);
            if (!romFile.exists()) {
                System.err.println("S3K ROM not available Ã¢â‚¬â€ visual tests skipped");
                initialized = false;
                return;
            }

            // Bootstrap EngineContext once so GraphicsManager.init can reach
            // GameServices.configuration() during the fresh-singleton init below.
            EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

            GLFWErrorCallback.createPrint(System.err).set();
            if (!glfwInit()) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }

            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);

            window = glfwCreateWindow(SCREEN_WIDTH, SCREEN_HEIGHT,
                    "SS Results Visual Test", NULL, NULL);
            if (window == NULL) {
                throw new RuntimeException("Failed to create GLFW window");
            }

            glfwMakeContextCurrent(window);
            GL.createCapabilities();

            // Full GL teardown required here: prior tests (e.g. headless mode) leave the singleton
            // in an incompatible state. resetState() only clears per-level resources; this GPU test
            // needs shaders and the tilemap renderer fully re-initialized for correct pixel rendering.
            GraphicsManager.destroyForReinit(); // full GL teardown needed, not resetState()

            GraphicsManager gm = GraphicsManager.getInstance();
            gm.init(Engine.RESOURCES_SHADERS_PIXEL_SHADER_GLSL);

            glViewport(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            projectionMatrix.identity().ortho2D(0, SCREEN_WIDTH, 0, SCREEN_HEIGHT);
            projectionMatrix.get(matrixBuffer);
            glLoadMatrixf(matrixBuffer);
            gm.setProjectionMatrixBuffer(matrixBuffer.clone());

            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            gm.setViewport(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

            // Load S3K ROM
            Rom rom = new Rom();
            assertTrue(rom.open(romFile.getAbsolutePath()), "Failed to open S3K ROM");
            GameModuleRegistry.detectAndSetModule(rom);
            RomManager.getInstance().setRom(rom);
            TestEnvironment.activeGameplayMode();

            // Re-capture EngineContext now that GraphicsManager has been re-created by
            // destroyForReinit() + getInstance() above. The earlier bootstrap captured
            // the now-destroyed singleton; without refreshing, GameServices.graphics()
            // (called from S3kSpecialStageResultsScreen.ensureArtCached) would write
            // pattern/palette cache entries into the dead instance and the rendered
            // frame would stay all-white.
            EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

            // Create the gameplay mode so GameServices.* accessors resolve.
            TestEnvironment.activeGameplayMode();

            // Configure emerald state for tests
            GameStateManager gs = GameServices.gameState();
            gs.configureSpecialStageProgress(7, 7);

            // Initialize camera at origin
            Camera camera = GameServices.camera();
            camera.setX((short) 0);
            camera.setY((short) 0);

            Files.createDirectories(OUTPUT_DIR);
            initialized = true;

        } catch (Exception e) {
            System.err.println("Visual test init failed: " + e.getMessage());
            e.printStackTrace();
            initialized = false;
        }
    }

    @AfterAll
    public static void tearDownClass() {
        if (window != NULL) {
            try { glfwDestroyWindow(window); } catch (Exception e) { /* ignore */ }
        }
        glfwTerminate();
        GraphicsManager.getInstance().resetState();
        SessionManager.clear();
    }

    /**
     * Test: Emerald collected, not all 7 Ã¢â‚¬â€ should show emerald indicators,
     * ring/time bonus, and character name. Standard success case.
     */
    @Test
    public void testResultsScreen_emeraldCollected() throws Exception {
        assumeTrue(initialized, "Test environment not initialized");

        // Mark 3 emeralds collected (slots 0, 1, 2)
        GameStateManager gs = GameServices.gameState();
        gs.resetSession();
        gs.configureSpecialStageProgress(7, 7);
        gs.markEmeraldCollected(0);
        gs.markEmeraldCollected(1);
        gs.markEmeraldCollected(2);

        S3kSpecialStageResultsScreen screen = new S3kSpecialStageResultsScreen(
                75, true, 2, 3, PlayerCharacter.SONIC_AND_TAILS);

        // Step 400 frames (past the 360-frame pre-tally wait + some tally)
        for (int i = 0; i < 400; i++) {
            screen.update(i, null);
        }

        // Dump raw mapping frame tile indices for key frames
        dumpMappingFrameTiles();

        // Capture two screenshots 1 frame apart (to verify emerald flicker)
        RgbaImage frame1 = renderResultsScreen(screen, 400);
        screen.update(401, null);
        RgbaImage frame2 = renderResultsScreen(screen, 401);

        ScreenshotCapture.savePNG(frame1, OUTPUT_DIR.resolve("emerald_collected_f400.png"));
        ScreenshotCapture.savePNG(frame2, OUTPUT_DIR.resolve("emerald_collected_f401.png"));

        // Verify images are not entirely white (text should be visible)
        assertNotAllWhite(frame1, "Frame 400 (emerald collected) should have visible text");
        assertNotAllWhite(frame2, "Frame 401 (emerald collected) should have visible text");

        // Verify emerald area has non-white pixels (emerald indicators)
        // Emeralds are around screen coords ($100-$150, $D0-$F0) = (128-208, 80-112) after VDP offset
        assertRegionHasContent(frame1, 80, 40, 130, 50,
                "Emerald indicator area should have content (frame 400)");

        System.out.println("Emerald-collected screenshots saved to " + OUTPUT_DIR.toAbsolutePath());
    }

    /**
     * Test: Failed to collect emerald Ã¢â‚¬â€ should show failure message,
     * no character name, no emerald text.
     */
    @Test
    public void testResultsScreen_failed() throws Exception {
        assumeTrue(initialized, "Test environment not initialized");

        // No emeralds collected
        GameStateManager gs = GameServices.gameState();
        gs.resetSession();
        gs.configureSpecialStageProgress(7, 7);

        S3kSpecialStageResultsScreen screen = new S3kSpecialStageResultsScreen(
                30, false, 0, 0, PlayerCharacter.SONIC_AND_TAILS);

        // Step 400 frames
        for (int i = 0; i < 400; i++) {
            screen.update(i, null);
        }

        RgbaImage frame = renderResultsScreen(screen, 400);
        ScreenshotCapture.savePNG(frame, OUTPUT_DIR.resolve("failed_f400.png"));

        assertNotAllWhite(frame, "Failed results screen should have visible text");

        System.out.println("Failed results screenshot saved to " + OUTPUT_DIR.toAbsolutePath());
    }

    // ================================================================
    // Rendering helper
    // ================================================================

    private RgbaImage renderResultsScreen(S3kSpecialStageResultsScreen screen, int frame) {
        GraphicsManager gm = GraphicsManager.getInstance();
        Camera camera = GameServices.camera();

        // Set camera to origin (matches Engine.java SPECIAL_STAGE_RESULTS mode)
        camera.setX((short) 0);
        camera.setY((short) 0);

        // White background
        glClearColor(1.0f, 1.0f, 1.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // Force art/palette caching with a dummy render call first
        // (ensures patterns are in atlas before batch begins)
        List<GLCommand> warmup = new ArrayList<>();
        screen.appendRenderCommands(warmup);

        // Render: begin batch Ã¢â€ â€™ add patterns Ã¢â€ â€™ end batch Ã¢â€ â€™ flush commands
        gm.beginPatternBatch();
        List<GLCommand> commands = new ArrayList<>();
        screen.appendRenderCommands(commands);
        gm.flushPatternBatch(); // Converts batch to registered command
        gm.flushScreenSpace();  // Execute all commands in screen space (camera 0,0)
        glFinish();

        return ScreenshotCapture.captureFramebuffer(SCREEN_WIDTH, SCREEN_HEIGHT);
    }

    // ================================================================
    // Diagnostic
    // ================================================================

    /**
     * Dump raw (unadjusted) tile indices for key Map_Results frames to understand
     * VRAM tile referencing. This helps diagnose tile index mismatches.
     */
    private void dumpMappingFrameTiles() {
        try {
            var rom = GameServices.rom().getRom();
            var reader = com.openggf.data.RomByteReader.fromRom(rom);
            var objectArt = new com.openggf.game.sonic3k.Sonic3kObjectArt(null, reader);
            var rawMappings = objectArt.loadResultsMappings();

            // Frames to check: 0=blank, 1="0" digit, 13=charName($13), 19=SCORE($13+6?),
            // 31=$1F=emerald5, 34=$22=failMsg, 49=$31=SCORE_S3K, 50=$32=RINGBONUS_S3K
            int[] frames = {0, 1, 0x13, 0x1B, 0x22, 0x31, 0x32, 0x33};
            for (int fi : frames) {
                if (fi < rawMappings.size()) {
                    var frame = rawMappings.get(fi);
                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("Frame $%02X (%d pieces): ", fi, frame.pieces().size()));
                    for (var piece : frame.pieces()) {
                        sb.append(String.format("[tile=$%03X pal=%d x=%d y=%d %dx%d] ",
                                piece.tileIndex(), piece.paletteIndex(),
                                piece.xOffset(), piece.yOffset(),
                                piece.widthTiles(), piece.heightTiles()));
                    }
                    System.out.println("MAP_DUMP: " + sb);
                }
            }
        } catch (Exception e) {
            System.out.println("MAP_DUMP: failed: " + e.getMessage());
        }
    }

    // ================================================================
    // Assertions
    // ================================================================

    private void assertNotAllWhite(RgbaImage image, String message) {
        int nonWhitePixels = 0;
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int rgb = image.argb(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                // Consider "white" as any pixel with all channels > 240
                if (r < 240 || g < 240 || b < 240) {
                    nonWhitePixels++;
                }
            }
        }
        assertTrue(nonWhitePixels > 100, message + " (found " + nonWhitePixels + " non-white pixels)");
    }

    private void assertRegionHasContent(RgbaImage image, int rx, int ry, int rw, int rh,
                                         String message) {
        int nonWhitePixels = 0;
        for (int y = ry; y < Math.min(ry + rh, image.height()); y++) {
            for (int x = rx; x < Math.min(rx + rw, image.width()); x++) {
                int rgb = image.argb(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r < 240 || g < 240 || b < 240) {
                    nonWhitePixels++;
                }
            }
        }
        assertTrue(nonWhitePixels > 10, message + " (found " + nonWhitePixels + " non-white pixels in region)");
    }
}
