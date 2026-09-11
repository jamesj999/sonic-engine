package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateSubtypeDefaultArgsRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * One {@code BossExplosionHitbox} child from {@code ChildObjDat_690D8}.
 *
 * <p>The ROM creates seven independent object slots.  Each starts with
 * {@code ObjDat_BossExplosionHitbox} ($97 collision, {@code Map_BossExplosion})
 * and uses its subtype to stagger {@code AniRaw_BossExplosion} before the
 * animation starts.</p>
 */
public final class AizMinibossNapalmExplosionChild extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnCoordinateSubtypeDefaultArgsRewindRecreatable {
    static final int[] X_OFFSETS = {0, 8, -8, 4, -4, 4, -4}; // ChildObjDat_690D8
    static final int[] Y_OFFSETS = {-0x24, -0x1C, -0x1C, -0x14, -0x14, -4, -4};

    private static final int COLLISION_FLAGS = 0x97; // ObjDat_BossExplosionHitbox
    /* AniRaw_BossExplosion is (mapping_frame, timer) pairs.  The first pair
     * at bytes 0/1 is skipped by Animate_RawMultiDelay's initial +2 step. */
    private static final int[] ANIM_DELAYS = {1, 1, 2, 3, 4, 4};
    private static final int[] ANIM_FRAMES = {0, 1, 2, 3, 4, 5};
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE =
            TouchResponseProfile.standardEnemy();

    private enum State {
        INIT,
        WAIT,
        READY,
        ANIMATE
    }

    private int currentX;
    private int currentY;
    private int subtype;
    private boolean hazardous;
    private State state;
    private int delayTimer;
    private int animationIndex;
    private int animationTimer;

    public AizMinibossNapalmExplosionChild(int x, int y, int subtype) {
        this(x, y, subtype, true);
    }

    public AizMinibossNapalmExplosionChild(int x, int y, int subtype, boolean hazardous) {
        super(new ObjectSpawn(x, y, Sonic3kObjectIds.AIZ_MINIBOSS, subtype & 0xFF, 0, false, 0),
                "AIZMinibossNapalmExplosion");
        this.currentX = x;
        this.currentY = y;
        this.subtype = subtype & 0xFF;
        this.hazardous = hazardous;
        this.state = State.INIT;
        // BossChild_SetSubtypeDelay: d1=$C; (d1 - subtype) << 1.
        this.delayTimer = Math.max(0, (0x0C - this.subtype) * 2);
        this.animationIndex = -1;
        this.animationTimer = 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        switch (state) {
            case INIT -> {
                // BossExplosionHitbox_Init is a real routine-0 dispatch.  It
                // installs ObjDat_BossExplosionHitbox, the animation script,
                // and the subtype delay before the first Obj_Wait dispatch.
                state = State.WAIT;
            }
            case WAIT -> {
                if (--delayTimer < 0) {
                    // BossExplosionHitbox_StartAnim changes routine to 4 but
                    // the current dispatch still has no Draw_And_Touch call.
                    // Keep a distinct READY state so harmful touch and the
                    // first Animate_RawMultiDelay step begin on the following
                    // routine-4 dispatch.
                    state = State.READY;
                }
            }
            case READY -> {
                // BossExplosionHitbox_Animate is routine 4's first dispatch;
                // Animate_RawMultiDelay must publish frame 0 before touch and
                // rendering become observable.
                advanceAnimation();
                state = State.ANIMATE;
            }
            case ANIMATE -> advanceAnimation();
        }
    }

    private void advanceAnimation() {
        // Animate_RawMultiDelay uses subq then bpl: a zero result still holds
        // the current mapping, so a delay N occupies N+1 routine-4 dispatches.
        if (--animationTimer >= 0) {
            return;
        }
        animationIndex++;
        if (animationIndex >= ANIM_FRAMES.length) {
            setDestroyed(true);
            return;
        }
        animationTimer = ANIM_DELAYS[animationIndex];
    }

    @Override
    public int getCollisionFlags() {
        return hazardous && state == State.ANIMATE && !isDestroyed()
                ? COLLISION_FLAGS
                : 0;
    }

    @Override
    public int getCollisionProperty() {
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
        // make_art_tile(ArtTile_BossExplosion2,0,0): priority bit is clear.
        return false;
    }

    @Override
    public int getPriorityBucket() {
        // ObjDat_BossExplosionHitbox priority is $80.
        return 1;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || state != State.ANIMATE) {
            return;
        }
        ObjectRenderManager rm = services().renderManager();
        if (rm == null) {
            return;
        }
        PatternSpriteRenderer renderer = rm.getRenderer(ObjectArtKeys.BOSS_EXPLOSION);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(ANIM_FRAMES[animationIndex], currentX, currentY, false, false);
    }
}
