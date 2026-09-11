package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.LazyMappingHolder;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x83 - Rotating Platforms from ARZ (Aquatic Ruin Zone).
 * <p>
 * Three platforms arranged 120 degrees apart that orbit around a central point,
 * connected by chain links. The platforms provide top-solid collision surfaces
 * that the player can stand on and ride.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 57379-57625 (Obj83 code)
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
        implements MultiPieceSolidProvider, SolidObjectListener, RewindRecreatable {

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
            SolidObjectParams.of(PLATFORM_HALF_WIDTH, PLATFORM_TOP_HEIGHT, PLATFORM_BOTTOM_HEIGHT);

    // Static mapping data
    private static final LazyMappingHolder MAPPINGS = new LazyMappingHolder();

    // Position state
    private int initialX;
    private int initialY;

    // Angle is stored as 16-bit word, only high byte used for sine lookup (68000 big-endian behavior)
    // This allows fractional rotation where speed is effectively speed/256 per frame
    private int angleWord;
    private int speed;

    // Platform positions (world coordinates)
    private final int[] platformX = new int[NUM_PLATFORMS];
    private final int[] platformY = new int[NUM_PLATFORMS];

    // Chain link positions (world coordinates)
    private final int[] chainX = new int[TOTAL_CHAIN_LINKS];
    private final int[] chainY = new int[TOTAL_CHAIN_LINKS];

    private boolean slotChildrenSpawned;
    @RewindTransient(reason = "structural Obj83 child slot link is recreated from the child's spawn and parent lookup")
    private Obj83SlotChild chainSlotChild;
    @RewindTransient(reason = "structural Obj83 child slot link is recreated from the child's spawn and parent lookup")
    private Obj83SlotChild platform2SlotChild;
    @RewindTransient(reason = "structural Obj83 child slot link is recreated from the child's spawn and parent lookup")
    private Obj83SlotChild platform3SlotChild;

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
    public ARZRotPformsObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new ARZRotPformsObjectInstance(ctx.spawn(), getName());
    }

    @Override
    protected void recreateConstructionChildrenForRewind() {
        ensureSlotChildrenSpawned();
    }

    @Override
    public int getX() {
        return platformX[0];
    }

    @Override
    public int getY() {
        return platformY[0];
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return initialX;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            expireSlotChildren();
            return;
        }

        ensureSlotChildrenSpawned();

        // Update rotation angle (16-bit accumulation)
        // Disassembly line 56997: add.w d0,angle(a0)
        angleWord = (angleWord + speed) & 0xFFFF;

        // Recalculate all positions
        updatePositions();
    }

    private void ensureSlotChildrenSpawned() {
        if (slotChildrenSpawned) {
            return;
        }

        // ROM Obj83_Init allocates three Obj83 child SST entries after the
        // parent: a chain multisprite object, then platform 2 and platform 3
        // (docs/s2disasm/s2.asm:57437-57466). The parent keeps the consolidated
        // renderer/collision model; these children preserve the ROM slot pressure
        // that later FindFreeObj calls observe.
        chainSlotChild = spawnChild(() -> new Obj83SlotChild(
                buildChildSpawn(getChainSlotX(), getChainSlotY()), this, ChildKind.CHAIN));
        platform2SlotChild = spawnChild(() -> new Obj83SlotChild(
                buildChildSpawn(platformX[1], platformY[1]), this, ChildKind.PLATFORM_2));
        platform3SlotChild = spawnChild(() -> new Obj83SlotChild(
                buildChildSpawn(platformX[2], platformY[2]), this, ChildKind.PLATFORM_3));
        slotChildrenSpawned = true;
    }

    private ObjectSpawn buildChildSpawn(int x, int y) {
        return new ObjectSpawn(
                x,
                y,
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags(),
                false,
                spawn.rawYWord());
    }

    private int getChainSlotX() {
        // ROM Obj83 writes the first chain-link coordinate to the child object;
        // the remaining links are subsprites on that child (s2.asm:57515-57522).
        return chainX[0];
    }

    private int getChainSlotY() {
        return chainY[0];
    }

    private void attachSlotChildForRewind(Obj83SlotChild child) {
        if (child == null) {
            return;
        }
        switch (child.kind) {
            case CHAIN -> chainSlotChild = child;
            case PLATFORM_2 -> platform2SlotChild = child;
            case PLATFORM_3 -> platform3SlotChild = child;
        }
        slotChildrenSpawned = chainSlotChild != null
                && platform2SlotChild != null
                && platform3SlotChild != null;
    }

    private void expireSlotChildren() {
        expireSlotChild(chainSlotChild);
        expireSlotChild(platform2SlotChild);
        expireSlotChild(platform3SlotChild);
        chainSlotChild = null;
        platform2SlotChild = null;
        platform3SlotChild = null;
    }

    private static void expireSlotChild(Obj83SlotChild child) {
        if (child != null && !child.isDestroyed()) {
            ObjectLifetimeOps.expireDynamic(child);
        }
    }

    @Override
    public void onUnload() {
        expireSlotChildren();
    }

    /**
     * Update positions for all platforms and chain links based on current angle.
     * <p>
     * The disassembly uses 16.16 fixed-point math where fractional bits accumulate:
     * <pre>
     *   swap    d0          ; Move sine to HIGH word: d0.l = 0xSSSS0000
     *   asr.l   #4,d0       ; 32-bit shift: HIGH = integer, LOW = 12-bit fractional
     *   add.l   d0,d4       ; 32-bit add preserves fractional accumulation!
     *   ...
     *   swap    d4          ; Only at END extract integer part
     * </pre>
     * <p>
     * To preserve subpixel precision, we multiply BEFORE dividing:
     * <ul>
     *   <li>ROM: {@code ((sin << 16) >> 4) * mult >> 16 = (sin * mult) >> 4}</li>
     *   <li>Our fix: {@code (sin * mult) >> 4}</li>
     * </ul>
     * This ensures small sine values (e.g., 6) contribute to pixel positions
     * instead of being truncated to zero by an early division.
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

            // Get sin/cos for this arm's angle (values range from -256 to +256)
            int sin = calcSine(armAngle);
            int cos = calcCosine(armAngle);

            // Position chain links at 1×, 2×, 3× radius
            // Multiply BEFORE dividing to preserve subpixel precision
            for (int c = 0; c < CHAINS_PER_ARM; c++) {
                int multiplier = c + 1;  // 1, 2, 3
                chainX[chainIndex] = initialX + ((cos * multiplier) >> 4);
                chainY[chainIndex] = initialY + ((sin * multiplier) >> 4);
                chainIndex++;
            }

            // Position platform at 4× radius
            platformX[arm] = initialX + ((cos * 4) >> 4);
            platformY[arm] = initialY + ((sin * 4) >> 4);
        }
    }

    /**
     * Calculate sine value for angle (0-255 maps to 0-360 degrees).
     * Returns value scaled to -256 to +256.
     */
    private int calcSine(int angle) {
        return TrigLookupTable.sinHex(angle);
    }

    /**
     * Calculate cosine value for angle (0-255 maps to 0-360 degrees).
     * Cosine = sine(angle + 64) where 64 = 90 degrees.
     */
    private int calcCosine(int angle) {
        return TrigLookupTable.cosHex(angle);
    }

    // MultiPieceSolidProvider implementation

    @Override
    public int getPieceCount() {
        // The parent Obj83 calls PlatformObject only for platform 1; platforms
        // 2 and 3 are separate routine-4 child objects (s2.asm:57570-57576,
        // 57612-57619).
        return 1;
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
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public boolean usesGroundHalfHeightForTopSolidContact() {
        // Obj83 passes d3=9 to PlatformObject (docs/s2disasm/s2.asm:57573-57576).
        return true;
    }

    @Override
    public void onPieceContact(int pieceIndex, PlayableEntity playerEntity,
                               SolidContact contact, int frameCounter) {
        // No special handling needed for piece contact
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No special handling needed for solid contact
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        List<SpriteMappingFrame> mappings = MAPPINGS.get(
                Sonic2Constants.MAP_UNC_OBJ83_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj83");
        if (mappings.isEmpty()) {
            return;
        }

        GraphicsManager graphicsManager = services().graphicsManager();
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

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw center point (yellow)
        ctx.drawLine(initialX - 4, initialY, initialX + 4, initialY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(initialX, initialY - 4, initialX, initialY + 4, 1.0f, 1.0f, 0.0f);

        // Draw platform collision boxes
        for (int i = 0; i < NUM_PLATFORMS; i++) {
            int left = platformX[i] - PLATFORM_HALF_WIDTH;
            int right = platformX[i] + PLATFORM_HALF_WIDTH;
            int top = platformY[i] - PLATFORM_TOP_HEIGHT;
            int bottom = platformY[i] + PLATFORM_BOTTOM_HEIGHT;

            // Green for platforms
            ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);  // Top edge (standing surface)
            ctx.drawLine(right, top, right, bottom, 0.3f, 0.7f, 0.3f);
            ctx.drawLine(right, bottom, left, bottom, 0.3f, 0.7f, 0.3f);
            ctx.drawLine(left, bottom, left, top, 0.3f, 0.7f, 0.3f);

            // Center cross in red
            ctx.drawLine(platformX[i] - 2, platformY[i], platformX[i] + 2, platformY[i], 1.0f, 0.0f, 0.0f);
            ctx.drawLine(platformX[i], platformY[i] - 2, platformX[i], platformY[i] + 2, 1.0f, 0.0f, 0.0f);
        }

        // Draw chain link positions (small cyan crosses)
        for (int i = 0; i < TOTAL_CHAIN_LINKS; i++) {
            ctx.drawLine(chainX[i] - 2, chainY[i], chainX[i] + 2, chainY[i], 0.0f, 1.0f, 1.0f);
            ctx.drawLine(chainX[i], chainY[i] - 2, chainX[i], chainY[i] + 2, 0.0f, 1.0f, 1.0f);
        }
    }

    private enum ChildKind {
        CHAIN,
        PLATFORM_2,
        PLATFORM_3
    }

    private static final class Obj83SlotChild extends AbstractObjectInstance
            implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {
        @RewindTransient(reason = "structural Obj83 parent link is restored by parent lookup")
        private final ARZRotPformsObjectInstance parent;
        @RewindTransient(reason = "Obj83 child role is constructor metadata preserved by recreateForRewind")
        private final ChildKind kind;

        private Obj83SlotChild(ObjectSpawn spawn, ARZRotPformsObjectInstance parent, ChildKind kind) {
            super(spawn, "ARZRotPformsSlotChild");
            this.parent = parent;
            this.kind = kind;
        }

        @Override
        public Obj83SlotChild recreateForRewind(RewindRecreateContext ctx) {
            ARZRotPformsObjectInstance restoredParent = nearestParentForRewind(ctx);
            if (restoredParent == null) {
                return null;
            }
            Obj83SlotChild child = new Obj83SlotChild(ctx.spawn(), restoredParent, kind);
            restoredParent.attachSlotChildForRewind(child);
            return child;
        }

        @Override
        public int getX() {
            return switch (kind) {
                case CHAIN -> parent.getChainSlotX();
                case PLATFORM_2 -> parent.platformX[1];
                case PLATFORM_3 -> parent.platformX[2];
            };
        }

        @Override
        public int getY() {
            return switch (kind) {
                case CHAIN -> parent.getChainSlotY();
                case PLATFORM_2 -> parent.platformY[1];
                case PLATFORM_3 -> parent.platformY[2];
            };
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        @Override
        public SolidObjectParams getSolidParams() {
            return PLATFORM_PARAMS;
        }

        @Override
        public boolean isTopSolidOnly() {
            return true;
        }

        @Override
        public boolean isSolidFor(PlayableEntity playerEntity) {
            return kind != ChildKind.CHAIN && !parent.isDestroyed();
        }

        @Override
        public boolean usesGroundHalfHeightForTopSolidContact() {
            // Routine-4 platform children pass d3=9 to PlatformObject
            // (docs/s2disasm/s2.asm:57613-57619).
            return true;
        }

        @Override
        public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
            // ROM routine-4 Obj83 children only run PlatformObject and update last_x_pos.
        }

        @Override
        public boolean usesCustomOutOfRangeCheck() {
            return true;
        }

        @Override
        public boolean isCustomOutOfRange(int cameraX) {
            return parent.isDestroyed();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Slot-pressure child only; the parent renders the full Obj83 assembly.
        }

        private static ARZRotPformsObjectInstance nearestParentForRewind(RewindRecreateContext ctx) {
            ObjectManager manager = ctx.objectManager();
            if (manager == null && ctx.objectServices() != null) {
                manager = ctx.objectServices().objectManager();
            }
            if (manager == null) {
                return null;
            }
            return manager.getActiveObjects().stream()
                    .filter(ARZRotPformsObjectInstance.class::isInstance)
                    .map(ARZRotPformsObjectInstance.class::cast)
                    .filter(parent -> !parent.isDestroyed())
                    .min((a, b) -> Integer.compare(
                            distanceFromChildSpawn(a, ctx.spawn()),
                            distanceFromChildSpawn(b, ctx.spawn())))
                    .orElse(null);
        }

        private static int distanceFromChildSpawn(ARZRotPformsObjectInstance parent, ObjectSpawn spawn) {
            return Math.abs(parent.initialX - spawn.x()) + Math.abs(parent.initialY - spawn.y());
        }
    }

}
