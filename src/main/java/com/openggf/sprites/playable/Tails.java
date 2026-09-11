package com.openggf.sprites.playable;

import com.openggf.graphics.GLCommand;
import com.openggf.physics.Direction;
import com.openggf.physics.GroundSensor;
import com.openggf.physics.Sensor;

import static org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN;

public class Tails extends AbstractPlayableSprite {

	public Tails(String code, short x, short y) {
		super(code, x, y);
		setWidth(20);
		setHeight(runHeight);
		setRenderOffsets((short) 0, (short) 0);
	}

	public void draw() {
		if (isHidden()) {
			return;
		}
		// ROM: Obj05 (Tails' tails) renders independently of invulnerability blink
		if (getTailsTailsController() != null) {
			getTailsTailsController().draw();
		}
		// ROM: During hurt bounce (routine 4), DisplaySprite is called directly
		// (always visible). Flashing only occurs after landing (routine 2) via
		// Tails_Display: lsr.w #3,d0 / bcc = visible when (timer & 0x04) != 0.
		if (!shouldRefreshRenderFlagThisFrame()) {
			// Still draw spindash dust even when blinking
			if (getSpindashDustController() != null) {
				getSpindashDustController().draw();
			}
			return; // Invisible this frame
		}
		if (getSpriteRenderer() != null) {
			if (getSpindashDustController() != null) {
				getSpindashDustController().draw();
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
				GL_TRIANGLE_FAN, 1, 1, 1, xPixel, yPixel, xPixel + width, yPixel
						+ height));
		graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.VERTEX2I,
				-1, 1, 0, 0, getCentreX(), getCentreY(), 0, 0));
	}

	@Override
	public SecondaryAbility getSecondaryAbility() {
		return SecondaryAbility.FLY;
	}

	/** ROM: Obj02's roll handler is Tails_RollSpeed (s2.asm:40031, sonic3k.asm:28169). */
	@Override
	public boolean usesTailsRollSpeedRoutine() {
		return true;
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
		slopeRollingDown = 80;
		slopeRollingUp = 20;
		rollDecel = 32;
		minStartRollSpeed = 264;
		minRollSpeed = 128;
		maxRoll = 4096;
		rollHeight = 28;
		runHeight = 30; // Tails is shorter: 2 * 15 = 30
		standXRadius = 9;
		standYRadius = 15; // Tails: 0x0F (shorter than Sonic's 0x13)
		rollXRadius = 7;
		rollYRadius = 14;
	}

	@Override
	protected void createSensorLines() {
		// Ground Sensors - Y offset matches standYRadius (0x0F = 15)
		groundSensors = new Sensor[2];
		groundSensors[0] = new GroundSensor(this, Direction.DOWN, (byte) -9, (byte) 15, true);
		groundSensors[1] = new GroundSensor(this, Direction.DOWN, (byte) 9, (byte) 15, true);

		// Ceiling Sensors - Y offset matches -standYRadius (-0x0F = -15)
		ceilingSensors = new Sensor[2];
		ceilingSensors[0] = new GroundSensor(this, Direction.UP, (byte) -9, (byte) -15, false);
		ceilingSensors[1] = new GroundSensor(this, Direction.UP, (byte) 9, (byte) -15, false);

		pushSensors = new Sensor[2];
		pushSensors[0] = new GroundSensor(this, Direction.LEFT, (byte) -10, (byte) 0, false);
		pushSensors[1] = new GroundSensor(this, Direction.RIGHT, (byte) 10, (byte) 0, false);
	}
}
