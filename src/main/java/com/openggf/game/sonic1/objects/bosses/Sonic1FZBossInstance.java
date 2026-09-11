package com.openggf.game.sonic1.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.GameRng;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.level.objects.boss.BossExplosionObjectInstance;
import com.openggf.game.sonic1.scroll.Sonic1ZoneConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x85 — Final Zone Boss (Eggman in machine with crushing cylinders and plasma launcher).
 * ROM: _incObj/85 Boss - Final.asm
 *
 * The FZ boss is a multi-component system: Eggman sits inside a control room
 * while 4 crushing cylinders attack Sonic and a plasma launcher fires energy balls.
 * The boss uses custom hit detection — Sonic must be rolling when pushed into
 * the boss body by a cylinder to deal damage (8 hits total, no rings available).
 *
 * State machine (objoff_34 sub-states, within routine 2):
 *   0: WAIT           — Wait for PLC buffer empty + camera at boss_fz_x
 *   2: CYLINDER_ATTACK — Select 2 cylinders, activate, handle solid collision + damage
 *   4: PLASMA_PHASE   — Activate plasma launcher, wait for completion, loop to 2
 *   6: DEFEAT_FALL    — Gravity descent after defeat, transition at Y threshold
 *   8: RUNNING_ESCAPE — Complex X velocity chase of Sonic, Y bounce
 *  10: FINAL_ASCENT   — High gravity arc to landing position
 *  12: SHIP_TRANSFORM — Switch to Map_Eggman, ascend to escape ship
 *  14: FINAL_FLIGHT   — Escape flight, player control lock, ending trigger
 *
 * Sub-objects (routines 4-12) in the ROM are visual overlays that track the parent.
 * In our engine these are handled as rendering overlays within this class.
 */
public class Sonic1FZBossInstance extends AbstractBossInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {
    private static final SpriteAnimationSet SEGG_ANIMATIONS = Sonic1BossAnimations.getSEggAnimations();

    // State machine constants (objoff_34 values in the ROM)
    private static final int STATE_WAIT = 0;
    private static final int STATE_CYLINDER_ATTACK = 2;
    private static final int STATE_PLASMA_PHASE = 4;
    private static final int STATE_DEFEAT_FALL = 6;
    private static final int STATE_RUNNING_ESCAPE = 8;
    private static final int STATE_FINAL_ASCENT = 10;
    private static final int STATE_SHIP_TRANSFORM = 12;
    private static final int STATE_FINAL_FLIGHT = 14;

    // Position constants from Constants.asm
    private static final int BOSS_FZ_X = Sonic1Constants.BOSS_FZ_X;     // 0x2450
    private static final int BOSS_FZ_Y = Sonic1Constants.BOSS_FZ_Y;     // 0x510
    private static final int BOSS_FZ_END = Sonic1Constants.BOSS_FZ_END; // 0x2700
    private static final int ENDING_ACT_FLOWERS = 0;
    private static final int ENDING_ACT_NO_EMERALDS = 1;
    private static final int ESCAPE_COLLISION_FLAGS = 0xC0 | 0x0F;

    // Cylinder lookup table: word_19FD6 — maps random index to cylinder pair
    // Random & $C gives 0, 4, 8, or 12 -> lookup gives cylinder pair indices
    private static final int[] CYLINDER_PAIR_TABLE = {0, 2, 2, 4, 4, 6, 6, 0};

    // Solid collision params for combat phase: d1=$2B, d2=$14, d3=$14
    private static final SolidObjectParams COMBAT_SOLID_PARAMS =
            SolidObjectParams.of(0x2B, 0x14, 0x14);

    // Solid collision params for escape phases: d1=$1B, d2=$70, d3=$71
    private static final SolidObjectParams ESCAPE_SOLID_PARAMS =
            SolidObjectParams.of(0x1B, 0x70, 0x71);
    private static final int COMBAT_TOP_LANDING_HALF_WIDTH = 0x20;

    // BossFinal_ObjData2 row 0: the parent slot's width byte, #64/2 = 32. On
    // REV01 BossFinal_Main stores it into obActWid; REV00 stores obWidth
    // instead (85,84,86 Boss - FZ Main, Cylinders, and Plasma Balls.asm:56-58,
    // 96-101). The engine targets REV01, so the byte is live.
    private static final int COMBAT_ACT_WIDTH = 0x20;

    // BossFinal_Eggman_Fall re-writes it to #96/2 = 48 while Eggman falls, then
    // back to #64/2 = 32 at the landing snap that advances to Eggman_Run
    // (:355-371). Same Revision=0 obWidth split as above.
    private static final int DEFEAT_FALL_ACT_WIDTH = 0x30;

    // Damage cooldown (objoff_35 in ROM)
    private int damageCooldown;

    // Cylinder management
    private FZCylinder[] cylinders;
    private FZPlasmaLauncher plasmaLauncher;
    private boolean childComponentsSpawned;

    // Camera X as of the previous frame's scroll. The FZ boss runs in ExecuteObjects
    // (before DeformLayers/ScrollHoriz), so its wait-exit camera read sees the
    // previous frame's camera; the engine's live camera.getX() is one frame ahead.
    // Seeded to 0 so the wait never advances on the spawn frame before a real read.
    private int previousFrameCamX;

    // objoff_30: cylinder activation state (-1 = ready for new pair)
    private int cylinderState;

    // objoff_32: active cylinder counter (decremented by each cylinder when done)
    private int activeCylinderCount;

    // Current SEgg animation
    private int seggAnim;
    private int seggAnimPrev;
    private int seggAnimScriptFrame;
    private int seggAnimTimeFrame;
    private int seggFrame;

    // Whether we've transitioned to Map_Eggman (escape ship form)
    private boolean escapingInShip;

    // Eggman face/flame overlays (sub-objects in ROM, rendered inline here)
    // These track state for the 6 sub-objects defined in BossFinal_ObjData
    private int legsFrame;
    private int legsTimer;
    private boolean showDamaged;

    // obColProp flag for escape phase hittability
    private boolean escapeHittable;
    private int escapeHitTimer;
    private int escapeCollisionFlags;
    private boolean endingTransitionRequested;
    private boolean suppressCurrentRollAttack;
    private boolean suppressedRollLeftInitialMapping;

