package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.physics.SensorDirection;
import uk.co.jamesj999.sonic.physics.SensorLine;

import com.jogamp.opengl.GL2;

public class Sonic extends AbstractPlayableSprite {

	public Sonic(String code, short x, short y, boolean debug) {
		super(code, x, y, debug);
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
        // Terrain Sensors
		terrainSensorLines.add(new SensorLine(this, (byte) 9, (byte) 0, (byte) 9, (byte) 20, SensorDirection.DOWN));
		terrainSensorLines.add(new SensorLine(this, (byte) -9, (byte) 0, (byte) -9, (byte) 20, SensorDirection.DOWN));

		// Wall Sensors
		wallSensorLines.add(new SensorLine(this, (byte) -10, (byte) 0, (byte) 0, (byte) 0, SensorDirection.LEFT));
		wallSensorLines.add(new SensorLine(this, (byte) 0, (byte) 0, (byte) 10, (byte) 0, SensorDirection.RIGHT));
	}

	@Override
	protected void updateSensorLinesForRunningMode(SpriteRunningMode runningMode) {
		// Sad to hard-code this, but meh.
		if(terrainSensorLines.size() != 2 && wallSensorLines.size() != 2) {
			throw new IllegalStateException("Sonic must have 2 terrain sensor lines and 2 wall sensor lines.");
		}
		SensorLine terrain1 = terrainSensorLines.get(0);
		SensorLine terrain2 = terrainSensorLines.get(1);
		SensorLine wall1 = wallSensorLines.get(0);
		SensorLine wall2 = wallSensorLines.get(1);

		switch (getRunningMode()) {
			case GROUND:
				terrain1.updateParameters((byte) 9, (byte) 0, (byte) 9, (byte) 20, SensorDirection.DOWN);
				terrain2.updateParameters((byte) -9, (byte) 0, (byte) -9, (byte) 20, SensorDirection.DOWN);
				wall1.updateParameters((byte) -10, (byte) 0, (byte) 0, (byte) 0, SensorDirection.LEFT);
				wall2.updateParameters((byte) 0, (byte) 0, (byte) 10, (byte) 0, SensorDirection.RIGHT);
				break;
			case LEFTWALL:
				//TODO
				break;
			case RIGHTWALL:
				//TODO
				break;
			case CEILING:
				//TODO
				break;
		}
	}
}
