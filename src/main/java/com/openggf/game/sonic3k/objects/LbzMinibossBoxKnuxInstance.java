package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Knuckles-route LBZ1 miniboss staging box.
 *
 * <p>ROM: {@code Obj_LBZMinibossBoxKnux} at {@code sonic3k.asm:192537}. It
 * locks the camera over the Knuckles arena ({@code word_8CF70}), hosts two
 * closed boxes ({@code loc_8D046}, dx ±$20), spawns two {@code Obj_LBZMiniboss}
 * instances (subtypes 0/2), and after both report defeat ({@code $38} bits 0/2)
 * runs the Knuckles end-of-act sequence: gradual max-Y raise to {@code $A80},
 * five wall explosions at {@code y=$A20}, the Knuckles box-chunk swap, and the
 * end-sign handoff once the camera descends past {@code $A7C}.
 */
public final class LbzMinibossBoxKnuxInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    /** ROM word_8CF70 row 1: activation camera range (yMin,yMax,xMin,xMax). */
    private static final int RANGE_Y_MIN = 0x7B6;
    private static final int RANGE_Y_MAX = 0x9C0;
    private static final int RANGE_X_MIN = 0x3BA0;
    private static final int RANGE_X_MAX = 0x3CA0;
    /** ROM word_8CF70 row 2: locked camera bounds for the fight. */
    private static final int CAMERA_MIN_Y = 0x7B6;
    private static final int CAMERA_MAX_Y = 0x936;
    private static final int CAMERA_MIN_X = 0x3BC0;
    private static final int CAMERA_MAX_X = 0x3C80;
    private static final int BOX_CHILD_DX = 0x20;
    private static final int FIGHT_START_CAMERA_Y = 0x932;
    private static final int ARM_WAIT_FRAMES = 2 * 60 - 1;
    private static final int POST_DEFEAT_CHUNK_SWAP_WAIT = 0x1F;
    private static final int POST_DEFEAT_MAX_Y = 0xA80;
    /** ROM Obj_IncLevEndYGradual accumulates $8000 per frame. */
    private static final int INC_LEVEL_END_Y_STEP = 0x8000;
    private static final int SIGNPOST_CAMERA_Y = 0xA7C;
    private static final int[] WALL_EXPLOSION_X = {0x3C40, 0x3C80, 0x3CC0, 0x3D00, 0x3D40};
    private static final int WALL_EXPLOSION_Y = 0xA20;
    private static final int MUSIC_FADE_FRAMES = 90;
    private static final int LEVEL_MUSIC_FADE_FRAMES = 2 * 60;
    private static final int BOX_PALETTE_LINE = 2;
    private static final int SIGNPOST_APPARENT_ACT = 0;
    private static final int DEFEAT_BITS_COMPLETE = 0b101;

    private enum Phase {
        WAIT_CAMERA_RANGE,
        WAIT_FIGHT_START,
        ARM_WAIT,
        FIGHT,
        POST_DEFEAT,
        AWAIT_SIGNPOST_CAMERA,
        DONE
    }

    private Phase phase = Phase.WAIT_CAMERA_RANGE;
    private LbzMinibossBoxRig leftRig;
    private LbzMinibossBoxRig rightRig;
    private int armTimer;
    private int defeatBits;
    private int chunkSwapTimer = -1;
    private boolean maxYRaiseActive;
    private int maxYAccumulator;
    private final List<S3kBossExplosionController> wallExplosions = new ArrayList<>();

    public LbzMinibossBoxKnuxInstance(ObjectSpawn spawn) {
        super(spawn, "LBZMinibossBoxKnux");
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    /** ROM loc_72584: each defeated miniboss sets $38 bit(subtype) on this box. */
    public void onKnucklesMinibossDefeated(int subtype) {
        defeatBits |= 1 << (subtype & 0x07);
    }

    public int getDefeatBitsForTest() {
        return defeatBits;
    }

    public Phase getPhaseForTest() {
        return phase;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        if (!isPlayerKnuckles()) {
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }
        var cameraForRigs = services().camera();
        int cameraX = cameraForRigs != null
                ? cameraForRigs.getX() & 0xFFFF
                : LbzMinibossBoxRig.NO_CAMERA;
        if (leftRig != null) {
            leftRig.update(cameraX);
            rightRig.update(cameraX);
        }
        tickWallExplosions();
        tickChunkSwap();
        switch (phase) {
            case WAIT_CAMERA_RANGE -> initializeWhenCameraInRange();
            case WAIT_FIGHT_START -> updateWaitFightStart();
            case ARM_WAIT -> updateArmWait();
            case FIGHT -> updateFight();
            case POST_DEFEAT -> {
                // POST_DEFEAT work is one-shot; tickChunkSwap advances to
                // AWAIT_SIGNPOST_CAMERA once the $1F wait expires.
            }
            case AWAIT_SIGNPOST_CAMERA -> updateAwaitSignpostCamera();
            case DONE -> {
                boolean explosionsDone = wallExplosions.stream().allMatch(S3kBossExplosionController::isFinished);
                boolean piecesGone = leftRig == null
                        || (!leftRig.hasVisiblePieces() && !rightRig.hasVisiblePieces());
                if (explosionsDone && piecesGone) {
                    ObjectLifetimeOps.deleteNoRespawn(this);
                }
            }
        }
        // loc_8CFC8 creates the Child6_IncLevY worker with CreateChild6_Simple
        // -> AllocateObjectAfterCurrent (sonic3k.asm:192565-192600,177119-177140,
        // 37917-37930), which only ever returns a slot after this object. The
        // ascending Process_Sprites walk therefore reaches the worker later in
        // the same pass, including the pass that creates it.
        updateGradualMaxYRaise();
    }

    private void initializeWhenCameraInRange() {
        int cameraX = services().camera().getX() & 0xFFFF;
        int cameraY = services().camera().getY() & 0xFFFF;
        if (cameraY < RANGE_Y_MIN || cameraY > RANGE_Y_MAX
                || cameraX < RANGE_X_MIN || cameraX > RANGE_X_MAX) {
            return;
        }
        ensureArtLoaded();
        var camera = services().camera();
        camera.setMinY((short) CAMERA_MIN_Y);
        camera.setMaxY((short) CAMERA_MAX_Y);
        camera.setMinX((short) CAMERA_MIN_X);
        camera.setMaxX((short) CAMERA_MAX_X);
        leftRig = new LbzMinibossBoxRig(getX() - BOX_CHILD_DX, getY());
        rightRig = new LbzMinibossBoxRig(getX() + BOX_CHILD_DX, getY());
        phase = Phase.WAIT_FIGHT_START;
    }

    private void updateWaitFightStart() {
        if ((services().camera().getY() & 0xFFFF) < FIGHT_START_CAMERA_Y) {
            return;
        }
        // ROM loc_8CFB2 -> bit 1: the subtype-0 box child starts the miniboss
        // music fade and both children arm the 2*60-1 frame wait.
        spawnFreeChild(() -> new SongFadeTransitionInstance(
                MUSIC_FADE_FRAMES, Sonic3kMusic.MINIBOSS.id));
        armTimer = ARM_WAIT_FRAMES;
        phase = Phase.ARM_WAIT;
    }

    private void updateArmWait() {
        armTimer--;
        if (armTimer >= 0) {
            return;
        }
        // ROM loc_8D082: each box child releases its pieces, plays
        // sfx_BossActivate, and spawns Obj_LBZMiniboss with this box as parent.
        leftRig.release();
        rightRig.release();
        services().playSfx(Sonic3kSfx.BOSS_ACTIVATE.id);
        spawnKnucklesMiniboss(getX() - BOX_CHILD_DX, getY(), 0);
        spawnKnucklesMiniboss(getX() + BOX_CHILD_DX, getY(), 2);
        phase = Phase.FIGHT;
    }

    private void spawnKnucklesMiniboss(int x, int y, int subtype) {
        spawnChild(() -> {
            LbzMinibossInstance miniboss = new LbzMinibossInstance(new ObjectSpawn(
                    x & 0xFFFF, y & 0xFFFF, Sonic3kObjectIds.LBZ_MINIBOSS, subtype, 0, false, 0));
            miniboss.setKnucklesFightParent(this);
            return miniboss;
        });
    }

    private void updateFight() {
        if ((defeatBits & DEFEAT_BITS_COMPLETE) != DEFEAT_BITS_COMPLETE) {
            return;
        }
        // ROM loc_8CFC8: stop the HUD timer, fade to the level music, start the
        // gradual max-Y raise, blow open the wall, and arm the chunk swap.
        if (services().levelGamestate() != null) {
            services().levelGamestate().pauseTimer();
        }
        int levelMusicId = services().getCurrentLevelMusicId();
        if (levelMusicId > 0) {
            spawnFreeChild(() -> new SongFadeTransitionInstance(LEVEL_MUSIC_FADE_FRAMES, levelMusicId));
        }
        maxYRaiseActive = true;
        maxYAccumulator = 0;
        // loc_8CFC8 writes Camera_stored_max_Y_pos and Camera_target_max_Y_pos
        // once, here; Obj_IncLevEndYGradual then moves Camera_max_Y_pos alone.
        var armCamera = services().camera();
        if (armCamera != null) {
            armCamera.setMaxYTarget((short) POST_DEFEAT_MAX_Y);
        }
        for (int x : WALL_EXPLOSION_X) {
            wallExplosions.add(new S3kBossExplosionController(x, WALL_EXPLOSION_Y, 0, services().rng()));
        }
        chunkSwapTimer = POST_DEFEAT_CHUNK_SWAP_WAIT;
        phase = Phase.POST_DEFEAT;
    }

    private void tickChunkSwap() {
        if (chunkSwapTimer < 0) {
            return;
        }
        chunkSwapTimer--;
        if (chunkSwapTimer >= 0) {
            return;
        }
        // ROM loc_8D020: Events_fg_4=$55 -> loc_53F50 swaps the three Knuckles
        // boss-area chunks.
        if (services().levelEventProvider() instanceof Sonic3kLevelEventManager manager
                && manager.getLbzEvents() != null) {
            manager.getLbzEvents().applyMinibossBoxOpenedChunkSwap(true);
        }
        if (phase == Phase.POST_DEFEAT) {
            phase = Phase.AWAIT_SIGNPOST_CAMERA;
        }
    }

    private void updateAwaitSignpostCamera() {
        if ((services().camera().getY() & 0xFFFF) < SIGNPOST_CAMERA_Y) {
            return;
        }
        // ROM loc_8D02E -> Wait_FadeToLevelMusic -> Obj_EndSignControl.
        int signpostX = getX();
        spawnChild(() -> new S3kBossDefeatSignpostFlow(
                signpostX, SIGNPOST_APPARENT_ACT,
                S3kBossDefeatSignpostFlow.CleanupAction.LOAD_MHZ2_OBJECT_PALETTE));
        phase = Phase.DONE;
    }

    private void updateGradualMaxYRaise() {
        if (!maxYRaiseActive) {
            return;
        }
        var camera = services().camera();
        int currentMax = camera.getMaxY() & 0xFFFF;
        if (currentMax >= POST_DEFEAT_MAX_Y) {
            camera.setMaxY((short) POST_DEFEAT_MAX_Y);
            maxYRaiseActive = false;
            return;
        }
        maxYAccumulator += INC_LEVEL_END_Y_STEP;
        int delta = maxYAccumulator >>> 16;
        int nextMax = Math.min(POST_DEFEAT_MAX_Y, currentMax + delta);
        camera.setMaxY((short) nextMax);
    }

    private void tickWallExplosions() {
        for (S3kBossExplosionController controller : wallExplosions) {
            if (controller.isFinished()) {
                continue;
            }
            controller.tick();
            for (S3kBossExplosionController.PendingExplosion explosion : controller.drainPendingExplosions()) {
                if (explosion.playSfx()) {
                    services().playSfx(Sonic3kSfx.EXPLODE.id);
                }
                spawnChild(() -> new S3kBossExplosionChild(explosion.x(), explosion.y()));
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || leftRig == null) {
            return;
        }
        PatternSpriteRenderer boxRenderer = getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX);
        if (boxRenderer == null) {
            return;
        }
        leftRig.draw(boxRenderer, BOX_PALETTE_LINE);
        rightRig.draw(boxRenderer, BOX_PALETTE_LINE);
    }

    private boolean isPlayerKnuckles() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration()) == PlayerCharacter.KNUCKLES;
    }

    private void ensureArtLoaded() {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null && renderManager.getArtProvider() instanceof Sonic3kObjectArtProvider provider) {
            provider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.LBZ_MINIBOSS);
            provider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX);
        }
    }
}
