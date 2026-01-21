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
 * <b>Routine Secondary states:</b>
 * <ul>
 *   <li>0: Wait for player proximity trigger (within 64 pixels horizontal)</li>
 *   <li>2: Rising - extends 4 pixels per frame for 6 frames</li>
 *   <li>4: Extended - waits for player to stand on it, then launches</li>
 * </ul>
 */
public class RisingPillarObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {
    private static final Logger LOGGER = Logger.getLogger(RisingPillarObjectInstance.class.getName());

    private static final int PALETTE_INDEX = 1;
    private static final int HALF_WIDTH = 0x1C;
    private static final int INITIAL_Y_RADIUS = 0x20;
    private static final int TRIGGER_DISTANCE = 0x40;
    private static final int RISE_SPEED = 4;
    private static final int RISE_DELAY = 3;
    private static final int MAX_EXTENSION_FRAME = 6;
    private static final int GRAVITY = 0x18;
    private static final int OFFSCREEN_Y_MARGIN = 0x120;

    // Debris fragment velocities from Obj2B_VelArray (word_25BBE)
    // Data format: index, x velocity, y velocity, delay
    // Even indices fly left (-x), odd indices fly right (+x)
    private static final int[][] DEBRIS_DATA = {
            {0, -0x200, -0x200, 0},   // Fragment 0: left
            {1,  0x200, -0x200, 0},   // Fragment 1: right
            {2, -0x1C0, -0x1C0, 0},   // Fragment 2: left
            {3,  0x1C0, -0x1C0, 0},   // Fragment 3: right
            {4, -0x180, -0x180, 4},   // Fragment 4: left
            {5,  0x180, -0x180, 4},   // Fragment 5: right
            {6, -0x140, -0x140, 8},   // Fragment 6: left
            {7,  0x140, -0x140, 8},   // Fragment 7: right
            {8, -0x100, -0x100, 12},  // Fragment 8: left
            {9,  0x100, -0x100, 12},  // Fragment 9: right
            {10,-0x0C0, -0x0C0, 16},  // Fragment 10: left
            {11, 0x0C0, -0x0C0, 16},  // Fragment 11: right
            {12,-0x080, -0x080, 20},  // Fragment 12: left
            {13, 0x080, -0x080, 20}   // Fragment 13: right
    };

    private static List<SpriteMappingFrame> mappings;
    private static boolean mappingLoadAttempted;

    private int x;
    private int y;
    private int baseY;
    private int yRadius;
    private int routineSecondary;
    private int riseFrame;
    private int delayCounter;
    private int mappingFrame;
    private ObjectSpawn dynamicSpawn;
    private boolean playerStanding;

