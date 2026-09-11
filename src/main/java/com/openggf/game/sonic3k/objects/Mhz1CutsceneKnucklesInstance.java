package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.RespawnState;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.save.SaveReason;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.runtime.S3kZoneRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.physics.Direction;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * MHZ Act 1 rival-Knuckles controller.
 *
 * <p>ROM reference: {@code Obj_MHZ1CutsceneKnuckles}. The ROM stores this
 * object's real routine in the shared event byte {@code _unkFAB8}; the instance
 * mirrors that byte directly so the MHZ1 button can signal the cleanup routine.
 */
public final class Mhz1CutsceneKnucklesInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_WAIT_PLAYER = 0x02;
    private static final int ROUTINE_WAIT_LANDING = 0x04;
    private static final int ROUTINE_WAIT_BEFORE_SCROLL = 0x06;
    private static final int ROUTINE_SCROLL_CAMERA = 0x08;
    private static final int ROUTINE_WAIT_BUTTON = 0x0A;
    private static final int ROUTINE_CLEANUP = 0x0C;

    private static final int SONIC_CLAMP_X = 0x0389;
    private static final int SIDEKICK_CLAMP_X = 0x0371;
    private static final int CAMERA_TARGET_Y = 0x05B0;
    private static final int SCROLL_STEP = 2;
    private static final int WAIT_BEFORE_SCROLL = 0x20;
    private static final int POST_CUTSCENE_CHECKPOINT_INDEX = 1;
    private static final int POST_CUTSCENE_RESTART_X = 0x0190;
    private static final int POST_CUTSCENE_RESTART_Y = 0x056C;
    private static final int CUTSCENE_PALETTE_LINE = 1;

    private int x;
    private int y;
    private int workspaceRoutine = ROUTINE_INIT;
    private int timer;
    private boolean playerTwoStopperSpawned;
    private boolean levelMusicTransitionSpawned;
    @RewindTransient(reason = "Derived cleanup cache copied from the level palette before Pal_CutsceneKnux is loaded")
    private Palette savedPaletteLine1;

    public Mhz1CutsceneKnucklesInstance(ObjectSpawn spawn) {
        super(spawn, "MHZ1CutsceneKnuckles");
        this.x = spawn.x();
        this.y = spawn.y();
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        switch (workspaceRoutine) {
            case ROUTINE_INIT -> routineInit();
            case ROUTINE_WAIT_PLAYER -> routineWaitPlayer(playerEntity);
            case ROUTINE_WAIT_LANDING -> routineWaitLanding(playerEntity);
            case ROUTINE_WAIT_BEFORE_SCROLL -> routineWaitBeforeScroll(playerEntity);
            case ROUTINE_SCROLL_CAMERA -> routineScrollCamera();
            case ROUTINE_WAIT_BUTTON -> {
                // The MHZ1 button owns the wait and eventually writes routine $0C.
            }
            case ROUTINE_CLEANUP -> routineCleanup(playerEntity);
            default -> workspaceRoutine = ROUTINE_WAIT_PLAYER;
        }
    }

    private void routineInit() {
        if (currentPlayerCharacter() == PlayerCharacter.KNUCKLES || lastStarPostHitIsSet()) {
            setDestroyed(true);
            return;
        }
        workspaceRoutine = ROUTINE_WAIT_PLAYER;
        snapshotPaletteLine1();
        if (!playerTwoStopperSpawned && services().playerQuery().nativeP2OrNull() != null) {
            spawnFreeChild(() -> new Mhz1CutscenePlayerTwoStopper(this));
            playerTwoStopperSpawned = true;
        }
    }

    private void routineWaitPlayer(PlayableEntity playerEntity) {
        if (playerEntity == null || (playerEntity.getCentreX() & 0xFFFF) < SONIC_CLAMP_X) {
            return;
        }
        if (playerEntity instanceof AbstractPlayableSprite playable) {
            NativePositionOps.writeXPosPreserveSubpixel(playable, SONIC_CLAMP_X);
        } else {
            playerEntity.setCentreX((short) SONIC_CLAMP_X);
        }
        // loc_62D04 calls Stop_Object (sonic3k.asm:177552-177556), which clears
        // x_vel, y_vel AND ground_vel. Clearing only the horizontal pair leaves the
        // player falling into the clamp with the gravity it had accumulated.
        playerEntity.setXSpeed((short) 0);
        playerEntity.setYSpeed((short) 0);
        playerEntity.setGSpeed((short) 0);
        if (playerEntity instanceof AbstractPlayableSprite playable) {
            playable.setDirection(Direction.RIGHT);
            playable.setRenderFlips(false, playable.getRenderVFlip());
            playable.setControlLocked(true);
            playable.clearLogicalInputState();
            playable.clearForcedInputMask();
        }
        workspaceRoutine = ROUTINE_WAIT_LANDING;
    }

    private void routineWaitLanding(PlayableEntity playerEntity) {
        if (playerEntity != null && playerEntity.getAir()) {
            return;
        }
        workspaceRoutine = ROUTINE_WAIT_BEFORE_SCROLL;
        timer = WAIT_BEFORE_SCROLL;
        // ROM loc_62D2C falls through to loc_62D42 after writing $2E=$20,
        // so the wait counter is decremented once on the landing frame.
        routineWaitBeforeScroll(playerEntity);
    }

    private void routineWaitBeforeScroll(PlayableEntity playerEntity) {
        if (timer > 0) {
            timer--;
            return;
        }
        if (playerEntity instanceof AbstractPlayableSprite playable) {
            playable.setForcedInputMask(AbstractPlayableSprite.INPUT_DOWN);
        }
        Camera camera = services().camera();
        if (camera != null) {
            camera.setFrozen(true);
        }
        workspaceRoutine = ROUTINE_SCROLL_CAMERA;
        routineScrollCamera();
    }

    private void routineScrollCamera() {
        Camera camera = services().camera();
        if (camera == null) {
            workspaceRoutine = ROUTINE_WAIT_BUTTON;
            return;
        }
        int cameraY = camera.getY() & 0xFFFF;
        if (cameraY >= CAMERA_TARGET_Y) {
            camera.setY((short) CAMERA_TARGET_Y);
            workspaceRoutine = ROUTINE_WAIT_BUTTON;
            return;
        }
        int nextY = Math.min(CAMERA_TARGET_Y, cameraY + SCROLL_STEP);
        camera.setY((short) nextY);
        if (nextY >= CAMERA_TARGET_Y) {
            workspaceRoutine = ROUTINE_WAIT_BUTTON;
        }
    }

    private void routineCleanup(PlayableEntity playerEntity) {
        workspaceRoutine = ROUTINE_INIT;
        if (playerEntity instanceof AbstractPlayableSprite playable) {
            playable.setControlLocked(false);
            playable.clearLogicalInputState();
            playable.clearForcedInputMask();
        }
        cleanupNativePlayerTwo();
        Camera camera = services().camera();
        if (camera != null) {
            camera.setFrozen(false);
        }
        fadeBackToLevelMusicOnce();
        restorePaletteLine1();
        savePostCutsceneRestartPoint(camera);
        setDestroyed(true);
    }

    private void snapshotPaletteLine1() {
        Level level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= CUTSCENE_PALETTE_LINE) {
            return;
        }
        savedPaletteLine1 = level.getPalette(CUTSCENE_PALETTE_LINE).deepCopy();
    }

    private void restorePaletteLine1() {
        if (savedPaletteLine1 == null) {
            return;
        }
        Level level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= CUTSCENE_PALETTE_LINE) {
            return;
        }
        S3kPaletteWriteSupport.applyPaletteLine(
                services().paletteOwnershipRegistryOrNull(),
                level,
                services().graphicsManager(),
                S3kPaletteOwners.MHZ1_CUTSCENE_RESTORE,
                S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                CUTSCENE_PALETTE_LINE,
                savedPaletteLine1,
                true);
    }

    private void savePostCutsceneRestartPoint(Camera camera) {
        RespawnState checkpointState = services().checkpointState();
        if (checkpointState == null) {
            return;
        }
        int cameraX = camera != null ? camera.getX() & 0xFFFF : 0;
        int cameraY = camera != null ? camera.getY() & 0xFFFF : 0;
        checkpointState.restoreFromSaved(POST_CUTSCENE_RESTART_X, POST_CUTSCENE_RESTART_Y,
                cameraX, cameraY, POST_CUTSCENE_CHECKPOINT_INDEX);
        services().requestSessionSave(SaveReason.PROGRESSION_SAVE);
    }

    private PlayerCharacter currentPlayerCharacter() {
        if (services().zoneRuntimeRegistry() != null
                && services().zoneRuntimeRegistry().current() instanceof S3kZoneRuntimeState state) {
            return state.playerCharacter();
        }
        return PlayerCharacter.SONIC_AND_TAILS;
    }

    private boolean lastStarPostHitIsSet() {
        RespawnState checkpointState = services().checkpointState();
        return checkpointState != null && checkpointState.getLastCheckpointIndex() > 0;
    }

    private void cleanupNativePlayerTwo() {
        try {
            if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick) {
                sidekick.setControlLocked(false);
                sidekick.clearLogicalInputState();
                sidekick.clearForcedInputMask();
                sidekick.setAnimationId(Sonic3kAnimationIds.WAIT);
            }
        } catch (UnsupportedOperationException ignored) {
            // Partial headless services can omit Player_2 when the test is not about participation.
        }
    }

    static Mhz1CutsceneKnucklesInstance activeInstance(ObjectManager objectManager) {
        if (objectManager == null) {
            return null;
        }
        return objectManager.activeObjectsOfType(Mhz1CutsceneKnucklesInstance.class).stream()
                .filter(instance -> !instance.isDestroyed())
                .findFirst()
                .orElse(null);
    }

    void signalButtonCallback() {
        if (workspaceRoutine >= ROUTINE_WAIT_BUTTON) {
            workspaceRoutine = ROUTINE_CLEANUP;
        }
    }

    int getWorkspaceRoutineForTest() {
        return workspaceRoutine;
    }

    void forceReadyForButtonForTest() {
        workspaceRoutine = ROUTINE_WAIT_BUTTON;
    }

    private void fadeBackToLevelMusicOnce() {
        if (levelMusicTransitionSpawned) {
            return;
        }
        levelMusicTransitionSpawned = true;
        spawnFreeChild(() -> new SongFadeTransitionInstance(2 * 60, Sonic3kMusic.MHZ1.id));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // ROM Obj_MHZ1CutsceneKnuckles is an invisible controller; visible Knuckles sprites are child objects.
    }

    private static final class Mhz1CutscenePlayerTwoStopper extends AbstractObjectInstance
            implements RewindRecreatable {
        private Mhz1CutsceneKnucklesInstance owner;
        private boolean locked;

        private Mhz1CutscenePlayerTwoStopper(Mhz1CutsceneKnucklesInstance owner) {
            super(new ObjectSpawn(0, 0, Sonic3kObjectIds.MHZ1_CUTSCENE_KNUCKLES, 0, 0, false, 0),
                    "MHZ1CutsceneP2Stopper");
            this.owner = owner;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            Mhz1CutsceneKnucklesInstance liveOwner = findNearestLiveOwner(ctx);
            return liveOwner == null ? null : new Mhz1CutscenePlayerTwoStopper(liveOwner);
        }

        @Override
        public int getX() {
            return SIDEKICK_CLAMP_X;
        }

        @Override
        public int getY() {
            return owner.y;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (!(services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick)) {
                setDestroyed(true);
                return;
            }
            if (owner.workspaceRoutine == ROUTINE_INIT || owner.isDestroyed()) {
                releaseSidekick(sidekick);
                setDestroyed(true);
                return;
            }
            int sidekickX = sidekick.getCentreX() & 0xFFFF;
            if (!locked) {
                if (owner.workspaceRoutine < ROUTINE_WAIT_LANDING && sidekickX < SIDEKICK_CLAMP_X) {
                    return;
                }
                lockSidekick(sidekick);
                locked = true;
            }
            // ROM loc_62DDC: move.w #$371,d0 / cmp.w x_pos(a1),d0 / bhi.s
            // locret_62E18 returns while $371 is still HIGHER than Tails's
            // x_pos, so the duck pose and Stop_Object only apply once Tails has
            // reached the marker (sonic3k.asm:130020-130030). The comparison was
            // inverted here, which held Tails in the duck animation for the
            // whole approach — MHZ complete-run rows 218-849 record anim 0
            // against the engine's 8.
            if (owner.workspaceRoutine < ROUTINE_WAIT_LANDING || sidekickX < SIDEKICK_CLAMP_X) {
                return;
            }
            sidekick.setAnimationId(Sonic3kAnimationIds.DUCK);
            stopSidekick(sidekick);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        private static void lockSidekick(AbstractPlayableSprite sidekick) {
            sidekick.setControlLocked(true);
            sidekick.clearLogicalInputState();
            if (sidekick.getCpuController() != null) {
                // loc_62DC4: st (Ctrl_2_locked).w / clr.w (Ctrl_2_logical).w
                // before Stop_Object (docs/skdisasm/sonic3k.asm:130013-130018).
                sidekick.getCpuController().setController2SignedLocked(true);
                sidekick.getCpuController().clearController2LogicalLatch();
            }
            stopSidekick(sidekick);
        }

        private static void releaseSidekick(AbstractPlayableSprite sidekick) {
            sidekick.setControlLocked(false);
            sidekick.clearLogicalInputState();
            if (sidekick.getCpuController() != null) {
                // loc_62E04/loc_62E1A clear Ctrl_2_locked and Ctrl_2_logical
                // when this helper releases/deletes (docs/skdisasm/sonic3k.asm:130033-130047).
                sidekick.getCpuController().setController2SignedLocked(false);
                sidekick.getCpuController().clearController2LogicalLatch();
            }
        }

        private static void stopSidekick(AbstractPlayableSprite sidekick) {
            sidekick.setXSpeed((short) 0);
            sidekick.setYSpeed((short) 0);
            sidekick.setGSpeed((short) 0);
        }

        private static Mhz1CutsceneKnucklesInstance findNearestLiveOwner(RewindRecreateContext ctx) {
            if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            Mhz1CutsceneKnucklesInstance best = null;
            long bestDistance = Long.MAX_VALUE;
            for (ObjectInstance object : ctx.objectServices().objectManager().getActiveObjects()) {
                if (!(object instanceof Mhz1CutsceneKnucklesInstance candidate) || candidate.isDestroyed()) {
                    continue;
                }
                if (spawn == null) {
                    return candidate;
                }
                long dx = candidate.getX() - spawn.x();
                long dy = candidate.getY() - spawn.y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
            return best;
        }
    }
}
