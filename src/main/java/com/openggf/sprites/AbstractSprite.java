package com.openggf.sprites;

import org.apache.commons.lang3.StringUtils;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.EngineServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.physics.Direction;
import com.openggf.physics.Sensor;

public abstract class AbstractSprite implements Sprite {
	protected final SonicConfigurationService configService = EngineServices.current().configuration();
	protected final GraphicsManager graphicsManager = EngineServices.current().graphics();

	protected String code;

	protected short xPixel;
	protected short yPixel;

	// ROM uses 16:16 fixed-point (16-bit subpixel). Storing full 16-bit fraction
	// to prevent cumulative carry errors vs the ROM's 32-bit position arithmetic.
	protected short xSubpixel;
	protected short ySubpixel;

	protected int width;
	protected int height;

	protected Sensor[] pushSensors;
	protected Sensor[] groundSensors;
	protected Sensor[] ceilingSensors;

	protected byte gravity = 56;

	protected Direction direction;

	protected byte layer = 0;

	protected AbstractSprite(String code, short xPixel, short yPixel) {
		this.code = code;
		this.xPixel = xPixel;
		this.yPixel = yPixel;
		direction = Direction.RIGHT;
		createSensorLines();
	}

	protected AbstractSprite(String code, short xPixel, short yPixel,
			Direction direction) {
		this(code, xPixel, yPixel);
		this.direction = direction;
	}

	public final String getCode() {
		return (code != null) ? code : StringUtils.EMPTY;
	}

	public final void setCode(String code) {
		this.code = code;
	}

	public final short getCentreX() {
		return (short) (xPixel + (width / 2));
	}

	public final short getCentreY() {
		return (short) (yPixel + (height / 2));
	}

	public void setCentreX(short x) {
		this.xPixel = (short) (x - (width / 2));
		this.xSubpixel = (short) 0;
	}

	public void setCentreY(short y) {
		this.yPixel = (short) (y - (height / 2));
		this.ySubpixel = (short) 0;
	}

	/**
	 * Sets the ROM centre X while preserving the current subpixel fraction.
	 * Mirrors a 68000 {@code move.w} to {@code x_pos}, which does not touch
	 * the low 16-bit subpixel field.
	 */
	public void setCentreXPreserveSubpixel(short x) {
		this.xPixel = (short) (x - (width / 2));
	}

	/**
	 * Sets the ROM centre Y while preserving the current subpixel fraction.
	 * Mirrors a 68000 {@code move.w} to {@code y_pos}, which does not touch
	 * the low 16-bit subpixel field.
	 */
	public void setCentreYPreserveSubpixel(short y) {
		this.yPixel = (short) (y - (height / 2));
	}

	public final short getX() {
		// SPG: Collision calculations use whole pixel position, ignoring subpixels.
		// No rounding should be done - the pixel position is authoritative.
		return xPixel;
	}

	public final void setX(short x) {
		if (x < 0) {
			this.xPixel = 0;
			this.xSubpixel = 0; // Boundary clamp: full reset
		} else {
			this.xPixel = x;
			// ROM-accurate: move.w to x_pos does not touch x_sub.
			// Subpixel fraction is preserved across position updates.
		}
	}

	public final short getY() {
		// SPG: Collision calculations use whole pixel position, ignoring subpixels.
		// No rounding should be done - the pixel position is authoritative.
		return yPixel;
	}

	public short getBottomY() {
		return (short) (getCentreY() - (getHeight() / 2));
	}

	public short getTopY() {
		return (short) (getCentreY() + (getHeight() / 2));
	}

	public short getLeftX() {
		return (short) (getCentreX() - (getWidth() / 2));
	}

	public short getRightX() {
		return (short) (getCentreX() + (getWidth() / 2));
	}

	public Direction getDirection() {
		return direction;
	}

	public void setDirection(Direction direction) {
		this.direction = direction;
	}

	public final void setY(short y) {
		this.yPixel = y;
		// ROM-accurate: move.w to y_pos does not touch y_sub.
		// Subpixel fraction is preserved across position updates.
	}

