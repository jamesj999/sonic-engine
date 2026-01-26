package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.game.GameServices;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.debug.DebugOverlayManager;
import uk.co.jamesj999.sonic.debug.DebugOverlayToggle;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.PatternDesc;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.MultiPieceSolidProvider;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.SolidContact;
import uk.co.jamesj999.sonic.level.objects.SolidObjectListener;
import uk.co.jamesj999.sonic.level.objects.SolidObjectParams;
import uk.co.jamesj999.sonic.level.render.SpriteMappingFrame;
import uk.co.jamesj999.sonic.level.render.SpriteMappingPiece;
import uk.co.jamesj999.sonic.level.render.SpritePieceRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x83 - Rotating Platforms from ARZ (Aquatic Ruin Zone).
 * <p>
 * Three platforms arranged 120 degrees apart that orbit around a central point,
 * connected by chain links. The platforms provide top-solid collision surfaces
 * that the player can stand on and ride.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 56894-57127 (Obj83 code)
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Bits 4-7: Rotation speed (masked, then shifted left 3)</li>
 *   <li>Subtype 0x10: speed = 0x10 &lt;&lt; 3 = 0x80 = 128 (per frame, added to 16-bit angle)</li>
 * </ul>
 * <p>
 * <b>Angle accumulation:</b>
 * The angle is stored as a 16-bit word but only the high byte is used for sine lookup.
 * This creates smooth fractional rotation where speed is effectively speed/256 per frame.
 * With speed=128 (subtype 0x10), angle increases by 1 every 2 frames = full rotation in ~8.5 seconds.
 * <p>
 * <b>Initial angle from status flags:</b>
 * <ul>
 *   <li>Bit 0 (X flip): adds 64 to starting angle (90 degrees)</li>
 *   <li>Bit 1 (Y flip): adds 128 to starting angle (180 degrees)</li>
 * </ul>
 * <p>
 * <b>Structure:</b>
 * <ul>
 *   <li>3 platforms at full orbit radius (sin/4 ≈ 64 pixels max)</li>
 *   <li>9 chain links (3 per arm) at 1/4, 1/2, 3/4 of each arm's radius</li>
 *   <li>Uses level art tiles: $55 for platform, $51 for chains</li>
 * </ul>
 */
