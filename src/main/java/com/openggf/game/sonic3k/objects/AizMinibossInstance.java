package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.events.S3kAizEventWriteSupport;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardManager;
import com.openggf.graphics.GLCommand;
import com.openggf.level.Palette;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * AIZ miniboss object (0x91).
 *
 * ROM: Obj_AIZMiniboss (sonic3k.asm):
 * - loc_68A46 init
 * - loc_68A6E trigger
 * - loc_68A94 descend entry
 * - loc_68ACC/loc_68AFE attack cycle
 * - loc_68B34/loc_68B92 movement variants
 */
public class AizMinibossInstance extends AbstractBossInstance implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AizMinibossInstance.class.getName());
    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_WAIT_TRIGGER = 2;
    private static final int ROUTINE_WAIT = 4;
    private static final int ROUTINE_DESCEND = 6;
    private static final int ROUTINE_SWING = 8;
    private static final int ROUTINE_BREATH = 10;
    private static final int ROUTINE_HOLD = 12;
    private static final int ROUTINE_HORIZONTAL_SWING = 14;
    private static final int ROUTINE_DEFEATED = 16;

    private static final int HIT_COUNT = 6;
    private static final int COLLISION_SIZE = 0x0F;
    private static final int TRIGGER_X = 0x10E0;
    private static final int WAIT_AFTER_TRIGGER = 3 * 60;
    private static final int DESCEND_VEL = 0x100;
    private static final int DESCEND_TIME = 0xAF;
    private static final int SWING_PREP_TIME = 20;
    private static final int FLAME_PREP_TIME = 30;
    private static final int BREATH_CYCLE_COUNT = 8;       // ROM: move.b #8,$39(a0)
    private static final int VERTICAL_DRIFT_TIME = 0x5F;
    private static final int HOLD_TIME = 0x10;
    private static final int HORIZONTAL_CYCLE_COUNT = 4;    // ROM: move.b #4,$39(a0)
    private static final int INVULN_TIME = 0x20;
    private static final int FATAL_HIT_DEFEAT_DELAY = 13;
    // Obj_LevelResultsWait2 owns its literal #90 counter. Entries displaced
    // by the delayed boss handoff are already carried into
    // Obj_EndSignControlWait and must not be repeated by the results owner.
    private static final int RESULTS_WAIT_DURATION_ADJUSTMENT = 0;
    /**
     * Native object-pass DISPATCH ENTRIES, not frames.
     *
     * <p>The name is easy to misread as a frame delay, and it is not one. It is
     * threaded through {@code S3kBossDefeatSignpostFlow} into
     * {@code S3kSignpostInstance} and on into
     * {@code S3kResultsScreenObjectInstance}, where it is summed with other
     * dispatch-boundary adjustments before it means anything.
     *
     * <p>Clearing it against the ROM therefore requires modelling the
     * results-owner dispatch ordering across those owners, not reading a single
     * constant out of a routine — noted here because a timing-constant sweep on
     * 2026-08-21 opened it, found no single citation, and correctly left it
     * alone. An uncited number invites a one-line fix; an uncited number whose
     * name says something other than what it counts invites a wrong one.
     */
    private static final int RESULTS_POST_CONTROL_HANDOFF_DELAY_ENTRIES = 13;
    private static final int DEFEAT_WAIT_FADE_TIMER = 0x3F;
    private static final int FLAG_PARENT_BITS = 0x38;
    private static final int FLAG_PARENT_COUNTER = 0x39;
    private static final int PARENT_BIT_BARREL_ACTIVATE = 1 << 1;
    private static final int PARENT_BIT_ALT_VERTICAL = 1 << 2;
    private static final int PARENT_BIT_ALT_HORIZONTAL = 1 << 3;
    private static final int TRIGGER_X_KNUCKLES = 0x10C0;

    private static final int[] BREATH_FLAME_X_OFFSETS = {-0x64, -0x54, -0x44, -0x2C};
    private static final int[] BREATH_FLAME_Y_OFFSETS = {4, 4, 4, 3};

    /** ROM: loc_68F62 custom flash — palette line 2 color indices (byte offsets $0E,$14,$16,$1C). */
    private static final int[] CUSTOM_FLASH_INDICES = {7, 10, 11, 14};
    /** ROM: loc_68F62 dark color set (bit 0 of timer set). */
    private static final int[] CUSTOM_FLASH_DARK = {0x0644, 0x0240, 0x0020, 0x0644};
    /** ROM: loc_68F62 bright color set (bit 0 of timer clear). */
    private static final int[] CUSTOM_FLASH_BRIGHT = {0x0888, 0x0AAA, 0x0EEE, 0x0AAA};

    private final AizMinibossSwingMotion swingMotion = new AizMinibossSwingMotion();

    private int waitTimer = -1;
    private WaitCallback waitCallback = WaitCallback.NONE;
    /** Callback for when the current horizontal swing count expires. */
    private HorizontalCallback horizontalCallback = HorizontalCallback.NONE;
    private boolean defeatRenderComplete;
    private int retainedResultsOwnerSlot = -1;
    private int pendingDefeatTimer = -1;
    private int endSignControlWaitEntriesOwnedByBossBridge;
    private int defeatHandoffTimer = -1;
    private boolean levelEndUnlockStarted;
    private boolean levelEndSizeChangeStarted;
    private boolean levelEndControlPollDeferred;

    /** Stagger explosion controller for boss defeat (ROM: Child6_CreateBossExplosion subtype 0). */
    private S3kBossExplosionController defeatExplosionController;

    private enum WaitCallback {
        NONE,
        INITIAL_DELAY_COMPLETE,
        DESCEND_COMPLETE,
        SWING_PREP_COMPLETE,
        FLAME_PREP_COMPLETE,
        VERTICAL_ARC_PREP,
        HORIZONTAL_ARC_START,
        BREATH_CYCLE_COMPLETE
    }

    private enum HorizontalCallback {
        NONE,
        HORIZONTAL_ARC_PIVOT,
        HORIZONTAL_ARC_COMPLETE
    }

    public AizMinibossInstance(ObjectSpawn spawn) {
        super(spawn, "AIZMiniboss");
    }

    @Override
    public AizMinibossInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AizMinibossInstance(ctx.spawn());
    }

    @Override
    protected void initializeBossState() {
        state.routine = ROUTINE_INIT;
        state.hitCount = HIT_COUNT;
        waitTimer = -1;
        waitCallback = WaitCallback.NONE;
        horizontalCallback = HorizontalCallback.NONE;
        defeatRenderComplete = false;
        pendingDefeatTimer = -1;
        endSignControlWaitEntriesOwnedByBossBridge = 0;
        defeatHandoffTimer = -1;
        levelEndUnlockStarted = false;
        levelEndSizeChangeStarted = false;
        defeatExplosionController = null;
    }

    @Override
    protected int getInitialHitCount() {
        return HIT_COUNT;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        TouchResponseProfile base = TouchResponseProfile.fromProvider(this);
        return new TouchResponseProfile(
                base.categoryDecodeMode(),
                base.continuousCallbacks(),
                base.requiresRenderFlagForTouch(),
                base.multiRegionSource(),
                base.shieldDeflectCapability(),
                base.shieldReactionFlags(),
                base.enablesPostSpecialTouchAirborneSideVelocityPreservation(),
                base.attackBouncePolicy(),
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                base.stopAfterFirstOverlapPolicy());
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // Draw_And_Touch_Sprite publishes this parent after its movement
        // routine. Collision_response_list keeps the object-RAM pointer, so
        // the following player slot observes that live post-motion position
        // (sonic3k.asm:137222-137229,137262-137271,20656-20710).
        return true;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // Behavior changes are driven by the state machine and parent flags.
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false;
    }

    @Override
    protected int getInvulnerabilityDuration() {
        return INVULN_TIME;
    }

    @Override
    protected int getPaletteLineForFlash() {
        return -1; // Disable standard flash — custom flash via updateCustomFlash()
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        if (state.invulnerable || state.defeated) {
            return;
        }

        state.hitCount--;
        state.invulnerabilityTimer = INVULN_TIME;
        state.invulnerable = true;
        paletteFlasher.startFlash();
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
        onHitTaken(state.hitCount);

        if (state.hitCount <= 0) {
            state.hitCount = 0;
            // ROM loc_68F62 enters the defeat branch from the boss object's
            // own collision_property poll after the fatal touch response has
            // cleared collision_flags. The parent remains live for the
            // intervening frames, which preserves the AIZ arena-edge contact
            // seen in the trace before Wait_FadeToLevelMusic starts.
            pendingDefeatTimer = FATAL_HIT_DEFEAT_DELAY;
        }
    }

    private void triggerPendingDefeat() {
        pendingDefeatTimer = -1;
        state.defeated = true;
        services().gameState().addScore(1000);
        onDefeatStarted();
    }

    @Override
    protected void onDefeatStarted() {
        state.routine = ROUTINE_DEFEATED;
        state.xVel = 0;
        state.yVel = 0;
        waitTimer = -1;
        waitCallback = WaitCallback.NONE;
        horizontalCallback = HorizontalCallback.NONE;

        // Clear invulnerability immediately to stop palette flash
        state.invulnerable = false;
        state.invulnerabilityTimer = 0;
        loadBossPalette(); // Restore clean boss palette colors on line 1

        // ROM loc_68FE0: jmp (BossDefeated_StopTimer).l (sonic3k.asm:137858).
        stopLevelTimerOnBossDefeat();

        // ROM: loc_46ED4 creates Child6_CreateBossExplosion (sub_52850, subtype 0).
        // CreateBossExp00: timer=$20 (33 explosions), xRange=$20, yRange=$20.
        // sub_52850 plays sfx_Explode each time it spawns an explosion child (every 3 frames).
        defeatExplosionController = new S3kBossExplosionController(state.x, state.y, 0, services().rng());
        // ROM loc_68FB6 switches the boss object to Wait_FadeToLevelMusic
        // and creates Child6_CreateBossExplosion; the later loc_68C02
        // Obj_EndSignControl callback is timed by the boss object's $2E,
        // not by the explosion controller finishing (sonic3k.asm:137793-137806,
        // 179651-179668,137381-137393).
        defeatHandoffTimer = DEFEAT_WAIT_FADE_TIMER;

        services().fadeOutMusic();

        // Clean up all visible children — barrels, body, arm, and live napalm shots.
        for (var child : childComponents) {
            child.setDestroyed(true);
        }
        childComponents.clear();

        // Destroy all in-flight attack objects (barrel shots, flames, napalm projectiles).
        // ROM: barrel children enter defeat animation and stop spawning new attacks;
        // existing projectiles check parent state and become inert.
        destroyAttackObjects();
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        captureRetainedResultsOwnerSlot();
        maintainArenaCameraLock();
        if (pendingDefeatTimer >= 0) {
            if (pendingDefeatTimer > 1) {
                // The fatal-touch collision bridge remains the executing SST
                // owner for these entries, while the ROM's Obj_EndSignControl
                // $77 wait has already begun. Preserve that elapsed owner state
                // at the later Java handoff instead of shortening Obj_EndSign's
                // post-landing countdown.
                endSignControlWaitEntriesOwnedByBossBridge++;
            }
            pendingDefeatTimer--;
            if (pendingDefeatTimer < 0) {
                triggerPendingDefeat();
            }
            updateCustomFlash();
        }
        switch (state.routine) {
            case ROUTINE_INIT -> updateInit();
            case ROUTINE_WAIT_TRIGGER -> updateWaitTrigger();
            case ROUTINE_WAIT -> updateWaitOnly();
            case ROUTINE_DESCEND -> updateMoveAndWait();
            case ROUTINE_SWING -> updateSwingAndWait();
            case ROUTINE_BREATH -> updateBreathSwingCount();
            case ROUTINE_HOLD -> updateWaitOnly();
            case ROUTINE_HORIZONTAL_SWING -> updateHorizontalSwingCount();
            case ROUTINE_DEFEATED -> updateDefeated(vIntRunCount);
            default -> {
            }
        }
        updateCustomFlash();
    }

    private void captureRetainedResultsOwnerSlot() {
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }
        for (S3kResultsScreenObjectInstance results :
                objectManager.activeObjectsOfType(S3kResultsScreenObjectInstance.class)) {
            if (!results.isDestroyed() && results.getSlotIndex() >= 0) {
                retainedResultsOwnerSlot = results.getSlotIndex();
                return;
            }
        }
    }

    /**
     * ROM: loc_68F62 custom AIZ miniboss flash.
     * Writes 4 specific palette entries on palette line 1 (engine index 1),
     * alternating between dark and bright color sets every frame.
     */
    private void updateCustomFlash() {
        if (state.defeated) {
            return;
        }
        if (!state.invulnerable) {
            return;
        }
        var level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= 1) {
            return;
        }
        // ROM: bit 0 of $20(a0) determines which color set
        boolean useDark = (state.invulnerabilityTimer & 1) != 0;
        int[] colors = useDark ? CUSTOM_FLASH_DARK : CUSTOM_FLASH_BRIGHT;
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                level,
                services().graphicsManager(),
                S3kPaletteOwners.AIZ_MINIBOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                CUSTOM_FLASH_INDICES,
                colors);
    }

    private void updateInit() {
        S3kAizEventWriteSupport.setBossFlag(services(), true);
        services().gameState().setCurrentBossId(0x91);
        loadBossPalette();
        state.routine = ROUTINE_WAIT_TRIGGER;
    }

    private void updateWaitTrigger() {
        var camera = services().camera();
        PlayerCharacter character = currentPlayerCharacter();
        int triggerX = (character == PlayerCharacter.KNUCKLES) ? TRIGGER_X_KNUCKLES : TRIGGER_X;
        if (camera.getX() < triggerX) {
            return;
        }

        lockArenaCamera(triggerX);
        services().fadeOutMusic();

        state.routine = ROUTINE_WAIT;
        setWait(WAIT_AFTER_TRIGGER, WaitCallback.INITIAL_DELAY_COMPLETE);
    }

    private void maintainArenaCameraLock() {
        if (state.routine < ROUTINE_WAIT) {
            return;
        }
        int triggerX = currentPlayerCharacter() == PlayerCharacter.KNUCKLES
                ? TRIGGER_X_KNUCKLES
                : TRIGGER_X;
        if (levelEndUnlockStarted || shouldReleaseArenaForAct2TitleExit()) {
            levelEndUnlockStarted = true;
            updateLevelEndCameraUnlock(triggerX);
            return;
        }
        lockArenaCamera(triggerX);
    }

    private boolean shouldReleaseArenaForAct2TitleExit() {
        if (!state.defeated || !defeatRenderComplete) {
            return false;
        }
        // ROM Obj_LevelResults changes into the in-level title-card object for
        // Act 1 results. The retained owner reaches its LoadEnemyArt handoff
        // before the later End_of_level_flag completion edge; Obj_EndSignControl
        // may then run Change_Act2Sizes without waiting for the title overlay's
        // remaining display-release entries (sonic3k.asm:62220-62253,
        // 62276-62301, 180415-180419). Do not widen the arena during the child
        // slide-out phase before that owner handoff exists.
        Sonic3kTitleCardManager titleCardManager =
                services().gameService(Sonic3kTitleCardManager.class);
        return services().gameState().isEndOfLevelFlag()
                || (titleCardManager != null
                        && titleCardManager.hasPublishedInLevelRuntimeArtAdmission());
    }

    private void updateLevelEndCameraUnlock(int triggerX) {
        var camera = services().camera();
        var level = services().currentLevel();
        if (!levelEndSizeChangeStarted) {
            Sonic3kTitleCardManager titleCardManager =
                    services().gameService(Sonic3kTitleCardManager.class);
            if (!levelEndControlPollDeferred
                    && titleCardManager != null
                    && titleCardManager.retainedControlPollFollowsTitleCompletion()) {
                // The title owner published End_of_level_flag from a higher
                // SST slot, after this retained control slot had already run.
                levelEndControlPollDeferred = true;
                return;
            }
            levelEndSizeChangeStarted = true;
            // ROM Change_Act2Sizes copies Act 2 size words into the stored
            // camera-boundary memory and Camera_target_max_Y_pos, then creates
            // gradual level-size objects (sonic3k.asm:180575-180609). Keep
            // X/Y under the ROM gradual objects below; use the engine's target
            // max-Y path for the copied Camera_target_max_Y_pos word. This
            if (level != null) {
                camera.setMinY((short) level.getMinY());
                camera.setMaxYTarget((short) level.getMaxY());
            }
            ObjectManager objectManager = services().objectManager();
            // Obj_LevelResults and its in-level title owner occupy one native SST.
            // The engine carries that owner in a later consolidated slot. When that
            // slot lies after this retained control owner, it is the first eligible
            // representation of the hole Change_Act2Sizes sees; reuse it for the
            // first Child1 worker, then continue FindNextFreeObj from there.
            boolean reusedResultsOwnerSlot = retainedResultsOwnerSlot > getSlotIndex()
                    && objectManager != null
                    && objectManager.reserveDynamicSlot(retainedResultsOwnerSlot);
            if (reusedResultsOwnerSlot) {
                ObjectLifetimeOps.addDynamicAtReservedSlot(objectManager,
                        new AizAct2CameraResizeController(
                                AizAct2CameraResizeController.MAX_X),
                        retainedResultsOwnerSlot);
                spawnChildAfterSlot(retainedResultsOwnerSlot,
                        () -> new AizAct2CameraResizeController(
                                AizAct2CameraResizeController.MAX_Y));
            } else {
                spawnDynamicObject(new AizAct2CameraResizeController(
                        AizAct2CameraResizeController.MAX_X));
                spawnDynamicObject(new AizAct2CameraResizeController(
                        AizAct2CameraResizeController.MAX_Y));
            }
            // Obj_EndSignControlDoStart calls Change_Act2Sizes and then
            // Delete_Current_Sprite. The two workers above continue from
            // their own slots (sonic3k.asm:180415-180419,180575-180609).
            setDestroyed(true);
        }
        camera.setMinX((short) triggerX);
    }

    private void lockArenaCamera(int triggerX) {
        var camera = services().camera();
        // ROM loc_68556 (sonic3k.asm:136774-136780) writes both
        // Camera_min_X_pos and Camera_max_X_pos to d5 before the miniboss wait
        // and fight routines. Reasserting preserves that lock across the
        // engine's seamless AIZ1->AIZ2 reload bookkeeping.
        camera.setMinX((short) triggerX);
        camera.setMaxX((short) triggerX);
    }

    private void onInitialDelayComplete() {
        state.routine = ROUTINE_DESCEND;
        state.yVel = DESCEND_VEL;
        setWait(DESCEND_TIME, WaitCallback.DESCEND_COMPLETE);

        spawnBossComponent(() -> new AizMinibossBodyChild(this));
        spawnBossComponent(() -> new AizMinibossArmChild(this));
        for (int i = 0; i < 3; i++) {
            int barrelIndex = i;
            spawnBossComponent(() -> new AizMinibossFlameBarrelChild(this, barrelIndex, false));
        }

        services().playMusic(Sonic3kMusic.MINIBOSS.id);
    }

    private void onDescendComplete() {
        state.routine = ROUTINE_SWING;
        setCustomFlag(FLAG_PARENT_COUNTER, 3);
        state.yVel = 0;
        swingMotion.setup1(state);
        setWait(SWING_PREP_TIME, WaitCallback.SWING_PREP_COMPLETE);
    }

    private void onSwingPrepComplete() {
        // ROM AIZMiniboss_SetFlameDelay (sonic3k.asm:137298-137308) sets the
        // barrel activation bit at the start of the 30-frame flame delay, and
        // only for character_id == 2 (Knuckles).  The existing three barrel
        // children consume this shared bit and each applies its own native
        // start delay before spawning its flare/FallingShot pair.
        if (currentPlayerCharacter() == PlayerCharacter.KNUCKLES) {
            setCustomFlag(FLAG_PARENT_BITS, getCustomFlag(FLAG_PARENT_BITS) | PARENT_BIT_BARREL_ACTIVATE);
        }
        setWait(FLAME_PREP_TIME, WaitCallback.FLAME_PREP_COMPLETE);
    }

    private void onFlamePrepComplete() {
        // ROM: loc_68AFE — enter BREATH routine ($A) with Swing_UpAndDown_Count
        state.routine = ROUTINE_BREATH;
        swingMotion.setCycleCounter(BREATH_CYCLE_COUNT);
        services().playSfx(Sonic3kSfx.FLAMETHROWER_QUIET.id);
        spawnBreathFlames();
        // No frame timer — phase progresses via swing half-cycle counting
        waitTimer = -1;
        waitCallback = WaitCallback.NONE;
    }

    /**
     * ROM: loc_68B1C — Swing_UpAndDown_Count, beq continue, tst d1 / bmi transition.
     * Counts half-cycles of the Y oscillation. When the count expires (at a peak)
     * AND y_vel < 0 (top of swing), transitions to the vertical drift phase.
     * Once the counter goes negative, updateAndCount returns EXPIRED on every
     * subsequent peak too, so no flag is needed.
     */
    private void updateBreathSwingCount() {
        var result = swingMotion.updateAndCount(state);
        if (result == AizMinibossSwingMotion.CountResult.EXPIRED && state.yVel < 0) {
            onBreathCycleComplete();
            return;
        }
        state.applyVelocity();
    }

    /**
     * ROM: loc_68B34 — after breath swing count expires at top of oscillation.
     * Toggles vertical direction: first pass moves up → horizontal arc,
     * second pass moves down → restart attack cycle.
     */
    private void onBreathCycleComplete() {
        // ROM: loc_68B34 — routine=6, wait=$5F, toggle bit 2 of $38
        state.routine = ROUTINE_DESCEND;
        int bits = getCustomFlag(FLAG_PARENT_BITS) ^ PARENT_BIT_ALT_VERTICAL;
        setCustomFlag(FLAG_PARENT_BITS, bits);

        if ((bits & PARENT_BIT_ALT_VERTICAL) != 0) {
            // First pass: move up, then horizontal arc
            state.yVel = -DESCEND_VEL;
            setWait(VERTICAL_DRIFT_TIME, WaitCallback.VERTICAL_ARC_PREP);
        } else {
            // Second pass: move down, restart attack cycle at loc_68ACC
            state.yVel = DESCEND_VEL;
            setWait(VERTICAL_DRIFT_TIME, WaitCallback.DESCEND_COMPLETE);
        }
    }

    /** ROM: loc_68B74 → loc_68B7C — hold at top, then start horizontal swing. */
    private void onVerticalArcPrep() {
        state.routine = ROUTINE_HOLD;
        state.xVel = 0;
        state.yVel = 0;
        setWait(HOLD_TIME, WaitCallback.HORIZONTAL_ARC_START);
    }

    /**
     * ROM: loc_68B92 — enter HORIZONTAL_SWING routine ($E) with Swing_UpAndDown_Count.
     * Counter = 4, x_vel toggles direction via bit 3 of $38, Swing_Setup1 resets Y oscillation.
     */
    private void onHorizontalArcStart() {
        state.routine = ROUTINE_HORIZONTAL_SWING;
        swingMotion.setCycleCounter(HORIZONTAL_CYCLE_COUNT);

        // ROM: bchg #3,$38(a0); bne.s skip_neg; neg.w d0
        int bits = getCustomFlag(FLAG_PARENT_BITS) ^ PARENT_BIT_ALT_HORIZONTAL;
        setCustomFlag(FLAG_PARENT_BITS, bits);

        // ROM: d0=$100, if bit was 0 (now 1) → neg → x_vel=-$100 (left)
        //      if bit was 1 (now 0) → no neg → x_vel=$100 (right)
        int xVel = ((bits & PARENT_BIT_ALT_HORIZONTAL) != 0) ? -DESCEND_VEL : DESCEND_VEL;
        state.xVel = xVel;
        swingMotion.setup1(state);
        // No frame timer — phase progresses via swing half-cycle counting
        waitTimer = -1;
        waitCallback = WaitCallback.NONE;
        horizontalCallback = HorizontalCallback.HORIZONTAL_ARC_PIVOT;
    }

    /**
     * ROM: loc_68BBC — Swing_UpAndDown_Count, bne call-callback, else MoveSprite2+Draw.
     * When the half-cycle counter expires, calls the current callback (pivot or complete).
     */
    private void updateHorizontalSwingCount() {
        var result = swingMotion.updateAndCount(state);
        if (result == AizMinibossSwingMotion.CountResult.EXPIRED) {
            runHorizontalCallback();
            return;
        }
        state.applyVelocity();
    }

    /**
     * ROM: loc_68BDC — first horizontal count expired.
     * Reset counter for second pass, flip render flags (facing direction),
     * apply one frame of movement, then continue horizontal swing.
     */
    private void onHorizontalArcPivot() {
        swingMotion.setCycleCounter(HORIZONTAL_CYCLE_COUNT);
        state.renderFlags ^= 1;
        state.applyVelocity(); // ROM: jmp (MoveSprite2).l
        horizontalCallback = HorizontalCallback.HORIZONTAL_ARC_COMPLETE;
    }

    /**
     * ROM: loc_68BF6 → loc_68B7C — second horizontal count expired.
     * Hold briefly, then return to the breath/vertical cycle via loc_68B34.
     */
    private void onHorizontalArcComplete() {
        state.routine = ROUTINE_HOLD;
        state.xVel = 0;
        state.yVel = 0;
        setWait(HOLD_TIME, WaitCallback.BREATH_CYCLE_COMPLETE);
    }

    private void updateWaitOnly() {
        tickWait();
    }

    /**
     * ROM: loc_685FC — Swing_UpAndDown + MoveWaitTouch.
     * Used for ROUTINE_SWING (routine 8): plain swing oscillation with frame timer.
     */
    private void updateSwingAndWait() {
        swingMotion.update(state);
        state.applyVelocity();
        tickWait();
    }

    /** ROM: loc_68ABA — MoveSprite2 + Obj_Wait (no swing). */
    private void updateMoveAndWait() {
        state.applyVelocity();
        tickWait();
    }

    private void updateDefeated(int vIntRunCount) {
        // Tick the stagger explosion controller each frame.
        // ROM: Obj_CreateBossExplosion (sub_52850) spawns one explosion every 3 frames
        // at random offsets within +/-$20 pixels. Total: 33 explosions over ~102 frames.
        if (defeatExplosionController != null && !defeatExplosionController.isFinished()) {
            defeatExplosionController.tick();
            for (var pending : defeatExplosionController.drainPendingExplosions()) {
                if (pending.playSfx()) {
                    services().playSfx(Sonic3kSfx.EXPLODE.id);
                }
                spawnFreeChild(() -> new S3kBossExplosionChild(pending.x(), pending.y()));
            }
        }

        if (defeatHandoffTimer >= 0) {
            defeatHandoffTimer--;
            if (defeatHandoffTimer < 0) {
                startDefeatHandoff();
            }
        }
    }

    private void startDefeatHandoff() {
        // ROM: Wait_FadeToLevelMusic -> Obj_Song_Fade_ToLevelMusic ->
        // loc_68C02 -> Obj_EndSignControl.
        // The fire transition swaps tileset/palette to AIZ2's look, but the zone is still
        // technically AIZ1 and the level music is AIZ1.
        if (!defeatRenderComplete) {
            defeatRenderComplete = true;
            spawnDynamicObject(new SongFadeTransitionInstance(120, Sonic3kMusic.AIZ1.id));

            // apparentAct = 0: AIZ miniboss ends act 1 (ROM's Apparent_act is 0 here,
            // even though the engine reloaded act 2 resources for the fire terrain swap)
            // Use spawnChild() so CONSTRUCTION_CONTEXT is set — the constructor calls services()
            spawnChild(() -> new S3kBossDefeatSignpostFlow(
                    state.x, 0,
                    S3kBossDefeatSignpostFlow.CleanupAction.RESTORE_AIZ_FIRE_PALETTE,
                    endSignControlWaitEntriesOwnedByBossBridge,
                    0,
                    RESULTS_WAIT_DURATION_ADJUSTMENT,
                    RESULTS_POST_CONTROL_HANDOFF_DELAY_ENTRIES,
                    true).withNativeControlSlot(getSlotIndex()));
        }
    }

    private void setWait(int frames, WaitCallback callback) {
        waitTimer = frames;
        waitCallback = callback;
    }

    private void tickWait() {
        if (waitTimer < 0) {
            return;
        }
        waitTimer--;
        if (waitTimer >= 0) {
            return;
        }
        runWaitCallback();
    }

    private void runWaitCallback() {
        if (waitCallback == WaitCallback.NONE) {
            return;
        }
        WaitCallback callback = waitCallback;
        waitCallback = WaitCallback.NONE;
        switch (callback) {
            case INITIAL_DELAY_COMPLETE -> onInitialDelayComplete();
            case DESCEND_COMPLETE -> onDescendComplete();
            case SWING_PREP_COMPLETE -> onSwingPrepComplete();
            case FLAME_PREP_COMPLETE -> onFlamePrepComplete();
            case VERTICAL_ARC_PREP -> onVerticalArcPrep();
            case HORIZONTAL_ARC_START -> onHorizontalArcStart();
            case BREATH_CYCLE_COMPLETE -> onBreathCycleComplete();
            case NONE -> {
            }
        }
    }

    private void runHorizontalCallback() {
        if (horizontalCallback == HorizontalCallback.NONE) {
            return;
        }
        HorizontalCallback callback = horizontalCallback;
        horizontalCallback = HorizontalCallback.NONE;
        switch (callback) {
            case HORIZONTAL_ARC_PIVOT -> onHorizontalArcPivot();
            case HORIZONTAL_ARC_COMPLETE -> onHorizontalArcComplete();
            case NONE -> {
            }
        }
    }

    private void spawnBossComponent(java.util.function.Supplier<? extends AbstractBossChild> factory) {
        AbstractBossChild child = spawnChild(factory);
        childComponents.add(child);
    }

    private void spawnBreathFlames() {
        for (int i = 0; i < BREATH_FLAME_X_OFFSETS.length; i++) {
            int flameIndex = i;
            // CreateChild1_Normal sets subtype = d2 (increments by 2): 0, 2, 4, 6
            spawnChild(() -> new AizMinibossFlameChild(
                    this,
                    BREATH_FLAME_X_OFFSETS[flameIndex],
                    BREATH_FLAME_Y_OFFSETS[flameIndex],
                    flameIndex * 2));
        }
    }

    /**
     * Destroy all in-flight attack objects spawned by this boss.
     * ROM: barrel children enter defeat animation; existing shots/flames check parent
     * state and stop. We achieve the same by scanning active objects.
     */
    private void destroyAttackObjects() {
        var objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }
        for (var obj : objectManager.getActiveObjects()) {
            if (obj instanceof AizMinibossBarrelShotChild attack) {
                attack.setDestroyed(true);
            } else if (obj instanceof AizMinibossFlameChild flame) {
                flame.setDestroyed(true);
            } else if (obj instanceof AizMinibossImpactFlameChild impact) {
                impact.setDestroyed(true);
            } else if (obj instanceof AizMinibossNapalmProjectile napalm) {
                napalm.setDestroyed(true);
            } else if (obj instanceof AizMinibossBarrelShotFlareChild flare) {
                flare.setDestroyed(true);
            }
        }
    }

    private void loadBossPalette() {
        try {
            byte[] line = services().rom().readBytes(
                    Sonic3kConstants.PAL_AIZ_MINIBOSS_ADDR, 32);
            S3kPaletteWriteSupport.applyLine(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    S3kPaletteOwners.AIZ_MINIBOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    1,
                    line);
        } catch (Exception e) {
            LOG.fine(() -> "AizMinibossInstance.loadBossPalette: " + e.getMessage());
        }
    }

    private PlayerCharacter currentPlayerCharacter() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || state.routine < ROUTINE_DESCEND || defeatRenderComplete) {
            return;
        }
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_MINIBOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        boolean hFlip = (state.renderFlags & 1) != 0;
        renderer.drawFrameIndex(0, state.x, state.y, hFlip, false);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_AIZMiniboss,1,1) — priority bit = 1
        return true;
    }

    @Override
    public String traceDebugDetails() {
        var camera = services().camera();
        var gameState = services().gameState();
        return String.format("r=%02X hp=%d inv=%s it=%d wait=%d pend=%d def=%s done=%s exp=%s boss=%04X end=%s flag=%s cam=%04X min=%04X max=%04X",
                state.routine & 0xFF,
                state.hitCount,
                state.invulnerable,
                state.invulnerabilityTimer,
                waitTimer,
                pendingDefeatTimer,
                state.defeated,
                defeatRenderComplete,
                defeatExplosionController == null || defeatExplosionController.isFinished(),
                gameState.getCurrentBossId(),
                gameState.isEndOfLevelActive(),
                gameState.isEndOfLevelFlag(),
                camera.getX() & 0xFFFF,
                camera.getMinX() & 0xFFFF,
                camera.getMaxX() & 0xFFFF);
    }

    @Override
    public boolean isPersistent() {
        // Dynamic_resize spawns the boss offscreen, then Obj_AIZMiniboss waits
        // for the later camera trigger before it appears.
        return true;
    }

    /**
     * The AIZ miniboss is spawned in AIZ2 and fought there; it is never legitimately carried
     * across a seamless act reload. It is {@code isPersistent()=true} and, because it does not use
     * the defeat sequencer, keeps running {@code maintainArenaCameraLock()} every frame even after
     * defeat — releasing the arena and self-destructing only when the end-of-level camera widening
     * completes. If it were ever carried into the next act (e.g. while still defeated-but-alive),
     * the default no-op carry hook would leave it un-offset and it would become an invisible,
     * camera-locking ghost — the same failure mode fixed for the AIZ1 cutscene miniboss.
     *
     * <p>Guard against that: remove this object and its tracked children when the act transition
     * carries them, matching the ROM where the boss object's RAM slot does not survive a reload.
     */
    @Override
    public void onCarriedAcrossSeamlessTransition(int offsetX, int offsetY) {
        for (var child : childComponents) {
            ObjectLifetimeOps.destroyBossChildLatched(child);
        }
        childComponents.clear();
        ObjectLifetimeOps.destroyLatched(this);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: ObjDat_AIZMiniboss priority $0200 → $200/$80 = bucket 4
        return 4;
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic3kSfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic3kSfx.EXPLODE.id;
    }
}
