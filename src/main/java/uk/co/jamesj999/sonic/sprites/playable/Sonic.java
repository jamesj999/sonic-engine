package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.GroundSensor;
import uk.co.jamesj999.sonic.physics.Sensor;

import com.jogamp.opengl.GL2;

public class Sonic extends AbstractPlayableSprite {

	public Sonic(String code, short x, short y, boolean debug) {
		super(code, x, y, debug);
		// width in pixels
		setWidth(20);
		setHeight(runHeight);
		setRenderOffsets((short) 0, (short) 0);
	}

	public void draw() {
		// Skip rendering on certain frames during invulnerability (blink effect)
		// Pattern: (frames & 0x04) creates ~8-frame on/off cycle
		if (getInvulnerableFrames() > 0 && (getInvulnerableFrames() & 0x04) != 0) {
			// Still draw spindash dust even when blinking
			if (getSpindashDustManager() != null) {
				getSpindashDustManager().draw();
			}
			return; // Invisible this frame
		}
		if (getSpriteRenderer() != null) {
			if (getSpindashDustManager() != null) {
				getSpindashDustManager().draw();
			}
			getSpriteRenderer().drawFrame(
					getMappingFrame(),
					getRenderCentreX(),
					getRenderCentreY(),
					getRenderHFlip(),
					getRenderVFlip());
			return;
		}
		graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.RECTI,
				GL2.GL_2D, 1, 1, 1, xPixel, yPixel, xPixel + width, yPixel
						+ height));
		graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.VERTEX2I,
				-1, 1, 0, 0, getCentreX(), getCentreY(), 0, 0));
	}

	@Override
	public void defineSpeeds() {
		// Base values - speed shoes boost is applied dynamically in getters
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
		minStartRollSpeed = 128; // SPG: 0.5 pixels (128 subpixels) in S1/S2
		minRollSpeed = 128;
		maxRoll = 4096;
		rollHeight = 28;
		runHeight = 38;
		standXRadius = 9;
		standYRadius = 19;
		rollXRadius = 7;
		rollYRadius = 14;
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
