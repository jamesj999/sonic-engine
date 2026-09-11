package com.openggf.game.sonic3k.objects;

import com.openggf.audio.GameSound;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.LevelManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x4A - CNZ bumper.
 *
 * <p>ROM reference: {@code Obj_Bumper}, non-Pachinko/non-competition branch in
 * {@code docs/skdisasm/sonic3k.asm}. Subtype zero is stationary. Nonzero
 * subtypes initialize {@code angle(a0)} and orbit the original placement on a
 * 64px radius using the low byte at {@code Level_frame_counter+1}; X flip reverses the cycle.
 */
public class CnzBumperObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, SpawnRewindRecreatable {

    private static final int COLLISION_FLAGS = 0x40 | 0x17;
    private static final int BOUNCE_VELOCITY = 0x700;
    private static final int ORBIT_RADIUS_SHIFT = 2;
    private static final int ANIM_HIT_DURATION = 8;
    private static final int MAX_SCORE_HITS = 10;
    private static final int SCORE_PER_HIT = 10;
    private static final int LIST_ADD_WINDOW = 0x280;
    private static final int CAMERA_COARSE_BACK_OFFSET = 0x80;

    private int originX;
    private int originY;
    private int initialAngle;
    private boolean reverseOrbit;

    private int currentX;
    private int currentY;
    private int touchX;
    private int touchY;
    private int animFrame;
    private int animTimer;
    private int scoreHits;
    private AbstractPlayableSprite pendingPrimaryTouch;
    private AbstractPlayableSprite pendingSidekickTouch;

