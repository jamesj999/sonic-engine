package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteMovementManager;

public abstract class AbstractPlayableSprite extends AbstractSprite {
	protected final PlayableSpriteMovementManager movementManager;
	/**
	 * gSpeed is the speed this sprite is moving across the 'ground'.
	 * Calculations will be performed against this and 'angle' to calculate new
	 * x/y values for each step.
	 */
	protected double gSpeed = 0.00f;
	protected byte angle;

	/**
	 * The amount this sprite's speed is effected by when running down/up a
	 * slope.
	 */
	protected double slopeRunning;
	/**
	 * The amount this sprite's speed is effected by when rolling down/up a
	 * slope.
	 */
	protected double slopeRolling;
	/**
	 * The speed at which this sprite accelerates when running.
	 */
	protected double runAccel;
	/**
	 * The speed at which this sprite decelerates when the opposite direction is
	 * pressed.
	 */
	protected double runDecel;
	/**
	 * The speed at which this sprite slows down while running with no
	 * directional keys pressed.
	 */
	protected double friction;
	/**
	 * Maximum running speed of this Sprite per step.
	 */
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

	public byte getAngle() {
		return angle;
	}

	public void setAngle(byte angle) {
		this.angle = angle;
	}

	public PlayableSpriteMovementManager getMovementManager() {
		return movementManager;
	}

	protected abstract void defineSpeeds();
}
