package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.rings.RingManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Ring sparkle effect used by signpost during spin.
 * Uses ring sparkle animation frames.
 * Self-destructs after animation completes.
 */
public class SignpostSparkleObjectInstance extends AbstractObjectInstance {

    // Animation timing
    private static final int FRAME_DELAY = 4; // Frames between animation steps

    private int animTimer = 0;
    private int animFrame = 0;
    private int totalFrames = 4; // Default, will be updated from RingManager
    private int sparkleStartIndex = 4; // Default sparkle frame start
    private final int worldX;
    private final int worldY;

    public SignpostSparkleObjectInstance(int x, int y) {
        super(null, "signpost_sparkle_" + x + "_" + y);
        this.worldX = x;
        this.worldY = y;

        // Try to get actual sparkle frame info from RingManager
        RingManager ringManager = LevelManager.getInstance().getRingManager();
        if (ringManager != null) {
            int count = ringManager.getSparkleFrameCount();
            if (count > 0) {
                totalFrames = count;
            }
            sparkleStartIndex = ringManager.getSparkleStartIndex();
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        animTimer++;
        if (animTimer >= FRAME_DELAY) {
            animTimer = 0;
            animFrame++;
            if (animFrame >= totalFrames) {
                setDestroyed(true);
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        RingManager ringManager = LevelManager.getInstance().getRingManager();
        if (ringManager == null) {
            return;
        }
        // Render current sparkle frame
        int frame = sparkleStartIndex + animFrame;
        ringManager.drawFrameIndex(frame, worldX, worldY);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(2); // Above background, below player
    }

    @Override
    public int getX() {
        return worldX;
    }

    @Override
    public int getY() {
        return worldY;
    }
}
