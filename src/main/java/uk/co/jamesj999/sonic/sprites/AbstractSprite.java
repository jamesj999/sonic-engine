package uk.co.jamesj999.sonic.sprites;

import org.apache.commons.lang3.StringUtils;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.Sensor;

import java.awt.image.BufferedImage;

public abstract class AbstractSprite implements Sprite {
	protected final SonicConfigurationService configService = SonicConfigurationService
			.getInstance();
	protected final GraphicsManager graphicsManager = GraphicsManager
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

	public final short getX() {
		// TODO Not sure if this is needed, round to nearest subpixel?
		if ((xPixel & 0xFF) > 128) {
			return (short) (xPixel + 1);
		}
		return xPixel;
	}

	public final void setX(short x) {
		if (x < 0) {
			this.xPixel = 0;
		}
		this.xPixel = x;
		this.xSubpixel = 0;
	}

	public final short getY() {
		// TODO Not sure if this is needed, round to nearest subpixel?
		if ((ySubpixel & 0xFF) > 128) {
			return (short) (yPixel + 1);
		}
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
		if (y < 0) {
			this.yPixel = 0;
		}
		this.yPixel = y;
		this.ySubpixel = 0;
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

		if (updatedXPixel < 0) {
			xPixel = 0;
			xSubpixel = 0;
			xSpeed = 0;
		} else {
			xPixel = updatedXPixel;
			xSubpixel = updatedXSubpixel;
		}

		if (updatedYPixel < 0) {
			yPixel = 0;
			ySubpixel = 0;
			ySpeed = 0;
		} else {
			yPixel = updatedYPixel;
			ySubpixel = updatedYSubpixel;
		}
		// System.out.println("x=" + xPixel + " y=" + yPixel);
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

	public byte getXSubpixel() {
		return xSubpixel;
	}

	public byte getYSubpixel() {
		return ySubpixel;
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
