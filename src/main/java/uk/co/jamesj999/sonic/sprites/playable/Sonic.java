package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.SensorLine;

import com.jogamp.opengl.GL2;

public class Sonic extends AbstractPlayableSprite {

	public Sonic(String code, short x, short y) {
		super(code, x, y);
		// width in pixels
		setWidth(20);
		setHeight(40);
	}

	public void draw() {
		graphicsManager.registerCommand(new GLCommand(GLCommand.Type.RECTI,
				GL2.GL_2D, 1, 1, 1, xPixel, yPixel, xPixel + width, yPixel
						+ height));
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
		angle = 0;
		slopeRunning = 32;
		slopeRollingDown = 20;
		slopeRollingUp = 80;
		rollDecel = 32;
		minStartRollSpeed = 264;
		minRollSpeed = 128;
		maxRoll = 4096;
		rollHeight = 30;
		runHeight = 40;
	}

	@Override
	protected void createSensorLines() {
		// Ceiling Sensors
		registerSensorLine(new SensorLine(this, true, (byte) 9, (byte) 0, (byte) 9, (byte) -20, Direction.UP));
		registerSensorLine(new SensorLine(this, true, (byte) -9, (byte) 0, (byte) -9, (byte) -20, Direction.UP));

        // Terrain Sensors
		registerSensorLine(new SensorLine(this, true, (byte) 9, (byte) 0, (byte) 9, (byte) 20, Direction.DOWN));
		registerSensorLine(new SensorLine(this, true, (byte) -9, (byte) 0, (byte) -9, (byte) 20, Direction.DOWN));

		// Wall Sensors
		registerSensorLine(new SensorLine(this, true, (byte) -10, (byte) 0, (byte) 0, (byte) 0, Direction.LEFT));
		registerSensorLine(new SensorLine(this, true, (byte) 0, (byte) 0, (byte) 10, (byte) 0, Direction.RIGHT));
	}

	@Override
	protected void updateSensorLines() {
		// Ground sensor lines need to be at y-8 if sonic is standing on the ground and angle == 0

		// If sonic is rolling, sensor lines need to update to:
		//

		// If sonic is not on flat ground or is in the air but not rolling:
		// If angle is in 270-0 or 0-90 range:
		// Ground sensors are at
	}
}
