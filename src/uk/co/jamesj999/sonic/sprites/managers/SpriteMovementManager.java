package uk.co.jamesj999.sonic.sprites.managers;

public interface SpriteMovementManager {
	public void handleMovement(boolean left, boolean right);

	public void handleGravity();

	public void handleCollisions();
}
