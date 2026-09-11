package com.openggf.game.sonic3k.features;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectContext;
import com.openggf.game.render.SpecialRenderEffectStage;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.AizBattleshipInstance;
import com.openggf.game.sonic3k.objects.AizShipBombInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternRenderCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.logging.Logger;

/**
 * AIZ2 battleship split-render overlay.
 *
 * <p>The main bombership body is drawn as a clipped strip over the top 64
 * scanlines. The source tiles come from the hidden AIZ2 boss strip in the
 * foreground layout while propellers remain separate sprite art rendered on
 * top of the strip.
 */
public final class AizBattleshipRenderFeature implements SpecialRenderEffect {
    private static final Logger LOG =
            Logger.getLogger(AizBattleshipRenderFeature.class.getName());

    private static final int STRIP_HEIGHT_PX = 0x40;
    private static final int TILE_SIZE = 8;
    private static final int EMPTY_PATTERN = 0;
    private static final int PROPELLER_FRONT_X = 0x3FBC;
    private static final int PROPELLER_BACK_X = 0x3DCC;
    private static final int PROPELLER_Y = 0x0A71;
    private static final int PROPELLER_FRAME_DELAY = 2;

    private final SonicConfigurationService configService = GameServices.configuration();
    private final PatternDesc reusableDesc = new PatternDesc();

    private int pendingStripHeight;
    private boolean loggedFirstRender;

    private final GLCommand prepareOverlayCommand = new GLCommand(
            GLCommand.CommandType.CUSTOM,
            (cx, cy, cw, ch) -> {
                GraphicsManager graphicsManager = GameServices.graphics();
                graphicsManager.setUseWaterShader(false);
                PatternRenderCommand.resetFrameState();
                int viewportX = graphicsManager.getViewportX();
                int viewportY = graphicsManager.getViewportY();
                int viewportWidth = graphicsManager.getViewportWidth();
                int viewportHeight = graphicsManager.getViewportHeight();
                int stripHeight = Math.max(0, Math.min(pendingStripHeight, ch));
                if (stripHeight > 0) {
                    float scaleY = viewportHeight / (float) ch;
                    int scissorHeight = Math.max(1, (int) Math.ceil(stripHeight * scaleY));
                    int scissorY = viewportY + viewportHeight - scissorHeight;
                    graphicsManager.enableScissor(viewportX, scissorY, viewportWidth, scissorHeight);
                }
            });

    private final GLCommand cleanupOverlayCommand = new GLCommand(
            GLCommand.CommandType.CUSTOM,
            (cx, cy, cw, ch) -> GameServices.graphics().disableScissor());

    public void reset() {
        loggedFirstRender = false;
        pendingStripHeight = 0;
    }

    @Override
    public SpecialRenderEffectStage stage() {
        return SpecialRenderEffectStage.AFTER_SPRITES;
    }

    @Override
    public void render(SpecialRenderEffectContext context) {
        renderAfterBackground(context.camera(), context.frameCounter());
    }

