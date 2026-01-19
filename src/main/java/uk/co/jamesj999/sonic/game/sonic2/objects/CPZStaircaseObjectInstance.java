package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.MultiPieceSolidProvider;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.SolidContact;
import uk.co.jamesj999.sonic.level.objects.SolidObjectListener;
import uk.co.jamesj999.sonic.level.objects.SolidObjectParams;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x78 - CPZ Staircase (Obj78)
 *
 * A multi-piece triggered elevator platform used in Chemical Plant Zone.
 * Consists of 4 platform pieces that move in a coordinated staircase pattern
 * when triggered by the player.
 *
 * State machine (subtype & 0x07):
 *   0, 4: Wait for player contact on TOP, then 30-frame countdown
 *   1, 3: Smooth upward interpolation (rise)
 *   2, 6: Wait for player contact from BOTTOM, 60-frame countdown with oscillation
 *   5, 7: Smooth downward interpolation (drop)
 *
 * Multi-piece structure:
 *   - 4 platform pieces spaced 32 pixels apart horizontally
 *   - Each piece moves to different Y offsets creating a staircase effect
 *   - Interpolation: piece[0]=100%, piece[1]=75%, piece[2]=50%, piece[3]=25%
 *
 * Shares art with Object 0x6B (CPZ Elevator/Platform).
 */
