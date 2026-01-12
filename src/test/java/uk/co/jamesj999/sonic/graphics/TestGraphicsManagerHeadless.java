package uk.co.jamesj999.sonic.graphics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.level.Palette;
import uk.co.jamesj999.sonic.level.Pattern;

import static org.junit.Assert.*;

/**
 * Tests for GraphicsManager headless mode.
 * Verifies that GL operations are properly skipped when running
 * without an OpenGL context.
 */
public class TestGraphicsManagerHeadless {

    private GraphicsManager graphicsManager;

    @Before
    public void setUp() {
        // Reset the singleton to get a fresh instance for each test
        GraphicsManager.resetInstance();
        graphicsManager = GraphicsManager.getInstance();
    }

    @After
    public void tearDown() {
        GraphicsManager.resetInstance();
    }

    // ==================== Headless Mode Flag Tests ====================

    @Test
    public void testDefaultModeIsNotHeadless() {
        assertFalse("Default mode should not be headless", graphicsManager.isHeadlessMode());
    }

    @Test
    public void testSetHeadlessMode() {
        graphicsManager.setHeadlessMode(true);
        assertTrue("Headless mode should be enabled", graphicsManager.isHeadlessMode());

        graphicsManager.setHeadlessMode(false);
        assertFalse("Headless mode should be disabled", graphicsManager.isHeadlessMode());
    }

    @Test
    public void testInitHeadlessEnablesHeadlessMode() {
        graphicsManager.initHeadless();
        assertTrue("initHeadless should enable headless mode", graphicsManager.isHeadlessMode());
    }

    @Test
    public void testInitHeadlessSetsGraphicsToNull() {
        graphicsManager.initHeadless();
        assertNull("Graphics should be null in headless mode", graphicsManager.getGraphics());
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
        assertNotNull("Pattern should be tracked in headless mode", textureId);
        assertEquals("Pattern texture ID should be -1 in headless mode", Integer.valueOf(-1), textureId);
    }

    @Test
    public void testCacheMultiplePatternsInHeadlessMode() {
        graphicsManager.initHeadless();
        Pattern pattern = createTestPattern();

        graphicsManager.cachePatternTexture(pattern, 0);
        graphicsManager.cachePatternTexture(pattern, 1);
        graphicsManager.cachePatternTexture(pattern, 100);

        assertNotNull("Pattern 0 should be tracked", graphicsManager.getPatternTextureId(0));
        assertNotNull("Pattern 1 should be tracked", graphicsManager.getPatternTextureId(1));
        assertNotNull("Pattern 100 should be tracked", graphicsManager.getPatternTextureId(100));
    }

    @Test
    public void testUpdatePatternTextureInHeadlessMode() {
        graphicsManager.initHeadless();
        Pattern pattern = createTestPattern();

        // Update should work without error and track the pattern
        graphicsManager.updatePatternTexture(pattern, 5);

        assertNotNull("Pattern should be tracked after update in headless mode",
                graphicsManager.getPatternTextureId(5));
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
        assertNull("Combined palette texture should be null in headless mode",
                graphicsManager.getCombinedPaletteTextureId());
    }

    // ==================== Flush Tests ====================

    @Test
    public void testFlushInHeadlessModeDoesNotThrow() {
        graphicsManager.initHeadless();

        // Register some commands
        graphicsManager.registerCommand((gl, cX, cY, cW, cH) -> {
            // This would crash without a GL context if flush actually executed it
            throw new RuntimeException("Command should not execute in headless mode");
        });

        // Flush should clear commands without executing them
        graphicsManager.flush();
    }

    @Test
    public void testFlushClearsCommandsInHeadlessMode() {
        graphicsManager.initHeadless();

        // Register a command
        graphicsManager.registerCommand((gl, cX, cY, cW, cH) -> {});

        // Flush should clear
        graphicsManager.flush();

        // Flush again - should not throw (commands already cleared)
        graphicsManager.flush();
    }

    // ==================== Batching Tests ====================

    @Test
    public void testBeginPatternBatchInHeadlessModeDoesNotThrow() {
        graphicsManager.initHeadless();

        // Should not throw or crash
        graphicsManager.beginPatternBatch();
    }

    @Test
    public void testFlushPatternBatchInHeadlessModeDoesNotThrow() {
        graphicsManager.initHeadless();

        // Should not throw or crash
        graphicsManager.flushPatternBatch();
    }

    @Test
    public void testBatchingOperationsInHeadlessMode() {
        graphicsManager.initHeadless();

        // Full batching cycle should work
        graphicsManager.beginPatternBatch();
        graphicsManager.flushPatternBatch();
        graphicsManager.beginPatternBatch();
        graphicsManager.flushPatternBatch();
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
        assertNull("Patterns should be cleared after cleanup",
                graphicsManager.getPatternTextureId(0));
    }

    // ==================== Singleton Reset Tests ====================

    @Test
    public void testResetInstanceCreatesNewInstance() {
        GraphicsManager first = GraphicsManager.getInstance();
        first.setHeadlessMode(true);

        GraphicsManager.resetInstance();

        GraphicsManager second = GraphicsManager.getInstance();
        assertFalse("New instance should have default headless mode", second.isHeadlessMode());
    }

    // ==================== Batching Enable/Disable Tests ====================

    @Test
    public void testBatchingEnabledByDefault() {
        assertTrue("Batching should be enabled by default", graphicsManager.isBatchingEnabled());
    }

    @Test
    public void testSetBatchingEnabled() {
        graphicsManager.setBatchingEnabled(false);
        assertFalse("Batching should be disabled", graphicsManager.isBatchingEnabled());

        graphicsManager.setBatchingEnabled(true);
        assertTrue("Batching should be re-enabled", graphicsManager.isBatchingEnabled());
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
}
