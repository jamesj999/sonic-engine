package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 7C - CPZ Pylon.
 * <p>
 * A large decorative pylon that renders behind the stage tiles and player,
 * but in front of the background art. Uses parallax scrolling.
 * <p>
 * Based on disassembly from s2.asm lines 46164-46212:
 * - Art: ArtNem_CPZMetalThings at tile $0373
 * - Mappings: 9 sprite pieces, each 4x4 tiles (32x32 pixels), arranged vertically
 * - Total height: 288 pixels (from Y=-0x80 to Y=0x7F)
 * - Width: 32 pixels
 * <p>
 * Parallax behavior:
 * X position = (Camera_X_pos & 0x3FF) / 2
 * Y position = 0xD0 (208, fixed screen position)
 * Only visible when (Camera_X_pos & 0x3FF) < 0x2E0
 * <p>
 * This object is NOT loaded from level object data - it is created automatically
 * when CPZ loads and uses camera-relative positioning.
 */
public class CPZPylonObjectInstance extends AbstractObjectInstance {

    // Screen Y position (fixed at 208 pixels from top)
    private static final int SCREEN_Y = 0xD0;

    // Maximum camera X value (masked) for visibility
    private static final int VISIBILITY_THRESHOLD = 0x2E0;

    // Camera X mask for parallax calculation
    private static final int CAMERA_X_MASK = 0x3FF;

    // Number of sprite pieces in the pylon
    private static final int PIECE_COUNT = 9;

    // Each piece is 4x4 tiles = 32x32 pixels
    private static final int PIECE_HEIGHT = 32;

    // Starting Y offset for the first piece (from Y=-0x80)
    private static final int START_Y_OFFSET = -0x80;

    public CPZPylonObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    @Override
    public int getX() {
        // X position uses parallax: (cameraX & 0x3FF) / 2
        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        return (cameraX & CAMERA_X_MASK) / 2;
    }

    @Override
    public int getY() {
        // Fixed screen Y position
        return SCREEN_Y;
    }

    @Override
    public boolean isHighPriority() {
        // Render in LOW priority pass (after low-pri tiles, before high-pri tiles)
        // This matches VDP high-priority sprite behavior
        return false;
    }

    @Override
    public int getPriorityBucket() {
        // Bucket 7 renders first (furthest back) in the render loop
        return RenderPriority.MAX;
    }

    /**
     * Checks if the pylon should be visible based on camera position.
     */
    private boolean isVisible() {
        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        return (cameraX & CAMERA_X_MASK) < VISIBILITY_THRESHOLD;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // No update logic needed - pylon is purely decorative
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!isVisible()) {
            return;
        }

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getCpzPylonRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        int cameraY = camera.getY();

        // Calculate screen position (parallax X, fixed Y)
        int screenX = (cameraX & CAMERA_X_MASK) / 2;
        int screenY = SCREEN_Y;

        // Convert to world position for rendering
        int worldX = cameraX + screenX;
        int worldY = cameraY + screenY;

        // Render all 9 pieces vertically
        for (int i = 0; i < PIECE_COUNT; i++) {
            int pieceY = worldY + START_Y_OFFSET + (i * PIECE_HEIGHT);
            renderer.drawFrameIndex(i, worldX, pieceY, false, false);
        }
    }
}
