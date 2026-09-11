package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.audio.GameSound;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.LazyMappingHolder;

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
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(RisingPillarObjectInstance.class.getName());

    // art_tile palette from disassembly: make_art_tile(ArtTile_ArtKos_LevelArt,1,0)
    // The original game ADDS this to each mapping piece's pattern word
    private static final int ART_TILE_PALETTE = 1;

    private static final int HALF_WIDTH = 0x10;
    private static final int INITIAL_Y_RADIUS = 0x18;
    private static final int TRIGGER_DISTANCE = 0x40;
    private static final int RISE_SPEED = 4;
    private static final int RISE_DELAY = 3;
    private static final int MAX_EXTENSION_FRAME = 6;
    private static final int GRAVITY = 0x18;
    private static final int DEBRIS_PIECE_MASK = 0x0F;
    private static final int DEBRIS_FRAME_SHIFT = 4;
    private static final int DEBRIS_FRAME_MASK = 0x0F;
    private static final int APPROX_RENDER_Y_MARGIN = 32;

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

    private static final LazyMappingHolder MAPPINGS = new LazyMappingHolder();

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
    private boolean romRenderOnScreen;
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
        this.romRenderOnScreen = true;

        updateDynamicSpawn(x, y);
    }

    @Override
    public RisingPillarObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new RisingPillarObjectInstance(ctx.spawn(), getName());
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
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (routine == 2) {
            updateMain(player);
        } else if (routine == 4) {
            updateDebris();
        }
        updateDynamicSpawn(x, y);
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
            // ROM (loc_25B3C): only sets routine_secondary to 2, does NOT
            // set objoff_34. Since objoff_34 starts at 0, the first subq.w #1
            // at loc_25B66 causes borrow (carry set), so bcc does NOT branch
            // and the first rise step executes immediately.
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
        } else {
            // ROM loc_25B9A does ObjectMove first, then adds gravity to y_vel.
            // Doing gravity first applies one frame's acceleration to the
            // current frame's motion and accumulates y-position drift.
            subX += velX;
            subY += velY;
            x = subX >> 8;
            y = subY >> 8;
            velY += GRAVITY;
        }

        // ROM loc_25BA4 tests render_flags.on_screen and jumps to DeleteObject
        // when the previous BuildSprites pass cleared the render-bounds bit
        // (docs/s2disasm/s2.asm:51885-51888).
        if (!romRenderOnScreen) {
            setDestroyed(true);
        }
    }

    /**
     * Release the player and break the pillar into debris.
     * The pillar itself becomes the first debris piece.
     * Corresponds to loc_25ACE and loc_25BF6 in disassembly.
     */
    private void releasePlayerAndBreak(AbstractPlayableSprite player) {
        // ROM loc_25AF6 (s2.asm:51393-51405) does: bset rolling; move.b #$E,y_radius;
        // move.b #7,x_radius; set Roll anim; bset in_air; bclr on_object; routine=2.
        // ROM y_pos is the CENTRE, so the y_radius change does not move the centre.
        // The engine stores top-left, so naively calling setRolling(true) here would
        // shrink height by 2 (runHeight 30 → rollHeight 28) and drop centreY by 1,
        // since SolidObject_Landed's RideObject_SetRide → Tails_ResetOnFloor_Part2
        // (s2.asm:40629-40636) already uncurled Tails (clearRollingOnLanding mirrors
        // that, including the subq.w #1, y_pos lift) before the pillar re-curls here.
        // Preserve the post-landing centreY across the curl toggle.
        short centreYBeforeCurl = player.getCentreY();
        player.setRolling(true);
        if (player.getCentreY() != centreYBeforeCurl) {
            player.setCentreYPreserveSubpixel(centreYBeforeCurl);
        }
        player.setAir(true);
        player.setOnObject(false); // ROM loc_25AF6 s2.asm:51401 bclr #status.player.on_object,status(a1)

        // Clear riding state so the stale-riding-state recovery in PlayableSpriteMovement
        // (which fires before inline solid contacts on the next frame) does not re-ground
        // the player. In the ROM this is implicit: once on_object is cleared and routine=2
        // is set, SolidObject no longer treats the player as riding this object.
        ObjectManager om = services().objectManager();
        if (om != null) {
            om.clearRidingObject(player);
        }

        // Play slow smash sound effect
        services().playSfx(GameSound.SLOW_SMASH);

        // NOTE: We do NOT call markRemembered here. The original game uses MarkObjGone
        // which CLEARS the respawn flag (bclr #7), allowing the pillar to respawn
        // in its initial shrunken state when the player returns to this area.

        // Get debris frame (mapping_frame + 7)
        List<SpriteMappingFrame> mappings = MAPPINGS.get(
                Sonic2Constants.MAP_UNC_OBJ2B_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj2B");
        if (mappings.isEmpty()) {
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
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null) {
            int numPieces = Math.min(pieces.size(), DEBRIS_DATA.length);
            for (int i = 1; i < numPieces; i++) {
                SpriteMappingPiece piece = pieces.get(i);
                int vx = DEBRIS_DATA[i][0];
                int vy = DEBRIS_DATA[i][1];
                int delay = DEBRIS_DATA[i][2];

                // ROM loc_25C1C calls AllocateObjectAfterCurrent for each
                // debris sibling (docs/s2disasm/s2.asm:51936-51938), so the
                // spawned pieces must occupy slots after the pillar slot.
                int fragmentIndex = i;
                spawnChild(() -> new RisingPillarDebrisInstance(
                        x, y, vx, vy, piece, delay, debrisFrame, fragmentIndex));
            }
        }

        // ROM Obj2B breaks from Obj2B_Main through loc_25ACE, spawns the debris
        // in loc_25BF6, then immediately branches to loc_25B8E for the parent
        // object, so piece 0 moves on the same frame as the standing contact
        // (docs/s2disasm/s2.asm:51840-51855,51875-51888,51909-51949).
        updateDebris();
        updateDynamicSpawn(x, y);

        LOGGER.fine(() -> String.format("Rising pillar at (%d,%d) broke into debris", spawn.x(), spawn.y()));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        List<SpriteMappingFrame> mappings = MAPPINGS.get(
                Sonic2Constants.MAP_UNC_OBJ2B_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj2B");
        if (mappings.isEmpty()) {
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
        List<SpriteMappingFrame> mappings = MAPPINGS.get(
                Sonic2Constants.MAP_UNC_OBJ2B_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj2B");
        int frame = mappingFrame;
        if (frame < 0 || frame >= mappings.size()) {
            frame = 0;
        }

        SpriteMappingFrame mapping = mappings.get(frame);
        if (mapping == null || mapping.pieces().isEmpty()) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        GraphicsManager graphicsManager = services().graphicsManager();
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

        GraphicsManager graphicsManager = services().graphicsManager();
        renderPieceWithArtTile(graphicsManager, debrisPiece, x, y, false, false);
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (routine == 4) {
            romRenderOnScreen = isWithinRenderSpriteBounds(getOnScreenHalfWidth(), getOnScreenHalfHeight());
        }
    }

    @Override
    public int getOnScreenHalfHeight() {
        // Obj2B sets render_flags.level_fg and width_pixels but does not set
        // render_flags.explicit_height, so S2 BuildSprites uses its approximate
        // +/-32px Y band before setting render_flags.on_screen.
        // docs/s2disasm/s2.asm:30569-30588
        return APPROX_RENDER_Y_MARGIN;
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
        return SolidObjectParams.of(
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
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Only handle solid contact in main routine
        if (routine != 2) {
            return;
        }

        // ROM (Obj2B_Main, line 51369): checks standing_mask REGARDLESS of
        // routine_secondary. If any player is standing, break the pillar.
        if (contact.standing()) {
            releasePlayerAndBreak(player);
        }
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Only solid in main routine (not when debris)
        return routine == 2 && !isDestroyed();
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int halfWidth = HALF_WIDTH;
        int halfHeight = yRadius;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        ctx.drawLine(left, top, right, top, 0.4f, 0.6f, 0.2f);
        ctx.drawLine(right, top, right, bottom, 0.4f, 0.6f, 0.2f);
        ctx.drawLine(right, bottom, left, bottom, 0.4f, 0.6f, 0.2f);
        ctx.drawLine(left, bottom, left, top, 0.4f, 0.6f, 0.2f);
    }

    /**
     * Inner class for debris fragments (pieces 1-13).
     * Piece 0 is the original pillar object transformed into debris.
     */
    public static class RisingPillarDebrisInstance extends AbstractObjectInstance
            implements RewindRecreatable {

        private static final int GRAVITY = 0x18;

        private int currentX;
        private int currentY;
        private int subX;
        private int subY;
        private int velX;
        private int velY;
        private int delay;
        private int mappingFrame;
        private int pieceIndex;
        private boolean romRenderOnScreen;
        private final SpriteMappingPiece piece;

        public RisingPillarDebrisInstance(int x, int y, int velX, int velY, SpriteMappingPiece piece, int delay) {
            this(x, y, velX, velY, piece, delay, 0, 0);
        }

        public RisingPillarDebrisInstance(int x, int y, int velX, int velY, SpriteMappingPiece piece, int delay,
                                          int mappingFrame, int pieceIndex) {
            this(debrisSpawn(x, y, mappingFrame, pieceIndex), velX, velY, piece, delay,
                    mappingFrame, pieceIndex);
        }

        public RisingPillarDebrisInstance(ObjectSpawn spawn) {
            this(spawn, 0, 0, debrisPiece(decodeMappingFrame(spawn), decodePieceIndex(spawn)), 0,
                    decodeMappingFrame(spawn), decodePieceIndex(spawn));
        }

        private RisingPillarDebrisInstance(ObjectSpawn spawn, int velX, int velY, SpriteMappingPiece piece,
                                           int delay, int mappingFrame, int pieceIndex) {
            super(spawn, "PillarDebris");
            this.currentX = spawn.x();
            this.currentY = spawn.y();
            this.subX = spawn.x() << 8;
            this.subY = spawn.y() << 8;
            this.velX = velX;
            this.velY = velY;
            this.piece = piece;
            this.delay = delay;
            this.mappingFrame = mappingFrame;
            this.pieceIndex = pieceIndex;
            this.romRenderOnScreen = true;
        }

        @Override
        public RisingPillarDebrisInstance recreateForRewind(RewindRecreateContext ctx) {
            return new RisingPillarDebrisInstance(ctx.spawn());
        }

        private static ObjectSpawn debrisSpawn(int x, int y, int mappingFrame, int pieceIndex) {
            int subtype = (pieceIndex & DEBRIS_PIECE_MASK)
                    | ((mappingFrame & DEBRIS_FRAME_MASK) << DEBRIS_FRAME_SHIFT);
            return new ObjectSpawn(x, y, 0x2B, subtype, 0, false, 0);
        }

        private static int decodePieceIndex(ObjectSpawn spawn) {
            return spawn.subtype() & DEBRIS_PIECE_MASK;
        }

        private static int decodeMappingFrame(ObjectSpawn spawn) {
            return (spawn.subtype() >> DEBRIS_FRAME_SHIFT) & DEBRIS_FRAME_MASK;
        }

        private static SpriteMappingPiece debrisPiece(int mappingFrame, int pieceIndex) {
            List<SpriteMappingFrame> mappings = MAPPINGS.get(
                    Sonic2Constants.MAP_UNC_OBJ2B_ADDR, S2SpriteDataLoader::loadMappingFrames, "Obj2B");
            if (mappingFrame < 0 || mappingFrame >= mappings.size()) {
                return null;
            }
            SpriteMappingFrame frame = mappings.get(mappingFrame);
            if (frame == null || frame.pieces().isEmpty()
                    || pieceIndex < 0 || pieceIndex >= frame.pieces().size()) {
                return null;
            }
            return frame.pieces().get(pieceIndex);
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
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (isDestroyed()) {
                return;
            }

            // Handle spawn delay (objoff_3F)
            if (delay > 0) {
                delay--;
            } else {
                subX += velX;
                subY += velY;
                currentX = subX >> 8;
                currentY = subY >> 8;
                velY += GRAVITY;
            }

            // ROM loc_25BA4 tests the previous BuildSprites on-screen bit.
            // BuildSprites uses a 32px approximate Y band for Obj2B debris
            // because Obj2B does not set render_flags.explicit_height.
            // docs/s2disasm/s2.asm:30569-30588,51885-51888
            if (!romRenderOnScreen) {
                setDestroyed(true);
            }
        }

        @Override
        public void refreshPostCameraRenderState() {
            romRenderOnScreen = isWithinRenderSpriteBounds(getOnScreenHalfWidth(), getOnScreenHalfHeight());
        }

        @Override
        public int getOnScreenHalfHeight() {
            return APPROX_RENDER_Y_MARGIN;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || piece == null || delay > 0) {
                return;
            }

            GraphicsManager graphicsManager = services().graphicsManager();
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
