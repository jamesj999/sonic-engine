package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.sonic2.OscillationManager;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.PatternDesc;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
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
 * Object 18 - Stationary floating platform (EHZ/ARZ/HTZ).
 * Implements movement behaviors and rendering from the disassembly.
 */
public class ARZPlatformObjectInstance extends AbstractObjectInstance implements SolidObjectProvider, SolidObjectListener {
    private static final Logger LOGGER = Logger.getLogger(ARZPlatformObjectInstance.class.getName());

    private static final int[] WIDTH_PIXELS = {
            0x20, 0x20, 0x20, 0x40, 0x30
    };
    private static final int[] FRAME_INDEX = { 0, 1, 2, 3, 4 };
    private static final int HALF_HEIGHT = 8;
    private static final int SOLID_EXTRA_WIDTH = 0x0B;
    private static final int SOLID_Y_RADIUS_DEFAULT = 0x30;
    private static final int SOLID_Y_RADIUS_ARZ = 0x28;
    private static final int PALETTE_INDEX = 2;

    private static final int FALL_GRAVITY = 0x38;
    private static final int FALL_RELEASE_DELAY = 0x1E;
    private static final int FALL_START_DELAY = 0x20;
    private static final int BUTTON_DELAY = 60;
    private static final int OFFSCREEN_Y_MARGIN = 0x120;

    private static final byte[] BUTTON_VINE_TRIGGERS = new byte[16];

    private static List<SpriteMappingFrame> mappingsA;
    private static List<SpriteMappingFrame> mappingsB;
    private static boolean mappingLoadAttempted;

    private int x;
    private int y;
    private int baseX;
    private int baseY;
    private int baseYFixed;
    private int widthPixels;
    private int mappingFrame;
    private int subtype;
    private int routine;
    private int bobAngle;
    private int angle;
    private int timer;
    private int yVel;
    private int yRadius;
    private ObjectSpawn dynamicSpawn;

    private static final short[] SINE_TABLE = {
            0, 6, 12, 18, 25, 31, 37, 43, 49, 56, 62, 68, 74, 80, 86, 92,
            97, 103, 109, 115, 120, 126, 131, 136, 142, 147, 152, 157, 162, 167, 171, 176,
            181, 185, 189, 193, 197, 201, 205, 209, 212, 216, 219, 222, 225, 228, 231, 234,
            236, 238, 241, 243, 244, 246, 248, 249, 251, 252, 253, 254, 254, 255, 255, 255,
            256, 255, 255, 255, 254, 254, 253, 252, 251, 249, 248, 246, 244, 243, 241, 238,
            236, 234, 231, 228, 225, 222, 219, 216, 212, 209, 205, 201, 197, 193, 189, 185,
            181, 176, 171, 167, 162, 157, 152, 147, 142, 136, 131, 126, 120, 115, 109, 103,
            97, 92, 86, 80, 74, 68, 62, 56, 49, 43, 37, 31, 25, 18, 12, 6,
            0, -6, -12, -18, -25, -31, -37, -43, -49, -56, -62, -68, -74, -80, -86, -92,
            -97, -103, -109, -117, -120, -126, -131, -136, -142, -147, -152, -157, -162, -167, -171, -176,
            -181, -185, -189, -193, -197, -201, -205, -209, -212, -216, -219, -222, -225, -228, -231, -234,
            -236, -238, -241, -243, -244, -246, -248, -249, -251, -252, -253, -254, -254, -255, -255, -255,
            -256, -255, -255, -255, -254, -254, -253, -252, -251, -249, -248, -246, -244, -243, -241, -238,
            -236, -234, -231, -228, -225, -222, -219, -216, -212, -209, -205, -201, -197, -193, -189, -185,
            -181, -176, -171, -167, -162, -157, -152, -147, -142, -136, -131, -126, -120, -117, -109, -103,
            -97, -92, -86, -80, -74, -68, -62, -56, -49, -43, -37, -31, -25, -18, -12, -6,
            0, 6, 12, 18, 25, 31, 37, 43, 49, 56, 62, 68, 74, 80, 86, 92,
            97, 103, 109, 115, 120, 126, 131, 136, 142, 147, 152, 157, 162, 167, 171, 176,
            181, 185, 189, 193, 197, 201, 205, 209, 212, 216, 219, 222, 225, 228, 231, 234,
            236, 238, 241, 243, 244, 246, 248, 249, 251, 252, 253, 254, 254, 255, 255, 255
    };

