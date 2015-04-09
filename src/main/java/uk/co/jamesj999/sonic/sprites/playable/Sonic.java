package uk.co.jamesj999.sonic.sprites.playable;

import javax.media.opengl.GL2;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.physics.SensorLine;

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
		terrainSensorLines.add(new SensorLine(this, 9, -36, 36, false));
		terrainSensorLines.add(new SensorLine(this, -9, -36, 36, false));

		// Wall Sensors
		wallSensorLine = new SensorLine(this, -10, -4, 20, true);
	}

	@Override
	protected void updateSensorLinesForRunningMode(SpriteRunningMode runningMode) {
		// Sad to hard-code this, but meh.
		if (terrainSensorLines.size() == 2) {
			SensorLine terrain1 = terrainSensorLines.get(0);
			SensorLine terrain2 = terrainSensorLines.get(1);
			if (SpriteRunningMode.LEFTWALL.equals(runningMode)) {
				terrain1.updateParameters(-36, 9, 36, true);
				terrain2.updateParameters(-36, -9, 36, true);
				wallSensorLine.updateParameters(-4, -10, 20, false);
			} else if (SpriteRunningMode.GROUND.equals(runningMode)) {
				terrain1.updateParameters(9, -36, 36, false);
				terrain2.updateParameters(-9, -36, 36, false);
				wallSensorLine.updateParameters(-10, -4, 20, true);
			} else if (SpriteRunningMode.RIGHTWALL.equals(runningMode)) {
				terrain1.updateParameters(0, 9, 36, true);
				terrain2.updateParameters(0, -9, 36, true);
				wallSensorLine.updateParameters(4, -10, 20, false);
            } else if(SpriteRunningMode.CEILING.equals(runningMode)) {
                terrain1.updateParameters(9, 0, 36, false);
                terrain2.updateParameters(-9, 0, 36, false);
				wallSensorLine.updateParameters(-10, 4, 20, true);
            }
		} else {
			// TODO: Change to proper logging
			System.out
					.println("ERROR - Couldn't find 2 wall sensors... Wut do? Sprite "
							+ getCode()
							+ "'s sensor lines will not update to match new running mode.");
		}
	}
}
