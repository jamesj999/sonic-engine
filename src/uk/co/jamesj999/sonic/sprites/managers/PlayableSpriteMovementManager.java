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
		byte angle = sprite.getAngle();
		// Calculate Angle here
		double slopeRunning = sprite.getSlopeRunning();
		gSpeed += (slopeRunning * Math.sin(angle));

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
			if ((gSpeed < friction && gSpeed > 0) || (gSpeed > -friction)
					&& gSpeed < 0) {
				gSpeed = 0;
			} else {
				gSpeed -= Math.min(Math.abs(gSpeed), friction)
						* Math.signum(gSpeed);
			}
			// if (gSpeed > 0.00d) {
			// if (gSpeed - friction < 0.00d) {
			// gSpeed = 0.00d;
			// } else {
			// gSpeed -= friction;
			// }
			// } else {
			// if (gSpeed + friction > 0.00d) {
			// gSpeed = 0.00d;
			// } else {
			// gSpeed += friction;
			// }
			// }
		}

		sprite.setGSpeed(gSpeed);
		// System.out.println(height);

		short xSpeed;
		short ySpeed;
		if (!sprite.getAir()) {
			xSpeed = (short) Math.round(gSpeed * Math.cos(angle));
			ySpeed = (short) Math.round(gSpeed * -Math.sin(angle));
		} else {
			xSpeed = sprite.getXSpeed();
			ySpeed = sprite.getYSpeed();
		}

		if (jump && !sprite.getAir()) {
			sprite.setAir(true);
			ySpeed += sprite.getJump();
		}
		short height = terrainCollisionManager.calculateTerrainHeight(sprite);

		if (sprite.getAir()) {
			ySpeed -= sprite.getGravity();
		}
		if (height > 0) {
			if (ySpeed < 0) {
				ySpeed = 0;
			}
			ySpeed += 16 * ((short) (height + sprite.getHeight() / 2) - sprite
					.getY());
			sprite.setY((short) (height + sprite.getHeight() / 2));
		}
		sprite.setXSpeed(xSpeed);
		sprite.setYSpeed(ySpeed);
		sprite.move(xSpeed, ySpeed);
		// -1 indicates no heightmap was found meaning we're not on a solid tile
		sprite.getGroundSensors().updateSensors(sprite);
	}

	// @Override
	// public void handleGravity(boolean down) {
	// if (!down) {
	// sprite.setYSpeed(0.00d);
	// } else {
	// float ySpeed = sprite.getYSpeed();
	// if (ySpeed < max) {
	// ySpeed += sprite.getGravity();
	// } else {
	// if (ySpeed + sprite.getGravity() > max) {
	// ySpeed = max;
	// }
	// }
	// int y = sprite.getY();
	// y += ySpeed;
	//
	// sprite.setYSpeed(ySpeed);
	// sprite.setY(y);
	// }
	// }

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