    public void renderAfterBackground(Camera camera, int frameCounter) {
        if (camera == null) {
            return;
        }

        LevelManager levelManager = GameServices.level();
        if (levelManager == null
                || levelManager.getCurrentZone() != Sonic3kZoneIds.ZONE_AIZ
                || levelManager.getCurrentAct() != 1) {
            return;
        }

        AizBattleshipInstance battleship = findActiveBattleship(levelManager);
        if (battleship == null) {
            return;
        }

        int screenWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
        int screenHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);
        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }

        pendingStripHeight = Math.min(STRIP_HEIGHT_PX, screenHeight);
        GraphicsManager graphicsManager = GameServices.graphics();
        // Render attached bombs BEFORE the scissor so the full bomb sprite is
        // visible as it emerges from the port.  The ship strip drawn afterwards
        // (within scissor) covers the portion behind the hull via painter's algorithm.
        renderAttachedBombs(levelManager);
        graphicsManager.registerCommand(prepareOverlayCommand);
        renderShipStrip(camera, battleship, screenWidth, pendingStripHeight);
        renderPropellers(camera, battleship, frameCounter, levelManager);
        graphicsManager.registerCommand(cleanupOverlayCommand);

        if (!loggedFirstRender) {
            loggedFirstRender = true;
            LOG.info("AIZ2 battleship: first split render at shipX=0x"
                    + Integer.toHexString(battleship.getSecondaryCameraX())
                    + " shipY=0x" + Integer.toHexString(battleship.getSecondaryCameraY()));
        }
    }

    private void renderAttachedBombs(LevelManager levelManager) {
        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        if (renderManager == null || levelManager.getObjectManager() == null) {
            return;
        }

        for (var object : levelManager.getObjectManager().getActiveObjects()) {
            if (object instanceof AizShipBombInstance bomb) {
                bomb.renderBehindBattleship(renderManager);
            }
        }
    }

    private void renderShipStrip(Camera camera,
                                 AizBattleshipInstance battleship,
                                 int screenWidth,
                                 int stripHeight) {
        LevelManager levelManager = GameServices.level();
        GraphicsManager graphicsManager = GameServices.graphics();
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int sourceX = battleship.getSecondaryCameraX();
        int sourceY = battleship.getSecondaryCameraY();

        int subTileX = Math.floorMod(sourceX, TILE_SIZE);
        int subTileY = Math.floorMod(sourceY, TILE_SIZE);
        int sampleStartX = sourceX - subTileX;
        int sampleStartY = sourceY - subTileY;

        for (int drawY = -subTileY, sampleY = sampleStartY;
             drawY < stripHeight;
             drawY += TILE_SIZE, sampleY += TILE_SIZE) {
            for (int drawX = -subTileX, sampleX = sampleStartX;
                 drawX < screenWidth;
                 drawX += TILE_SIZE, sampleX += TILE_SIZE) {
                int descriptor = levelManager.getForegroundTileDescriptorAtWorld(sampleX, sampleY);
                int patternIndex = descriptor & 0x7FF;
                if (patternIndex == EMPTY_PATTERN) {
                    continue;
                }

                reusableDesc.set(descriptor);
                graphicsManager.renderPatternWithId(patternIndex, reusableDesc,
                        cameraX + drawX, cameraY + drawY);
            }
        }
    }

    private void renderPropellers(Camera camera,
                                  AizBattleshipInstance battleship,
                                  int frameCounter,
                                  LevelManager levelManager) {
        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ2_SHIP_PROPELLER);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        int frameIndex = (frameCounter / PROPELLER_FRAME_DELAY) & 0x3;
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int shipX = battleship.getSecondaryCameraX();
        int shipY = battleship.getSecondaryCameraY();

        drawPropeller(renderer, frameIndex, cameraX, cameraY, shipX, shipY, PROPELLER_FRONT_X);
        drawPropeller(renderer, frameIndex, cameraX, cameraY, shipX, shipY, PROPELLER_BACK_X);
    }

    private void drawPropeller(PatternSpriteRenderer renderer,
                               int frameIndex,
                               int cameraX,
                               int cameraY,
                               int shipX,
                               int shipY,
                               int sourceX) {
        int worldX = cameraX + (sourceX - shipX);
        int worldY = cameraY + (PROPELLER_Y - shipY);
        renderer.drawFrameIndex(frameIndex, worldX, worldY, false, false);
    }

    private AizBattleshipInstance findActiveBattleship(LevelManager levelManager) {
        if (levelManager.getObjectManager() == null) {
            return null;
        }
        for (var object : levelManager.getObjectManager().getActiveObjects()) {
            if (object instanceof AizBattleshipInstance battleship && !battleship.isDestroyed()) {
                return battleship;
            }
        }
        return null;
    }
}
