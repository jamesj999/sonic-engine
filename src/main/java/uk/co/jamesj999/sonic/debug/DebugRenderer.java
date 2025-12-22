package uk.co.jamesj999.sonic.debug;

import com.jogamp.opengl.util.awt.TextRenderer;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
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
	private final LevelManager levelManager = LevelManager.getInstance();
	private final SonicConfigurationService configService = SonicConfigurationService
			.getInstance();
	private TextRenderer renderer;
	private TextRenderer objectRenderer;

	private int width = configService
			.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
	private int height = configService
			.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

	private String sonicCode = configService
			.getString(SonicConfiguration.MAIN_CHARACTER_CODE);

	public void renderDebugInfo() {
		if (renderer == null) {
			renderer = new TextRenderer(new Font(
					"SansSerif", Font.PLAIN, 6));
		}
		if (objectRenderer == null) {
			objectRenderer = new TextRenderer(new Font(
					"SansSerif", Font.PLAIN, 6));
			objectRenderer.setColor(Color.MAGENTA);
		}

		renderer.beginRendering(width, height);

		Sprite sprite = spriteManager.getSprite(sonicCode);
		if (sprite != null) {
			int ringCount = 0;
			if (sprite instanceof AbstractPlayableSprite) {
				ringCount = ((AbstractPlayableSprite) sprite).getRingCount();
				renderer.draw("SpdshConst: " + ((AbstractPlayableSprite) sprite).getSpindashConstant(), 2, height-10);
				renderer.draw("Dir: " + ((AbstractPlayableSprite) sprite).getDirection(), 2, height-20);
				renderer.draw(
						"Mode: "
								+ ((AbstractPlayableSprite) sprite)
								.getGroundMode(), 2, 103);
				if (((AbstractPlayableSprite) sprite).getAir()) {
					renderer.draw("Air", 24, height-40);
				}
				if (((AbstractPlayableSprite) sprite).getRolling()) {
					renderer.draw("Roll", 24, height-40);
				}
				if (((AbstractPlayableSprite) sprite).getSpindash()) {
					renderer.draw("Spdash", 24, height-40);
				}
				renderer.draw(
						"gS:" + ((AbstractPlayableSprite) sprite).getGSpeed(),
						2, height-50);
				renderer.draw(
						"xS:" + ((AbstractPlayableSprite) sprite).getXSpeed(),
						2, height-60);
				renderer.draw(
						"yS:" + ((AbstractPlayableSprite) sprite).getYSpeed(),
						2, height-70);
				// DECIMAL VERSION:
				renderer.draw("Deg:" + (((AbstractPlayableSprite) sprite).getAngle()), 2, height-80);
			}
            // HEX VERSION:
//			renderer.draw(
//					"Angle:" + Integer.toHexString(((AbstractPlayableSprite) sprite).getAngle()), 2,
//					38);

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
			renderer.draw("pX: " + xString, 2, height-95);
			renderer.draw("pY: " + yString, 2, height-105);
			renderer.draw("Layer: " + sprite.getLayer(), 2, height-115);

			renderer.draw(xString, 2, 25);
			renderer.draw(yString, 2, 12);

			String ringText = String.format("RINGS: %03d", ringCount);
			int ringTextWidth = (int) Math.ceil(renderer.getBounds(ringText).getWidth());
			int ringX = Math.max(2, width - ringTextWidth - 4);
			renderer.draw(ringText, ringX, 6);

			renderer.endRendering();

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
		}

		renderObjectLabels();
	}

	private void renderObjectLabels() {
		if (objectRenderer == null) {
			return;
		}
		java.util.Collection<ObjectSpawn> spawns = levelManager.getActiveObjectSpawns();
		if (spawns.isEmpty()) {
			return;
		}
		Camera camera = Camera.getInstance();

		objectRenderer.beginRendering(width, height);
		for (ObjectSpawn spawn : spawns) {
			int screenX = spawn.x() - camera.getX();
			int screenY = spawn.y() - camera.getY();

			if (screenX < -8 || screenX > width + 8) {
				continue;
			}
			if (screenY < -8 || screenY > height + 8) {
				continue;
			}

			StringBuilder label = new StringBuilder(String.format("%02X:%02X",
					spawn.objectId(), spawn.subtype()));
			if (spawn.renderFlags() != 0) {
				label.append(" F").append(Integer.toHexString(spawn.renderFlags()).toUpperCase());
			}
			if (spawn.respawnTracked()) {
				label.append(" R");
			}

			objectRenderer.draw(label.toString(), screenX + 2, height - screenY + 2);
		}
		objectRenderer.endRendering();
	}

	public static synchronized DebugRenderer getInstance() {
		if (debugRenderer == null) {
			debugRenderer = new DebugRenderer();
		}
		return debugRenderer;
	}
}
