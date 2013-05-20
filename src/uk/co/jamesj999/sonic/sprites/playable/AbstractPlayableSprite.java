package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteMovementManager;

public abstract class AbstractPlayableSprite extends AbstractSprite {
	protected final PlayableSpriteMovementManager movementManager;

	protected float xSpeed = 0f;
	protected float ySpeed = 0f;
	protected float runAccel;
	protected float runDecel;
	protected float friction;
	protected float max;

	protected AbstractPlayableSprite(String code, int x, int y) {
		super(code, x, y);
		defineSpeeds();
		movementManager = new PlayableSpriteMovementManager(this);
	}

	public float getXSpeed() {
		return xSpeed;
	}

	public void setXSpeed(float xSpeed) {
		this.xSpeed = xSpeed;
	}

	public float getYSpeed() {
		return ySpeed;
	}

	public float getRunAccel() {
		return runAccel;
	}

	public float getRunDecel() {
		return runDecel;
	}

	public float getFriction() {
		return friction;
	}

	public float getMax() {
		return max;
	}

	public PlayableSpriteMovementManager getMovementManager() {
		return movementManager;
	}

	protected abstract void defineSpeeds();
}
