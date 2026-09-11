package com.openggf.game.sonic1.objects;

import com.openggf.audio.GameSound;
import com.openggf.camera.Camera;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.GameStateManager;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x51 - Smashable Green Block (MZ).
 * <p>
 * A solid green block found in Marble Zone that shatters when Sonic lands on it
 * while rolling/jumping. Breaking the block spawns 4 fragment objects that scatter
 * with gravity, awards escalating chain bonus points, and bounces Sonic upward.
 * <p>
 * Subtypes (obSubtype -> obFrame):
 * <ul>
 *   <li>0 = Standard frame (two 32x16 halves)</li>
 * </ul>
 * <p>
 * Chain bonus system (v_itembonus):
 * <ul>
 *   <li>1st block: 100 pts</li>
 *   <li>2nd block: 200 pts</li>
 *   <li>3rd block: 500 pts</li>
 *   <li>4th+ block: 1000 pts</li>
 *   <li>16th block: 10000 pts (special bonus)</li>
 * </ul>
 * <p>
 * Uses RememberState to persist destruction across respawning.
 * <p>
 * Reference: docs/s1disasm/_incObj/51 Smashable Green Block.asm
 */
public class Sonic1SmashBlockObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly: move.w #$1B,d1
    private static final int SOLID_HALF_WIDTH = 0x1B;
    // From disassembly: move.w #$10,d2
    private static final int SOLID_HALF_HEIGHT_AIR = 0x10;
    // From disassembly: move.w #$11,d3
    private static final int SOLID_HALF_HEIGHT_GROUND = 0x11;
    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;
    // From disassembly: move.b #$10,obActWid(a0)
    private static final int ACTIVE_WIDTH = 0x10;

    // From disassembly: move.w #-$300,obVelY(a1) - player rebound velocity
    private static final int PLAYER_REBOUND_VEL_Y = -0x300;

    // From disassembly: move.w #$38,d2 - fragment gravity acceleration
    private static final int FRAGMENT_GRAVITY = 0x38;

    // Fragment count: d1 = 3 means 4 fragments (dbf loop counts down from 3 to 0)
    private static final int FRAGMENT_COUNT = 4;

    // Smab_Speeds: fragment velocities (x-speed, y-speed) for the 4 quadrants
    // From disassembly lines 104-107:
    //   dc.w -$200, -$200   ; top-left
    //   dc.w -$100, -$100   ; bottom-left
    //   dc.w  $200, -$200   ; top-right
    //   dc.w  $100, -$100   ; bottom-right
    private static final int[][] FRAGMENT_SPEEDS = {
            {-0x200, -0x200},
            {-0x100, -0x100},
            { 0x200, -0x200},
            { 0x100, -0x100},
    };

    // Smab_Scores in ROM is stored in x10 score units: dc.w 10,20,50,100.
    // Engine score uses displayed points, so scale to 100,200,500,1000.
    private static final int[] CHAIN_SCORES = {100, 200, 500, 1000};
    // Special bonus for 16th smash: cmpi.w #$20,(v_itembonus).w
    private static final int SPECIAL_BONUS_THRESHOLD = 0x20;
    private static final int SPECIAL_BONUS_POINTS = 10000;

    // Mapping frame indices
    private static final int FRAME_INTACT = 0;
    private static final int FRAME_FRAGMENTS = 1;

    // id_Roll animation constant - Sonic's rolling animation ID
    // From disassembly: cmpi.b #id_Roll,sonicAniFrame(a0)
    // Sonic rolling = animation $02 (from the disassembly constants)
    // In our engine this is checked via cached pre-collision animation, with
    // player.getRolling() as a fallback.

    private int frameIndex;
    private boolean broken;
    private boolean initialized;

    // objoff_34: cached v_itembonus value at start of frame (before potential increment)
    private int cachedItemBonus;
    // objoff_32: cached Sonic animation from before SolidObject collision resolution
    private int cachedSonicAnimationId;

    public Sonic1SmashBlockObjectInstance(ObjectSpawn spawn) {
        super(spawn, "SmashBlock");
        // From disassembly: move.b obSubtype(a0),obFrame(a0)
        this.frameIndex = spawn.subtype() & 0xFF;
    }

    /**
     * Lazy initialization: check RememberState on first update.
     * Moved out of constructor to avoid calling services() during construction.
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        // RememberState: check if already broken
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null && objectManager.isRemembered(spawn)) {
            this.broken = true;
            setDestroyed(true);
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (broken || player == null) {
            return;
        }

        // From disassembly Smab_Solid:
        // move.w (v_itembonus).w,objoff_34(a0)
        // move.b (v_player+obAnim).w,sonicAniFrame(a0)
        // Cache the current item bonus counter
        cachedItemBonus = services().gameState().getItemBonus();
        cachedSonicAnimationId = player.getAnimationId();
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly:
        //   move.w #$1B,d1   ; half-width for X check
        //   move.w #$10,d2   ; half-height for air check
        //   move.w #$11,d3   ; half-height for ground check
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT_AIR, SOLID_HALF_HEIGHT_GROUND);
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !broken;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (broken || player == null) {
            return;
        }

        // From disassembly:
        //   btst #3,obStatus(a0) - has Sonic landed on the block?
        //   bne.s .smash         - if yes, branch
        if (!contact.standing()) {
            return;
        }

        // From disassembly:
        //   cmpi.b #id_Roll,sonicAniFrame(a0) - is Sonic rolling/jumping?
        //   bne.s .notspinning                - if not, branch
        // The animation check uses the pre-collision animation cached in update(),
        // matching ROM behavior when landing clears rolling during collision resolution.
        boolean wasRollAnimating = cachedSonicAnimationId == Sonic1AnimationIds.ROLL.id();
        if (!wasRollAnimating && !player.getRolling()) {
            return;
        }

        smashBlock(player);
    }

    /**
     * Smash the block: spawn fragments, award points, bounce player.
     * <p>
     * From disassembly Smab_Solid .smash (lines 45-87).
     */
    private void smashBlock(AbstractPlayableSprite player) {
        broken = true;

        // Mark as remembered (RememberState) so it stays broken on revisit
        ObjectManager objectManager = services().objectManager();
        ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);

        // Restore cached item bonus before incrementing
        // From disassembly: move.w .count(a0),(v_itembonus).w
        GameStateManager gameState = services().gameState();
        gameState.setItemBonus(cachedItemBonus);

        // From disassembly:
        //   bset #2,obStatus(a1)     ; set player rolling bit
        //   move.b #$E,obHeight(a1)  ; rolling height
        //   move.b #7,obWidth(a1)    ; rolling width
        //   move.b #id_Roll,obAnim(a1) ; make Sonic roll
        // ROM .smash sets obHeight=sonic_roll_height directly (bset #2,obStatus;
        // move.b #sonic_roll_height,obHeight) WITHOUT adjusting obY -- ROM obY is
        // the center, so shrinking the height leaves the center (and thus the
        // recorded y_pos) unchanged (docs/s1disasm/_incObj/51 MZ Smashable Green
        // Block.asm:60-66). The engine stores y_pos as the TOP-left and derives the
        // centre as top + height/2, so setRolling()'s height shrink (38->28px when
        // the lander un-rolled to standing during Solid_ResetFloor and is now
        // re-rolled) moves the derived CENTRE up by (sonic_height-sonic_roll_height)
        // = 5px. Preserve the centre across the rolling transition so the rebound
        // launches from ROM's obY (MZ3 f7982: ENTER centre 0x6CC matched ROM, but
        // setRolling shifted it to 0x6C7 -- 5px high).
        int centreYBeforeRoll = player.getCentreY();
        boolean wasRolling = player.getRolling();
        player.setRolling(true);
        if (!wasRolling) {
            NativePositionOps.writeYPosPreserveSubpixel(player, centreYBeforeRoll);
        }

        // From disassembly: move.w #-$300,obVelY(a1) - rebound upward
        player.setYSpeed((short) PLAYER_REBOUND_VEL_Y);

        // From disassembly:
        //   bset #1,obStatus(a1)  ; set in-air flag
        //   bclr #3,obStatus(a1)  ; clear standing-on-object flag
        //   move.b #2,obRoutine(a1) ; set player to normal routine
        player.setAir(true);

        // From disassembly:
        //   bclr #3,obStatus(a0)  ; clear player-standing flag on block
        //   clr.b obSolid(a0)     ; disable solidity
        //   move.b #1,obFrame(a0) ; set to fragment mapping frame

        // Spawn 4 fragment objects using SmashObject subroutine
        spawnFragments();

        // Award chain bonus points
        awardChainPoints(objectManager);

        // Mark this object as destroyed
        setDestroyed(true);
    }

    /**
     * Spawn 4 fragment objects that scatter with velocity and gravity.
     * <p>
     * Implements SmashObject subroutine (docs/s1disasm/_incObj/sub SmashObject.asm).
     * Each fragment gets one mapping piece from frame 1 (.four) and an initial
     * velocity from Smab_Speeds.
     */
    private void spawnFragments() {
        ObjectManager objectManager = services().objectManager();
        ObjectRenderManager renderManager = services().renderManager();
        if (objectManager == null || renderManager == null) {
            return;
        }

        ObjectSpriteSheet sheet = renderManager.getSheet(ObjectArtKeys.MZ_SMASH_BLOCK);
        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.MZ_SMASH_BLOCK);
        if (sheet == null || renderer == null) {
            return;
        }

        // Get frame 1 (.four) which has 4 pieces for the fragment quadrants
        SpriteMappingFrame fragFrame = (FRAME_FRAGMENTS < sheet.getFrameCount())
                ? sheet.getFrame(FRAME_FRAGMENTS) : null;
        if (fragFrame == null || fragFrame.pieces() == null
                || fragFrame.pieces().size() < FRAGMENT_COUNT) {
            return;
        }

        List<SpriteMappingPiece> pieces = fragFrame.pieces();
        final int blockX = spawn.x();
        final int blockY = spawn.y();
        final PatternSpriteRenderer fRenderer = renderer;

        for (int i = 0; i < FRAGMENT_COUNT; i++) {
            final SpriteMappingPiece piece = pieces.get(i);
            final int velX = FRAGMENT_SPEEDS[i][0];
            final int velY = FRAGMENT_SPEEDS[i][1];

            final int fragmentIndex = i;
            SmashBlockFragmentInstance fragment = spawnFreeChild(() -> new SmashBlockFragmentInstance(
                    blockX, blockY, velX, velY, fragmentIndex, piece, fRenderer));
            // FixBugs = 0 (docs/s1disasm/sonic.asm:20) — the shipped branch, which is
            // what the traces record. SmashObject
            // (docs/s1disasm/_incObj/"sub SmashObject.asm":51-65) allocates fragments
            // with FindFreeObj, which scans the SST from the start, so a fragment can
            // land BELOW the parent in RAM. ExecuteObjects walks ascending and has
            // already passed that slot, so the shipped ROM runs a one-off catch-up on
            // such a fragment — SpeedToPos plus `add.w d2,obVelY` where d2 is that
            // caller's fragment gravity (MZ green block: gravity,
            // "51 MZ Smashable Green Block.asm":75) — exactly one extra fall step, so
            // it stays in sync with the fragments that will still run this frame, and
            // DisplaySprite2 so it still renders. With FixBugs = 1 the allocator would
            // be FindNextFreeObj (never below the parent) and this whole block is
            // omitted as redundant. Effect: a one-frame position offset on debris.
            if (fragment != null && services().objectManager() != null
                    && services().objectManager().isSlotAlreadyExecutedThisFrame(fragment)) {
                fragment.applySmashObjectCatchUpStep();
            }
        }

        // From disassembly SmashObject .playsnd:
        //   move.w #sfx_WallSmash,d0 / jmp (QueueSound2).l
        services().playSfx(GameSound.WALL_SMASH);
    }

    /**
     * Award escalating chain bonus points.
     * <p>
     * From disassembly lines 64-87 (FindFreeObj -> points popup):
     * <pre>
     *     move.w  (v_itembonus).w,d2       ; current chain index
     *     addq.w  #2,(v_itembonus).w       ; increment for next smash
     *     cmpi.w  #6,d2                    ; cap at index 6 (4th entry)
     *     blo.s   .bonus
     *     moveq   #6,d2
     * .bonus:
     *     moveq   #0,d0
     *     move.w  Smab_Scores(pc,d2.w),d0  ; look up points
     *     cmpi.w  #$20,(v_itembonus).w     ; 16th smash?
     *     blo.s   .givepoints
     *     move.w  #1000,d0                 ; special 1000 (x10 units) => 10000 pts
     *     moveq   #10,d2                   ; points frame index 5
     * .givepoints:
     *     jsr     (AddPoints).l
     *     lsr.w   #1,d2
     *     move.b  d2,obFrame(a1)           ; set points display frame
     * </pre>
     */
    private void awardChainPoints(ObjectManager objectManager) {
        GameStateManager gameState = services().gameState();

        // move.w (v_itembonus).w,d2
        int d2 = gameState.getItemBonus();

        // addq.w #2,(v_itembonus).w
        gameState.setItemBonus(d2 + 2);

        // cmpi.w #6,d2 / blo.s .bonus / moveq #6,d2
        if (d2 >= 6) {
            d2 = 6;
        }

        // move.w Smab_Scores(pc,d2.w),d0
        // d2 is a byte offset into a word table, so d2/2 is the array index
        int scoreIndex = d2 / 2;
        int points = CHAIN_SCORES[scoreIndex];

        // cmpi.w #$20,(v_itembonus).w / blo.s .givepoints
        // Check AFTER increment: if 16th smash, override to special bonus
        int updatedBonus = gameState.getItemBonus();
        int pointsFrameIndex;
        if (updatedBonus >= SPECIAL_BONUS_THRESHOLD) {
            points = SPECIAL_BONUS_POINTS;
            d2 = 10; // moveq #10,d2
        }

        // jsr (AddPoints).l
        gameState.addScore(points);

        // lsr.w #1,d2 / move.b d2,obFrame(a1)
        pointsFrameIndex = d2 >> 1;

        // Spawn points popup object
        if (objectManager != null) {
            final int fPoints = points;
            final int fPointsFrameIndex = pointsFrameIndex;
            spawnFreeChild(() -> {
                Sonic1PointsObjectInstance pointsObj = new Sonic1PointsObjectInstance(
                        new ObjectSpawn(spawn.x(), spawn.y(), 0x29, 0, 0, false, 0),
                        services(), fPoints);
                // ROM writes obFrame directly from d2>>1 for this path.
                pointsObj.setScoreFrameIndex(fPointsFrameIndex);
                return pointsObj;
            });
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (broken) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.MZ_SMASH_BLOCK);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // From disassembly: obFrame initially set from obSubtype (always 0 for MZ placements)
        renderer.drawFrameIndex(frameIndex, getX(), getY(), false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (broken) {
            return;
        }
        // Draw solid collision bounds
        ctx.drawRect(getX(), getY(), SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT_AIR,
                0.0f, 0.8f, 0.2f);
        ctx.drawWorldLabel(getX(), getY(), -2,
                String.format("SmashBlk bonus=%d", cachedItemBonus), DebugColor.GREEN);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    /**
     * Fragment object spawned when the green block shatters.
     * <p>
     * From disassembly Smab_Points (Routine 4):
     * <pre>
     *     bsr.w   SpeedToPos
     *     addi.w  #$38,obVelY(a0)     ; gravity
     *     tst.b   obRender(a0)        ; off-screen check
     *     bpl.w   DeleteObject
     * </pre>
     */
    static class SmashBlockFragmentInstance extends AbstractObjectInstance implements RewindRecreatable {

        private int posX, posY;
        private int subX, subY;  // 8.8 fixed-point sub-pixel
        private int velX, velY;  // 8.8 fixed-point velocity
        private final SpriteMappingPiece piece;
        private final PatternSpriteRenderer renderer;

        SmashBlockFragmentInstance(int x, int y, int velX, int velY) {
            this(x, y, velX, velY, 0, null, null);
        }

        SmashBlockFragmentInstance(int x, int y, int velX, int velY,
                                   SpriteMappingPiece piece, PatternSpriteRenderer renderer) {
            this(x, y, velX, velY, 0, piece, renderer);
        }

        SmashBlockFragmentInstance(int x, int y, int velX, int velY,
                                   int fragmentIndex,
                                   SpriteMappingPiece piece, PatternSpriteRenderer renderer) {
            super(new ObjectSpawn(x, y, 0x51, fragmentIndex & 0xFF, 0, false, 0), "SmashBlockFragment");
            this.posX = x;
            this.posY = y;
            this.subX = x << 8;
            this.subY = y << 8;
            this.velX = velX;
            this.velY = velY;
            this.piece = piece;
            this.renderer = renderer;
        }

        @Override
        public SmashBlockFragmentInstance recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn spawn = ctx.spawn();
            int fragmentIndex = spawn.subtype() & 0xFF;
            ObjectRenderManager renderManager = ctx.objectServices() == null ? null : ctx.objectServices().renderManager();
            PatternSpriteRenderer restoredRenderer = renderManager == null
                    ? null
                    : renderManager.getRenderer(ObjectArtKeys.MZ_SMASH_BLOCK);
            SpriteMappingPiece restoredPiece = fragmentPiece(
                    renderManager, ObjectArtKeys.MZ_SMASH_BLOCK, fragmentIndex);
            return new SmashBlockFragmentInstance(
                    spawn.x(), spawn.y(), 0, 0, fragmentIndex, restoredPiece, restoredRenderer);
        }

        /**
         * One extra Smab_Fragment fall step, applied by SmashObject's shipped
         * (FixBugs = 0) catch-up path to a fragment allocated below the parent's
         * SST slot (docs/s1disasm/_incObj/"sub SmashObject.asm":51-65). Identical
         * to the routine-4 body's SpeedToPos + gravity, which is what the ROM's
         * `bsr SpeedToPos` / `add.w d2,obVelY` pair reproduces by hand.
         */
        void applySmashObjectCatchUpStep() {
            subX += velX;
            subY += velY;
            posX = subX >> 8;
            posY = subY >> 8;
            velY += FRAGMENT_GRAVITY;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (isDestroyed()) {
                return;
            }

            // SpeedToPos: position += velocity (8.8 fixed point)
            subX += velX;
            subY += velY;
            posX = subX >> 8;
            posY = subY >> 8;

            // From disassembly: addi.w #$38,obVelY(a0) - gravity
            velY += FRAGMENT_GRAVITY;

            // From disassembly: tst.b obRender(a0) / bpl.w DeleteObject
            // Delete when off-screen (render flag bit 7 indicates on-screen)
            Camera cam = services().camera();
            int cameraX = cam.getX();
            int cameraY = cam.getY();
            if (posX < cameraX - 64 || posX > cameraX + 320 + 64
                    || posY > cameraY + 224 + 64) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawPieces(List.of(piece), posX, posY, false, false);
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY);
        }

        private static SpriteMappingPiece fragmentPiece(
                ObjectRenderManager renderManager,
                String artKey,
                int fragmentIndex) {
            if (renderManager == null) {
                return null;
            }
            ObjectSpriteSheet sheet = renderManager.getSheet(artKey);
            if (sheet == null || FRAME_FRAGMENTS >= sheet.getFrameCount()) {
                return null;
            }
            SpriteMappingFrame frame = sheet.getFrame(FRAME_FRAGMENTS);
            if (frame == null || frame.pieces() == null
                    || fragmentIndex < 0 || fragmentIndex >= frame.pieces().size()) {
                return null;
            }
            return frame.pieces().get(fragmentIndex);
        }
    }
}
