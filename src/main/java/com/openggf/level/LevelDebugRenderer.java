package com.openggf.level;

import com.openggf.Engine;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugOption;
import com.openggf.debug.DebugOverlayPalette;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.GameModule;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GLCommandGroup;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.ShaderProgram;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.rings.RingManager;
import com.openggf.level.rings.RingSpawn;
import com.openggf.physics.Direction;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.SensorConfiguration;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;

/**
 * Handles all debug overlay rendering previously inlined in LevelManager.
 * This is a pure extraction — no behavioral changes from the original code.
 */
public class LevelDebugRenderer {
    private static final Logger LOGGER = Logger.getLogger(LevelDebugRenderer.class.getName());

    static final float SWITCHER_DEBUG_R = 1.0f;
    static final float SWITCHER_DEBUG_G = 0.55f;
    static final float SWITCHER_DEBUG_B = 0.1f;
    static final float SWITCHER_DEBUG_ALPHA = 0.35f;

    private final LevelDebugContext ctx;

    // Pre-allocated lists for debug overlay rendering (avoids per-frame allocations)
    private final List<GLCommand> debugObjectCommands = new ArrayList<>(256);
    private final List<GLCommand> debugSwitcherLineCommands = new ArrayList<>(128);
    private final List<GLCommand> debugSwitcherAreaCommands = new ArrayList<>(128);
    private final List<GLCommand> debugRingCommands = new ArrayList<>(256);
    private final List<GLCommand> debugBoxCommands = new ArrayList<>(512);
    private final List<GLCommand> debugCenterCommands = new ArrayList<>(256);
    private final List<GLCommand> collisionCommands = new ArrayList<>(256);
    private final List<GLCommand> priorityDebugCommands = new ArrayList<>(256);
    private final List<GLCommand> sensorCommands = new ArrayList<>(128);
    private final List<GLCommand> cameraBoundsCommands = new ArrayList<>(64);

    // Reusable context for per-object debug rendering (avoids per-frame allocation)
    private final DebugRenderContext reusableDebugCtx = new DebugRenderContext();

    // Reusable PatternDesc to avoid per-iteration allocations in tight loops
    private final PatternDesc reusablePatternDesc = new PatternDesc();

    /**
     * Functional interface for block access — avoids coupling to LevelManager.
     */
    @FunctionalInterface
    public interface BlockAccessor {
        Block getBlockAtPosition(byte layer, int x, int y);
    }

