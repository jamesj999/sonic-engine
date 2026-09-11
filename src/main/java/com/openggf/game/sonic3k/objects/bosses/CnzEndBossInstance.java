package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.events.S3kCnzEventWriteSupport;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.objects.CnzCannonInstance;
import com.openggf.game.sonic3k.objects.CnzEggCapsuleInstance;
import com.openggf.game.sonic3k.objects.SongFadeTransitionInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.Palette;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.physics.SwingMotion;
import com.openggf.sprites.NativePositionOps;

import java.util.List;
import java.io.IOException;

/**
 * ROM port of CNZ Act 2's {@code Obj_CNZEndBoss} magnetic boss.
 *
 * <p>ROM anchor: {@code Obj_CNZEndBoss}.
 *
 * <p>The eight native routines cover the entry swing, player tracking, magnet
 * drop/bounce, alignment, charge, attraction, descent and return. The magnetic
 * arms retain their four phase offsets from {@code ChildObjDat_6EDD4}; the
 * attraction curve is the exact {@code dword_6EC7E} table.
 */
public final class CnzEndBossInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, SpawnRewindRecreatable {
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = TouchResponseProfile.fromCanonical(
            com.openggf.game.profiles.touchresponse.TouchResponseProfile.standardEnemy(true));
    private static final int START_CAMERA_X_MIN = 0x4660;
    private static final int START_CAMERA_X_MAX = 0x4860;
    private static final int STORED_BOUND_BASE = 0x4760;
    private static final int CAPSULE_BOUND_MAX_X = 0x48F0;
    private static final int CANNON_BOUND_MAX_X = 0x4A70;
    private static final int CAPSULE_X = 0x4990;
    private static final int CAPSULE_Y = 0x02E0;
    private static final int CANNON_TRIGGER_X = 0x4A30;
    private static final int CANNON_X = 0x4B20;
    private static final int CANNON_Y = 0x02A8;
    private static final int CANNON_LAUNCH_WAIT = 0xBF;
    private static final int ICZ_START_ZONE_WORD = 0x500;
    private static final int HIT_COUNT = 8;
    private static final int COLLISION_FLAGS = 0x06;
    private static final int HIT_INVULNERABILITY_FRAMES = 0x20;
    private static final int SWING_ACCEL = 0x10;
    private static final int SWING_MAX = 0xC0;
    private static final int TRACK_SPEED = 0x100;
    private static final int ENTRY_WAIT = 0x7F;
    private static final int TRACK_WAIT = 3 * 60;
    private static final int CHARGE_WAIT = 0xBF;
    private static final int MAGNET_ACTIVE_WAIT = 0xFF;
    private static final int[] MAGNET_PULL_16_16 = {
            0x28000, 0x20000, 0x1C000, 0x18000, 0x14000, 0x10000, 0x0C000, 0x08000
    };

    enum Routine {
        WAIT_CAMERA, CAMERA_LOCK, INIT, ENTRY, TRACK, MAGNET_DROP, ALIGN, CHARGE, WIND_DOWN,
        DESCEND, ASCEND, DEFEATED
    }

    private int centreX;
    private int centreY;
    private int xSubpixel;
    private int ySubpixel;
    private int xVelocity;
    private int yVelocity;
    private int swingVelocity;
    private boolean swingDown;
    private boolean facingRight;
    private int routineTimer;
    private Routine routine = Routine.WAIT_CAMERA;
    private int savedHoverY;
    private boolean magneticFieldActive;
    private int lastGravityMachineSfxFrame = Integer.MIN_VALUE;
    private int mappingFrame;
    private boolean bodyVisibleThisFrame = true;
    private boolean startupComplete;
    private final S3kSharedBossCameraGate cameraGate = new S3kSharedBossCameraGate();
    private int savedCameraMinX;
    private int savedCameraMaxX;
    private int savedCameraMinY;
    private int savedCameraMaxY;
    private CnzEndBossMagnetChild magnetChild;

