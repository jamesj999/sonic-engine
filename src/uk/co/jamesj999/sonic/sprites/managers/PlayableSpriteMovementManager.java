package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.physics.TerrainCollisionManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class PlayableSpriteMovementManager extends
		AbstractSpriteMovementManager<AbstractPlayableSprite> {
	private final TerrainCollisionManager terrainCollisionManager = TerrainCollisionManager
			.getInstance();

	private final short max;
	private final short runAccel;
	private final short runDecel;
	private final short slopeRunning;
	private final short minStartRollSpeed;
	private final short minRollSpeed;
	private final short maxRoll;
	private final short slopeRollingUp;
	private final short slopeRollingDown;
	private final short rollDecel;

	private boolean jumpPressed;

	public PlayableSpriteMovementManager(AbstractPlayableSprite sprite) {
		super(sprite);
		max = sprite.getMax();
		runAccel = sprite.getRunAccel();
		runDecel = sprite.getRunDecel();
		slopeRunning = sprite.getSlopeRunning();
		minStartRollSpeed = sprite.getMinStartRollSpeed();
		minRollSpeed = sprite.getMinRollSpeed();
		maxRoll = sprite.getMaxRoll();
		slopeRollingUp = sprite.getSlopeRollingUp();
		slopeRollingDown = sprite.getSlopeRollingDown();
		rollDecel = sprite.getRollDecel();
	}

	/**
	 * Calculates next frame of movement for this Sprite. Since this is a
	 * PlayableSprite, we will need the left and right button presses to
	 * calculate left/right movement.
	 */
	@Override
	public void handleMovement(boolean left, boolean right, boolean down,
			boolean jump) {
		// First thing to do is calculate whether or not to start/stop rolling.
		// If we are in the air, the roll status cannot change, so we don't need
		// to call this method
		if (!sprite.getAir()) {
			calculateRoll(sprite, down);
		}

		// a height of -1 indicates no heightmap was found meaning we're not on
		// a solid tile
		// we also ignore heights of 0 because they are meaningless
		short realHeight = terrainCollisionManager
				.calculateTerrainHeight(sprite);
		short height = (short) (realHeight % 16);

		// Extra handling for jumps. If we have jumped recently we need to check
		// the status of the jump button:
		if (jumpPressed) {
			jumpHandler(jump);
		}

		boolean moveY = true;

		if (sprite.getAir()) {
			if (realHeight > -1
					&& sprite.getYSpeed() < 0
					&& (sprite.getCentreY() - (sprite.getHeight() / 2)) < realHeight) {
				calculateLanding(sprite);
				calculateXYFromGSpeed(sprite);
			} else {
				calculateAirMovement(sprite, left, right);
			}
		} else {
			if (jump && !jumpPressed) {
				jump(sprite);
			} else if (height <= -1) {
				sprite.setAir(true);
				calculateAirMovement(sprite, left, right);
			} else {
				calculateGSpeed(sprite, left, right, realHeight);
				calculateXYFromGSpeed(sprite);
				moveY = false;
			}
		}
		if (moveY) {
			sprite.move();
		} else {
			sprite.setY((short) (realHeight + 16 + (sprite.getHeight() / 2)));
			sprite.move(sprite.getXSpeed(), (short) 0);

		}
		
		short wallPosition = terrainCollisionManager.calculateWallPosition(sprite);
		System.out.println(wallPosition);
		if(wallPosition > -1) {
			sprite.setX(wallPosition);
		}

		// Temporary 'death' detection just resets X/Y of sprite.
		if (sprite.getY() <= 0) {
			sprite.setX((short) 50);
			sprite.setY((short) 50);
			sprite.setXSpeed((short) 0);
			sprite.setYSpeed((short) 0);
			sprite.setGSpeed((short) 0);
		}
	}

	@Override
	public void handleCollisions(boolean up, boolean down) {
		// TODO remove this old method.
	}

	/**
	 * Will calculate gSpeed for Sprite based on current terrain and left/right
	 * keypresses. Only to be used when sprite is on ground. This will only
	 * calculate gSpeed, X/Y speeds must be calculated afterwards.
	 * 
	 * @param sprite
	 *            The sprite in question
	 * @param left
	 *            Whether or not the left key is pressed
	 * @param right
	 *            Whether or not the right key is pressed
	 */
	private void calculateGSpeed(AbstractPlayableSprite sprite, boolean left,
			boolean right, short realHeight) {
		short gSpeed = sprite.getGSpeed();
		int angle = calculateAngle(sprite);

		short friction;
		short slopeRunningVariant;
		short maxSpeed;
		short accel;
		short decel;
		if (!sprite.getRolling()) {
			friction = sprite.getFriction();
			slopeRunningVariant = slopeRunning;
			maxSpeed = max;
			accel = runAccel;
			decel = runDecel;
		} else {
			friction = (short) (sprite.getFriction() / 2);
			double gSpeedSign = Math.signum(gSpeed);
			double angleSign = Math.signum(Math.sin(Math.toRadians(angle)));
			if (gSpeedSign == angleSign) {
				slopeRunningVariant = 80;
			} else {
				slopeRunningVariant = 20;
			}
			accel = 0;
			decel = rollDecel;
			maxSpeed = maxRoll;
		}
		// Running or rolling on the ground
		gSpeed -= slopeRunningVariant * Math.sin(Math.toRadians(angle));
		if (left) {
			if (gSpeed > 0) {
				gSpeed -= decel;
			} else if (!sprite.getRolling()) {
				if (gSpeed > -maxSpeed) {
					gSpeed -= accel;
				} else {
					gSpeed = (short) -maxSpeed;
				}
			}
		}
		if (right) {
			if (gSpeed < 0) {
				gSpeed += decel;
			} else if (!sprite.getRolling()) {
				if (gSpeed < maxSpeed) {
					gSpeed = (short) (gSpeed + accel);
				} else {
					gSpeed = maxSpeed;
				}
			}
		}
		if (!left && !right) {
			if ((gSpeed < friction && gSpeed > 0) || (gSpeed > -friction)
					&& gSpeed < 0) {
				gSpeed = 0;
			} else {
				gSpeed -= Math.min(Math.abs(gSpeed), friction)
						* Math.signum(gSpeed);
			}
		}

		sprite.setGSpeed(gSpeed);
	}

	/**
	 * Causes current sprite to jump. Only to be used when sprite is in the air.
	 * 
	 * @param sprite
	 *            The sprite in question
	 */
	private void jump(AbstractPlayableSprite sprite) {
		sprite.setAir(true);
		sprite.setRolling(true);
		int angle = calculateAngle(sprite);
		jumpPressed = true;
		sprite.setXSpeed((short) (sprite.getXSpeed() - sprite.getJump()
				* Math.sin(Math.toRadians(angle))));
		sprite.setYSpeed((short) (sprite.getYSpeed() + sprite.getJump()
				* Math.cos(Math.toRadians(angle))));
	}

	/**
	 * Calculates air movement for sprite based on gravity and current
	 * left/right keypresses. Only to be used when sprite is in the air.
	 * 
	 * @param sprite
	 *            The sprite in question
	 * @param left
	 *            Whether or not the left key is pressed
	 * @param right
	 *            Whether or not the right key is pressed
	 */
	private void calculateAirMovement(AbstractPlayableSprite sprite,
			boolean left, boolean right) {
		short xSpeed = sprite.getXSpeed();
		short ySpeed = sprite.getYSpeed();
		// In the air
		if (left) {
			if (xSpeed - (2 * runAccel) < -max) {
				xSpeed = (short) -max;
			} else {
				xSpeed -= (2 * runAccel);
			}
		}
		if (right) {
			if (xSpeed + (2 * runAccel) > max) {
				xSpeed = max;
			} else {
				xSpeed += (2 * runAccel);
			}
		}
		// xSpeed = gSpeed;
		if (ySpeed > 0 && ySpeed < 1024) {
			if (Math.abs(xSpeed) >= 32) {
				xSpeed *= 0.96875;
			}
		}
		ySpeed -= sprite.getGravity();

		sprite.setXSpeed(xSpeed);
		sprite.setYSpeed(ySpeed);
	}

	/**
	 * Calculates gSpeed from x/y speed and angle when sprite lands. This should
	 * only be used in the frame the sprite regains contact with the ground,
	 * that is when at the start of the tick the sprite is in the air, but a
	 * height >0 is returned from the Terrain Collision Manager.
	 * 
	 * @param sprite
	 *            The sprite in question
	 */
	private void calculateLanding(AbstractPlayableSprite sprite) {
		sprite.setRolling(false);
		sprite.setAir(false);
		short ySpeed = sprite.getYSpeed();
		short xSpeed = sprite.getXSpeed();
		short gSpeed = sprite.getGSpeed();
		int angle = calculateAngle(sprite);
		if (ySpeed < 0) {
			byte originalAngle = sprite.getAngle();
			if ((originalAngle >= (byte) 0xF0 && originalAngle <= (byte) 0xFF)
					|| (originalAngle >= (byte) 0x00 && originalAngle <= (byte) 0x0F)) {
				gSpeed = xSpeed;
			} else if ((originalAngle >= (byte) 0xE0 && originalAngle <= (byte) 0xEF)
					|| (originalAngle >= (byte) 0x10 && originalAngle <= (byte) 0x1F)) {
				if (Math.abs(xSpeed) > Math.abs(ySpeed)) {
					gSpeed = xSpeed;
				} else {
					gSpeed = (short) (ySpeed * 0.5 * -(Math.signum(Math
							.cos(Math.toRadians(angle)))));
				}
			} else if ((originalAngle >= (byte) 0xC0 && originalAngle <= (byte) 0xDF)
					|| (originalAngle >= (byte) 0x20 && originalAngle <= (byte) 0x3F)) {
				if (Math.abs(xSpeed) > Math.abs(ySpeed)) {
					gSpeed = xSpeed;
				} else {
					gSpeed = (short) (ySpeed * -(Math.signum(Math.cos(Math
							.toRadians(angle)))));
				}
			}
		}
		sprite.setGSpeed(gSpeed);
	}

	private void calculateRoll(AbstractPlayableSprite sprite, boolean down) {
		short gSpeed = sprite.getGSpeed();

		// If the player is pressing down, we're not in the air, we're not
		// currently rolling and our ground speed is greater than the minimum
		// speed, start rolling:
		if (down && !sprite.getAir() && !sprite.getRolling()
				&& (gSpeed > minStartRollSpeed || gSpeed < -minStartRollSpeed)) {
			sprite.setRolling(true);
		}

		// If we're rolling and our ground speed is less than the minimum roll
		// speed then stop rolling:
		if (sprite.getRolling()
				&& ((gSpeed < minRollSpeed && gSpeed >= 0) || (gSpeed > -minRollSpeed && gSpeed <= 0))) {
			sprite.setRolling(false);
		}
	}

	/**
	 * 
	 * @param sprite
	 *            The sprite in question
	 * @return The correct angle, based on 360 degree rotation. Convert this to
	 *         radians before using Math.sin etc.
	 */
	private int calculateAngle(AbstractPlayableSprite sprite) {
		int angle = (int) ((256 - sprite.getAngle()) * 1.40625);

		// Small hack to make sure the angle is between -360 and 360.
		while (angle >= 360) {
			angle -= 360;
		}
		while (angle <= -360) {
			angle += 360;
		}
		return angle;
	}

	private void calculateXYFromGSpeed(AbstractPlayableSprite sprite) {
		short gSpeed = sprite.getGSpeed();
		int angle = calculateAngle(sprite);
		sprite.setXSpeed((short) Math.round(gSpeed
				* Math.cos(Math.toRadians(angle))));

		sprite.setYSpeed((short) -Math.round(gSpeed
				* Math.sin(Math.toRadians(angle))));
	}

	private void jumpHandler(boolean jump) {
		short ySpeedConstant = (4 * 256);
		if (sprite.getYSpeed() > ySpeedConstant) {
			if (!jump) {
				sprite.setYSpeed(ySpeedConstant);
			}
		}
		if (!sprite.getAir() && !jump) {
			jumpPressed = false;
		}
	}
}
