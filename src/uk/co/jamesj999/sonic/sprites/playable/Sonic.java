package uk.co.jamesj999.sonic.sprites.playable;

import javax.media.opengl.GL2;

import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.physics.TerrainSensor;
import uk.co.jamesj999.sonic.physics.TerrainSensorPair;

public class Sonic extends AbstractPlayableSprite {

	public Sonic(String code, short x, short y) {
		super(code, x, y);
		// width in pixels for now
		setWidth(20);
		setHeight(24);

		// Add sensors
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

	@Override
	public void createGroundSensors() {
		Sensor left = new TerrainSensor(-20, 0);
		Sensor right = new TerrainSensor(20, 0);
		groundSensors = new TerrainSensorPair(left, right);
	}
}
