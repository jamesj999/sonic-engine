package com.openggf.graphics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.graphics.color.DisplayColorProfile;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;

import java.lang.reflect.Field;
import java.lang.reflect.Array;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for GraphicsManager headless mode.
 * Verifies that GL operations are properly skipped when running
 * without an OpenGL context.
 *
 * Note: These tests require LWJGL native libraries to be loadable.
 * On headless CI systems without xvfb, these tests will be skipped.
 */
public class TestGraphicsManagerHeadless {

    private GraphicsManager graphicsManager;
    private static boolean lwjglAvailable = false;

    @BeforeAll
    public static void checkLwjglAvailable() {
        // These tests use GraphicsManager in headless mode, which should NOT require
        // LWJGL natives. The headless mode avoids all GL calls and lazy-allocates
        // native buffers only when needed. We just check if the LWJGL classes are on
        // the classpath (without triggering native library loading).
        try {
            Class.forName("org.lwjgl.system.MemoryUtil");
            lwjglAvailable = true;
        } catch (ClassNotFoundException e) {
            System.err.println("LWJGL classes not on classpath, skipping headless tests: " + e.getMessage());
            lwjglAvailable = false;
        }
    }

    @BeforeEach
    public void setUp() {
        assumeTrue(lwjglAvailable, "LWJGL natives not available");
        // Destroy and recreate the singleton to get a truly fresh instance.
        // resetState() preserves headlessMode, which causes test ordering
        // failures when a prior test enables headless mode on the singleton.
        GraphicsManager.destroyForReinit();
        graphicsManager = GraphicsManager.getInstance();
    }

    @AfterEach
    public void tearDown() {
        GraphicsManager.destroyForReinit();
    }

    // ==================== Headless Mode Flag Tests ====================

    @Test
    public void testDefaultModeIsNotHeadless() {
        assertFalse(graphicsManager.isHeadlessMode(), "Default mode should not be headless");
    }

    @Test
    public void testSetHeadlessMode() {
        graphicsManager.setHeadlessMode(true);
        assertTrue(graphicsManager.isHeadlessMode(), "Headless mode should be enabled");

        graphicsManager.setHeadlessMode(false);
        assertFalse(graphicsManager.isHeadlessMode(), "Headless mode should be disabled");
    }

    @Test
    public void testInitHeadlessEnablesHeadlessMode() {
        graphicsManager.initHeadless();
        assertTrue(graphicsManager.isHeadlessMode(), "initHeadless should enable headless mode");
    }

    @Test
    public void testInitHeadlessSetsGlNotInitialized() {
        graphicsManager.initHeadless();
        assertFalse(graphicsManager.isGlInitialized(), "GL should not be initialized in headless mode");
    }

    // ==================== Pattern Caching Tests ====================

    @Test
    public void testCachePatternTextureInHeadlessMode() {
        graphicsManager.initHeadless();

        // Create a simple test pattern
        Pattern pattern = createTestPattern();

        // This should not throw even without a GL context
        graphicsManager.cachePatternTexture(pattern, 42);

        // Pattern should be tracked (with dummy ID -1)
        Integer textureId = graphicsManager.getPatternTextureId(42);
        assertNotNull(textureId, "Pattern should be tracked in headless mode");
        assertEquals(Integer.valueOf(-1), textureId, "Pattern texture ID should be -1 in headless mode");
    }

    @Test
    public void testCacheMultiplePatternsInHeadlessMode() {
        graphicsManager.initHeadless();
        Pattern pattern = createTestPattern();

        graphicsManager.cachePatternTexture(pattern, 0);
        graphicsManager.cachePatternTexture(pattern, 1);
        graphicsManager.cachePatternTexture(pattern, 100);

        assertNotNull(graphicsManager.getPatternTextureId(0), "Pattern 0 should be tracked");
        assertNotNull(graphicsManager.getPatternTextureId(1), "Pattern 1 should be tracked");
        assertNotNull(graphicsManager.getPatternTextureId(100), "Pattern 100 should be tracked");
    }

