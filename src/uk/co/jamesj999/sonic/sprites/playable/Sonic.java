package uk.co.jamesj999.sonic.sprites.playable;

import javax.media.opengl.GL2;

public class Sonic extends AbstractPlayableSprite {

	public Sonic(String code, int x, int y) {
		super(code, x, y);
		setWidth(16);
		setHeight(16);
	}

	@Override
	public void draw(GL2 gl) {
		gl.glBegin(GL2.GL_2D);
		gl.glRectd(x, y, x+width, y-height);
		gl.glEnd();
	}

	@Override
	public void defineSpeeds() {
		runAccel = 0.046875d;
		// 0.50 seems WAY too high for runDecel... but it's what the physics
		// guide says!
		runDecel = 0.50d;
		friction = 0.046875d;
		max = 6.00d;
		/**
		 * Change 'angle' to make sonic walk at an angle!
		 */
		angle = 0;
		slopeRunning = 0.125d;
		slopeRolling = 0.078125d;
	}
}
