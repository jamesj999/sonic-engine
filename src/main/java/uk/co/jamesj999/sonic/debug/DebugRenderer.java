package uk.co.jamesj999.sonic.debug;

import com.jogamp.opengl.util.awt.TextRenderer;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.PlaneSwitcherManager;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
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
        private TextRenderer planeSwitcherRenderer;

        private final int baseWidth = configService
                        .getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
        private final int baseHeight = configService
                        .getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
        private int viewportWidth = baseWidth;
        private int viewportHeight = baseHeight;
        private double scaleX = 1.0;
        private double scaleY = 1.0;

	private String sonicCode = configService
			.getString(SonicConfiguration.MAIN_CHARACTER_CODE);

	public void renderDebugInfo() {
                if (renderer == null) {
                        renderer = new TextRenderer(new Font(
                                        "SansSerif", Font.PLAIN, 14), true, true);
                }
                if (objectRenderer == null) {
                        objectRenderer = new TextRenderer(new Font(
                                        "SansSerif", Font.PLAIN, 12), true, true);
                }
                if (planeSwitcherRenderer == null) {
                        planeSwitcherRenderer = new TextRenderer(new Font(
                                        "SansSerif", Font.PLAIN, 12), true, true);
                }

                renderer.beginRendering(viewportWidth, viewportHeight);

		Sprite sprite = spriteManager.getSprite(sonicCode);
		if (sprite != null) {
			int ringCount = 0;
			if (sprite instanceof AbstractPlayableSprite) {
				ringCount = ((AbstractPlayableSprite) sprite).getRingCount();
                                drawOutlined(renderer,
                                                "SpdshConst: " + ((AbstractPlayableSprite) sprite).getSpindashConstant(),
                                                uiX(2), uiY(baseHeight - 10), Color.WHITE);
                                drawOutlined(renderer,
                                                "Dir: " + ((AbstractPlayableSprite) sprite).getDirection(),
                                                uiX(2), uiY(baseHeight - 20), Color.WHITE);
                                drawOutlined(renderer,
                                                "Mode: "
                                                                + ((AbstractPlayableSprite) sprite)
                                                                .getGroundMode(),
                                                uiX(2), uiY(103), Color.WHITE);
                                if (((AbstractPlayableSprite) sprite).getAir()) {
                                        drawOutlined(renderer, "Air",
                                                        uiX(24), uiY(baseHeight - 40), Color.WHITE);
                                }
                                if (((AbstractPlayableSprite) sprite).getRolling()) {
                                        drawOutlined(renderer, "Roll",
                                                        uiX(24), uiY(baseHeight - 40), Color.WHITE);
                                }
                                if (((AbstractPlayableSprite) sprite).getSpindash()) {
                                        drawOutlined(renderer, "Spdash",
                                                        uiX(24), uiY(baseHeight - 40), Color.WHITE);
                                }
                                drawOutlined(renderer,
                                                "gS:" + ((AbstractPlayableSprite) sprite).getGSpeed(),
                                                uiX(2), uiY(baseHeight - 50), Color.WHITE);
                                drawOutlined(renderer,
                                                "xS:" + ((AbstractPlayableSprite) sprite).getXSpeed(),
                                                uiX(2), uiY(baseHeight - 60), Color.WHITE);
                                drawOutlined(renderer,
                                                "yS:" + ((AbstractPlayableSprite) sprite).getYSpeed(),
                                                uiX(2), uiY(baseHeight - 70), Color.WHITE);
                                // DECIMAL VERSION:
                                drawOutlined(renderer,
                                                "Deg:" + (((AbstractPlayableSprite) sprite).getAngle()),
                                                uiX(2), uiY(baseHeight - 80), Color.WHITE);
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
                        drawOutlined(renderer, "pX: " + xString, uiX(2), uiY(baseHeight - 95), Color.WHITE);
                        drawOutlined(renderer, "pY: " + yString, uiX(2), uiY(baseHeight - 105), Color.WHITE);
                        if (sprite instanceof AbstractPlayableSprite playable) {
                                String layerLabel = String.valueOf(formatLayer(playable.getLayer()));
                                String priorityLabel = String.valueOf(formatPriority(playable.isHighPriority()));
                                drawOutlined(renderer, "Layer: " + layerLabel + " Prio: " + priorityLabel,
                                                uiX(2), uiY(baseHeight - 115), Color.WHITE);
                        } else {
                                drawOutlined(renderer, "Layer: " + sprite.getLayer(),
                                                uiX(2), uiY(baseHeight - 115), Color.WHITE);
                        }

                        drawOutlined(renderer, xString.toString(), uiX(2), uiY(25), Color.WHITE);
                        drawOutlined(renderer, yString.toString(), uiX(2), uiY(12), Color.WHITE);

			String ringText = String.format("RINGS: %03d", ringCount);
			int ringTextWidth = (int) Math.ceil(renderer.getBounds(ringText).getWidth());
                        int ringX = Math.max(2, viewportWidth - ringTextWidth - 4);
                        drawOutlined(renderer, ringText, ringX, uiY(6), Color.WHITE);

                        renderer.endRendering();

                        TextRenderer sensorRenderer = new TextRenderer(new Font(
                                        "SansSerif", Font.PLAIN, 11), true, true);
                        sensorRenderer.beginRendering(viewportWidth, viewportHeight);
                        AbstractPlayableSprite abstractPlayableSprite = (AbstractPlayableSprite) sprite;

                        for(Sensor sensor : abstractPlayableSprite.getAllSensors()) {
                                SensorResult result = sensor.getCurrentResult();
                                if (sensor.isActive() && result != null) {
                                        Camera camera = Camera.getInstance();

                                        SensorConfiguration sensorConfiguration = SpriteManager
                                                        .getSensorConfigurationForGroundModeAndDirection(
                                                                        abstractPlayableSprite.getGroundMode(),
                                                                        sensor.getDirection());
                                        Direction globalDirection = sensorConfiguration.direction();
                                        short[] rotatedOffset = sensor.getRotatedOffset();

                                        short worldX = (short) (sprite.getCentreX() + rotatedOffset[0]);
                                        short worldY = (short) (sprite.getCentreY() + rotatedOffset[1]);
                                        short xAdjusted = (short) (worldX - camera.getX());
                                        short yAdjusted = (short) (worldY - camera.getY());

                                        byte solidityBit = (globalDirection == Direction.DOWN || globalDirection == Direction.UP)
                                                        ? abstractPlayableSprite.getTopSolidBit()
                                                        : abstractPlayableSprite.getLrbSolidBit();
                                        char tableLabel = (solidityBit >= 0x0E) ? 'S' : 'P';

                                        String angleHex = String.format("%02X", result.angle() & 0xFF);
                                        String label = String.format("%s d:%d a:%s b:%02X %c",
                                                        globalDirection.name().substring(0, 1),
                                                        result.distance(),
                                                        angleHex,
                                                        solidityBit & 0xFF,
                                                        tableLabel);
                                        String coords = String.format("x:%d y:%d", (int) worldX, (int) worldY);

                                        Color sensorColor = getSensorColor(globalDirection);
                                        int screenX = toScreenX(xAdjusted);
                                        int screenY = toScreenYFromWorld(yAdjusted);
                                        drawOutlined(sensorRenderer, label, screenX, screenY, sensorColor);
                                        drawOutlined(sensorRenderer, coords, screenX, screenY - uiY(6), sensorColor);
                                }
                        }
                        sensorRenderer.endRendering();
		}

                renderObjectLabels();
                renderPlayerPlaneState();
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

                objectRenderer.beginRendering(viewportWidth, viewportHeight);
                for (ObjectSpawn spawn : spawns) {
                        int screenX = spawn.x() - camera.getX();
                        int screenY = spawn.y() - camera.getY();

                        if (screenX < -8 || screenX > baseWidth + 8) {
                                continue;
                        }
                        if (screenY < -8 || screenY > baseHeight + 8) {
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

                        drawOutlined(objectRenderer, label.toString(),
                                        toScreenX(screenX + 2),
                                        toScreenYFromWorld(screenY) + uiY(2),
                                        Color.MAGENTA);
                }
                objectRenderer.endRendering();

                if (planeSwitcherRenderer == null) {
                        return;
                }
                planeSwitcherRenderer.beginRendering(viewportWidth, viewportHeight);
                for (ObjectSpawn spawn : spawns) {
                        if (spawn.objectId() != PlaneSwitcherManager.OBJECT_ID) {
                                continue;
                        }
                        int screenX = spawn.x() - camera.getX();
                        int screenY = spawn.y() - camera.getY();
                        if (screenX < -8 || screenX > baseWidth + 8) {
                                continue;
                        }
                        if (screenY < -8 || screenY > baseHeight + 8) {
                                continue;
                        }

                        drawPlaneSwitcherLabels(spawn, screenX, screenY);
                }
                planeSwitcherRenderer.endRendering();
        }

        private Color getSensorColor(Direction direction) {
                return switch (direction) {
                        case DOWN -> new Color(0, 220, 0);
                        case UP -> new Color(0, 200, 255);
                        case LEFT -> new Color(255, 200, 0);
                        case RIGHT -> new Color(255, 120, 0);
                };
        }

        private void drawPlaneSwitcherLabels(ObjectSpawn spawn, int screenX, int screenY) {
                int subtype = spawn.subtype();
                boolean horizontal = PlaneSwitcherManager.isHorizontal(subtype);
                String side0 = formatPlaneSwitcherSide(subtype, 0);
                String side1 = formatPlaneSwitcherSide(subtype, 1);

                if (horizontal) {
                        int aboveY = screenY - 6;
                        int belowY = screenY + 6;
                        drawOutlined(planeSwitcherRenderer, side0,
                                        toScreenX(screenX + 2),
                                        toScreenYFromWorld(aboveY),
                                        new Color(255, 140, 0));
                        drawOutlined(planeSwitcherRenderer, side1,
                                        toScreenX(screenX + 2),
                                        toScreenYFromWorld(belowY),
                                        new Color(255, 140, 0));
                } else {
                        int leftX = screenX - 16;
                        int rightX = screenX + 6;
                        drawOutlined(planeSwitcherRenderer, side0,
                                        toScreenX(leftX), toScreenYFromWorld(screenY),
                                        new Color(255, 140, 0));
                        drawOutlined(planeSwitcherRenderer, side1,
                                        toScreenX(rightX), toScreenYFromWorld(screenY),
                                        new Color(255, 140, 0));
                }
        }

        private void renderPlayerPlaneState() {
                if (planeSwitcherRenderer == null) {
                        return;
                }
                Sprite sprite = spriteManager.getSprite(sonicCode);
                if (!(sprite instanceof AbstractPlayableSprite playable)) {
                        return;
                }
                Camera camera = Camera.getInstance();
                int screenX = playable.getCentreX() - camera.getX();
                int screenY = playable.getY() - camera.getY();
                if (screenX < -16 || screenX > baseWidth + 16) {
                        return;
                }
                if (screenY < -16 || screenY > baseHeight + 16) {
                        return;
                }
                String label = formatLayer(playable.getLayer()) + " " + formatPriority(playable.isHighPriority());
                planeSwitcherRenderer.beginRendering(viewportWidth, viewportHeight);
                drawOutlined(planeSwitcherRenderer, label,
                                toScreenX(screenX - 6),
                                toScreenYFromWorld(screenY) + uiY(8),
                                new Color(255, 140, 0));
                planeSwitcherRenderer.endRendering();
        }

        private String formatPlaneSwitcherSide(int subtype, int side) {
                int path = PlaneSwitcherManager.decodePath(subtype, side);
                boolean highPriority = PlaneSwitcherManager.decodePriority(subtype, side);
                return formatLayer((byte) path) + " " + formatPriority(highPriority);
        }

        private char formatLayer(byte layer) {
                return PlaneSwitcherManager.formatLayer(layer);
        }

        private char formatPriority(boolean highPriority) {
                return PlaneSwitcherManager.formatPriority(highPriority);
        }

        private void drawOutlined(TextRenderer textRenderer, String text, int x, int y, Color color) {
                textRenderer.setColor(Color.BLACK);
                textRenderer.draw(text, x - 1, y);
                textRenderer.draw(text, x + 1, y);
                textRenderer.draw(text, x, y - 1);
                textRenderer.draw(text, x, y + 1);
                textRenderer.draw(text, x - 1, y - 1);
                textRenderer.draw(text, x + 1, y - 1);
                textRenderer.draw(text, x - 1, y + 1);
                textRenderer.draw(text, x + 1, y + 1);
                textRenderer.setColor(color);
                textRenderer.draw(text, x, y);
        }

        public static synchronized DebugRenderer getInstance() {
                if (debugRenderer == null) {
                        debugRenderer = new DebugRenderer();
                }
                return debugRenderer;
        }

        public void updateViewport(int viewportWidth, int viewportHeight) {
                if (viewportWidth <= 0 || viewportHeight <= 0) {
                        return;
                }
                this.viewportWidth = viewportWidth;
                this.viewportHeight = viewportHeight;
                this.scaleX = viewportWidth / (double) baseWidth;
                this.scaleY = viewportHeight / (double) baseHeight;
        }

        private int uiX(int gameX) {
                return toScreenX(gameX);
        }

        private int uiY(int gameY) {
                return toScreenY(gameY);
        }

        private int toScreenX(int gameX) {
                return (int) Math.round(gameX * scaleX);
        }

        private int toScreenY(int gameY) {
                return (int) Math.round(gameY * scaleY);
        }

        private int toScreenYFromWorld(int worldY) {
                return viewportHeight - toScreenY(worldY);
        }
}