    public LevelDebugRenderer(LevelDebugContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns the collision command list. LevelManager needs access to this
     * because it clears it per frame and passes it to renderBackgroundShader.
     */
    public List<GLCommand> getCollisionCommands() {
        return collisionCommands;
    }

    /**
     * Returns the priority debug command list.
     */
    public List<GLCommand> getPriorityDebugCommands() {
        return priorityDebugCommands;
    }

    /**
     * Checks if a point is within the visible camera frustum with optional padding.
     * Used to cull debug overlay commands for off-screen objects.
     *
     * @param x       world X coordinate
     * @param y       world Y coordinate
     * @param padding extra pixels around screen edges to include
     * @return true if the point is visible (or near-visible with padding)
     */
    boolean isInCameraFrustum(int x, int y, int padding) {
        Camera camera = GameServices.camera();
        int camX = camera.getX();
        int camY = camera.getY();
        return x >= camX - padding && x <= camX + ctx.screenWidth() + padding
                && y >= camY - padding && y <= camY + ctx.screenHeight() + padding;
    }

    /**
     * Debug Functionality to print each pattern to the screen.
     */
    public void drawAllPatterns() {
        Level level = ctx.level();
        GraphicsManager graphicsManager = ctx.graphicsManager();

        if (level == null) {
            LOGGER.warning("No level loaded to draw.");
            return;
        }

        Camera camera = GameServices.camera();
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int cameraWidth = camera.getWidth();
        int cameraHeight = camera.getHeight();

        // Calculate drawing bounds, adjusted to include partially visible tiles
        int drawX = cameraX;
        int drawY = cameraY;
        int levelWidth = level.getMap().getWidth() * ctx.blockPixelSize();
        int levelHeight = level.getMap().getHeight() * ctx.blockPixelSize();

        int xLeftBound = Math.max(drawX, 0);
        int xRightBound = Math.min(cameraX + cameraWidth, levelWidth);
        int yTopBound = Math.max(drawY, 0);
        int yBottomBound = Math.min(cameraY + cameraHeight + LevelConstants.CHUNK_HEIGHT, levelHeight);

        List<GLCommand> commands = new ArrayList<>(256);

        // Iterate over the visible area of the level
        int count = 0;
        int maxCount = level.getPatternCount();

        if (Engine.getDebugOption().ordinal() > LevelConstants.MAX_PALETTES) {
            Engine.setDebugOption(DebugOption.A);
        }

        for (int y = yTopBound; y <= yBottomBound; y += Pattern.PATTERN_HEIGHT) {
            for (int x = xLeftBound; x <= xRightBound; x += Pattern.PATTERN_WIDTH) {
                if (count < maxCount) {
                    reusablePatternDesc.setPaletteIndex(Engine.getDebugOption().ordinal());
                    reusablePatternDesc.setPatternIndex(count);
                    graphicsManager.renderPattern(reusablePatternDesc, x, y);
                    count++;
                }
            }
        }

        // Register all collected drawing commands with the graphics manager
        graphicsManager.registerCommand(new GLCommandGroup(GL_POINTS, commands));

    }

    /**
     * Generates collision debug overlay commands for visible chunks.
     * This method iterates over visible chunks in the foreground layer (Layer 0)
     * and generates collision debug rendering commands independently of tile rendering.
     *
     * @param commands      the list of GLCommands to add collision rectangles to
     * @param camera        the camera for visibility culling
     * @param blockAccessor accessor for retrieving blocks at positions
     */
    void generateCollisionDebugCommands(List<GLCommand> commands, Camera camera, BlockAccessor blockAccessor) {
        Level level = ctx.level();
        int blockPixelSize = ctx.blockPixelSize();

        if (level == null || level.getMap() == null) {
            return;
        }
        if (!ctx.overlayManager().isEnabled(DebugOverlayToggle.COLLISION_VIEW)) {
            return;
        }

        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int cameraWidth = camera.getWidth();
        int cameraHeight = camera.getHeight();

        int levelWidth = level.getMap().getWidth() * blockPixelSize;
        int levelHeight = level.getMap().getHeight() * blockPixelSize;

        // Calculate visible chunk range (same culling logic as foreground rendering)
        int xStart = cameraX - (cameraX % LevelConstants.CHUNK_WIDTH);
        int xEnd = cameraX + cameraWidth;
        int yStart = cameraY - (cameraY % LevelConstants.CHUNK_HEIGHT);
        int yEnd = cameraY + cameraHeight + LevelConstants.CHUNK_HEIGHT;

        for (int y = yStart; y <= yEnd; y += LevelConstants.CHUNK_HEIGHT) {
            // Foreground clamps vertically (doesn't wrap)
            if (y < 0 || y >= levelHeight) {
                continue;
            }

            for (int x = xStart; x <= xEnd; x += LevelConstants.CHUNK_WIDTH) {
                // Handle X wrapping
                int wrappedX = ((x % levelWidth) + levelWidth) % levelWidth;

                Block block = blockAccessor.getBlockAtPosition((byte) 0, wrappedX, y);
                if (block == null) {
                    continue;
                }

                int xBlockBit = (wrappedX % blockPixelSize) / LevelConstants.CHUNK_WIDTH;
                int yBlockBit = (y % blockPixelSize) / LevelConstants.CHUNK_HEIGHT;
                ChunkDesc chunkDesc = block.getChunkDesc(xBlockBit, yBlockBit);

                int chunkIndex = chunkDesc.getChunkIndex();
                if (chunkIndex < 0 || chunkIndex >= level.getChunkCount()) {
                    continue;
                }

                Chunk chunk = level.getChunk(chunkIndex);
                if (chunk == null) {
                    continue;
                }

                // Calculate screen coordinates then convert to render coordinates
                int screenX = x - cameraX;
                int screenY = y - cameraY;
                int renderX = screenX + cameraX;
                int renderY = screenY + cameraY;

                // Generate collision debug for both primary and secondary collision
                processCollisionMode(commands, chunkDesc, chunk, true, renderX, renderY);
                processCollisionMode(commands, chunkDesc, chunk, false, renderX, renderY);
            }
        }
    }

    /**
     * Processes and renders collision modes for a chunk.
     *
     * @param commands  the list of GLCommands to add to
     * @param chunkDesc the description of the chunk
     * @param chunk     the chunk data
     * @param isPrimary whether to process the primary collision mode
     * @param x         the x-coordinate
     * @param y         the y-coordinate
     */
    private void processCollisionMode(
            List<GLCommand> commands,
            ChunkDesc chunkDesc,
            Chunk chunk,
            boolean isPrimary,
            int x,
            int y) {
        if (!ctx.overlayManager().isEnabled(DebugOverlayToggle.COLLISION_VIEW)) {
            return;
        }

        boolean hasSolidity = isPrimary
                ? chunkDesc.hasPrimarySolidity()
                : chunkDesc.hasSecondarySolidity();
        if (!hasSolidity) {
            return;
        }

        int solidTileIndex = isPrimary
                ? chunk.getSolidTileIndex()
                : chunk.getSolidTileAltIndex();
        SolidTile solidTile = ctx.level().getSolidTile(solidTileIndex);
        if (solidTile == null) {
            LOGGER.warning("SolidTile at index " + solidTileIndex + " is null.");
            return;
        }

        // Determine color based on collision mode
        float r, g, b;
        if (isPrimary) {
            r = 1.0f; // White color for primary collision
            g = 1.0f;
            b = 1.0f;
        } else {
            r = 0.5f; // Gray color for secondary collision
            g = 0.5f;
            b = 0.5f;
        }

        boolean hFlip = chunkDesc.getHFlip();
        boolean yFlip = chunkDesc.getVFlip(); // Using VFlip as per your current code

        // Disable shaders for drawing solid colors (RECTI uses its own debug shader)
        ShaderProgram shaderProgram = ctx.graphicsManager().getShaderProgram();
        int shaderProgramId = 0;
        if (shaderProgram != null) {
            shaderProgramId = shaderProgram.getProgramId();
        }
        commands.add(new GLCommand(GLCommand.CommandType.USE_PROGRAM, 0));

        // Iterate over each pixel column in the tile
        for (int i = 0; i < LevelConstants.CHUNK_WIDTH; i++) {
            int tileIndex = hFlip ? (LevelConstants.CHUNK_HEIGHT - 1 - i) : i;
            int height = solidTile.getHeightAt((byte) tileIndex);

            if (height > 0) {
                int drawStartX = x + i;
                int drawEndX = drawStartX + 1;

                int drawStartY;
                int drawEndY;

                // Adjust drawing coordinates based on vertical flip
                // GLCommand constructor handles Y-flip (SCREEN_HEIGHT_PIXELS - y)
                // and execute() applies camera offset (y + cameraY)
                // We add 16 to align with the pattern renderer's coordinate system
                if (yFlip) {
                    // When yFlip is true, collision extends upward from bottom of chunk
                    drawStartY = y - LevelConstants.CHUNK_HEIGHT + 16;
                    drawEndY = drawStartY + height;
                } else {
                    // Normal rendering: collision extends downward from top of chunk
                    drawStartY = y + 16;
                    drawEndY = y - height + 16;
                }

                commands.add(new GLCommand(
                        GLCommand.CommandType.RECTI,
                        GL_2D,
                        r,
                        g,
                        b,
                        drawStartX,
                        drawEndY,
                        drawEndX,
                        drawStartY));
            }
        }
        // Re-enable shader for subsequent rendering
        if (shaderProgramId != 0) {
            commands.add(new GLCommand(GLCommand.CommandType.USE_PROGRAM, shaderProgramId));
        }
    }

    /**
     * Generates tile priority debug overlay commands for visible chunks.
     * This method iterates over visible chunks in the foreground layer (Layer 0)
     * and overlays high-priority tiles with a semi-transparent red tint.
     * Helps diagnose sprite-behind-tile priority issues like the ARZ wall bug.
     *
     * @param commands      the list of GLCommands to add priority rectangles to
     * @param camera        the camera for visibility culling
     * @param blockAccessor accessor for retrieving blocks at positions
     */
    void generateTilePriorityDebugCommands(List<GLCommand> commands, Camera camera, BlockAccessor blockAccessor) {
        Level level = ctx.level();
        int blockPixelSize = ctx.blockPixelSize();

        if (level == null || level.getMap() == null) {
            return;
        }
        if (!ctx.overlayManager().isEnabled(DebugOverlayToggle.TILE_PRIORITY_VIEW)) {
            return;
        }

        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int cameraWidth = camera.getWidth();
        int cameraHeight = camera.getHeight();

        int levelWidth = level.getMap().getWidth() * blockPixelSize;
        int levelHeight = level.getMap().getHeight() * blockPixelSize;

        // Calculate visible chunk range (same culling logic as foreground rendering)
        int xStart = cameraX - (cameraX % LevelConstants.CHUNK_WIDTH);
        int xEnd = cameraX + cameraWidth;
        int yStart = cameraY - (cameraY % LevelConstants.CHUNK_HEIGHT);
        int yEnd = cameraY + cameraHeight + LevelConstants.CHUNK_HEIGHT;

        // Disable shaders for drawing solid colors (RECTI uses its own debug shader)
        ShaderProgram shaderProgram = ctx.graphicsManager().getShaderProgram();
        int shaderProgramId = 0;
        if (shaderProgram != null) {
            shaderProgramId = shaderProgram.getProgramId();
        }
        commands.add(new GLCommand(GLCommand.CommandType.USE_PROGRAM, 0));

        for (int y = yStart; y <= yEnd; y += LevelConstants.CHUNK_HEIGHT) {
            // Foreground clamps vertically (doesn't wrap)
            if (y < 0 || y >= levelHeight) {
                continue;
            }

            for (int x = xStart; x <= xEnd; x += LevelConstants.CHUNK_WIDTH) {
                // Handle X wrapping
                int wrappedX = ((x % levelWidth) + levelWidth) % levelWidth;

                Block block = blockAccessor.getBlockAtPosition((byte) 0, wrappedX, y);
                if (block == null) {
                    continue;
                }

                int xBlockBit = (wrappedX % blockPixelSize) / LevelConstants.CHUNK_WIDTH;
                int yBlockBit = (y % blockPixelSize) / LevelConstants.CHUNK_HEIGHT;
                ChunkDesc chunkDesc = block.getChunkDesc(xBlockBit, yBlockBit);

                int chunkIndex = chunkDesc.getChunkIndex();
                if (chunkIndex < 0 || chunkIndex >= level.getChunkCount()) {
                    continue;
                }

                Chunk chunk = level.getChunk(chunkIndex);
                if (chunk == null) {
                    continue;
                }

                // Check each of the 4 patterns (8x8 tiles) in this 16x16 chunk
                for (int py = 0; py < 2; py++) {
                    for (int px = 0; px < 2; px++) {
                        PatternDesc desc = chunk.getPatternDesc(px, py);
                        if (desc == null || desc == PatternDesc.EMPTY) {
                            continue;
                        }

                        // Only highlight high-priority tiles (priority bit = 1)
                        if (!desc.getPriority()) {
                            continue;
                        }

                        // Calculate screen coordinates for this 8x8 pattern
                        // We need to account for chunk flip flags
                        int patternX = x + (chunkDesc.getHFlip() ? (1 - px) : px) * Pattern.PATTERN_WIDTH;
                        int patternY = y + (chunkDesc.getVFlip() ? (1 - py) : py) * Pattern.PATTERN_HEIGHT;

                        // Draw a semi-transparent red overlay for high-priority tiles
                        // Use RECTI with alpha blending
                        int renderX = patternX;
                        int renderY = patternY;

                        // Add 16 to align with the pattern renderer's coordinate system (same as collision debug)
                        int drawStartX = renderX;
                        int drawEndX = drawStartX + Pattern.PATTERN_WIDTH;
                        int drawStartY = renderY + 16;
                        int drawEndY = drawStartY - Pattern.PATTERN_HEIGHT;

                        commands.add(new GLCommand(
                                GLCommand.CommandType.RECTI,
                                -1,
                                GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                                1.0f, 0.0f, 0.0f, 0.4f, // Red with 40% opacity
                                drawStartX,
                                drawEndY,
                                drawEndX,
                                drawStartY));
                    }
                }
            }
        }

        // Re-enable shader for subsequent rendering
        if (shaderProgramId != 0) {
            commands.add(new GLCommand(GLCommand.CommandType.USE_PROGRAM, shaderProgramId));
        }
    }

    void drawPlayableSpriteBounds(AbstractPlayableSprite sprite) {
        GraphicsManager graphicsManager = ctx.graphicsManager();
        PlayerSpriteRenderer renderer = sprite.getSpriteRenderer();
        if (renderer == null) {
            return;
        }

        boolean hFlip = Direction.LEFT.equals(sprite.getDirection());
        SpritePieceRenderer.FrameBounds mappingBounds = renderer.getFrameBounds(sprite.getMappingFrame(), hFlip, false);

        int collisionCenterX = sprite.getCentreX();
        int collisionCenterY = sprite.getCentreY();
        int renderCenterX = sprite.getRenderCentreX();
        int renderCenterY = sprite.getRenderCentreY();
        sensorCommands.clear();

        if (mappingBounds.width() > 0 && mappingBounds.height() > 0) {
            int mapLeft = renderCenterX + mappingBounds.minX();
            int mapRight = renderCenterX + mappingBounds.maxX();
            int mapTop = renderCenterY + mappingBounds.minY();
            int mapBottom = renderCenterY + mappingBounds.maxY();
            appendBox(sensorCommands, mapLeft, mapTop, mapRight, mapBottom, 0.1f, 0.85f, 1f);
        }

        int radiusLeft = collisionCenterX - sprite.getXRadius();
        int radiusRight = collisionCenterX + sprite.getXRadius();
        int radiusTop = collisionCenterY - sprite.getYRadius();
        int radiusBottom = collisionCenterY + sprite.getYRadius();
        appendBox(sensorCommands, radiusLeft, radiusTop, radiusRight, radiusBottom, 1f, 0.8f, 0.1f);

        appendCross(sensorCommands, collisionCenterX, collisionCenterY, 2, 1f, 0.8f, 0.1f);
        appendCross(sensorCommands, renderCenterX, renderCenterY, 2, 0.1f, 0.85f, 1f);

        Sensor[] sensors = sprite.getAllSensors();
        for (int i = 0; i < sensors.length; i++) {
            Sensor sensor = sensors[i];
            if (sensor == null) {
                continue;
            }
            sensor.computeRotatedOffset();
            int originX = collisionCenterX + sensor.getRotatedX();
            int originY = collisionCenterY + sensor.getRotatedY();

            float[] color = DebugOverlayPalette.sensorLineColor(i, sensor.isActive());
            appendCross(sensorCommands, originX, originY, 1, color[0], color[1], color[2]);

            if (!sensor.isActive()) {
                continue;
            }
            SensorResult result = sensor.getCurrentResult();
            if (result == null) {
                continue;
            }

            SensorConfiguration sensorConfiguration = SpriteManager
                    .getSensorConfigurationForGroundModeAndDirection(sprite.getGroundMode(), sensor.getDirection());
            Direction globalDirection = sensorConfiguration.direction();

            int dist = result.distance();
            int endX = originX;
            int endY = originY;
            switch (globalDirection) {
                case DOWN -> endY = originY + dist;
                case UP -> endY = originY - dist;
                case LEFT -> endX = originX - dist;
                case RIGHT -> endX = originX + dist;
            }

            appendLine(sensorCommands, originX, originY, endX, endY, color[0], color[1], color[2]);
        }

        if (!sensorCommands.isEmpty()) {
            graphicsManager.enqueueDebugLineState();
            graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, sensorCommands));
        }
    }

    void drawCameraBounds() {
        GraphicsManager graphicsManager = ctx.graphicsManager();
        Camera camera = GameServices.camera();
        cameraBoundsCommands.clear();

        int camX = camera.getX();
        int camY = camera.getY();
        int camW = camera.getWidth();
        int camH = camera.getHeight();

        appendBox(cameraBoundsCommands, camX, camY, camX + camW, camY + camH, 0.85f, 0.9f, 1f);
        appendCross(cameraBoundsCommands, camX + (camW / 2), camY + (camH / 2), 4, 0.85f, 0.9f, 1f);

        int minX = camera.getMinX();
        int minY = camera.getMinY();
        int maxX = camera.getMaxX();
        int maxY = camera.getMaxY();
        if (maxX > minX || maxY > minY) {
            appendBox(cameraBoundsCommands, minX, minY, maxX + camW, maxY + camH, 0.2f, 0.9f, 0.9f);
        }

        if (!cameraBoundsCommands.isEmpty()) {
            graphicsManager.enqueueDebugLineState();
            graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, cameraBoundsCommands));
        }
    }

    void appendLine(
            List<GLCommand> commands,
            int x1,
            int y1,
            int x2,
            int y2,
            float r,
            float g,
            float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    void appendCross(
            List<GLCommand> commands,
            int centerX,
            int centerY,
            int halfSpan,
            float r,
            float g,
            float b) {
        appendLine(commands, centerX - halfSpan, centerY, centerX + halfSpan, centerY, r, g, b);
        appendLine(commands, centerX, centerY - halfSpan, centerX, centerY + halfSpan, r, g, b);
    }

    void appendPlaneSwitcherDebug(ObjectSpawn spawn,
            List<GLCommand> lineCommands,
            List<GLCommand> areaCommands,
            AbstractPlayableSprite player,
            GameModule gameModule,
            ObjectManager objectManager) {
        if (gameModule == null || spawn.objectId() != gameModule.getPlaneSwitcherObjectId()) {
            return;
        }
        int subtype = spawn.subtype();
        int halfSpan = ObjectManager.decodePlaneSwitcherHalfSpan(subtype);
        boolean horizontal = ObjectManager.isPlaneSwitcherHorizontal(subtype);
        int x = spawn.x();
        int y = spawn.y();
        int sideState = objectManager != null ? objectManager.getPlaneSwitcherSideState(spawn) : -1;
        if (sideState < 0 && player != null) {
            sideState = horizontal
                    ? (player.getCentreY() >= y ? 1 : 0)
                    : (player.getCentreX() >= x ? 1 : 0);
        }
        if (sideState < 0) {
            sideState = 0;
        }

        int extent = halfSpan;
        if (horizontal) {
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x - halfSpan, y, 0, 0));
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x + halfSpan, y, 0, 0));

            int top = sideState == 0 ? y - extent : y;
            int bottom = sideState == 0 ? y : y + extent;
            areaCommands.add(new GLCommand(GLCommand.CommandType.RECTI, -1, GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B, SWITCHER_DEBUG_ALPHA,
                    x - halfSpan, top,
                    x + halfSpan, bottom));
        } else {
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x, y - halfSpan, 0, 0));
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x, y + halfSpan, 0, 0));

            int left = sideState == 0 ? x - extent : x;
            int right = sideState == 0 ? x : x + extent;
            areaCommands.add(new GLCommand(GLCommand.CommandType.RECTI, -1, GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B, SWITCHER_DEBUG_ALPHA,
                    left, y - halfSpan,
                    right, y + halfSpan));
        }
    }

    void appendBox(
            List<GLCommand> commands,
            int left,
            int top,
            int right,
            int bottom,
            float r,
            float g,
            float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, left, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, right, top, 0, 0));

        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, right, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, right, bottom, 0, 0));

        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, right, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, left, bottom, 0, 0));

        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, left, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, left, top, 0, 0));
    }

    /**
     * Renders all debug overlays (object points, plane switchers, object hitboxes,
     * ring bounds, camera bounds, player bounds) that were previously inline in
     * LevelManager.drawWithSpritePriority().
     *
     * @param overlayEnabled  whether debug overlay is globally enabled
     * @param objectManager   the object manager (may be null)
     * @param ringManager     the ring manager (may be null)
     * @param spriteManager   the sprite manager
     * @param gameModule      the current game module
     * @param configService   configuration service
     * @param frameCounter    current frame counter
     */
    void renderDebugOverlays(
            boolean overlayEnabled,
            ObjectManager objectManager,
            RingManager ringManager,
            SpriteManager spriteManager,
            GameModule gameModule,
            SonicConfigurationService configService,
            int frameCounter) {
        GraphicsManager graphicsManager = ctx.graphicsManager();

        if (overlayEnabled) {
            graphicsManager.enqueueDebugLineState();
        }

        if (objectManager != null && overlayEnabled) {
            boolean showObjectPoints = ctx.overlayManager().isEnabled(DebugOverlayToggle.OBJECT_POINTS);
            boolean showPlaneSwitchers = ctx.overlayManager().isEnabled(DebugOverlayToggle.PLANE_SWITCHERS);
            debugObjectCommands.clear();
            debugSwitcherLineCommands.clear();
            debugSwitcherAreaCommands.clear();
            Sprite player = spriteManager.getSprite(ActiveGameplayTeamResolver.resolveMainCharacterCode(configService));
            AbstractPlayableSprite playable = player instanceof AbstractPlayableSprite
                    ? (AbstractPlayableSprite) player
                    : null;
            for (ObjectSpawn spawn : objectManager.getActiveSpawns()) {
                // Frustum cull: skip objects outside visible area (with 32px padding for large objects)
                if (!isInCameraFrustum(spawn.x(), spawn.y(), 32)) {
                    continue;
                }
                if (showObjectPoints) {
                    debugObjectCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I,
                            -1,
                            GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                            1f, 0f, 1f,
                            spawn.x(), spawn.y(), 0, 0));
                }
                if (showPlaneSwitchers) {
                    appendPlaneSwitcherDebug(spawn, debugSwitcherLineCommands, debugSwitcherAreaCommands,
                            playable, gameModule, objectManager);
                }
            }
            if (showPlaneSwitchers && !debugSwitcherAreaCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                for (GLCommand command : debugSwitcherAreaCommands) {
                    graphicsManager.registerCommand(command);
                }
            }
            if (showPlaneSwitchers && !debugSwitcherLineCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, debugSwitcherLineCommands));
            }
            if (showObjectPoints && !debugObjectCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                graphicsManager.registerCommand(new GLCommandGroup(GL_POINTS, debugObjectCommands));
            }
        }

        // Per-object debug rendering (hitboxes, velocity vectors, AI state labels)
        if (objectManager != null && overlayEnabled
                && ctx.overlayManager().isEnabled(DebugOverlayToggle.OBJECT_DEBUG)) {
            reusableDebugCtx.clear();
            for (ObjectInstance instance : objectManager.getActiveObjects()) {
                ObjectSpawn spawn = instance.getSpawn();
                if (spawn == null) {
                    continue;
                }
                if (!isInCameraFrustum(spawn.x(), spawn.y(), 64)) {
                    continue;
                }
                instance.appendDebugRenderCommands(reusableDebugCtx);
            }
            if (reusableDebugCtx.hasGeometry()) {
                graphicsManager.enqueueDebugLineState();
                graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, reusableDebugCtx.getGeometryCommands()));
            }
            if (reusableDebugCtx.hasText()) {
                // Transfer ownership - the overlay manager holds this list until
                // DebugRenderer consumes it, then we clear() on the next frame.
                ctx.overlayManager().setObjectDebugTextEntries(reusableDebugCtx.getTextEntries());
            } else {
                ctx.overlayManager().clearObjectDebugTextEntries();
            }
        }

        if (ringManager != null && overlayEnabled
                && ctx.overlayManager().isEnabled(DebugOverlayToggle.RING_BOUNDS)) {
            Collection<RingSpawn> rings = ringManager.getActiveSpawns();
            if (!rings.isEmpty()) {
                if (!ringManager.hasRenderer()) {
                    debugRingCommands.clear();
                    for (RingSpawn ring : rings) {
                        if (!ringManager.isRenderable(ring, frameCounter)) {
                            continue;
                        }
                        // Frustum cull rings outside visible area
                        if (!isInCameraFrustum(ring.x(), ring.y(), 16)) {
                            continue;
                        }
                        debugRingCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I,
                                -1,
                                GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                                1f, 0.85f, 0.1f,
                                ring.x(), ring.y(), 0, 0));
                    }
                    graphicsManager.enqueueDebugLineState();
                    graphicsManager.registerCommand(new GLCommandGroup(GL_POINTS, debugRingCommands));
                } else {
                    PatternSpriteRenderer.FrameBounds bounds = ringManager.getFrameBounds(frameCounter);
                    debugBoxCommands.clear();
                    debugCenterCommands.clear();
                    int crossHalf = 2;

                    for (RingSpawn ring : rings) {
                        if (!ringManager.isRenderable(ring, frameCounter)) {
                            continue;
                        }
                        // Frustum cull rings outside visible area
                        if (!isInCameraFrustum(ring.x(), ring.y(), 16)) {
                            continue;
                        }
                        int centerX = ring.x();
                        int centerY = ring.y();
                        int left = centerX + bounds.minX();
                        int right = centerX + bounds.maxX();
                        int top = centerY + bounds.minY();
                        int bottom = centerY + bounds.maxY();

                        // Bounding box (4 line segments)
                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, top, 0, 0));
                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, top, 0, 0));

                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, top, 0, 0));
                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, bottom, 0, 0));

                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, bottom, 0, 0));
                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, bottom, 0, 0));

                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, bottom, 0, 0));
                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, top, 0, 0));

                        // Center cross
                        debugCenterCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX - crossHalf, centerY, 0, 0));
                        debugCenterCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX + crossHalf, centerY, 0, 0));
                        debugCenterCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX, centerY - crossHalf, 0, 0));
                        debugCenterCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX, centerY + crossHalf, 0, 0));
                    }

                    if (!debugBoxCommands.isEmpty()) {
                        graphicsManager.enqueueDebugLineState();
                        graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, debugBoxCommands));
                    }
                    if (!debugCenterCommands.isEmpty()) {
                        graphicsManager.enqueueDebugLineState();
                        graphicsManager.registerCommand(new GLCommandGroup(GL_LINES, debugCenterCommands));
                    }
                }
            }
        }

        if (overlayEnabled) {
            Sprite player = spriteManager.getSprite(ActiveGameplayTeamResolver.resolveMainCharacterCode(configService));
            if (player instanceof AbstractPlayableSprite playable) {
                if (ctx.overlayManager().isEnabled(DebugOverlayToggle.CAMERA_BOUNDS)) {
                    drawCameraBounds();
                }
                if (ctx.overlayManager().isEnabled(DebugOverlayToggle.PLAYER_BOUNDS)) {
                    drawPlayableSpriteBounds(playable);
                }
            }
        }
    }
}