	/**
	 * Shifts the pixel X position by a delta without zeroing subpixels.
	 * ROM-accurate: matches 68000 {@code sub.w d2, obX(a1)} which modifies
	 * only the pixel word, preserving any accumulated subpixel fraction.
	 * Used by platform riding code (MvSonicOnPtfm / MvSonicOnPtfm2).
	 */
	public final void shiftX(int delta) {
		this.xPixel += delta;
	}

	/**
	 * Shifts the pixel Y position by a delta without zeroing subpixels.
	 * ROM-accurate: matches 68000 position word modification that preserves
	 * subpixel fraction. Used by platform riding code.
	 */
	public final void shiftY(int delta) {
		this.yPixel += delta;
	}

	public final void move(short xSpeed, short ySpeed) {
		// ROM-accurate 16:16 fixed-point position update (SpeedToPos).
		// ROM: move.l obX(a0),d2 — loads 32-bit value (pixel:16 | subpixel:16)
		//      ext.l d0 / asl.l #8,d0 — velocity * 256, sign-extended to 32-bit
		//      add.l d0,d2 — 32-bit add preserving all 16 subpixel bits
		//      move.l d2,obX(a0) — stores back full 32-bit value
		int xPos = (xPixel << 16) | (xSubpixel & 0xFFFF);
		int yPos = (yPixel << 16) | (ySubpixel & 0xFFFF);

		xPos += (int) xSpeed << 8;
		yPos += (int) ySpeed << 8;

		xPixel = (short) (xPos >> 16);
		xSubpixel = (short) (xPos & 0xFFFF);

		yPixel = (short) (yPos >> 16);
		ySubpixel = (short) (yPos & 0xFFFF);
	}

	public int getWidth() {
		return width;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	public int getHeight() {
		return height;
	}

	public void setHeight(int height) {
		this.height = height;
	}

	public float getGravity() {
		return gravity;
	}

	/**
	 * Returns the high 8 bits of the 16-bit subpixel fraction.
	 * This is the portion that corresponds to 1/256 pixel units
	 * (the same units as velocity). Callers using {@code & 0xFF}
	 * get the same value as before the 16:16 upgrade.
	 */
	public byte getXSubpixel() {
		return (byte) (xSubpixel >> 8);
	}

	/**
	 * Returns the high 8 bits of the 16-bit subpixel fraction.
	 * @see #getXSubpixel()
	 */
	public byte getYSubpixel() {
		return (byte) (ySubpixel >> 8);
	}

	/** Returns the full 16-bit subpixel value (for diagnostic comparison). */
	public int getXSubpixelRaw() {
		return xSubpixel & 0xFFFF;
	}

	/** Returns the full 16-bit subpixel value (for diagnostic comparison). */
	public int getYSubpixelRaw() {
		return ySubpixel & 0xFFFF;
	}

	/**
	 * Restores the full 16-bit subpixel fraction directly.
	 * Used by trace/bootstrap code that needs ROM-accurate sprite state hydration.
	 */
	public void setSubpixelRaw(int xSubpixel, int ySubpixel) {
		this.xSubpixel = (short) xSubpixel;
		this.ySubpixel = (short) ySubpixel;
	}

	public Sensor[] getPushSensors() {
		return pushSensors;
	}

	public void setPushSensors(Sensor[] pushSensors) {
		this.pushSensors = pushSensors;
	}

	public Sensor[] getGroundSensors() {
		return groundSensors;
	}

	public void setGroundSensors(Sensor[] groundSensors) {
		this.groundSensors = groundSensors;
	}

	public Sensor[] getCeilingSensors() {
		return ceilingSensors;
	}

	public void setCeilingSensors(Sensor[] ceilingSensors) {
		this.ceilingSensors = ceilingSensors;
	}

	protected abstract void createSensorLines();

	public void setLayer(byte layer) {
		this.layer = layer;
	}

	public byte getLayer() {
		return layer;
	}
}
