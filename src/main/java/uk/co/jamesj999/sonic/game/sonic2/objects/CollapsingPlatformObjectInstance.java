package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.PatternDesc;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;
import uk.co.jamesj999.sonic.level.render.SpritePieceRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Collapsing Platform (Object 0x1F) - OOZ, MCZ, and ARZ.
 * <p>
 * A platform that collapses into falling fragments when the player stands on it
 * for 7 frames. Each zone has different art, fragment counts, and delay patterns.
 * <p>
 * Based on Obj1F in the Sonic 2 disassembly (s2.asm).
 * <p>
 * Zone-specific behavior:
 * - OOZ: 7 fragments, dedicated Nemesis art at 0x809D0
 * - MCZ: 6 fragments, dedicated Nemesis art at 0xF1ABA
 * - ARZ: 8 fragments, uses level art tiles (0x55, 0x59, 0xA3, 0xA7)
 */
public class CollapsingPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    private static final Logger LOGGER = Logger.getLogger(CollapsingPlatformObjectInstance.class.getName());

    /**
     * Zone-specific configuration for collapsing platforms.
     * <p>
     * Note: Fragment visual offsets are stored in the sprite mapping data,
     * not here. Each fragment spawns at the parent's exact x/y position,
     * and the sprite piece's offsets provide the visual displacement.
     */
    private record ZoneConfig(
            int halfWidth,
            int halfHeight,
            int[] delayData,
            String artKey,
            int palette,
            boolean usesLevelArt,
            int[][] pieceOffsets  // For debug rendering only - actual offsets come from mappings
    ) {}

    // OOZ: 7 fragments from obj1F_b.asm
    // Delay values: $1A, $12, $0A, $02, $16, $0E, $06
    // Piece offsets from mapping data (for debug rendering)
    private static final ZoneConfig OOZ_CONFIG = new ZoneConfig(
            0x40,  // width_pixels from disassembly (half-width, collision extends ±0x40 from center)
            0x10,  // 16px half-height
            new int[]{0x1A, 0x12, 0x0A, 0x02, 0x16, 0x0E, 0x06},
            Sonic2ObjectArtKeys.OOZ_COLLAPSING_PLATFORM,
            3,
            false,
            new int[][]{  // Piece offsets from obj1F_b.asm for debug rendering
                    {-0x40, -0x10},  // Piece 0
                    {-0x20, -0x10},  // Piece 1
                    {0x00, -0x10},   // Piece 2
                    {0x20, -0x10},   // Piece 3
                    {-0x40, 0x10},   // Piece 4
                    {-0x20, 0x10},   // Piece 5
                    {0x00, 0x10}     // Piece 6
            }
    );

    // MCZ: 6 fragments from obj1F_c.asm
    // Delay values: $1A, $16, $12, $0E, $0A, $02
    private static final ZoneConfig MCZ_CONFIG = new ZoneConfig(
            0x20,  // width_pixels from disassembly (half-width, collision extends ±0x20 from center)
            0x10,  // 16px half-height
            new int[]{0x1A, 0x16, 0x12, 0x0E, 0x0A, 0x02},
            Sonic2ObjectArtKeys.MCZ_COLLAPSING_PLATFORM,
            3,
            false,
            new int[][]{  // Piece offsets from obj1F_c.asm frame 1 for debug rendering
                    {-0x20, -0x10},  // Piece 0
                    {-0x10, -0x10},  // Piece 1
                    {0x00, -0x10},   // Piece 2
                    {0x10, -0x10},   // Piece 3
                    {-0x10, 0x00},   // Piece 4
                    {0x08, 0x00}     // Piece 5
            }
    );

    // ARZ: 8 fragments from obj1F_d.asm
    // Delay values: $16, $1A, $18, $12, $06, $0E, $0A, $02
    private static final ZoneConfig ARZ_CONFIG = new ZoneConfig(
            0x20,  // width_pixels from disassembly (half-width, collision extends ±0x20 from center)
            0x10,  // 16px half-height
            new int[]{0x16, 0x1A, 0x18, 0x12, 0x06, 0x0E, 0x0A, 0x02},
            null,  // Uses level art
            2,
            true,
            new int[][]{  // Piece offsets from obj1F_d.asm frame 1 for debug rendering
                    {-0x20, -0x10},  // Piece 0
                    {-0x10, -0x10},  // Piece 1
                    {0x00, -0x10},   // Piece 2
                    {0x10, -0x10},   // Piece 3
                    {-0x20, 0x00},   // Piece 4
                    {-0x10, 0x00},   // Piece 5
                    {0x00, 0x00},    // Piece 6
                    {0x10, 0x00}     // Piece 7
            }
    );

    // Default config for unknown zones (uses OOZ config)
    private static final ZoneConfig DEFAULT_CONFIG = OOZ_CONFIG;

    // ARZ uses level art tiles at specific indices from obj1F_d.asm
    // Palette line 2 as per disassembly: make_art_tile(ArtTile_ArtKos_LevelArt,2,0)
    private static final int ARZ_PALETTE = 2;

    // ARZ Frame 0 (intact) - 4 pieces from obj1F_d.asm Map_obj1F_d_0004
    private static final SpriteMappingFrame ARZ_FRAME_INTACT = new SpriteMappingFrame(List.of(
            new SpriteMappingPiece(-0x20, -0x10, 4, 2, 0x55, false, false, ARZ_PALETTE),  // Top-left
            new SpriteMappingPiece(0x00, -0x10, 4, 2, 0x55, true, false, ARZ_PALETTE),   // Top-right (H-flip)
            new SpriteMappingPiece(-0x20, 0x00, 4, 2, 0xA3, false, false, ARZ_PALETTE),  // Bottom-left
            new SpriteMappingPiece(0x00, 0x00, 4, 2, 0xA3, true, false, ARZ_PALETTE)     // Bottom-right (H-flip)
    ));

    // ARZ Frame 1 (collapsed) - 8 pieces from obj1F_d.asm Map_obj1F_d_0026
    private static final SpriteMappingFrame ARZ_FRAME_COLLAPSED = new SpriteMappingFrame(List.of(
            new SpriteMappingPiece(-0x20, -0x10, 2, 2, 0x55, false, false, ARZ_PALETTE),  // Piece 0
            new SpriteMappingPiece(-0x10, -0x10, 2, 2, 0x59, false, false, ARZ_PALETTE),  // Piece 1
            new SpriteMappingPiece(0x00, -0x10, 2, 2, 0x59, true, false, ARZ_PALETTE),   // Piece 2 (H-flip)
            new SpriteMappingPiece(0x10, -0x10, 2, 2, 0x55, true, false, ARZ_PALETTE),   // Piece 3 (H-flip)
            new SpriteMappingPiece(-0x20, 0x00, 2, 2, 0xA3, false, false, ARZ_PALETTE),  // Piece 4
            new SpriteMappingPiece(-0x10, 0x00, 2, 2, 0xA7, false, false, ARZ_PALETTE),  // Piece 5
            new SpriteMappingPiece(0x00, 0x00, 2, 2, 0xA7, true, false, ARZ_PALETTE),    // Piece 6 (H-flip)
            new SpriteMappingPiece(0x10, 0x00, 2, 2, 0xA3, true, false, ARZ_PALETTE)     // Piece 7 (H-flip)
    ));

    // State
    private static final int INITIAL_DELAY = 7;  // 7 frames before collapse starts

    private ZoneConfig config;
    private int delayCounter = INITIAL_DELAY;
    private boolean stoodOnFlag = false;
    private boolean collapsed = false;
    private int mappingFrame = 0;  // 0 = intact, 1 = collapsed appearance

    // Orientation from spawn render_flags (inherited by fragments per disassembly)
    private final boolean hFlip;
    private final boolean vFlip;

    public CollapsingPlatformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        // Extract flip flags from spawn renderFlags (bit 0 = hFlip, bit 1 = vFlip)
        this.hFlip = (spawn.renderFlags() & 1) != 0;
        this.vFlip = (spawn.renderFlags() & 2) != 0;
        initZoneConfig();
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return spawn.y();
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (collapsed || isDestroyed()) {
            return;
        }

        // Check if player is standing on the platform
        boolean isStanding = isPlayerStanding();

        if (isStanding) {
            if (!stoodOnFlag) {
                // Player just landed on the platform
                stoodOnFlag = true;
            }

            // Decrement delay counter while standing
            delayCounter--;

            if (delayCounter <= 0) {
                // Trigger collapse
                collapse();
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null || config == null) {
            appendDebug(commands);
            return;
        }

        if (config.usesLevelArt()) {
            // ARZ uses level patterns - render using level tiles
            renderUsingLevelArt(commands);
        } else {
            // OOZ/MCZ use dedicated art
            PatternSpriteRenderer renderer = renderManager.getRenderer(config.artKey());
            if (renderer == null || !renderer.isReady()) {
                appendDebug(commands);
                return;
            }
            renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        if (config == null) {
            return new SolidObjectParams(0x40, 0x10, 0x10);
        }
        return new SolidObjectParams(config.halfWidth(), config.halfHeight(), config.halfHeight());
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Collision handling is managed via update() and isPlayerStanding()
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !collapsed && !isDestroyed();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    private void initZoneConfig() {
        LevelManager manager = LevelManager.getInstance();
        final int zoneIndex = (manager != null && manager.getCurrentLevel() != null)
                ? manager.getCurrentLevel().getZoneIndex()
                : -1;

        config = switch (zoneIndex) {
            case Sonic2Constants.ZONE_OIL_OCEAN -> OOZ_CONFIG;
            case Sonic2Constants.ZONE_MYSTIC_CAVE -> MCZ_CONFIG;
            case Sonic2Constants.ZONE_ARZ -> ARZ_CONFIG;
            default -> DEFAULT_CONFIG;
        };

        LOGGER.fine(() -> String.format("CollapsingPlatform at (%d,%d) using %s config",
                spawn.x(), spawn.y(),
                zoneIndex == Sonic2Constants.ZONE_OIL_OCEAN ? "OOZ" :
                        zoneIndex == Sonic2Constants.ZONE_MYSTIC_CAVE ? "MCZ" :
                                zoneIndex == Sonic2Constants.ZONE_ARZ ? "ARZ" : "DEFAULT"));
    }

    private boolean isPlayerStanding() {
        LevelManager manager = LevelManager.getInstance();
        if (manager == null || manager.getSolidObjectManager() == null) {
            return false;
        }
        return manager.getSolidObjectManager().isRidingObject(this);
    }

    private void collapse() {
        if (collapsed) {
            return;
        }
        collapsed = true;
        mappingFrame = 1;  // Show collapsed appearance (if applicable)

        // Play collapse sound
        AudioManager.getInstance().playSfx(Sonic2AudioConstants.SFX_SMASH);

        // Spawn fragments
        spawnFragments();

        // Mark as destroyed
        setDestroyed(true);

        LOGGER.fine(() -> String.format("CollapsingPlatform at (%d,%d) collapsed, spawning %d fragments",
                spawn.x(), spawn.y(), config.delayData().length));
    }

    private void spawnFragments() {
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (objectManager == null) {
            return;
        }

        int[] delayData = config.delayData();

        // Fragments spawn at parent's exact position - sprite piece offsets handle visual displacement
        // (This matches the disassembly where fragments inherit parent x_pos/y_pos and render_flags)
        for (int i = 0; i < delayData.length; i++) {
            int delay = delayData[i];

            CollapsingPlatformFragmentInstance fragment = new CollapsingPlatformFragmentInstance(
                    spawn.x(), spawn.y(), delay, i, config, renderManager, hFlip, vFlip);
            objectManager.addDynamicObject(fragment);
        }
    }

    private void renderUsingLevelArt(List<GLCommand> commands) {
        // ARZ uses level art tiles at specific indices from obj1F_d.asm
        // basePatternIndex = 0 because level patterns start at index 0
        SpriteMappingFrame frame = (mappingFrame == 0) ? ARZ_FRAME_INTACT : ARZ_FRAME_COLLAPSED;
        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        List<SpriteMappingPiece> pieces = frame.pieces();

        // Draw in reverse order (Painter's Algorithm) - first piece in list appears on top
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = pieces.get(i);
            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    spawn.x(),
                    spawn.y(),
                    0,  // Level patterns start at index 0
                    ARZ_PALETTE,
                    hFlip,  // Frame H-flip from spawn render_flags
                    vFlip,  // Frame V-flip from spawn render_flags
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        descIndex |= (paletteIndex & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                    });
        }
    }

    private void appendDebug(List<GLCommand> commands) {
        if (config == null) {
            return;
        }
        int halfWidth = config.halfWidth();
        int halfHeight = config.halfHeight();
        int x = spawn.x();
        int y = spawn.y();
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        float r = collapsed ? 0.5f : 0.8f;
        float g = 0.4f;
        float b = collapsed ? 0.2f : 0.6f;

        appendLine(commands, left, top, right, top, r, g, b);
        appendLine(commands, right, top, right, bottom, r, g, b);
        appendLine(commands, right, bottom, left, bottom, r, g, b);
        appendLine(commands, left, bottom, left, top, r, g, b);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2, float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    /**
     * Fragment object that falls after the platform collapses.
     * Each fragment has a delay before it starts falling.
     * <p>
     * In the original disassembly, each fragment uses static_mappings mode where
     * the mappings pointer points to a single sprite piece (not a full frame).
     * The piece's x/y offsets provide the visual displacement from the fragment's
     * world position. For now, we use debug rendering until art is fully integrated.
     */
    public static class CollapsingPlatformFragmentInstance extends AbstractObjectInstance {

        // Gravity from ObjectMoveAndFall (s2.asm line 29950)
        private static final int GRAVITY = 0x38;

        private int currentX;
        private int currentY;
        private int subY;     // 8.8 fixed point for sub-pixel Y
        private int yVel = 0; // 8.8 fixed point velocity
        private int delayCounter;
        private final int fragmentIndex;
        private final ZoneConfig config;
        private final ObjectRenderManager renderManager;
        private boolean falling = false;

        // Inherited from parent (per disassembly: render_flags copied from parent to fragment)
        private final boolean hFlip;
        private final boolean vFlip;

        public CollapsingPlatformFragmentInstance(int x, int y, int delay, int fragmentIndex,
                                                   ZoneConfig config, ObjectRenderManager renderManager,
                                                   boolean hFlip, boolean vFlip) {
            super(new ObjectSpawn(x, y, 0x1F, 0, 0, false, 0), "CollapsingPlatformFragment");
            this.currentX = x;
            this.currentY = y;
            this.subY = y << 8;
            this.delayCounter = delay;
            this.fragmentIndex = fragmentIndex;
            this.config = config;
            this.renderManager = renderManager;
            this.hFlip = hFlip;
            this.vFlip = vFlip;
        }

        @Override
        public int getX() {
            return currentX;
        }

        @Override
        public int getY() {
            return currentY;
        }

        @Override
        public void update(int frameCounter, AbstractPlayableSprite player) {
            if (isDestroyed()) {
                return;
            }

            if (delayCounter > 0) {
                // Still waiting before falling
                delayCounter--;
                return;
            }

            // Start falling
            falling = true;

            // Apply gravity
            yVel += GRAVITY;

            // Update position
            subY += yVel;
            currentY = subY >> 8;

            // Check if off-screen (destroy if too far below camera)
            Camera camera = Camera.getInstance();
            int screenHeight = 224;
            if (currentY > camera.getY() + screenHeight + 64) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }

            if (renderManager == null || config == null) {
                appendDebugFragment(commands);
                return;
            }

            if (config.usesLevelArt()) {
                // ARZ fragments use level art
                renderArzFragment();
                return;
            }

            PatternSpriteRenderer renderer = renderManager.getRenderer(config.artKey());
            if (renderer == null || !renderer.isReady()) {
                appendDebugFragment(commands);
                return;
            }

            // Fragment frame index depends on zone
            // OOZ: All fragments use the same mapping (frame 0 or 1)
            // MCZ: Fragments use frame 1 (collapsed pieces)
            int frameIndex = 1;  // Collapsed/fragment frame
            renderer.drawFrameIndex(frameIndex, currentX, currentY, hFlip, vFlip);
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(4);
        }

        /**
         * Render an ARZ fragment using level patterns.
         * Each fragment renders its corresponding piece from ARZ_FRAME_COLLAPSED.
         */
        private void renderArzFragment() {
            if (fragmentIndex < 0 || fragmentIndex >= ARZ_FRAME_COLLAPSED.pieces().size()) {
                return;
            }

            SpriteMappingPiece piece = ARZ_FRAME_COLLAPSED.pieces().get(fragmentIndex);
            GraphicsManager graphicsManager = GraphicsManager.getInstance();

            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    currentX,
                    currentY,
                    0,  // Level patterns start at index 0
                    ARZ_PALETTE,
                    hFlip,  // Frame H-flip inherited from parent
                    vFlip,  // Frame V-flip inherited from parent
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        descIndex |= (paletteIndex & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                    });
        }

        private void appendDebugFragment(List<GLCommand> commands) {
            // Get visual offset from piece offsets (for debug rendering)
            int offsetX = 0;
            int offsetY = 0;
            if (config != null && config.pieceOffsets() != null &&
                    fragmentIndex < config.pieceOffsets().length) {
                offsetX = config.pieceOffsets()[fragmentIndex][0];
                offsetY = config.pieceOffsets()[fragmentIndex][1];
            }

            // Fragment renders at world position + piece offset
            int renderX = currentX + offsetX;
            int renderY = currentY + offsetY;

            int size = 12;  // Approximate piece size for debug
            int left = renderX - size;
            int right = renderX + size;
            int top = renderY - size;
            int bottom = renderY + size;

            float alpha = falling ? 0.7f : 1.0f;
            float r = 0.6f * alpha;
            float g = 0.3f * alpha;
            float b = 0.1f * alpha;

            // Draw a small square for the fragment
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
    }
}
