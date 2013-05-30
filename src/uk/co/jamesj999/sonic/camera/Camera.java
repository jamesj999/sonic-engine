package uk.co.jamesj999.sonic.camera;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.sprites.Sprite;

public class Camera {
	private static Camera camera;
	private short x = 0;
	private short y = 0;

	private short width;
	private short height;

	private Camera() {
		SonicConfigurationService configService = SonicConfigurationService
				.getInstance();
		width = configService.getShort(SonicConfiguration.SCREEN_WIDTH);
		height = configService.getShort(SonicConfiguration.SCREEN_HEIGHT);
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
