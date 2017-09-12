package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.sprites.Sprite;

public abstract class AbstractSpriteMovementManager<T extends Sprite> implements
        SpriteMovementManager {
    protected final T sprite;

    protected AbstractSpriteMovementManager(T sprite) {
        this.sprite = sprite;
    }
}
