package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class PlayableSpriteMovementManager extends
		AbstractSpriteMovementManager<AbstractPlayableSprite> {

	private final double max;
	private final double runAccel;
	private final double runDecel;
	private final double friction;

	public PlayableSpriteMovementManager(AbstractPlayableSprite sprite) {
		super(sprite);
		max = sprite.getMax();
		runAccel = sprite.getRunAccel();
		runDecel = sprite.getRunDecel();
		friction = sprite.getFriction();
	}

	@Override
	public void handleMovement(boolean left, boolean right) {
		double gSpeed = sprite.getGSpeed();
		double angle = sprite.getAngle();
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
					gSpeed = -max;
				}
			}
		} else if (right) {
			if (gSpeed < 0) {
				gSpeed += runDecel;
			} else {
				if (gSpeed < max) {
					gSpeed = gSpeed + runAccel;
				} else {
					gSpeed = max;
				}
			}
		} else {
			gSpeed -= Math.min(Math.abs(gSpeed), friction)
					* Math.signum(gSpeed);
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

		int x = sprite.getX();
		int y = sprite.getY();

		sprite.setGSpeed(gSpeed);
		x += gSpeed * Math.cos(angle);
		y += gSpeed * (0 - Math.sin(angle));
		sprite.setX(x);
		sprite.setY(y);
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
			sprite.setAngle(sprite.getAngle() + 0.01d);
			System.out.println(sprite.getAngle());
		}
		if (up) {
			sprite.setAngle(sprite.getAngle() - 0.01d);
			System.out.println(sprite.getAngle());
		}

	}
}
