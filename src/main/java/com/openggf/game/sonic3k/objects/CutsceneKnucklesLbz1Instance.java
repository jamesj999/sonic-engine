package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.events.Sonic3kZoneEvents;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * Rival Knuckles sequence in Launch Base Zone Act 1.
 *
 * <p>ROM reference: {@code CutsceneKnux_LBZ1} and helpers
 * {@code loc_627C6}, {@code loc_6282A}, {@code loc_6285A}.
 */
public final class CutsceneKnucklesLbz1Instance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final int INITIAL_MAPPING_FRAME = 0x16;
    private static final int STAND_MAPPING_FRAME = 0x1C;
    private static final int CAMERA_MIN_Y = 0x00A0;
    private static final int SONG_FADE_FRAMES = 2 * 60;
    private static final int WAIT_AFTER_CAPTURE = 60 - 1;
    private static final int WAIT_AFTER_BOMB = 0x0F;
    private static final int WAIT_BEFORE_COLLAPSE = 0x7F;
    private static final int COLLAPSE_WAIT = 0x5F;
    private static final int EXIT_SPEED = 2;
    private static final int EXIT_CAMERA_MAX_X = 0x3B60;
    private static final int EXIT_CAMERA_MAX_Y_TARGET = 0x0148;
    private static final int OBJECT_PATTERN_BASE = PatternAtlasRange.OBJECTS.base();
    private static final int HELPER_X_OFFSET = -0x40;
    private static final int HELPER_Y_OFFSET = 0;
    private static final int BOMB_X_OFFSET = -8;
    private static final int BOMB_Y_OFFSET = -0x10;
    private static final int[] RUN_FRAMES = {0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11};
    private static final int RUN_DELAY = 4;
    private static final int[][] THROW_FRAMES = {
            {0x16, 7},
            {0x16, 7},
            {0x17, 7},
            {0x18, 7},
            {0x19, 0x13},
            {0x1A, 7},
            {0x1B, 0}
    };

    private Routine routine = Routine.INIT;
    private int currentX;
    private int currentY;
    private int mappingFrame = INITIAL_MAPPING_FRAME;
    private int timer;
    private int animationIndex;
    private int animationTimer;
    private boolean helperSignal;
    private boolean helperSpawned;
    private boolean bombSpawned;
    private boolean collapseChildrenSpawned;

    public CutsceneKnucklesLbz1Instance(ObjectSpawn spawn) {
        super(spawn, "CutsceneKnuxLBZ1");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
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
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        switch (routine) {
            case INIT -> routineInit(playerEntity);
            case WAIT_FOR_HELPER_SIGNAL -> routineWaitForHelperSignal();
            case WAIT_AFTER_SIGNAL -> routineWaitAfterSignal();
            case THROW_ANIMATION -> routineThrowAnimation();
            case WAIT_AFTER_BOMB -> routineWaitAfterBomb();
            case WAIT_BEFORE_COLLAPSE -> routineWaitBeforeCollapse(vIntRunCount);
            case COLLAPSE_WAIT -> routineCollapseWait(vIntRunCount);
            case EXIT_RIGHT -> routineExitRight();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES);
        if (renderer == null || !renderer.isReady()) {
            renderer = AizIntroArtLoader.getKnucklesRenderer(services());
        }
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    void signalHelperCapture() {
        helperSignal = true;
    }

    public int getRoutineForTest() {
        return switch (routine) {
            case INIT -> 0x00;
            case WAIT_FOR_HELPER_SIGNAL -> 0x02;
            case WAIT_AFTER_SIGNAL -> 0x04;
            case THROW_ANIMATION -> 0x06;
            case WAIT_AFTER_BOMB -> 0x08;
            case WAIT_BEFORE_COLLAPSE -> 0x0A;
            case COLLAPSE_WAIT -> 0x0C;
            case EXIT_RIGHT -> 0x0E;
        };
    }

    public int getMappingFrameForTest() {
        return mappingFrame;
    }

    public boolean hasHelperSignalForTest() {
        return helperSignal;
    }

    private void routineInit(PlayableEntity playerEntity) {
        if (isPlayerKnuckles()) {
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }
        if (playerEntity instanceof AbstractPlayableSprite player
                && ((player.getCentreX() & 0xFFFF) >= (currentX & 0xFFFF))) {
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }
        AizIntroArtLoader.loadCutsceneKnucklesArt(services());
        AizIntroArtLoader.applyKnucklesPalette(services());
        ensureBossExplosionArtReady();
        mappingFrame = INITIAL_MAPPING_FRAME;
        services().camera().setMinY((short) CAMERA_MIN_Y);
        spawnRangeHelperOnce();
        routine = Routine.WAIT_FOR_HELPER_SIGNAL;
    }

    private void routineWaitForHelperSignal() {
        if (!helperSignal) {
            return;
        }
        spawnDynamicObject(new SongFadeTransitionInstance(SONG_FADE_FRAMES, Sonic3kMusic.KNUCKLES.id));
        timer = WAIT_AFTER_CAPTURE;
        routine = Routine.WAIT_AFTER_SIGNAL;
    }

    private void routineWaitAfterSignal() {
        timer--;
        if (timer >= 0) {
            return;
        }
        startThrowAnimation();
        routine = Routine.THROW_ANIMATION;
    }

    private void routineThrowAnimation() {
        if (advanceThrowAnimation()) {
            timer = WAIT_AFTER_BOMB;
            spawnThrownBombOnce();
            routine = Routine.WAIT_AFTER_BOMB;
        }
    }

    private void routineWaitAfterBomb() {
        timer--;
        if (timer >= 0) {
            return;
        }
        mappingFrame = STAND_MAPPING_FRAME;
        timer = WAIT_BEFORE_COLLAPSE;
        setBombShakeActive(true);
        routine = Routine.WAIT_BEFORE_COLLAPSE;
    }

    private void routineWaitBeforeCollapse(int vIntRunCount) {
        applyCollapseShake();
        timer--;
        if (timer >= 0) {
            return;
        }
        spawnCollapseChildrenOnce();
        applyCollapseShake();
        timer = COLLAPSE_WAIT;
        routine = Routine.COLLAPSE_WAIT;
    }

    private void routineCollapseWait(int vIntRunCount) {
        applyCollapseShake();
        timer--;
        if (timer >= 0) {
            return;
        }
        animationIndex = 0;
        animationTimer = 0;
        mappingFrame = RUN_FRAMES[0];
        routine = Routine.EXIT_RIGHT;
    }

    private void routineExitRight() {
        // loc_62778 reads render_flags.on_screen before moving Knuckles.  The
        // flag comes from the prior Render_Sprites pass rather than the wider
        // placement lifetime margin used by ordinary tracked objects.
        if (isPreUpdateWithinRenderSpriteBounds(0x0E, 0)) {
            currentX += EXIT_SPEED;
            animateRun();
            return;
        }
        releasePlayersAndCamera();
        ObjectLifetimeOps.deleteNoRespawn(this);
    }

    private void startThrowAnimation() {
        animationIndex = 0;
        animationTimer = 0;
        mappingFrame = THROW_FRAMES[0][0];
    }

    private boolean advanceThrowAnimation() {
        if (animationIndex >= THROW_FRAMES.length) {
            return true;
        }
        if (animationTimer <= 0) {
            mappingFrame = THROW_FRAMES[animationIndex][0];
            animationTimer = THROW_FRAMES[animationIndex][1];
            animationIndex++;
            return animationIndex >= THROW_FRAMES.length && animationTimer == 0;
        }
        animationTimer--;
        return false;
    }

    private void animateRun() {
        animationTimer--;
        if (animationTimer >= 0) {
            return;
        }
        mappingFrame = RUN_FRAMES[animationIndex];
        animationIndex = (animationIndex + 1) % RUN_FRAMES.length;
        animationTimer = RUN_DELAY;
    }

    private void spawnRangeHelperOnce() {
        if (helperSpawned) {
            return;
        }
        helperSpawned = true;
        spawnChild(() -> new CutsceneKnucklesLbz1RangeHelper(this, currentX + HELPER_X_OFFSET,
                currentY + HELPER_Y_OFFSET));
    }

    private void spawnThrownBombOnce() {
        if (bombSpawned) {
            return;
        }
        bombSpawned = true;
        spawnChild(() -> new CutsceneKnucklesLbz1ThrownBomb(currentX + BOMB_X_OFFSET,
                currentY + BOMB_Y_OFFSET));
    }

    private void spawnCollapseChildrenOnce() {
        if (collapseChildrenSpawned) {
            return;
        }
        collapseChildrenSpawned = true;
        for (int subtype = 0; subtype < 4; subtype++) {
            final int childSubtype = subtype;
            spawnChild(() -> new CutsceneKnucklesLbz1CollapseChild(this, childSubtype));
        }
    }

    private void ensureBossExplosionArtReady() {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        if (renderManager.getArtProvider() instanceof Sonic3kObjectArtProvider s3kProvider) {
            s3kProvider.ensureBossExplosionArtLoaded();
        }
        if (services().graphicsManager() != null) {
            renderManager.ensurePatternsCached(services().graphicsManager(), OBJECT_PATTERN_BASE);
        }
    }

    private void releasePlayersAndCamera() {
        if (services().spriteManager() != null) {
            for (com.openggf.sprites.Sprite sprite : services().spriteManager().getAllSprites()) {
                if (sprite instanceof AbstractPlayableSprite player) {
                    releasePlayer(player);
                }
            }
            // Player 2 is maintained in the sidekick registry even during CPU
            // suppression.  Clear the native fixed-slot counterpart explicitly,
            // mirroring loc_6278A's Player_1/Player_2 object_control writes.
            for (AbstractPlayableSprite sidekick : services().spriteManager().getRegisteredSidekicks()) {
                releasePlayer(sidekick);
            }
        }
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(services().zoneRuntimeRegistry()).orElse(null);
        if (state != null) {
            state.setLbz1KnucklesCutsceneControlLocked(false);
        }
        services().camera().setMaxX((short) EXIT_CAMERA_MAX_X);
        services().camera().setMaxXTarget((short) EXIT_CAMERA_MAX_X);
        services().camera().setMaxYTarget((short) EXIT_CAMERA_MAX_Y_TARGET);
        if (services().levelEventProvider() instanceof Sonic3kLevelEventManager manager) {
            // loc_6278A writes Camera_target_max_Y_pos before the current
            // DynamicLevelEvents tail. Publish that tail's resulting live
            // Camera_max_Y_pos to the controller mirror used by next frame's
            // Tails_Check_Screen_Boundaries.
            manager.requestSidekickBoundsPublishAfterCameraEasing();
        }
        Sonic3kZoneEvents.loadPaletteFromPalPointers(Sonic3kConstants.PAL_POINTERS_LBZ1_INDEX);
    }

    private static void releasePlayer(AbstractPlayableSprite player) {
        ObjectControlState.none().applyTo(player);
        player.setControlLocked(false);
        player.clearForcedInputMask();
        player.clearLogicalInputState();
    }

    private void applyCollapseShake() {
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(services().zoneRuntimeRegistry()).orElse(null);
        if (state != null) {
            // The object writes Screen_shake_flag=-1; it does not sample
            // ScreenShakeArray2 itself. The later ShakeScreen/scroll phase
            // owns the Level_frame_counter-indexed offset.
            state.setLbz1KnucklesBombShakeActive(true);
        }
    }

    private void setBombShakeActive(boolean active) {
        LbzZoneRuntimeState state = S3kRuntimeStates.currentLbz(services().zoneRuntimeRegistry()).orElse(null);
        if (state != null) {
            state.setLbz1KnucklesBombShakeActive(active);
        }
    }

    private boolean isPlayerKnuckles() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration()) == PlayerCharacter.KNUCKLES;
    }

    private enum Routine {
        INIT,
        WAIT_FOR_HELPER_SIGNAL,
        WAIT_AFTER_SIGNAL,
        THROW_ANIMATION,
        WAIT_AFTER_BOMB,
        WAIT_BEFORE_COLLAPSE,
        COLLAPSE_WAIT,
        EXIT_RIGHT
    }
}
