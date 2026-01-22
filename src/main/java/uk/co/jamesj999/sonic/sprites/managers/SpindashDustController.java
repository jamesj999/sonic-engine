package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.Tails;
import uk.co.jamesj999.sonic.sprites.render.PlayerSpriteRenderer;

/**
 * Handles spindash dust animation and drawing.
 */
public class SpindashDustController {
    private static final int[] DASH_FRAMES = { 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10 };
    private static final int FRAME_DELAY = 1;
    private static final int TAILS_Y_OFFSET = -4;

    private final AbstractPlayableSprite sprite;
    private final PlayerSpriteRenderer renderer;
    private int frameIndex;
    private int frameTick;
    private int currentFrame;
    private boolean activeLastTick;

    public SpindashDustController(AbstractPlayableSprite sprite, PlayerSpriteRenderer renderer) {
        this.sprite = sprite;
        this.renderer = renderer;
        this.frameIndex = 0;
        this.frameTick = 0;
        this.currentFrame = DASH_FRAMES[0];
        this.activeLastTick = false;
    }

    public void update() {
        boolean active = isActive();
        if (!active) {
            reset();
            return;
        }
        if (!activeLastTick) {
            activeLastTick = true;
            frameIndex = 0;
            frameTick = 0;
        }
        int duration = frameTick - 1;
        boolean advance = duration < 0;
        if (advance) {
            duration = FRAME_DELAY;
        }
        frameTick = duration;
        currentFrame = DASH_FRAMES[frameIndex];
        if (advance) {
            frameIndex = (frameIndex + 1) % DASH_FRAMES.length;
        }
    }

    public void draw() {
        if (!isActive() || renderer == null) {
            return;
        }
        int originX = sprite.getRenderCentreX();
        int originY = sprite.getRenderCentreY();
        if (sprite instanceof Tails) {
            originY += TAILS_Y_OFFSET;
        }
        boolean hFlip = Direction.LEFT.equals(sprite.getDirection());
        renderer.drawFrame(currentFrame, originX, originY, hFlip, false);
    }

    private boolean isActive() {
        return sprite != null && sprite.getSpindash() && !sprite.getAir();
    }

    private void reset() {
        activeLastTick = false;
        frameIndex = 0;
        frameTick = 0;
        currentFrame = DASH_FRAMES[0];
    }

    /**
     * Returns the renderer used for dust/splash animation.
     * Used by SplashObjectInstance to share the same art assets.
     */
    public PlayerSpriteRenderer getRenderer() {
        return renderer;
    }
}
