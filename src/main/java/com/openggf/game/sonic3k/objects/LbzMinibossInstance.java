package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Launch Base Zone Act 1 miniboss.
 *
 * <p>ROM: {@code Obj_LBZMiniboss} at {@code sonic3k.asm:151366}. This object is
 * spawned by {@code Obj_LBZ1Robotnik}'s {@code ChildObjDat_8D264} handoff after
 * Robotnik drops the carried yellow box, by {@code Obj_LBZMinibossBox} on a
 * star-post restart, and twice (subtypes 0/2) by {@code Obj_LBZMinibossBoxKnux}
 * on the Knuckles route.
 */
public final class LbzMinibossInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, SpawnRewindRecreatable {
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_INIT_WAIT = 0x02;
    private static final int ROUTINE_RISE_WAIT = 0x04;
    private static final int ROUTINE_OPENING = 0x06;
    private static final int ROUTINE_TRACK_PLAYER = 0x08;
    private static final int ROUTINE_ESCAPE = 0x0A;

    private static final int INITIAL_HITS = 6;
    private static final int OPEN_COLLISION_FLAGS = 0x06;
    private static final int HIT_REACTION_FRAMES = 0x20;
    private static final int INIT_WAIT = 0x10;
    private static final int RISE_WAIT = 0x3F;
    private static final int OPENING_WAIT = 0x10;
    private static final int OPENING_LOOP_START = 0x0F;
    private static final int TRACK_VEL = 0x0100;
    private static final int PLAYER_TARGET_Y_OFFSET = -0x38;
    private static final int KNUCKLES_TARGET_X_OFFSET = 0x20;
    private static final int TARGET_DEADBAND = 4;
    private static final int ARM_COLLISION_FLAGS = 0x98;
    private static final int BODY_PALETTE_LINE = 1;
    private static final int SIGNPOST_APPARENT_ACT = 0;
    /** ROM: BossDefeated sets $2E=$3F before Wait_NewDelay hands off to loc_72562. */
    private static final int DEFEAT_WAIT_FRAMES = 0x3F;
    /** ROM: Obj_Song_Fade_ToLevelMusic waits 2*60 frames before Restore_LevelMusic. */
    private static final int LEVEL_MUSIC_FADE_FRAMES = 2 * 60;
    /** ROM: sub_72910 sets $2E=$80 (Obj_Wait -> Go_Delete_Sprite) on detached arms. */
    private static final int DETACHED_PANEL_LIFETIME = 0x80;
    /** ROM: BossFlash toggles Normal_palette_line_2+$2 between 0 and $EEE. */
    private static final int FLASH_PALETTE_COLOR_INDEX = 1;
    private static final int FLASH_WHITE = 0x0EEE;
    private static final int FLASH_BLACK = 0x0000;

    private static final int[] BODY_RAW_FRAMES = {0, 1, 0, 2};
    private static final int[] PANEL_FRAMES = {7, 8, 7, 8, 7, 6};
    private static final int[] PANEL_ANGLE_A = {0x55, 0x00, 0xD5, 0xAA, 0x80, 0x68};
    private static final int[] PANEL_ANGLE_A_REVERSE = {0x2A, 0x7A, 0xB0, 0xDA, 0x00, 0x18};
    private static final int[] PANEL_ANGLE_B = {0xD5, 0x80, 0x55, 0x2A, 0x00, 0xF4};
    private static final int[] PANEL_ANGLE_B_REVERSE = {0xAA, 0x00, 0x2A, 0x55, 0x80, 0x8C};
    private static final int[] PANEL_ROUTINE4_WAITS = {0, 0x14, 9, 9, 9, 5};
    private static final int[] PANEL_ROUTINE8_WAITS = {0x34, 0x14, 9, 9, 9, 3};
    private static final int[] PANEL_DETACH_X_VEL = {0x100, -0x200, 0x300, 0x200, -0x100, -0x80};
    private static final int[] PANEL_DETACH_Y_VEL = {-0x100, -0x200, -0x200, -0x100, -0x200, -0x100};

