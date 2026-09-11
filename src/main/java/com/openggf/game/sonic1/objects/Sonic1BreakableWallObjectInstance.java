package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;

import com.openggf.audio.GameSound;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Sonic 1 Smashable Wall (Object 0x3C) - GHZ and SLZ.
 * <p>
 * A solid wall that breaks when Sonic rolls into it at speed >= $480.
 * On impact, spawns 8 fragment objects that scatter with gravity.
 * Uses RememberState to stay destroyed on revisit.
 * <p>
 * Subtypes (obSubtype → obFrame):
 * <ul>
 *   <li>0 = Left section</li>
 *   <li>1 = Middle section</li>
 *   <li>2 = Right section</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/3C Smashable Wall.asm
 */
public class Sonic1BreakableWallObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly: move.w #$1B,d1
    private static final int HALF_WIDTH = 0x1B;
    // From disassembly: move.w #$20,d2 / move.w #$20,d3
    private static final int HALF_HEIGHT = 0x20;
    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;
    // From disassembly: cmpi.w #$480,d0
    private static final int BREAK_SPEED_THRESHOLD = 0x480;
    // From disassembly: move.w #$70,d2 (fragment gravity)
    private static final int FRAGMENT_GRAVITY = 0x70;

    // Smash_FragSpd1: fragments move right (Sonic approaching from left)
    // From sonic.asm lines 5286-5293
    private static final int[][] FRAG_SPD_RIGHT = {
            { 0x400, -0x500},
            { 0x600, -0x100},
            { 0x600,  0x100},
            { 0x400,  0x500},
            { 0x600, -0x600},
            { 0x800, -0x200},
            { 0x800,  0x200},
            { 0x600,  0x600},
    };

    // Smash_FragSpd2: fragments move left (Sonic approaching from right)
    // From sonic.asm lines 5295-5302
    private static final int[][] FRAG_SPD_LEFT = {
            {-0x600, -0x600},
            {-0x800, -0x200},
            {-0x800,  0x200},
            {-0x600,  0x600},
            {-0x400, -0x500},
            {-0x600, -0x100},
            {-0x600,  0x100},
            {-0x400,  0x500},
    };

    // Fragment count (d1 = 7 means 8 fragments, dbf loop)
    private static final int FRAGMENT_COUNT = 8;

    private int frameIndex;
    private boolean broken;
    private boolean initialized;

    public Sonic1BreakableWallObjectInstance(ObjectSpawn spawn) {
        super(spawn, "SmashableWall");
        // From disassembly: move.b obSubtype(a0),obFrame(a0)
        this.frameIndex = spawn.subtype() & 0xFF;
    }

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
        SolidCheckpointBatch batch = checkpointAll();
        List<PlayableEntity> participants = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS);
        if (!participants.contains(player)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(player);
            withUpdatePlayer.addAll(participants);
            participants = withUpdatePlayer;
        }
        for (PlayableEntity participant : participants) {
            if (broken) {
                break;
            }
            if (participant instanceof AbstractPlayableSprite sprite) {
                applyCheckpointContact(sprite, batch.perPlayer().get(participant));
            }
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT);
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
        tryBreak(player, contact.touchSide(), player.getAir(),
                player.getAnimationId() == Sonic1AnimationIds.ROLL.id(), player.getXSpeed());
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    private void applyCheckpointContact(AbstractPlayableSprite player, PlayerSolidContactResult result) {
        if (player == null || result == null || broken) {
            return;
        }
        tryBreak(player,
                result.kind() == ContactKind.SIDE,
                player.getAir(),
                result.preContact().animationId() == Sonic1AnimationIds.ROLL.id(),
                result.preContact().xSpeed());
    }

    private void tryBreak(AbstractPlayableSprite player, boolean sideContact,
                          boolean airborne, boolean rolling, int impactSpeed) {
        // From disassembly: btst #5,obStatus(a0) - is Sonic pushing against the wall?
        // In the ROM, SolidObject sets bit 5 for ANY ground side contact.
        if (!sideContact || airborne) {
            return;
        }
        // From disassembly: cmpi.b #id_Roll,obAnim(a1) - is Sonic rolling?
        if (!rolling) {
            return;
        }

        // From disassembly: check absolute speed >= $480
        int absSpeed = Math.abs(impactSpeed);
        if (absSpeed < BREAK_SPEED_THRESHOLD) {
            return;
        }

        smashWall(player, impactSpeed);
    }

    private void smashWall(AbstractPlayableSprite player, int impactSpeed) {
        broken = true;

        // Mark as remembered (RememberState) so it stays broken
        ObjectManager objectManager = services().objectManager();
        ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);

        // From disassembly: move.w smash_speed(a0),obVelX(a1)
        player.setXSpeed((short) impactSpeed);

        // From disassembly (lines 57-64):
        //   addq.w  #4,obX(a1)              ; always add 4 first (INTEGER word only, obSubX unchanged)
        //   lea     (Smash_FragSpd1).l,a4   ; default = RIGHT fragments
        //   move.w  obX(a0),d0              ; d0 = wall X
        //   cmp.w   obX(a1),d0              ; compare wallX with (sonicX+4)
        //   blo.s   .smash                  ; if wallX < sonicX+4, keep FragSpd1 (RIGHT)
        //   subq.w  #4*2,obX(a1)            ; else subtract 8 (net -4, INTEGER word only, obSubX unchanged)
        //   lea     (Smash_FragSpd2).l,a4   ; use FragSpd2 (LEFT)
        //
        // ROM `addq.w/subq.w` modify only obX (the integer pixel word); obSubX is left intact.
        // Use shiftX(delta) which matches this: shiftX adds to the pixel integer only,
        // preserving the sub-pixel fraction (per AbstractSprite.shiftX JavaDoc).
        int wallX = spawn.x();
        int sonicX = player.getCentreX();

        int[][] fragSpeeds;
        if (wallX < sonicX + 4) {
            // Sonic is to the RIGHT of wall: net +4 (addq.w #4, no subq)
            fragSpeeds = FRAG_SPD_RIGHT;
            player.shiftX(4);   // addq.w #4,obX(a1) — integer only, sub-pixel preserved
        } else {
            // Sonic is to the LEFT of wall: net -4 (addq.w #4 then subq.w #8)
            fragSpeeds = FRAG_SPD_LEFT;
            player.shiftX(-4);  // net of addq.w #4 + subq.w #8 — integer only, sub-pixel preserved
        }

        // From disassembly: move.w obVelX(a1),obInertia(a1)
        player.setGSpeed(player.getXSpeed());

        // From disassembly: bclr #5,obStatus(a0) / bclr #5,obStatus(a1)
        // Clearing push status - handled implicitly by the engine

        // Spawn 8 fragments
        spawnFragments(fragSpeeds);

        // Mark this object as destroyed
        setDestroyed(true);
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    private void spawnFragments(int[][] fragSpeeds) {
        ObjectManager objectManager = services().objectManager();
        ObjectRenderManager renderManager = services().renderManager();
        if (objectManager == null || renderManager == null) {
            return;
        }

        ObjectSpriteSheet sheet = renderManager.getSheet(ObjectArtKeys.BREAKABLE_WALL);
        final PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.BREAKABLE_WALL);
        if (sheet == null || renderer == null) {
            return;
        }

        // SmashObject: each fragment gets one mapping piece from the current frame.
        // From disassembly: the subroutine reads pieces sequentially from the mapping
        // data, advancing a3 by 5 bytes per piece (S1 mapping piece size).
        // We have 8 pieces per frame, one fragment per piece.
        SpriteMappingFrame frame = (frameIndex >= 0 && frameIndex < sheet.getFrameCount())
                ? sheet.getFrame(frameIndex) : null;
        if (frame == null || frame.pieces() == null || frame.pieces().size() < FRAGMENT_COUNT) {
            return;
        }

        List<SpriteMappingPiece> pieces = frame.pieces();
        final int wallX = spawn.x();
        final int wallY = spawn.y();

        for (int i = 0; i < FRAGMENT_COUNT; i++) {
            final SpriteMappingPiece piece = pieces.get(i);
            final int velX = fragSpeeds[i][0];
            final int velY = fragSpeeds[i][1];

            final int wallFrameIndex = frameIndex;
            final int fragmentIndex = i;
            WallFragmentInstance fragment = spawnFreeChild(() -> new WallFragmentInstance(
                    wallX, wallY, velX, velY, wallFrameIndex, fragmentIndex, piece, renderer));
            // FixBugs = 0 (docs/s1disasm/sonic.asm:20) — the shipped branch, which is
            // what the traces record. SmashObject
            // (docs/s1disasm/_incObj/"sub SmashObject.asm":51-65) allocates fragments
            // with FindFreeObj, which scans the SST from the start, so a fragment can
            // land BELOW the parent in RAM. ExecuteObjects walks ascending and has
            // already passed that slot, so the shipped ROM runs a one-off catch-up on
            // such a fragment — SpeedToPos plus `add.w d2,obVelY` where d2 is that
            // caller's fragment gravity (GHZ/SLZ wall: gravity*2,
            // "3C GHZ, SLZ Smashable Wall.asm":72) — exactly one extra fall step, so
            // it stays in sync with the fragments that will still run this frame, and
            // DisplaySprite2 so it still renders. With FixBugs = 1 the allocator would
            // be FindNextFreeObj (never below the parent) and this whole block is
            // omitted as redundant. Effect: a one-frame position offset on debris.
            if (fragment != null
                    && objectManager.isSlotAlreadyExecutedThisFrame(fragment)) {
                fragment.applySmashObjectCatchUpStep();
            }
        }

        // From disassembly: move.w #sfx_WallSmash,d0 / jmp (QueueSound2).l
        services().playSfx(GameSound.WALL_SMASH);
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

        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.BREAKABLE_WALL);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(frameIndex, getX(), getY(), false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (broken) {
            return;
        }
        // Debug: draw solid collision bounds
        int x = getX();
        int y = getY();
        ctx.drawRect(x, y, HALF_WIDTH, HALF_HEIGHT, 0.0f, 1.0f, 0.0f);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    /**
     * Wall fragment - a single mapping piece that flies away with velocity and gravity.
     * <p>
     * From disassembly Smash_FragMove (Routine 4):
     * <pre>
     *     bsr.w   SpeedToPos
     *     addi.w  #$70,obVelY(a0)     ; gravity
     *     bsr.w   DisplaySprite
     *     tst.b   obRender(a0)        ; off-screen check
     *     bpl.w   DeleteObject
     * </pre>
     */
    static class WallFragmentInstance extends AbstractObjectInstance implements RewindRecreatable {

        private int posX, posY;
        private int subX, subY; // 8.8 fixed point sub-pixel
        private int velX, velY; // 8.8 fixed point velocity
        private final SpriteMappingPiece piece;
        private final PatternSpriteRenderer renderer;

        WallFragmentInstance(int x, int y, int velX, int velY) {
            this(x, y, velX, velY, 0, 0, null, null);
        }

        WallFragmentInstance(int x, int y, int velX, int velY,
                             SpriteMappingPiece piece, PatternSpriteRenderer renderer) {
            this(x, y, velX, velY, 0, 0, piece, renderer);
        }

        WallFragmentInstance(int x, int y, int velX, int velY,
                             int wallFrameIndex, int fragmentIndex,
                             SpriteMappingPiece piece, PatternSpriteRenderer renderer) {
            super(new ObjectSpawn(x, y, 0x3C, fragmentSubtype(wallFrameIndex, fragmentIndex),
                    0, false, 0), "WallFragment");
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
        public WallFragmentInstance recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn spawn = ctx.spawn();
            int wallFrameIndex = wallFrameIndex(spawn.subtype());
            int fragmentIndex = fragmentIndex(spawn.subtype());
            ObjectRenderManager renderManager = ctx.objectServices() == null ? null : ctx.objectServices().renderManager();
            PatternSpriteRenderer restoredRenderer = renderManager == null
                    ? null
                    : renderManager.getRenderer(ObjectArtKeys.BREAKABLE_WALL);
            SpriteMappingPiece restoredPiece = fragmentPiece(
                    renderManager, ObjectArtKeys.BREAKABLE_WALL, wallFrameIndex, fragmentIndex);
            return new WallFragmentInstance(
                    spawn.x(), spawn.y(), 0, 0, wallFrameIndex, fragmentIndex, restoredPiece, restoredRenderer);
        }

        /**
         * One extra Smash_Fragment fall step, applied by SmashObject's shipped
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

            // From disassembly: addi.w #$70,obVelY(a0) - gravity
            velY += FRAGMENT_GRAVITY;

            // From disassembly: tst.b obRender(a0) / bpl.w DeleteObject
            // Delete when off-screen (render flag bit 7 indicates on-screen)
            var cam = services().camera();
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

        private static int fragmentSubtype(int wallFrameIndex, int fragmentIndex) {
            return ((wallFrameIndex & 0x0F) << 4) | (fragmentIndex & 0x0F);
        }

        private static int wallFrameIndex(int subtype) {
            return (subtype >>> 4) & 0x0F;
        }

        private static int fragmentIndex(int subtype) {
            return subtype & 0x0F;
        }

        private static SpriteMappingPiece fragmentPiece(
                ObjectRenderManager renderManager,
                String artKey,
                int wallFrameIndex,
                int fragmentIndex) {
            if (renderManager == null) {
                return null;
            }
            ObjectSpriteSheet sheet = renderManager.getSheet(artKey);
            if (sheet == null || wallFrameIndex < 0 || wallFrameIndex >= sheet.getFrameCount()) {
                return null;
            }
            SpriteMappingFrame frame = sheet.getFrame(wallFrameIndex);
            if (frame == null || frame.pieces() == null
                    || fragmentIndex < 0 || fragmentIndex >= frame.pieces().size()) {
                return null;
            }
            return frame.pieces().get(fragmentIndex);
        }
    }
}
