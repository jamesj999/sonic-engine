package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class PlayableSpriteMovementManager extends
		AbstractSpriteMovementManager<AbstractPlayableSprite> {
	private final float runAccel;
	private final float runDecel;
	private final float friction;
	private final float max;

	public PlayableSpriteMovementManager(AbstractPlayableSprite sprite) {
		super(sprite);
		this.runAccel = sprite.getRunAccel();
		this.runDecel = sprite.getRunDecel();
		this.friction = sprite.getFriction();
		this.max = sprite.getMax();
	}

	@Override
	public void handleMovement(boolean left, boolean right) {
		float xSpeed = sprite.getXSpeed();
		// float ySpeed = sprite.getYSpeed();

		if (left) {
			if (xSpeed <= 0.00f) {
				if (xSpeed < 0 - max) {
					xSpeed = 0 - max;
				} else {
					xSpeed -= runAccel;
				}
			} else {
				if (xSpeed - runDecel < 0) {
					xSpeed = 0;
				} else {
					xSpeed -= runDecel;
				}
			}
		}
		if (right) {
			if (xSpeed >= 0.00f) {
				if (xSpeed > max) {
					xSpeed = max;
				} else {
					xSpeed += runAccel;
				}
			} else {
				if (xSpeed + runDecel > 0.00f) {
					xSpeed = 0.00f;
				} else {
					xSpeed += runDecel;
				}
			}
		}
		if (!left && !right) {
			if (xSpeed > 0.00f) {
				if (xSpeed - friction < 0.00f) {
					xSpeed = 0.00f;
				} else {
					xSpeed -= friction;
				}
			} else {
				if (xSpeed + friction > 0.00f) {
					xSpeed = 0.00f;
				} else {
					xSpeed += friction;
				}
			}
		}
		int x = sprite.getX();
		x += xSpeed;

		sprite.setXSpeed(xSpeed);
		sprite.setX(x);
	}

	@Override
	public void handleGravity(boolean down) {
		if (!down) {
			sprite.setYSpeed(0.00f);
		} else {
			float ySpeed = sprite.getYSpeed();
			if (ySpeed < max) {
				ySpeed += sprite.getGravity();
			} else {
				if (ySpeed + sprite.getGravity() > max) {
					ySpeed = max;
				}
			}
			int y = sprite.getY();
			y += ySpeed;

			sprite.setYSpeed(ySpeed);
			sprite.setY(y);
		}
	}

	@Override
	public void handleCollisions() {
		// TODO Auto-generated method stub

	}

}
