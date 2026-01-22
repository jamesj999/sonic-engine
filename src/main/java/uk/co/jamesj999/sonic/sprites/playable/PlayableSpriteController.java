package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteAnimation;
import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteMovement;
import uk.co.jamesj999.sonic.sprites.managers.SpindashDustController;

public class PlayableSpriteController {
    private final PlayableSpriteMovement movement;
    private final PlayableSpriteAnimation animation;
    private final DrowningController drowning;
    private SpindashDustController spindashDust;

    public PlayableSpriteController(AbstractPlayableSprite sprite) {
        this.movement = new PlayableSpriteMovement(sprite);
        this.animation = new PlayableSpriteAnimation(sprite);
        this.drowning = new DrowningController(sprite);
    }

    public PlayableSpriteMovement getMovement() {
        return movement;
    }

    public PlayableSpriteAnimation getAnimation() {
        return animation;
    }

    public DrowningController getDrowning() {
        return drowning;
    }

    public SpindashDustController getSpindashDust() {
        return spindashDust;
    }

    public void setSpindashDust(SpindashDustController spindashDust) {
        this.spindashDust = spindashDust;
    }
}