public class CPZStaircaseObjectInstance extends AbstractObjectInstance
        implements MultiPieceSolidProvider, SolidObjectListener {

    // Constants from disassembly
    private static final int NUM_PIECES = 4;
    private static final int PIECE_SPACING = 0x20;  // 32 pixels
    // Collision half-width from disassembly: width_pixels + 0x0B = 0x10 + 0x0B = 0x1B
    // This creates intentional overlap between adjacent pieces (54px collision width
    // with 32px spacing = 22px overlap), allowing smooth walking across pieces.
    private static final int PIECE_HALF_WIDTH = 0x1B;  // 27 pixels (matches original)
    private static final int PIECE_TOP_HEIGHT = 0x10;  // 16 pixels
    private static final int PIECE_BOTTOM_HEIGHT = 0x11;  // 17 pixels

    private static final int TOP_CONTACT_DELAY = 0x1E;  // 30 frames
    private static final int BOTTOM_CONTACT_DELAY = 60;  // 60 frames
    private static final int MAX_Y_OFFSET = 0x80;  // 128 pixels max travel

    // Collision parameters (shared by all pieces)
    private static final SolidObjectParams PIECE_PARAMS =
            new SolidObjectParams(PIECE_HALF_WIDTH, PIECE_TOP_HEIGHT, PIECE_BOTTOM_HEIGHT);

    // State
    private int state;  // 0-7 (subtype & 0x07)
    private int timer;
    private final int baseX;
    private final int baseY;
    private final boolean xFlip;

    // Y offsets for each piece (piece 0 is the "master", others interpolate from it)
    private final int[] yOffsets = new int[NUM_PIECES];

    // Contact tracking - uses frame numbers because solidObjectManager.update() runs
    // AFTER objectManager.update(), so contact flags are set after our update() runs.
    // We check if contact was made in the PREVIOUS frame instead.
    private int lastTopContactFrame = -2;
    private int lastBottomContactFrame = -2;

    // Dynamic spawn for position tracking
    private ObjectSpawn dynamicSpawn;

    public CPZStaircaseObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.state = spawn.subtype() & 0x07;
        this.timer = 0;

        // Initialize Y offsets based on initial state
        // States 5, 6, 7 start at the bottom (yOffset = MAX), states 0-4 start at top (yOffset = 0)
        // Subtype meanings:
        //   0x00 (state 0): Wait at top, descend on contact
        //   0x01 (state 1): Descend immediately from top
        //   0x02 (state 2): Wait at bottom (non-moving until triggered from below)
        //   0x04 (state 4): Wait at top (alternate cycle)
        //   0x05 (state 5): Ascend immediately from bottom
        //   0x06 (state 6): Wait at bottom (alternate cycle)
        //   0x07 (state 7): Ascend immediately from bottom
        int initialOffset = 0;
        if (state == 2 || state == 5 || state == 6 || state == 7) {
            // These states expect the platform to be at the bottom position
            initialOffset = MAX_Y_OFFSET;
        }
        yOffsets[0] = initialOffset;

        // Apply initial staircase interpolation
        applyStaircaseInterpolation();

        refreshDynamicSpawn();
    }

    @Override
    public int getX() {
        return baseX;
    }

    @Override
    public int getY() {
        return baseY;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return dynamicSpawn != null ? dynamicSpawn : spawn;
    }

    // MultiPieceSolidProvider implementation

    @Override
    public int getPieceCount() {
        return NUM_PIECES;
    }

    @Override
    public int getPieceX(int pieceIndex) {
        // Pieces are spaced 32 pixels apart horizontally
        // X positions always increase left-to-right, regardless of flip
        // (flip only affects which Y offset is assigned to which position)
        return baseX + (pieceIndex * PIECE_SPACING);
    }

    @Override
    public int getPieceY(int pieceIndex) {
        // Each piece has its own Y offset based on staircase interpolation
        int index = xFlip ? (NUM_PIECES - 1 - pieceIndex) : pieceIndex;
        return baseY + yOffsets[index];
    }

    @Override
    public SolidObjectParams getPieceParams(int pieceIndex) {
        return PIECE_PARAMS;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // Return params for the first piece as default
        return PIECE_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        // Original Obj78 uses full SolidObject collision (not top-solid only).
        // The overlapping collision boxes (27px half-width, 32px spacing) combined
        // with the "near vertical edge" check in SolidObject allows smooth walking
        // across adjacent pieces while still having solid sides.
        return false;
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
    }

    @Override
    public void onPieceContact(int pieceIndex, AbstractPlayableSprite player,
                               SolidContact contact, int frameCounter) {
        // Track contact from any piece by recording the frame number.
        // Since solidObjectManager.update() runs AFTER objectManager.update(),
        // contact is detected after our update() has already run this frame.
        // Our update() checks if contact was made in the PREVIOUS frame.
        if (contact.standing() || contact.touchTop()) {
            lastTopContactFrame = frameCounter;
        }
        if (contact.touchBottom()) {
            lastBottomContactFrame = frameCounter;
        }
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        // Aggregate contact callback - also tracked via onPieceContact
        if (contact.standing() || contact.touchTop()) {
            lastTopContactFrame = frameCounter;
        }
        if (contact.touchBottom()) {
            lastBottomContactFrame = frameCounter;
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Check contact from the PREVIOUS frame, since solidObjectManager.update()
        // runs AFTER objectManager.update() in the game loop.
        boolean touchTop = (frameCounter - lastTopContactFrame) <= 1;
        boolean touchBottom = (frameCounter - lastBottomContactFrame) <= 1;

        // Run state machine
        switch (state) {
            case 0, 4 -> updateWaitTop(touchTop);
            case 1, 3 -> updateRise();
            case 2, 6 -> updateWaitBottom(touchBottom);
            case 5, 7 -> updateDrop();
        }

        // Apply staircase interpolation to all pieces
        applyStaircaseInterpolation();

        refreshDynamicSpawn();
    }

    /**
     * States 0, 4: Wait for player contact on TOP, then 30-frame countdown.
     */
    private void updateWaitTop(boolean touchTop) {
        if (touchTop && timer == 0) {
            timer = TOP_CONTACT_DELAY;
        }

        if (timer > 0) {
            timer--;
            if (timer == 0) {
                // Transition to rise state
                state++;
            }
        }
    }

    /**
     * States 1, 3: Smooth downward movement (platforms descend, carrying player down).
     * Despite being called "rise" in disassembly, the Y offset increases.
     * The master piece (piece 0) moves down 1 pixel per frame.
     */
    private void updateRise() {
        // Move master piece down (positive Y = down in screen coords)
        if (yOffsets[0] < MAX_Y_OFFSET) {
            yOffsets[0]++;
        } else {
            // Reached max travel, transition to next state
            state++;
        }
    }

    /**
     * States 2, 6: Wait for player contact from BOTTOM, 60-frame countdown with oscillation.
     * Oscillation visual effect is applied in appendRenderCommands().
     */
    private void updateWaitBottom(boolean touchBottom) {
        if (touchBottom && timer == 0) {
            timer = BOTTOM_CONTACT_DELAY;
        }

        if (timer > 0) {
            timer--;
            if (timer == 0) {
                // Transition to drop state
                state++;
            }
        }
    }

    /**
     * States 5, 7: Smooth upward movement (platforms rise back to original position).
     * Despite being called "drop" in disassembly, this returns platforms to start.
     * The master piece (piece 0) moves up 1 pixel per frame.
     */
    private void updateDrop() {
        // Move master piece up (negative direction = returning to base)
        if (yOffsets[0] > 0) {
            yOffsets[0]--;
        } else {
            // Reached original position, transition to next state
            // State wraps: 5 -> 6, 7 -> 0 (via & 0x07)
            state = (state + 1) & 0x07;
        }
    }

    /**
     * Applies staircase interpolation from the disassembly using fixed-point arithmetic.
     * The original code uses 16.16 fixed point with swap operations.
     *
     * For positive counter (states 1, 3 - upward shift):
     *   lsr.l #1,d1 (logical shift right)
     * For negative counter (states 5, 7 - downward shift):
     *   asr.l #1,d1 (arithmetic shift right to preserve sign)
     *
     * Result:
     *   piece[0] = 100% of master offset (the reference)
     *   piece[1] = 75% of master offset
     *   piece[2] = 50% of master offset
     *   piece[3] = 25% of master offset
     */
    private void applyStaircaseInterpolation() {
        int counter = yOffsets[0];  // Master piece offset (100%)

        // Convert to 16.16 fixed-point (swap = shift left 16)
        long d1 = ((long) counter) << 16;

        // Apply shifts - use arithmetic shift for negative values
        long d2, d3;
        if (counter >= 0) {
            // States 1, 3: logical shift (lsr)
            d2 = d1 >>> 1;          // counter/2 (50%)
            d1 = d1 >>> 1;          // counter/2
            d1 = d1 >>> 1;          // counter/4 (25%)
        } else {
            // States 5, 7: arithmetic shift (asr) to preserve sign
            d2 = d1 >> 1;           // counter/2 (50%)
            d1 = d1 >> 1;           // counter/2
            d1 = d1 >> 1;           // counter/4 (25%)
        }
        d3 = d1 + d2;               // 75% = 25% + 50%

        // Extract high word (swap back) - shift right 16
        yOffsets[1] = (int)(d3 >> 16);  // 75%
        yOffsets[2] = (int)(d2 >> 16);  // 50%
        yOffsets[3] = (int)(d1 >> 16);  // 25%
        // yOffsets[0] stays as raw counter (100%)
    }

    // Oscillation is handled in appendRenderCommands() for visual effect only

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            appendDebug(commands);
            return;
        }

        // Use the dedicated CPZ Stair Block renderer (NOT cpzPlatformRenderer which is Obj19)
        PatternSpriteRenderer renderer = renderManager.getCpzStairBlockRenderer();
        if (renderer == null || !renderer.isReady()) {
            appendDebug(commands);
            return;
        }

        // Calculate checkerboard oscillation for states 2, 6 (wait at bottom with countdown)
        // Disassembly: lsr.b #2,d0 / andi.b #1,d0 - toggles every 4 frames
        // Creates checkerboard pattern: pieces 0,2 move together, 1,3 move opposite
        boolean oscillating = (state == 2 || state == 6) && timer > 0;

        // Render all 4 pieces
        // Frame 0 is the 32x32 stair block (4x4 tiles)
        for (int i = 0; i < NUM_PIECES; i++) {
            int pieceIndex = xFlip ? (NUM_PIECES - 1 - i) : i;
            int pieceX = baseX + (i * PIECE_SPACING);
            int pieceY = baseY + yOffsets[pieceIndex];

            // Apply checkerboard oscillation pattern during wait states
            // Blocks 0,2 get baseBit value, blocks 1,3 get opposite value
            if (oscillating) {
                int baseBit = (timer >> 2) & 1;  // Toggles every 4 frames
                // pieceIndex determines which group: 0,2 vs 1,3
                int shake = ((pieceIndex & 1) == 0) ? baseBit : (baseBit ^ 1);
                pieceY += shake;
            }

            // Draw the stair block at this position
            renderer.drawFrameIndex(0, pieceX, pieceY, xFlip, false);
        }
    }

    private void refreshDynamicSpawn() {
        // Track the position of the first piece for riding calculations
        int pieceY = baseY + yOffsets[0];
        if (dynamicSpawn == null || dynamicSpawn.y() != pieceY) {
            dynamicSpawn = new ObjectSpawn(
                    baseX,
                    pieceY,
                    spawn.objectId(),
                    spawn.subtype(),
                    spawn.renderFlags(),
                    spawn.respawnTracked(),
                    spawn.rawYWord());
        }
    }

    private void appendDebug(List<GLCommand> commands) {
        // Debug rendering - draw rectangles for each piece
        for (int i = 0; i < NUM_PIECES; i++) {
            int pieceIndex = xFlip ? (NUM_PIECES - 1 - i) : i;
            int pieceX = baseX + (i * PIECE_SPACING);
            int pieceY = baseY + yOffsets[pieceIndex];

            int left = pieceX - PIECE_HALF_WIDTH;
            int right = pieceX + PIECE_HALF_WIDTH;
            int top = pieceY - PIECE_TOP_HEIGHT;
            int bottom = pieceY + PIECE_BOTTOM_HEIGHT;

            appendLine(commands, left, top, right, top);
            appendLine(commands, right, top, right, bottom);
            appendLine(commands, right, bottom, left, bottom);
            appendLine(commands, left, bottom, left, top);
        }
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.6f, 0.8f, 0.3f, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                0.6f, 0.8f, 0.3f, x2, y2, 0, 0));
    }
}