    private boolean defeatHandoffComplete;
    private boolean defeatScatterStarted;
    private boolean defeatBodyHidden;
    private int hitCount = HIT_COUNT;
    private int hitInvulnerabilityTimer;
    private boolean capsuleResultsComplete;
    private boolean postCapsuleReleaseComplete;
    private boolean cannonSpawned;
    private boolean cannonArmed;
    private boolean cannonLaunched;
    private boolean cannonLaunchInputForced;
    private boolean transitionRequested;
    private int cannonLaunchTimer = -1;
    private int postCapsuleReleaseCountForTest;
    private CnzCannonInstance endCannon;
    private int defeatWaitTimer;
    private boolean defeatWaitJustStarted;
    private int storedBoundBase;

    public CnzEndBossInstance(ObjectSpawn spawn) {
        super(spawn, "CNZEndBoss");
        this.centreX = spawn.x();
        this.centreY = spawn.y();
    }

    @Override
    public int getX() {
        return centreX - 0x40;
    }

    @Override
    public int getY() {
        return centreY - 0x14;
    }

    public int getCentreX() {
        return centreX;
    }

    public int getCentreY() {
        return centreY;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        bodyVisibleThisFrame = hitInvulnerabilityTimer <= 0 || (vIntRunCount & 1) == 0;
        if (hitInvulnerabilityTimer > 0) {
            applyHitFlash(hitInvulnerabilityTimer);
            hitInvulnerabilityTimer--;
            if (hitInvulnerabilityTimer == 0) installBossPalette();
        }
        if (!defeatHandoffComplete) {
            updateNativeBoss(player);
        }
        updateDefeatWait();
        updatePostDefeatSequence(vIntRunCount, player);
    }

    private void updateNativeBoss(PlayableEntity player) {
        switch (routine) {
            case WAIT_CAMERA -> updateCameraGate();
            case CAMERA_LOCK -> updateCameraLock();
            case INIT -> initializeNativeRoutineZero();
            case ENTRY -> {
                swingAndMove();
                if (waitExpired()) beginTracking();
            }
            case TRACK -> {
                trackClosestPlayer(player);
                swingAndMove();
                if (waitExpired()) beginMagnetDrop();
            }
            case MAGNET_DROP -> updateMagnetDrop();
            case ALIGN -> updateAlign();
            case CHARGE -> updateCharge(player);
            case WIND_DOWN -> updateWindDown();
            case DESCEND -> updateDescent();
            case ASCEND -> updateAscent();
            case DEFEATED -> { }
        }
    }

    private void updateCameraGate() {
        if (services().camera() == null) return;
        int cameraX = services().camera().getX() & 0xFFFF;
        int cameraY = services().camera().getY() & 0xFFFF;
        if (cameraX < START_CAMERA_X_MIN || cameraX > START_CAMERA_X_MAX
                || cameraY < 0 || cameraY > 0x300) return;

        savedCameraMinX = services().camera().getMinX() & 0xFFFF;
        savedCameraMaxX = services().camera().getMaxX() & 0xFFFF;
        savedCameraMinY = services().camera().getMinY() & 0xFFFF;
        savedCameraMaxY = services().camera().getMaxY() & 0xFFFF;
        setStoredCameraBounds(savedCameraMinX, savedCameraMaxX, savedCameraMinY, savedCameraMaxY);
        cameraGate.begin(
                services().camera(),
                new S3kSharedBossCameraGate.LockBounds(0x0240, 0x0240, 0x4760, 0x47E0),
                2 * 60);
        routine = Routine.CAMERA_LOCK;
        S3kCnzEventWriteSupport.setBossFlag(services(), true);
        services().fadeOutMusic();
        cnzArtProvider().queueCnzEndBossArt();
        installBossPalette();
    }

