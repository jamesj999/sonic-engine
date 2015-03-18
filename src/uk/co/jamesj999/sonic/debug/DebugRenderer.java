package uk.co.jamesj999.sonic.debug;

import java.awt.Font;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.graphics.SpriteManager;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import com.jogamp.opengl.util.awt.TextRenderer;

public class DebugRenderer {
	private static DebugRenderer debugRenderer;
	// private final GraphicsManager graphicsManager = GraphicsManager
	// .getInstance();
	private final SpriteManager spriteManager = SpriteManager.getInstance();
	private final SonicConfigurationService configService = SonicConfigurationService
			.getInstance();
	private final TextRenderer renderer = new TextRenderer(new Font(
			"SansSerif", Font.BOLD, 12));

	private int width = configService
			.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
	private int height = configService
			.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

	private String sonicCode = configService
			.getString(SonicConfiguration.MAIN_CHARACTER_CODE);

	public void renderDebugInfo() {
		renderer.beginRendering(width, height);
		Sprite sprite = spriteManager.getSprite(sonicCode);
		if (sprite != null) {
			if (sprite instanceof AbstractPlayableSprite) {
                renderer.draw("Direction: "+((AbstractPlayableSprite) sprite).getDirection(), 2, 120);
				renderer.draw(
						"Mode: "
								+ ((AbstractPlayableSprite) sprite)
										.getRunningMode(), 2, 103);
				if (((AbstractPlayableSprite) sprite).getAir()) {
					renderer.draw("Air", 2, 90);
				}
				if (((AbstractPlayableSprite) sprite).getRolling()) {
					renderer.draw("Rolling", 24, 90);
				}
			}
			renderer.draw(
					"gSpeed:" + ((AbstractPlayableSprite) sprite).getGSpeed(),
					2, 77);
			renderer.draw(
					"xSpeed:" + ((AbstractPlayableSprite) sprite).getXSpeed(),
					2, 64);
			renderer.draw(
					"ySpeed:" + ((AbstractPlayableSprite) sprite).getYSpeed(),
					2, 51);
			renderer.draw(
					"Angle:" + ((AbstractPlayableSprite) sprite).getAngle(), 2,
					38);
			StringBuilder xString = new StringBuilder(Integer.toHexString(
					sprite.getX()).toUpperCase());
			StringBuilder yString = new StringBuilder(Integer.toHexString(
					sprite.getY()).toUpperCase());
			while (xString.length() < 6) {
				xString.insert(0, "0");
			}
			while (yString.length() < 6) {
				yString.insert(0, "0");
			}
			renderer.draw(xString, 2, 25);
			renderer.draw(yString, 2, 12);
		}
		renderer.endRendering();
	}

	public static synchronized DebugRenderer getInstance() {
		if (debugRenderer == null) {
			debugRenderer = new DebugRenderer();
		}
		return debugRenderer;
	}
}