    public Sonic1FZBossInstance(ObjectSpawn spawn) {
        super(spawn, "FZ Boss");
    }

    @Override
    public Sonic1FZBossInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic1FZBossInstance(ctx.spawn());
    }

    @Override
    protected void initializeBossState() {
        // ROM: BossFinal_Main sets objoff_34 = 0 (state WAIT)
        state.routineSecondary = STATE_WAIT;
        state.xVel = 0;
        state.yVel = 0;

        // ROM: Initial position from BossFinal_ObjData — boss_fz_x+$160, boss_fz_y+$80
        state.x = BOSS_FZ_X + 0x160;
        state.y = BOSS_FZ_Y + 0x80;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;

        // ROM: move.w #-1,objoff_30(a0) — ready for first cylinder pair
        cylinderState = -1;
        activeCylinderCount = 0;

        damageCooldown = 0;
        seggAnim = Sonic1BossAnimations.ANIM_SEGG_STAND;
        seggAnimPrev = -1;
        seggAnimScriptFrame = 0;
        seggAnimTimeFrame = 0;
        seggFrame = 0;
        escapingInShip = false;
        legsFrame = 0;
        legsTimer = 0;
        showDamaged = false;
        escapeHittable = false;
        escapeHitTimer = 0;
        escapeCollisionFlags = 0;
        endingTransitionRequested = false;

        // Initialize arrays before spawning (field initializers haven't run yet
        // because AbstractBossInstance constructor calls initializeBossState())
        cylinders = new FZCylinder[4];
        childComponentsSpawned = false;
    }

    private void spawnChildComponents() {
        if (services().objectManager() == null) return;

        // Spawn 4 cylinders with subtypes 0, 2, 4, 6 (ROM: loc_19E3E)
        for (int i = 0; i < 4; i++) {
            final int subtype = i * 2;
            FZCylinder cylinder = spawnFreeChild(() -> new FZCylinder(this, subtype));
            cylinders[i] = cylinder;
            childComponents.add(cylinder);
        }

        // Spawn plasma launcher (ROM: loc_19E20)
        plasmaLauncher = spawnFreeChild(() -> new FZPlasmaLauncher(this));
        childComponents.add(plasmaLauncher);
    }

    private void ensureChildComponentsSpawned() {
        if (childComponentsSpawned) return;
        if (services().objectManager() == null) return;
        spawnChildComponents();
        childComponentsSpawned = true;
    }

    void adoptCylinderForRewind(FZCylinder cylinder) {
        if (cylinder == null) {
            return;
        }
        if (cylinders == null || cylinders.length != 4) {
            cylinders = new FZCylinder[4];
        }
        int subtype = cylinder.subtypeForRewind();
        int index = subtype >> 1;
        if (index >= 0 && index < cylinders.length) {
            FZCylinder previous = cylinders[index];
            if (previous != null && previous != cylinder) {
                childComponents.remove(previous);
            }
            cylinders[index] = cylinder;
        }
        childComponents.removeIf(child ->
                child instanceof FZCylinder existing
                        && existing != cylinder
                        && existing.subtypeForRewind() == subtype);
        if (!childComponents.contains(cylinder)) {
            childComponents.add(cylinder);
        }
        childComponentsSpawned = true;
    }

    void adoptPlasmaLauncherForRewind(FZPlasmaLauncher launcher) {
        if (launcher == null) {
            return;
        }
        if (plasmaLauncher != null && plasmaLauncher != launcher) {
            childComponents.remove(plasmaLauncher);
        }
        childComponents.removeIf(child ->
                child instanceof FZPlasmaLauncher && child != launcher);
        plasmaLauncher = launcher;
        if (!childComponents.contains(launcher)) {
            childComponents.add(launcher);
        }
        childComponentsSpawned = true;
    }

    @Override
    protected int getInitialHitCount() {
        return 8; // ROM: move.b #8,obColProp(a0)
    }

    @Override
    protected int getCollisionSizeIndex() {
        // FZ boss does NOT use standard touch response collision
        return 0;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false; // FZ boss has custom defeat logic in states 6-14
    }

    @Override
    public boolean isPersistent() {
        // ROM DLE_FZ_Boss spawns Obj85 as soon as camera reaches boss_fz_x-$150,
        // then BossFinal_Main initializes the whole boss group before any standard
        // out_of_range tail call (_inc/DynamicLevelEvents.asm:770-779,
        // _incObj/85 Boss - Final.asm:41-79). The parent starts at x=$25B0,
        // outside the generic S1 window, but its cylinder children must exist
        // immediately to run SolidObject at x=$24D0/$2550.
        return true;
    }

    @Override
    public int getCollisionFlags() {
        // ROM: FZ boss never uses standard touch response (obColType is never set
        // during combat). Damage is handled via SolidObject push + roll check.
        // During escape (state 14), obColType=$F is set for hittability.
        if (state.routineSecondary == STATE_FINAL_FLIGHT && escapeHittable) {
            return escapeCollisionFlags;
        }
        return 0;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: In state 14, a successful player hit clears obColType, starting objoff_30 timer.
        if (state.routineSecondary != STATE_FINAL_FLIGHT || !escapeHittable || escapeCollisionFlags == 0) {
            return;
        }
        escapeCollisionFlags = 0;
        escapeHitTimer = 0x1E;
        escapeHittable = false;
        showDamaged = true;
        services().playSfx(Sonic1Sfx.HIT_BOSS.id);
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // Only used during escape phase (state 14) hit
    }

    @Override
    protected void onDefeatStarted() {
        // Not used — FZ boss handles defeat inline
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Spawn children after the parent is already inserted in ObjectManager.
        // This preserves parent-before-child collision order for FZ boss solids.
        ensureChildComponentsSpawned();

        switch (state.routineSecondary) {
            case STATE_WAIT -> updateWait();
            case STATE_CYLINDER_ATTACK -> updateCylinderAttack(player);
            case STATE_PLASMA_PHASE -> updatePlasmaPhase(vIntRunCount);
            case STATE_DEFEAT_FALL -> updateDefeatFall();
            case STATE_RUNNING_ESCAPE -> updateRunningEscape(player);
            case STATE_FINAL_ASCENT -> updateFinalAscent();
            case STATE_SHIP_TRANSFORM -> updateShipTransform();
            case STATE_FINAL_FLIGHT -> updateFinalFlight(player, vIntRunCount);
        }
    }

    // === State 0: WAIT (loc_19E90 / BossFinal_Eggman_Wait) ===
    // Wait for camera to reach boss_fz_x. ROM advances out of wait only when the
    // game-owned PLC FIFO is empty and the camera has reached boss_fz_x.
    private void updateWait() {
        // ROM BossFinal_Eggman_Wait reads (v_screenposx).w from inside ExecuteObjects,
        // which runs BEFORE DeformLayers/ScrollHoriz in the level main loop
        // (docs/s1disasm/sonic.asm Level loop: ExecuteObjects then DeformLayers;
        // docs/s1disasm/_inc/DeformLayers (REV01).asm:16-18). So the boss sees the
        // camera as left by the PREVIOUS frame's scroll.
        //
        // The FZ boss is a dynamic object executed during object execution
        // (LevelFrameStep step 2/3), which now runs BEFORE the camera scroll
        // (step 4a, camera.updatePosition()) — matching ROM ExecuteObjects running
        // before DeformLayers. So camera.getX() read here is already the
        // previous-frame post-scroll camera (this frame's scroll has not run yet),
        // exactly what ROM's ExecuteObjects-time read sees. Read it directly.
        int camX = services().camera().getX() & 0xFFFF;

        Sonic1PlcService plcService = services().gameService(Sonic1PlcService.class);
        boolean plcBusy = plcService != null && plcService.isBusy();

        if (!plcBusy && camX >= BOSS_FZ_X) {
            state.routineSecondary = STATE_CYLINDER_ATTACK;
        }

        // ROM: loc_19EA2 — addq.l #1,(v_random).w runs EVERY frame the boss is in
        // the wait sub-state (the fall-through tail of BossFinal_Eggman_Wait,
        // reached whether or not the wait advances this frame). This deterministic
        // per-frame advance of v_random through the boss-intro wait is what places
        // the seed for the first BossFinal_Eggman_Crush RandomNumber draw
        // (selectCylinderPair). _incObj/85,84,86 Boss - FZ Main, Cylinders, and
        // Plasma Balls.asm:131-133. With the previous-frame camera read above, the
        // wait spans the ROM-correct number of frames, so no separate seed
        // compensation is needed.
        GameRng rng = services().rng();
        rng.setSeed(rng.getSeed() + 1);
    }

    // === State 2: CYLINDER_ATTACK (loc_19EA8) ===
    // Select and activate cylinder pairs, handle solid collision and damage
    private void updateCylinderAttack(AbstractPlayableSprite player) {
        if (player != null
                && !suppressCurrentRollAttack
                && player.getPushingAtFrameStart()
                && player.getMappingFrame() == 0x2E
                && player.getGSpeed() != 0
                && (player.getCentreX() & 0xFFFF) < (state.x & 0xFFFF)
                && Math.abs(player.getCentreX() - state.x) <= COMBAT_SOLID_PARAMS.halfWidth() + 0x10) {
            suppressCurrentRollAttack = true;
            suppressedRollLeftInitialMapping = false;
        }
        if (player == null || !player.getAir() || !player.getRolling()) {
            suppressCurrentRollAttack = false;
            suppressedRollLeftInitialMapping = false;
        } else if (suppressCurrentRollAttack) {
            if (player.getMappingFrame() != 0x2E) {
                suppressedRollLeftInitialMapping = true;
            } else if (suppressedRollLeftInitialMapping && player.getGSpeed() == 0) {
                suppressCurrentRollAttack = false;
                suppressedRollLeftInitialMapping = false;
            }
        }
        if (cylinderState < 0) {
            // ROM: clr.w objoff_30 then select new pair
            cylinderState = 0;
            selectCylinderPair();
        }

        // ROM: tst.w objoff_32 — check if cylinders signaled completion
        if (activeCylinderCount < 0) {
            // Cylinders done, check hit count
            if (state.hitCount <= 0) {
                // ROM: loc_19FBC — defeated
                services().gameState().addScore(1000);
                // ROM: v_bossstatus = 0 cleared on defeat (matches other S1 bosses)
                services().gameState().setCurrentBossId(0);
                state.routineSecondary = STATE_DEFEAT_FALL;
                // ROM loc_19FBC: move.w #boss_fz_x+$170,obX / move.w #boss_fz_y+$2C,obY
                // are WORD stores to the high word of the 16.16 long, preserving the
                // accumulated subpixel low word.
                clampXPreservingSubpixel(BOSS_FZ_X + 0x170);
                clampYPreservingSubpixel(BOSS_FZ_Y + 0x2C);
                state.defeated = true;
                return;
            }
            // ROM: addq.b #2,objoff_34 — advance to plasma phase
            state.routineSecondary = STATE_PLASMA_PHASE;
            suppressCurrentRollAttack = false;
            suppressedRollLeftInitialMapping = false;
            cylinderState = -1;
            activeCylinderCount = 0;
            return;
        }

        // Handle solid collision and damage check (ROM: loc_19F10 onward)
        handleCombatCollision(player);

        // Animate SEgg (ROM: AnimateSprite with Ani_SEgg)
        updateCombatAnimation();
    }

    private void selectCylinderPair() {
        // ROM: loc_19EA8 — RandomNumber, andi #$C
        int random = services().rng().nextRaw();
        int index1 = (random & 0xC) >> 1;
        int index2 = index1 + 1;
        if (random < 0) {
            int swap = index1;
            index1 = index2;
            index2 = swap;
        }

        // ROM: word_19FD6 lookup
        int cylIndex1 = CYLINDER_PAIR_TABLE[index1];
        int cylIndex2 = CYLINDER_PAIR_TABLE[index2];

        // Activate the two selected cylinders
        int cyl1 = cylIndex1 >> 1;
        int cyl2 = cylIndex2 >> 1;
        if (cyl1 < 4 && cylinders[cyl1] != null) {
            cylinders[cyl1].activate(-1); // Descending
        }
        if (cyl2 < 4 && cylinders[cyl2] != null) {
            cylinders[cyl2].activate(1);  // Ascending
        }

        // ROM: move.w #1,objoff_32(a0)
        activeCylinderCount = 1;
        damageCooldown = 0;

        // ROM: sfx_Rumbling
        services().playSfx(Sonic1Sfx.RUMBLING.id);
    }

    private void handleCombatCollision(AbstractPlayableSprite player) {
        // The actual SolidObject collision is handled by the engine's SolidContacts
        // system via our SolidObjectProvider implementation. The SolidObjectListener
        // callback (onSolidContact) handles the push/roll damage check.

        // ROM: Face direction toward player (bclr/bset #0,obStatus)
        if (player != null) {
            int playerX = player.getCentreX() & 0xFFFF;
            if (playerX >= state.x) {
                state.renderFlags |= 1; // Face right
            } else {
                state.renderFlags &= ~1; // Face left
            }
        }
    }

    private void updateCombatAnimation() {
        if (damageCooldown > 0) {
            damageCooldown--;
            if (damageCooldown > 0) {
                // ROM: move.b #3,obAnim — inside tube during cooldown
                seggAnim = Sonic1BossAnimations.ANIM_SEGG_INTUBE;
            } else {
                // ROM: move.b #1,obAnim — return to laugh when cooldown ends
                seggAnim = Sonic1BossAnimations.ANIM_SEGG_LAUGH;
            }
        } else {
            // ROM: move.b #1,obAnim — normal laugh (default combat anim)
            seggAnim = Sonic1BossAnimations.ANIM_SEGG_LAUGH;
        }
        updateSeggAnimation();
    }

    // === State 4: PLASMA_PHASE (loc_19FE6) ===
    private void updatePlasmaPhase(int vIntRunCount) {
        if (cylinderState < 0) {
            // ROM: Activate plasma launcher
            cylinderState = 0;
            if (plasmaLauncher != null) {
                plasmaLauncher.activateForBoss();
            }
        }

        // ROM: play electricity sound every 16 frames
        if ((vIntRunCount & 0xF) == 0) {
            services().playSfx(Sonic1Sfx.ELECTRIC.id);
        }

        // ROM: tst.w objoff_32 — check if plasma launcher signaled completion
        if (activeCylinderCount < 0) {
            // Plasma done, loop back to cylinder attack
            state.routineSecondary = STATE_CYLINDER_ATTACK;
            cylinderState = -1;
            activeCylinderCount = 0;
        }
    }

    // === State 6: DEFEAT_FALL (loc_1A02A) ===
    // Gravity descent after defeat
    private void updateDefeatFall() {
        // ROM: bset #0,obStatus — face right
        state.renderFlags |= 1;

        // ROM: SpeedToPos
        state.xFixed += (state.xVel << 8);
        state.yFixed += (state.yVel << 8);
        state.x = state.xFixed >> 16;
        state.y = state.yFixed >> 16;

        // ROM: move.b #6,obFrame — starjump frame
        seggAnim = Sonic1BossAnimations.ANIM_SEGG_STARJUMP;
        seggFrame = 6;

        // ROM: addi.w #$10,obVelY — gravity
        state.yVel += 0x10;

        // ROM: cmpi.w #boss_fz_y+$8C,obY — check landing
        if (state.y >= BOSS_FZ_Y + 0x8C) {
            // ROM loc_1A05A: move.w #boss_fz_y+$8C,obY — WORD store preserves subpixel.
            clampYPreservingSubpixel(BOSS_FZ_Y + 0x8C);
            state.routineSecondary = STATE_RUNNING_ESCAPE;
            // ROM: move.w #$100,obVelX / move.w #-$100,obVelY
            state.xVel = 0x100;
            state.yVel = -0x100;
            // ROM: addq.b #2,(v_dle_routine).w — advance level event routine
            // This is handled by SBZ events checking boss state
        }

        // Expand camera boundary (ROM: loc_1A166)
        expandCameraBoundary();

        // Solid collision during escape (ROM: loc_1A172 — d1=$1B, d2=$70, d3=$71)
        // Only if objoff_34 < $C (states 6-10)
    }

    // === State 8: RUNNING_ESCAPE (loc_1A074) ===
    // Complex X velocity chase of Sonic with Y bounce
    private void updateRunningEscape(AbstractPlayableSprite player) {
        state.renderFlags |= 1; // bset #0,obStatus
        seggAnim = Sonic1BossAnimations.ANIM_SEGG_RUNNING;

        // ROM: SpeedToPos
        state.xFixed += (state.xVel << 8);
        state.yFixed += (state.yVel << 8);
        state.x = state.xFixed >> 16;
        state.y = state.yFixed >> 16;

        // ROM: addi.w #$10,obVelY — gravity
        state.yVel += 0x10;

        // ROM: cmpi.w #boss_fz_y+$93,obY — bounce at floor
        if (state.y >= BOSS_FZ_Y + 0x93) {
            state.yVel = -0x40;
        }

        // ROM: Complex X velocity calculation (loc_1A074 - loc_1A0F2)
        // Base speed: $400, increase to $500 if behind player, reduce based on distance ahead
        int xVelCalc = 0x400;
        if (player != null) {
            int dist = state.x - (player.getCentreX() & 0xFFFF);
            if (dist < 0) {
                // Behind player — speed up
                xVelCalc = 0x500;
            } else {
                // Ahead of player — slow down based on distance
                dist -= 0x70;
                if (dist >= 0) {
                    xVelCalc -= 0x100;
                    dist -= 8;
                    if (dist >= 0) {
                        xVelCalc -= 0x100;
                        dist -= 8;
                        if (dist >= 0) {
                            xVelCalc -= 0x80;
                            dist -= 8;
                            if (dist >= 0) {
                                xVelCalc -= 0x80;
                                dist -= 8;
                                if (dist >= 0) {
                                    xVelCalc -= 0x80;
                                    dist -= 0x38;
                                    if (dist >= 0) {
                                        xVelCalc = 0; // Stop
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        state.xVel = xVelCalc;

        // ROM: cmpi.w #boss_fz_x+$250,obX — transition to final ascent
        if (state.x >= BOSS_FZ_X + 0x250) {
            // ROM loc_1A0F2: move.w #boss_fz_x+$250,obX — WORD store preserves subpixel.
            clampXPreservingSubpixel(BOSS_FZ_X + 0x250);
            state.xVel = 0x240;
            state.yVel = -0x4C0;
            state.routineSecondary = STATE_FINAL_ASCENT;
        }

        // Expand camera boundary + solid collision (ROM: loc_1A15C -> loc_1A166)
        expandCameraBoundary();
        updateSeggAnimation();
    }

    // === State 10: FINAL_ASCENT (loc_1A112) ===
    // High gravity arc to landing position
    private void updateFinalAscent() {
        // ROM: SpeedToPos
        state.xFixed += (state.xVel << 8);
        state.yFixed += (state.yVel << 8);
        state.x = state.xFixed >> 16;
        state.y = state.yFixed >> 16;

        // ROM: cmpi.w #boss_fz_x+$290,obX — cap X position
        if (state.x >= BOSS_FZ_X + 0x290) {
            state.xVel = 0;
        }

        // ROM: addi.w #$34,obVelY — strong gravity
        state.yVel += 0x34;

        // ROM: cmpi.w #boss_fz_y+$82,obY — landing check (only if yVel >= 0)
        if (state.yVel >= 0 && state.y >= BOSS_FZ_Y + 0x82) {
            // ROM: move.w #boss_fz_y+$82,obY — WORD store preserves subpixel.
            clampYPreservingSubpixel(BOSS_FZ_Y + 0x82);
            state.yVel = 0;
        }

        // Check if both velocities are zero — landed
        if (state.xVel == 0 && state.yVel == 0) {
            state.routineSecondary = STATE_SHIP_TRANSFORM;
            state.yVel = -0x180;
            // ROM: move.b #1,obColProp — mark as hittable (for escape sequence)
            escapeHittable = false; // Not yet — set in ship transform
        }

        // Expand camera boundary + animate (ROM: loc_1A15C -> loc_1A166)
        expandCameraBoundary();
        updateSeggAnimation();
    }

    // === State 12: SHIP_TRANSFORM (loc_1A192) ===
    // Switch to Map_Eggman, ascend to escape ship
    private void updateShipTransform() {
        // ROM: move.l #Map_Eggman,obMap / move.b #0,obAnim
        escapingInShip = true;
        seggAnim = Sonic1BossAnimations.ANIM_SEGG_STAND;
        state.renderFlags |= 1; // bset #0,obStatus

        // ROM: SpeedToPos
        state.xFixed += (state.xVel << 8);
        state.yFixed += (state.yVel << 8);
        state.x = state.xFixed >> 16;
        state.y = state.yFixed >> 16;

        // ROM: cmpi.w #boss_fz_y+$34,obY — ascend until reaching escape height
        if (state.y < BOSS_FZ_Y + 0x34) {
            // ROM: Transition to final flight
            state.xVel = 0x180;
            state.yVel = -0x18;
            state.routineSecondary = STATE_FINAL_FLIGHT;
            escapeHittable = true;
            escapeHitTimer = 0;
            escapeCollisionFlags = ESCAPE_COLLISION_FLAGS;
        }

        updateEscapeLegs();

        // Expand camera boundary + animate
        expandCameraBoundary();
        updateSeggAnimation();
    }

    // === State 14: FINAL_FLIGHT (loc_1A1D4) ===
    // Escape flight with player control lock and ending trigger
    private void updateFinalFlight(AbstractPlayableSprite player, int vIntRunCount) {
        state.renderFlags |= 1; // bset #0,obStatus

        // ROM: SpeedToPos
        state.xFixed += (state.xVel << 8);
        state.yFixed += (state.yVel << 8);
        state.x = state.xFixed >> 16;
        state.y = state.yFixed >> 16;

        // ROM loc_1A1FC: when the $1E post-hit timer expires, `tst.b obStatus / bpl
        // loc_1A210` re-arms col_48x48|col_boss ONLY when obStatus bit 7 (the boss-
        // defeated flag set by React_BossHit) is clear; otherwise it falls
        // (move.w #$60,obVelY) and stays col_none. The escape carries obBossHits=1
        // (BossFinal_Eggman_Jump loc_1A142), so the single escape roll-bounce takes
        // obBossHits 1->0 and sets the defeated bit — the boss is never hittable
        // again. The prior on-screen check wrongly re-armed the hitbox, producing a
        // second roll-bounce ROM never makes (FZ trace f4182). showDamaged is set in
        // onPlayerAttack on that escape hit and is the faithful defeated-bit proxy.
        if (escapeHitTimer > 0) {
            escapeHitTimer--;
            if (escapeHitTimer == 0) {
                if (showDamaged) {
                    state.yVel = 0x60;
                } else {
                    escapeHittable = true;
                    escapeCollisionFlags = ESCAPE_COLLISION_FLAGS;
                }
            }
        }

        updateEscapeLegs();

        // ROM: Player control lock when player X >= boss_fz_end + $90
        if (player != null) {
            int playerX = player.getCentreX() & 0xFFFF;

            // ROM: loc_1A216 — lock player controls past threshold
            if (playerX >= BOSS_FZ_END + 0x90) {
                // ROM: move.b #1,f_lockctrl / move.w #0,v_jpadhold2 / clr.w
                // (v_player+obInertia). ROM clears ONLY the inertia (ground speed) —
                // it never clears obVelX, so an airborne rolling Sonic keeps his
                // x_speed (FZ trace f4200: ROM x_speed stays 0x0255). The prior
                // setXSpeed(0) zeroed it and diverged.
                player.setControlLocked(true);
                player.setGSpeed((short) 0);

                // ROM: tst.w obVelY(a0) / bpl loc_1A248 — only when the boss is still
                // rising (velY < 0, i.e. Eggman escaped un-hit) does ROM force btnUp to
                // make Sonic look up. On a successful-hit run the boss is defeated and
                // falls (velY=$60), so velY >= 0 here and ROM takes no extra action.
            }

            // ROM: Cap player X at boss_fz_end + $E0
            if (playerX >= BOSS_FZ_END + 0xE0) {
                player.setCentreX((short) (BOSS_FZ_END + 0xE0));
            }
        }

        // ROM: cmpi.w #boss_fz_end+$200,obX — trigger ending
        if (state.x >= BOSS_FZ_END + 0x200 && !isBossOnScreen()) {
            requestEndingTransition();
            setDestroyed(true);
            return;
        }

        // Expand camera boundary
        expandCameraBoundary();

        // ROM: sub-object routine 6 keeps calling BossDefeated while damaged escape sprite is active.
        if (showDamaged) {
            triggerBossDefeatedExplosion(vIntRunCount);
        }

        updateSeggAnimation();
    }

    private void updateEscapeLegs() {
        if (!escapingInShip || legsFrame > 2) {
            return;
        }
        if (legsTimer == 0) {
            legsTimer = 0x14;
        }
        legsTimer--;
        if (legsTimer <= 0) {
            legsFrame++;
        }
    }

    private void updateSeggAnimation() {
        SpriteAnimationScript script = SEGG_ANIMATIONS.getScript(seggAnim);
        if (script == null || script.frames().isEmpty()) {
            return;
        }

        if (seggAnim != seggAnimPrev) {
            seggAnimPrev = seggAnim;
            seggAnimScriptFrame = 0;
            seggAnimTimeFrame = 0;
        }

        seggAnimTimeFrame--;
        if (seggAnimTimeFrame >= 0) {
            return;
        }

        seggAnimTimeFrame = script.delay() & 0xFF;

        if (seggAnimScriptFrame < 0 || seggAnimScriptFrame >= script.frames().size()) {
            seggAnimScriptFrame = 0;
        }

        seggFrame = script.frames().get(seggAnimScriptFrame) & 0x1F;
        seggAnimScriptFrame++;

        if (seggAnimScriptFrame < script.frames().size()) {
            return;
        }

        switch (script.endAction()) {
            case HOLD -> seggAnimScriptFrame = script.frames().size() - 1;
            case LOOP_BACK -> {
                int loopBack = script.endParam();
                if (loopBack <= 0) {
                    seggAnimScriptFrame = 0;
                } else {
                    int target = script.frames().size() - loopBack;
                    seggAnimScriptFrame = Math.max(target, 0);
                }
            }
            case SWITCH -> {
                int nextAnim = script.endParam();
                if (nextAnim == seggAnim) {
                    seggAnimScriptFrame = 0;
                } else {
                    seggAnim = nextAnim;
                    seggAnimPrev = -1;
                }
            }
            case LOOP -> seggAnimScriptFrame = 0;
            default -> seggAnimScriptFrame = 0;
        }
    }

    /**
     * Expand camera right boundary by 2 per frame until boss_fz_end.
     * ROM: loc_1A166 — addq.w #2,(v_limitright2).w
     */
    private void expandCameraBoundary() {
        Camera camera = services().camera();
        int rightBoundary = camera.getMaxX() & 0xFFFF;
        if (rightBoundary < BOSS_FZ_END) {
            camera.setMaxX((short) (rightBoundary + 2));
        }

        // Unfreeze camera during escape so player can follow
        if (state.routineSecondary >= STATE_DEFEAT_FALL && camera.getFrozen()) {
            camera.setFrozen(false);
        }
    }

    /**
     * Called by SolidContacts when player is pushed by this boss.
     * Implements the FZ boss's unique damage mechanic:
     * ROM: loc_19F50 — check d4 > 0 (side contact) AND obAnim == id_Roll
     */
    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Only process during cylinder attack phase
        if (state.routineSecondary != STATE_CYLINDER_ATTACK) return;

        // ROM: tst.w d4 / bgt.s loc_19F50 — side collision path (d4 > 0, i.e.
        // SolidObject returned d4 == 1 "side collision", sub SolidObject.asm:13).
        if (!contact.touchSide()) return;

        // SolidObject biases the combined-height check by +4, yielding the native
        // asymmetric window [-extent-4, extent-5]. The shared touchSide flag uses a
        // symmetric box, so reproduce that gate before applying side-branch state.
        int verticalExtent = COMBAT_SOLID_PARAMS.airHalfHeight() + player.getYRadius();
        int relativeY = player.getCentreY() - state.y;
        if (relativeY < -verticalExtent - 4 || relativeY >= verticalExtent - 4) return;

        // ROM: loc_19F50 — addq.w #7,(v_random).w runs on EVERY side-contact frame,
        // BEFORE the rolling/bounce check, whether or not the player is rolling
        // (_incObj/85,84,86 Boss - FZ Main, Cylinders, and Plasma Balls.asm:192-195).
        // This advances v_random while Sonic pushes against the boss body during the
        // cylinder-attack phase, so the later BossPlasma_MakeBalls RandomNumber draws
        // (ball target spread) consume the ROM seed. addq.w targets (v_random).w —
        // the high word of the 32-bit seed (big-endian) — so it is a 16-bit add to
        // the high word with no carry into the low word.
        GameRng rng = services().rng();
        long seed = rng.getSeed();
        long highWord = ((seed >>> 16) + 7) & 0xFFFFL;
        rng.setSeed((seed & 0xFFFFL) | (highWord << 16));

        // ROM: cmpi.b #id_Roll,(v_player+obAnim).w
        int animId = player.getAnimationId();
        boolean rollAnimating = animId == Sonic1AnimationIds.ROLL.id() || animId == Sonic1AnimationIds.ROLL2.id();
        if (!rollAnimating) return;
        // The ROM can replace obAnim again before BossFinal_Eggman_Crush polls it
        // when a jump begins from Status_Push beside the boss while the first roll
        // mapping frame is displayed. Suppress through its first neutral-speed wrap;
        // an already-established roll merely wrapping to frame 0x2E must remain
        // able to hit and must not start a new suppression cycle.
        if (!suppressCurrentRollAttack
                && player.getPushingAtFrameStart()
                && player.getMappingFrame() == 0x2E
                && player.getGSpeed() != 0
                && (player.getCentreX() & 0xFFFF) < (state.x & 0xFFFF)) {
            suppressCurrentRollAttack = true;
            suppressedRollLeftInitialMapping = false;
        }
        if (suppressCurrentRollAttack) return;

        // ROM: Bounce player back — move.w #$300,d0
        int bounceVel = 0x300;
        if ((state.renderFlags & 1) == 0) {
            bounceVel = -bounceVel;
        }
        player.setXSpeed((short) bounceVel);

        // ROM: tst.b objoff_35 — check damage cooldown
        if (damageCooldown > 0) return;

        // Deal damage
        state.hitCount--;
        damageCooldown = 0x64; // ROM: move.b #$64,objoff_35

        // ROM: sfx_HitBoss
        services().playSfx(Sonic1Sfx.HIT_BOSS.id);
    }

    /**
     * Called by cylinders when they finish retracting.
     * ROM: subq.w #1,objoff_32(a1) — decrement parent's active counter
     */
    public void onCylinderDone() {
        activeCylinderCount--;
    }

    /**
     * Called by plasma launcher when all balls are done.
     * ROM: move.w #-1,objoff_32(a1) — signal completion
     */
    public void onPlasmaComplete() {
        activeCylinderCount = -1;
    }

    /**
     * Keep boss position locked to the active host cylinder.
     * ROM: _incObj/84 FZ Eggman's Cylinders.asm loc_1A514.
     */
    void syncPositionFromCylinder(int x, int y) {
        state.x = x;
        state.y = y;
        state.xFixed = x << 16;
        state.yFixed = y << 16;
    }

    /**
     * Clamp the integer Y position while preserving the accumulated 16.16 subpixel
     * fraction, matching the ROM's {@code move.w #imm,obY(a0)} WORD store (which writes
     * only the high word of the 16.16 long; the low-word subpixel is untouched).
     * Zeroing the fraction with {@code y << 16} loses up to ~1px across the slow
     * (velY=-$18) escape ascent, which shifted the boss 1px high and made the
     * escape-sprite roll-bounce fire one frame late (FZ trace f4128).
     */
    private void clampYPreservingSubpixel(int newY) {
        state.yFixed = (newY << 16) | (state.yFixed & 0xFFFF);
        state.y = newY;
    }

    /**
     * Clamp the integer X position while preserving the accumulated 16.16 subpixel
     * fraction, matching the ROM's {@code move.w #imm,obX(a0)} WORD store.
     */
    private void clampXPreservingSubpixel(int newX) {
        state.xFixed = (newX << 16) | (state.xFixed & 0xFFFF);
        state.x = newX;
    }

    /**
     * Check if boss is defeated (for cylinder BossDefeated checks).
     */
    public boolean isBossDefeated() {
        return state.hitCount <= 0;
    }

    /**
     * ROM BossDefeated helper used by FZ child objects during post-defeat sequences.
     * Runs on an 8-frame cadence like v_vbla_byte&7 in the original routine.
     */
    void triggerBossDefeatedExplosion(int frameCounter) {
        if ((frameCounter & 7) == 0) {
            spawnDefeatExplosionAt(state.x, state.y);
        }
    }

    /**
     * ROM BossDefeated helper for FZ child components with independent positions.
     */
    void triggerBossDefeatedExplosion(int frameCounter, int sourceX, int sourceY) {
        if ((frameCounter & 7) == 0) {
            spawnDefeatExplosionAt(sourceX, sourceY);
        }
    }

    private void spawnDefeatExplosionAt(int sourceX, int sourceY) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null || services().objectManager() == null) {
            return;
        }

        int random = services().rng().nextWord();
        int xOffset = ((random & 0xFF) >> 2) - 0x20;
        int yOffset = (((random >> 8) & 0xFF) >> 2) - 0x20;
        final int finalSourceX = sourceX + xOffset;
        final int finalSourceY = sourceY + yOffset;
        spawnFreeChild(() -> new BossExplosionObjectInstance(
                finalSourceX,
                finalSourceY,
                Sonic1ObjectIds.EXPLOSION,
                Sonic1Sfx.BOSS_EXPLOSION.id));
    }

    private void requestEndingTransition() {
        if (endingTransitionRequested) {
            return;
        }
        endingTransitionRequested = true;

        int endingAct = services().gameState().hasAllEmeralds()
                ? ENDING_ACT_FLOWERS
                : ENDING_ACT_NO_EMERALDS;
        // ROM: move.b #id_Ending,(v_gamemode).w
        // Engine parity path: transition to the dedicated ending zone/act variant.
        services().requestZoneAndAct(Sonic1ZoneConstants.ZONE_ENDING, endingAct, true);
    }

    // === SolidObjectProvider interface ===

    @Override
    public boolean usesPreUpdatePositionForSolidContact(PlayableEntity player) {
        // BossFinal_Eggman_Crush calls SolidObject from the boss slot before the
        // later cylinder slots update the parent's y_pos. During the cylinder
        // attack, contact therefore observes the boss's frame-start position.
        return state.routineSecondary == STATE_CYLINDER_ATTACK;
    }

    /**
     * Eggman's ROM {@code obActWid}, which the defeat fall re-writes.
     *
     * <p>Combat is {@code BossFinal_ObjData2} row 0's {@code #64/2} = 32,
     * stored by {@code BossFinal_Main} into the parent's own slot via
     * {@code movea.l a0,a1} (:76-101). {@code BossFinal_Eggman_Fall} then writes
     * {@code #96/2} = 48 for the fall and {@code #64/2} = 32 again at the
     * landing snap (:355-371). Both sites sit on a {@code Revision} conditional
     * rather than a {@code FixBugs} one: {@code Revision = 0} writes
     * {@code obWidth} instead, which the listing at {@code :776-778} calls the
     * developers "fumbling obWidth and obActWidth, which wasn't completely fixed
     * until REV01". The engine targets REV01, so {@code obActWid} is the byte
     * that is written.
     *
     * <p>Only the combat 32 is observable through {@code Sonic_Balance}.
     * Reaching {@code BossFinal_Eggman_Fall} requires the boss to be defeated,
     * and {@code Sonic ReactToItem.asm:268} sets {@code obStatus} bit 7 on the
     * defeated boss — which {@code Sonic_Balance} tests before it ever reads the
     * width, branching to {@code Sonic_LookUp}
     * (docs/s1disasm/_incObj/01 Sonic.asm:418-420). The fall value is supplied
     * anyway because this accessor is also {@code BuildSprites}' horizontal cull
     * bound (docs/s1disasm/_inc/BuildSprites.asm:49-58), and the cull stays live
     * after the defeat.
     *
     * <p>The phase split reuses {@code routineSecondary}, which the class
     * already keeps; the solid boxes are authored separately and unchanged.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return state.routineSecondary == STATE_DEFEAT_FALL
                ? DEFEAT_FALL_ACT_WIDTH
                : COMBAT_ACT_WIDTH;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: Phases 6-10 use larger solid box (d1=$1B, d2=$70, d3=$71)
        if (state.routineSecondary >= STATE_DEFEAT_FALL &&
                state.routineSecondary < STATE_SHIP_TRANSFORM) {
            return ESCAPE_SOLID_PARAMS;
        }
        // ROM: Combat phase uses smaller box (d1=$2B, d2=$14, d3=$14)
        if (state.routineSecondary == STATE_CYLINDER_ATTACK) {
            return COMBAT_SOLID_PARAMS;
        }
        // No solidity in other states
        return null;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: Solid during cylinder attack and escape phases 6-10
        return state.routineSecondary == STATE_CYLINDER_ATTACK ||
                (state.routineSecondary >= STATE_DEFEAT_FALL &&
                        state.routineSecondary < STATE_SHIP_TRANSFORM);
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // The FZ boss combat body uses plain SolidObject (BossFinal_Eggman_Crush ->
        // loc_19F2E jsr (SolidObject), docs/s1disasm/_incObj/85,84,86 Boss - FZ Main,
        // Cylinders, and Plasma Balls.asm:177-182). SolidObject's right-edge X gate is
        // `cmp.w d3,d0 / bhi.w Solid_NoCollision` (docs/s1disasm/_incObj/sub
        // SolidObject.asm:167-168), where bhi is exclusive-greater — so the exact-edge
        // case relX == width*2 (d0 == d3) IS a valid contact. The engine's default
        // exclusive gate (relX >= width*2 -> no contact) rejected the frame Sonic's
        // rolling jump grazes the boss's right edge (player center == bossX + $2B), so
        // the +$300 rolling-into-boss bounce fired one frame late (FZ trace f837 -> f838).
        // Opting into the ROM-faithful inclusive right edge restores the f837 bounce.
        return true;
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity playerEntity, int collisionHalfWidth) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (state.routineSecondary == STATE_CYLINDER_ATTACK) {
            return COMBAT_TOP_LANDING_HALF_WIDTH;
        }
        // Escape phases follow the full-solid family default: Solid_Landed
        // re-reads obActWid = d1 - $B (_incObj/sub SolidObject.asm:318-336).
        return Math.max(0, collisionHalfWidth - 0x0B);
    }

    // === Rendering ===

    private boolean isBossOnScreen() {
        Camera camera = services().camera();
        int screenX = state.x - camera.getX();
        return screenX >= -64 && screenX <= 384;
    }

    @Override
    public int getPriorityBucket() {
        return 4; // ROM: obPriority = 4 (adjusted for escape = 2)
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        if (!escapingInShip) {
            // Draw using Map_SEgg (FZ Eggman in machine)
            PatternSpriteRenderer seggRenderer = renderManager.getRenderer(ObjectArtKeys.FZ_SEGG);
            if (seggRenderer != null && seggRenderer.isReady()) {
                boolean flipped = (state.renderFlags & 1) != 0;
                seggRenderer.drawFrameIndex(seggFrame, state.x, state.y, flipped, false);
            }
        } else {
            // Draw using Map_Eggman (standard Eggman escape ship)
            PatternSpriteRenderer eggmanRenderer = renderManager.getRenderer(ObjectArtKeys.EGGMAN);
            if (eggmanRenderer != null && eggmanRenderer.isReady()) {
                boolean flipped = (state.renderFlags & 1) != 0;

                // Keep the base escape ship body visible in all escape-hit states.
                eggmanRenderer.drawFrameIndex(0, state.x, state.y, flipped, false);

                if (!showDamaged) {
                    // Panic face (frame 6) before escape hit.
                    eggmanRenderer.drawFrameIndex(6, state.x, state.y, flipped, false);
                }

                // Flame overlay — escape flame
                eggmanRenderer.drawFrameIndex(11, state.x, state.y, flipped, false);
            }

            // Draw FZ legs overlay if legs are visible (sub-object routine 8)
            if (!showDamaged && state.routineSecondary >= STATE_SHIP_TRANSFORM && legsFrame <= 2) {
                PatternSpriteRenderer legsRenderer = renderManager.getRenderer(ObjectArtKeys.FZ_LEGS);
                if (legsRenderer != null && legsRenderer.isReady()) {
                    boolean flipped = (state.renderFlags & 1) != 0;
                    legsRenderer.drawFrameIndex(legsFrame, state.x, state.y, flipped, false);
                }
            }

            // Post-hit escape visuals overlay Map_FZDamaged on top of the base ship.
            if (showDamaged) {
                PatternSpriteRenderer damagedRenderer = renderManager.getRenderer(ObjectArtKeys.FZ_DAMAGED);
                if (damagedRenderer != null && damagedRenderer.isReady()) {
                    boolean flipped = (state.renderFlags & 1) != 0;
                    damagedRenderer.drawFrameIndex((state.lastUpdatedVIntRunCount >> 2) & 1, state.x, state.y, flipped, false);
                }
            }
        }
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic1Sfx.HIT_BOSS.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic1Sfx.BOSS_EXPLOSION.id;
    }

    @Override
    protected int getBossExplosionObjectId() {
        return Sonic1ObjectIds.EXPLOSION;
    }

}
