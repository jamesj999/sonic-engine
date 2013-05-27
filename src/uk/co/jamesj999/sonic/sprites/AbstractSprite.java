package uk.co.jamesj999.sonic.sprites;

import java.awt.image.BufferedImage;

import org.apache.commons.lang3.StringUtils;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;

public abstract class AbstractSprite implements Sprite {
	protected final SonicConfigurationService configService = SonicConfigurationService
			.getInstance();

	protected BufferedImage spriteImage = new BufferedImage(
			configService.getInt(SonicConfiguration.SCREEN_WIDTH),
			configService.getInt(SonicConfiguration.SCREEN_HEIGHT),
			BufferedImage.TYPE_INT_RGB);

	protected String code;

	protected short xPixel;
	protected short yPixel;

	protected byte xSubpixel;
	protected byte ySubpixel;

	protected int width;
	protected int height;

	protected float gravity = 0.02f;

	protected AbstractSprite(String code, short xPixel, short yPixel) {
		this.code = code;
		this.xPixel = xPixel;
		this.yPixel = yPixel;
	}

	public final String getCode() {
		return (code != null) ? code : StringUtils.EMPTY;
	}

	public final void setCode(String code) {
		this.code = code;
	}

	public final short getX() {
		return xPixel;
	}

	public final void setX(short x) {
		this.xPixel = x;
	}

	public final short getY() {
		return yPixel;
	}

	public final void setY(short y) {
		this.yPixel = y;
	}

	public final void move(short xSpeed, short ySpeed) {
		/*
		 * Speeds are provied in subpixels, need to convert current
		 * Pixel/Subpixel values to subpixels, add our speeds and convert back.
		 */
		long xTotal = (xPixel * 256) + (xSubpixel & 0xFF);
		long yTotal = (yPixel * 256) + (ySubpixel & 0xFF);

		xTotal += xSpeed;
		yTotal += ySpeed;

		short updatedXPixel = (short) (xTotal / 256);
		short updatedYPixel = (short) (yTotal / 256);

		byte updatedXSubpixel = (byte) (xTotal % 256);
		byte updatedYSubpixel = (byte) (yTotal % 256);

		xPixel = updatedXPixel;
		yPixel = updatedYPixel;

		xSubpixel = updatedXSubpixel;
		ySubpixel = updatedYSubpixel;
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
}
