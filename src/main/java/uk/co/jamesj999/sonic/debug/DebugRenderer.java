package uk.co.jamesj999.sonic.debug;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.awt.*;

public class DebugRenderer {
	private static DebugRenderer debugRenderer;
	// private final GraphicsManager graphicsManager = GraphicsManager
	// .getInstance();
	private final SpriteManager spriteManager = SpriteManager.getInstance();
	private final SonicConfigurationService configService = SonicConfigurationService
			.getInstance();
	//private TextRenderer renderer;

	private int width = configService
			.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
	private int height = configService
			.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

	private String sonicCode = configService
			.getString(SonicConfiguration.MAIN_CHARACTER_CODE);

	public void renderDebugInfo() {


		/*if (renderer == null) {
			renderer = new TextRenderer(new Font(
					"SansSerif", Font.PLAIN, 6));
		}

		renderer.beginRendering(width, height);

		Sprite sprite = spriteManager.getSprite(sonicCode);
		if (sprite != null) {

			TextRenderer sensorRenderer = new TextRenderer(new Font(
					"SansSerif", Font.PLAIN, 4));
			sensorRenderer.setColor(Color.RED);
			sensorRenderer.beginRendering(width, height);

			AbstractPlayableSprite abstractPlayableSprite = (AbstractPlayableSprite) sprite;

			for(Sensor sensor : abstractPlayableSprite.getAllSensors()) {
				SensorResult result = sensor.getCurrentResult();
				if (sensor.isActive() && result != null) {
					Camera camera = Camera.getInstance();

					short xAdjusted = (short) (sprite.getCentreX() - camera.getX());
					short yAdjusted = (short) (sprite.getCentreY() - camera.getY());
					xAdjusted += sensor.getX();
					yAdjusted += sensor.getY();
					sensorRenderer.draw(String.valueOf(result.distance()), xAdjusted, height - yAdjusted);
				}
			}
			sensorRenderer.endRendering();
		}*/
	}

	public static synchronized DebugRenderer getInstance() {
		if (debugRenderer == null) {
			debugRenderer = new DebugRenderer();
		}
		return debugRenderer;
	}
}
