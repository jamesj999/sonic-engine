package com.openggf.game.sonic1.credits;

import com.openggf.game.GameServices;
import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenDataLoader;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;

import java.util.List;
import java.util.logging.Logger;

/**
 * Renders credit text at screen center using the Nemesis credit font.
 * <p>
 * Reuses the same credit text patterns loaded by
 * {@link Sonic1TitleScreenDataLoader}, with mapping frames from
 * {@link Sonic1CreditsMappings}.
 */
public class Sonic1CreditsTextRenderer {

    private static final Logger LOGGER = Logger.getLogger(Sonic1CreditsTextRenderer.class.getName());

    /** Screen center position: 320/2 = 160, 224/2 = 112. */
    private static final int SCREEN_CENTER_X = 160;
    private static final int SCREEN_CENTER_Y = 112;

    private PatternSpriteRenderer renderer;
    private List<SpriteMappingFrame> mappingFrames;
    private Sonic1TitleScreenDataLoader dataLoaderRef;
    private boolean initialized;
    private boolean gpuDirty; // Set when zone loading may have overwritten GPU patterns/palette

    /**
     * Initializes the renderer. Loads credit text patterns and creates
     * the sprite sheet for all 9 credit frames.
     *
     * @param dataLoader the title screen data loader (must have loaded credit text patterns)
     */
    public void initialize(Sonic1TitleScreenDataLoader dataLoader) {
        if (initialized) {
            return;
        }

        mappingFrames = Sonic1CreditsMappings.createFrames();

        if (dataLoader == null || dataLoader.getCreditTextPatterns() == null
                || dataLoader.getCreditTextPatterns().length == 0) {
            LOGGER.warning("Credit text patterns not available from title screen data loader");
            return;
        }

        ObjectSpriteSheet sheet = new ObjectSpriteSheet(
                dataLoader.getCreditTextPatterns(),
                mappingFrames,
                0, // palette line 0
                1
        );
        renderer = new PatternSpriteRenderer(sheet);
        dataLoaderRef = dataLoader;

        GraphicsManager gm = GameServices.graphics();
        // Reset cache flags so patterns and palette are re-uploaded to GPU
        // (zone palettes will have overwritten the GPU palette since title screen)
        dataLoader.resetCache();
        dataLoader.cacheCreditTextToGpu(gm);
        dataLoader.cachePalettesToGpu(gm);
        renderer.ensurePatternsCached(gm, Sonic1TitleScreenDataLoader.CREDIT_TEXT_PATTERN_BASE);

        initialized = true;
        LOGGER.info("Credits text renderer initialized with " + mappingFrames.size() + " frames");
    }

    /**
     * Draws the credit text for the given credit number at screen center.
     *
     * @param creditsNum credit index (0-8)
     */
    public void draw(int creditsNum) {
        if (!initialized || renderer == null || !renderer.isReady()) {
            return;
        }
        if (creditsNum < 0 || creditsNum >= mappingFrames.size()) {
            return;
        }

        // Re-upload patterns and palette if a zone load has dirtied the GPU cache
        if (gpuDirty && dataLoaderRef != null) {
            GraphicsManager gm = GameServices.graphics();
            dataLoaderRef.resetCache();
            dataLoaderRef.cacheCreditTextToGpu(gm);
            dataLoaderRef.cachePalettesToGpu(gm);
            gpuDirty = false;
        }

        GraphicsManager gm = GameServices.graphics();
        gm.beginPatternBatch();
        renderer.drawFrameIndex(creditsNum, SCREEN_CENTER_X, SCREEN_CENTER_Y);
        gm.flushPatternBatch();
    }

    /**
     * Marks the GPU pattern/palette cache as dirty. Call this when a zone load
     * has overwritten the GPU state (e.g., between CREDITS_DEMO and CREDITS_TEXT phases).
     */
    public void markGpuDirty() {
        gpuDirty = true;
    }

    public boolean isReady() {
        return initialized && renderer != null && renderer.isReady();
    }
}
