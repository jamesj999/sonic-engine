package uk.co.jamesj999.sonic.sprites.playable;

import javax.media.opengl.GL2;

public class Sonic extends AbstractPlayableSprite {

	public Sonic(String code, short x, short y) {
		super(code, x, y);
		// width in pixels for now
		setWidth(12);
		setHeight(16);
	}

	@Override
	public void draw(GL2 gl) {
		gl.glBegin(GL2.GL_2D);
		gl.glRectd(xPixel, yPixel, xPixel + width, yPixel - height);
		gl.glEnd();
	}

	@Override
	public void defineSpeeds() {
		runAccel = 12;
		runDecel = 128;
		friction = 12;
		max = 1536;
		/**
		 * Change 'angle' to make sonic walk at an angle!
		 */
		angle = 0;
		// slopeRunning = 1;
		// slopeRolling = 0.078125d;
	}
}
