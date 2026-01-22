package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.WaterSystem;
import uk.co.jamesj999.sonic.level.objects.ObjectSpriteSheet;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages water surface sprite rendering for Sonic 2 levels.
 * <p>
 * The water surface consists of animated sprites displayed at the water line.
 * The original game only displays half of the sprites each frame, alternating
 * to stay under the Mega Drive's per-scanline sprite limit.
 * <p>
 * CPZ2, ARZ1, and ARZ2 all have water surface sprites.
 * CPZ uses pink/purple chemical water art (shared with HPZ).
 * ARZ uses natural blue water art.
 */
public class WaterSurfaceManager {
    private static final Logger LOGGER = Logger.getLogger(WaterSurfaceManager.class.getName());

    // Water surface sprite width in pixels (each segment is 32 pixels wide in
    // original)
    // The art consists of 24 blocks (CPZ) or 16 blocks (ARZ) that tile horizontally
    private static final int SEGMENT_WIDTH_PIXELS = 32;

    // Zone IDs
    private static final int ZONE_CPZ = 0x0D;
    private static final int ZONE_ARZ = 0x0F;

    private final int zoneId;
    private final int actId;
    private final Pattern[] cpzPatterns;
    private final Pattern[] arzPatterns;
    private final ObjectSpriteSheet cpzSheet;
    private final ObjectSpriteSheet arzSheet;

    private PatternSpriteRenderer cpzRenderer;
    private PatternSpriteRenderer arzRenderer;

    private boolean initialized;

    public WaterSurfaceManager(int zoneId, int actId, Pattern[] cpzPatterns, Pattern[] arzPatterns) {
        this.zoneId = zoneId;
        this.actId = actId;
        this.cpzPatterns = cpzPatterns;
        this.arzPatterns = arzPatterns;

        // Create sprite sheets with mappings
        // CPZ uses Y offset -8 (centered on waterline)
        // ARZ uses Y offset -4 (half tile above waterline to align with palette boundary)
        List<SpriteMappingFrame> cpzMappings = createWaterSurfaceMappings(cpzPatterns.length, -8);
        List<SpriteMappingFrame> arzMappings = createWaterSurfaceMappings(arzPatterns.length, -4);

        this.cpzSheet = new ObjectSpriteSheet(cpzPatterns, cpzMappings, 0, 1);
        this.arzSheet = new ObjectSpriteSheet(arzPatterns, arzMappings, 0, 1);

        this.cpzRenderer = new PatternSpriteRenderer(cpzSheet);
        this.arzRenderer = new PatternSpriteRenderer(arzSheet);

        this.initialized = false;
    }

    /**
     * Create sprite mappings for water surface animation.
     * <p>
     * Water surface art is organized as horizontal bands that tile across the
     * screen.
     * The sprite consists of multiple 8x8 tiles arranged in a horizontal strip.
     * 
     * @param patternCount Number of patterns available
     * @param yOffset      Y offset for sprite positioning (CPZ uses -8, ARZ uses -4)
     * @return List of mapping frames for animation
     */
    private List<SpriteMappingFrame> createWaterSurfaceMappings(int patternCount, int yOffset) {
        List<SpriteMappingFrame> frames = new ArrayList<>();

        // Based on original obj04.asm water surface object:
        // The water surface uses 32-pixel wide segments (4 tiles wide, 2 tiles tall for
        // a 32x16 sprite)
        // CPZ: 24 blocks = 24 tiles worth of patterns across multiple frames
        // ARZ: 16 blocks = 16 tiles worth of patterns across multiple frames
        //
        // Frame layout: Each frame is a 4x2 tile segment (32x16 pixels)

        int tilesPerSegment = 4; // 32 pixels / 8 pixels per tile
        int rowsPerFrame = 2; // Each segment is 16 pixels tall
        int tilesPerFrame = tilesPerSegment * rowsPerFrame;

        int frameCount = patternCount / tilesPerFrame;
        if (frameCount < 1)
            frameCount = 1;

        for (int f = 0; f < frameCount; f++) {
            List<SpriteMappingPiece> pieces = new ArrayList<>();
            int baseTile = f * tilesPerFrame;

            // Create a 4x2 tile sprite piece (32x16 pixels)
            // Y offset is zone-specific: CPZ uses -8 (centered), ARZ uses -4
            pieces.add(new SpriteMappingPiece(
                    -16, yOffset, // Centered horizontally, zone-specific Y offset
                    4, 2, // 4 tiles wide, 2 tiles tall (32x16)
                    baseTile,
                    false, false, 0));

            frames.add(new SpriteMappingFrame(pieces));
        }

        // If we have no frames at all, create at least one empty frame
        if (frames.isEmpty()) {
            frames.add(new SpriteMappingFrame(new ArrayList<>()));
        }

        return frames;
    }

    /**
     * Ensure patterns are cached in the graphics manager.
     * 
     * @param graphicsManager The graphics manager
     * @param baseIndex       Starting pattern index for caching
     * @return Next available pattern index after caching
     */
    public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
        int next = baseIndex;

