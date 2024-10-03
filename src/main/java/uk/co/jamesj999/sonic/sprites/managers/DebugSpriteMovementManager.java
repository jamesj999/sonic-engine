package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.sprites.Sprite;

public class DebugSpriteMovementManager extends AbstractSpriteMovementManager {
    public DebugSpriteMovementManager(Sprite sprite) {
        super(sprite);
    }

    public void handleMovement(boolean up, boolean down, boolean left, boolean right, boolean space, boolean testKey) {
        if(left) {
            sprite.setX((short) (sprite.getX() - 1));
        }
        if(right) {
            sprite.setX((short) (sprite.getX() + 1));
        }
        if(up) {
            sprite.setY((short) (sprite.getY() + 1));
        }
        if(down) {
            sprite.setY((short) (sprite.getY() - 1));
        }
    }
}