    public CnzBumperObjectInstance(ObjectSpawn spawn) {
        super(spawn, "CNZBumper");
        this.originX = spawn.x();
        this.originY = spawn.y();
        this.currentX = originX;
        this.currentY = originY;
        this.touchX = originX;
        this.touchY = originY;
        this.initialAngle = spawn.subtype() & 0xFF;
        this.reverseOrbit = (spawn.renderFlags() & 0x1) != 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Self-contained: all state (including the origin/angle/orbit fields)
     * is derived deterministically from the captured spawn. Mutable scalar fields are
     * reapplied by the standard scalar-restore pass after recreate; pending player-touch
     * back-references are not wired here (they were not captured by the deleted explicit
     * restore path either). Replaces the former explicit dynamic restore path (Phase-2 codec-deletion
     * batch 2).
     */

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (initialAngle == 0) {
            updateOrbit(vIntRunCount);
            processPendingTouches(vIntRunCount);
            updateAnimation();
            return;
        }

        updateOrbit(vIntRunCount);
        processPendingTouches(vIntRunCount);
        touchX = currentX;
        touchY = currentY;
        updateAnimation();
    }

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return originX;
    }

    @Override
    public boolean isOnScreenForTouch() {
        ObjectServices svc = tryServices();
        if (svc == null || svc.camera() == null) {
            return super.isOnScreenForTouch();
        }
        // Obj_Bumper adds CNZ bumpers to Collision_response_list using the
        // original anchor $30(a0), not the current orbit point:
        // (origin_x & $FF80) - Camera_X_pos_coarse_back <= $280
        // (sonic3k.asm:68881-68886). Camera_X_pos_coarse_back is
        // (Camera_X_pos - $80) & $FF80 (sonic3k.asm:37472-37478).
        int cameraCoarseBack = ((svc.camera().getX() & 0xFFFF) - CAMERA_COARSE_BACK_OFFSET) & 0xFF80;
        int originCoarse = originX & 0xFF80;
        int delta = (originCoarse - cameraCoarseBack) & 0xFFFF;
        return delta <= LIST_ADD_WINDOW;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                true,
                true,
                multiRegionSource,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                multiRegionSource
                        ? TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY
                        : TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        if (initialAngle == 0) {
            return null;
        }
        return new TouchRegion[] { new TouchRegion(touchX, touchY, COLLISION_FLAGS) };
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)
                || player.isHurt() || player.getDead()) {
            return;
        }
        if (player.isCpuControlled()) {
            pendingSidekickTouch = player;
        } else {
            pendingPrimaryTouch = player;
        }
    }

    private void updateOrbit(int vIntRunCount) {
        if (initialAngle == 0) {
            currentX = originX;
            currentY = originY;
            updateDynamicSpawn(currentX, currentY);
            return;
        }

        int resolvedFrame = resolveLevelFrameCounter(vIntRunCount);
        int phase = resolvedFrame & 0xFF;
        if (reverseOrbit) {
            phase = (-phase) & 0xFF;
        }
        int angle = (initialAngle + phase) & 0xFF;
        currentX = originX + (TrigLookupTable.cosHex(angle) >> ORBIT_RADIUS_SHIFT);
        currentY = originY + (TrigLookupTable.sinHex(angle) >> ORBIT_RADIUS_SHIFT);
        updateDynamicSpawn(currentX, currentY);
    }

    private int resolveLevelFrameCounter(int vIntRunCount) {
        ObjectServices svc = tryServices();
        LevelManager levelManager = svc != null ? svc.levelManager() : null;
        if (levelManager != null) {
            // The fresh-load Process_Sprites setup dispatch is represented by
            // ObjectManager's production setup lifecycle. The stored counter
            // therefore owns the adjacent Level_frame_counter epoch read by
            // Obj_Bumper. The retained results owner must not add another
            // orbit tick after its title-card handoff has been published.
            return levelManager.getFrameCounter();
        }
        return vIntRunCount + 1;
    }

    private void updateAnimation() {
        if (animTimer <= 0) {
            return;
        }
        animTimer--;
        if (animTimer == 0) {
            animFrame = 0;
        }
    }

    private void processPendingTouches(int vIntRunCount) {
        int levelFrameCounter = resolveLevelFrameCounterForBounce(vIntRunCount);
        AbstractPlayableSprite primary = pendingPrimaryTouch;
        AbstractPlayableSprite sidekick = pendingSidekickTouch;
        pendingPrimaryTouch = null;
        pendingSidekickTouch = null;

        if (primary != null) {
            applyBounce(primary, levelFrameCounter);
        }
        if (sidekick != null) {
            applyBounce(sidekick, levelFrameCounter);
        }
    }

    private int resolveLevelFrameCounterForBounce(int vIntRunCount) {
        ObjectServices svc = tryServices();
        LevelManager levelManager = svc != null ? svc.levelManager() : null;
        int orbitFrameOffset = initialAngle == 0 ? 0 : 1;
        if (levelManager != null) {
            // sub_32F56 reads Level_frame_counter. Stationary bumpers match the
            // stored engine counter directly; orbiting bumpers are positioned
            // from Level_frame_counter+1 earlier in Obj_Bumper, so their pending
            // touch response uses that same visible tick.
            return levelManager.getFrameCounter() + orbitFrameOffset;
        }
        return vIntRunCount + orbitFrameOffset;
    }

    private void applyBounce(AbstractPlayableSprite player, int frameCounter) {
        int dx = currentX - player.getCentreX();
        int dy = currentY - player.getCentreY();
        // ROM sub_32F56 uses move.b (Level_frame_counter).w. The 68000 is
        // big-endian, so byte-addressing the word label reads the high byte.
        int framePerturb = (frameCounter >>> 8) & 0x03;
        int angle = (TrigLookupTable.calcAngle((short) dx, (short) dy)
                + framePerturb) & 0xFF;
        int xSpeed = (TrigLookupTable.cosHex(angle) * -BOUNCE_VELOCITY) >> 8;
        int ySpeed = (TrigLookupTable.sinHex(angle) * -BOUNCE_VELOCITY) >> 8;

        player.setXSpeed((short) xSpeed);
        player.setYSpeed((short) ySpeed);
        player.setAir(true);
        player.setRollingJump(false);
        player.setPushing(false);
        player.setJumping(false);

        animFrame = 1;
        animTimer = ANIM_HIT_DURATION;

        try {
            services().playSfx(GameSound.BUMPER);
        } catch (Exception e) {
            // Gameplay state must not depend on audio availability in tests.
        }

        if (scoreHits < MAX_SCORE_HITS) {
            scoreHits++;
            ObjectServices svc = tryServices();
            GameStateManager gameState = svc != null ? svc.gameState() : null;
            if (gameState != null) {
                gameState.addScore(SCORE_PER_HIT);
            }
            if (svc != null && svc.objectManager() != null) {
                // ROM sub_32F56 adds the bumper score, then AllocateObject
                // creates Obj_EnemyScore at the bumper coordinates.
                // docs/skdisasm/sonic3k.asm:68980-68989
                spawnFreeChild(() -> new Sonic3kPointsObjectInstance(
                        new ObjectSpawn(currentX, currentY, 0x29, 0, 0, false, 0),
                        svc,
                        SCORE_PER_HIT));
            }
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(1);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_BUMPER);
        if (renderer == null) {
            return;
        }
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
