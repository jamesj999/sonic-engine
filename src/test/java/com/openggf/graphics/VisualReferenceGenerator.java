package com.openggf.graphics;

import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import com.openggf.Engine;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.GameModuleRegistry;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static com.openggf.tests.RomTestUtils.ensureRomAvailable;

/**
 * CLI tool to generate baseline PNG screenshots for visual regression testing.
 *
 * Usage:
 * <pre>
 * mvn exec:java -Dexec.mainClass="com.openggf.graphics.VisualReferenceGenerator" -q
 * </pre>
 */
public class VisualReferenceGenerator {

    private static final String REFERENCE_DIR = "src/test/resources/visual-reference";
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    /**
     * Test positions for visual regression testing.
     * Uses checkpoint positions to capture interesting areas with objects/badniks.
     * Each entry: { zone, act, playerX, playerY, "filename" }
     */
    private static final Object[][] TEST_POSITIONS = {
            {0, 0, 3568, 170, "ehz_a1_cp1.png"},      // EHZ Act 1 checkpoint 1
            {0, 0, 1806, 697, "ehz_a1_tunnel.png"},   // EHZ Act 1 tunnel (priority system test)
            {0, 1, 4224, 200, "ehz_a2_cp1.png"},      // EHZ Act 2 checkpoint 1
            {1, 0, 4272, 1512, "cpz_a1_cp1.png"},     // CPZ Act 1 checkpoint 1
            {1, 1, 3136, 616, "cpz_a2_cp1.png"},      // CPZ Act 2 checkpoint 1
            {3, 0, 5944, 296, "cnz_a1_cp1.png"},      // CNZ Act 1 checkpoint 1
            {4, 0, 4160, 204, "htz_a1_cp1.png"},      // HTZ Act 1 checkpoint 1 (overlay tileset)
            {5, 0, 1400, 1128, "mcz_a1_cp1.png"},     // MCZ Act 1 checkpoint 1
    };

    private long window;
    private Rom rom;

    // JOML matrix for projection
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final float[] matrixBuffer = new float[16];

    public static void main(String[] args) {
        VisualReferenceGenerator generator = new VisualReferenceGenerator();
        try {
            generator.initialize();
            generator.generateAll();
            System.out.println("Reference generation complete.");
        } catch (Exception e) {
            System.err.println("Error generating reference files: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            generator.cleanup();
        }
    }

    /**
     * Initialize the offscreen OpenGL context and load the ROM.
     */
    public void initialize() throws IOException {
        System.out.println("Initializing offscreen OpenGL context...");

        // Disable debug view to avoid rendering debug markers
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, false);

        // Setup error callback
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        // Configure GLFW for offscreen rendering
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);

        // Create hidden window for offscreen rendering
        window = glfwCreateWindow(SCREEN_WIDTH, SCREEN_HEIGHT, "Visual Reference Generator", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        // Make context current
        glfwMakeContextCurrent(window);

        // Create GL capabilities
        GL.createCapabilities();

        // Initialize graphics manager with shader
        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        graphicsManager.init(Engine.RESOURCES_SHADERS_PIXEL_SHADER_GLSL);

        // Set viewport
        glViewport(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        // Set up projection matrix using JOML
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        projectionMatrix.identity().ortho2D(0, SCREEN_WIDTH, 0, SCREEN_HEIGHT);
        projectionMatrix.get(matrixBuffer);
        glLoadMatrixf(matrixBuffer);

        // Provide projection matrix to GraphicsManager for shader-based rendering
        // (required since Engine.getInstance() returns null in test/CLI context)
        graphicsManager.setProjectionMatrixBuffer(matrixBuffer.clone());

        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        // Enable blending for transparency
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // Load ROM
        System.out.println("Loading ROM...");
        File romFile = ensureRomAvailable();
        if (romFile == null) {
            throw new IOException("Sonic 2 ROM not found. Place it in the working directory or set -Dsonic.rom.path");
        }
        rom = new Rom();
        if (!rom.open(romFile.getAbsolutePath())) {
            throw new IOException("Failed to open ROM file: " + romFile.getAbsolutePath());
        }

        // Register game module
        GameModuleRegistry.detectAndSetModule(rom);
        System.out.println("ROM loaded and game module registered.");

        // Create player sprite (required by LevelManager.loadZoneAndAct)
        SonicConfigurationService configService = SonicConfigurationService.getInstance();
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        AbstractPlayableSprite mainSprite = new Sonic(mainCode, (short) 100, (short) 624);
        GameServices.sprites().addSprite(mainSprite);

        // Set up camera to follow player
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(mainSprite);
        camera.updatePosition(true);
    }

    /**
     * Generate all reference images.
     */
    public void generateAll() throws IOException {
        Path refDir = Paths.get(REFERENCE_DIR);
        Files.createDirectories(refDir);

        System.out.println("Generating visual reference files to: " + refDir.toAbsolutePath());

        LevelManager levelManager = GameServices.level();
        Camera camera = GameServices.camera();
        GraphicsManager graphicsManager = GraphicsManager.getInstance();

        // Update viewport dimensions in GraphicsManager
        graphicsManager.setViewport(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        SpriteManager spriteManager = GameServices.sprites();

        for (Object[] position : TEST_POSITIONS) {
            int zone = (int) position[0];
            int act = (int) position[1];
            int playerX = (int) position[2];
            int playerY = (int) position[3];
            String filename = (String) position[4];

            System.out.println("Generating " + filename + " (zone=" + zone + ", act=" + act +
                    ", x=" + playerX + ", y=" + playerY + ")...");

            try {
                // Load level
                levelManager.loadZoneAndAct(zone, act);

                // Move player to checkpoint position
                var player = camera.getFocusedSprite();
                if (player != null) {
                    player.setX((short) playerX);
                    player.setY((short) playerY);
                }

                // Force camera to snap to player position (no smooth scrolling)
                camera.updatePosition(true);

                // Spawn objects within camera view
                levelManager.updateObjectPositions();

                // Update ring spawning within camera view
                if (levelManager.getRingManager() != null) {
                    levelManager.getRingManager().update(camera.getX(), player, 0);
                }

                // Update sprite animation to set correct mapping frame
                // (default frame 0 is intentionally empty; WAIT animation starts at frame 15)
                spriteManager.updateWithoutInput();

                // Clear screen with level's background color
                levelManager.setClearColor();
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

                // Render level with sprites
                levelManager.drawWithSpritePriority(spriteManager);

                // Flush all queued commands
                graphicsManager.flush();

                // Ensure all GL commands are complete
                glFinish();

                // Capture framebuffer
                RgbaImage screenshot = ScreenshotCapture.captureFramebuffer(SCREEN_WIDTH, SCREEN_HEIGHT);

                // Save to file
                Path filePath = refDir.resolve(filename);
                ScreenshotCapture.savePNG(screenshot, filePath);

                System.out.println("  Written: " + filename);

            } catch (Exception e) {
                System.err.println("  WARNING: Failed to generate " + filename + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Cleanup OpenGL resources.
     */
    public void cleanup() {
        if (window != NULL) {
            try {
                glfwDestroyWindow(window);
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        glfwTerminate();

        // Reset graphics singleton for clean state
        GraphicsManager.getInstance().resetState();
    }
}


