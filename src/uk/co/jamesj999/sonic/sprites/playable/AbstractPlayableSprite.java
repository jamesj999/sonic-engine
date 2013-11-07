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

	protected SpriteRunningMode runningMode = SpriteRunningMode.GROUND;

	/**
	 * gSpeed is the speed this sprite is moving across the 'ground'.
	 * Calculations will be performed against this and 'angle' to calculate new
	 * x/y values for each step.
	 */
	protected short gSpeed = 0;

	/**
	 * Current angle of the terrain this sprite is on.
	 */
	protected byte angle;

	/**
	 * Speed (in subpixels) at which this sprite walks
	 */
	protected short jump = 0;

	protected short xSpeed = 0;
	protected short ySpeed = 0;

	private short[] xHistory = new short[32];
	private short[] yHistory = new short[32];

	private byte historyPos = 0;

	/**
	 * Whether or not this sprite is rolling
	 */
	protected boolean rolling = false;

	/**
	 * Whether or not this sprite is in the air
	 */
	protected boolean air = false;

	public boolean getAir() {
		return air;
	}

	public void setAir(boolean air) {
		//TODO Update ground sensors here
		this.air = air;
	}

	public short getJump() {
		return jump;
	}

	public short getXSpeed() {
		return xSpeed;
	}

	public void setXSpeed(short xSpeed) {
		this.xSpeed = xSpeed;
	}

	public short getYSpeed() {
		return ySpeed;
	}

	public void setYSpeed(short ySpeed) {
		this.ySpeed = ySpeed;
	}

	/**
	 * The amount this sprite's speed is effected by when running down/up a
	 * slope.
	 */
	protected short slopeRunning;
	/**
	 * The amount this sprite's speed is effected by when rolling up a slope.
	 */
	protected short slopeRollingUp;
	/**
	 * The amount this sprite's speed is effected by when rolling down a slope.
	 */
	protected short slopeRollingDown;
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
	 * Maximum rolling speed of this Sprite per step.
	 */
	protected short maxRoll;
	/**
	 * Maximum running speed of this Sprite per step.
	 */
	protected short max;

	/**
	 * The speed at which this sprite slows down while rolling with no
	 * directional keys pressed.
	 */
	protected short rollDecel;

	/**
	 * Minimum speed required to start rolling.
	 */
	protected short minStartRollSpeed;

	/**
	 * Speed at which to stop rolling
	 */
	protected short minRollSpeed;

	/**
	 * Height when rolling
	 */
	protected short rollHeight;

	/**
	 * Height when running
	 */
	protected short runHeight;

	protected AbstractPlayableSprite(String code, short x, short y) {
		super(code, x, y);
		// Must define speeds before creating Manager (it will read speeds upon
		// instantiation).
		defineSpeeds();

		// Set our entire history for x and y to be the starting position so if
		// the player spindashes immediately the camera effect won't be b0rked.
		for (short i = 0; i < 32; i++) {
			xHistory[i] = x;
			yHistory[i] = y;
		}
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

	public short getSlopeRollingUp() {
		return slopeRollingUp;
	}

	public short getSlopeRollingDown() {
		return slopeRollingDown;
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

	public short[] getXHistory() {
		return xHistory;
	}

	public short[] getYHistory() {
		return yHistory;
	}

	public boolean getRolling() {
		return rolling;
	}

	public void setRolling(boolean rolling) {
		if (rolling) {
			setHeight(rollHeight);
		} else {
			setHeight(runHeight);
		}
		this.rolling = rolling;
	}

	@Override
	public void setHeight(int height) {
		super.setHeight(height);
		// if (terrainSensorBox != null) {
		// terrainSensorBox.setYOffset((short) (-(height / 2) - 16));
		// }
	}

	public short getRollDecel() {
		return rollDecel;
	}

	public short getMaxRoll() {
		return maxRoll;
	}

	public short getMinStartRollSpeed() {
		return minStartRollSpeed;
	}

	public short getMinRollSpeed() {
		return minRollSpeed;
	}

	public PlayableSpriteMovementManager getMovementManager() {
		return movementManager;
	}

	protected abstract void defineSpeeds();

	public final void move() {
		move(xSpeed, ySpeed);
	}

	public SpriteRunningMode getRunningMode() {
		return runningMode;
	}

	public void setRunningMode(SpriteRunningMode runningMode) {
		this.runningMode = runningMode;
		updateSensorLinesForRunningMode(runningMode);
	}
	
	protected abstract void updateSensorLinesForRunningMode(SpriteRunningMode runningMode);

	/**
	 * Causes the sprite to update its position history as we are now at the end
	 * of the tick so all movement calculations have been performed.
	 */
	public void endOfTick() {
		if (historyPos == 31) {
			historyPos = 0;
		} else {
			historyPos++;
		}
		xHistory[historyPos] = xPixel;
		yHistory[historyPos] = yPixel;
	}
}