    @Test
    public void testUpdatePatternTextureInHeadlessMode() {
        graphicsManager.initHeadless();
        Pattern pattern = createTestPattern();

        // Update should work without error and track the pattern
        graphicsManager.updatePatternTexture(pattern, 5);

        assertNotNull(graphicsManager.getPatternTextureId(5), "Pattern should be tracked after update in headless mode");
    }

    // ==================== Palette Caching Tests ====================

    @Test
    public void testCachePaletteTextureInHeadlessMode() {
        graphicsManager.initHeadless();
        Palette palette = createTestPalette();

        // This should not throw even without a GL context
        graphicsManager.cachePaletteTexture(palette, 0);

        // Combined palette texture ID should be null in headless mode
        // (we use dummy tracking via paletteTextureMap)
        assertNull(graphicsManager.getCombinedPaletteTextureId(), "Combined palette texture should be null in headless mode");
    }

    @Test
    public void testClearPaletteTexturesClearsHeadlessTrackingAndUnderwaterSelection() throws Exception {
        graphicsManager.initHeadless();
        graphicsManager.cachePaletteTexture(createTestPalette(), 2);
        graphicsManager.setUseUnderwaterPaletteForBackground(true);

        Map<?, ?> paletteTextureMap = paletteTextureMap(graphicsManager);
        assertFalse(paletteTextureMap.isEmpty(), "precondition: headless palette cache should be tracked");
        assertTrue(graphicsManager.isUseUnderwaterPaletteForBackground(),
                "precondition: underwater palette selection should be enabled");

        graphicsManager.clearPaletteTextures();

        assertTrue(paletteTextureMap.isEmpty(), "palette reset should clear headless palette tracking");
        assertNull(graphicsManager.getCombinedPaletteTextureId(), "combined palette texture should be cleared");
        assertNull(graphicsManager.getUnderwaterPaletteTextureId(), "underwater palette texture should be cleared");
        assertFalse(graphicsManager.isUseUnderwaterPaletteForBackground(),
                "palette reset should clear the underwater palette selector");
    }

    @Test
    public void testClearPaletteTexturesDropsFadeAndCachedPaletteOwners() throws Exception {
        graphicsManager.initHeadless();
        graphicsManager.cachePaletteTexture(createTestPalette(), 2);
        graphicsManager.setPaletteFadePresentation(
                PaletteFadePresentation.Mode.FROM_BLACK, 7,
                PaletteFadePresentation.LINES_1_TO_3);

        Field cachedLines = GraphicsManager.class.getDeclaredField(
                "lastCachedPaletteLines");
        cachedLines.setAccessible(true);
        Object before = cachedLines.get(graphicsManager);
        assertNotNull(Array.get(before, 2),
                "precondition: palette teardown must have an owner to drop");
        assertTrue(graphicsManager.getPaletteFadePresentation().isActive(),
                "precondition: palette teardown must have an active fade");

        graphicsManager.clearPaletteTextures();

        assertFalse(graphicsManager.getPaletteFadePresentation().isActive(),
                "palette teardown must neutralize the presentation fade");
        Object after = cachedLines.get(graphicsManager);
        assertEquals(0, Array.getLength(after),
                "palette teardown must drop prior palette owners");

        // Repeated session-boundary cleanup is safe and stays neutral.
        graphicsManager.clearPaletteTextures();
        assertFalse(graphicsManager.getPaletteFadePresentation().isActive());
        assertEquals(0, Array.getLength(cachedLines.get(graphicsManager)));
    }

    @Test
    public void testPaletteUploadBytesUseSelectedDisplayProfile() {
        graphicsManager.setDisplayColorProfile(DisplayColorProfile.MD_ANALOG);

        assertArrayEquals(new int[] {238, 238, 238, 255},
                graphicsManager.paletteUploadRgbaForTest(255, 255, 255, 1));
        assertArrayEquals(new int[] {238, 238, 238, 0},
                graphicsManager.paletteUploadRgbaForTest(255, 255, 255, 0));
    }

