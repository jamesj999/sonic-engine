package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.PatternDesc;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.SolidContact;
import uk.co.jamesj999.sonic.level.objects.SolidObjectListener;
import uk.co.jamesj999.sonic.level.objects.SolidObjectParams;
import uk.co.jamesj999.sonic.level.objects.SolidObjectProvider;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;
import uk.co.jamesj999.sonic.level.render.SpritePieceRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Object 2B - Rising Pillar from ARZ.
 * <p>
 * A pillar that rises when the player approaches within 64 pixels horizontally.
 * When fully extended and the player stands on it, the pillar launches them upward
 * and breaks into debris fragments.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 51297-51524 (Obj2B code)
 * <p>
 * <b>Main routine states:</b>
 * <ul>
 *   <li>2: Main - handles rising and solid object behavior</li>
 *   <li>4: Debris - falling debris piece with gravity</li>
 * </ul>
 * <b>Routine Secondary states (when routine=2):</b>
 * <ul>
 *   <li>0: Wait for player proximity trigger (within 64 pixels horizontal)</li>
 *   <li>2: Rising - extends 4 pixels per frame for 6 frames</li>
 *   <li>4: Extended - waits for player to stand on it, then breaks</li>
 * </ul>
 */
public class RisingPillarObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {
    private static final Logger LOGGER = Logger.getLogger(RisingPillarObjectInstance.class.getName());

    // art_tile palette from disassembly: make_art_tile(ArtTile_ArtKos_LevelArt,1,0)
    // The original game ADDS this to each mapping piece's pattern word
    private static final int ART_TILE_PALETTE = 1;

    private static final int HALF_WIDTH = 0x1C;
    private static final int INITIAL_Y_RADIUS = 0x20;
    private static final int TRIGGER_DISTANCE = 0x40;
    private static final int RISE_SPEED = 4;
    private static final int RISE_DELAY = 3;
    private static final int MAX_EXTENSION_FRAME = 6;
    private static final int GRAVITY = 0x18;

    // Debris fragment velocities from word_25BBE and delays from byte_25BB0
    // Format: x velocity, y velocity, delay
    private static final int[][] DEBRIS_DATA = {
            {-0x200, -0x200, 0},   // Fragment 0
            { 0x200, -0x200, 0},   // Fragment 1
            {-0x1C0, -0x1C0, 0},   // Fragment 2
            { 0x1C0, -0x1C0, 0},   // Fragment 3
            {-0x180, -0x180, 4},   // Fragment 4
            { 0x180, -0x180, 4},   // Fragment 5
            {-0x140, -0x140, 8},   // Fragment 6
            { 0x140, -0x140, 8},   // Fragment 7
            {-0x100, -0x100, 12},  // Fragment 8
            { 0x100, -0x100, 12},  // Fragment 9
            {-0x0C0, -0x0C0, 16},  // Fragment 10
            { 0x0C0, -0x0C0, 16},  // Fragment 11
            {-0x080, -0x080, 20},  // Fragment 12
            { 0x080, -0x080, 20}   // Fragment 13
    };

    private static List<SpriteMappingFrame> mappings;
    private static boolean mappingLoadAttempted;

    // Position (8.8 fixed point for debris mode)
    private int x;
    private int y;
    private int subX;
    private int subY;

    private int yRadius;
    private int routine;           // 2 = main, 4 = debris
    private int routineSecondary;  // sub-state for main routine
    private int delayCounter;
    private int mappingFrame;
    private int velX;
    private int velY;
    private int debrisDelay;       // objoff_3F in ROM - delay before debris starts moving
    private SpriteMappingPiece debrisPiece;  // single piece for debris mode
    private ObjectSpawn dynamicSpawn;

    public RisingPillarObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.x = spawn.x();
        this.y = spawn.y();
        this.subX = x << 8;
        this.subY = y << 8;
        this.yRadius = INITIAL_Y_RADIUS;
        this.mappingFrame = 0;
        this.routine = 2;          // Start in main routine
        this.routineSecondary = 0;
        this.delayCounter = 0;
        this.velX = 0;
        this.velY = 0;
        this.debrisDelay = 0;
        this.debrisPiece = null;

