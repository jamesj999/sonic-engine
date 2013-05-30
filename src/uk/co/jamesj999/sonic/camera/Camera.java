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
		width = configService.getShort(SonicConfiguration.SCREEN_WIDTH);
		height = configService.getShort(SonicConfiguration.SCREEN_HEIGHT);
	}

	public void updatePosition() {
		short focusedSpriteRealX = (short) (focusedSprite.getX() - x);
		short focusedSpriteRealY = (short) (focusedSprite.getY() - y);
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
		if (x < 0) {
			x = 0;
		}
		
//		if(focusedSprite.getAir()) {
//			 if(focusedSpriteRealY < 96) {
//				 short difference = (short) (focusedSpriteRealX - 96);
//				 if(difference)
//			 } else if(focusedSpriteRealY > 160) {
//				 
//			 }
//		}
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