    /**
     * ROM: byte_72988 raw-animation script for the centre child.
     * Three rows: {7: 3,4,5} -> {3F: 5,5,5} -> {7: 5,4,3,4 loop}. The $F8 jump
     * commands advance through the rows once, then the final row's $FC restarts
     * itself, so the steady state is 5,4,3,4 at delay 7.
     */
    private static final int[][] CENTER_ANIM_ROW_FRAMES = {{3, 4, 5}, {5, 5, 5}, {5, 4, 3, 4}};
    private static final int[] CENTER_ANIM_ROW_DELAYS = {7, 0x3F, 7};
    private static final int[] CENTER_ANIM_ROW_NEXT = {1, 2, 2};

    private final SubpixelMotion.State motion;
    private final List<PanelState> panels = new ArrayList<>();
    private int routine = ROUTINE_INIT;
    private int waitTimer = -1;
    private WaitCallback waitCallback = WaitCallback.NONE;
    private int hitCount = INITIAL_HITS;
    private int collisionFlags;
    private int hitReactionTimer;
    private int savedRoutine;
    private int homeX;
    private int bodyTouchX;
    private int openingLoopCounter;
    private int bodyFrame;
    private int bodyAnimIndex;
    private int bodyAnimTimer;
    private int centerChildFrame = CENTER_ANIM_ROW_FRAMES[0][0];
    private int centerChildAnimRow;
    private int centerChildAnimIndex;
    private int centerChildAnimTimer;
    private boolean flashColorIsBlack;
    private boolean parentBit0;
    private boolean parentBit1;
    private boolean defeated;
    private boolean bodyVisible = true;
    private int defeatWaitTimer = -1;
    private boolean defeatFlowSpawned;
    private S3kBossExplosionController defeatExplosionController;
    private boolean defeatExplosionCreationPending;
    private LbzMinibossBoxKnuxInstance knucklesFightParent;

    private enum WaitCallback {
        NONE,
        START_RISE_WAIT,
        ARM_OPENING,
        OPENING_STEP
    }

    public LbzMinibossInstance(ObjectSpawn spawn) {
        super(spawn, "LBZMiniboss");
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        this.bodyTouchX = spawn.x();
    }

    /**
     * ROM: loc_8D082 copies the Knuckles box's parent3/subtype onto the spawned
     * miniboss so loc_72584 can report the defeat back via $38 bit(subtype).
     */
    public void setKnucklesFightParent(LbzMinibossBoxKnuxInstance parent) {
        this.knucklesFightParent = parent;
    }

    @Override
    public int getX() {
        return motion.x & 0xFFFF;
    }

