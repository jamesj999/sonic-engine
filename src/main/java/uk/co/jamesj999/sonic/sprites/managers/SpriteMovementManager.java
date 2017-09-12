package uk.co.jamesj999.sonic.sprites.managers;

public interface SpriteMovementManager {

    public void handleMovement(boolean left, boolean right, boolean space,
                               boolean down, boolean testKey);

}
