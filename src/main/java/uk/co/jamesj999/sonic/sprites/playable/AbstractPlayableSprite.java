package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.sprites.AbstractSprite;
import uk.co.jamesj999.sonic.sprites.managers.DebugSpriteMovementManager;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
import uk.co.jamesj999.sonic.sprites.managers.PlayableSpriteMovementManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteMovementManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;

/**
 * Movement speeds are in subpixels (256 subpixels per pixel...).
 * 
 * @author james
 * 
 */
public abstract class AbstractPlayableSprite extends AbstractSprite {
	protected final SpriteMovementManager movementManager;

	protected GroundMode runningMode = GroundMode.GROUND;

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

    /**
     * Whether or not this sprite is preparing for a spindash.
     */
    protected boolean spindash = false;

    protected float spindashConstant = 0f;

	public boolean getAir() {
		return air;
	}

	public void setAir(boolean air) {
		//TODO Update ground sensors here
		this.air = air;
		if (air) {
			setGroundMode(GroundMode.GROUND);
		}
	}

	public short getJump() {
		return jump;
	}

    public boolean getSpindash() {
        return spindash;
    }

    public void setSpindash(boolean spindash) {
        this.spindash = spindash;
    }

    public float getSpindashConstant() {
        return spindashConstant;
    }

    public void setSpindashConstant(float spindashConstant) {
        this.spindashConstant = spindashConstant;
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

	protected AbstractPlayableSprite(String code, short x, short y, boolean debug) {
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
		if(debug) {
			movementManager = new DebugSpriteMovementManager(this);
		} else {
			movementManager = new PlayableSpriteMovementManager(this);
		}
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
        if(GroundMode.CEILING.equals(runningMode) || GroundMode.GROUND.equals(runningMode)) {
            if (rolling) {
                setHeight(rollHeight);
            } else {
                setHeight(runHeight);
            }
        } else {
            if(rolling) {
                setWidth(rollHeight);
            } else {
                setWidth(runHeight);
            }
        }
		this.rolling = rolling;
	}

	@Override
	public void setHeight(int height) {
		super.setHeight(height);
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

	public SpriteMovementManager getMovementManager() {
		return movementManager;
	}

	protected abstract void defineSpeeds();

	public final void move() {
		move(xSpeed, ySpeed);
	}

	public GroundMode getGroundMode() {
		return runningMode;
	}

	public void setGroundMode(GroundMode groundMode) {
		if(this.runningMode != groundMode) {
			updateSpriteShapeForRunningMode(groundMode, this.runningMode);
			this.runningMode = groundMode;
		}
	}

	protected void updateSpriteShapeForRunningMode(GroundMode newRunningMode, GroundMode oldRunningMode) {
		// Best if statement ever...
		if(((GroundMode.CEILING.equals(runningMode) || GroundMode.GROUND.equals(runningMode)) &&
				(GroundMode.LEFTWALL.equals(oldRunningMode) || GroundMode.RIGHTWALL.equals(oldRunningMode))) ||
				((GroundMode.RIGHTWALL.equals(runningMode) || GroundMode.LEFTWALL.equals(runningMode)) &&
						((GroundMode.CEILING.equals(oldRunningMode) || GroundMode.GROUND.equals(oldRunningMode))))) {
			int oldHeight = getHeight();
			setHeight(width);
			setWidth(oldHeight);
		}
	}

	public final short getCentreX(int framesBehind) {
		int desired = historyPos - framesBehind;
		if (desired < 0) {
			desired += xHistory.length;
		}
		return (short) (xHistory[desired] + (width / 2));
	}

	public final short getCentreY(int framesBehind) {
		int desired = historyPos - framesBehind;
		if (desired < 0) {
			desired += yHistory.length;
		}
		return (short) (yHistory[desired] + (height / 2));
	}

	public void updateSensors(short originalX, short originalY) {
		Sensor[] sensorsToActivate;
		Sensor[] sensorsToDeactivate;

		Sensor groundA = groundSensors[0];
		Sensor groundB = groundSensors[1];

		Sensor ceilingC = ceilingSensors[0];
		Sensor ceilingD = ceilingSensors[1];

		Sensor pushE = pushSensors[0];
		Sensor pushF = pushSensors[1];

		if (getAir()) {
			short xSpeedPositive = (short) Math.abs(xSpeed);
			short ySpeedPositive = (short) Math.abs(ySpeed);

			if(xSpeedPositive > ySpeedPositive) {
				if (xSpeed > 0) {
					sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD, pushF };
					sensorsToDeactivate = new Sensor[] { pushE };
				} else {
					sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD, pushE };
					sensorsToDeactivate = new Sensor[] { pushF };
				}
			} else {
				if(ySpeed > 0) {
					sensorsToActivate = new Sensor[] { groundA, groundB, pushE, pushF };
					sensorsToDeactivate = new Sensor[] { ceilingC, ceilingD };
				} else {
					sensorsToActivate = new Sensor[] { ceilingC, ceilingD, pushE, pushF };
					sensorsToDeactivate = new Sensor[] { groundA, groundB };
				}
			}
		}  else {
			boolean pushActive = Math.abs(angle) < 32;
			if (xSpeed > 0) {
				sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD, pushF};
				sensorsToDeactivate = new Sensor[] { pushE };
				if(!pushActive) {
					sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD };
					sensorsToDeactivate = new Sensor[] { pushE, pushF };
				}
			} else if (xSpeed < 0) {
				sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD, pushE};
				sensorsToDeactivate = new Sensor[] { pushF };
				if(!pushActive) {
					sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD };
					sensorsToDeactivate = new Sensor[] { pushE, pushF };
				}
			} else {
				sensorsToActivate = new Sensor[] { groundA, groundB, ceilingC, ceilingD};
				sensorsToDeactivate = new Sensor[] { pushE, pushF };
			}
		}

		setSensorActive(sensorsToActivate, true);
		setSensorActive(sensorsToDeactivate, false);
	}

	private void setSensorActive(Sensor[] sensors, boolean active) {
		for(Sensor sensor : sensors) {
			sensor.setActive(active);
		}
	}

	public Sensor[] getAllSensors() {
		Sensor[] sensors = new Sensor[6];
		sensors[0] = groundSensors[0];
		sensors[1] = groundSensors[1];
		sensors[2] = ceilingSensors[0];
		sensors[3] = ceilingSensors[1];
		sensors[4] = pushSensors[0];
		sensors[5] = pushSensors[1];

		return sensors;
	}

	public void moveForGroundModeAndDirection(byte distance, Direction direction) {
		SensorConfiguration sensorConfiguration = SpriteManager.getSensorConfigurationForGroundModeAndDirection(getGroundMode(), direction);
		switch (sensorConfiguration.direction()) {
			case DOWN -> {
				yPixel = (short) (yPixel + distance);
			}
			case RIGHT -> {
				xPixel = (short) (xPixel + distance);
			}
			case UP -> {
				yPixel = (short) (yPixel - distance);
			}
			case LEFT -> {
				xPixel = (short) (xPixel - distance);
			}
		}
	}

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