    // ==================== Flush Tests ====================

    @Test
    public void testFlushInHeadlessModeDoesNotThrow() {
        graphicsManager.initHeadless();

        // Register some commands
        graphicsManager.registerCommand((cX, cY, cW, cH) -> {
            // This would crash without a GL context if flush actually executed it
            throw new RuntimeException("Command should not execute in headless mode");
        });

        // Flush should clear commands without executing them
        graphicsManager.flush();
    }

    @Test
    public void testFlushClearsCommandsInHeadlessMode() {
        graphicsManager.initHeadless();

        // Register a command that records whether it was ever executed.
        boolean[] executed = {false};
        graphicsManager.registerCommand((cX, cY, cW, cH) -> executed[0] = true);

        // The queue should now hold exactly the registered command.
        assertEquals(1, graphicsManager.commands.size(),
                "registerCommand should enqueue exactly one pending command");

        // Flush should clear the queue without executing the command in headless mode.
        graphicsManager.flush();
        assertTrue(graphicsManager.commands.isEmpty(),
                "flush should clear the command queue in headless mode");
        assertFalse(executed[0],
                "headless flush must not execute queued GL commands");

        // Second flush proves the queue was actually cleared: nothing left to run.
        graphicsManager.flush();
        assertFalse(executed[0],
                "a previously cleared command must not re-execute on a second flush");
        assertTrue(graphicsManager.commands.isEmpty(),
                "command queue should remain empty after a second flush");
    }

    // ==================== Batching Tests ====================

    @Test
    public void testBeginPatternBatchInHeadlessModeDoesNotThrow() {
        graphicsManager.initHeadless();

        assertDoesNotThrow(graphicsManager::beginPatternBatch,
                "beginPatternBatch must be a safe no-op in headless mode");
        // Beginning a batch must not enqueue any GL command in headless mode.
        assertTrue(graphicsManager.commands.isEmpty(),
                "beginPatternBatch must not queue GL commands in headless mode");
    }

    @Test
    public void testFlushPatternBatchInHeadlessModeDoesNotThrow() {
        graphicsManager.initHeadless();

        assertDoesNotThrow(graphicsManager::flushPatternBatch,
                "flushPatternBatch must be a safe no-op in headless mode");
        // Flushing an (empty, never-begun) batch must not enqueue any GL command.
        assertTrue(graphicsManager.commands.isEmpty(),
                "flushPatternBatch must not queue GL commands in headless mode");
    }

    @Test
    public void testBatchingOperationsInHeadlessMode() {
        graphicsManager.initHeadless();

        // A full begin/flush cycle (repeated) must stay a no-op in headless mode:
        // never throw and never produce queued GL commands to run later.
        assertDoesNotThrow(() -> {
            graphicsManager.beginPatternBatch();
            graphicsManager.flushPatternBatch();
            graphicsManager.beginPatternBatch();
            graphicsManager.flushPatternBatch();
        }, "repeated headless batch cycles must not throw");
        assertTrue(graphicsManager.commands.isEmpty(),
                "headless batch cycles must leave the command queue empty");
    }

    // ==================== Cleanup Tests ====================

    @Test
    public void testCleanupInHeadlessModeDoesNotThrow() {
        graphicsManager.initHeadless();

        // Cache some patterns
        graphicsManager.cachePatternTexture(createTestPattern(), 0);
        graphicsManager.cachePatternTexture(createTestPattern(), 1);

        // Cleanup should work without GL context
        graphicsManager.cleanup();

        // Patterns should be cleared
        assertNull(graphicsManager.getPatternTextureId(0), "Patterns should be cleared after cleanup");
    }

    // ==================== Singleton Reset Tests ====================

    @Test
    public void testDestroyForReinitCreatesNewInstance() {
        GraphicsManager first = GraphicsManager.getInstance();
        first.setHeadlessMode(true);

        GraphicsManager.destroyForReinit();

        GraphicsManager second = GraphicsManager.getInstance();
        assertFalse(second.isHeadlessMode(), "New instance should have default headless mode");
    }

