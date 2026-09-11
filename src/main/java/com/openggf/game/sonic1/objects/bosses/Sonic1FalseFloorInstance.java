package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
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
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 1 Object 0x83 - False Floor (SBZ2 boss arena collapsing floor).
 * <p>
 * The master object spawns 8 child block objects that together form a
 * solid platform. When signaled by ScrapEggman (via {@link #signalDisintegrate()}),
 * the master sequentially tells each child to break apart into 4 falling
 * fragments, collapsing the floor from left to right.
 * <p>
 * Based on the Sonic 1 disassembly (Obj83 / FFloor).
 * <p>
 * Key constants from disassembly:
 * <pre>
 *   BOSS_SBZ2_X = 0x2050
 *   BOSS_SBZ2_Y = 0x510
 *   Master position: (0x2080, 0x5D0)
 *   Child starting X: 0x2010, each child 0x20 apart, 8 total
 *   Child Y: 0x5D0
 * </pre>
 */
public class Sonic1FalseFloorInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener,
        Sonic1ScrapEggmanInstance.Disintegratable, SpawnRewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(Sonic1FalseFloorInstance.class.getName());

    // ---- Boss arena constants from disassembly ----
    private static final int BOSS_SBZ2_X = Sonic1Constants.BOSS_SBZ2_X;
    private static final int BOSS_SBZ2_Y = Sonic1Constants.BOSS_SBZ2_Y;

    // Master position: (BOSS_SBZ2_X + 0x30, BOSS_SBZ2_Y + 0xC0)
    private static final int MASTER_X = BOSS_SBZ2_X + 0x30; // 0x2080
    private static final int MASTER_Y = BOSS_SBZ2_Y + 0xC0; // 0x5D0

    // Child block positions
    private static final int CHILD_START_X = BOSS_SBZ2_X - 0x40; // 0x2010
    private static final int CHILD_SPACING = 0x20; // 32 pixels apart
    private static final int CHILD_Y = MASTER_Y; // 0x5D0
    private static final int CHILD_COUNT = 8;

    // ROM: obActWid = 0x80, obHeight = 0x10
    private static final int MASTER_HALF_HEIGHT = 0x10;

    // Timer decrement rate for sequential disintegration (ROM: subi.b #$E)
    private static final int TIMER_DECREMENT = 0x0E;

    // Solid object base X for shrinking calculation: boss_sbz2_x + 0xB0 = 0x2100
    private static final int SOLID_BASE_X = BOSS_SBZ2_X + 0xB0;

    // ---- State machine routines ----
    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_SOLID_WAITING = 2;
    private static final int ROUTINE_DISINTEGRATING = 4;
    private static final int ROUTINE_CLEANUP = 6;
    private static final ObjectPlayerParticipationPolicy CLEANUP_PARTICIPATION =
            ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS;

    // ---- Instance state ----
    private int routine = ROUTINE_INIT;
    private int currentX;
    private int currentY;
    private boolean goSignal = false;

    /**
     * Byte-sized timer for sequential disintegration.
     * ROM uses subi.b #$E with bcc (carry clear = no borrow = skip).
     * We track as int but mask to byte for accurate wrapping.
     */
    private int disintegrateTimer = 0;
    private int currentFrame = 0;

    /** Dynamic half-width that shrinks as blocks are destroyed */
    private int currentHalfWidth;

    /**
     * X offset for solid collision, relative to MASTER_X.
     * The ROM shifts the solid center rightward as blocks break from the left,
     * but moving currentX causes the engine's solid contact resolution to push
     * Sonic sideways. Using offsetX in SolidObjectParams avoids this.
     */
    private int solidOffsetX = 0;

    private final List<FalseFloorBlock> childBlocks = new ArrayList<>();
    public Sonic1FalseFloorInstance(ObjectSpawn spawn) {
        super(spawn, "FalseFloor");
        this.currentX = MASTER_X;
        this.currentY = MASTER_Y;
        this.currentHalfWidth = 0x80;
        updateDynamicSpawn(currentX, currentY);
    }

    /**
     * Signal from ScrapEggman to begin the floor collapse sequence.
     * ROM: move.w #"GO",obSubtype(a1)
     */
    @Override
    public void signalDisintegrate() {
        this.goSignal = true;
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
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        switch (routine) {
            case ROUTINE_INIT -> updateInit();
            case ROUTINE_SOLID_WAITING -> updateSolidWaiting();
            case ROUTINE_DISINTEGRATING -> updateDisintegrating();
            case ROUTINE_CLEANUP -> updateCleanup(player);
        }

        if (!isDestroyed() && (routine == ROUTINE_SOLID_WAITING || routine == ROUTINE_DISINTEGRATING)) {
            super.checkpointAll();
        }
    }

    // ---- Routine 0: FFloor_Main (initialization) ----
    private void updateInit() {
        currentX = MASTER_X;
        currentY = MASTER_Y;
        updateDynamicSpawn(currentX, currentY);

        if (services().objectManager() == null) {
            return;
        }

        // Spawn 8 child blocks (ROM: moveq #7,d6 -> dbf loop = 8 iterations)
        for (int i = 0; i < CHILD_COUNT; i++) {
            final int childX = CHILD_START_X + (i * CHILD_SPACING);
            final int slot = i;
            FalseFloorBlock block = spawnFreeChild(() -> new FalseFloorBlock(childX, CHILD_Y, slot));
            childBlocks.add(block);
        }

        routine = ROUTINE_SOLID_WAITING;
        LOGGER.fine(() -> String.format("FalseFloor init: master at (%d,%d), spawned %d children",
                currentX, currentY, CHILD_COUNT));
    }

    // ---- Routine 2: FFloor_ChkBreak -> FFloor_Solid ----
    // Solid platform waiting for "GO" signal from Eggman.
    // ROM: FFloor_Solid does dynamic solid width calculation based on obFrame.
    private void updateSolidWaiting() {
        if (goSignal) {
            currentFrame = 0;
            routine = ROUTINE_DISINTEGRATING;
            disintegrateTimer = 0;
        }

        updateSolidDimensions();
    }

    // ---- Routine 4: Sequential disintegration ----
    // ROM: subi.b #$E,obTimeFrame(a0); bcc.s FFloor_Solid2
    // Uses unsigned byte subtraction - borrow triggers next child break.
    private void updateDisintegrating() {
        int oldTimer = disintegrateTimer & 0xFF;
        int newTimer = oldTimer - TIMER_DECREMENT;
        boolean borrow = newTimer < 0; // carry set = borrow occurred
        disintegrateTimer = newTimer & 0xFF;

        if (borrow) {
            // Signal next child block to break
            if (currentFrame < childBlocks.size()) {
                // ROM: move.w objoff_30(a0,d0.w),d0 -> get child pointer
                //      move.w #"GO",obSubtype(a1)
                FalseFloorBlock block = childBlocks.get(currentFrame);
                block.signalBreak();
                currentFrame++;
            }

            // Check if all children have been signaled (ROM: cmpi.b #8,obFrame)
            if (currentFrame >= CHILD_COUNT) {
                routine = ROUTINE_CLEANUP;
                return;
            }
        }

        // FFloor_Solid2 -> FFloor_Solid: update solid dimensions
        updateSolidDimensions();
    }

    // ---- Routine 6: Cleanup ----
    // ROM: bclr #3,obStatus(a0); bclr #3,(v_player+obStatus).w; bra DeleteObject
    private void updateCleanup(AbstractPlayableSprite player) {
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null) {
            ObjectPlayerQuery query = new ObjectPlayerQuery(
                    () -> player,
                    () -> services().playerQuery().sidekicks());
            for (PlayableEntity participant : query.playersFor(CLEANUP_PARTICIPATION)) {
                objectManager.clearRidingObject(participant);
            }
        }

        setDestroyed(true);
    }

    /**
     * ROM FFloor_Solid: Dynamically adjusts solid area as blocks are destroyed.
     * <pre>
     *   d0 = (8 - frame) * 16
     *   obActWid = d0
     *   obX = 0x2100 - d0
     *   d1 = 0x0B + d0 (half-width for SolidObject)
     * </pre>
     *
     * The ROM shifts the solid center rightward ({@code obX = SOLID_BASE_X - halfWidth})
     * as blocks break from the left. We keep {@code currentX} fixed at MASTER_X
     * and express the shift via {@link SolidObjectParams#offsetX()} to avoid the
     * engine's solid contact resolution pushing the player sideways.
     */
    private void updateSolidDimensions() {
        int blocksRemaining = 8 - currentFrame;
        int halfWidth = blocksRemaining * 16; // (8 - frame) << 4
        currentHalfWidth = halfWidth;

        // ROM: obX = SOLID_BASE_X - halfWidth.
        // We express this as an offset from the fixed MASTER_X position:
        //   solidAnchorX = MASTER_X + solidOffsetX = SOLID_BASE_X - halfWidth
        //   solidOffsetX = (SOLID_BASE_X - halfWidth) - MASTER_X
        solidOffsetX = (SOLID_BASE_X - halfWidth) - MASTER_X;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Master object is invisible; children handle rendering.
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    // ---- SolidObjectProvider ----

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: d1 = 0x0B + d0, d2 = 0x10, d3 = 0x11
        int halfWidth = 0x0B + currentHalfWidth;
        return SolidObjectParams.of(halfWidth, MASTER_HALF_HEIGHT, MASTER_HALF_HEIGHT + 1,
                solidOffsetX, 0);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    /**
     * The floor's SST {@code obActWid}, which {@code Sonic_Balance} reads off
     * the stood-on object (docs/s1disasm/_incObj/01 Sonic.asm:423).
     *
     * <p>{@code FFloor_Solid} computes the remaining half-width in {@code d0},
     * stores it with {@code move.b d0,obActWid(a0)}, and only then passes
     * {@code d1 = sonic_solid_width + d0} to {@code SolidObject}
     * (docs/s1disasm/_incObj/82, 83 SBZ Eggman Cutscene and Crumbling
     * Floor.asm:265-280). So {@code obActWid} is {@link #currentHalfWidth}
     * itself, and the collision width modelled by {@link #getSolidParams()} is
     * that plus {@code $B} — the two differ by exactly Sonic's solid width.
     *
     * <p>Required rather than tidy: the base {@code getBalanceWidthPixels()}
     * returns {@code getSolidParams().halfWidth()} for top-solid objects, so
     * without this override the balance test received {@code d0 + $B} where the
     * ROM uses {@code d0}.
     *
     * <p><b>This value is dynamic and must stay dynamic.</b> {@code d0} is
     * {@code (8 - blocks_broken) << 4}, recomputed in
     * {@link #updateSolidDimensions()} as the floor crumbles, so it walks from
     * {@code 0x80} down to zero across the fight. A constant would match the
     * ROM at exactly one width and be wrong at every other, while still looking
     * like a fix.
     *
     * <p>Note, and deliberately not addressed here: this class declares
     * {@link #isTopSolidOnly()} {@code true} while {@code FFloor_Solid} ends in
     * {@code jmp (SolidObject).l}, a full solid. That is a separate question
     * from the width, recorded in the trace frontier log, and changing it here
     * would make this commit alter two decisions instead of one.
     */
    @Override
    public int getBalanceWidthPixels() {
        return currentHalfWidth;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed() && (routine == ROUTINE_SOLID_WAITING || routine == ROUTINE_DISINTEGRATING);
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Handled by ObjectManager
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    /**
     * Rewind-restore hook: re-registers a recreated {@link FalseFloorBlock} into
     * {@code childBlocks} so the disintegration sequence
     * ({@code childBlocks.get(currentFrame).signalBreak()}) still resolves after a
     * held rewind. Inserts ordered by {@link FalseFloorBlock#blockIndex} so the
     * left-to-right collapse order matches the ROM regardless of restore order.
     */
    public void reattachChildBlock(FalseFloorBlock block) {
        if (block == null) {
            return;
        }
        int insertAt = childBlocks.size();
        for (int i = 0; i < childBlocks.size(); i++) {
            if (childBlocks.get(i).blockIndex > block.blockIndex) {
                insertAt = i;
                break;
            }
        }
        childBlocks.add(insertAt, block);
    }

    // =========================================================================
    // Inner class: FalseFloorBlock - child block (routine 8)
    // =========================================================================

    /**
     * A single 32x32 block of the false floor. Eight of these form the complete
     * platform. Each block waits until signaled to break apart into 4 falling
     * fragments.
     * <p>
     * ROM: routine 8 = waiting, routine 0xA = falling fragment.
     */
    public static class FalseFloorBlock extends AbstractObjectInstance implements RewindRecreatable {

        private static final int BLOCK_HALF_WIDTH = 0x10;  // 16 pixels
        private static final int BLOCK_HALF_HEIGHT = 0x10; // 16 pixels

        // Fragment Y velocities from ROM: FFloor_FragSpeed
        private static final int[] FRAGMENT_Y_VEL = {0x80, 0x00, 0x120, 0xC0};

        // Fragment position offsets from ROM: FFloor_FragPos (X, Y pairs)
        private static final int[][] FRAGMENT_OFFSETS = {
                {-8, -8},    // Fragment 0: top-left, frame 1
                {0x10, 0},   // Fragment 1: top-right, frame 2
                {0, 0x10},   // Fragment 2: bottom-left, frame 3
                {0x10, 0x10} // Fragment 3: bottom-right, frame 4
        };

        // Fragment mapping frame indices (1-4 for quarter pieces)
        private static final int[] FRAGMENT_FRAMES = {1, 2, 3, 4};

        // Non-final so the generic rewind field capturer can reapply the captured
        // values after RewindRecreatable recreates the block with placeholder
        // (0, 0, 0) ctor args.
        private int blockIndex;
        private int currentX;
        private int currentY;
        private boolean broken = false;
        private boolean goSignal = false;

        FalseFloorBlock() {
            this(0, 0, 0);
        }

        public FalseFloorBlock(int x, int y, int blockIndex) {
            super(new ObjectSpawn(x, y, 0x83, 0, 0, false, 0), "FalseFloorBlock");
            this.currentX = x;
            this.currentY = y;
            this.blockIndex = blockIndex;
        }

        void signalBreak() {
            this.goSignal = true;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            if (ctx == null || ctx.objectServices() == null
                    || ctx.objectServices().objectManager() == null) {
                return null;
            }
            Sonic1FalseFloorInstance master = null;
            for (ObjectInstance inst : ctx.objectServices().objectManager().getActiveObjects()) {
                if (inst instanceof Sonic1FalseFloorInstance falseFloor) {
                    master = falseFloor;
                    break;
                }
            }
            if (master == null) {
                return null;
            }
            FalseFloorBlock block = new FalseFloorBlock(0, 0, 0);
            master.reattachChildBlock(block);
            return block;
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
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (isDestroyed() || broken) {
                return;
            }

            if (goSignal) {
                breakApart();
            }
        }

        private void breakApart() {
            broken = true;

            if (services().objectManager() == null) {
                setDestroyed(true);
                return;
            }

            // ROM: FFloor_Break - first fragment reuses parent, remaining 3 are new objects.
            // We create all 4 as new objects since our architecture differs.
            for (int i = 0; i < 4; i++) {
                final int fragX = currentX + FRAGMENT_OFFSETS[i][0];
                final int fragY = currentY + FRAGMENT_OFFSETS[i][1];
                final int fragYVel = FRAGMENT_Y_VEL[i];
                final int fragFrame = FRAGMENT_FRAMES[i];

                spawnFreeChild(() -> new FalseFloorFragment(
                        fragX, fragY, fragYVel, fragFrame));
            }

            // ROM: move.w #sfx_WallSmash,d0; jsr (QueueSound2).l
            services().playSfx(Sonic1Sfx.WALL_SMASH.id);

            setDestroyed(true);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || broken) {
                return;
            }

            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager != null) {
                PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.SBZ2_FALSE_FLOOR);
                if (renderer != null && renderer.isReady()) {
                    // Frame 0: whole 32x32 block
                    renderer.drawFrameIndex(0, currentX, currentY, false, false);
                }
            }
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(3); // ROM: obPriority = 3
        }
    }

    // =========================================================================
    // Inner class: FalseFloorFragment - falling quarter-block (routine 0xA)
    // =========================================================================

    /**
     * A falling fragment (quarter of a 32x32 block) created when a
     * {@link FalseFloorBlock} breaks apart. Falls under gravity until off-screen.
     * <p>
     * ROM: routine $A - ObjectFall + DisplaySprite, deleted when off-screen.
     */
    public static class FalseFloorFragment extends AbstractObjectInstance implements RewindRecreatable {

        // ROM: ObjectFall gravity = addi.w #$38,obVelY(a0)
        private static final int GRAVITY = 0x38;

        private int currentX;
        private int currentY;
        private int subY; // 16.8 fixed-point Y position
        private int yVel; // 8.8 fixed-point Y velocity
        private int mappingFrame;

        public FalseFloorFragment(int x, int y, int initialYVel, int frame) {
            super(new ObjectSpawn(x, y, 0x83, 0, 0, false, 0), "FalseFloorFragment");
            this.currentX = x;
            this.currentY = y;
            this.subY = y << 8;
            this.yVel = initialYVel;
            this.mappingFrame = frame;
        }

        @Override
        public FalseFloorFragment recreateForRewind(RewindRecreateContext ctx) {
            return new FalseFloorFragment(ctx.spawn().x(), ctx.spawn().y(), 0, 0);
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
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (isDestroyed()) {
                return;
            }

            // ROM: tst.b obRender(a0); bpl.w DeleteObject (off-screen check)
            // Then: jsr (ObjectFall).l - apply gravity + SpeedToPos
            yVel += GRAVITY;
            subY += yVel;
            currentY = subY >> 8;

            // Delete when off-screen (beyond camera viewport + margin)
            if (!isOnScreen(48)) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }

            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager != null) {
                PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.SBZ2_FALSE_FLOOR);
                if (renderer != null && renderer.isReady()) {
                    renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
                }
            }
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(3); // ROM: obPriority = 3
        }
    }
}