    private void updateCameraLock() {
        boolean cameraReady = cameraGate.update(
                services().camera(),
                () -> {
                    services().gameState().setCurrentBossId(Sonic3kObjectIds.CNZ_END_BOSS);
                    services().playMusic(Sonic3kMusic.BOSS.id);
                });
        if (!cameraReady) {
            return;
        }
        routine = Routine.INIT;
    }

    /** ROM {@code loc_6E4F2}: routine zero owns all six native child allocations. */
    private void initializeNativeRoutineZero() {
        startupComplete = true;
        routine = Routine.ENTRY;
        routineTimer = ENTRY_WAIT;
        savedHoverY = centreY;
        xVelocity = -TRACK_SPEED;
        swingVelocity = SWING_MAX;
        swingDown = false;
        spawnNativeChildren();
    }

    private Sonic3kObjectArtProvider cnzArtProvider() {
        if (services().renderManager().getArtProvider() instanceof Sonic3kObjectArtProvider provider) {
            return provider;
        }
        throw new IllegalStateException("CNZ end boss requires the S3K object-art provider");
    }

    private void setStoredCameraBounds(int minX, int maxX, int minY, int maxY) {
        Object provider = services().levelEventProvider();
        if (provider instanceof Sonic3kLevelEventManager manager && manager.getCnzEvents() != null) {
            manager.getCnzEvents().setCameraStoredMinXPos((short) minX);
            manager.getCnzEvents().setCameraStoredMaxXPos((short) maxX);
            manager.getCnzEvents().setCameraStoredMinYPos((short) minY);
            manager.getCnzEvents().setCameraStoredMaxYPos((short) maxY);
        }
    }

    private CnzEndBossRobotnikShipChild spawnNativeChildren() {
        CnzEndBossRobotnikShipChild ship =
                spawnChild(() -> new CnzEndBossRobotnikShipChild(this));
        magnetChild = spawnChild(() -> new CnzEndBossMagnetChild(this));
        // CreateChild3_NormalRepeated advances d2 by two for each child, so
        // loc_6E994 receives subtypes 0,2,4,6 and derives quarter-turn phases.
        for (int childIndex = 0; childIndex < 4; childIndex++) {
            int phase = childIndex << 6;
            spawnChild(() -> new CnzEndBossArmChild(this, phase));
        }
        return ship;
    }

    @Override
    protected void recreateConstructionChildrenForRewind() {
        // loc_6E4F2 creates this fixed six-slot graph on the first native
        // routine dispatch. Rebuild candidates so restore can adopt the exact
        // captured ship/magnet/arm identities and their scalar state.
        CnzEndBossRobotnikShipChild ship = spawnNativeChildren();

        // Routine-created CNZ graph members do not have standalone probe
        // constructors: they require this exact restored boss/ship identity.
        // Offer every fixed phase role as a reconstruction candidate; the
        // ObjectManager adopts only captured entries and drops unmatched roles.
        spawnChild(() -> new CnzEndBossExplosionControllerChild(ship, 4));
        spawnChild(() -> new CnzEndBossRobotnikFlameChild(ship));
        spawnChild(() -> new CnzEndBossFieldChild(this, -0x0C));
        spawnChild(() -> new CnzEndBossFieldChild(this, 0x0C));
        spawnChild(() -> CnzEndBossBoundaryController.increaseMaxX(
                centreX, centreY, CAPSULE_BOUND_MAX_X));
        spawnChild(() -> CnzEndBossBoundaryController.decreaseMinY(
                centreX, centreY, 0x0200));
        spawnChild(() -> CnzEndBossBoundaryController.increaseMaxX(
                centreX, centreY, CANNON_BOUND_MAX_X));
    }