        // Cache CPZ patterns
        cpzRenderer.ensurePatternsCached(graphicsManager, next);
        next += cpzPatterns.length;

        // Cache ARZ patterns
        arzRenderer.ensurePatternsCached(graphicsManager, next);
        next += arzPatterns.length;

        this.initialized = true;
        LOGGER.fine(String.format("Water surface patterns cached: CPZ=%d, ARZ=%d, base=%d",
                cpzPatterns.length, arzPatterns.length, baseIndex));

        return next;
    }

    /**
     * Check if water surface should be rendered for the current level.
     */
    public boolean shouldRenderWaterSurface() {
        WaterSystem waterSystem = WaterSystem.getInstance();
        if (!waterSystem.hasWater(zoneId, actId)) {
            return false;
        }

        // Only render for CPZ Act 2, ARZ Act 1, and ARZ Act 2
        if (zoneId == ZONE_CPZ && actId == 1)
            return true; // CPZ Act 2
        if (zoneId == ZONE_ARZ)
            return true; // ARZ Act 1 or 2

        return false;
    }

    /**
     * Render water surface sprites at the water line.
     * <p>
     * The original game alternates rendering half of the water surface sprites
     * each frame to stay under the per-scanline sprite limit. We implement
     * this by only rendering even or odd segments based on the frame counter.
     *
     * @param camera       The camera
     * @param frameCounter Current frame number for animation and alternation
     */
    public void render(Camera camera, int frameCounter) {
        if (!initialized || !shouldRenderWaterSurface()) {
            return;
        }

        WaterSystem waterSystem = WaterSystem.getInstance();
        // Use visual water level (with oscillation) for rendering the water surface
        // sprites
        int waterLevelY = waterSystem.getVisualWaterLevelY(zoneId, actId);

        // Get visible area
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int cameraWidth = camera.getWidth();

        // Only render if water line is visible on screen
        int waterScreenY = waterLevelY - cameraY;
        if (waterScreenY < -16 || waterScreenY > camera.getHeight()) {
            return;
        }

        // Get the appropriate renderer based on zone
        PatternSpriteRenderer renderer = (zoneId == ZONE_CPZ) ? cpzRenderer : arzRenderer;
        if (!renderer.isReady()) {
            return;
        }

        // Calculate how many segments we need to cover the screen
        int startX = cameraX - SEGMENT_WIDTH_PIXELS;
        startX = (startX / SEGMENT_WIDTH_PIXELS) * SEGMENT_WIDTH_PIXELS; // Align to segment boundary
        int endX = cameraX + cameraWidth + SEGMENT_WIDTH_PIXELS;

        // Determine animation frame based on frame counter
        // Water surface animates slowly - change frame every 16 frames (matches
        // original game)
        // Animation uses ping-pong pattern: 0-1-2-1-0-1-2-1... (bounces back instead of
        // looping)
        int mappingFrameCount = (zoneId == ZONE_CPZ) ? cpzSheet.getFrameCount() : arzSheet.getFrameCount();
        int animFrame;
        if (mappingFrameCount <= 1) {
            animFrame = 0;
        } else {
            // Ping-pong cycle length is (frameCount - 1) * 2
            // For 3 frames: cycle is 0,1,2,1 = 4 steps
            int cycleLength = (mappingFrameCount - 1) * 2;
            int position = (frameCounter / 16) % cycleLength;
            // If position < frameCount, use position directly; otherwise bounce back
            if (position < mappingFrameCount) {
                animFrame = position;
            } else {
                animFrame = cycleLength - position;
            }
        }

        // Alternate which segments are drawn each frame (even/odd based on
        // frameCounter)
        // This mimics the original game's sprite-saving technique
        boolean drawEven = (frameCounter % 2) == 0;

        int segment = 0;
        for (int x = startX; x < endX; x += SEGMENT_WIDTH_PIXELS) {
            // Alternate: draw even segments on even frames, odd on odd frames
            boolean shouldDraw = (segment % 2 == 0) ? drawEven : !drawEven;

            if (shouldDraw) {
                // Draw the water surface segment at this position
                // Y position is at the water line, centered on the segment
                renderer.drawFrameIndex(animFrame, x + SEGMENT_WIDTH_PIXELS / 2, waterLevelY, false, false);
            }
            segment++;
        }
    }

    /**
     * Get the CPZ water surface renderer.
     */
    public PatternSpriteRenderer getCpzRenderer() {
        return cpzRenderer;
    }

    /**
     * Get the ARZ water surface renderer.
     */
    public PatternSpriteRenderer getArzRenderer() {
        return arzRenderer;
    }

    /**
     * Get the CPZ water surface sheet.
     */
    public ObjectSpriteSheet getCpzSheet() {
        return cpzSheet;
    }

    /**
     * Get the ARZ water surface sheet.
     */
    public ObjectSpriteSheet getArzSheet() {
        return arzSheet;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
