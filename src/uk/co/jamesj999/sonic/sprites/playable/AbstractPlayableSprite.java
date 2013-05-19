package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.sprites.AbstractSprite;

public abstract class AbstractPlayableSprite extends AbstractSprite {
	protected float xSpeed = 0f;
	protected float ySpeed = 0f;
	protected float runAccel;
	protected float runDecel;
	protected float friction;
	protected float max;

	protected AbstractPlayableSprite(String code, int x, int y) {
		super(code, x, y);
		defineSpeeds();
	}

	public float getXSpeed() {
		return xSpeed;
	}

	public float getYSpeed() {
		return ySpeed;
	}

	public float getRunAccel() {
		return runAccel;
	}

	public float getRunDecel() {
		return runDecel;
	}

	public void handle(boolean left, boolean right) {
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

		x += xSpeed;
	}

	protected abstract void defineSpeeds();
}
