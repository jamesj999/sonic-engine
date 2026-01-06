package uk.co.jamesj999.sonic.level.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public class ShieldObjectInstance extends AbstractObjectInstance {
    private final AbstractPlayableSprite player;
    private final PatternSpriteRenderer renderer;
    // Animation sequence from disassembly (Ani_obj38): 5, 0, 5, 1, 5, 2, 5, 3, 5, 4
    // Alternates between expanded frame (5) and smaller frames (0-4)
    private static final int[] ANIMATION_SEQUENCE = { 5, 0, 5, 1, 5, 2, 5, 3, 5, 4 };
    private static final int ANIMATION_SPEED = 1; // frames per step (disassembly uses delay 0)

    private int sequenceIndex = 0;
    private boolean destroyed = false;
    private boolean visible = true;

    public ShieldObjectInstance(AbstractPlayableSprite player) {
        super(null, "Shield");
        this.player = player;
        // ... (lines 24-32 omitted for brevity in prompt match, targeting field area)
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

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (destroyed) {
            return;
        }
        // Animation sequence from disassembly - step through the sequence
        if (frameCounter % ANIMATION_SPEED == 0) {
            sequenceIndex++;
            if (sequenceIndex >= ANIMATION_SEQUENCE.length) {
                sequenceIndex = 0;
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed || !visible || renderer == null) {
            return;
        }

        int currentFrame = ANIMATION_SEQUENCE[sequenceIndex];
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
