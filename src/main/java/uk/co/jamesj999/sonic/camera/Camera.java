package uk.co.jamesj999.sonic.camera;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class Camera {
	private static Camera camera;
	private short x = 0;
	private short y = 0;

	private short minX;
	private short minY;
	private short maxX;
	private short maxY;

	private int framesBehind = 0;

	private boolean frozen = false;

	private AbstractPlayableSprite focusedSprite;

	private short width;
	private short height;

	private Camera() {
		SonicConfigurationService configService = SonicConfigurationService
				.getInstance();
		width = configService.getShort(SonicConfiguration.SCREEN_WIDTH_PIXELS);
		height = configService.getShort(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
	}

	public void updatePosition() {
		updatePosition(false);
	}

	public void updatePosition(boolean force) {
		if (force) {
			x = focusedSprite.getCentreX();
			y = focusedSprite.getCentreY();
			return;
		}
		if (frozen) {
			framesBehind++;
			return;
		}
		short focusedSpriteRealX;
		short focusedSpriteRealY;
		if(framesBehind > 0) {
			focusedSpriteRealX = (short) (focusedSprite.getCentreX(framesBehind) - x);
			focusedSpriteRealY = (short) (focusedSprite.getCentreY(framesBehind) - y);
		} else {
			focusedSpriteRealX = (short) (focusedSprite.getCentreX() - x);
			focusedSpriteRealY = (short) (focusedSprite.getCentreY() - y);
		}

		if (focusedSpriteRealX < 144) {
			short difference = (short) (focusedSpriteRealX - 144);
			if (difference > 16) {
				x -= 16;
			} else {
				x += difference;
			}
		} else if (focusedSpriteRealX > 160) {
			short difference = (short) (focusedSpriteRealX - 160);
			if (difference > 16) {
				x += 16;
			} else {
				x += difference;
			}
		}

		if (focusedSprite.getAir()) {
			if (focusedSpriteRealY < 96) {
				short difference = (short) (focusedSpriteRealY - 96);
				if (difference < -16) {
					y -= 16;
				} else {
					y += difference;
				}
			} else if (focusedSpriteRealY > 160) {
				short difference = (short) (focusedSpriteRealY - 160);
				if (difference > 16) {
					y += 16;
				} else {
					y += difference;
				}
			}
		} else if (focusedSpriteRealY > 96) {
			short ySpeed = (short) (focusedSprite.getYSpeed() / 256);
			short difference = (short) (focusedSpriteRealY - 96);
			byte tolerance;

			if (ySpeed > 6) {
				tolerance = 16;
			} else {
				tolerance = 6;
			}

			if (difference > tolerance) {
				y += tolerance;
			} else {
				y += difference;
			}
		} else if (focusedSpriteRealY < 96) {
			short ySpeed = (short) (focusedSprite.getYSpeed() / 256);
			short difference = (short) (focusedSpriteRealY - 96);
			byte tolerance;

			if (ySpeed < -6) {
				tolerance = -16;
			} else {
				tolerance = -6;
			}

			if (difference < tolerance) {
				y += tolerance;
			} else {
				y += difference;
			}
		}
		if (x < 0) {
			x = 0;
		}
		if (y < 0) {
			y = 0;
		}
		if (x > maxX) {
			x = maxX;
		}
		if (y > maxY) {
			y = maxY;
		}
		if (framesBehind > 0) {
			framesBehind--;
		}
	}

	public void setFrozen(boolean frozen) {
		this.frozen = frozen;
	}

	public boolean getFrozen() {
		return frozen;
	}

	public boolean isOnScreen(Sprite sprite) {
		int xLower = x;
		int yLower = y;
		int xUpper = x + width;
		int yUpper = y + height;
		int spriteX = sprite.getX();
		int spriteY = sprite.getY();
		return spriteX >= xLower && spriteY >= yLower && spriteX <= xUpper
				&& spriteY <= yUpper;
	}

	public void setFocusedSprite(AbstractPlayableSprite sprite) {
		this.focusedSprite = sprite;
		x = sprite.getX();
		y = sprite.getY();
	}

	public AbstractPlayableSprite getFocusedSprite() {
		return focusedSprite;
	}

	public short getX() {
		return x;
	}

	public short getY() {
		return y;
	}
	
	public short getWidth() {
		return width;
	}
	
	public short getHeight() {
		return height;
	}

	public short getMinX() {
		return minX;
	}

	public void setMinX(short minX) {
		this.minX = minX;
	}

	public short getMinY() {
		return minY;
	}

	public void setMinY(short minY) {
		this.minY = minY;
	}

	public short getMaxX() {
		return maxX;
	}

	public void setMaxX(short maxX) {
		this.maxX = maxX;
	}

	public short getMaxY() {
		return maxY;
	}

	public void setMaxY(short maxY) {
		this.maxY = maxY;
	}

	public void incrementX(short amount) {
		x += amount;
	}

	public void incrementY(short amount) {
		y += amount;
	}

	public static synchronized Camera getInstance() {
		if (camera == null) {
			camera = new Camera();
		}
		return camera;
	}
}
