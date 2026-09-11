package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x74 — MZ Boss Fire.
 * Reference: docs/s1disasm/_incObj/74 MZ Boss Fire.asm
 */
public class Sonic1BossFireInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    // Main routine (obRoutine)
    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_ACTION = 2;
    private static final int ROUTINE_DELETE_ANIM = 4;
    private static final int ROUTINE_DELETE = 6;

    // Action routine (ob2ndRout) while obRoutine == 2
    private static final int STATE_DROP = 0;
    private static final int STATE_MAKE_FLAME = 2;
    private static final int STATE_DUPLICATE = 4;
    private static final int STATE_FALL_EDGE = 6;

    // Arena constants (Constants.asm)
    private static final int BOSS_MZ_X = 0x1800;
    private static final int BOSS_MZ_Y = 0x210;
    private static final int LAVA_LEVEL_Y = BOSS_MZ_Y + 0xD8;    // $2E8
    private static final int RIGHT_BOUNDARY_X = BOSS_MZ_X + 0x140; // $1940

    // Physics
    private static final int DROP_GRAVITY = 0x18;
    private static final int FLAME_X_VEL = 0xA0;
    private static final int BOUNCE_GRAVITY = 0x24;

    // Visual/animation
    private static final int ANIM_SPEED = 6; // Ani_Fire speed 5 => every 6 frames
    private static final int[] VERT_ANIM_FRAMES = {0, 0, 1, 1};
    private static final boolean[] VERT_ANIM_HFLIP = {false, true, false, true};
    private static final int[] HORIZ_ANIM_FRAMES = {3, 3, 4, 4};
    private static final boolean[] HORIZ_ANIM_VFLIP = {false, true, false, true};
    private static final int VERT_COLLIDE_FRAME = 2;

    private static final int Y_RADIUS = 8;
    private static final int COLLISION_TYPE_FIRE = 0x8B;

    private int routine;
    private int routineSecondary;
    private int subtype;

    private int currentX;
    private int currentY;
    private int xVel;       // 8.8
    private int yVel;       // 8.8
    private int xFixed;     // 16.16
    private int yFixed;     // 16.16

    // objoff mirrors used by this object
    private int prevX;      // objoff_30
    private int edgeX;      // objoff_32
    private int savedY;     // objoff_38
    private int counter29;  // objoff_29 (used for drop delay, bounce count, AND delete countdown)

    // Status/GFX flags used by AnimateSprite path
    private boolean statusHFlip; // obStatus bit 0
    private boolean statusVFlip; // obStatus bit 1
    @SuppressWarnings("unused")
    private boolean gfxHighBit;  // obGfx bit 7

    // Animation state
    private int animId;          // 0=vertical, 1=vertical collide, 2=horizontal
    private int animStep;
    private int animTimer;
    private int collideAnimTimer;

    private boolean collisionActive;

    public Sonic1BossFireInstance(ObjectSpawn spawn) {
        super(spawn, "BossFire");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xFixed = currentX << 16;
        this.yFixed = currentY << 16;
        this.savedY = currentY;
        this.subtype = spawn.subtype() & 0xFF;
        this.routine = ROUTINE_INIT;
        this.routineSecondary = STATE_DROP;
        this.animId = 0;
        this.animStep = 0;
    }

    @Override
    public Sonic1BossFireInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic1BossFireInstance(ctx.spawn());
    }

    /**
     * Creates the short-lived stationary flame spawned by BossFire_Duplicate2.
     * ROM writes {@code move.w #$67,obSubtype(a1)}, i.e. obSubtype=0, objoff_29=$67.
     */
    private static Sonic1BossFireInstance createDuplicateDecayFlame(int x, int y) {
        ObjectSpawn spawn = new ObjectSpawn(x, y, Sonic1ObjectIds.BOSS_FIRE, 0, 0, false, 0);
        Sonic1BossFireInstance flame = new Sonic1BossFireInstance(spawn);
        flame.routine = ROUTINE_DELETE_ANIM;
        flame.routineSecondary = STATE_DROP;
        flame.counter29 = 0x67;
        flame.collisionActive = true;
        flame.animId = 0;
        flame.animStep = 0;
        flame.animTimer = 0;
        flame.collideAnimTimer = 0;
        return flame;
    }

    /**
     * Copy constructor used by BossFire_MakeFlame twin spawn.
     * It copies live action state but does not replay spawn SFX.
     */
    private Sonic1BossFireInstance(Sonic1BossFireInstance parent) {
        super(parent.spawn, "BossFire");
        this.routine = ROUTINE_ACTION;
        this.routineSecondary = STATE_DUPLICATE;
        this.subtype = parent.subtype;
        this.currentX = parent.currentX;
        this.currentY = parent.currentY;
        this.xVel = parent.xVel;
        this.yVel = parent.yVel;
        this.xFixed = parent.xFixed;
        this.yFixed = parent.yFixed;
        this.prevX = parent.prevX;
        this.edgeX = parent.edgeX;
        this.savedY = parent.savedY;
        this.counter29 = parent.counter29;
        this.statusHFlip = parent.statusHFlip;
        this.statusVFlip = parent.statusVFlip;
        this.gfxHighBit = parent.gfxHighBit;
        this.animId = parent.animId;
        this.animStep = parent.animStep;
        this.animTimer = parent.animTimer;
        this.collisionActive = parent.collisionActive;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (routine) {
            case ROUTINE_INIT -> updateInit();
            case ROUTINE_ACTION -> updateAction();
            case ROUTINE_DELETE_ANIM -> updateDeleteAnim();
            case ROUTINE_DELETE -> setDestroyed(true);
            default -> setDestroyed(true);
        }
    }

    private void updateInit() {
        // BossFire_Main
        routine = ROUTINE_ACTION;
        savedY = currentY;
        animId = 0;
        animStep = 0;
        animTimer = 0;
        collideAnimTimer = 0;
        statusHFlip = false;
        statusVFlip = false;
        gfxHighBit = false;
        collisionActive = false;

        if (subtype == 0) {
            // Subtype 0 normally arrives via move.w #$67,obSubtype(a1), where objoff_29
            // is preloaded. If it's not preloaded in this engine path, delete safely.
            if (counter29 <= 0) {
                setDestroyed(true);
                return;
            }
            collisionActive = true;
            routine = ROUTINE_DELETE_ANIM;
            return;
        }

        // Non-zero subtype: delayed drop + sfx_Fireball (BossFire_Main -> loc_1870A).
        // ROM loc_1870A has no rts/branch: it falls straight through into
        // BossFire_Action, so the spawn frame ALSO runs one BossFire_Drop tick
        // (plus SpeedToPos/AnimateSprite). Replicate that same-frame fall-through;
        // otherwise the drop begins a frame late, leaving the falling fireball
        // ~4px high at the contact frame and missing the player it should hurt.
        counter29 = 0x1E;
        services().playSfx(Sonic1Sfx.BURNING.id);
        routine = ROUTINE_ACTION;
        updateAction();
    }

    private void updateAction() {
        switch (routineSecondary) {
            case STATE_DROP -> updateDrop();
            case STATE_MAKE_FLAME -> updateMakeFlame();
            case STATE_DUPLICATE -> updateDuplicate();
            case STATE_FALL_EDGE -> updateFallEdge();
            default -> setDestroyed(true);
        }

        applyVelocity();
        updateAnimation();

        // BossFire_Action delete check
        if (currentY > LAVA_LEVEL_Y) {
            setDestroyed(true);
        }
    }

    private void updateDrop() {
        // BossFire_Drop: bset #1,obStatus while counting down.
        statusVFlip = true;
        counter29--;
        if (counter29 >= 0) {
            return;
        }

        // Activate collision and start falling.
        collisionActive = true;
        subtype = 0;
        yVel += DROP_GRAVITY;
        statusVFlip = false;

        TerrainCheckResult floor = checkFloorDist();
        if (floor.foundSurface() && floor.distance() < 0) {
            routineSecondary = STATE_MAKE_FLAME;
        }
    }

    private void updateMakeFlame() {
        // BossFire_MakeFlame
        currentY -= 2;
        yFixed = currentY << 16;
        gfxHighBit = true;
        xVel = FLAME_X_VEL;
        yVel = 0;
        prevX = currentX;
        savedY = currentY;
        edgeX = currentX;
        counter29 = 3;
        animId = 2;
        animStep = 0;
        animTimer = 0;
        collisionActive = true;

        // Spawn mirrored twin by copying parent state then negating X speed.
        if (services().objectManager() != null) {
            spawnFreeChild(() -> {
                Sonic1BossFireInstance twin = new Sonic1BossFireInstance(this);
                twin.xVel = -FLAME_X_VEL;
                twin.xFixed = twin.currentX << 16;
                return twin;
            });
        }

        routineSecondary = STATE_DUPLICATE;
    }

    private void updateDuplicate() {
        // BossFire_Duplicate
        TerrainCheckResult floor = checkFloorDist();
        if (!floor.foundSurface() || floor.distance() >= 0) {
            routineSecondary = STATE_FALL_EDGE;
            return;
        }

        if (currentX > RIGHT_BOUNDARY_X) {
            // ROM: addq.b #2,obRoutine(a0) from routine 2 -> routine 4 (loc_18886).
            routine = ROUTINE_DELETE_ANIM;
            return;
        }

        int curBlock = currentX & 0x10;
        int prevBlock = prevX & 0x10;
        if (currentX != prevX && curBlock != prevBlock) {
            spawnDuplicate();
            edgeX = currentX;
        }

        prevX = currentX;
    }

    private void spawnDuplicate() {
        if (services().objectManager() == null) {
            return;
        }
        spawnFreeChild(() -> createDuplicateDecayFlame(currentX, currentY));
    }

    private void updateFallEdge() {
        // BossFire_FallEdge
        statusVFlip = false;
        yVel += BOUNCE_GRAVITY;

        int deltaX = Math.abs(currentX - edgeX);
        if (deltaX == 0x12) {
            gfxHighBit = false;
        }

        TerrainCheckResult floor = checkFloorDist();
        if (!floor.foundSurface() || floor.distance() >= 0) {
            return;
        }

        counter29--;
        if (counter29 <= 0) {
            setDestroyed(true);
            return;
        }

        yVel = 0;
        currentX = edgeX;
        currentY = savedY;
        xFixed = currentX << 16;
        yFixed = currentY << 16;
        gfxHighBit = true;
        routineSecondary = STATE_DUPLICATE;
    }

    private void updateDeleteAnim() {
        // loc_18886
        gfxHighBit = true;
        counter29--;
        if (counter29 == 0) {
            animId = 1; // .vertcollide
            animStep = 0;
            animTimer = 0;
            collideAnimTimer = 0;
            currentY -= 4;
            yFixed = currentY << 16;
            collisionActive = false;
        }

        updateAnimation();
        if (animId == 1) {
            collideAnimTimer++;
            if (collideAnimTimer >= ANIM_SPEED) {
                routine = ROUTINE_DELETE;
            }
        }
    }

    private void applyVelocity() {
        xFixed += (xVel << 8);
        yFixed += (yVel << 8);
        currentX = xFixed >> 16;
        currentY = yFixed >> 16;
    }

    private void updateAnimation() {
        if (animId == 1) {
            return; // single collide frame handled in rendering + timer above
        }

        animTimer++;
        if (animTimer < ANIM_SPEED) {
            return;
        }

        animTimer = 0;
        if (animId == 2) {
            animStep = (animStep + 1) & 3;
        } else {
            animStep = (animStep + 1) & 3;
        }
    }

    private TerrainCheckResult checkFloorDist() {
        // ROM BossFire uses ObjFloorDist (terrain probe), not solid object contact checks.
        return ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
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
    public int getPriorityBucket() {
        return 5;
    }

    @Override
    public int getCollisionFlags() {
        return collisionActive ? COLLISION_TYPE_FIRE : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.MZ_FIREBALL);
        if (renderer == null) return;

        int frameIndex;
        boolean hFlip = statusHFlip;
        boolean vFlip = statusVFlip;

        if (animId == 1) {
            frameIndex = VERT_COLLIDE_FRAME;
        } else if (animId == 2) {
            frameIndex = HORIZ_ANIM_FRAMES[animStep];
            // Horizontal fire frames are authored facing right; mirror for leftward travel.
            hFlip ^= (xVel < 0);
            vFlip ^= HORIZ_ANIM_VFLIP[animStep];
        } else {
            frameIndex = VERT_ANIM_FRAMES[animStep];
            hFlip ^= VERT_ANIM_HFLIP[animStep];
        }

        renderer.drawFrameIndex(frameIndex, currentX, currentY, hFlip, vFlip);
    }
}