    private void installBossPalette() {
        if (services().levelManager() == null || services().levelManager().getCurrentLevel() == null) return;
        byte[] bytes;
        try {
            bytes = services().rom().readBytes(Sonic3kConstants.PAL_CNZ_END_BOSS_ADDR, 32);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read Pal_CNZEndBoss", e);
        }
        if (bytes == null || bytes.length < 32) return;
        Palette palette = new Palette();
        palette.fromSegaFormat(bytes);
        S3kPaletteWriteSupport.applyPaletteLine(
                services().paletteOwnershipRegistryOrNull(),
                services().levelManager().getCurrentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.CNZ_END_BOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                palette,
                false);
    }

    private void applyHitFlash(int timer) {
        if (services().levelManager() == null
                || services().levelManager().getCurrentLevel() == null) {
            return;
        }
        int[] colors = {9, 10, 11, 14};
        int[] dark = {0x0060, 0x0020, 0x0020, 0x0640};
        int[] bright = {0x0888, 0x0EEE, 0x0EEE, 0x0AAA};
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                services().levelManager().getCurrentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.CNZ_END_BOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                colors,
                (timer & 1) == 0 ? bright : dark);
    }

    private void beginTracking() {
        routine = Routine.TRACK;
        routineTimer = TRACK_WAIT;
    }

    private void trackClosestPlayer(PlayableEntity fallback) {
        PlayableEntity target = fallback;
        int best = fallback == null ? Integer.MAX_VALUE : Math.abs(centreX - fallback.getCentreX());
        for (PlayableEntity candidate : services().playerQuery().playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            int distance = Math.abs(centreX - candidate.getCentreX());
            if (distance < best) {
                best = distance;
                target = candidate;
            }
        }
        if (target == null || best < 0x10) return;
        facingRight = target.getCentreX() > centreX;
        xVelocity = facingRight ? TRACK_SPEED : -TRACK_SPEED;
    }

    private void beginMagnetDrop() {
        routine = Routine.MAGNET_DROP;
        xVelocity = 0;
        magnetChild.beginDrop();
    }

    private void updateMagnetDrop() {
        swingAndMove();
        if (magnetChild.isLanded()) routine = Routine.ALIGN;
    }

    private void updateAlign() {
        swingAndMove();
        int magnetCentreX = magnetChild.getCentreX();
        if (centreX != magnetCentreX) {
            int direction = Integer.compare(magnetCentreX, centreX);
            facingRight = direction > 0;
            centreX += direction;
            return;
        }
        routine = Routine.CHARGE;
        routineTimer = CHARGE_WAIT;
    }

    private void updateCharge(PlayableEntity player) {
        if (!magneticFieldActive) {
            if (!waitExpired()) return;
            magneticFieldActive = true;
            routineTimer = MAGNET_ACTIVE_WAIT;
            spawnChild(() -> new CnzEndBossFieldChild(this, -0x0C));
            spawnChild(() -> new CnzEndBossFieldChild(this, 0x0C));
            // The freshly allocated field children execute later in the same
            // native object pass, so their first pull is visible immediately.
            applyMagnetPull();
            return;
        }
        if (waitExpired()) {
            // loc_6E650 clears parent bit 2 before the field children run, so
            // the expiry frame does not apply one final attraction step.
            magneticFieldActive = false;
            routine = Routine.WIND_DOWN;
            routineTimer = MAGNET_ACTIVE_WAIT;
            return;
        }
        applyMagnetPull();
    }

    /** ROM {@code loc_6E650/loc_6E66C}: parent bit 7 remains set for {@code $FF}. */
    private void updateWindDown() {
        if (!waitExpired()) return;
        routine = Routine.DESCEND;
    }

    /** ROM {@code Play_SFX_Continuous}: request the sound once every 16 V-int frames. */
    void playGravityMachineSfx(int frameCounter) {
        if ((frameCounter & 0x0F) != 0 || lastGravityMachineSfxFrame == frameCounter) return;
        lastGravityMachineSfxFrame = frameCounter;
        services().playSfx(Sonic3kSfx.GRAVITY_MACHINE.id);
    }

    private void applyMagnetPull() {
        for (PlayableEntity candidate : services().playerQuery().playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            int delta = centreX - candidate.getCentreX();
            int distance = Math.min(Math.abs(delta) & 0xFFC0, 0x1C0);
            int index = Math.min(distance >> 6, MAGNET_PULL_16_16.length - 1);
            int pull16 = MAGNET_PULL_16_16[index];
            int signed = delta >= 0 ? pull16 : -pull16;
            if (candidate instanceof AbstractPlayableSprite sprite) {
                NativePositionOps.addXPos16_16(sprite, signed);
            }
        }
    }

    private void updateDescent() {
        int nextY = centreY + 1;
        int target = magnetChild.getCentreY() - 0x14;
        if (nextY < target) {
            centreY = nextY;
            return;
        }
        // loc_6E69C changes routine without storing d0 (the incremented Y).
        // The parent therefore begins ascent one pixel above the magnet target.
        magnetChild.reattachAtDescentBottom();
        routine = Routine.ASCEND;
    }

    private void updateAscent() {
        int nextY = centreY - 1;
        if (nextY > savedHoverY) {
            centreY = nextY;
            return;
        }
        centreY = savedHoverY;
        swingVelocity = SWING_MAX;
        swingDown = false;
        beginTracking();
    }

    private void swingAndMove() {
        SwingMotion.Result swing = SwingMotion.update(SWING_ACCEL, swingVelocity, SWING_MAX, swingDown);
        swingVelocity = swing.velocity();
        swingDown = swing.directionDown();
        yVelocity = swingVelocity;
        xSubpixel += xVelocity;
        ySubpixel += yVelocity;
        centreX += xSubpixel >> 8;
        centreY += ySubpixel >> 8;
        xSubpixel &= 0xFF;
        ySubpixel &= 0xFF;
    }

    private boolean waitExpired() {
        return --routineTimer < 0;
    }

    @Override
    public int getCollisionFlags() {
        // Before loc_6E4F2 installs ObjDat_CNZEndBoss, the camera-gate wrapper
        // has not entered Draw_And_Touch_Sprite and owns no collision response.
        if (!startupComplete || defeatHandoffComplete || hitInvulnerabilityTimer > 0 || hitCount <= 0) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return hitCount;
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        // ROM TouchResponse consumes x_pos/y_pos. getX()/getY() are the
        // renderer's top-left bounds for this 0x80-by-0x28 body, so publish the
        // native centre explicitly instead of shifting the hit box up-left.
        return new TouchRegion[] { new TouchRegion(centreX, centreY, getCollisionFlags()) };
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
    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        if (defeatHandoffComplete || hitInvulnerabilityTimer > 0 || hitCount <= 0) {
            return;
        }
        hitCount--;
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
        if (hitCount <= 0) {
            beginDefeatSequence();
        } else {
            hitInvulnerabilityTimer = HIT_INVULNERABILITY_FRAMES;
        }
    }

    /**
     * Returns whether the wider CNZ script has already declared this boss slot
     * active through shared state.
     *
     * <p>Task 8 intentionally does not let the promoted production slot claim
     * boss mode on its own. The real startup gate belongs to the later attack
     * choreography and CNZ event flow; this predicate also supports tests that
     * enter at the native externally-owned boss seam.
     */
    private boolean isBossModeAlreadyOwnedExternally() {
        if (services().gameState().getCurrentBossId() == Sonic3kObjectIds.CNZ_END_BOSS) {
            return true;
        }
        Object provider = services().levelEventProvider();
        if (provider instanceof Sonic3kLevelEventManager manager) {
            Sonic3kCNZEvents events = manager.getCnzEvents();
            return events != null && events.isBossFlag();
        }
        return false;
    }

    /**
     * Verified Task 8 defeat handoff.
     *
     * <p>This is the honest boundary from the ROM findings:
     * <ol>
     *   <li>clear {@code Boss_flag}</li>
     *   <li>widen the camera max so the player can move past the boss arena</li>
     *   <li>spawn the CNZ-local egg capsule wrapper</li>
     *   <li>stay alive as the post-results cannon-launch controller</li>
     * </ol>
     */
    private void beginDefeatSequence() {
        if (!isBossModeAlreadyOwnedExternally()) {
            return;
        }
        routine = Routine.DEFEATED;
        magneticFieldActive = false;
        hitInvulnerabilityTimer = 0;
        defeatWaitTimer = 0x3F;
        defeatWaitJustStarted = true;
        services().gameState().addScore(1000);
        if (services().levelGamestate() != null) {
            services().levelGamestate().pauseTimer();
        }
    }

    private void updateDefeatWait() {
        if (routine != Routine.DEFEATED || defeatHandoffComplete) return;
        if (defeatWaitJustStarted) {
            defeatWaitJustStarted = false;
            return;
        }
        if (defeatWaitTimer-- > 0) return;
        if (!defeatScatterStarted) {
            beginDefeatScatter();
        } else {
            applyDefeatHandoff();
        }
    }

    private void beginDefeatScatter() {
        defeatScatterStarted = true;
        defeatBodyHidden = true;
        defeatWaitTimer = (2 * 60) - 1;
        spawnFreeChild(() -> new SongFadeTransitionInstance(2 * 60, Sonic3kMusic.CNZ2.id));
        spawnChild(() -> new CnzEndBossDefeatDebrisChild(this, -0x14, -0x100));
        spawnChild(() -> new CnzEndBossDefeatDebrisChild(this, 0x14, 0x100));
    }

    private void applyDefeatHandoff() {
        defeatHandoffComplete = true;

        S3kCnzEventWriteSupport.setBossFlag(services(), false);
        services().gameState().setCurrentBossId(0);

        storedBoundBase = STORED_BOUND_BASE;
        setStoredMaxX(CAPSULE_BOUND_MAX_X);
        spawnChild(() -> CnzEndBossBoundaryController.increaseMaxX(
                centreX, centreY, CAPSULE_BOUND_MAX_X));

        spawnChild(() -> new CnzEggCapsuleInstance(
                new ObjectSpawn(CAPSULE_X, CAPSULE_Y, Sonic3kObjectIds.EGG_CAPSULE, 0, 0, false, 0),
                CnzEggCapsuleInstance.CompletionContinuation.CNZ_END_BOSS_SEQUENCE));
    }

    private void updatePostDefeatSequence(int vIntRunCount, PlayableEntity player) {
        if (!defeatHandoffComplete || transitionRequested) {
            return;
        }
        if (!capsuleResultsComplete && services().gameState().isEndOfLevelFlag()) {
            // loc_6E724 reads _unkFAA8 itself after the lower-slot
            // Obj_LevelResults clears it; Obj_EggCapsule is not an
            // intermediate notification owner (sonic3k.asm:146087-146103).
            capsuleResultsComplete = true;
        }
        if (capsuleResultsComplete && !cannonSpawned) {
            releasePostCapsuleStateOnce();
            if (player instanceof AbstractPlayableSprite sprite
                    && (sprite.getCentreX() & 0xFFFF) >= CANNON_TRIGGER_X) {
                spawnEndCannon();
            }
            return;
        }
        if (!cannonSpawned || !(player instanceof AbstractPlayableSprite sprite)) {
            return;
        }
        // loc_6E7B6 resumes pinning the left boundary once loc_6E778 has
        // allocated the end cannon. The intervening walk-to-cannon routine
        // does not write Camera_min_X_pos.
        services().camera().setMinX(services().camera().getX());
        if (!cannonArmed && endCannon != null && endCannon.hasCapturedPlayerForEndSequence()) {
            cannonArmed = true;
            cannonLaunchTimer = CANNON_LAUNCH_WAIT;
            services().camera().setMaxYTarget((short) 0x0200);
            S3kCnzEventWriteSupport.requestSidekickBoundsPublishAfterCameraEasing(
                    services());
            sprite.setControlLocked(true);
            return;
        }
        if (cannonArmed && !cannonLaunched) {
            if (cannonLaunchInputForced) {
                if (endCannon != null && endCannon.hasCapturedPlayerForEndSequence()) {
                    return;
                }
                sprite.setForcedInputMask(0);
                cannonLaunchInputForced = false;
                cannonLaunched = true;
            } else {
                cannonLaunchTimer--;
                if (cannonLaunchTimer >= 0) {
                    return;
                }
                if (endCannon == null || endCannon.getSpinAngle() != 0x12) {
                    return;
                }
                sprite.setForcedInputMask(AbstractPlayableSprite.INPUT_JUMP);
                cannonLaunchInputForced = true;
                return;
            }
        }
        if (cannonLaunched && isPlayerPastIczLaunchThreshold(sprite)) {
            requestIczTransition();
        }
    }

    public void onCapsuleResultsComplete() {
        capsuleResultsComplete = true;
    }

    public int getPostCapsuleReleaseCountForTest() {
        return postCapsuleReleaseCountForTest;
    }

    private void spawnEndCannon() {
        cnzArtProvider().queueBadnikExplosionArt();
        cannonSpawned = true;
        // loc_6E778 calls AllocateObject, so the cannon takes the lowest free
        // SST even when that slot is behind this retained boss controller.
        endCannon = spawnFreeChild(() -> new CnzCannonInstance(
                new ObjectSpawn(CANNON_X, CANNON_Y, Sonic3kObjectIds.CNZ_CANNON,
                        CnzCannonInstance.END_SEQUENCE_SUBTYPE, 0, false, 0)));
    }

    private boolean isPlayerPastIczLaunchThreshold(AbstractPlayableSprite sprite) {
        int cameraYPlusWindow = (services().camera().getY() & 0xFFFF) + 0x20;
        int playerY = sprite.getCentreY() & 0xFFFF;
        return cameraYPlusWindow >= playerY;
    }

    private void requestIczTransition() {
        transitionRequested = true;
        int act = ICZ_START_ZONE_WORD & 0xFF;
        services().requestZoneAndAct(Sonic3kZoneIds.ZONE_ICZ, act, true);
        setDestroyed(true);
    }

    /**
     * Restores main-player and sidekick control after the defeat handoff.
     *
     * <p>The teleporter beam can leave the player object-controlled, rolled, and
     * hidden. CNZ's boss release must clear all three so the capsule handoff
     * leaves the player in a normal controllable state.
     */
    private void restorePlayerControl() {
        PlayableEntity focused = services().camera().getFocusedSprite();
        List<PlayableEntity> players = services().playerQuery()
                .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS);
        for (PlayableEntity candidate : players) {
            if (candidate instanceof AbstractPlayableSprite sprite) {
                releaseSprite(sprite);
            }
        }
        if (focused instanceof AbstractPlayableSprite focusedPlayer && !players.contains(focusedPlayer)) {
            releaseSprite(focusedPlayer);
        }
    }

    private void releasePostCapsuleStateOnce() {
        if (postCapsuleReleaseComplete) {
            return;
        }
        restorePlayerControl();
        restoreLevelMusic();
        setStoredMinY(0x0200);
        spawnChild(() -> CnzEndBossBoundaryController.decreaseMinY(centreX, centreY, 0x0200));
        setStoredMaxX(CANNON_BOUND_MAX_X);
        spawnChild(() -> CnzEndBossBoundaryController.increaseMaxX(
                centreX, centreY, CANNON_BOUND_MAX_X));
        postCapsuleReleaseComplete = true;
        postCapsuleReleaseCountForTest++;
    }

    private void releaseSprite(AbstractPlayableSprite sprite) {
        if (sprite.getCpuController() != null) {
            sprite.getCpuController().setController2SignedLocked(false);
        }
        sprite.setControlLocked(false);
        ObjectControlState.none().applyTo(sprite);
        sprite.setHidden(false);
        sprite.setRolling(false);
        // Restore_PlayerControl clears Status_InAir and publishes Wait to both
        // anim and prev_anim, with anim_frame/time_frame reset. The boss slot
        // runs after the playable slots, so the old victory mapping remains
        // visible for this frame while the raw animation byte changes
        // (sonic3k.asm:180361-180371,146037-146061).
        sprite.setAir(false);
        sprite.setAnimationId(Sonic3kAnimationIds.WAIT);
        sprite.setAnimationFrameIndex(0);
        sprite.setAnimationTick(0);
    }

    /**
     * Restores CNZ Act 2 music instead of claiming a full boss music / fade
     * state machine.
     */
    private void restoreLevelMusic() {
        services().playMusic(Sonic3kMusic.CNZ2.id);
    }

    private void setStoredMaxX(int value) {
        Object provider = services().levelEventProvider();
        if (provider instanceof Sonic3kLevelEventManager manager && manager.getCnzEvents() != null) {
            manager.getCnzEvents().setCameraStoredMaxXPos((short) value);
        }
    }

    private void setStoredMinY(int value) {
        Object provider = services().levelEventProvider();
        if (provider instanceof Sonic3kLevelEventManager manager && manager.getCnzEvents() != null) {
            manager.getCnzEvents().setCameraStoredMinYPos((short) value);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!bodyVisibleThisFrame || !nativeBodyRenderable()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_END_BOSS);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, centreX, centreY, facingRight, false);
    }

    public String getRoutineForTest() {
        return routine.name();
    }

    @Override
    public String traceDebugDetails() {
        return "routine=" + routine + " timer=" + routineTimer
                + " cannon=" + cannonSpawned + "/" + cannonArmed + "/"
                + cannonLaunched + " launchTimer=" + cannonLaunchTimer
                + " field=" + magneticFieldActive
                + " centre=" + String.format("%04X,%04X", centreX & 0xFFFF, centreY & 0xFFFF)
                + " magnet=" + (magnetChild == null ? "none"
                : String.format("%04X,%04X", magnetChild.getCentreX() & 0xFFFF,
                        magnetChild.getCentreY() & 0xFFFF));
    }

    public boolean isStartupCompleteForTest() {
        return startupComplete;
    }

    public boolean isMagneticFieldActiveForTest() {
        return magneticFieldActive;
    }

    public int getMappingFrameForTest() {
        return mappingFrame;
    }

    Routine nativeRoutine() {
        return routine;
    }

    boolean magneticFieldActive() {
        return magneticFieldActive;
    }

    boolean defeatStarted() {
        return routine == Routine.DEFEATED;
    }

    boolean defeatScatterStarted() {
        return defeatScatterStarted;
    }

    boolean facingRight() {
        return facingRight;
    }

    boolean hurtStatusActive() {
        return hitInvulnerabilityTimer > 0;
    }

    boolean shipEscapeSignalled() {
        return defeatHandoffComplete;
    }

    void clearBossFlagFromEscapingShip() {
        S3kCnzEventWriteSupport.setBossFlag(services(), false);
        if (services().gameState() != null) {
            services().gameState().setCurrentBossId(0);
        }
    }

    public boolean isBodyVisibleForTest() {
        return bodyVisibleThisFrame;
    }

    public boolean isNativeBodyRenderableForTest() {
        return nativeBodyRenderable();
    }

    private boolean nativeBodyRenderable() {
        return routine.ordinal() > Routine.INIT.ordinal() && !defeatBodyHidden;
    }

    void relinkMagnetChild(CnzEndBossMagnetChild child) {
        magnetChild = child;
    }

    void unlinkMagnetChild(CnzEndBossMagnetChild child) {
        if (magnetChild == child) {
            magnetChild = null;
        }
    }
}
