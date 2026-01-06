package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public class ShieldObjectInstance extends AbstractObjectInstance {
    private final AbstractPlayableSprite player;
    private final PatternSpriteRenderer renderer;
    private static final int ANIMATION_SPEED = 2; // frames per frame
    private static final int FRAME_COUNT = 6;

    private int currentFrame = 0;
    private boolean destroyed = false;

    public ShieldObjectInstance(AbstractPlayableSprite player) {
        super(null, "Shield");
        this.player = player;

        // Use renderer from ObjectRenderManager
        ObjectRenderManager renderManager = null;
        if (LevelManager.getInstance() != null) {
            renderManager = LevelManager.getInstance().getObjectRenderManager();
        }
        if (renderManager != null) {
            this.renderer = renderManager.getShieldRenderer();
        } else {
            this.renderer = null;
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (destroyed) {
            return;
        }
        // Simple animation loop
        if (frameCounter % ANIMATION_SPEED == 0) {
            currentFrame++;
            if (currentFrame >= FRAME_COUNT) {
                currentFrame = 0;
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed || renderer == null) {
            return;
        }

        renderer.drawFrameIndex(currentFrame, player.getCentreX(), player.getCentreY(), false, false);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    public void destroy() {
        setDestroyed(true);
    }
}
