package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteMovementManager;

/**
 * Movement speeds are in subpixels (256 subpixels per pixel...).
 * 
 * @author james
 * 
 */
public abstract class AbstractPlayableSprite extends AbstractSprite {
	protected final PlayableSpriteMovementManager movementManager;
	/**
	 * gSpeed is the speed this sprite is moving across the 'ground'.
	 * Calculations will be performed against this and 'angle' to calculate new
	 * x/y values for each step.
	 */
	protected short gSpeed = 0;
	protected byte angle;

	/**
	 * The amount this sprite's speed is effected by when running down/up a
	 * slope.
	 */
	protected short slopeRunning;
	/**
	 * The amount this sprite's speed is effected by when rolling down/up a
	 * slope.
	 */
	protected short slopeRolling;
	/**
	 * The speed at which this sprite accelerates when running.
	 */
	protected short runAccel;
	/**
	 * The speed at which this sprite decelerates when the opposite direction is
	 * pressed.
	 */
	protected short runDecel;
	/**
	 * The speed at which this sprite slows down while running with no
	 * directional keys pressed.
	 */
	protected short friction;
	/**
	 * Maximum running speed of this Sprite per step.
	 */
	protected short max;

	protected AbstractPlayableSprite(String code, short x, short y) {
		super(code, x, y);
		// Must define speeds before creating Manager (it will read speeds upon
		// instantiation).
		defineSpeeds();
		movementManager = new PlayableSpriteMovementManager(this);
	}

	public short getGSpeed() {
		return gSpeed;
	}

	public void setGSpeed(short gSpeed) {
		this.gSpeed = gSpeed;
	}

	public short getRunAccel() {
		return runAccel;
	}

	public short getRunDecel() {
		return runDecel;
	}

	public short getSlopeRunning() {
		return slopeRunning;
	}

	public short getSlopeRolling() {
		return slopeRolling;
	}

	public short getFriction() {
		return friction;
	}

	public short getMax() {
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
