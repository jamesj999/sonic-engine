package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestSessionOutputPaths;

import com.openggf.Engine;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.dataselect.DataSelectSessionController;
import com.openggf.game.save.SaveManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RgbaImage;
import com.openggf.graphics.ScreenshotCapture;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
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
 * Manual visual-capture utility for the native S3K Data Select screen.
 * Saves a framebuffer PNG for human inspection without launching the full app.
 */
public final class S3kDataSelectVisualCapture {
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;
    private static final Path OUTPUT_DIR = TestSessionOutputPaths.diagnostics("s3k-dataselect-visual");
    private static final Path SAVE_ROOT = OUTPUT_DIR.resolve("saves");
    private static final Path EMPTY_OUTPUT_FILE = OUTPUT_DIR.resolve("native_s3k_dataselect_capture.png");
    private static final Path SAVES_OUTPUT_FILE = OUTPUT_DIR.resolve("native_s3k_dataselect_with_saves.png");
    private static final Path SLOT8_OUTPUT_FILE = OUTPUT_DIR.resolve("native_s3k_dataselect_slot8.png");
    private static final Path CLEAR_OUTPUT_FILE = OUTPUT_DIR.resolve("native_s3k_dataselect_clear_slot.png");

    private S3kDataSelectVisualCapture() {
    }

    public static void main(String[] args) throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

