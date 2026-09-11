package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;

/**
 * AIZ miniboss {@code AIZMiniboss_FallingShot} child (ROM {@code loc_68C96}).
 *
 * <p>The production object is created by an existing
 * {@link AizMinibossFlameBarrelChild}, exactly as
 * {@code ChildObjDat_AIZMiniboss_BarrelShotAndFallingShot} does in the ROM.  The
 * FallingShot child subtype is always {@code $02}; the parent barrel supplies
 * its own subtype (0/2/4), its {@code $39} position counter, and its facing when
 * {@code AIZMiniboss_SetFallingShotDelay} reaches the camera-relative drop.</p>
 */
public class AizMinibossNapalmProjectile extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private static final int COLLISION_FLAGS_HAZARD = 0x98; // ObjDat_AIZMiniboss_BarrelShot
    private static final int FRAME_RISE_A = 0x0C;
    private static final int FRAME_RISE_B = 0x0D;
    private static final int PROJECTILE_PALETTE = 0;
    private static final int Y_RADIUS = 8; // FallingShot_Init: move.b #8,y_radius(a0)
    private static final int RISE_WAIT = 0x60; // BarrelShot_Init: move.w #$60,$2E(a0)
    private static final int DROP_PAUSE = 8; // FallingShot_StartPause
    private static final int[] FALLING_SHOT_DELAYS = {0, 0x20, 0x40};

    // AIZMiniboss_ShotSlotsRight/Left and AIZMiniboss_ShotXOffsetsRight/Left.
    private static final int[] SHOT_SLOTS_RIGHT = {
            2, 3, 4, 0, 0, 2, 4, 0, 1, 3, 4, 0, 0, 1, 4, 0
    };
    private static final int[] SHOT_SLOTS_LEFT = {
            3, 2, 0, 0, 4, 3, 1, 0, 4, 2, 0, 0, 3, 2, 1, 0
    };
    private static final int[] SHOT_X_OFFSETS_RIGHT = {0x24, 0x4C, 0x74, 0x9C, 0xC4};
    private static final int[] SHOT_X_OFFSETS_LEFT = {0x7C, 0xA4, 0xCC, 0xF4, 0x11C};

    /* ObjDat_AIZMiniboss_BarrelShot has no shield-reaction write in the native
     * FallingShot path, so this independent live route keeps the reaction byte
     * clear rather than borrowing the nearby barrel-shot approximation. */
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            false,
            true,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    private enum State {
        INIT,
        RISE,
        PAUSE,
        DROP_WAIT,
        FALL
    }

    /** ROM parent3(a0): the boss that owns the barrel graph. */
    private final AbstractBossInstance parent;
    /** ROM parent3(a0) for FallingShot: the existing barrel controller. */
    private final AizMinibossFlameBarrelChild barrel;

    // These are intentionally separate.  childSubtype is the CreateChild1_Normal
    // index ($02), while barrelSubtype is read from the parent barrel by the ROM.
    private int childSubtype;
    private int barrelSubtype;

    /* Standalone construction is retained for focused object tests.  Production
     * FallingShots never use these fallback values: the barrel owns the counter
     * and facing state. */
    private int standalonePositionCounter;
    private boolean standaloneHFlip;

    /* These are the object-RAM fields needed by the native routine. */
    private int currentX;
    private int currentY;
    private int xFixed;
    private int yFixed;
    private int yVel;
    private int timer;
    private int mappingFrame;
    private int animTimer;
    private boolean vFlip;
    private boolean needsInitSfx;
    private State state;

    /** Standalone probe constructor used by focused native-contract tests. */
    public AizMinibossNapalmProjectile(int startX, int startY) {
        this(null, null, startX, startY, 2, 0, false, 0);
    }

    /**
     * Production constructor for {@code ChildObjDat_...BarrelShotAndFallingShot}.
     * The child subtype is supplied explicitly because it is not the barrel
     * subtype read by {@code SetFallingShotDelay}.
     */
    public AizMinibossNapalmProjectile(AbstractBossInstance parent,
                                        AizMinibossFlameBarrelChild barrel,
                                        int startX,
                                        int startY,
                                        int childSubtype) {
        this(parent, barrel, startX, startY, childSubtype,
                barrel == null ? 0 : barrel.getBarrelSubtype(),
                false, 0);
    }

    /** Legacy standalone seam retained for native movement tests. */
    AizMinibossNapalmProjectile(int startX,
                                int startY,
                                int barrelSubtype,
                                boolean hFlip,
                                int positionCounter) {
        this(null, null, startX, startY, 2, barrelSubtype, hFlip, positionCounter);
    }

    private AizMinibossNapalmProjectile(AbstractBossInstance parent,
                                         AizMinibossFlameBarrelChild barrel,
                                         int startX,
                                         int startY,
                                         int childSubtype,
                                         int barrelSubtype,
                                         boolean hFlip,
                                         int positionCounter) {
        super(new ObjectSpawn(startX, startY, Sonic3kObjectIds.AIZ_MINIBOSS,
                childSubtype & 0xFF, 0, false, 0), "AIZNapalmProjectile");
        this.parent = parent;
        this.barrel = barrel;
        this.childSubtype = childSubtype & 0xFF;
        this.barrelSubtype = barrelSubtype & 0xFF;
        this.standaloneHFlip = hFlip;
        this.standalonePositionCounter = positionCounter & 0xFF;
        this.currentX = startX;
        this.currentY = startY;
        this.xFixed = startX << 16;
        this.yFixed = startY << 16;
        this.yVel = 0;
        this.timer = 0;
        this.mappingFrame = FRAME_RISE_A;
        this.animTimer = 0;
        this.vFlip = false;
        this.needsInitSfx = true;
        this.state = State.INIT;
    }

    /** Probe constructor retained for object-construction tests. */
    AizMinibossNapalmProjectile(ObjectSpawn spawn) {
        this(null, null, spawn.x(), spawn.y(), spawn.subtype(), 0, false, 0);
    }

    @Override
    public AizMinibossNapalmProjectile recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null) {
            return null;
        }
        AizMinibossInstance boss = AizMinibossRewindLinks.nearestBoss(ctx);
        AizMinibossFlameBarrelChild restoredBarrel = AizMinibossRewindLinks.nearestBarrel(ctx);
        if (boss == null || restoredBarrel == null) {
            return null;
        }
        return new AizMinibossNapalmProjectile(
                boss,
                restoredBarrel,
                ctx.spawn().x(),
                ctx.spawn().y(),
                ctx.spawn().subtype());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (parent != null && (parent.isDestroyed() || parent.getState().defeated)) {
            // FallingShot checks parent3(barrel)->parent3(boss).status bit 7
            // before publishing touch/draw; a defeated boss removes the shot,
            // while already-spawned explosion children remain independent.
            setDestroyed(true);
            return;
        }
        switch (state) {
            case INIT -> initializeNativeObject();
            case RISE -> updateRise();
            case PAUSE -> updatePause();
            case DROP_WAIT -> updateDropWait();
            case FALL -> updateFall();
        }
    }

    /** AIZMiniboss_FallingShot_Init -> AIZMiniboss_BarrelShot_Init. */
    private void initializeNativeObject() {
        if (needsInitSfx) {
            needsInitSfx = false;
            services().playSfx(Sonic3kSfx.PROJECTILE.id);
        }
        yVel = -0x400;
        timer = RISE_WAIT;
        mappingFrame = FRAME_RISE_A;
        // Animate_Raw is called on the first Rise dispatch.  A zero timer makes
        // that call consume the initial C/D prefix immediately; a timer of two
        // would introduce a frame not present in the ROM.
        animTimer = 0;
        state = State.RISE;
    }

    /** AIZMiniboss_FallingShot_Rise: Animate_Raw, MoveSprite2, Obj_Wait. */
    private void updateRise() {
        animateBarrelShot();
        moveSprite2();
        if (--timer < 0) {
            // AIZMiniboss_FallingShot_StartPause: routine 4, timer = 8.
            timer = DROP_PAUSE;
            state = State.PAUSE;
        }
    }

    /** AIZMiniboss_FallingShot_Wait while the pause callback is armed. */
    private void updatePause() {
        if (--timer < 0) {
            enterDrop();
        }
    }

    /** AIZMiniboss_FallingShot_Drop -> FallingShot_StartFall callback. */
    private void enterDrop() {
        state = State.DROP_WAIT;
        timer = fallingShotDelay();
        vFlip = true;
        yVel = 0x400;

        // SetFallingShotDelay falls through SetShotPosition in the ROM.  The
        // existing barrel, not this child, owns $39 and is incremented by four.
        int counter = nextPositionCounter();
        int subtype = barrel == null ? barrelSubtype : barrel.getBarrelSubtype();
        int slotIndex = ((subtype >> 1) + (counter & 0x0C)) & 0x0F;
        boolean hFlip = currentHFlip();
        int[] slotTable = hFlip ? SHOT_SLOTS_LEFT : SHOT_SLOTS_RIGHT;
        int[] offsetTable = hFlip ? SHOT_X_OFFSETS_LEFT : SHOT_X_OFFSETS_RIGHT;
        currentX = services().camera().getX() + offsetTable[slotTable[slotIndex]];
        currentY = services().camera().getY() - 0x20;
        xFixed = currentX << 16;
        yFixed = currentY << 16;
    }

    /** Obj_Wait after SetFallingShotDelay. */
    private void updateDropWait() {
        if (--timer < 0) {
            // FallingShot_StartFall changes priority/callback.  The first
            // MoveSprite2 call is on the following routine-8 dispatch.
            state = State.FALL;
        }
    }

    /** AIZMiniboss_FallingShot_Fall: Animate_Raw, MoveSprite2, ObjHitFloor_DoRoutine. */
    private void updateFall() {
        animateBarrelShot();
        moveSprite2();
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        if (floor.hasCollision()) {
            // ObjHitFloor_DoRoutine adds d1 to the integer y_pos.  Keep the native
            // subpixel remainder while applying that word adjustment.
            yFixed += floor.distance() << 16;
            currentY = yFixed >> 16;
            explode();
        }
    }

    private int fallingShotDelay() {
        int index = barrelSubtype >> 1;
        return index >= 0 && index < FALLING_SHOT_DELAYS.length
                ? FALLING_SHOT_DELAYS[index]
                : 0;
    }

    private int nextPositionCounter() {
        if (barrel != null) {
            int value = (barrel.getPositionCounter() + 4) & 0xFF;
            barrel.setPositionCounter(value);
            return value;
        }
        standalonePositionCounter = (standalonePositionCounter + 4) & 0xFF;
        return standalonePositionCounter;
    }

    private boolean currentHFlip() {
        if (barrel != null) {
            return barrel.isFacingFlipped();
        }
        if (parent != null) {
            return (parent.getState().renderFlags & 1) != 0;
        }
        return standaloneHFlip;
    }

    private void explode() {
        services().playSfx(Sonic3kSfx.MISSILE_EXPLODE.id);
        for (int i = 0; i < AizMinibossNapalmExplosionChild.X_OFFSETS.length; i++) {
            int x = currentX + AizMinibossNapalmExplosionChild.X_OFFSETS[i];
            int y = currentY + AizMinibossNapalmExplosionChild.Y_OFFSETS[i];
            int subtype = i * 2; // CreateChild1_Normal's sequential subtype word index.
            spawnChild(() -> new AizMinibossNapalmExplosionChild(x, y, subtype, true));
        }
        setDestroyed(true);
    }

    private void moveSprite2() {
        yFixed += yVel << 8;
        currentX = xFixed >> 16;
        currentY = yFixed >> 16;
    }

    /** Animate_Raw for AniRaw_AIZMiniboss_BarrelShot's looping C/D prefix. */
    private void animateBarrelShot() {
        if (--animTimer > 0) {
            return;
        }
        animTimer = 2;
        mappingFrame = mappingFrame == FRAME_RISE_A ? FRAME_RISE_B : FRAME_RISE_A;
    }

    int getChildSubtype() {
        return childSubtype;
    }

    int getBarrelSubtype() {
        return barrelSubtype;
    }

    @Override
    public int getCollisionFlags() {
        // FallingShot_Init retains ObjDat_AIZMiniboss_BarrelShot's $98 for the
        // live route.  The cutscene parent clears it in a separate native branch.
        return isDestroyed() ? 0 : COLLISION_FLAGS_HAZARD;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getShieldReactionFlags() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // loc_68C96 runs Add_SpriteToCollisionResponseList after its movement
        // routine, so the following player slot reads this post-move position.
        return true;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(currentX, currentY);
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
    public boolean isHighPriority() {
        // make_art_tile(ArtTile_AIZMiniboss,0,0): priority bit is clear.
        return false;
    }

    @Override
    public int getPriorityBucket() {
        // ObjDat priority $280, then FallingShot_StartFall priority $80.
        return state == State.FALL ? 1 : 5;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager rm = services().renderManager();
        if (rm == null) {
            return;
        }
        PatternSpriteRenderer renderer = rm.getRenderer(Sonic3kObjectArtKeys.AIZ_MINIBOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY,
                currentHFlip(), vFlip, PROJECTILE_PALETTE);
    }
}
