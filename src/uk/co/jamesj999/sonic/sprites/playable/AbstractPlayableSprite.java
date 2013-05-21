package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteMovementManager;

public abstract class AbstractPlayableSprite extends AbstractSprite {
	protected final PlayableSpriteMovementManager movementManager;
	protected double gSpeed = 0.00f;
	protected double angle;

	protected double slopeRunning;
	protected double slopeRolling;
	protected double runAccel;
	protected double runDecel;
	protected double friction;
	protected double max;

	protected AbstractPlayableSprite(String code, int x, int y) {
		super(code, x, y);
		// Must define speeds before creating Manager (it will read speeds upon
		// instantiation).
		defineSpeeds();
		movementManager = new PlayableSpriteMovementManager(this);
	}

	public double getGSpeed() {
		return gSpeed;
	}

	public void setGSpeed(double gSpeed) {
		this.gSpeed = gSpeed;
	}

	public double getRunAccel() {
		return runAccel;
	}

	public double getRunDecel() {
		return runDecel;
	}

	public double getSlopeRunning() {
		return slopeRunning;
	}

	public double getSlopeRolling() {
		return slopeRolling;
	}

	public double getFriction() {
		return friction;
	}

	public double getMax() {
		return max;
	}

	public double getAngle() {
		return angle;
	}

	public void setAngle(double angle) {
		this.angle = angle;
	}

	public PlayableSpriteMovementManager getMovementManager() {
		return movementManager;
	}

	protected abstract void defineSpeeds();
}