    @Override
    public int getY() {
        return motion.y & 0xFFFF;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getCollisionFlags() {
        if (defeated || hitReactionTimer > 0) {
            return 0;
        }
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return hitCount;
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        if (panels.isEmpty() || isDestroyed()) {
            return null;
        }
        List<TouchRegion> regions = new ArrayList<>();
        int bodyFlags = getCollisionFlags();
        if (bodyFlags != 0) {
            regions.add(new TouchRegion(bodyTouchX, getY(), bodyFlags));
        }
        for (PanelState panel : panels) {
            if (!panel.center && !panel.detached && !panel.deleted) {
                // Each native arm is a separate SST object. Its circular move
                // runs before Draw_And_Touch_Sprite publishes the pointer, so
                // the folded provider must expose the panel's live position.
                int touchX = panel.x;
                int touchY = panel.y;
                if (routine == ROUTINE_ESCAPE) {
                    // The parent subtracts two from y_pos before the linked
                    // child slots run. Preserve the one-pixel interleave those
                    // native slots expose through the shared object pointers.
                    touchY = (touchY + 1) & 0xFFFF;
                }
                regions.add(new TouchRegion(touchX, touchY, ARM_COLLISION_FLAGS));
            }
        }
        return regions.isEmpty() ? null : regions.toArray(TouchRegion[]::new);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TouchResponseProfile.fromProvider(this, multiRegionSource);
    }

    @Override
    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        if (defeated || hitReactionTimer > 0 || collisionFlags == 0) {
            return;
        }
        hitCount = Math.max(0, hitCount - 1);
        collisionFlags = 0;
        if (hitCount == 3) {
            // ROM loc_7285A: bset #0,$38(a0) when collision_property reaches 3;
            // the bit-2 (second-ring) arm chains detach from loc_728C8.
            parentBit0 = true;
        }
        if (hitCount == 0) {
            // ROM Touch_Enemy: the final hit sets status bit 7 on the boss, and
            // loc_7289A starts the defeat without playing sfx_BossHit again.
            startDefeat();
            return;
        }
        savedRoutine = routine;
        routine = ROUTINE_ESCAPE;
        hitReactionTimer = HIT_REACTION_FRAMES;
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        if (defeated) {
            updateDefeat();
            updateDynamicSpawn(getX(), getY());
            return;
        }
        // The native Collision_response_list retains the parent slot's X from
        // the execution pass that published it. The engine folds the parent's
        // later-slot tracker into this aggregate object, so retain that
        // published X separately from the position advanced below. Arm child
        // regions already maintain their own independent slot phase.
        bodyTouchX = getX();
        switch (routine) {
            case ROUTINE_INIT -> initialize();
            case ROUTINE_INIT_WAIT -> tickWait();
            case ROUTINE_RISE_WAIT -> {
                moveSprite2();
                tickWait();
            }
            case ROUTINE_OPENING -> {
                animateOpeningFrame();
                tickWait();
            }
            case ROUTINE_TRACK_PLAYER -> {
                trackPlayer(playerEntity);
                moveSprite2();
                animateOpeningFrame();
            }
            case ROUTINE_ESCAPE -> {
                // ROM loc_72558: subq.w #2,y_pos(a0) every frame of the hit flash.
                motion.y -= 2;
            }
            default -> {
            }
        }
        // ROM loc_72840 runs after the routine body each frame, so the final
        // hit-flash frame still applies the loc_72558 rise before restoring.
        updateHitReaction();
        // ROM: the arm/centre children are independent objects updated every
        // frame from creation, regardless of the parent's routine.
        updatePanels();
        updateDynamicSpawn(getX(), getY());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer bossRenderer = getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS);
        if (bossRenderer == null) {
            return;
        }
        if (bodyVisible) {
            bossRenderer.drawFrameIndex(bodyFrame, getX(), getY(), false, false, BODY_PALETTE_LINE);
        }
        for (PanelState panel : panels) {
            if (!panel.deleted) {
                bossRenderer.drawFrameIndex(panel.frame, panel.x, panel.y, false, false, BODY_PALETTE_LINE);
            }
        }
    }

    public int getRoutineForTest() {
        return routine;
    }

    public int getWaitTimerForTest() {
        return waitTimer;
    }

    public int getPanelCountForTest() {
        return panels.size();
    }

    public int getPanelRoutineForTest(int index, boolean secondRing) {
        int offset = 1 + (secondRing ? PANEL_FRAMES.length : 0);
        return panels.get(offset + index).childRoutine;
    }

    public int getPanelXForTest(int index, boolean secondRing) {
        return panelForTest(index, secondRing).x;
    }

    public int getPanelYForTest(int index, boolean secondRing) {
        return panelForTest(index, secondRing).y;
    }

    public int getPanelTouchYForTest(int index, boolean secondRing) {
        PanelState panel = panelForTest(index, secondRing);
        int touchY = panel.y;
        return routine == ROUTINE_ESCAPE ? (touchY + 1) & 0xFFFF : touchY;
    }

    private PanelState panelForTest(int index, boolean secondRing) {
        int offset = 1 + (secondRing ? PANEL_FRAMES.length : 0);
        return panels.get(offset + index);
    }

    public int getXVelocityForTest() {
        return motion.xVel;
    }

    public int getYVelocityForTest() {
        return motion.yVel;
    }

    public int getHitReactionTimerForTest() {
        return hitReactionTimer;
    }

    public boolean isDefeatedForTest() {
        return defeated;
    }

    public boolean isDefeatFlowSpawnedForTest() {
        return defeatFlowSpawned;
    }

    public void forceOpenForTest(int x, int y) {
        motion.x = x;
        motion.y = y;
        motion.xSub = 0;
        motion.ySub = 0;
        motion.xVel = 0;
        motion.yVel = 0;
        homeX = x;
        bodyTouchX = x;
        bodyFrame = 0;
        bodyAnimIndex = 0;
        bodyAnimTimer = OPENING_WAIT;
        openingLoopCounter = 2;
        resetCenterChildAnimation();
        routine = ROUTINE_TRACK_PLAYER;
        waitTimer = -1;
        waitCallback = WaitCallback.NONE;
        hitCount = INITIAL_HITS;
        collisionFlags = OPEN_COLLISION_FLAGS;
        parentBit1 = true;
        ensurePanelsCreated();
        updatePanels();
        updateDynamicSpawn(getX(), getY());
    }

    private void initialize() {
        ensureArtLoaded();
        loadPalette();
        homeX = getX();
        hitCount = INITIAL_HITS;
        collisionFlags = 0;
        services().gameState().setCurrentBossId(Sonic3kObjectIds.LBZ_MINIBOSS);
        ensurePanelsCreated();
        waitTimer = INIT_WAIT;
        waitCallback = WaitCallback.START_RISE_WAIT;
        routine = ROUTINE_INIT_WAIT;
    }

    private void tickWait() {
        if (waitTimer < 0) {
            return;
        }
        waitTimer--;
        if (waitTimer >= 0) {
            return;
        }
        WaitCallback callback = waitCallback;
        waitCallback = WaitCallback.NONE;
        switch (callback) {
            case START_RISE_WAIT -> startRiseWait();
            case ARM_OPENING -> armOpening();
            case OPENING_STEP -> openingStep();
            case NONE -> {
            }
        }
    }

    private void startRiseWait() {
        routine = ROUTINE_RISE_WAIT;
        motion.yVel = 0;
        waitTimer = RISE_WAIT;
        waitCallback = WaitCallback.ARM_OPENING;
    }

    private void armOpening() {
        routine = ROUTINE_OPENING;
        parentBit1 = true;
        openingLoopCounter = OPENING_LOOP_START;
        bodyFrame = BODY_RAW_FRAMES[0];
        bodyAnimIndex = 0;
        bodyAnimTimer = OPENING_WAIT;
        resetCenterChildAnimation();
        waitTimer = OPENING_WAIT;
        waitCallback = WaitCallback.OPENING_STEP;
    }

    private void openingStep() {
        openingLoopCounter--;
        if (openingLoopCounter <= 2) {
            routine = ROUTINE_TRACK_PLAYER;
            collisionFlags = OPEN_COLLISION_FLAGS;
            return;
        }
        waitTimer = OPENING_WAIT;
        waitCallback = WaitCallback.OPENING_STEP;
    }

    private void trackPlayer(PlayableEntity playerEntity) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            motion.xVel = 0;
            motion.yVel = 0;
            return;
        }

        // ROM loc_724E2 adds the offset to the boss x before comparing, so the
        // boss settles at player x MINUS $20 for subtype 0 (and plus for the
        // second Knuckles boss).
        int bossX = getX();
        if (isKnuckles()) {
            bossX += spawn.subtype() == 0 ? KNUCKLES_TARGET_X_OFFSET : -KNUCKLES_TARGET_X_OFFSET;
        }
        int projectedPlayerX = projectNativePosition(
                player.getCentreX(), player.getXSubpixelRaw(), player.getXSpeed());
        int dx = signedDelta(projectedPlayerX & 0xFFFF, bossX);
        motion.xVel = Math.abs(dx) <= TARGET_DEADBAND ? 0 : (dx < 0 ? -TRACK_VEL : TRACK_VEL);

        int targetY = (player.getCentreY() + PLAYER_TARGET_Y_OFFSET) & 0xFFFF;
        int dy = signedDelta(targetY, getY());
        motion.yVel = Math.abs(dy) <= TARGET_DEADBAND ? 0 : (dy < 0 ? -TRACK_VEL : TRACK_VEL);
    }

    private int projectNativePosition(int position, int subpixel, int velocity) {
        // The native tracker observes the player's complete 16:8 movement
        // result. Preserve the high fractional byte when deciding which side
        // of the four-pixel deadband the player reaches.
        return ((position << 8) + ((subpixel >> 8) & 0xFF) + velocity) >> 8;
    }

    private boolean isKnuckles() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration()) == PlayerCharacter.KNUCKLES;
    }

    private int signedDelta(int target, int current) {
        return (short) ((target & 0xFFFF) - (current & 0xFFFF));
    }

    private void moveSprite2() {
        SubpixelMotion.moveSprite2(motion);
    }

    private void animateOpeningFrame() {
        bodyAnimTimer--;
        if (bodyAnimTimer < 0) {
            bodyAnimIndex++;
            if (bodyAnimIndex >= BODY_RAW_FRAMES.length) {
                bodyAnimIndex = 0;
            }
            bodyFrame = BODY_RAW_FRAMES[bodyAnimIndex];
            bodyAnimTimer = openingLoopCounter;
        }
        updateCenterChildAnimation();
    }

    private void resetCenterChildAnimation() {
        centerChildAnimRow = 0;
        centerChildAnimIndex = 0;
        centerChildAnimTimer = CENTER_ANIM_ROW_DELAYS[0];
        centerChildFrame = CENTER_ANIM_ROW_FRAMES[0][0];
    }

    private void updateCenterChildAnimation() {
        if (!parentBit1) {
            return;
        }
        centerChildAnimTimer--;
        if (centerChildAnimTimer >= 0) {
            return;
        }
        centerChildAnimIndex++;
        if (centerChildAnimIndex >= CENTER_ANIM_ROW_FRAMES[centerChildAnimRow].length) {
            // ROM byte_72988 $F8/$FC commands: advance to the next row (or
            // restart the final row) and show its first frame immediately.
            centerChildAnimRow = CENTER_ANIM_ROW_NEXT[centerChildAnimRow];
            centerChildAnimIndex = 0;
        }
        centerChildFrame = CENTER_ANIM_ROW_FRAMES[centerChildAnimRow][centerChildAnimIndex];
        centerChildAnimTimer = CENTER_ANIM_ROW_DELAYS[centerChildAnimRow];
    }

    private void updateHitReaction() {
        if (hitReactionTimer <= 0) {
            return;
        }
        // ROM loc_7287A: BossFlash every frame of the $20 window, then restore
        // $EEE and the saved routine/collision when the counter expires.
        applyBossFlashToggle();
        hitReactionTimer--;
        if (hitReactionTimer == 0) {
            restoreBossFlashColor();
            routine = savedRoutine;
            collisionFlags = OPEN_COLLISION_FLAGS;
        }
    }

    private void applyBossFlashToggle() {
        flashColorIsBlack = !flashColorIsBlack;
        writeFlashColor(flashColorIsBlack ? FLASH_BLACK : FLASH_WHITE);
    }

    private void restoreBossFlashColor() {
        // ROM loc_72894 writes the literal $EEE back, not a saved colour.
        flashColorIsBlack = false;
        writeFlashColor(FLASH_WHITE);
    }

    private void writeFlashColor(int segaWord) {
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                services().currentLevel(),
                services().graphicsManager(),
                S3kPaletteOwners.LBZ_MINIBOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                BODY_PALETTE_LINE,
                new int[] {FLASH_PALETTE_COLOR_INDEX},
                new int[] {segaWord});
    }

    private void startDefeat() {
        defeated = true;
        collisionFlags = 0;
        hitReactionTimer = 0;
        restoreBossFlashColor();
        services().gameState().addScore(1000);
        if (!isKnuckles()) {
            // ROM: BossDefeated_StopTimer clears Update_HUD_timer; the Knuckles
            // route's loc_728C2 goes through plain BossDefeated instead.
            if (services().levelGamestate() != null) {
                services().levelGamestate().pauseTimer();
            }
        }
        defeatWaitTimer = DEFEAT_WAIT_FRAMES;
        defeatExplosionController = new S3kBossExplosionController(getX(), getY(), 0, services().rng());
        // CreateChild1_Normal installs Obj_CreateBossExplosion after the live
        // boss slot. Because that child slot is still ahead of the current
        // Process_Sprites cursor, its zeroed Obj_Wait tail dispatches the first
        // explosion in this same object pass. The following emission remains
        // three complete child-slot turns later.
        defeatExplosionController.dispatchCreation();
        defeatExplosionCreationPending = true;
        // ROM Touch_Enemy sets status bit 7 on the final hit; loc_728C8/loc_72902
        // then unravel both arm chains one panel per frame.
        for (PanelState panel : panels) {
            if (!panel.center && panel.index == 0) {
                panel.markedForDetach = true;
            }
        }
    }

    private void updateDefeat() {
        updatePanels();
        tickDefeatExplosions();
        if (defeatWaitTimer >= 0) {
            defeatWaitTimer--;
            if (defeatWaitTimer < 0) {
                // ROM Wait_NewDelay expiry -> loc_72562: the boss body stops
                // being drawn and the end-of-act flow takes over.
                bodyVisible = false;
                spawnDefeatHandoff();
            }
        }
        boolean explosionsDone = defeatExplosionController == null || defeatExplosionController.isFinished();
        if (defeatFlowSpawned && explosionsDone && allPanelsGone()) {
            setDestroyed(true);
        }
    }

    private void tickDefeatExplosions() {
        if (defeatExplosionController == null || defeatExplosionController.isFinished()) {
            return;
        }
        if (defeatExplosionCreationPending) {
            defeatExplosionCreationPending = false;
        } else {
            defeatExplosionController.tick();
        }
        for (S3kBossExplosionController.PendingExplosion explosion
                : defeatExplosionController.drainPendingExplosions()) {
            if (explosion.playSfx()) {
                services().playSfx(Sonic3kSfx.EXPLODE.id);
            }
            spawnChild(() -> new S3kBossExplosionChild(explosion.x(), explosion.y()));
        }
    }

    private void spawnDefeatHandoff() {
        if (defeatFlowSpawned) {
            return;
        }
        defeatFlowSpawned = true;
        if (isKnuckles() && knucklesFightParent != null) {
            // ROM loc_72584: report the kill back to Obj_LBZMinibossBoxKnux via
            // $38 bit(subtype) and delete without the signpost flow.
            knucklesFightParent.onKnucklesMinibossDefeated(spawn.subtype());
            return;
        }
        int levelMusicId = services().getCurrentLevelMusicId();
        if (levelMusicId > 0) {
            // ROM loc_72562 allocates Obj_Song_Fade_ToLevelMusic.
            spawnFreeChild(() -> new SongFadeTransitionInstance(LEVEL_MUSIC_FADE_FRAMES, levelMusicId));
        }
        // ROM loc_72562 restores x_pos from $3C before Obj_EndSignControl, so the
        // signpost falls at the boss's home X. AfterBoss_LBZ shares AfterBoss_MHZ
        // and loads Pal_MHZ2's first line during the results (original game bug).
        spawnChild(() -> new S3kBossDefeatSignpostFlow(
                homeX, SIGNPOST_APPARENT_ACT,
                S3kBossDefeatSignpostFlow.CleanupAction.LOAD_MHZ2_OBJECT_PALETTE,
                0, 0, 0, 0, false, true));
    }

    private boolean allPanelsGone() {
        for (PanelState panel : panels) {
            if (!panel.center && !panel.deleted) {
                return false;
            }
        }
        return true;
    }

    private void ensurePanelsCreated() {
        if (!panels.isEmpty()) {
            return;
        }
        panels.add(new PanelState(true, 0, false));
        for (int i = 0; i < PANEL_FRAMES.length; i++) {
            panels.add(new PanelState(false, i, false));
        }
        for (int i = 0; i < PANEL_FRAMES.length; i++) {
            panels.add(new PanelState(false, i, true));
        }
    }

    private void updatePanels() {
        for (PanelState panel : panels) {
            panel.update();
        }
    }

    private void ensureArtLoaded() {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null && renderManager.getArtProvider() instanceof Sonic3kObjectArtProvider provider) {
            provider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.LBZ_MINIBOSS);
        }
    }

    private void loadPalette() {
        try {
            byte[] palData = services().rom().readBytes(Sonic3kConstants.PAL_LBZ_MINIBOSS_ADDR, 32);
            S3kPaletteWriteSupport.applyLine(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    S3kPaletteOwners.LBZ_MINIBOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    BODY_PALETTE_LINE,
                    palData);
        } catch (IOException ignored) {
            // Palette loading is best-effort in headless tests without a ROM handle.
        }
    }

    private final class PanelState {
        private static final int ROUTINE_INIT_WAIT = 2;
        private static final int ROUTINE_ROTATE_IF_ARMED = 4;
        private static final int ROUTINE_SYNC_TO_PARENT = 6;
        private static final int ROUTINE_ROTATE_AND_WAIT = 8;
        private static final int ROUTINE_OUTER_PAUSE = 10;

        private final boolean center;
        private final int index;
        private final boolean secondRing;
        private int x;
        private int y;
        private int xSub;
        private int ySub;
        private int xVel;
        private int yVel;
        private int frame;
        private int angle;
        private int angleDelta = 4;
        private int childRoutine = ROUTINE_INIT_WAIT;
        private int childWaitTimer = 0x100;
        private PanelWaitCallback waitCallback = PanelWaitCallback.START_ROTATE_IF_ARMED;
        private boolean initialized;
        private boolean bit1;
        private boolean detached;
        private boolean markedForDetach;
        private boolean deleted;
        private int detachedLifeTimer;

        private enum PanelWaitCallback {
            NONE,
            START_ROTATE_IF_ARMED,
            AFTER_ROTATE_IF_ARMED,
            AFTER_ROTATE_AND_WAIT
        }

        private PanelState(boolean center, int index, boolean secondRing) {
            this.center = center;
            this.index = index;
            this.secondRing = secondRing;
            this.frame = center ? CENTER_ANIM_ROW_FRAMES[0][0] : PANEL_FRAMES[index];
            this.angle = center ? 0 : (secondRing ? PANEL_ANGLE_B[index] : PANEL_ANGLE_A[index]);
            this.bit1 = !center && index == 5;
            if (center) {
                childWaitTimer = -1;
                waitCallback = PanelWaitCallback.NONE;
            }
        }

        private void update() {
            if (deleted) {
                return;
            }
            if (detached) {
                SubpixelMotion.State state = new SubpixelMotion.State(x, y, xSub, ySub, xVel, yVel);
                SubpixelMotion.moveSprite(state, SubpixelMotion.S3K_GRAVITY);
                x = state.x;
                y = state.y;
                xSub = state.xSub;
                ySub = state.ySub;
                xVel = state.xVel;
                yVel = state.yVel;
                detachedLifeTimer--;
                if (detachedLifeTimer < 0) {
                    deleted = true;
                }
                return;
            }
            if (center) {
                x = getX();
                y = getY();
                xSub = motion.xSub;
                ySub = motion.ySub;
                if (parentBit1) {
                    frame = centerChildFrame;
                }
                return;
            }
            if (!initialized) {
                // loc_7261C falls through loc_727B0 to perform the initial
                // circular placement, then returns without calling Obj_Wait.
                // The $100 timer starts decrementing on the child's next SST
                // dispatch.
                initialized = true;
                moveCircular();
                return;
            }
            // ROM loc_728C8 (subtype 0) / loc_72902 (others): a marked panel
            // detaches, marking the next link so the chain unravels one panel
            // per frame.
            if (index == 0 && !markedForDetach) {
                if (defeated || (parentBit0 && secondRing)) {
                    markedForDetach = true;
                }
            }
            if (markedForDetach) {
                detachAndPropagate();
                return;
            }
            updateLinkedArmRoutine();
        }

        private void updateLinkedArmRoutine() {
            switch (childRoutine) {
                case ROUTINE_INIT_WAIT -> {
                    moveCircular();
                    tickPanelWait();
                }
                case ROUTINE_ROTATE_IF_ARMED -> {
                    if (bit1) {
                        angle = (angle + angleDelta) & 0xFF;
                        moveCircular();
                        tickPanelWait();
                    } else {
                        moveCircular();
                    }
                }
                case ROUTINE_SYNC_TO_PARENT -> {
                    PanelState parent = linkedParent();
                    angle = parent.angle;
                    moveCircular();
                    if (!parent.bit1) {
                        childRoutine = ROUTINE_ROTATE_AND_WAIT;
                    }
                }
                case ROUTINE_ROTATE_AND_WAIT -> {
                    angle = (angle + angleDelta) & 0xFF;
                    tickPanelWait();
                    moveCircular();
                }
                case ROUTINE_OUTER_PAUSE -> {
                    moveCircular();
                    tickPanelWait();
                }
                default -> moveCircular();
            }
        }

        private void tickPanelWait() {
            if (childWaitTimer < 0) {
                return;
            }
            childWaitTimer--;
            if (childWaitTimer >= 0) {
                return;
            }
            PanelWaitCallback callback = waitCallback;
            waitCallback = PanelWaitCallback.NONE;
            switch (callback) {
                case START_ROTATE_IF_ARMED -> startRotateIfArmed();
                case AFTER_ROTATE_IF_ARMED -> afterRotateIfArmed();
                case AFTER_ROTATE_AND_WAIT -> afterRotateAndWait();
                case NONE -> {
                }
            }
        }

        private void startRotateIfArmed() {
            childRoutine = ROUTINE_ROTATE_IF_ARMED;
            childWaitTimer = PANEL_ROUTINE4_WAITS[index];
            waitCallback = PanelWaitCallback.AFTER_ROTATE_IF_ARMED;
        }

        private void afterRotateIfArmed() {
            childWaitTimer = PANEL_ROUTINE8_WAITS[index];
            waitCallback = PanelWaitCallback.AFTER_ROTATE_AND_WAIT;
            if (index != 0) {
                childRoutine = ROUTINE_SYNC_TO_PARENT;
                PanelState parent = linkedParent();
                parent.bit1 = true;
                angle = parent.angle;
            } else {
                childRoutine = ROUTINE_ROTATE_AND_WAIT;
            }
        }

        private void afterRotateAndWait() {
            bit1 = false;
            angleDelta = -angleDelta;
            startRotateIfArmed();
            if (index == 5) {
                childRoutine = ROUTINE_OUTER_PAUSE;
                childWaitTimer = 0x3C;
                waitCallback = PanelWaitCallback.START_ROTATE_IF_ARMED;
                bit1 = true;
            }
            int[] table;
            if (secondRing) {
                table = angleDelta < 0 ? PANEL_ANGLE_B_REVERSE : PANEL_ANGLE_B;
            } else {
                table = angleDelta < 0 ? PANEL_ANGLE_A_REVERSE : PANEL_ANGLE_A;
            }
            angle = table[index];
        }

        private void moveCircular() {
            int shift = index == 5 ? 4 : 5;
            PanelAnchor anchor = resolvePanelAnchor();
            // MoveSprite_CircularSimple shifts the signed sine/cosine words as
            // 16.16 longs, then adds them to the parent's complete x_pos/y_pos
            // longs. Preserve each link's fractional remainder so it can carry
            // into later links in the chain.
            int xFixed = (anchor.x() << 16) | (anchor.xSub() & 0xFFFF);
            int yFixed = (anchor.y() << 16) | (anchor.ySub() & 0xFFFF);
            xFixed += TrigLookupTable.sinHex(angle) << (16 - shift);
            yFixed += TrigLookupTable.cosHex(angle) << (16 - shift);
            x = xFixed >> 16;
            y = yFixed >> 16;
            xSub = xFixed & 0xFFFF;
            ySub = yFixed & 0xFFFF;
        }

        private PanelAnchor resolvePanelAnchor() {
            if (index == 0) {
                return new PanelAnchor(getX(), getY(), motion.xSub, motion.ySub);
            }
            PanelState previous = linkedParent();
            return new PanelAnchor(previous.x, previous.y, previous.xSub, previous.ySub);
        }

        private PanelState linkedParent() {
            int previousIndex = (secondRing ? 1 + PANEL_FRAMES.length : 1) + index - 1;
            return panels.get(previousIndex);
        }

        private void detachAndPropagate() {
            if (detached || center) {
                return;
            }
            detached = true;
            detachedLifeTimer = DETACHED_PANEL_LIFETIME;
            xVel = PANEL_DETACH_X_VEL[index];
            yVel = PANEL_DETACH_Y_VEL[index];
            // ROM loc_728EA: bset #7,status on the $44-linked (next) panel.
            if (index + 1 < PANEL_FRAMES.length) {
                int nextIndex = (secondRing ? 1 + PANEL_FRAMES.length : 1) + index + 1;
                panels.get(nextIndex).markedForDetach = true;
            }
        }
    }

    private record PanelAnchor(int x, int y, int xSub, int ySub) {
    }
}
