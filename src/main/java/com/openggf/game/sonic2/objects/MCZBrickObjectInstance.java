package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.LazyMappingHolder;

import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Object 0x75 - MCZ Brick / Spike Ball from Mystic Cave Zone.
 * <p>
 * This is a dual-purpose object with three subtypes:
 * <ul>
 *   <li><b>0x0F (Brick)</b>: Static solid block - player can stand on it</li>
 *   <li><b>0x16 (Small Spike Ball)</b>: 22-segment rotating spike chain - damages player</li>
 *   <li><b>0x17 (Large Spike Ball)</b>: 23-segment rotating spike chain - damages player</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 55036-55198 (Obj75)
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Lower nibble (0x0F): Mode/chain count (0x0F = brick, else chain segment count)</li>
 *   <li>Upper nibble (0xF0): Rotation speed (shifted left 3 after sign extension)</li>
 * </ul>
 * <p>
 * <b>Initial angle from render flags:</b>
 * <ul>
 *   <li>Bit 0 (X flip): adds 64 to starting angle (90 degrees)</li>
 *   <li>Bit 1 (Y flip): adds 128 to starting angle (180 degrees)</li>
 * </ul>
 */
public class MCZBrickObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, TouchResponseProvider, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(MCZBrickObjectInstance.class.getName());

    // Mode constants
    private static final int BRICK_SUBTYPE = 0x0F;

    // Brick collision dimensions (from disassembly lines 55183-55187)
    // d1 = width_pixels + 0x0B = 0x10 + 0x0B = 0x1B (27 pixels half-width)
    // d2 = 0x10 (16 pixels - top height)
    // d3 = 0x11 (17 pixels - bottom height)
    private static final int BRICK_HALF_WIDTH = 0x1B;    // 27 pixels
    private static final int BRICK_TOP_HEIGHT = 0x10;    // 16 pixels
    private static final int BRICK_BOTTOM_HEIGHT = 0x11; // 17 pixels

    private static final SolidObjectParams BRICK_PARAMS =
            SolidObjectParams.of(BRICK_HALF_WIDTH, BRICK_TOP_HEIGHT, BRICK_BOTTOM_HEIGHT);

    // Spike ball collision flags (from disassembly line 55092)
    // $9A = High nibble 0x90 (HURT category) + Low nibble 0x0A (size index)
    private static final int SPIKE_BALL_COLLISION_FLAGS = 0x9A;

    // Static mapping data
    private static final LazyMappingHolder MAPPINGS = new LazyMappingHolder();

    // Mode
    private enum Mode { BRICK, SPIKE_BALL }
    private Mode mode;

    // Position state
    private int initialX;
    private int initialY;

    // Spike ball state (only used in SPIKE_BALL mode)
    private int chainCount;
    private int speed;
    private int angleWord;
    private int[] chainX;
    private int[] chainY;
    private int spikeBallX;
    private int spikeBallY;
    private MCZBrickDisplayChild displayChild;

    public MCZBrickObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.initialX = spawn.x();
        this.initialY = spawn.y();

        int subtype = spawn.subtype();
        int lowerNibble = subtype & 0x0F;

        // Determine mode from lower nibble
        if (lowerNibble == BRICK_SUBTYPE) {
            this.mode = Mode.BRICK;
            this.chainCount = 0;
            this.speed = 0;
            this.spikeBallX = initialX;
            this.spikeBallY = initialY;
        } else {
            this.mode = Mode.SPIKE_BALL;

            // Validate chain count - ROM only uses 0x16 (22) and 0x17 (23) for spike balls
            // Invalid values (0x00-0x0E) would cause ROM to loop 65536 times (dbf underflow)
            if (lowerNibble < 1) {
                LOGGER.warning(() -> String.format(
                        "Invalid MCZ Brick chain count %d at (%d,%d), defaulting to 22",
                        lowerNibble, spawn.x(), spawn.y()));
                this.chainCount = 22;
            } else {
                this.chainCount = lowerNibble;
            }

            // Speed calculation from disassembly (lines 55073-55076):
            // andi.b #$F0,d0    ; Keep upper nibble
            // ext.w d0          ; Sign extend byte to word
            // asl.w #3,d0       ; Shift left 3 (multiply by 8)
            int speedByte = subtype & 0xF0;
            int speedWord = (speedByte > 127) ? (speedByte | 0xFF00) : speedByte;
            this.speed = (speedWord << 3) & 0xFFFF;

            // Initial angle from render flags (lines 55079-55082):
            // ror.b #2,d0       ; Rotate Y-flip and X-flip into bits 6-7
            // andi.b #$C0,d0    ; Keep only bits 6-7
            // This gives: Y-flip in bit 7 (+128), X-flip in bit 6 (+64)
            int flags = spawn.renderFlags();
            boolean xFlip = (flags & 0x01) != 0;
            boolean yFlip = (flags & 0x02) != 0;
            int initialAngle = ((yFlip ? 0x80 : 0) | (xFlip ? 0x40 : 0)) & 0xFF;
            this.angleWord = initialAngle << 8;

            // Initialize chain position arrays
            this.chainX = new int[chainCount];
            this.chainY = new int[chainCount];

            // Calculate initial positions
            updateRotation();
        }

        LOGGER.fine(() -> String.format(
                "MCZBrick init: pos=(%d,%d), subtype=0x%02X, mode=%s, chainCount=%d, speed=%d",
                initialX, initialY, subtype, mode, chainCount, speed));
    }

    @Override
    public MCZBrickObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MCZBrickObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public int getX() {
        // For spike ball mode, return the spike ball head position for collision
        if (mode == Mode.SPIKE_BALL) {
            return spikeBallX;
        }
        return initialX;
    }

    @Override
    public int getY() {
        if (mode == Mode.SPIKE_BALL) {
            return spikeBallY;
        }
        return initialY;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return initialX;
    }

    @Override
    public ObjectSpawn getSpawn() {
        // For spike ball mode, return spawn with dynamic position for touch response collision
        if (mode == Mode.SPIKE_BALL) {
            return buildSpawnAt(spikeBallX, spikeBallY);
        }
        return spawn;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        if (mode == Mode.SPIKE_BALL) {
            ensureDisplayChild();
            // Update rotation angle (16-bit accumulation)
            angleWord = (angleWord + speed) & 0xFFFF;
            updateRotation();
        }
        // Brick mode has no update logic - it's static
    }

    @Override
    public void onUnload() {
        if (displayChild != null) {
            displayChild.setDestroyed(true);
        }
    }

    private void ensureDisplayChild() {
        if (displayChild != null) {
            return;
        }
        displayChild = spawnChild(() -> new MCZBrickDisplayChild(this));
    }

    private void attachDisplayChildForRewind(MCZBrickDisplayChild child) {
        displayChild = child;
    }

    // Returns the spike-ball brick whose swinging tip is nearest the display child's
    // captured spawn, or empty when none survives (a legitimate absent-parent state:
    // the brick was swept before capture). No {@code !isDestroyed()} filter: during a
    // rewind restore the parent's destroyed flag is applied in a later pass, so a
    // captured-but-destroyed brick is a valid relink target here.
    private static Optional<MCZBrickObjectInstance> nearestParentForRewind(RewindRecreateContext ctx) {
        var objectManager = ctx.objectManager() != null
                ? ctx.objectManager()
                : ctx.objectServices().objectManager();
        if (objectManager == null) {
            return Optional.empty();
        }
        ObjectSpawn spawn = ctx.spawn();
        return objectManager.getActiveObjects().stream()
                .filter(MCZBrickObjectInstance.class::isInstance)
                .map(MCZBrickObjectInstance.class::cast)
                .filter(parent -> parent.mode == Mode.SPIKE_BALL)
                .min((a, b) -> Integer.compare(
                        distanceFromChildSpawn(a, spawn),
                        distanceFromChildSpawn(b, spawn)));
    }

    private static int distanceFromChildSpawn(MCZBrickObjectInstance parent, ObjectSpawn spawn) {
        return Math.abs(parent.displayChildX() - spawn.x())
                + Math.abs(parent.displayChildY() - spawn.y());
    }

    private int displayChildX() {
        return chainCount > 0 ? chainX[chainCount - 1] : spikeBallX;
    }

    private int displayChildY() {
        return chainCount > 0 ? chainY[chainCount - 1] : spikeBallY;
    }

    /**
     * Update positions for all chain segments and spike ball head based on current angle.
     * <p>
     * The disassembly uses 16.16 fixed-point math (lines 55141-55152):
     * <pre>
     *   movem.l d4-d5,-(sp)  ; Save accumulators
     *   swap    d4           ; Extract integer part FIRST
     *   swap    d5
     *   add.w   d2,d4        ; Add center position
     *   add.w   d3,d5
     *   move.w  d5,(a2)+     ; STORE position
     *   move.w  d4,(a2)+
     *   movem.l (sp)+,d4-d5  ; Restore accumulators
     *   add.l   d0,d4        ; THEN accumulate for next iteration
     *   add.l   d1,d5
     * </pre>
     * <p>
     * The ROM stores position BEFORE accumulating, so:
     * - Chain segment 0 is at center (0 steps)
     * - Chain segment 1 is at 1 step from center
     * - Spike ball head is at chainCount steps
     */
    private void updateRotation() {
        // Extract effective angle (high byte of 16-bit word)
        int effectiveAngle = (angleWord >> 8) & 0xFF;

        // Get sin/cos (values range from -256 to +256)
        int sin = TrigLookupTable.sinHex(effectiveAngle);
        int cos = TrigLookupTable.cosHex(effectiveAngle);

        // ROM uses 16.16 fixed-point accumulation:
        // swap d0; asr.l #4,d0  =>  ((sin << 16) >> 4) = sin << 12
        long sinStep = ((long) sin << 16) >> 4;
        long cosStep = ((long) cos << 16) >> 4;

        long accX = 0;
        long accY = 0;

        // Position chain segments: store position FIRST, then accumulate (ROM order)
        for (int i = 0; i < chainCount; i++) {
            // Position from current accumulated value (swap d4/d5 extracts high word)
            chainX[i] = initialX + (int) (accX >> 16);
            chainY[i] = initialY + (int) (accY >> 16);

            // Then accumulate for next iteration
            accX += cosStep;  // X uses cosine (d1 in ROM)
            accY += sinStep;  // Y uses sine (d0 in ROM)
        }

        // Spike ball head at end of chain (lines 55154-55159)
        // Final position after all chain link accumulations
        spikeBallX = initialX + (int) (accX >> 16);
        spikeBallY = initialY + (int) (accY >> 16);
    }

    // SolidObjectProvider implementation (brick mode only)

    @Override
    public SolidObjectParams getSolidParams() {
        if (mode == Mode.BRICK) {
            return BRICK_PARAMS;
        }
        return null;  // Spike ball has no solid collision
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;  // Brick is fully solid
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return mode == Mode.BRICK && !isDestroyed();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No special handling needed for brick contact
    }

    // TouchResponseProvider implementation (spike ball mode only)

    @Override
    public int getCollisionFlags() {
        if (mode == Mode.SPIKE_BALL) {
            return SPIKE_BALL_COLLISION_FLAGS;
        }
        return 0;  // Brick doesn't use touch response
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        List<SpriteMappingFrame> mappings = MAPPINGS.get(
                Sonic2Constants.MAP_UNC_OBJ75_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj75");
        if (mappings.isEmpty()) {
            return;
        }

        GraphicsManager graphicsManager = services().graphicsManager();
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        if (mode == Mode.BRICK) {
            // Render brick (frame 2)
            if (mappings.size() > 2) {
                SpriteMappingFrame brickFrame = mappings.get(2);
                if (brickFrame != null && !brickFrame.pieces().isEmpty()) {
                    renderPieces(graphicsManager, brickFrame.pieces(), initialX, initialY, hFlip, vFlip);
                }
            }
        } else {
            // Render chain segments first (frame 1 = chain link)
            if (mappings.size() > 1) {
                SpriteMappingFrame chainFrame = mappings.get(1);
                if (chainFrame != null && !chainFrame.pieces().isEmpty()) {
                    for (int i = 0; i < chainCount; i++) {
                        renderPieces(graphicsManager, chainFrame.pieces(), chainX[i], chainY[i], false, false);
                    }
                }
            }

            // Render spike ball head (frame 0)
            if (!mappings.isEmpty()) {
                SpriteMappingFrame headFrame = mappings.get(0);
                if (headFrame != null && !headFrame.pieces().isEmpty()) {
                    renderPieces(graphicsManager, headFrame.pieces(), spikeBallX, spikeBallY, false, false);
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
                1,  // art_tile palette offset: make_art_tile(ArtTile_ArtKos_LevelArt,1,0)
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
        // From disassembly: brick = priority 4, spike ball = priority 5
        return RenderPriority.clamp(mode == Mode.BRICK ? 4 : 5);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (mode == Mode.BRICK) {
            // Draw brick collision box
            int left = initialX - BRICK_HALF_WIDTH;
            int right = initialX + BRICK_HALF_WIDTH;
            int top = initialY - BRICK_TOP_HEIGHT;
            int bottom = initialY + BRICK_BOTTOM_HEIGHT;

            // Green for solid collision
            ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);
            ctx.drawLine(right, top, right, bottom, 0.0f, 1.0f, 0.0f);
            ctx.drawLine(right, bottom, left, bottom, 0.0f, 1.0f, 0.0f);
            ctx.drawLine(left, bottom, left, top, 0.0f, 1.0f, 0.0f);

            // Center cross
            ctx.drawLine(initialX - 4, initialY, initialX + 4, initialY, 1.0f, 1.0f, 0.0f);
            ctx.drawLine(initialX, initialY - 4, initialX, initialY + 4, 1.0f, 1.0f, 0.0f);
        } else {
            // Draw center point (yellow)
            ctx.drawLine(initialX - 4, initialY, initialX + 4, initialY, 1.0f, 1.0f, 0.0f);
            ctx.drawLine(initialX, initialY - 4, initialX, initialY + 4, 1.0f, 1.0f, 0.0f);

            // Draw chain segment positions (small cyan crosses)
            for (int i = 0; i < chainCount; i++) {
                ctx.drawLine(chainX[i] - 2, chainY[i], chainX[i] + 2, chainY[i], 0.0f, 1.0f, 1.0f);
                ctx.drawLine(chainX[i], chainY[i] - 2, chainX[i], chainY[i] + 2, 0.0f, 1.0f, 1.0f);
            }

            // Draw spike ball head position (red cross)
            ctx.drawLine(spikeBallX - 4, spikeBallY, spikeBallX + 4, spikeBallY, 1.0f, 0.0f, 0.0f);
            ctx.drawLine(spikeBallX, spikeBallY - 4, spikeBallX, spikeBallY + 4, 1.0f, 0.0f, 0.0f);
        }
    }

    private static final class MCZBrickDisplayChild extends AbstractObjectInstance implements RewindRecreatable {
        private final MCZBrickObjectInstance parent;
        private int x;
        private int y;

        private MCZBrickDisplayChild(MCZBrickObjectInstance parent) {
            super(new ObjectSpawn(
                    parent.displayChildX(),
                    parent.displayChildY(),
                    parent.spawn.objectId(),
                    parent.spawn.subtype(),
                    parent.spawn.renderFlags(),
                    false,
                    parent.spawn.rawYWord(),
                    parent.spawn.layoutIndex()),
                    "MCZBrickDisplayChild");
            this.parent = parent;
            syncFromParent();
        }

        @Override
        public MCZBrickDisplayChild recreateForRewind(RewindRecreateContext ctx) {
            // Obj75 display child of a spike-ball brick. If the parent was swept
            // before capture there is no assembly owner to relink to, so drop the
            // child (its live update self-expires with a dead parent) rather than
            // throw. nearestParentForRewind matches on the swinging spike-ball tip
            // (the child's captured spawn), so it keeps its own distance metric.
            return nearestParentForRewind(ctx)
                    .map(parent -> {
                        MCZBrickDisplayChild child = new MCZBrickDisplayChild(parent);
                        parent.attachDisplayChildForRewind(child);
                        return child;
                    })
                    .orElse(null);
        }

        private void syncFromParent() {
            this.x = parent.displayChildX();
            this.y = parent.displayChildY();
            updateDynamicSpawn(x, y);
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
        public int getOutOfRangeReferenceX() {
            return parent.initialX;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
                return;
            }
            syncFromParent();
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Parent renders the full Obj75 multi-sprite assembly; this instance occupies the ROM SST child slot.
        }
    }

}
