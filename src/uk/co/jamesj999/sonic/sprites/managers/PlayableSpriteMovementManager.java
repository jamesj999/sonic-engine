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
		short gSpeed = sprite.getGSpeed();

		short xSpeed = sprite.getXSpeed();
		short ySpeed = sprite.getYSpeed();

		// Store whether or not we were in the air at the beginning of this tick
		boolean initialAir = sprite.getAir();

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

		// a height of -1 indicates no heightmap was found meaning we're not on
		// a solid tile
		// we also ignore heights of 0 because they are meaningless
		short realHeight = terrainCollisionManager
				.calculateTerrainHeight(sprite);
		short height = (short) (realHeight % 16);
		int angle = (int) ((256 - sprite.getAngle()) * 1.40625);

		// Small hack to make sure the angle is between -360 and 360.
		while (angle >= 360) {
			angle -= 360;
		}
		while (angle <= -360) {
			angle += 360;
		}

		if (!sprite.getAir()) {
			if (height < 0) {
				sprite.setAir(true);
			}
			if (jump && !jumpPressed) {
				jump = true;
				sprite.setAir(true);
				sprite.setRolling(true);
				jumpPressed = true;
				xSpeed -= sprite.getJump() * Math.sin(Math.toRadians(angle));
				ySpeed += sprite.getJump() * Math.cos(Math.toRadians(angle));
			}
		}
		if (!sprite.getAir()) {
			if (height > 0) {
				sprite.setY((short) (realHeight + 20));
			}
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
			gSpeed -= slopeRunningVariant
					* Math.sin(Math.toRadians((256 - sprite.getAngle()) * 1.40625));
			if (left) {
				if (gSpeed > 0) {
					gSpeed -= decel;
				} else {
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
				} else {
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
			xSpeed = (short) Math.round(gSpeed
					* Math.cos(Math.toRadians(angle)));

			ySpeed = (short) Math.round(gSpeed
					* Math.sin(Math.toRadians(angle)));
		}
		if (sprite.getAir()) {
			if (height > 0) {
				if (ySpeed < 0) {
					if ((sprite.getCentreY() - 20) < realHeight) {
						sprite.setAir(false);
						sprite.setY((short) (realHeight + 20));
					}
				}
			}
			// Landing, so calculate our ground speed from the angle of the
			// slope:
			if (!sprite.getAir()) {
				sprite.setRolling(false);
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
							gSpeed = (short) (ySpeed * -(Math.signum(Math
									.cos(Math.toRadians(angle)))));
						}
					}
				}
			}
		}
		if (sprite.getAir()) {
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
		}

		if (!jump && jumpPressed) {
			if (ySpeed > 1024) {
				ySpeed = (short) 1024;
			}
			if (ySpeed == 0) {
				jumpPressed = false;
			}
		}
		sprite.setGSpeed(gSpeed);

		sprite.setXSpeed(xSpeed);
		sprite.setYSpeed(ySpeed);

		if (height >= 0 && !sprite.getAir()) {
			sprite.move(xSpeed, (short) 0);
		} else {
			sprite.move(xSpeed, ySpeed);
		}
		// sprite.setY((short) 100);
		// Temporary 'death' detection just resets X/Y of sprite.
		if (sprite.getY() <= 0) {
			sprite.setX((short) 50);
			sprite.setY((short) 50);
			sprite.setXSpeed((short) 0);
			sprite.setYSpeed((short) 0);
			sprite.setGSpeed((short) 0);
		}
		// System.out.println(sprite.getX() + "," + sprite.getXSubpixel() + "x"
		// + sprite.getY() + "," + sprite.getYSubpixel());
	}

	@Override
	public void handleCollisions(boolean up, boolean down) {
		// temporarily changing the angle in here to test angled running
		if (down) {
			sprite.setAngle((byte) (sprite.getAngle() + 1));
			System.out.println(sprite.getAngle());
		}
		if (up) {
			sprite.setAngle((byte) (sprite.getAngle() - 1));
			System.out.println(sprite.getAngle());
		}

	}
}
