package uk.co.jamesj999.sonic.game.sonic2.objects;

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
import uk.co.jamesj999.sonic.physics.ObjectTerrainUtils;
import uk.co.jamesj999.sonic.physics.TerrainCheckResult;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Object 23 - Falling Pillar from ARZ.
 * <p>
 * A pillar that drops its lower section when the player gets close.
 * Consists of two parts:
 * <ul>
 *   <li>Top section (frame 0): static, solid platform at original position</li>
 *   <li>Bottom section (frame 1-2): spawned child that shakes, falls, and lands</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 51160-51294 (Obj23 code)
 * <p>
 * <b>Routine Secondary states:</b>
 * <ul>
 *   <li>0: Static top part (no child behavior)</li>
 *   <li>2: Child waiting for player proximity trigger</li>
 *   <li>4: Child shaking (8 frames)</li>
 *   <li>6: Child falling with gravity</li>
 * </ul>
 */
public class FallingPillarObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener {
    private static final Logger LOGGER = Logger.getLogger(FallingPillarObjectInstance.class.getName());

    private static final int PALETTE_INDEX = 1;
    private static final int TOP_HALF_WIDTH = 0x10;
    private static final int TOP_HALF_HEIGHT = 0x20;
    private static final int CHILD_HALF_WIDTH = 0x10;
    private static final int CHILD_HALF_HEIGHT = 0x10;
    private static final int CHILD_Y_OFFSET = 0x30;
    private static final int TRIGGER_DISTANCE = 0x80;
    private static final int SHAKE_DURATION = 8;
    private static final int GRAVITY = 0x38;
    private static final int OFFSCREEN_Y_MARGIN = 0x120;

    private static final byte[] SHAKE_OFFSETS = { 0, 1, -1, 1, 0, -1, 0, 1 };

    private static List<SpriteMappingFrame> mappings;
    private static boolean mappingLoadAttempted;

    private final boolean isChild;
    private int x;
    private int y;
    private int baseX;
    private int routineSecondary;
    private int shakeTimer;
    private int yVel;
    private int mappingFrame;
    private int yFixed;
    private ObjectSpawn dynamicSpawn;
    private FallingPillarObjectInstance childInstance;
    private boolean childSpawned;

    public FallingPillarObjectInstance(ObjectSpawn spawn, String name) {
        this(spawn, name, false, spawn.y());
    }

    private FallingPillarObjectInstance(ObjectSpawn spawn, String name, boolean isChild, int childY) {
        super(spawn, name);
        this.isChild = isChild;
        this.x = spawn.x();
        this.baseX = spawn.x();

        if (isChild) {
            this.y = childY;
            this.yFixed = childY << 8;
            this.mappingFrame = 1;
            this.routineSecondary = 2;
        } else {
            this.y = spawn.y();
            this.yFixed = spawn.y() << 8;
            this.mappingFrame = 0;
            this.routineSecondary = 0;
        }

        refreshDynamicSpawn();
    }

    public FallingPillarObjectInstance createChild() {
        int childY = spawn.y() + CHILD_Y_OFFSET;
        return new FallingPillarObjectInstance(spawn, name, true, childY);
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

    public FallingPillarObjectInstance getChildInstance() {
        return childInstance;
    }

    public boolean isChildSpawned() {
        return childSpawned;
    }

    public void markChildSpawned(FallingPillarObjectInstance child) {
        this.childInstance = child;
        this.childSpawned = true;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (!isChild && !childSpawned) {
            spawnChild();
        }
        if (isChild) {
            updateChild(player);
        }
        refreshDynamicSpawn();
    }

    private void spawnChild() {
        LevelManager manager = LevelManager.getInstance();
        if (manager == null) {
            return;
        }
        ObjectManager objectManager = manager.getObjectManager();
        if (objectManager == null) {
            return;
        }
        FallingPillarObjectInstance child = createChild();
        objectManager.addDynamicObject(child);
        markChildSpawned(child);
    }

    private void updateChild(AbstractPlayableSprite player) {
        switch (routineSecondary) {
            case 2 -> updateWaitForTrigger(player);
            case 4 -> updateShaking();
            case 6 -> updateFalling();
        }
    }

    private void updateWaitForTrigger(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        int dx = x - player.getX();
        if (dx < 0) {
            dx = -dx;
        }
        if (dx < TRIGGER_DISTANCE) {
            routineSecondary = 4;
            shakeTimer = SHAKE_DURATION;
        }
    }

    private void updateShaking() {
        shakeTimer--;
        if (shakeTimer < 0) {
            routineSecondary = 6;
            x = baseX;
            return;
        }
        int offset = SHAKE_OFFSETS[shakeTimer & 0x07];
        x = baseX + offset;
    }

    private void updateFalling() {
        yFixed += yVel;
        yVel += GRAVITY;
        y = yFixed >> 8;

        // Real-time floor check using ObjectTerrainUtils (mirrors ROM's ObjCheckFloorDist)
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(x, y, CHILD_HALF_HEIGHT);
        if (result.hasCollision()) {
            // Hit floor - snap to surface
            y = y + result.distance();
            yFixed = y << 8;
            yVel = 0;
            mappingFrame = 2;
            routineSecondary = 0;
            return;
        }

        // Off-screen cleanup
        int cameraMaxY = Camera.getInstance().getMaxY();
        if (y > cameraMaxY + OFFSCREEN_Y_MARGIN) {
            setDestroyed(true);
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
        if (isChild) {
            return new SolidObjectParams(
                    CHILD_HALF_WIDTH + 0x0B,
                    CHILD_HALF_HEIGHT,
                    CHILD_HALF_HEIGHT + 1
            );
        }
        return new SolidObjectParams(
                TOP_HALF_WIDTH + 0x0B,
                TOP_HALF_HEIGHT,
                TOP_HALF_HEIGHT + 1
        );
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
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
            mappings = loadMappingFrames(reader, Sonic2Constants.MAP_UNC_OBJ23_ADDR);
            LOGGER.fine("Loaded " + mappings.size() + " Obj23 mapping frames");
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load Obj23 mappings: " + e.getMessage());
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
                frameAddr += 2;
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
        int halfWidth = isChild ? CHILD_HALF_WIDTH : TOP_HALF_WIDTH;
        int halfHeight = isChild ? CHILD_HALF_HEIGHT : TOP_HALF_HEIGHT;
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
                0.6f, 0.4f, 0.2f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.6f, 0.4f, 0.2f, x2, y2, 0, 0));
    }
}