    public RisingPillarObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.x = spawn.x();
        this.y = spawn.y();
        this.baseY = spawn.y();
        this.yRadius = INITIAL_Y_RADIUS;
        this.mappingFrame = 0;
        this.routineSecondary = 0;
        this.riseFrame = 0;
        this.delayCounter = 0;
        this.playerStanding = false;

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
        switch (routineSecondary) {
            case 0 -> updateWaitForTrigger(player);
            case 2 -> updateRising();
            case 4 -> updateExtended(player);
        }
        refreshDynamicSpawn();
    }

    /**
     * State 0: Wait for player to approach within trigger distance.
     */
    private void updateWaitForTrigger(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // Use player centre X for distance check (matches ROM behavior)
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
     */
    private void updateRising() {
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        // Extend pillar: y -= 4, yRadius += 4
        y -= RISE_SPEED;
        yRadius += RISE_SPEED;
        riseFrame++;

        // Update mapping frame based on rise progress
        if (riseFrame <= MAX_EXTENSION_FRAME) {
            mappingFrame = riseFrame;
        }

        if (riseFrame >= MAX_EXTENSION_FRAME) {
            // Fully extended, transition to extended state
            routineSecondary = 4;
        }

        delayCounter = RISE_DELAY;
    }

    /**
     * State 4: Extended - wait for player to stand, then launch.
     */
    private void updateExtended(AbstractPlayableSprite player) {
        // The launch is triggered in onSolidContact when player stands on pillar
        // Nothing to do here in the update loop
    }

    /**
     * Release the player and destroy the pillar with debris.
     * Note: The original ROM does NOT launch the player upward - it simply
     * sets them to rolling/in-air state and lets them free-fall.
     */
    private void releasePlayerAndBreak(AbstractPlayableSprite player) {
        // Set player to rolling state (bset #status.player.rolling)
        player.setRolling(true);

        // Set player to in-air state (bset #status.player.in_air)
        player.setAir(true);

        // Note: Original ROM does NOT modify y_vel - player free-falls

        // Play slow smash sound effect
        AudioManager.getInstance().playSfx(GameSound.SLOW_SMASH);

        // Spawn debris fragments
        spawnDebrisFragments();

        // Destroy this pillar
        setDestroyed(true);

        LOGGER.fine(() -> String.format("Rising pillar at (%d,%d) launched player", spawn.x(), spawn.y()));
    }

    /**
     * Spawn 14 debris fragment objects with scripted velocities.
     */
    private void spawnDebrisFragments() {
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager == null) {
            return;
        }

        for (int[] data : DEBRIS_DATA) {
            int frameIndex = 7 + data[0]; // Frames 7-13 are debris
            int velX = data[1];
            int velY = data[2];
            int delay = data[3];

            RisingPillarDebrisInstance debris = new RisingPillarDebrisInstance(
                    x, y, velX, velY, frameIndex, delay);
            objectManager.addDynamicObject(debris);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ensureMappingsLoaded();
        if (mappings == null || mappings.isEmpty()) {
            appendDebug(commands);
            return;
        }

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
            SpritePieceRenderer.renderPieces(
                    List.of(piece),
                    x,
                    y,
                    0,
                    PALETTE_INDEX,
                    hFlip,
                    vFlip,
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
        if (routineSecondary != 4) {
            return;
        }

        // If player is standing on extended pillar, launch them
        if (contact.standing()) {
            releasePlayerAndBreak(player);
        }
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
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
                frameAddr += 2; // skip 2 bytes
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
     * Inner class for debris fragments that fly apart when the pillar launches the player.
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
        private final int frameIndex;
        private boolean active;

        public RisingPillarDebrisInstance(int x, int y, int velX, int velY, int frameIndex, int delay) {
            super(new ObjectSpawn(x, y, 0x2B, 0, 0, false, 0), "PillarDebris");
            this.currentX = x;
            this.currentY = y;
            this.subX = x << 8;
            this.subY = y << 8;
            this.velX = velX;
            this.velY = velY;
            this.frameIndex = frameIndex;
            this.delay = delay;
            this.active = (delay == 0);
        }

        @Override
        public void update(int frameCounter, AbstractPlayableSprite player) {
            if (isDestroyed()) {
                return;
            }

            // Handle spawn delay
            if (!active) {
                delay--;
                if (delay <= 0) {
                    active = true;
                }
                return;
            }

            // Apply gravity
            velY += GRAVITY;

            // Update position (8.8 fixed point)
            subX += velX;
            subY += velY;
            currentX = subX >> 8;
            currentY = subY >> 8;

            // Check if off-screen
            int cameraY = Camera.getInstance().getY();
            int screenHeight = 224;
            if (currentY > cameraY + screenHeight + 32) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || !active) {
                return;
            }

            ensureMappingsLoaded();
            if (mappings == null || frameIndex >= mappings.size()) {
                return;
            }

            SpriteMappingFrame mapping = mappings.get(frameIndex);
            if (mapping == null || mapping.pieces().isEmpty()) {
                return;
            }

            GraphicsManager graphicsManager = GraphicsManager.getInstance();
            List<SpriteMappingPiece> pieces = mapping.pieces();
            for (int i = pieces.size() - 1; i >= 0; i--) {
                SpriteMappingPiece piece = pieces.get(i);
                SpritePieceRenderer.renderPieces(
                        List.of(piece),
                        currentX,
                        currentY,
                        0,
                        PALETTE_INDEX,
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
                            descIndex |= (paletteIndex & 0x3) << 13;
                            graphicsManager.renderPattern(new PatternDesc(descIndex), drawX, drawY);
                        });
            }
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(4);
        }
    }
}