        long window = NULL;
        try {
            String romPath = System.getProperty("s3k.rom.path",
                    "s3k.gen");
            File romFile = new File(romPath);
            if (!romFile.exists()) {
                throw new IllegalStateException("S3K ROM not available: " + romFile.getAbsolutePath());
            }

            Files.createDirectories(OUTPUT_DIR);
            Files.createDirectories(SAVE_ROOT.resolve("s3k"));
            clearDirectory(SAVE_ROOT.resolve("s3k"));

            GLFWErrorCallback.createPrint(System.err).set();
            if (!glfwInit()) {
                throw new IllegalStateException("Unable to initialize GLFW");
            }

            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
            window = glfwCreateWindow(SCREEN_WIDTH, SCREEN_HEIGHT, "S3K Data Select Capture", NULL, NULL);
            if (window == NULL) {
                throw new IllegalStateException("Failed to create hidden GLFW window");
            }

            glfwMakeContextCurrent(window);
            GL.createCapabilities();

            GraphicsManager.destroyForReinit();
            EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

            GraphicsManager graphics = GraphicsManager.getInstance();
            graphics.init(Engine.RESOURCES_SHADERS_PIXEL_SHADER_GLSL);

            glViewport(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            Matrix4f projection = new Matrix4f().ortho2D(0, SCREEN_WIDTH, 0, SCREEN_HEIGHT);
            float[] matrixBuffer = new float[16];
            projection.get(matrixBuffer);
            glLoadMatrixf(matrixBuffer);
            graphics.setProjectionMatrixBuffer(matrixBuffer.clone());

            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            graphics.setViewport(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

            try (Rom rom = new Rom()) {
                if (!rom.open(romFile.getAbsolutePath())) {
                    throw new IllegalStateException("Failed to open S3K ROM");
                }
                GameModuleRegistry.detectAndSetModule(rom);
                RomManager.getInstance().setRom(rom);
                TestEnvironment.activeGameplayMode();
                GameServices.camera().resetState();

                S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(com.openggf.data.RomByteReader.fromRom(rom));
                loader.loadData();
                System.out.println("menuBgPatterns=" + loader.getMenuBackgroundPatterns().length
                        + " miscPatterns=" + loader.getMiscPatterns().length
                        + " bgLayoutWords=" + loader.getMenuBackgroundLayoutWords().length
                        + " layoutWords=" + loader.getLayoutWords().length
                        + " mappings=" + loader.getSaveScreenMappings().size());

                SaveManager saveManager = new SaveManager(SAVE_ROOT);
                SonicConfigurationService config = EngineServices.current().configuration();
                S3kDataSelectAssetSource assets = S3kDataSelectPresentation.createDefaultAssets();
                captureScenario(saveManager, config, graphics, assets,
                        EMPTY_OUTPUT_FILE, 0, Integer.getInteger("s3k.capture.idleFramesBeforeCapture", 20),
                        ignored -> {
                        });
                captureScenario(saveManager, config, graphics, assets,
                        SAVES_OUTPUT_FILE, 1, 20,
                        S3kDataSelectVisualCapture::seedOccupiedAndClearSaves);
                captureScenario(saveManager, config, graphics, assets,
                        SLOT8_OUTPUT_FILE, 8, 20,
                        S3kDataSelectVisualCapture::seedOccupiedAndClearSaves);
                captureScenario(saveManager, config, graphics, assets,
                        CLEAR_OUTPUT_FILE, 2, 20,
                        S3kDataSelectVisualCapture::seedOccupiedAndClearSaves);
            }

            System.out.println(EMPTY_OUTPUT_FILE.toAbsolutePath());
        } finally {
            if (window != NULL) {
                try {
                    glfwDestroyWindow(window);
                } catch (Exception ignored) {
                }
            }
            glfwTerminate();
            GraphicsManager.getInstance().resetState();
            SessionManager.clear();
        }
    }

    private static int queuedCommandCount(GraphicsManager graphics) throws Exception {
        Field field = GraphicsManager.class.getDeclaredField("commands");
        field.setAccessible(true);
        return ((java.util.List<?>) field.get(graphics)).size();
    }

    private static void clearDirectory(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static int countNonBlackPixels(RgbaImage image) {
        int count = 0;
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int argb = image.argb(x, y);
                if ((argb & 0x00FFFFFF) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void captureScenario(SaveManager saveManager,
                                        SonicConfigurationService config,
                                        GraphicsManager graphics,
                                        S3kDataSelectAssetSource assets,
                                        Path outputFile,
                                        int moveRightCount,
                                        int idleFramesBeforeCapture,
                                        SaveSeeder saveSeeder) throws Exception {
        clearDirectory(SAVE_ROOT.resolve("s3k"));
        saveSeeder.accept(saveManager);

        DataSelectSessionController controller = new DataSelectSessionController(new S3kDataSelectProfile());
        S3kDataSelectPresentation presentation = new S3kDataSelectPresentation(
                controller,
                saveManager,
                config,
                assets,
                new S3kDataSelectRenderer(),
                ignored -> {
                });

        presentation.initialize();
        presentation.update(new InputHandler());

        if (moveRightCount > 0) {
            InputHandler input = new InputHandler();
            int rightKey = config.getInt(SonicConfiguration.RIGHT);
            for (int i = 0; i < moveRightCount; i++) {
                input.handleKeyEvent(rightKey, 1);
                presentation.update(input);
                input.update();
                input.handleKeyEvent(rightKey, 0);
                presentation.update(input);
                input.update();
            }
        }
        for (int i = 0; i < idleFramesBeforeCapture; i++) {
            presentation.update(new InputHandler());
        }

        Camera camera = GameServices.camera();
        camera.setX((short) 0);
        camera.setY((short) 0);

        presentation.setClearColor();
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        presentation.draw();
        System.out.println(outputFile.getFileName() + " queuedCommandsBeforeFlush=" + queuedCommandCount(graphics));
        graphics.flushScreenSpace();
        glFinish();

        RgbaImage image = ScreenshotCapture.captureFramebuffer(SCREEN_WIDTH, SCREEN_HEIGHT);
        ScreenshotCapture.savePNG(image, outputFile);
        System.out.println(outputFile.getFileName() + " nonBlackPixels=" + countNonBlackPixels(image));
    }

    private static void seedOccupiedAndClearSaves(SaveManager saveManager) throws Exception {
        saveManager.writeSlot("s3k", 1, Map.of(
                "zone", 2,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "lives", 5,
                "continues", 2,
                "chaosEmeralds", List.of(0, 1, 2),
                "clear", false
        ));
        saveManager.writeSlot("s3k", 2, Map.ofEntries(
                Map.entry("zone", 3),
                Map.entry("act", 1),
                Map.entry("mainCharacter", "knuckles"),
                Map.entry("sidekicks", List.of()),
                Map.entry("lives", 7),
                Map.entry("continues", 4),
                Map.entry("chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6)),
                Map.entry("superEmeralds", List.of(0, 1, 2, 3, 4, 5, 6)),
                Map.entry("clear", true),
                Map.entry("progressCode", 11),
                Map.entry("clearState", 2)
        ));
    }

    @FunctionalInterface
    private interface SaveSeeder {
        void accept(SaveManager saveManager) throws Exception;
    }
}
