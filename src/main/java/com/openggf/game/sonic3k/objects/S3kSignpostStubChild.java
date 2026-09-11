package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Signpost stub/post child object.
 *
 * <p>Simple child that follows the signpost with a Y offset of +0x18,
 * representing the post/pole beneath the spinning sign face.
 * Self-destroys when the parent signpost is destroyed.
 */
public class S3kSignpostStubChild extends AbstractObjectInstance implements RewindRecreatable {

    private static final Logger LOG = Logger.getLogger(S3kSignpostStubChild.class.getName());

    private static final int Y_OFFSET = 0x18;
    @RewindTransient(reason = "parent signpost is relinked from the restore-time graph during recreate")
    private final S3kSignpostInstance parent;
    private int currentX;
    private int currentY;

    public S3kSignpostStubChild(S3kSignpostInstance parent) {
        super(null, "S3kSignpostStub");
        this.parent = parent;
        syncPosition();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        S3kSignpostInstance liveParent = activeSignpostForRewind(ctx);
        return liveParent == null ? null : new S3kSignpostStubChild(liveParent);
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (parent == null || parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }
        syncPosition();
    }

    private static S3kSignpostInstance activeSignpostForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null) {
            return null;
        }
        ObjectManager objectManager = ctx.objectServices().objectManager();
        if (objectManager == null) {
            return null;
        }
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof S3kSignpostInstance signpost && !signpost.isDestroyed()) {
                return signpost;
            }
        }
        return null;
    }

    private void syncPosition() {
        if (parent != null) {
            currentX = parent.getWorldX();
            currentY = parent.getWorldY() + Y_OFFSET;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getStubRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        // Single frame (frame 0) at stub position
        renderer.drawFrameIndex(0, currentX, currentY, false, false);
    }

    private PatternSpriteRenderer getStubRenderer() {
        try {
            var renderManager = services().renderManager();
            if (renderManager != null) {
                return renderManager.getRenderer(Sonic3kObjectArtKeys.SIGNPOST_STUB);
            }
        } catch (Exception e) {
            LOG.fine(() -> "S3kSignpostStubChild.getStubRenderer: " + e.getMessage());
        }
        return null;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}
