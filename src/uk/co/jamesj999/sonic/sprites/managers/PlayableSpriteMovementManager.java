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

		// Calculate Angle here

		if (left) {
			if (gSpeed <= 0.00d) {
				if (gSpeed - runAccel < 0 - max) {
					gSpeed = 0 - max;
				} else {
					gSpeed -= runAccel;
				}
			} else {
				if (gSpeed - runDecel < 0) {
					gSpeed = 0;
				} else {
					gSpeed -= runDecel;
				}
			}
		}
		if (right) {
			if (gSpeed >= 0.00d) {
				if (gSpeed + runAccel > max) {
					gSpeed = max;
				} else {
					gSpeed += runAccel;
				}
			} else {
				if (gSpeed + runDecel > 0.00d) {
					gSpeed = 0.00d;
				} else {
					gSpeed += runDecel;
				}
			}
		}
		if (!left && !right) {
			if (gSpeed > 0.00d) {
				if (gSpeed - friction < 0.00d) {
					gSpeed = 0.00d;
				} else {
					gSpeed -= friction;
				}
			} else {
				if (gSpeed + friction > 0.00d) {
					gSpeed = 0.00d;
				} else {
					gSpeed += friction;
				}
			}
		}
		int x = sprite.getX();
		int y = sprite.getY();

		double angle = sprite.getAngle();

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
	public void handleCollisions() {
		// TODO Auto-generated method stub

	}

}