    public ARZPlatformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        init();
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
        OscillationManager.update(frameCounter);
        x = baseX;

        boolean standing = isStanding();
        if (routine == 2 || routine == 8) {
            updateBobAngle(standing);
        }

        boolean updateAngle = applyBehaviour(player, standing);
        if (updateAngle) {
            angle = OscillationManager.getByte(0x18);
        }

        applySineBob();
        refreshDynamicSpawn();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        List<SpriteMappingFrame> mappings = resolveMappings();
        if (mappings == null || mappings.isEmpty()) {
            appendDebug(commands);
            return;
        }

        int frame = mappingFrame;
        if (frame < 0) {
            frame = 0;
        }
        if (frame >= mappings.size()) {
            frame = mappings.size() - 1;
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
    public SolidObjectParams getSolidParams() {
        if (routine == 8) {
            int halfWidth = widthPixels + SOLID_EXTRA_WIDTH;
            int airHalfHeight = Math.max(1, yRadius);
            int groundHalfHeight = Math.max(1, yRadius + 1);
            return new SolidObjectParams(halfWidth, airHalfHeight, groundHalfHeight);
        }
        return new SolidObjectParams(widthPixels, HALF_HEIGHT, HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return routine != 8;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Platform state is driven via SolidObjectManager standing checks.
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
    }

    private void init() {
        routine = 2;
        int initIndex = (spawn.subtype() >> 3) & 0x0E;
        initIndex /= 2;
        if (initIndex < 0) {
            initIndex = 0;
        }
        if (initIndex >= WIDTH_PIXELS.length) {
            initIndex = WIDTH_PIXELS.length - 1;
        }
        widthPixels = WIDTH_PIXELS[initIndex];
        mappingFrame = FRAME_INDEX[initIndex];

        baseX = spawn.x();
        baseY = spawn.y();
        baseYFixed = baseY << 8;
        x = baseX;
        y = baseY;
        angle = 0x80;

        subtype = spawn.subtype();
        if ((subtype & 0x80) != 0) {
            routine = 8;
            subtype &= 0x0F;
            yRadius = isAquaticRuin() ? SOLID_Y_RADIUS_ARZ : SOLID_Y_RADIUS_DEFAULT;
        } else {
            subtype &= 0x0F;
            yRadius = HALF_HEIGHT;
        }

        refreshDynamicSpawn();
    }

    private boolean applyBehaviour(AbstractPlayableSprite player, boolean standing) {
        int behaviour = subtype & 0x0F;
        switch (behaviour) {
            case 0, 9 -> {
                return false;
            }
            case 1 -> {
                x = baseX + signedByte(angle - 0x40);
                return true;
            }
            case 2 -> {
                setBaseY(baseY + signedByte(angle - 0x40));
                return true;
            }
            case 3 -> {
                handleFallTrigger(standing);
                return false;
            }
            case 4 -> {
                handleFalling(player);
                return false;
            }
            case 5 -> {
                x = baseX + signedByte(0x40 - angle);
                return true;
            }
            case 6 -> {
                setBaseY(baseY + signedByte(0x40 - angle));
                return true;
            }
            case 7 -> {
                handleButtonTrigger();
                return false;
            }
            case 8 -> {
                handleRise();
                return false;
            }
            case 10 -> {
                int offset = signedByte(angle - 0x40) >> 1;
                setBaseY(baseY + offset);
                return true;
            }
            case 11 -> {
                int offset = signedByte(0x40 - angle) >> 1;
                setBaseY(baseY + offset);
                return true;
            }
            case 12 -> {
                int osc = OscillationManager.getByte(0x0C);
                setBaseY(baseY + signedByte(osc - 0x30));
                return true;
            }
            case 13 -> {
                int osc = OscillationManager.getByte(0x0C);
                setBaseY(baseY + signedByte(0x30 - osc));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void handleFallTrigger(boolean standing) {
        if (timer == 0) {
            if (standing) {
                timer = FALL_RELEASE_DELAY;
            }
            return;
        }
        timer--;
        if (timer == 0) {
            timer = FALL_START_DELAY;
            subtype = (subtype + 1) & 0xFF;
        }
    }

    private void handleFalling(AbstractPlayableSprite player) {
        if (timer != 0) {
            timer--;
            if (timer == 0) {
                if (player != null && isStanding()) {
                    releasePlayer(player);
                }
                routine = 6;
            }
        }

        baseYFixed += yVel;
        yVel += FALL_GRAVITY;

        int cameraMaxY = Camera.getInstance().getMaxY();
        if ((baseYFixed >> 8) > cameraMaxY + OFFSCREEN_Y_MARGIN) {
            setDestroyed(true);
        }
    }

    private void releasePlayer(AbstractPlayableSprite player) {
        player.setAir(true);
        player.setYSpeed((short) yVel);
    }

    private void handleButtonTrigger() {
        int triggerIndex = (subtype >> 4) & 0x0F;
        if (timer == 0) {
            if (triggerIndex >= 0 && triggerIndex < BUTTON_VINE_TRIGGERS.length
                    && BUTTON_VINE_TRIGGERS[triggerIndex] != 0) {
                timer = BUTTON_DELAY;
            }
            return;
        }
        timer--;
        if (timer == 0) {
            subtype = (subtype + 1) & 0xFF;
        }
    }

    private void handleRise() {
        baseYFixed -= (2 << 8);
        if ((baseYFixed >> 8) == (baseY - 0x200)) {
            subtype = 0;
        }
    }

    private void updateBobAngle(boolean standing) {
        if (!standing) {
            if (bobAngle > 0) {
                bobAngle = Math.max(0, bobAngle - 4);
            }
            return;
        }
        if (bobAngle < 0x40) {
            bobAngle = Math.min(0x40, bobAngle + 4);
        }
    }

    private void applySineBob() {
        int sin = calcSine(bobAngle);
        int offset = (sin * 0x400) >> 16;
        y = (baseYFixed >> 8) + offset;
    }

    private int calcSine(int angle) {
        return SINE_TABLE[angle & 0xFF];
    }

    private int signedByte(int value) {
        return (byte) value;
    }

    private void setBaseY(int value) {
        baseYFixed = value << 8;
    }

    private boolean isStanding() {
        LevelManager manager = LevelManager.getInstance();
        if (manager == null || manager.getSolidObjectManager() == null) {
            return false;
        }
        return manager.getSolidObjectManager().isRidingObject(this);
    }

    private boolean isAquaticRuin() {
        LevelManager manager = LevelManager.getInstance();
        return manager != null && manager.getCurrentZone() == Sonic2Constants.ZONE_AQUATIC_RUIN;
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

    private List<SpriteMappingFrame> resolveMappings() {
        ensureMappingsLoaded();
        return isAquaticRuin() ? mappingsB : mappingsA;
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
            mappingsA = loadMappingFrames(reader, Sonic2Constants.MAP_UNC_OBJ18_A_ADDR);
            mappingsB = loadMappingFrames(reader, Sonic2Constants.MAP_UNC_OBJ18_B_ADDR);
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load Obj18 mappings: " + e.getMessage());
        }
    }

    private static List<SpriteMappingFrame> loadMappingFrames(RomByteReader reader, int mappingAddr) {
        int frameCount = reader.readU16BE(mappingAddr);
        List<SpriteMappingFrame> frames = new ArrayList<>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            int frameAddr = reader.readPointer16(mappingAddr, i);
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
                frameAddr += 2; // Skip 2P tile word
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
        int halfWidth = widthPixels;
        int halfHeight = routine == 8 ? yRadius : HALF_HEIGHT;
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
                0.35f, 0.7f, 1.0f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.35f, 0.7f, 1.0f, x2, y2, 0, 0));
    }
}
