package uk.co.jamesj999.sonic.debug;

import uk.co.jamesj999.sonic.game.GameServices;
import uk.co.jamesj999.sonic.game.GameModuleRegistry;

import com.jogamp.opengl.util.awt.TextRenderer;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.ObjectManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRegistry;
import uk.co.jamesj999.sonic.level.objects.TouchResponseDebugHit;
import uk.co.jamesj999.sonic.level.objects.TouchResponseDebugState;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class DebugRenderer {
	private static DebugRenderer debugRenderer;
	// private final GraphicsManager graphicsManager = GraphicsManager
	// .getInstance();
	private final SpriteManager spriteManager = SpriteManager.getInstance();
	private final LevelManager levelManager = LevelManager.getInstance();
        private final SonicConfigurationService configService = SonicConfigurationService
                        .getInstance();
        private final DebugOverlayManager overlayManager = GameServices.debugOverlay();
        private TextRenderer renderer;
        private TextRenderer objectRenderer;
        private TextRenderer planeSwitcherRenderer;
        private TextRenderer sensorRenderer;
        private static final String[] SENSOR_LABELS = {"A", "B", "C", "D", "E", "F"};

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
                                        "SansSerif", Font.PLAIN, 12), true, true);
                }
                if (objectRenderer == null) {
                        objectRenderer = new TextRenderer(new Font(
                                        "SansSerif", Font.PLAIN, 11), true, true);
                }
                if (planeSwitcherRenderer == null) {
                        planeSwitcherRenderer = new TextRenderer(new Font(
                                        "SansSerif", Font.PLAIN, 11), true, true);
                }
                if (sensorRenderer == null) {
                        sensorRenderer = new TextRenderer(new Font(
                                        "SansSerif", Font.PLAIN, 10), true, true);
                }

                renderer.beginRendering(viewportWidth, viewportHeight);

                boolean showOverlay = overlayManager.isEnabled(DebugOverlayToggle.OVERLAY);
                boolean showShortcuts = overlayManager.isEnabled(DebugOverlayToggle.SHORTCUTS);

                if (!showOverlay) {
                        if (showShortcuts) {
                                renderOverlayShortcuts(renderer, true);
                        } else {
                                drawOutlined(renderer,
                                                "Overlay Off (" + DebugOverlayToggle.OVERLAY.shortcutLabel() + ")",
                                                uiX(6), uiY(baseHeight - 6), Color.WHITE);
                        }
                        renderer.endRendering();
                        return;
                }

                if (showShortcuts) {
                        renderOverlayShortcuts(renderer, false);
                }

                Sprite sprite = spriteManager.getSprite(sonicCode);
                AbstractPlayableSprite playable = null;
                if (sprite != null) {
                        int ringCount = 0;
                        if (sprite instanceof AbstractPlayableSprite casted) {
                                playable = casted;
                                ringCount = casted.getRingCount();
                                if (overlayManager.isEnabled(DebugOverlayToggle.PLAYER_PANEL)) {
                                        renderPlayerStatusPanel(casted, ringCount);
                                }
                                if (overlayManager.isEnabled(DebugOverlayToggle.TOUCH_RESPONSE)) {
                                        renderTouchResponsePanel(casted);
                                }
                        }
                }
                if (overlayManager.isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER)) {
                        renderObjectArtViewerPanel();
                }

                renderer.endRendering();

                if (playable != null && overlayManager.isEnabled(DebugOverlayToggle.SENSOR_LABELS)) {
                        sensorRenderer.beginRendering(viewportWidth, viewportHeight);
                        Sensor[] sensors = playable.getAllSensors();
                        for (int i = 0; i < sensors.length && i < SENSOR_LABELS.length; i++) {
                                Sensor sensor = sensors[i];
                                if (sensor == null) {
                                        continue;
                                }
                                SensorResult result = sensor.getCurrentResult();
                                if (sensor.isActive() && result != null) {
                                        Camera camera = Camera.getInstance();

                                        SensorConfiguration sensorConfiguration = SpriteManager
                                                        .getSensorConfigurationForGroundModeAndDirection(
                                                                        playable.getGroundMode(),
                                                                        sensor.getDirection());
                                        Direction globalDirection = sensorConfiguration.direction();
                                        short[] rotatedOffset = sensor.getRotatedOffset();

                                        short worldX = (short) (playable.getCentreX() + rotatedOffset[0]);
                                        short worldY = (short) (playable.getCentreY() + rotatedOffset[1]);
                                        short xAdjusted = (short) (worldX - camera.getX());
                                        short yAdjusted = (short) (worldY - camera.getY());

                                        String angleHex = String.format("%02X", result.angle() & 0xFF);
                                        String label = String.format("%s(%s) d:%d a:%s",
                                                        SENSOR_LABELS[i],
                                                        globalDirection.name().substring(0, 1),
                                                        result.distance(),
                                                        angleHex);

                                        Color sensorColor = DebugOverlayPalette.sensorLabelColor(i, true);
                                        int screenX = toScreenX(xAdjusted);
                                        int screenY = toScreenYFromWorld(yAdjusted);
                                        int offsetX = 0;
                                        int offsetY = 0;
                                        int stackOffset = (i % 2 == 0) ? 0 : uiY(6);
                                        switch (globalDirection) {
                                                case DOWN -> offsetY = uiY(10) + stackOffset;
                                                case UP -> offsetY = -uiY(10) - stackOffset;
                                                case LEFT -> {
                                                        offsetX = -uiX(32);
                                                        offsetY = stackOffset;
                                                }
                                                case RIGHT -> {
                                                        offsetX = uiX(6);
                                                        offsetY = stackOffset;
                                                }
                                        }
                                        drawOutlined(sensorRenderer, label, screenX + offsetX, screenY + offsetY, sensorColor);
                                }
                        }
                        sensorRenderer.endRendering();
                }

                if (overlayManager.isEnabled(DebugOverlayToggle.OBJECT_LABELS)) {
                        renderObjectLabels();
                }
                if (overlayManager.isEnabled(DebugOverlayToggle.PLANE_SWITCHERS)) {
                        renderPlayerPlaneState();
                }
        }

        private void renderObjectLabels() {
            if (objectRenderer == null) {
                return;
            }
                ObjectRegistry registry = GameModuleRegistry.getCurrent().createObjectRegistry();
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

                        String name = registry.getPrimaryName(spawn.objectId());
                        String line1 = String.format("%02X:%02X",
                                        spawn.objectId(), spawn.subtype());
                        if (spawn.renderFlags() != 0) {
                                line1 += " F" + Integer.toHexString(spawn.renderFlags()).toUpperCase();
                        }
                        if (spawn.respawnTracked()) {
                                line1 += " R";
                        }
                        int rawFlags = spawn.rawFlags() >> 12;
                        String line2 = rawFlags != 0
                                        ? ("YF:" + Integer.toHexString(rawFlags).toUpperCase())
                                        : null;

                        int labelX = toScreenX(screenX + 2);
                        int labelY = toScreenYFromWorld(screenY) + uiY(2);
                        int lineHeight = uiY(10);
                        drawOutlined(objectRenderer, name, labelX, labelY - lineHeight, Color.WHITE);
                        drawOutlined(objectRenderer, line1, labelX, labelY, Color.MAGENTA);
                        if (line2 != null) {
                                drawOutlined(objectRenderer, line2, labelX, labelY + uiY(10),
                                                new Color(255, 180, 255));
                        }
                }
                objectRenderer.endRendering();

                if (!overlayManager.isEnabled(DebugOverlayToggle.PLANE_SWITCHERS)) {
                        return;
                }
                if (planeSwitcherRenderer == null) {
                        return;
                }
                planeSwitcherRenderer.beginRendering(viewportWidth, viewportHeight);
                int planeSwitcherObjectId = GameModuleRegistry.getCurrent().getPlaneSwitcherObjectId();
                if (levelManager.getGameModule() != null) {
                        planeSwitcherObjectId = levelManager.getGameModule().getPlaneSwitcherObjectId();
                }
                for (ObjectSpawn spawn : spawns) {
                        if (spawn.objectId() != planeSwitcherObjectId) {
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

        private void renderOverlayShortcuts(TextRenderer textRenderer, boolean overlayOff) {
                List<String> lines = overlayManager.buildShortcutLines();
                if (overlayOff) {
                        lines.add(0, "Overlay Off (" + DebugOverlayToggle.OVERLAY.shortcutLabel() + ")");
                }
                int startX = uiX(baseWidth - 150);
                int startY = uiY(baseHeight - 6);
                int lineHeight = Math.max(8, uiY(9));
                int y = startY;
                for (String line : lines) {
                        drawOutlined(textRenderer, line, startX, y, Color.WHITE);
                        y -= lineHeight;
                }
        }

        private void drawPlaneSwitcherLabels(ObjectSpawn spawn, int screenX, int screenY) {
                int subtype = spawn.subtype();
                boolean horizontal = ObjectManager.isPlaneSwitcherHorizontal(subtype);
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

        private void renderPlayerStatusPanel(AbstractPlayableSprite sprite, int ringCount) {
                List<String> lines = new ArrayList<>();
                int angleByte = sprite.getAngle() & 0xFF;
                float angleDeg = ((256 - angleByte) * 360f / 256f) % 360f;

                lines.add("== PLAYER ==");
                lines.add(String.format("Pos: %d.%02X  %d.%02X",
                                (int) sprite.getX(), sprite.getXSubpixel() & 0xFF,
                                (int) sprite.getY(), sprite.getYSubpixel() & 0xFF));
                lines.add(String.format("Spd: X %d (%.2f)",
                                sprite.getXSpeed(), sprite.getXSpeed() / 256f));
                lines.add(String.format("Spd: Y %d (%.2f)",
                                sprite.getYSpeed(), sprite.getYSpeed() / 256f));
                lines.add(String.format("GSpd: %d (%.2f)",
                                sprite.getGSpeed(), sprite.getGSpeed() / 256f));
                lines.add(String.format("Angle: %02X (%.1f°)", angleByte, angleDeg));
                lines.add("Mode: " + sprite.getGroundMode());
                lines.add("Dir: " + sprite.getDirection());
                lines.add("State: " + formatStateFlags(sprite));
                lines.add(String.format("Layer: %c  Prio: %c",
                                formatLayer(sprite.getLayer()),
                                formatPriority(sprite.isHighPriority())));
                lines.add(String.format("Solidity: top %02X lrb %02X",
                                sprite.getTopSolidBit() & 0xFF,
                                sprite.getLrbSolidBit() & 0xFF));
                lines.add(String.format("Radii: x %d y %d", sprite.getXRadius(), sprite.getYRadius()));
                lines.add(String.format("Anim: id %d frame %d/%d tick %d",
                                sprite.getAnimationId(),
                                sprite.getAnimationFrameIndex(),
                                sprite.getAnimationFrameCount(),
                                sprite.getAnimationTick()));
                lines.add(String.format("MapFrame: %d", sprite.getMappingFrame()));
                lines.add(String.format("Rings: %d", ringCount));
                lines.add("== SENSORS ==");

                Sensor[] sensors = sprite.getAllSensors();
                for (int i = 0; i < sensors.length && i < SENSOR_LABELS.length; i++) {
                        Sensor sensor = sensors[i];
                        if (sensor == null) {
                                lines.add(SENSOR_LABELS[i] + ": --");
                                continue;
                        }
                        SensorConfiguration sensorConfiguration = SpriteManager
                                        .getSensorConfigurationForGroundModeAndDirection(
                                                        sprite.getGroundMode(), sensor.getDirection());
                        String dir = sensorConfiguration.direction().name().substring(0, 1);
                        String prefix = SENSOR_LABELS[i] + "(" + dir + "): ";
                        if (!sensor.isActive()) {
                                lines.add(prefix + "--");
                                continue;
                        }
                        SensorResult result = sensor.getCurrentResult();
                        if (result == null) {
                                lines.add(prefix + "??");
                                continue;
                        }
                        lines.add(String.format("%sd:%d a:%02X", prefix, result.distance(), result.angle() & 0xFF));
                }

                int startX = uiX(6);
                int startY = uiY(baseHeight - 6);
                int lineHeight = Math.max(8, uiY(9));
                int y = startY;
                for (String line : lines) {
                        drawOutlined(renderer, line, startX, y, Color.WHITE);
                        y -= lineHeight;
                }
        }

        private void renderTouchResponsePanel(AbstractPlayableSprite sprite) {
                ObjectManager manager = levelManager.getObjectManager();
                if (manager == null || renderer == null) {
                        return;
                }
                TouchResponseDebugState state = manager.getTouchResponseDebugState();
                if (state == null) {
                        return;
                }

                List<TouchResponseDebugHit> hits = state.getHits();
                int hitCount = 0;
                for (TouchResponseDebugHit hit : hits) {
                        if (hit.overlapping()) {
                                hitCount++;
                        }
                }

                List<String> lines = new ArrayList<>();
                lines.add("== TOUCH RESP ==");
                String crouch = state.isCrouching() ? "C" : "-";
                lines.add(String.format("Player: x %d y %d h %d yR %d %s",
                                state.getPlayerX(), state.getPlayerY(),
                                state.getPlayerHeight(), state.getPlayerYRadius(), crouch));
                lines.add(String.format("Objects: %d Hits: %d", hits.size(), hitCount));

                ObjectRegistry registry = GameModuleRegistry.getCurrent().createObjectRegistry();
                int maxLines = 12;
                int shown = 0;
                for (TouchResponseDebugHit hit : hits) {
                        if (shown >= maxLines) {
                                break;
                        }
                        ObjectSpawn spawn = hit.spawn();
                        String name = registry.getPrimaryName(spawn.objectId());
                        if (name.length() > 12) {
                                name = name.substring(0, 12);
                        }
                        String status = hit.overlapping() ? "HIT" : "--";
                        String category = formatTouchCategory(hit.category());
                        lines.add(String.format("%02X:%02X %s %s %02X %2d,%2d %s",
                                        spawn.objectId(), spawn.subtype(), status, category,
                                        hit.sizeIndex(), hit.width(), hit.height(), name));
                        shown++;
                }

                int startX = uiX(baseWidth - 240);
                int startY = uiY(baseHeight - 140);
                int lineHeight = Math.max(8, uiY(9));
                int y = startY;
                for (String line : lines) {
                        drawOutlined(renderer, line, startX, y, new Color(180, 255, 180));
                        y -= lineHeight;
                }
        }

        private void renderObjectArtViewerPanel() {
                if (renderer == null) {
                        return;
                }
                DebugObjectArtViewer viewer = DebugObjectArtViewer.getInstance();
                List<String> lines = new ArrayList<>();
                lines.add("== ART VIEWER ==");
                lines.add("Target: " + viewer.getTargetLabel());
                lines.add("Mode: " + viewer.getViewModeLabel());
                if (viewer.isPatternMode()) {
                        lines.add(String.format("Tile: %d", viewer.getPatternCursor()));
                        lines.add(String.format("Page: %d-%d", viewer.getPatternPageStart(),
                                        viewer.getPatternPageEnd()));
                        lines.add("Palette: " + viewer.getPaletteLabel());
                        lines.add("Keys: Arrows, Tab/M, PgUp/PgDn, 0-4");
                } else {
                        int maxFrames = viewer.getMaxFrames();
                        if (maxFrames > 0) {
                                lines.add(String.format("Frame: %d/%d", viewer.getFrameIndex(), maxFrames - 1));
                        } else {
                                lines.add("Frame: --");
                        }
                        lines.add("Keys: Left/Right, Tab/M, PgUp/PgDn");
                }

                int startX = uiX(baseWidth - 160);
                int startY = uiY(baseHeight - 120);
                int lineHeight = Math.max(8, uiY(9));
                int y = startY;
                for (String line : lines) {
                        drawOutlined(renderer, line, startX, y, new Color(180, 255, 180));
                        y -= lineHeight;
                }
        }

        private String formatStateFlags(AbstractPlayableSprite sprite) {
                StringBuilder flags = new StringBuilder();
                if (sprite.getAir()) {
                        flags.append("Air ");
                } else {
                        flags.append("Ground ");
                }
                if (sprite.getRolling()) {
                        flags.append("Roll ");
                }
                if (sprite.getSpindash()) {
                        flags.append("Spindash ");
                }
                if (sprite.getCrouching()) {
                        flags.append("Crouch ");
                }
                if (sprite.getPushing()) {
                        flags.append("Push ");
                }
                if (flags.length() == 0) {
                        return "None";
                }
                return flags.toString().trim();
        }

        private String formatPlaneSwitcherSide(int subtype, int side) {
                int path = ObjectManager.decodePlaneSwitcherPath(subtype, side);
                boolean highPriority = ObjectManager.decodePlaneSwitcherPriority(subtype, side);
                return formatLayer((byte) path) + " " + formatPriority(highPriority);
        }

        private char formatLayer(byte layer) {
                return ObjectManager.formatPlaneSwitcherLayer(layer);
        }

        private char formatPriority(boolean highPriority) {
                return ObjectManager.formatPlaneSwitcherPriority(highPriority);
        }

        private String formatTouchCategory(uk.co.jamesj999.sonic.level.objects.TouchCategory category) {
                if (category == null) {
                        return "?";
                }
                return switch (category) {
                        case ENEMY -> "E";
                        case SPECIAL -> "S";
                        case HURT -> "H";
                        case BOSS -> "B";
                };
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

