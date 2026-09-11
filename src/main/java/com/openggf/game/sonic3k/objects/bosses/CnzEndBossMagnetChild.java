package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;

import java.util.List;

/** ROM child {@code loc_6E82C}, the released magnet head. */
final class CnzEndBossMagnetChild extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = TouchResponseProfile.fromCanonical(
            com.openggf.game.profiles.touchresponse.TouchResponseProfile.standardEnemy(true));
    private final CnzEndBossInstance boss;
    private int centreX;
    private int centreY;
    private int xSubpixel;
    private int ySubpixel;
    private int xVelocity;
    private int yVelocity;
    private int frame = 4;
    private boolean dropping;
    private boolean dropJustStarted;
    private boolean landed;
    private int animTimer;
    private int animIndex;
    private boolean collisionEnabled = true;
    // ROM byte_6EE1D, consumed by Animate_RawMultiDelay. ObjDat supplies
    // pair zero (frame 4, delay 0), so the first animation tick advances to
    // pair one exactly as the native helper does.
    private static final int[] ANIMATION_FRAMES = {4, 5, 4, 5, 4, 5, 4};
    private static final int[] ANIMATION_DELAYS = {0, 0, 0, 0, 4, 0, 9};

    CnzEndBossMagnetChild(CnzEndBossInstance boss) {
        super(new ObjectSpawn(boss.getCentreX(), boss.getCentreY() + 0x14, 0, 0, 0, false, 0),
                "CNZEndBossMagnet");
        this.boss = boss;
        resetForNextCycle();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        CnzEndBossInstance restoredBoss = CnzEndBossRewindLinks.boss(ctx);
        if (restoredBoss == null) return null;
        CnzEndBossMagnetChild restored = new CnzEndBossMagnetChild(restoredBoss);
        restoredBoss.relinkMagnetChild(restored);
        return restored;
    }

    void beginDrop() {
        centreX = boss.getCentreX();
        centreY = boss.getCentreY() + 0x14;
        xSubpixel = 0;
        var nearest = services().playerQuery().nearestByRomX(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2, centreX);
        PlayableEntity target = nearest.player();
        xVelocity = target != null && (short) (target.getCentreX() - centreX) > 0
                ? 0x100 : -0x100;
        dropping = true;
        dropJustStarted = true;
        landed = false;
        frame = 4;
        resetAnimation();
    }

    void resetForNextCycle() {
        dropping = false;
        dropJustStarted = false;
        landed = false;
        centreX = boss.getCentreX();
        centreY = boss.getCentreY() + 0x14;
        xSubpixel = 0;
        xVelocity = 0;
        frame = 4;
        resetAnimation();
    }

    /** ROM {@code loc_6E69C -> loc_6E920}: consume parent bit 3 at descent bottom. */
    void reattachAtDescentBottom() {
        resetForNextCycle();
    }

    boolean isLanded() { return landed; }

    @Override public int getX() { return centreX - 0x10; }
    @Override public int getY() { return centreY - 0x10; }
    public int getCentreX() { return centreX; }
    public int getCentreY() { return centreY; }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (boss.isDestroyed()) {
            boss.unlinkMagnetChild(this);
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        if (boss.defeatStarted()) {
            collisionEnabled = false;
            if (boss.defeatScatterStarted()) beginDefeatScatter();
            return;
        }
        if (!dropping) {
            centreX = boss.getCentreX();
            centreY = boss.getCentreY() + 0x14;
        } else if (dropJustStarted) {
            // loc_6E87E installs the falling routine and returns; MoveSprite
            // starts when routine 4 is dispatched on the next object pass.
            dropJustStarted = false;
        } else if (!landed) {
            advanceDropMotion();
            var floor = ObjectTerrainUtils.checkFloorDist(centreX, centreY, 0x10);
            resolveFloorContact(floor.distance());
        }
        boolean animationSignalActive = magnetAnimationSignalActive();
        if (landed && animationSignalActive) {
            advanceAnimation();
        } else if (!animationSignalActive) {
            frame = 4;
            resetAnimation();
        }
        updateDynamicSpawn(getCentreX(), getCentreY());
    }

    /** ROM MoveSprite followed by the magnet routine's {@code addi.w #$38,y_vel}. */
    void advanceDropMotion() {
        // Integrate the old velocity first. Both halves retain their 8-bit
        // subpixel remainder.
        xSubpixel += xVelocity;
        centreX += xSubpixel >> 8;
        xSubpixel &= 0xFF;
        ySubpixel += yVelocity;
        centreY += ySubpixel >> 8;
        ySubpixel &= 0xFF;
        yVelocity += 0x38;
    }

    /** ROM descending-only floor response after {@code MoveSprite}'s gravity step. */
    void resolveFloorContact(int floorDistance) {
        // ObjHitFloor_DoRoutine dispatches its callback for d1 == 0 as well as
        // penetration (sonic3k.asm loc_848AC).
        if (yVelocity < 0 || floorDistance > 0) return;
        centreY += floorDistance;
        services().playSfx(Sonic3kSfx.FLOOR_THUMP.id);
        if (yVelocity >= 0x80) {
            yVelocity = -(yVelocity >> 1);
        } else {
            landed = true;
        }
    }

    private boolean magnetAnimationSignalActive() {
        int routine = boss.nativeRoutine().ordinal();
        return routine >= CnzEndBossInstance.Routine.CHARGE.ordinal()
                && routine < CnzEndBossInstance.Routine.DESCEND.ordinal();
    }

    private void advanceAnimation() {
        if (--animTimer >= 0) return;
        animIndex++;
        if (animIndex >= ANIMATION_FRAMES.length) animIndex = 0;
        frame = ANIMATION_FRAMES[animIndex];
        animTimer = ANIMATION_DELAYS[animIndex];
    }

    private void resetAnimation() {
        animIndex = 0;
        animTimer = ANIMATION_DELAYS[0];
    }

    int xVelocityForTest() { return xVelocity; }
    int yVelocityForTest() { return yVelocity; }
    int frameForTest() { return frame; }
    boolean isReleasedForTest() { return dropping; }

    @Override
    public void onUnload() {
        boss.unlinkMagnetChild(this);
    }

    @Override
    protected void onDroppedAsUnmatchedRewindReconstructionChild() {
        boss.unlinkMagnetChild(this);
    }

    @Override public boolean isPersistent() { return true; }

    /** ROM parent-bit-4 signal: replace the magnet slot with two ordered spark children. */
    void beginDefeatScatter() {
        if (isDestroyed()) return;
        collisionEnabled = false;
        int sparkY = centreY;
        spawnChild(() -> new DefeatSpark(centreX - 8, sparkY, 0));
        spawnChild(() -> new DefeatSpark(centreX + 8, sparkY, 1));
        boss.unlinkMagnetChild(this);
        ObjectLifetimeOps.expireDynamic(this);
    }

    @Override public int getCollisionFlags() {
        return collisionEnabled && !boss.defeatStarted() ? 0x8B : 0;
    }
    @Override public int getCollisionProperty() { return 0; }
    @Override public TouchRegion[] getMultiTouchRegions() {
        return new TouchRegion[] { new TouchRegion(centreX, centreY, getCollisionFlags()) };
    }
    @Override public TouchResponseProfile getTouchResponseProfile() { return TOUCH_RESPONSE_PROFILE; }
    @Override public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }
    @Override public int getPriorityBucket() { return 5; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_END_BOSS);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(frame, centreX, centreY, false, false);
        }
    }

    /** ROM {@code loc_6E936}, frame-$A debris spawned by the defeated magnet. */
    static final class DefeatSpark extends AbstractObjectInstance implements RewindRecreatable {
        private static final int FRAME = 0x0A;
        private int subtype;
        private int xFixed;
        private int yFixed;
        private int xVelocity;
        private int yVelocity;
        private int flickerCounter;

        DefeatSpark(int centreX, int centreY, int subtype) {
            super(new ObjectSpawn(centreX, centreY, 0, subtype & 1, 0, false, 0),
                    "CNZEndBossMagnetSpark");
            this.subtype = subtype & 1;
            xFixed = centreX << 8;
            yFixed = centreY << 8;
            xVelocity = this.subtype == 0 ? -0x200 : 0x200;
            yVelocity = -0x200;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return new DefeatSpark(ctx.spawn().x(), ctx.spawn().y(), ctx.spawn().subtype());
        }

        @Override public int getX() { return getCentreX() - 8; }
        @Override public int getY() { return getCentreY() - 8; }
        int getCentreX() { return xFixed >> 8; }
        int getCentreY() { return yFixed >> 8; }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            xFixed = S3kBossFlickerMove.integrate(xFixed, xVelocity);
            yFixed = S3kBossFlickerMove.integrate(yFixed, yVelocity);
            yVelocity += 0x38;
            flickerCounter++;
            var objectServices = tryServices();
            if (objectServices != null && objectServices.camera() != null) {
                int cameraX = Short.toUnsignedInt(objectServices.camera().getX());
                int cameraY = Short.toUnsignedInt(objectServices.camera().getY());
                if (S3kBossFlickerMove.isOutsideNativeBounds(
                        getCentreX(), getCentreY(), cameraX, cameraY)) {
                    ObjectLifetimeOps.expireDynamic(this);
                    return;
                }
            }
            updateDynamicSpawn(getCentreX(), getCentreY());
        }

        @Override public boolean isPersistent() { return true; }
        @Override public int getPriorityBucket() { return 5; }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (!S3kBossFlickerMove.isVisible(flickerCounter)) return;
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_END_BOSS);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(FRAME, getCentreX(), getCentreY(),
                        horizontalFlipForTest(), false);
            }
        }

        int frameForTest() { return FRAME; }
        int xVelocityForTest() { return xVelocity; }
        int yVelocityForTest() { return yVelocity; }
        boolean horizontalFlipForTest() { return subtype != 0; }
        boolean visibleForTest() { return S3kBossFlickerMove.isVisible(flickerCounter); }
    }
}
