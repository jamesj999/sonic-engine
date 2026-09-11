package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.rings.RingManager;

import java.util.List;
import java.util.logging.Logger;

/**
 * Short-lived sparkle effect spawned by the S3K signpost during its fall.
 */
public class S3kSignpostSparkleChild extends AbstractObjectInstance implements SpawnRewindRecreatable {

    private static final Logger LOG = Logger.getLogger(S3kSignpostSparkleChild.class.getName());

    private static final int FRAME_DELAY = 4;
    private static final int TOTAL_FRAMES = 4;

    private int worldX;
    private int worldY;
    private int animTimer;
    private int animFrame;

    public S3kSignpostSparkleChild(int x, int y) {
        super(null, "S3kSignpostSparkle");
        this.worldX = x;
        this.worldY = y;
    }

    public S3kSignpostSparkleChild(ObjectSpawn spawn) {
        this(spawn != null ? spawn.x() : 0, spawn != null ? spawn.y() : 0);
    }

    @Override
    public int getX() {
        return worldX;
    }

    @Override
    public int getY() {
        return worldY;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        animTimer++;
        if (animTimer >= FRAME_DELAY) {
            animTimer = 0;
            animFrame++;
            if (animFrame >= TOTAL_FRAMES) {
                setDestroyed(true);
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Try to use RingManager's sparkle frames if available
        try {
            RingManager ringManager = services().ringManager();
            if (ringManager != null) {
                int startIdx = ringManager.getSparkleStartIndex();
                ringManager.drawFrameIndex(startIdx + animFrame, worldX, worldY);
                return;
            }
        } catch (Exception e) {
            LOG.fine(() -> "S3kSignpostSparkleChild.appendRenderCommands: " + e.getMessage());
        }

        // Placeholder: small diamond shape
        float r = 1.0f, g = 1.0f, b = 0.5f;
        int size = 3 - animFrame; // Shrinks over time
        if (size < 1) size = 1;
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, worldX - size, worldY, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, worldX + size, worldY, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, worldX, worldY - size, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1,
                GLCommand.BlendType.SOLID, r, g, b, worldX, worldY + size, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(2);
    }
}
