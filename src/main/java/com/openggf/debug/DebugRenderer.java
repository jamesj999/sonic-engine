package com.openggf.debug;

import com.openggf.game.CollisionModel;
import com.openggf.game.GameServices;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.GameModule;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.level.objects.TouchCategory;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.S3kZoneSet;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic2.slotmachine.CNZSlotMachineRenderer;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.TouchResponseDebugHit;
import com.openggf.level.objects.TouchResponseDebugState;
import com.openggf.physics.Direction;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import com.openggf.graphics.PixelFontTextRenderer;
import com.openggf.graphics.PixelFontVariant;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.SensorConfiguration;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.debug.playback.PlaybackDebugManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DebugRenderer {
	private static DebugRenderer debugRenderer;
        private final SonicConfigurationService configService;
        private final DebugOverlayManager overlayManager;
        private final PlaybackDebugManager playbackDebugManager;
        private final PerformanceProfiler profiler;
        private final PixelFontTextRenderer performanceTextRenderer;
        private GlyphBatchRenderer glyphBatch;
        private PerformancePanelRenderer performancePanelRenderer;
        private static final String[] SENSOR_LABELS = {"A", "B", "C", "D", "E", "F"};

        // Font size constants for different debug text categories
        private static final FontSize SENSOR_FONT = FontSize.SMALL;      // 10pt - sensor labels
        private static final FontSize OBJECT_LABEL_FONT = FontSize.MEDIUM; // 11pt - object labels
        private static final FontSize PANEL_FONT = FontSize.LARGE;        // 12pt - main status panels

        // Cached DebugColor objects to avoid per-frame allocations
        private static final DebugColor COLOR_PLANE_SWITCH = new DebugColor(255, 140, 0);
        private static final DebugColor COLOR_TOUCH_PANEL = new DebugColor(180, 255, 180);
        private static final DebugColor COLOR_OBJECT_SUBTYPE = new DebugColor(255, 180, 255);
        private static final DebugColor COLOR_ART_VIEWER = new DebugColor(180, 255, 180);
        private static final DebugColor COLOR_PACHINKO_TRAP = new DebugColor(160, 255, 255);

        // Reusable lists for panel rendering to avoid per-frame allocations
        private final List<String> playerStatusLines = new ArrayList<>(24);
        private final List<String> touchResponseLines = new ArrayList<>(20);
        private final List<String> artViewerLines = new ArrayList<>(16);
        private final List<String> pachinkoTrapLines = new ArrayList<>(10);

        // Reusable StringBuilders for string construction
        private final StringBuilder stateFlagsBuilder = new StringBuilder(64);
        private final StringBuilder objectLabelBuilder = new StringBuilder(32);
        private final StringBuilder sensorLabelBuilder = new StringBuilder(32);
        private final StringBuilder panelLineBuilder = new StringBuilder(64);

        private final int baseWidth;
        private final int baseHeight;
        private int viewportWidth;
        private int viewportHeight;
        private double scaleX = 1.0;
        private double scaleY = 1.0;

        static record SensorLabelSpec(int orthogonalAxis, int sensorIndex, int labelWidth) {
        }

        static record SensorLabelPlacement(SensorLabelSpec spec, int drawX, int drawY) {
        }

        private record ActiveSensorLabel(SensorLabelSpec spec, String label, DebugColor color) {
        }

        public DebugRenderer() {
                this(GameServices.configuration(), GameServices.debugOverlay(),
                        GameServices.playbackDebug(), GameServices.profiler());
        }

        public DebugRenderer(SonicConfigurationService configService,
                             DebugOverlayManager overlayManager,
                             PlaybackDebugManager playbackDebugManager,
                             PerformanceProfiler profiler) {
                this.configService = Objects.requireNonNull(configService, "configService");
                this.overlayManager = Objects.requireNonNull(overlayManager, "overlayManager");
                this.playbackDebugManager = Objects.requireNonNull(playbackDebugManager, "playbackDebugManager");
                this.profiler = Objects.requireNonNull(profiler, "profiler");
                this.performanceTextRenderer = new PixelFontTextRenderer(PixelFontVariant.PIXEL_FONT_NO_SHADOW);
                this.baseWidth = this.configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
                this.baseHeight = this.configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
                this.viewportWidth = baseWidth;
                this.viewportHeight = baseHeight;
        }

	/**
	 * Eagerly initializes debug text resources.
	 */
	public void eagerInit() {
		if (glyphBatch == null) {
			float scale = (float) Math.max(scaleX, scaleY);
			glyphBatch = new GlyphBatchRenderer(
                                new PixelFontTextRenderer(PixelFontVariant.PIXEL_FONT_NO_SHADOW));
			glyphBatch.init(null, scale);
		}
	}

	public void renderDebugInfo() {
                // Lazy initialization of glyph batch renderer
                float scale = (float) Math.max(scaleX, scaleY);
                if (glyphBatch == null) {
                        glyphBatch = new GlyphBatchRenderer(
                                new PixelFontTextRenderer(PixelFontVariant.PIXEL_FONT_NO_SHADOW));
                        glyphBatch.init(null, scale);
                }
                if (!glyphBatch.isInitialized()) {
                        return;
                }
                // Reinitialize if scale changed significantly (e.g., window resize)
                glyphBatch.updateScale(null, scale);
                glyphBatch.updateViewport(viewportWidth, viewportHeight);

                glyphBatch.begin();

                boolean showOverlay = overlayManager.isEnabled(DebugOverlayToggle.OVERLAY);
                boolean showShortcuts = overlayManager.isEnabled(DebugOverlayToggle.SHORTCUTS);

                // Always show playback status panel when playback is active/loaded,
                // even if the normal overlay is toggled off.
                renderPlaybackPanel();

                if (!showOverlay) {
                        if (showShortcuts) {
                                renderOverlayShortcuts(true);
                        }
                        glyphBatch.end();
                        if (overlayManager.isEnabled(DebugOverlayToggle.PERFORMANCE)) {
                                renderPerformancePanel();
                        }
                        return;
                }

                if (showShortcuts) {
                        renderOverlayShortcuts(false);
                }

                Sprite sprite = getSpriteManager().getSprite(getMainCharacterCode());
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
                renderPachinkoTrapPanel(playable);
                if (overlayManager.isEnabled(DebugOverlayToggle.OBJECT_ART_VIEWER)) {
                        renderObjectArtViewerPanel();
                }
                // Render sensor labels
                if (playable != null && overlayManager.isEnabled(DebugOverlayToggle.SENSOR_LABELS)) {
                        renderSensorLabels(playable);
                }

                // Render object labels
                if (overlayManager.isEnabled(DebugOverlayToggle.OBJECT_LABELS)) {
                        renderObjectLabels();
                }

                // Render player plane state
                if (overlayManager.isEnabled(DebugOverlayToggle.PLANE_SWITCHERS)) {
                        renderPlayerPlaneState();
                }

                // Render per-object debug text labels
                if (overlayManager.isEnabled(DebugOverlayToggle.OBJECT_DEBUG)) {
                        renderObjectDebugLabels();
                }

                // End the batch - single draw call for all text
                glyphBatch.end();

                // Render performance panel (requires separate GL calls for pie chart)
                if (overlayManager.isEnabled(DebugOverlayToggle.PERFORMANCE)) {
                        renderPerformancePanel();
                }
        }

        public void renderPerformanceOverlay() {
                renderPerformancePanel();
        }

        private void renderObjectLabels() {
                if (!glyphBatch.isBatchActive()) {
                        return;
                }
                GameModule module = getLevelManager().getGameModule();
                if (module == null) {
                        module = GameServices.module();
                }
                ObjectRegistry registry = module.createObjectRegistry();
                java.util.Collection<ObjectSpawn> spawns = getLevelManager().getActiveObjectSpawns();
                if (registry == null || spawns.isEmpty()) {
                        return;
                }
                Camera camera = GameServices.camera();

                for (ObjectSpawn spawn : spawns) {
                        int screenX = spawn.x() - camera.getX();
                        int screenY = spawn.y() - camera.getY();

                        if (screenX < -8 || screenX > baseWidth + 8) {
                                continue;
                        }
                        if (screenY < -8 || screenY > baseHeight + 8) {
                                continue;
                        }

                        String name = resolveObjectNameForDebug(registry, spawn.objectId(),
                                getLevelManager().getRomZoneId());
                        objectLabelBuilder.setLength(0);
                        DebugRenderContext.appendHex2(objectLabelBuilder, spawn.objectId());
                        objectLabelBuilder.append(':');
                        DebugRenderContext.appendHex2(objectLabelBuilder, spawn.subtype());
                        if (spawn.renderFlags() != 0) {
                                objectLabelBuilder.append(" F").append(Integer.toHexString(spawn.renderFlags()).toUpperCase());
                        }
                        if (spawn.respawnTracked()) {
                                objectLabelBuilder.append(" R");
                        }
                        String line1 = objectLabelBuilder.toString();
                        int rawFlags = spawn.rawFlags() >> 12;
                        String line2 = rawFlags != 0
                                        ? ("YF:" + Integer.toHexString(rawFlags).toUpperCase())
                                        : null;

                        int labelX = toScreenX(screenX + 2);
                        int labelY = toScreenYFromWorld(screenY) + uiY(2);
                        int lineHeight = glyphBatch.getLineHeight(OBJECT_LABEL_FONT);
                        glyphBatch.drawTextOutlined(name, labelX, labelY - lineHeight, DebugColor.WHITE, OBJECT_LABEL_FONT);
                        glyphBatch.drawTextOutlined(line1, labelX, labelY, DebugColor.MAGENTA, OBJECT_LABEL_FONT);
                        if (line2 != null) {
                                glyphBatch.drawTextOutlined(line2, labelX, labelY + lineHeight,
                                                COLOR_OBJECT_SUBTYPE, OBJECT_LABEL_FONT);
                        }
                }

                if (!overlayManager.isEnabled(DebugOverlayToggle.PLANE_SWITCHERS)) {
                        return;
                }
                GameModule planeSwitcherModule = getLevelManager().getGameModule();
                if (planeSwitcherModule == null) {
                        planeSwitcherModule = GameServices.module();
                }
                int planeSwitcherObjectId = planeSwitcherModule.getPlaneSwitcherObjectId();
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
        }

        private void renderOverlayShortcuts(boolean overlayOff) {
                List<String> lines = overlayManager.buildShortcutLines();
                if (overlayOff) {
                        lines.add(0, "Overlay Off (" + DebugOverlayToggle.OVERLAY.shortcutLabel() + ")");
                }
                int startX = uiX(baseWidth - 150);
                int startY = uiY(baseHeight - 18);
                int lineHeight = glyphBatch.getLineHeight(PANEL_FONT);
                int y = startY;
                for (String line : lines) {
                        glyphBatch.drawTextOutlined(line, startX, y, DebugColor.WHITE, PANEL_FONT);
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
                        glyphBatch.drawTextOutlined(side0,
                                        toScreenX(screenX + 2),
                                        toScreenYFromWorld(aboveY),
                                        COLOR_PLANE_SWITCH, OBJECT_LABEL_FONT);
                        glyphBatch.drawTextOutlined(side1,
                                        toScreenX(screenX + 2),
                                        toScreenYFromWorld(belowY),
                                        COLOR_PLANE_SWITCH, OBJECT_LABEL_FONT);
                } else {
                        int leftX = screenX - 16;
                        int rightX = screenX + 6;
                        glyphBatch.drawTextOutlined(side0,
                                        toScreenX(leftX), toScreenYFromWorld(screenY),
                                        COLOR_PLANE_SWITCH, OBJECT_LABEL_FONT);
                        glyphBatch.drawTextOutlined(side1,
                                        toScreenX(rightX), toScreenYFromWorld(screenY),
                                        COLOR_PLANE_SWITCH, OBJECT_LABEL_FONT);
                }
        }

        private void renderPlayerPlaneState() {
                if (!glyphBatch.isBatchActive()) {
                        return;
                }
                Sprite sprite = getSpriteManager().getSprite(getMainCharacterCode());
                if (!(sprite instanceof AbstractPlayableSprite playable)) {
                        return;
                }
                Camera camera = GameServices.camera();
                int screenX = playable.getCentreX() - camera.getX();
                int screenY = playable.getCentreY() - camera.getY();
                if (screenX < -16 || screenX > baseWidth + 16) {
                        return;
                }
                if (screenY < -16 || screenY > baseHeight + 16) {
                        return;
                }
                String label;
                if (isUnifiedCollision(playable)) {
                        // S1 (UNIFIED): layer derived from loop-low-plane; tunnel = 0/1
                        char layerChar = playable.isLoopLowPlane() ? 'B' : 'A';
                        char tunnelChar = playable.isTunnelMode() ? '1' : '0';
                        label = layerChar + " " + tunnelChar;
                } else {
                        // S2/S3K (DUAL_PATH): collision path + VDP sprite priority
                        label = formatLayer(playable.getLayer()) + " " + formatPriority(playable.isHighPriority());
                }
                glyphBatch.drawTextOutlined(label,
                                toScreenX(screenX - 6),
                                toScreenYFromWorld(screenY) + uiY(8),
                                COLOR_PLANE_SWITCH, OBJECT_LABEL_FONT);
        }

        private void renderObjectDebugLabels() {
                if (!glyphBatch.isBatchActive()) {
                        return;
                }
                List<DebugRenderContext.DebugTextEntry> entries = overlayManager.getObjectDebugTextEntries();
                if (entries.isEmpty()) {
                        return;
                }
                Camera camera = GameServices.camera();
                int lineHeight = glyphBatch.getLineHeight(OBJECT_LABEL_FONT);

                for (DebugRenderContext.DebugTextEntry entry : entries) {
                        int screenX = entry.worldX() - camera.getX();
                        int screenY = entry.worldY() - camera.getY();

                        if (screenX < -32 || screenX > baseWidth + 32) {
                                continue;
                        }
                        if (screenY < -32 || screenY > baseHeight + 32) {
                                continue;
                        }

                        int px = toScreenX(screenX + 2);
                        int py = toScreenYFromWorld(screenY) + entry.lineOffset() * lineHeight;
                        glyphBatch.drawTextOutlined(entry.text(), px, py, entry.color(), OBJECT_LABEL_FONT);
                }

                overlayManager.clearObjectDebugTextEntries();
        }

        private void renderSensorLabels(AbstractPlayableSprite playable) {
                if (!glyphBatch.isBatchActive()) {
                        return;
                }
                Camera camera = GameServices.camera();
                Sensor[] sensors = playable.getAllSensors();
                List<ActiveSensorLabel> upLabels = new ArrayList<>(2);
                List<ActiveSensorLabel> downLabels = new ArrayList<>(2);
                List<ActiveSensorLabel> leftLabels = new ArrayList<>(2);
                List<ActiveSensorLabel> rightLabels = new ArrayList<>(2);

                for (int i = 0; i < sensors.length && i < SENSOR_LABELS.length; i++) {
                        Sensor sensor = sensors[i];
                        if (sensor == null || !sensor.isActive()) {
                                continue;
                        }
                        SensorResult result = sensor.getCurrentResult();
                        if (result == null) {
                                continue;
                        }

                        SensorConfiguration sensorConfiguration = SpriteManager
                                        .getSensorConfigurationForGroundModeAndDirection(
                                                        playable.getGroundMode(),
                                                        sensor.getDirection());
                        Direction globalDirection = sensorConfiguration.direction();
                        sensor.computeRotatedOffset();

                        short worldX = (short) (playable.getCentreX() + sensor.getRotatedX());
                        short worldY = (short) (playable.getCentreY() + sensor.getRotatedY());
                        short xAdjusted = (short) (worldX - camera.getX());
                        short yAdjusted = (short) (worldY - camera.getY());

                        sensorLabelBuilder.setLength(0);
                        sensorLabelBuilder.append(SENSOR_LABELS[i]).append('(')
                                        .append(globalDirection.name().charAt(0))
                                        .append(") d:").append(result.distance())
                                        .append(" a:");
                        DebugRenderContext.appendHex2(sensorLabelBuilder, result.angle() & 0xFF);
                        String label = sensorLabelBuilder.toString();
                        int labelWidth = glyphBatch.measureTextWidth(label, SENSOR_FONT);
                        int orthogonalAxis = switch (globalDirection) {
                                case UP, DOWN -> toScreenX(xAdjusted);
                                case LEFT, RIGHT -> toScreenYFromWorld(yAdjusted);
                        };
                        ActiveSensorLabel activeLabel = new ActiveSensorLabel(
                                        new SensorLabelSpec(orthogonalAxis, i, labelWidth),
                                        label,
                                        DebugOverlayPalette.sensorLabelColor(i, true));
                        switch (globalDirection) {
                                case UP -> upLabels.add(activeLabel);
                                case DOWN -> downLabels.add(activeLabel);
                                case LEFT -> leftLabels.add(activeLabel);
                                case RIGHT -> rightLabels.add(activeLabel);
                        }
                }

                int centerX = toScreenX(playable.getCentreX() - camera.getX());
                int centerY = toScreenYFromWorld(playable.getCentreY() - camera.getY());
                int xRadius = Math.max(uiX(playable.getXRadius()), uiX(6));
                int yRadius = Math.max(uiY(playable.getYRadius()), uiY(6));
                int lineHeight = glyphBatch.getLineHeight(SENSOR_FONT);
                int sideMarginX = uiX(8);
                int sideMarginY = uiY(10);
                int labelGap = Math.max(1, uiX(3));

                drawSensorLabelGroup(Direction.UP, upLabels, centerX, centerY, xRadius, yRadius,
                                lineHeight, sideMarginX, sideMarginY, labelGap);
                drawSensorLabelGroup(Direction.DOWN, downLabels, centerX, centerY, xRadius, yRadius,
                                lineHeight, sideMarginX, sideMarginY, labelGap);
                drawSensorLabelGroup(Direction.LEFT, leftLabels, centerX, centerY, xRadius, yRadius,
                                lineHeight, sideMarginX, sideMarginY, labelGap);
                drawSensorLabelGroup(Direction.RIGHT, rightLabels, centerX, centerY, xRadius, yRadius,
                                lineHeight, sideMarginX, sideMarginY, labelGap);
        }

        private void drawSensorLabelGroup(Direction direction,
                                          List<ActiveSensorLabel> activeLabels,
                                          int centerX,
                                          int centerY,
                                          int xRadius,
                                          int yRadius,
                                          int lineHeight,
                                          int sideMarginX,
                                          int sideMarginY,
                                          int labelGap) {
                if (activeLabels.isEmpty()) {
                        return;
                }
                List<SensorLabelSpec> specs = new ArrayList<>(activeLabels.size());
                for (ActiveSensorLabel activeLabel : activeLabels) {
                        specs.add(activeLabel.spec());
                }
                List<SensorLabelPlacement> placements = layoutSensorLabelsForSide(
                                direction,
                                centerX,
                                centerY,
                                xRadius,
                                yRadius,
                                lineHeight,
                                sideMarginX,
                                sideMarginY,
                                labelGap,
                                specs);
                for (SensorLabelPlacement placement : placements) {
                        ActiveSensorLabel activeLabel = findSensorLabel(activeLabels, placement.spec().sensorIndex());
                        if (activeLabel == null) {
                                continue;
                        }
                        glyphBatch.drawTextOutlined(activeLabel.label(), placement.drawX(), placement.drawY(),
                                        activeLabel.color(), SENSOR_FONT);
                }
        }

        private ActiveSensorLabel findSensorLabel(List<ActiveSensorLabel> activeLabels, int sensorIndex) {
                for (ActiveSensorLabel activeLabel : activeLabels) {
                        if (activeLabel.spec().sensorIndex() == sensorIndex) {
                                return activeLabel;
                        }
                }
                return null;
        }

        static List<SensorLabelPlacement> layoutSensorLabelsForSide(Direction direction,
                                                                    int centerX,
                                                                    int centerY,
                                                                    int xRadius,
                                                                    int yRadius,
                                                                    int lineHeight,
                                                                    int sideMarginX,
                                                                    int sideMarginY,
                                                                    int labelGap,
                                                                    List<SensorLabelSpec> labels) {
                if (labels.isEmpty()) {
                        return List.of();
                }
                List<SensorLabelSpec> orderedLabels = new ArrayList<>(labels);
                orderedLabels.sort((left, right) -> {
                        int axisCompare = Integer.compare(left.orthogonalAxis(), right.orthogonalAxis());
                        if (axisCompare != 0) {
                                return axisCompare;
                        }
                        return Integer.compare(left.sensorIndex(), right.sensorIndex());
                });

                List<SensorLabelPlacement> placements = new ArrayList<>(orderedLabels.size());
                int stackStep = lineHeight + labelGap;
                switch (direction) {
                        case LEFT, RIGHT -> {
                                int widestLabel = 0;
                                for (SensorLabelSpec label : orderedLabels) {
                                        widestLabel = Math.max(widestLabel, label.labelWidth());
                                }
                                int drawX = direction == Direction.LEFT
                                                ? centerX - xRadius - sideMarginX - widestLabel
                                                : centerX + xRadius + sideMarginX;
                                int startY = centerY + (stackStep * (orderedLabels.size() - 1)) / 2;
                                for (int i = 0; i < orderedLabels.size(); i++) {
                                        placements.add(new SensorLabelPlacement(
                                                        orderedLabels.get(i),
                                                        drawX,
                                                        startY - i * stackStep));
                                }
                        }
                        case UP, DOWN -> {
                                int totalWidth = labelGap * (orderedLabels.size() - 1);
                                for (SensorLabelSpec label : orderedLabels) {
                                        totalWidth += label.labelWidth();
                                }
                                int drawX = centerX - totalWidth / 2;
                                int drawY = direction == Direction.UP
                                                ? centerY + yRadius + sideMarginY + lineHeight
                                                : centerY - yRadius - sideMarginY;
                                for (SensorLabelSpec label : orderedLabels) {
                                        placements.add(new SensorLabelPlacement(label, drawX, drawY));
                                        drawX += label.labelWidth() + labelGap;
                                }
                        }
                }
                return placements;
        }

        private void renderPlayerStatusPanel(AbstractPlayableSprite sprite, int ringCount) {
                playerStatusLines.clear();
                List<String> lines = playerStatusLines;
                int angleByte = sprite.getAngle() & 0xFF;
                float angleDeg = ((256 - angleByte) * 360f / 256f) % 360f;

                lines.add("== PLAYER ==");
                StringBuilder pb = panelLineBuilder;

                pb.setLength(0);
                pb.append("Pos: ").append((int) sprite.getX()).append('.');
                DebugRenderContext.appendHex2(pb, sprite.getXSubpixel() & 0xFF);
                pb.append("  ").append((int) sprite.getY()).append('.');
                DebugRenderContext.appendHex2(pb, sprite.getYSubpixel() & 0xFF);
                lines.add(pb.toString());

                pb.setLength(0);
                pb.append("Spd: X ").append(sprite.getXSpeed()).append(" (");
                DebugRenderContext.appendFixed2(pb, sprite.getXSpeed() / 256f);
                pb.append(')');
                lines.add(pb.toString());

                pb.setLength(0);
                pb.append("Spd: Y ").append(sprite.getYSpeed()).append(" (");
                DebugRenderContext.appendFixed2(pb, sprite.getYSpeed() / 256f);
                pb.append(')');
                lines.add(pb.toString());

                pb.setLength(0);
                pb.append("GSpd: ").append(sprite.getGSpeed()).append(" (");
                DebugRenderContext.appendFixed2(pb, sprite.getGSpeed() / 256f);
                pb.append(')');
                lines.add(pb.toString());

                pb.setLength(0);
                pb.append("Angle: ");
                DebugRenderContext.appendHex2(pb, angleByte);
                pb.append(" (");
                DebugRenderContext.appendFixed1(pb, angleDeg);
                pb.append("°)");
                lines.add(pb.toString());

                lines.add("Mode: " + sprite.getGroundMode());
                lines.add("Dir: " + sprite.getDirection());
                lines.add("State: " + formatStateFlags(sprite));

                pb.setLength(0);
                if (isUnifiedCollision(sprite)) {
                        // S1 (UNIFIED): A/B from loop-low-plane; tunnel mode 0/1.
                        // Dual-path Prio/Solidity bits are stuck at defaults in S1, so we
                        // surface S1's actual per-frame routing state instead.
                        char layerChar = sprite.isLoopLowPlane() ? 'B' : 'A';
                        char tunnelChar = sprite.isTunnelMode() ? '1' : '0';
                        pb.append("Layer: ").append(layerChar)
                          .append("  Tunnel: ").append(tunnelChar);
                        lines.add(pb.toString());
                } else {
                        pb.append("Layer: ").append(formatLayer(sprite.getLayer()))
                          .append("  Prio: ").append(formatPriority(sprite.isHighPriority()));
                        lines.add(pb.toString());

                        pb.setLength(0);
                        pb.append("Solidity: top ");
                        DebugRenderContext.appendHex2(pb, sprite.getTopSolidBit() & 0xFF);
                        pb.append(" lrb ");
                        DebugRenderContext.appendHex2(pb, sprite.getLrbSolidBit() & 0xFF);
                        lines.add(pb.toString());
                }

                pb.setLength(0);
                pb.append("Radii: x ").append(sprite.getXRadius())
                  .append(" y ").append(sprite.getYRadius());
                lines.add(pb.toString());

                pb.setLength(0);
                pb.append("Anim: id ").append(sprite.getAnimationId())
                  .append(" frame ").append(sprite.getAnimationFrameIndex())
                  .append('/').append(sprite.getAnimationFrameCount())
                  .append(" tick ").append(sprite.getAnimationTick());
                lines.add(pb.toString());

                lines.add("MapFrame: " + sprite.getMappingFrame());
                lines.add("Rings: " + ringCount);
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
                        pb.setLength(0);
                        pb.append(prefix).append("d:").append(result.distance()).append(" a:");
                        DebugRenderContext.appendHex2(pb, result.angle() & 0xFF);
                        lines.add(pb.toString());
                }

                int startX = uiX(6);
                int startY = uiY(baseHeight - 18);
                int lineHeight = glyphBatch.getLineHeight(PANEL_FONT);
                int y = startY;
                for (String line : lines) {
                        glyphBatch.drawTextOutlined(line, startX, y, DebugColor.WHITE, PANEL_FONT);
                        y -= lineHeight;
                }

                // Live dynamic-object slot usage vs the fixed ROM-sized pool.
                // White normally; orange when nearing the limit (>= 90%); red when
                // full (allocateSlot() failing -> new spawns are dropped).
                ObjectManager objManager =
                        getLevelManager() != null ? getLevelManager().getObjectManager() : null;
                if (objManager != null) {
                        int live = objManager.getActiveObjectSlotCount();
                        int peak = objManager.getPeakObjectSlotCount();
                        int cap = objManager.getObjectSlotCapacity();
                        DebugColor objColor = DebugColor.WHITE;
                        if (cap > 0) {
                                if (live >= cap) {
                                        objColor = DebugColor.RED;
                                } else if (live >= (cap * 9) / 10) {
                                        objColor = DebugColor.ORANGE;
                                }
                        }
                        glyphBatch.drawTextOutlined(
                                        "Objs: " + live + "/" + cap + " peak " + peak,
                                        startX, y, objColor, PANEL_FONT);
                        y -= lineHeight;
                }
        }

        private void renderTouchResponsePanel(AbstractPlayableSprite sprite) {
                ObjectManager manager = getLevelManager().getObjectManager();
                if (manager == null || !glyphBatch.isBatchActive()) {
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

                touchResponseLines.clear();
                List<String> lines = touchResponseLines;
                StringBuilder tb = panelLineBuilder;
                lines.add("== TOUCH RESP ==");
                String crouch = state.isCrouching() ? "C" : "-";

                tb.setLength(0);
                tb.append("Player: x ").append(state.getPlayerX())
                  .append(" y ").append(state.getPlayerY())
                  .append(" h ").append(state.getPlayerHeight())
                  .append(" yR ").append(state.getPlayerYRadius())
                  .append(' ').append(crouch);
                lines.add(tb.toString());

                tb.setLength(0);
                tb.append("Objects: ").append(hits.size())
                  .append(" Hits: ").append(hitCount);
                lines.add(tb.toString());

                GameModule module = getLevelManager().getGameModule();
                if (module == null) {
                        module = GameServices.module();
                }
                ObjectRegistry registry = module.createObjectRegistry();
                int maxLines = 12;
                int shown = 0;
                for (TouchResponseDebugHit hit : hits) {
                        if (shown >= maxLines) {
                                break;
                        }
                        ObjectSpawn spawn = hit.spawn();
                        String name = resolveObjectNameForDebug(registry, spawn.objectId(),
                                getLevelManager().getRomZoneId());
                        if (name.length() > 12) {
                                name = name.substring(0, 12);
                        }
                        String status = hit.overlapping() ? "HIT" : "--";
                        String category = formatTouchCategory(hit.category());
                        tb.setLength(0);
                        DebugRenderContext.appendHex2(tb, spawn.objectId());
                        tb.append(':');
                        DebugRenderContext.appendHex2(tb, spawn.subtype());
                        tb.append(' ').append(status).append(' ').append(category).append(' ');
                        DebugRenderContext.appendHex2(tb, hit.sizeIndex());
                        tb.append(' ');
                        if (hit.width() < 10) tb.append(' ');
                        tb.append(hit.width()).append(',');
                        if (hit.height() < 10) tb.append(' ');
                        tb.append(hit.height()).append(' ').append(name);
                        lines.add(tb.toString());
                        shown++;
                }

                int startX = uiX(baseWidth - 240);
                int startY = uiY(baseHeight - 140);
                int lineHeight = glyphBatch.getLineHeight(PANEL_FONT);
                int y = startY;
                for (String line : lines) {
                        glyphBatch.drawTextOutlined(line, startX, y, COLOR_TOUCH_PANEL, PANEL_FONT);
                        y -= lineHeight;
                }
        }

        private void renderObjectArtViewerPanel() {
                if (!glyphBatch.isBatchActive()) {
                        return;
                }
                DebugObjectArtViewer viewer = overlayManager.getObjectArtViewer();
                artViewerLines.clear();
                List<String> lines = artViewerLines;
                lines.add("== ART VIEWER ==");
                lines.add("Target: " + viewer.getTargetLabel());

                // Special display for CNZ slot faces
                if (viewer.isCnzSlotsMode()) {
                        lines.add("Shows all 6 slot faces");
                        lines.add("Row 1: 0-2 (Sonic,Tails,Eggman)");
                        lines.add("Row 2: 3-5 (Jackpot,Ring,Bar)");
                        lines.add("");
                        lines.add("Expected face order:");
                        for (int i = 0; i < 6; i++) {
                                String name = CNZSlotMachineRenderer.getFaceName(i);
                                String reward = CNZSlotMachineRenderer.getFaceReward(i);
                                lines.add(String.format("  %d: %s (%s)", i, name, reward));
                        }
                        lines.add("");
                        lines.add("Keys: PgUp/PgDn to switch target");
                } else {
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
                }

                int startX = uiX(baseWidth - 160);
                int startY = uiY(baseHeight - 120);
                int lineHeight = glyphBatch.getLineHeight(PANEL_FONT);
                int y = startY;
                for (String line : lines) {
                        glyphBatch.drawTextOutlined(line, startX, y, COLOR_ART_VIEWER, PANEL_FONT);
                        y -= lineHeight;
                }
        }

        private void renderPlaybackPanel() {
                if (!glyphBatch.isBatchActive()) {
                        return;
                }
                List<String> lines = playbackDebugManager.buildOverlayLines();
                if (lines.isEmpty()) {
                        return;
                }
                int startX = uiX(baseWidth - 220);
                int startY = uiY(baseHeight - 18);
                int lineHeight = glyphBatch.getLineHeight(PANEL_FONT);
                int y = startY;
                for (String line : lines) {
                        glyphBatch.drawTextOutlined(line, startX, y, COLOR_ART_VIEWER, PANEL_FONT);
                        y -= lineHeight;
                }
        }

        private void renderPachinkoTrapPanel(AbstractPlayableSprite playable) {
                if (!glyphBatch.isBatchActive()) {
                        return;
                }
                LevelManager levelManager = getLevelManager();
                if (levelManager.getRomZoneId() != Sonic3kZoneIds.ZONE_GLOWING_SPHERE) {
                        return;
                }
                ObjectManager objectManager = levelManager.getObjectManager();
                if (objectManager == null) {
                        return;
                }

                PachinkoEnergyTrapObjectInstance trap = null;
                int columnCount = 0;
                int beamCount = 0;
                int objectManagerFrame = objectManager.getFrameCounter();
                int trapSlotCollisionCount = 0;
                String trapSlotPeer = null;
                for (ObjectInstance instance : objectManager.getActiveObjects()) {
                        if (instance instanceof PachinkoEnergyTrapObjectInstance energyTrap) {
                                trap = energyTrap;
                                continue;
                        }
                        String simpleName = instance.getClass().getSimpleName();
                        if ("EnergyTrapColumnChild".equals(simpleName)) {
                                columnCount++;
                        } else if ("EnergyTrapBeamChild".equals(simpleName)) {
                                beamCount++;
                        }
                }

                ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
                boolean trapRendererLoaded = renderManager != null
                                && renderManager.getRenderer(Sonic3kObjectArtKeys.PACHINKO_ENERGY_TRAP) != null;
                boolean beamRendererLoaded = renderManager != null
                                && renderManager.getRenderer(Sonic3kObjectArtKeys.PACHINKO_INVISIBLE_UNKNOWN) != null;

                pachinkoTrapLines.clear();
                List<String> lines = pachinkoTrapLines;
                StringBuilder pb = panelLineBuilder;
                Camera camera = GameServices.camera();
                lines.add("== PACHINKO TRAP ==");

                pb.setLength(0);
                pb.append("LM frame: ").append(levelManager.getFrameCounter());
                lines.add(pb.toString());

                pb.setLength(0);
                pb.append("OM frame: ").append(objectManagerFrame);
                lines.add(pb.toString());

                pb.setLength(0);
                pb.append("Cam: ").append(camera.getX()).append(' ').append(camera.getY());
                lines.add(pb.toString());

                if (playable != null) {
                        pb.setLength(0);
                        pb.append("Player: ").append(playable.getCentreX())
                                .append(' ').append(playable.getCentreY());
                        lines.add(pb.toString());
                }

                if (trap == null) {
                        lines.add("Trap: MISSING");
                } else {
                        for (ObjectInstance instance : objectManager.getActiveObjects()) {
                                if (!(instance instanceof com.openggf.level.objects.AbstractObjectInstance aoi)) {
                                        continue;
                                }
                                if (aoi.getSlotIndex() != trap.getSlotIndex()) {
                                        continue;
                                }
                                trapSlotCollisionCount++;
                                if (instance != trap && trapSlotPeer == null) {
                                        trapSlotPeer = instance.getClass().getSimpleName();
                                }
                        }

                        int trapScreenX = trap.getX() - camera.getX();
                        int trapScreenY = trap.getY() - camera.getY();

                        pb.setLength(0);
                        pb.append("Trap: ").append(trap.getX()).append(' ').append(trap.getY())
                                .append("  scr ").append(trapScreenX).append(' ').append(trapScreenY);
                        lines.add(pb.toString());

                        pb.setLength(0);
                        pb.append("State: init ").append(trap.isInitialized() ? 'Y' : 'N')
                                .append(" rise ").append(trap.getRiseDelayFrames())
                                .append(" arm ").append(trap.isExitArmed() ? 'Y' : 'N')
                                .append(" cap ").append(trap.hasCapturedPlayer() ? 'Y' : 'N')
                                .append(" exit ").append(trap.isExitRequested() ? 'Y' : 'N');
                        lines.add(pb.toString());

                        pb.setLength(0);
                        pb.append("Slot: ").append(trap.getSlotIndex())
                                .append(" dead ").append(trap.isDestroyed() ? 'Y' : 'N');
                        lines.add(pb.toString());

                        pb.setLength(0);
                        pb.append("Upd: cnt ").append(trap.getUpdateCount())
                                .append(" last ").append(trap.getLastUpdateFrameCounter())
                                .append(" rnd ").append(trap.getRenderCount())
                                .append(" beam+ ").append(trap.getBeamSpawnCount());
                        lines.add(pb.toString());

                        pb.setLength(0);
                        pb.append("SlotDup: ").append(trapSlotCollisionCount);
                        if (trapSlotPeer != null) {
                                pb.append(" peer ").append(trapSlotPeer);
                        }
                        lines.add(pb.toString());
                }

                pb.setLength(0);
                pb.append("Objs: col ").append(columnCount).append(" beam ").append(beamCount);
                lines.add(pb.toString());

                pb.setLength(0);
                pb.append("Art: trap ").append(trapRendererLoaded ? 'Y' : 'N')
                        .append(" beam ").append(beamRendererLoaded ? 'Y' : 'N');
                lines.add(pb.toString());

                int startX = uiX(baseWidth - 250);
                int startY = uiY(baseHeight - 18);
                int lineHeight = glyphBatch.getLineHeight(PANEL_FONT);
                int y = startY;
                for (String line : lines) {
                        glyphBatch.drawTextOutlined(line, startX, y, COLOR_PACHINKO_TRAP, PANEL_FONT);
                        y -= lineHeight;
                }
        }

        private void renderPerformancePanel() {
                if (performancePanelRenderer == null) {
                        performancePanelRenderer = new PerformancePanelRenderer(
                                baseWidth, baseHeight, performanceTextRenderer, GameServices.graphics(), profiler);
                }
                performancePanelRenderer.updateViewport(viewportWidth, viewportHeight);

                ProfileSnapshot snapshot = profiler.getSnapshot();
                performancePanelRenderer.render(snapshot);
        }

        private String formatStateFlags(AbstractPlayableSprite sprite) {
                stateFlagsBuilder.setLength(0);
                if (sprite.getAir()) {
                        stateFlagsBuilder.append("Air ");
                } else {
                        stateFlagsBuilder.append("Ground ");
                }
                if (sprite.getRolling()) {
                        stateFlagsBuilder.append("Roll ");
                }
                if (sprite.getSpindash()) {
                        stateFlagsBuilder.append("Spindash ");
                }
                if (sprite.getCrouching()) {
                        stateFlagsBuilder.append("Crouch ");
                }
                if (sprite.getPushing()) {
                        stateFlagsBuilder.append("Push ");
                }
                if (stateFlagsBuilder.length() == 0) {
                        return "None";
                }
                // Trim trailing space
                if (stateFlagsBuilder.charAt(stateFlagsBuilder.length() - 1) == ' ') {
                        stateFlagsBuilder.setLength(stateFlagsBuilder.length() - 1);
                }
                return stateFlagsBuilder.toString();
        }

        private String formatPlaneSwitcherSide(int subtype, int side) {
                int path = ObjectManager.decodePlaneSwitcherPath(subtype, side);
                boolean highPriority = ObjectManager.decodePlaneSwitcherPriority(subtype, side);
                return formatLayer((byte) path) + " " + formatPriority(highPriority);
        }

        private char formatLayer(byte layer) {
                return ObjectManager.formatPlaneSwitcherLayer(layer);
        }

        private boolean isUnifiedCollision(AbstractPlayableSprite sprite) {
                return sprite.getGameRules() != null
                        && sprite.getGameRules().collision() != null
                        && sprite.getGameRules().collision().collisionModel() == CollisionModel.UNIFIED;
        }

        private char formatPriority(boolean highPriority) {
                return ObjectManager.formatPlaneSwitcherPriority(highPriority);
        }

        private SpriteManager getSpriteManager() {
                return GameServices.sprites();
        }

        private LevelManager getLevelManager() {
                return GameServices.level();
        }

        private String getMainCharacterCode() {
                return ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
        }

        private String formatTouchCategory(TouchCategory category) {
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

        static String resolveObjectNameForDebug(ObjectRegistry registry, int objectId, int zoneId) {
                if (registry instanceof Sonic3kObjectRegistry sonic3kRegistry && zoneId >= 0) {
                        return sonic3kRegistry.getPrimaryName(objectId, S3kZoneSet.forZone(zoneId));
                }
                return registry.getPrimaryName(objectId);
        }
}
