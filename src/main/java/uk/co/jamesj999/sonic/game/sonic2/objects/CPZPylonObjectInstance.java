package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
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
 * - Art: ArtNem_CPZMetalThings at ROM 0x825AE
 * - Mappings: 1 frame with 9 sprite pieces (4x4 tiles each), arranged vertically
 * - All pieces use tile 0 with alternating vertical flip for symmetry
 * - Priority: 7 (highest - renders first/furthest back)
 * <p>
 * Parallax behavior (from disassembly):
 * - Visibility: (Camera_X_pos & 0x3FF) < 0x2E0
 * - X pixel = -3 * (Camera_X_pos & 0x3FF) / 4  (moves left as camera moves right)
 * - Y pixel = 0x100 - ((Camera_Y_pos / 2) & 0x3F)
 * <p>
 * This object is NOT loaded from level object data - it is created automatically
 * when CPZ loads and uses camera-relative positioning.
 */
public class CPZPylonObjectInstance extends AbstractObjectInstance {

    // Maximum camera X value (masked) for visibility
    private static final int VISIBILITY_THRESHOLD = 0x2E0;

    // Camera X mask for parallax calculation
    private static final int CAMERA_X_MASK = 0x3FF;

    // Camera Y mask after shift
    private static final int CAMERA_Y_MASK = 0x3F;

    // Base Y offset for the pylon
    private static final int BASE_Y = 0x100;

    public CPZPylonObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    @Override
    public int getX() {
        // X position uses parallax: -3 * (cameraX & 0x3FF) / 4
        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        int masked = cameraX & CAMERA_X_MASK;
        // Original: asr.w #1,d1 -> d1/2; move.w d1,d0; asr.w #1,d1 -> d1/4; add.w d1,d0 -> 3/4; neg.w d0
        int x = (masked >> 1) + (masked >> 2);  // 3/4 of masked value
        return -x;
    }

    @Override
    public int getY() {
        // Y position: 0x100 - ((cameraY / 2) & 0x3F)
        Camera camera = Camera.getInstance();
        int cameraY = camera.getY();
        int yOffset = (cameraY >> 1) & CAMERA_Y_MASK;
        return BASE_Y - yOffset;
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

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_PYLON);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        int cameraY = camera.getY();

        // Calculate screen position using correct parallax formulas
        int screenX = getX();
        int screenY = getY();

        // Convert to world position for rendering
        // Screen coordinates are relative to camera, so:
        // worldX = cameraX + screenX
        // worldY = cameraY + screenY
        int worldX = cameraX + screenX;
        int worldY = cameraY + screenY;

        // Render frame 0 (the single frame containing all 9 pieces)
        renderer.drawFrameIndex(0, worldX, worldY, false, false);
    }
}
