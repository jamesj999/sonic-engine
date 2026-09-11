package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x76 — SYZ Boss blocks that Eggman picks up.
 * ROM: docs/s1disasm/_incObj/76 SYZ Boss Blocks.asm
 *
 * 10 blocks spawned in a row at boss_syz_x + $10, spacing $20, Y = $582.
 * Each block has a column index (0-9).
 *
 * States:
 *   SOLID    — normal solid platform, player can stand on it
 *   GRABBED  — held by boss, follows boss position at Y + $2C
 *   BREAKING — spawns 4 quarter fragments, then deletes
 *   FRAGMENT — falling quarter piece with gravity (routine 4 in ROM)
 *
 * Solid collision: d1=$1B, d2=$10, d3=$11
 * Art: Map_BossBlock, make_art_tile(ArtTile_Level,2,0) — level art, palette line 2
 */
public class Sonic1BossBlockInstance extends AbstractObjectInstance
        implements SolidObjectProvider, RewindRecreatable {

    // Block states
    private static final int STATE_SOLID = 0;
    private static final int STATE_GRABBED = 1;
    private static final int STATE_BREAKING = 2;
    private static final int STATE_FRAGMENT = 3;

    // Solid collision: d1=$1B, d2=$10, d3=$11
    private static final SolidObjectParams SOLID_PARAMS =
            SolidObjectParams.of(0x1B, 0x10, 0x11);

    // Arena constants
    private static final int BOSS_SYZ_X = 0x2C00;
    private static final int BLOCK_START_X = BOSS_SYZ_X + 0x10;
    private static final int BLOCK_SPACING = 0x20;
    private static final int BLOCK_Y = 0x582;
    private static final int BLOCK_COUNT = 10;

    // Y offset below boss when grabbed
    private static final int GRAB_Y_OFFSET = 0x2C;

    // ROM: BossBlock_FragSpeed — xVel, yVel for each of 4 fragments
    private static final int[][] FRAG_VELOCITIES = {
            {-0x180, -0x200},
            { 0x180, -0x200},
            {-0x100, -0x100},
            { 0x100, -0x100}
    };

    // ROM: BossBlock_FragPos — x, y offset for each of 4 fragments
    private static final int[][] FRAG_POSITIONS = {
            { -8, -8},
            {0x10,   0},
            {   0, 0x10},
            {0x10, 0x10}
    };

    // ROM: ObjectFall gravity constant
    private static final int GRAVITY = 0x38;

    // Mutable position (blocks can move when grabbed, fragments fall)
    private int x;
    private int y;

    // Instance fields
    // Un-finaled for rewind: blockColumn is NOT spawn-derivable for the fragment form
    // (it is -1 while spawn.subtype()==0), so the generic field capturer reapplies the
    // captured value after the recreate hook uses the public column ctor.
    private int blockColumn;   // 0-9, from low byte of obSubtype
    private int blockState;
    private Sonic1SYZBossInstance grabbingBoss;

    // Fragment physics (only used in FRAGMENT state)
    private int xVel;
    private int yVel;
    private int xFixed;
    private int yFixed;
    private int fragmentFrame;       // 1-4 for quarter pieces

    /**
     * Create a single boss block at the specified column.
     * ROM: BossBlock_MakeBlock
     */
    public Sonic1BossBlockInstance(int column) {
        super(new ObjectSpawn(
                BLOCK_START_X + (column * BLOCK_SPACING),
                BLOCK_Y,
                Sonic1ObjectIds.SYZ_BOSS_BLOCK,
                column,  // subtype = column index
                0, false, 0),
                "BossBlock");
        this.blockColumn = column;
        this.blockState = STATE_SOLID;
        this.x = BLOCK_START_X + (column * BLOCK_SPACING);
        this.y = BLOCK_Y;
    }

    Sonic1BossBlockInstance() {
        this(0);
    }

    @Override
    public Sonic1BossBlockInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic1BossBlockInstance block = new Sonic1BossBlockInstance(ctx.spawn().subtype());
        if (ctx.objectServices() != null && ctx.objectServices().objectManager() != null) {
            for (ObjectInstance inst : ctx.objectServices().objectManager().getActiveObjects()) {
                if (inst instanceof Sonic1SYZBossInstance boss) {
                    block.relinkGrabbingBoss(boss);
                    break;
                }
            }
        }
        return block;
    }

    /**
     * Private constructor for fragment pieces.
     */
    private Sonic1BossBlockInstance(int x, int y, int xVel, int yVel, int frame) {
        super(new ObjectSpawn(x, y, Sonic1ObjectIds.SYZ_BOSS_BLOCK, 0, 0, false, 0),
                "BossBlockFrag");
        this.blockColumn = -1;
        this.blockState = STATE_FRAGMENT;
        this.x = x;
        this.y = y;
        this.xVel = xVel;
        this.yVel = yVel;
        this.xFixed = x << 16;
        this.yFixed = y << 16;
        this.fragmentFrame = frame;
    }

    /**
     * Spawn all 10 boss blocks and add them to the object manager.
     * Called from level events (DLE_SYZ3main).
     * ROM: BossBlock_Main spawning loop.
     */
    public static void spawnAllBlocks() {
        var objectManager = staticObjectManager();
        if (objectManager == null) {
            return;
        }
        // Called from Sonic1SYZEvents (LevelEventManager), not from inside an
        // AbstractObjectInstance — spawnFreeChild() is unreachable here, so use
        // ObjectManager.createDynamicObject which sets CONSTRUCTION_CONTEXT
        // before invoking the factory.
        for (int col = 0; col < BLOCK_COUNT; col++) {
            final int colIndex = col;
            objectManager.createDynamicObject(() -> new Sonic1BossBlockInstance(colIndex));
        }
    }

    // ========================================================================
    // Position overrides (mutable position for grabbed/fragment states)
    // ========================================================================

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    // ========================================================================
    // Public accessors for boss interaction
    // ========================================================================

    public int getBlockColumn() {
        return blockColumn;
    }

    public boolean isGrabbed() {
        return blockState == STATE_GRABBED;
    }

    /**
     * Called by boss when grabbing this block.
     * ROM: move.b #-1,objoff_29(a1) + move.l a0,objoff_34(a1)
     */
    public void setGrabbedByBoss(Sonic1SYZBossInstance boss) {
        this.grabbingBoss = boss;
        this.blockState = STATE_GRABBED;
    }

    /**
     * Rewind relink: reattach the grabbing-boss object reference to the live SYZ boss
     * after a held-rewind restore, WITHOUT touching blockState (the generic capturer
     * reapplies the captured blockState; setGrabbedByBoss() would corrupt it). Only
     * meaningful when blockState==STATE_GRABBED; harmless otherwise.
     */
    public void relinkGrabbingBoss(Sonic1SYZBossInstance boss) {
        this.grabbingBoss = boss;
    }

    /**
     * Called by boss when releasing the block — triggers break into 4 fragments.
     * ROM: move.b #$A,objoff_29(a1) → BossBlock_Break
     */
    public void releaseAndBreak() {
        this.blockState = STATE_BREAKING;
    }

    // ========================================================================
    // SolidObjectProvider
    // ========================================================================

    /**
     * ROM: the solid boss blocks (BossBlock_Action routine 2 -> BossBlock_Solid ->
     * SolidObject -> BossBlock_Display) have NO out_of_range / DeleteObject path
     * (docs/s1disasm/_incObj/75, 76 Boss - SYZ Main and Blocks.asm:757-791). All 10
     * blocks are created at once by BossBlock_Main's FindFreeObj loop (x =
     * boss_syz_x+$10 .. +$130 = 0x2C10..0x2D30, asm:725-753) while the camera is
     * still left of the arena, so the two rightmost blocks (0x2D10, 0x2D30) spawn
     * off-screen and must stay alive for the player to walk onto them. Mark the
     * solid/grabbed/breaking block persistent so the engine's generic out-of-range
     * unload does not cull the off-screen blocks (which would never respawn — they
     * are dynamically spawned, not layout objects). Fragments (routine 4) DO delete
     * off-screen (BossBlock_Frag: tst.b obRender / bpl BossBlock_Delete, asm:798-804)
     * and are handled by updateFragment()'s own isOnScreen() check, so they remain
     * non-persistent.
     */
    @Override
    public boolean isPersistent() {
        return blockState != STATE_FRAGMENT;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return blockState == STATE_SOLID;
    }

    // ========================================================================
    // Update logic
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (blockState) {
            case STATE_SOLID -> { /* Solid collision handled by engine */ }
            case STATE_GRABBED -> updateGrabbed();
            case STATE_BREAKING -> doBreak();
            case STATE_FRAGMENT -> updateFragment();
        }
    }

    /**
     * ROM: loc_19718 — Track boss position while grabbed.
     * Block follows boss X, boss Y + $2C.
     * Also adds boss Y velocity displacement for smooth tracking.
     */
    private void updateGrabbed() {
        if (grabbingBoss == null || grabbingBoss.isDefeated()) {
            // ROM: tst.b obColProp(a1) / beq.s → break if boss hitcount 0
            blockState = STATE_BREAKING;
            return;
        }

        // ROM: move.w obX(a1),obX(a0) / move.w obY(a1),obY(a0) / addi.w #$2C,obY(a0)
        x = grabbingBoss.getState().x;
        y = grabbingBoss.getState().y + GRAB_Y_OFFSET;

        // ROM: move.w obVelY(a1),d0 / ext.l d0 / asr.l #8,d0 / add.w d0,obY(a0)
        // Add boss Y velocity as pixel displacement for smooth tracking
        int bossYVel = grabbingBoss.getState().yVel;
        y += (bossYVel >> 8);
    }

    /**
     * ROM: BossBlock_Break — Create 4 quarter fragments with velocities.
     */
    private void doBreak() {
        if (services().objectManager() != null) {
            for (int i = 0; i < 4; i++) {
                final int fragX = x + FRAG_POSITIONS[i][0];
                final int fragY = y + FRAG_POSITIONS[i][1];
                final int fragXVel = FRAG_VELOCITIES[i][0];
                final int fragYVel = FRAG_VELOCITIES[i][1];
                final int fragFrame = i + 1; // frames 1-4 for quarter pieces

                spawnFreeChild(() -> new Sonic1BossBlockInstance(
                        fragX, fragY, fragXVel, fragYVel, fragFrame));
            }
        }

        // ROM: sfx_WallSmash
        services().playSfx(Sonic1Sfx.WALL_SMASH.id);

        // Delete this block
        setDestroyed(true);
    }

    /**
     * ROM: loc_19762 (Routine 4) — ObjectFall + DisplaySprite.
     * Fragment falls with gravity and deletes when off screen.
     */
    private void updateFragment() {
        // ROM: tst.b obRender(a0) / bpl.s BossBlock_Delete
        if (!isOnScreen()) {
            setDestroyed(true);
            return;
        }

        // ROM: ObjectFall — apply gravity and move
        yVel += GRAVITY;
        xFixed += (xVel << 8);
        yFixed += (yVel << 8);
        x = xFixed >> 16;
        y = yFixed >> 16;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        // Use SYZ_BOSS_BLOCK art key (loaded from level patterns)
        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.SYZ_BOSS_BLOCK);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        int frame = (blockState == STATE_FRAGMENT) ? fragmentFrame : 0;
        renderer.drawFrameIndex(frame, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return 3; // ROM: obPriority = 3
    }
}
