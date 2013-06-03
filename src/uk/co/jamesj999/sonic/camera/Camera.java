package uk.co.jamesj999.sonic.camera;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class Camera {
	private static Camera camera;
	private short x = 0;
	private short y = 0;

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
		short focusedSpriteRealX = (short) (focusedSprite.getCentreX() - x);
		short focusedSpriteRealY = (short) (focusedSprite.getCentreY() - y);
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
