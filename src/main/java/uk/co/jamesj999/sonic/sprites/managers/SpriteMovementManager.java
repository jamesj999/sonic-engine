package uk.co.jamesj999.sonic.sprites.managers;

public interface SpriteMovementManager {

	public void handleMovement(boolean up, boolean down, boolean left, boolean right, boolean space,
			boolean testKey);

}
