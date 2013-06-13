package uk.co.jamesj999.sonic.sprites.playable;

import javax.media.opengl.GL2;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.physics.SensorLine;

public class Sonic extends AbstractPlayableSprite {

	public Sonic(String code, short x, short y) {
		super(code, x, y);
		// width in pixels
		setWidth(28);
		setHeight(40);
	}

	@Override
	public void draw() {
		graphicsManager.registerCommand(new GLCommand(GLCommand.Type.RECTI,
				GL2.GL_2D, 1, 1, 1, xPixel, yPixel, xPixel + width, yPixel
						- height));
		graphicsManager.registerCommand(new GLCommand(GLCommand.Type.VERTEX2I,
				-1, 1, 0, 0, getCentreX(), getCentreY(), 0, 0));
	}

	@Override
	public void defineSpeeds() {
		runAccel = 12;
		runDecel = 128;
		friction = 12;
		max = 1536;
		jump = 1664;
		/**
		 * Change 'angle' to make sonic walk at an angle!
		 */
		angle = 0;
		slopeRunning = 32;
		rollDecel = 32;
		minStartRollSpeed = 264;
		minRollSpeed = 128;
		maxRoll = 4096;
		rollHeight = 30;
		runHeight = 40;
		// slopeRunning = 1;
		// slopeRolling = 0.078125d;
	}

	@Override
	protected void createSensorLines() {
		/**
		 * fix this - A/B sensor lines run at: x = -9, x= 9, vertical, y = 0, y
		 * = 0, length = -20
		 * 
		 * But the ground sensors need to be separate.
		 */
		sensorLines.add(new SensorLine(this, 9, -36, 36, false));
		sensorLines.add(new SensorLine(this, -9, -36, 36, false));
	}
}
