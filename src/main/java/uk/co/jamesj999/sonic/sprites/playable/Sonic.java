package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.GroundSensor;
import uk.co.jamesj999.sonic.physics.Sensor;
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
		// Ground Sensors
		groundSensors = new Sensor[2];
		groundSensors[0] = new GroundSensor(this, Direction.DOWN, (byte) -9, (byte) 20, true);
		groundSensors[1] = new GroundSensor(this, Direction.DOWN, (byte) 9, (byte) 20, true);

		// Ceiling Sensors
		ceilingSensors = new Sensor[2];
		ceilingSensors[0] = new GroundSensor(this, Direction.UP, (byte) -9, (byte) -20, false);
		ceilingSensors[1] = new GroundSensor(this, Direction.UP, (byte) 9, (byte) -20, false);

		// Push Sensors
		pushSensors = new Sensor[2];
		pushSensors[0] = new GroundSensor(this, Direction.LEFT, (byte) -10, (byte) 0, false);
		pushSensors[1] = new GroundSensor(this, Direction.RIGHT, (byte) 10, (byte) 0, false);
	}
}
