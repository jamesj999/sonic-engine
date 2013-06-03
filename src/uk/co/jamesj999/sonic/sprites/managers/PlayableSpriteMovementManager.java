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
	private final short friction;

	private boolean jumpPressed;

	public PlayableSpriteMovementManager(AbstractPlayableSprite sprite) {
		super(sprite);
		max = sprite.getMax();
		runAccel = sprite.getRunAccel();
		runDecel = sprite.getRunDecel();
		friction = sprite.getFriction();
	}

	/**
	 * Calculates next frame of movement for this Sprite. Since this is a
	 * PlayableSprite, we will need the left and right button presses to
	 * calculate left/right movement.
	 */
	@Override
	public void handleMovement(boolean left, boolean right, boolean jump) {
		short gSpeed = sprite.getGSpeed();

		short xSpeed = sprite.getXSpeed();
		short ySpeed = sprite.getYSpeed();

		// Store whether or not we were in the air at the beginning of this tick
		boolean initialAir = sprite.getAir();

		// a height of -1 indicates no heightmap was found meaning we're not on
		// a solid tile
		// we also ignore heights of 0 because they are meaningless
		short height = terrainCollisionManager.calculateTerrainHeight(sprite);
		int angle = (int) ((256 - sprite.getAngle()) * 1.40625);
		// sprite.getAngle();
		// & 0xFF);

		if (!sprite.getAir()) {
			if (!initialAir) {
				if (left) {
					if (gSpeed > 0) {
						gSpeed -= runDecel;
					} else {
						if (gSpeed > -max) {
							gSpeed -= runAccel;
						} else {
							gSpeed = (short) -max;
						}
					}
				} else if (right) {
					if (gSpeed < 0) {
						gSpeed += runDecel;
					} else {
						if (gSpeed < max) {
							gSpeed = (short) (gSpeed + runAccel);
						} else {
							gSpeed = max;
						}
					}
				} else {
					if ((gSpeed < friction && gSpeed > 0)
							|| (gSpeed > -friction) && gSpeed < 0) {
						gSpeed = 0;
					} else {
						gSpeed -= Math.min(Math.abs(gSpeed), friction)
								* Math.signum(gSpeed);
					}
				}
				xSpeed = (short) Math.round(gSpeed
						* Math.cos(Math.toRadians(angle)));
				ySpeed = 0;// (short) Math.round(gSpeed * -Math.sin(angle &
							// 0xFF));
			} else {
				gSpeed = xSpeed;
			}
		} else {
			xSpeed = sprite.getXSpeed();
			ySpeed = sprite.getYSpeed();
			if (ySpeed > 0 && ySpeed < 1024) {
				if (Math.abs(xSpeed) >= 32) {
					xSpeed *= 0.96875;
				}
			}
		}

		if (height > 0) {
			if (ySpeed < 0) {
				ySpeed = 0;
			}
			ySpeed += 256 * (((short) (height + sprite.getHeight() / 2) - sprite
					.getY()));
			sprite.setY((short) (height + sprite.getHeight() / 2));
		}
		sprite.setGSpeed(gSpeed);

		if (jump && !sprite.getAir() && !jumpPressed) {
			jump = true;
			sprite.setAir(true);
			jumpPressed = true;
			xSpeed -= sprite.getJump() * Math.sin(Math.toRadians(angle));
			ySpeed += sprite.getJump() * Math.cos(Math.toRadians(angle));
		}

		if (!jump && jumpPressed) {
			if (ySpeed > 1024) {
				ySpeed = (short) 1024;
			}
			jumpPressed = false;
		}

		if (sprite.getAir()) {
			ySpeed -= sprite.getGravity();
		}

		sprite.setXSpeed(xSpeed);
		sprite.setYSpeed(ySpeed);

		if (height > 0) {
			sprite.move(xSpeed, (short) 0);
		} else {
			sprite.move(xSpeed, ySpeed);
		}
		// Temporary 'death' detection just resets X/Y of sprite.
		if (sprite.getY() <= 0) {
			sprite.setX((short) 50);
			sprite.setY((short) 50);
			sprite.setXSpeed((short) 0);
			sprite.setYSpeed((short) 0);
			sprite.setGSpeed((short) 0);
		}
//		System.out.println(sprite.getX() + "," + sprite.getY());
//		System.out.println(height);
//		System.out.println(ySpeed);
		sprite.getGroundSensors().updateSensors(sprite);
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