        refreshDynamicSpawn();
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return dynamicSpawn != null ? dynamicSpawn : spawn;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (routine == 2) {
            updateMain(player);
        } else if (routine == 4) {
            updateDebris();
        }
        refreshDynamicSpawn();
    }

    /**
     * Main routine (routine = 2): handles rising and solid object behavior.
     * Corresponds to Obj2B_Main in disassembly.
     */
    private void updateMain(AbstractPlayableSprite player) {
        switch (routineSecondary) {
            case 0 -> updateWaitForTrigger(player);
            case 2 -> updateRising();
            case 4 -> {
                // Extended state - solid contact triggers break
                // The actual break is triggered in onSolidContact
            }
        }
    }

    /**
     * State 0: Wait for player to approach within trigger distance.
     * Corresponds to loc_25B3C in disassembly.
     */
    private void updateWaitForTrigger(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        int dx = x - player.getCentreX();
        if (dx < 0) {
            dx = -dx;
        }

        if (dx < TRIGGER_DISTANCE) {
            routineSecondary = 2;
            delayCounter = RISE_DELAY;
        }
    }

    /**
     * State 2: Rising - extend the pillar upward.
     * Corresponds to loc_25B66 in disassembly.
     */
    private void updateRising() {
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        // Extend pillar: y -= 4, yRadius += 4
        y -= RISE_SPEED;
        yRadius += RISE_SPEED;
        mappingFrame++;

        if (mappingFrame >= MAX_EXTENSION_FRAME) {
            routineSecondary = 4;
        }

        delayCounter = RISE_DELAY;
    }

    /**
     * Debris routine (routine = 4): falling debris with gravity.
     * Corresponds to loc_25B8E in disassembly.
     */
    private void updateDebris() {
        // Check delay (objoff_3F)
        if (debrisDelay > 0) {
            debrisDelay--;
            return;
        }

        // Apply gravity and move (ObjectMove + add gravity)
        velY += GRAVITY;
        subX += velX;
        subY += velY;
        x = subX >> 8;
        y = subY >> 8;

        // Check if off-screen - delete if below screen
        Camera camera = Camera.getInstance();
        int screenBottom = camera.getY() + 224 + 128;
        if (y > screenBottom) {
            setDestroyed(true);
        }
    }

    /**
     * Release the player and break the pillar into debris.
     * The pillar itself becomes the first debris piece.
     * Corresponds to loc_25ACE and loc_25BF6 in disassembly.
     */
    private void releasePlayerAndBreak(AbstractPlayableSprite player) {
        // Set player to rolling state and in-air
        player.setRolling(true);
        player.setAir(true);

        // Play slow smash sound effect
        AudioManager.getInstance().playSfx(GameSound.SLOW_SMASH);

        // NOTE: We do NOT call markRemembered here. The original game uses MarkObjGone
        // which CLEARS the respawn flag (bclr #7), allowing the pillar to respawn
        // in its initial shrunken state when the player returns to this area.

        // Get debris frame (mapping_frame + 7)
        ensureMappingsLoaded();
        if (mappings == null || mappings.isEmpty()) {
            setDestroyed(true);
            return;
        }

        int debrisFrame = Math.min(mappingFrame + 7, mappings.size() - 1);
        SpriteMappingFrame frame = mappings.get(debrisFrame);
        if (frame == null || frame.pieces().isEmpty()) {
            setDestroyed(true);
            return;
        }

        List<SpriteMappingPiece> pieces = frame.pieces();

        // Transform THIS pillar into the first debris piece
        // The original sets routine to 4, gives it velocity, and sets static mappings
        routine = 4;
        mappingFrame = debrisFrame;
        debrisPiece = pieces.get(0);
        velX = DEBRIS_DATA[0][0];
        velY = DEBRIS_DATA[0][1];
        debrisDelay = DEBRIS_DATA[0][2];

        // Initialize fixed-point position for debris movement
        subX = x << 8;
        subY = y << 8;

        // Spawn remaining debris pieces (1-13) as separate objects
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager != null) {
            int numPieces = Math.min(pieces.size(), DEBRIS_DATA.length);
            for (int i = 1; i < numPieces; i++) {
                SpriteMappingPiece piece = pieces.get(i);
                int vx = DEBRIS_DATA[i][0];
                int vy = DEBRIS_DATA[i][1];
                int delay = DEBRIS_DATA[i][2];

                RisingPillarDebrisInstance debris = new RisingPillarDebrisInstance(
                        x, y, vx, vy, piece, delay);
                objectManager.addDynamicObject(debris);
            }
        }

        LOGGER.fine(() -> String.format("Rising pillar at (%d,%d) broke into debris", spawn.x(), spawn.y()));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ensureMappingsLoaded();
        if (mappings == null || mappings.isEmpty()) {
            appendDebug(commands);
            return;
        }

        if (routine == 4) {
            // Debris mode - render single piece
            renderDebrisPiece(commands);
        } else {
            // Main mode - render full frame
            renderFullFrame(commands);
        }
    }

    private void renderFullFrame(List<GLCommand> commands) {
        int frame = mappingFrame;
        if (frame < 0 || frame >= mappings.size()) {
            frame = 0;
        }

        SpriteMappingFrame mapping = mappings.get(frame);
        if (mapping == null || mapping.pieces().isEmpty()) {
            appendDebug(commands);
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        List<SpriteMappingPiece> pieces = mapping.pieces();
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = pieces.get(i);
            renderPieceWithArtTile(graphicsManager, piece, x, y, hFlip, vFlip);
        }
    }

    private void renderDebrisPiece(List<GLCommand> commands) {
        if (debrisPiece == null) {
            return;
        }

        // Don't render if in delay period
        if (debrisDelay > 0) {
            return;
        }

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        renderPieceWithArtTile(graphicsManager, debrisPiece, x, y, false, false);
    }

    /**
     * Render a piece, adding art_tile palette offset as the original game does.
     * The original game does: pattern_word = mapping_pattern + art_tile
     * This adds the art_tile palette (1) to the mapping piece's palette.
     */
    private void renderPieceWithArtTile(GraphicsManager graphicsManager, SpriteMappingPiece piece,
                                        int drawX, int drawY, boolean hFlip, boolean vFlip) {
        SpritePieceRenderer.renderPieces(
                List.of(piece),
                drawX,
                drawY,
                0,
                -1, // We handle palette ourselves
                hFlip,
                vFlip,
                (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, px, py) -> {
                    int descIndex = patternIndex & 0x7FF;
                    if (pieceHFlip) {
                        descIndex |= 0x800;
                    }
                    if (pieceVFlip) {
                        descIndex |= 0x1000;
                    }
                    // Add art_tile palette to mapping palette (as original game does)
                    int finalPalette = (paletteIndex + ART_TILE_PALETTE) & 0x3;
                    descIndex |= (finalPalette & 0x3) << 13;
                    graphicsManager.renderPattern(new PatternDesc(descIndex), px, py);
                });
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return new SolidObjectParams(
                HALF_WIDTH + 0x0B,
                yRadius,
                yRadius + 1
        );
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Only handle solid contact in main routine, extended state
        if (routine != 2 || routineSecondary != 4) {
            return;
        }

        // If player is standing on extended pillar, break it
        if (contact.standing()) {
            releasePlayerAndBreak(player);
        }
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        // Only solid in main routine (not when debris)
        return routine == 2 && !isDestroyed();
    }

    private void refreshDynamicSpawn() {
        if (dynamicSpawn == null || dynamicSpawn.x() != x || dynamicSpawn.y() != y) {
            dynamicSpawn = new ObjectSpawn(
                    x,
                    y,
                    spawn.objectId(),
                    spawn.subtype(),
                    spawn.renderFlags(),
                    spawn.respawnTracked(),
                    spawn.rawYWord());
        }
    }

    private static void ensureMappingsLoaded() {
        if (mappingLoadAttempted) {
            return;
        }
        mappingLoadAttempted = true;
        LevelManager manager = LevelManager.getInstance();
        if (manager == null || manager.getGame() == null) {
            return;
        }
        try {
            Rom rom = manager.getGame().getRom();
            RomByteReader reader = RomByteReader.fromRom(rom);
            mappings = loadMappingFrames(reader, Sonic2Constants.MAP_UNC_OBJ2B_ADDR);
            LOGGER.fine("Loaded " + mappings.size() + " Obj2B mapping frames");
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load Obj2B mappings: " + e.getMessage());
        }
    }

    private static List<SpriteMappingFrame> loadMappingFrames(RomByteReader reader, int mappingAddr) {
        int firstOffset = reader.readU16BE(mappingAddr);
        int frameCount = firstOffset / 2;
        List<SpriteMappingFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int offset = reader.readU16BE(mappingAddr + (i * 2));
            int frameAddr = mappingAddr + offset;
            int pieceCount = reader.readU16BE(frameAddr);
            frameAddr += 2;
            List<SpriteMappingPiece> pieces = new ArrayList<>(pieceCount);
            for (int p = 0; p < pieceCount; p++) {
                int yOffset = (byte) reader.readU8(frameAddr);
                frameAddr += 1;
                int size = reader.readU8(frameAddr);
                frameAddr += 1;
                int tileWord = reader.readU16BE(frameAddr);
                frameAddr += 2;
                frameAddr += 2; // skip 2 bytes (2P tile word)
                int xOffset = (short) reader.readU16BE(frameAddr);
                frameAddr += 2;

                int widthTiles = ((size >> 2) & 0x3) + 1;
                int heightTiles = (size & 0x3) + 1;

                int tileIndex = tileWord & 0x7FF;
                boolean hFlip = (tileWord & 0x800) != 0;
                boolean vFlip = (tileWord & 0x1000) != 0;
                int paletteIndex = (tileWord >> 13) & 0x3;

                pieces.add(new SpriteMappingPiece(
                        xOffset,
                        yOffset,
                        widthTiles,
                        heightTiles,
                        tileIndex,
                        hFlip,
                        vFlip,
                        paletteIndex));
            }
            frames.add(new SpriteMappingFrame(pieces));
        }
        return frames;
    }

    private void appendDebug(List<GLCommand> commands) {
        int halfWidth = HALF_WIDTH;
        int halfHeight = yRadius;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        appendLine(commands, left, top, right, top);
        appendLine(commands, right, top, right, bottom);
        appendLine(commands, right, bottom, left, bottom);
        appendLine(commands, left, bottom, left, top);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.4f, 0.6f, 0.2f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.4f, 0.6f, 0.2f, x2, y2, 0, 0));
    }

    /**
     * Inner class for debris fragments (pieces 1-13).
     * Piece 0 is the original pillar object transformed into debris.
     */
    public static class RisingPillarDebrisInstance extends AbstractObjectInstance {

        private static final int GRAVITY = 0x18;

        private int currentX;
        private int currentY;
        private int subX;
        private int subY;
        private int velX;
        private int velY;
        private int delay;
        private final SpriteMappingPiece piece;

        public RisingPillarDebrisInstance(int x, int y, int velX, int velY, SpriteMappingPiece piece, int delay) {
            super(new ObjectSpawn(x, y, 0x2B, 0, 0, false, 0), "PillarDebris");
            this.currentX = x;
            this.currentY = y;
            this.subX = x << 8;
            this.subY = y << 8;
            this.velX = velX;
            this.velY = velY;
            this.piece = piece;
            this.delay = delay;
        }

        @Override
        public void update(int frameCounter, AbstractPlayableSprite player) {
            if (isDestroyed()) {
                return;
            }

            // Handle spawn delay (objoff_3F)
            if (delay > 0) {
                delay--;
                return;
            }

            // Apply gravity and move
            velY += GRAVITY;
            subX += velX;
            subY += velY;
            currentX = subX >> 8;
            currentY = subY >> 8;

            // Check if off-screen
            Camera camera = Camera.getInstance();
            int screenBottom = camera.getY() + 224 + 128;
            if (currentY > screenBottom) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || piece == null || delay > 0) {
                return;
            }

            GraphicsManager graphicsManager = GraphicsManager.getInstance();
            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    currentX,
                    currentY,
                    0,
                    -1,
                    false,
                    false,
                    (patternIndex, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                        int descIndex = patternIndex & 0x7FF;
                        if (pieceHFlip) {
                            descIndex |= 0x800;
                        }
                        if (pieceVFlip) {
                            descIndex |= 0x1000;
                        }
                        // Add art_tile palette to mapping palette
                        int finalPalette = (paletteIndex + ART_TILE_PALETTE) & 0x3;
                        descIndex |= (finalPalette & 0x3) << 13;
                        graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                    });
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(4);
        }
    }
}
