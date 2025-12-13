package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.Engine;
import uk.co.jamesj999.sonic.sprites.Sprite;

public class DebugSpriteMovementManager extends AbstractSpriteMovementManager {
    private static int MOVE_SPEED = 3;
    private boolean testKeyPressed = false;
    private int debounceTestKey = 0;
    public DebugSpriteMovementManager(Sprite sprite) {
        super(sprite);
    }

    public void handleMovement(boolean up, boolean down, boolean left, boolean right, boolean space, boolean testKey) {
        if(left) {
            sprite.setX((short) (sprite.getX() - MOVE_SPEED));
        }
        if(right) {
            sprite.setX((short) (sprite.getX() + MOVE_SPEED));
        }
        if(up) {
            sprite.setY((short) (sprite.getY() - MOVE_SPEED));
        }
        if(down) {
            sprite.setY((short) (sprite.getY() + MOVE_SPEED));
        }
        if (space) {
            if (debounceTestKey <= 0) {
                Engine.nextDebugOption();
                debounceTestKey = 10;
            }
            else {
                debounceTestKey--;
            }
        }
        if(testKey){
            if (debounceTestKey <= 0) {
                Engine.nextDebugState();
                debounceTestKey = 10;
            }
            else {
                debounceTestKey--;
            }
        }

        if(!testKey) {
            testKeyPressed = false;
        }
    }
}