    // ==================== Safe-Area Projection Tests ====================

    @Test
    public void testBeginSafeAreaProjectionNativeWidthIsNoOp() {
        // At native width (320) safe-area ortho == scene ortho: left=0, right=320.
        // Matrix: ortho2D(0, 320, 0, 224) — identical to the default scene projection.
        graphicsManager.initHeadless();
        graphicsManager.beginSafeAreaProjection(320, 224);
        float[] buf = graphicsManager.getProjectionMatrixBuffer();
        assertNotNull(buf, "beginSafeAreaProjection should set projection buffer");
        assertEquals(16, buf.length, "projection buffer should be 16 floats");
        // For ortho2D(left=0, right=320, bottom=0, top=224):
        // m[0] (col 0, row 0) = 2/(right-left) = 2/320 = 0.00625
        assertEquals(2f / 320f, buf[0], 1e-6f, "m[0] should be 2/320 at native width");
        // m[5] (col 1, row 1) = 2/(top-bottom) = 2/224
        assertEquals(2f / 224f, buf[5], 1e-6f, "m[5] should be 2/224");
        // m[12] (col 3, row 0) = -(right+left)/(right-left) = -1.0
        assertEquals(-1f, buf[12], 1e-6f, "m[12] translation should be -1 at native width");
    }

    @Test
    public void testBeginSafeAreaProjectionWiderViewportCenters() {
        // At 400 px wide: pad = (400-320)/2 = 40, left = -40, right = 360.
        graphicsManager.initHeadless();
        graphicsManager.beginSafeAreaProjection(400, 224);
        float[] buf = graphicsManager.getProjectionMatrixBuffer();
        assertNotNull(buf, "beginSafeAreaProjection should set projection buffer");
        // m[0] = 2/(360-(-40)) = 2/400 = 0.005
        assertEquals(2f / 400f, buf[0], 1e-6f, "m[0] should be 2/(right-left) = 2/400 at 400px width");
        // m[12] = -(right+left)/(right-left) = -(360 + (-40))/400 = -320/400 = -0.8
        float expectedTranslation = -(360f + (-40f)) / 400f;
        assertEquals(expectedTranslation, buf[12], 1e-6f, "m[12] translation should center the 320-wide safe area in 400px");
    }

    @Test
    public void testEndSafeAreaProjectionClearsOverride() {
        graphicsManager.initHeadless();
        graphicsManager.beginSafeAreaProjection(400, 224);
        assertNotNull(graphicsManager.getProjectionMatrixBuffer(), "buffer should be set after begin");
        graphicsManager.endSafeAreaProjection();
        // After end, local override is null; getProjectionMatrixBuffer falls back to Engine
        // (null here because no engine set in headless) — so result is null.
        assertNull(graphicsManager.getProjectionMatrixBuffer(),
                "endSafeAreaProjection should clear the local override");
    }

    // ==================== Batching Enable/Disable Tests ====================

    @Test
    public void testBatchingEnabledByDefault() {
        assertTrue(graphicsManager.isBatchingEnabled(), "Batching should be enabled by default");
    }

    @Test
    public void testSetBatchingEnabled() {
        graphicsManager.setBatchingEnabled(false);
        assertFalse(graphicsManager.isBatchingEnabled(), "Batching should be disabled");

        graphicsManager.setBatchingEnabled(true);
        assertTrue(graphicsManager.isBatchingEnabled(), "Batching should be re-enabled");
    }

    // ==================== Helper Methods ====================

    private Pattern createTestPattern() {
        // Create a simple 8x8 pattern for testing using default constructor
        return new Pattern();
    }

    private Palette createTestPalette() {
        // Create a simple 16-color palette for testing using default constructor
        return new Palette();
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> paletteTextureMap(GraphicsManager graphicsManager) throws Exception {
        Field field = GraphicsManager.class.getDeclaredField("paletteTextureMap");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(graphicsManager);
    }
}