public class ARZRotPformsObjectInstance extends AbstractObjectInstance
        implements MultiPieceSolidProvider, SolidObjectListener {

    private static final Logger LOGGER = Logger.getLogger(ARZRotPformsObjectInstance.class.getName());

    // Constants
    private static final int NUM_PLATFORMS = 3;
    private static final int CHAINS_PER_ARM = 3;
    private static final int TOTAL_CHAIN_LINKS = NUM_PLATFORMS * CHAINS_PER_ARM;  // 9 chains
    private static final int ANGLE_120_DEGREES = 256 / 3;  // ~85.33, use 85

    // Platform collision (from disassembly: width_pixels=$20, y_radius=$08)
    // Total half-width = width_pixels + $0B = $20 + $0B = $2B (43 pixels)
    private static final int PLATFORM_HALF_WIDTH = 0x2B;  // 43 pixels
    private static final int PLATFORM_TOP_HEIGHT = 8;
    private static final int PLATFORM_BOTTOM_HEIGHT = 9;

    // Collision parameters (shared by all platforms)
    private static final SolidObjectParams PLATFORM_PARAMS =
            new SolidObjectParams(PLATFORM_HALF_WIDTH, PLATFORM_TOP_HEIGHT, PLATFORM_BOTTOM_HEIGHT);

    // ROM-accurate 256-entry sine table (values from -256 to +256)
    // Index 0 = 0 degrees, index 64 = 90 degrees, index 128 = 180 degrees, etc.
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

    // Static mapping data
    private static List<SpriteMappingFrame> mappings;
    private static boolean mappingsLoadAttempted;

    // Debug state (cached for performance)
    private static final boolean DEBUG_VIEW_ENABLED = SonicConfigurationService.getInstance()
            .getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
    private static final DebugOverlayManager OVERLAY_MANAGER = GameServices.debugOverlay();

    // Position state
    private final int initialX;
    private final int initialY;

    // Angle is stored as 16-bit word, only high byte used for sine lookup (68000 big-endian behavior)
    // This allows fractional rotation where speed is effectively speed/256 per frame
    private int angleWord;
    private final int speed;

    // Platform positions (world coordinates)
    private final int[] platformX = new int[NUM_PLATFORMS];
    private final int[] platformY = new int[NUM_PLATFORMS];

    // Chain link positions (world coordinates)
    private final int[] chainX = new int[TOTAL_CHAIN_LINKS];
    private final int[] chainY = new int[TOTAL_CHAIN_LINKS];

    public ARZRotPformsObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.initialX = spawn.x();
        this.initialY = spawn.y();

        // Speed calculation from disassembly (lines 56928-56931):
        // andi.b #$F0,d0    ; Keep upper nibble (but don't shift)
        // ext.w d0          ; Sign extend byte to word
        // asl.w #3,d0       ; Shift left 3 (multiply by 8)
        //
        // For subtype 0x10: 0x10 & 0xF0 = 0x10, ext.w = 0x0010, << 3 = 0x0080 = 128
        // The speed is added to a 16-bit angle word, but only the high byte is used for lookup.
        // This gives effective rotation of speed/256 angle units per frame.
        // With speed=128, angle increases by 1 every 2 frames = full rotation in ~8.5 seconds.
        int speedByte = spawn.subtype() & 0xF0;
        // Sign extend byte to word (Java bytes are signed, but we masked to int, need explicit sign extension)
        int speedWord = (speedByte > 127) ? (speedByte | 0xFF00) : speedByte;
        this.speed = (speedWord << 3) & 0xFFFF;

        // Initial angle from status/render flags (lines 56934-56937):
        // ror.b #2,d0       ; Rotate Y-flip and X-flip into bits 6-7
        // andi.b #$C0,d0    ; Keep only bits 6-7
        // This gives: Y-flip in bit 7 (+128), X-flip in bit 6 (+64)
        int flags = spawn.renderFlags();
        boolean xFlip = (flags & 0x01) != 0;
        boolean yFlip = (flags & 0x02) != 0;
        int initialAngle = ((yFlip ? 0x80 : 0) | (xFlip ? 0x40 : 0)) & 0xFF;
        // Store in high byte of 16-bit angle word
        this.angleWord = initialAngle << 8;

        LOGGER.fine(() -> String.format(
                "ARZRotPforms init: pos=(%d,%d), subtype=0x%02X, speed=%d, initialAngle=%d",
                initialX, initialY, spawn.subtype(), speed, initialAngle));

        // Calculate initial positions
        updatePositions();
    }

    @Override
    public int getX() {
        return initialX;
    }

    @Override
    public int getY() {
        return initialY;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (isDestroyed()) {
            return;
        }

        // Update rotation angle (16-bit accumulation)
        // Disassembly line 56997: add.w d0,angle(a0)
        angleWord = (angleWord + speed) & 0xFFFF;

        // Recalculate all positions
        updatePositions();
    }

    /**
     * Update positions for all platforms and chain links based on current angle.
     * <p>
     * The disassembly uses 16.16 fixed-point math:
     * 1. CalcSine returns sin/cos as 16.16 fixed point (integer part in high word)
     * 2. Values are shifted right 4 bits (asr.l #4) to scale the orbit radius
     * 3. Positions are accumulated for chain links at 1/4, 1/2, 3/4 of full radius
     * 4. Platforms are positioned at full radius (4× the base step)
     */
    private void updatePositions() {
        // Extract effective angle (high byte of 16-bit word, 68000 big-endian)
        int effectiveAngle = (angleWord >> 8) & 0xFF;

        // Calculate positions for each arm (3 arms at 120 degrees apart)
        int chainIndex = 0;
        for (int arm = 0; arm < NUM_PLATFORMS; arm++) {
            // Calculate angle for this arm
            // Disassembly: angle, angle+256/3, angle-256/3
            int armAngle;
            if (arm == 0) {
                armAngle = effectiveAngle;
            } else if (arm == 1) {
                armAngle = (effectiveAngle + ANGLE_120_DEGREES) & 0xFF;
            } else {
                armAngle = (effectiveAngle - ANGLE_120_DEGREES) & 0xFF;
            }

            // Get sin/cos for this arm's angle
            int sin = calcSine(armAngle);
            int cos = calcCosine(armAngle);

            // After CalcSine and asr.l #4, the values are scaled:
            // sin/16 and cos/16 in integer form (max ±16 pixels per step)
            // The disassembly accumulates these: 1×, 2×, 3× for chains, 4× for platform
            int sinStep = sin >> 4;  // sin/16
            int cosStep = cos >> 4;  // cos/16

            // Position chain links at 1×, 2×, 3× of the step (1/4, 1/2, 3/4 radius)
            for (int c = 0; c < CHAINS_PER_ARM; c++) {
                int multiplier = c + 1;  // 1, 2, 3
                chainX[chainIndex] = initialX + (cosStep * multiplier);
                chainY[chainIndex] = initialY + (sinStep * multiplier);
                chainIndex++;
            }

            // Position platform at 4× the step (full radius)
            platformX[arm] = initialX + (cosStep * 4);
            platformY[arm] = initialY + (sinStep * 4);
        }
    }

    /**
     * Calculate sine value for angle (0-255 maps to 0-360 degrees).
     * Returns value scaled to -256 to +256.
     */
    private int calcSine(int angle) {
        return SINE_TABLE[angle & 0xFF];
    }

    /**
     * Calculate cosine value for angle (0-255 maps to 0-360 degrees).
     * Cosine = sine(angle + 64) where 64 = 90 degrees.
     */
    private int calcCosine(int angle) {
        return SINE_TABLE[(angle + 0x40) & 0xFF];
    }

    // MultiPieceSolidProvider implementation

    @Override
    public int getPieceCount() {
        return NUM_PLATFORMS;
    }

    @Override
    public int getPieceX(int pieceIndex) {
        if (pieceIndex >= 0 && pieceIndex < NUM_PLATFORMS) {
            return platformX[pieceIndex];
        }
        return initialX;
    }

    @Override
    public int getPieceY(int pieceIndex) {
        if (pieceIndex >= 0 && pieceIndex < NUM_PLATFORMS) {
            return platformY[pieceIndex];
        }
        return initialY;
    }

    @Override
    public SolidObjectParams getPieceParams(int pieceIndex) {
        return PLATFORM_PARAMS;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return PLATFORM_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;  // Platforms only solid from top
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
    }

    @Override
    public void onPieceContact(int pieceIndex, AbstractPlayableSprite player,
                               SolidContact contact, int frameCounter) {
        // No special handling needed for piece contact
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // No special handling needed for solid contact
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ensureMappingsLoaded();

        // Draw debug collision boxes when F1 debug view is enabled
        if (isDebugViewEnabled()) {
            appendDebug(commands);
        }

        if (mappings == null || mappings.isEmpty()) {
            return;
        }

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        // Render chain links first (behind platforms)
        if (mappings.size() > 1) {
            SpriteMappingFrame chainFrame = mappings.get(1);  // Frame 1 = chain link
            if (chainFrame != null && !chainFrame.pieces().isEmpty()) {
                for (int i = 0; i < TOTAL_CHAIN_LINKS; i++) {
                    renderPieces(graphicsManager, chainFrame.pieces(), chainX[i], chainY[i], hFlip, vFlip);
                }
            }
        }

        // Render platforms (in front of chains)
        if (mappings.size() > 0) {
            SpriteMappingFrame platformFrame = mappings.get(0);  // Frame 0 = platform
            if (platformFrame != null && !platformFrame.pieces().isEmpty()) {
                for (int i = 0; i < NUM_PLATFORMS; i++) {
                    renderPieces(graphicsManager, platformFrame.pieces(), platformX[i], platformY[i], hFlip, vFlip);
                }
            }
        }
    }

    private void renderPieces(GraphicsManager graphicsManager, List<SpriteMappingPiece> pieces,
                              int drawX, int drawY, boolean hFlip, boolean vFlip) {
        SpritePieceRenderer.renderPieces(
                pieces,
                drawX,
                drawY,
                0,  // Base pattern index (level art starts at 0)
                -1, // Use palette from piece
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
                    descIndex |= (paletteIndex & 0x3) << 13;
                    graphicsManager.renderPattern(new PatternDesc(descIndex), px, py);
                });
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);  // Priority 4 from disassembly
    }

    private static void ensureMappingsLoaded() {
        if (mappingsLoadAttempted) {
            return;
        }
        mappingsLoadAttempted = true;

        LevelManager manager = LevelManager.getInstance();
        if (manager == null || manager.getGame() == null) {
            return;
        }

        try {
            Rom rom = manager.getGame().getRom();
            RomByteReader reader = RomByteReader.fromRom(rom);
            mappings = loadMappingFrames(reader, Sonic2Constants.MAP_UNC_OBJ83_ADDR);
            LOGGER.fine("Loaded " + mappings.size() + " Obj83 mapping frames");
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Failed to load Obj83 mappings: " + e.getMessage());
        }
    }

    /**
     * Load mapping frames from ROM.
     * Format: offset table (word per frame) followed by frame data.
     * Each frame: piece count (word), then 8 bytes per piece.
     */
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

                frameAddr += 2;  // Skip 2P tile word

                int xOffset = (short) reader.readU16BE(frameAddr);
                frameAddr += 2;

                int widthTiles = ((size >> 2) & 0x3) + 1;
                int heightTiles = (size & 0x3) + 1;

                int tileIndex = tileWord & 0x7FF;
                boolean pieceHFlip = (tileWord & 0x800) != 0;
                boolean pieceVFlip = (tileWord & 0x1000) != 0;
                int paletteIndex = (tileWord >> 13) & 0x3;

                pieces.add(new SpriteMappingPiece(
                        xOffset,
                        yOffset,
                        widthTiles,
                        heightTiles,
                        tileIndex,
                        pieceHFlip,
                        pieceVFlip,
                        paletteIndex));
            }

            frames.add(new SpriteMappingFrame(pieces));
        }

        return frames;
    }

    private void appendDebug(List<GLCommand> commands) {
        // Draw center point (yellow)
        appendLine(commands, initialX - 4, initialY, initialX + 4, initialY, 1.0f, 1.0f, 0.0f);
        appendLine(commands, initialX, initialY - 4, initialX, initialY + 4, 1.0f, 1.0f, 0.0f);

        // Draw platform collision boxes
        for (int i = 0; i < NUM_PLATFORMS; i++) {
            int left = platformX[i] - PLATFORM_HALF_WIDTH;
            int right = platformX[i] + PLATFORM_HALF_WIDTH;
            int top = platformY[i] - PLATFORM_TOP_HEIGHT;
            int bottom = platformY[i] + PLATFORM_BOTTOM_HEIGHT;

            // Green for platforms
            appendLine(commands, left, top, right, top, 0.0f, 1.0f, 0.0f);  // Top edge (standing surface)
            appendLine(commands, right, top, right, bottom, 0.3f, 0.7f, 0.3f);
            appendLine(commands, right, bottom, left, bottom, 0.3f, 0.7f, 0.3f);
            appendLine(commands, left, bottom, left, top, 0.3f, 0.7f, 0.3f);

            // Center cross in red
            appendLine(commands, platformX[i] - 2, platformY[i], platformX[i] + 2, platformY[i], 1.0f, 0.0f, 0.0f);
            appendLine(commands, platformX[i], platformY[i] - 2, platformX[i], platformY[i] + 2, 1.0f, 0.0f, 0.0f);
        }

        // Draw chain link positions (small cyan crosses)
        for (int i = 0; i < TOTAL_CHAIN_LINKS; i++) {
            appendLine(commands, chainX[i] - 2, chainY[i], chainX[i] + 2, chainY[i], 0.0f, 1.0f, 1.0f);
            appendLine(commands, chainX[i], chainY[i] - 2, chainX[i], chainY[i] + 2, 0.0f, 1.0f, 1.0f);
        }
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2, float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    private boolean isDebugViewEnabled() {
        return DEBUG_VIEW_ENABLED && OVERLAY_MANAGER.isEnabled(DebugOverlayToggle.OVERLAY);
    }
}
