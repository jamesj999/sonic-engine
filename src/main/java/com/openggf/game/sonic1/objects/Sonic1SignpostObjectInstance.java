package com.openggf.game.sonic1.objects;
import com.openggf.audio.GameMusic;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;

import com.openggf.camera.Camera;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.objects.SignpostSparkleObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 1 end-of-act signpost (Object 0D).
 * <p>
 * Behavior from docs/s1disasm/_incObj/0D Signpost.asm:
 * <ol>
 *   <li>Sign_Main (routine 0): Init art/mappings, fall through to Touch</li>
 *   <li>Sign_Touch (routine 2): Wait for player to pass signpost</li>
 *   <li>Sign_Spin (routine 4): Spin sign, spawn sparkles</li>
 *   <li>Sign_SonicRun (routine 6): Lock controls, player walks off-screen</li>
 *   <li>Sign_Exit (routine 8): No-op, sign stays rendered</li>
 * </ol>
 * <p>
 * Animation scripts from _anim/Signpost.asm:
 * <ul>
 *   <li>Anim 0 (.eggman): frame 0 at delay $F (static Eggman)</li>
 *   <li>Anim 1 (.spin1): frames 0,1,2,3 at delay 1 (Eggman-to-Sonic spin)</li>
 *   <li>Anim 2 (.spin2): frames 4,1,2,3 at delay 1 (Sonic-to-Eggman spin)</li>
 *   <li>Anim 3 (.sonic): frame 4 at delay $F (static Sonic)</li>
 * </ul>
 */
public class Sonic1SignpostObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic1SignpostObjectInstance.class.getName());

    // Routine states matching disassembly Sign_Index offsets
    private static final int STATE_IDLE = 0;       // Sign_Main + Sign_Touch (routine 0+2)
    private static final int STATE_SPINNING = 1;   // Sign_Spin (routine 4)
    private static final int STATE_WALK_OFF = 2;   // Sign_SonicRun (routine 6)
    private static final int STATE_COMPLETE = 3;   // Sign_Exit (routine 8)

    // Mapping frame indices from Map_Sign_internal
    private static final int FRAME_EGGMAN = 0;     // .eggman: Eggman face
    private static final int FRAME_SPIN1 = 1;      // .spin1: Wide spinning
    private static final int FRAME_SPIN2 = 2;      // .spin2: Thin/edge-on
    private static final int FRAME_SPIN3 = 3;      // .spin3: Wide mirrored spinning
    private static final int FRAME_SONIC = 4;      // .sonic: Sonic face

    // Animation sequences from Ani_Sign
    // Anim 0 (.eggman): static Eggman face
    // Anim 1 (.spin1): 0->1->2->3 at 2-frame delay (delay byte = 1 => 2 frames per frame)
    // Anim 2 (.spin2): 4->1->2->3 at 2-frame delay
    // Anim 3 (.sonic): static Sonic face
    private static final int[][] ANIM_SEQUENCES = {
            { FRAME_EGGMAN },                                     // anim 0: static Eggman
            { FRAME_EGGMAN, FRAME_SPIN1, FRAME_SPIN2, FRAME_SPIN3 }, // anim 1: Eggman spin
            { FRAME_SONIC, FRAME_SPIN1, FRAME_SPIN2, FRAME_SPIN3 },  // anim 2: Sonic spin
            { FRAME_SONIC },                                      // anim 3: static Sonic
    };

    // Ani_Sign delay byte for spinning anims is 1, meaning 2 frames per animation step
    // (S1 AnimateSprite: delay byte + 1 = frames between advances)
    private static final int SPIN_ANIM_FRAME_DELAY = 2;

    // Sign_Spin: spin cycle time = 60 frames (1 second at 60fps)
    // ROM: move.w #60,spintime(a0)
    private static final int SPIN_CYCLE_FRAMES = 60;

    // Sign_Spin: 3 spin cycles before transitioning to walk-off
    // ROM: cmpi.b #3,obAnim(a0)
    private static final int SPIN_CYCLE_COUNT = 3;

    // Sign_Touch: player must be within $20 pixels right of signpost
    // ROM: sub.w obX(a0),d0; bcs.s .notouch; cmpi.w #$20,d0; bhs.s .notouch
    private static final int ACTIVATION_X_RANGE = 0x20;

    // Sign_SonicRun: player must pass camera right + $128 to trigger results
    // ROM: addi.w #$128,d1
    private static final int WALK_OFF_OFFSET = 0x128;

    // Sparkle spawn timing from Sign_Spin
    // ROM: move.w #$B,sparkletime(a0)
    private static final int SPARKLE_SPAWN_DELAY = 0x0B;

    // Sparkle positions (relative to signpost center) from Sign_SparkPos
    // ROM: 8 pairs of signed bytes (x, y)
    private static final int[][] SPARKLE_POSITIONS = {
            { -0x18, -0x10 }, {  0x08,  0x08 }, { -0x10,  0x00 }, {  0x18, -0x08 },
            {  0x00, -0x08 }, {  0x10,  0x00 }, { -0x18,  0x08 }, {  0x18,  0x10 },
    };

    // S1 has 3 acts per zone; signpost only appears in acts 1 and 2 (indices 0 and 1)
    // Act 3 (index 2) is the boss act with no signpost
    private static final int BOSS_ACT_INDEX = 2;

    private int routineState = STATE_IDLE;
    private int currentFrame = FRAME_EGGMAN;

    // Spin state
    private int currentAnim = 0;
    private int spinTimer = 0;
    private int animFrameIndex = 0;
    private int animDelayCounter = 0;

    // Sparkle state
    private int sparkleTimer = 0;
    private int sparkleIndex = 0;

    // Walk-off state
    private boolean resultsSpawned;
    private boolean initialized;

    public Sonic1SignpostObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Signpost");
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;

        // Signpost is disabled in act 3 (boss acts)
        // S1 only has signposts in acts 1 and 2 (indices 0 and 1)
        int currentAct = services().currentAct();
        if (currentAct >= BOSS_ACT_INDEX) {
            ObjectManager objMgr = services().objectManager();
            ObjectLifetimeOps.markSpawnRemembered(objMgr, spawn);
            setDestroyed(true);
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed() || player == null) {
            return;
        }

        switch (routineState) {
            case STATE_IDLE -> checkPlayerPass(player);
            case STATE_SPINNING -> updateSpinning();
            case STATE_WALK_OFF -> updateWalkOff(player);
            case STATE_COMPLETE -> { /* Sign_Exit: rts */ }
        }
    }

    /**
     * Sign_Touch (routine 2): Check if player has passed the signpost.
     * ROM: move.w (v_player+obX).w,d0; sub.w obX(a0),d0; bcs.s .notouch;
     *      cmpi.w #$20,d0; bhs.s .notouch
     * <p>
     * S1 obX is the center position for both player and objects.
     * The check is: playerCenterX - signpostCenterX >= 0 AND < $20.
     */
    private void checkPlayerPass(AbstractPlayableSprite player) {
        int dx = player.getCentreX() - spawn.x();
        if (dx < 0 || dx >= ACTIVATION_X_RANGE) {
            return;
        }
        activateSignpost();
    }

    private void activateSignpost() {
        LOGGER.info("S1 Signpost activated at X=" + spawn.x());

        // ROM: move.w #sfx_Signpost,d0; jsr (QueueSound1).l
        try {
            services().playSfx(Sonic1Sfx.SIGNPOST.id);
        } catch (Exception e) {
            LOGGER.warning("Failed to play signpost sound: " + e.getMessage());
        }

        // ROM: clr.b (f_timecount).w - stop time counter
        var levelGamestate = services().levelGamestate();
        if (levelGamestate != null) {
            levelGamestate.pauseTimer();
        }

        // ROM: move.w (v_limitright2).w,(v_limitleft2).w
        // S1 only uses v_limitleft2 as the boundary (v_limitleft1 is marked "unused"
        // in Variables.asm), and ScrollHoriz clamps directly against v_limitleft2.
        // This means the left boundary takes effect immediately — no easing.
        // Use setMinX() to set both current and target, matching the instant lock.
        Camera camera = services().camera();
        if (camera != null) {
            camera.setMinX(camera.getMaxX());
            LOGGER.fine("Camera left boundary locked to maxX=" + camera.getMaxX());
        }

        // ROM: addq.b #2,obRoutine(a0) - advance to Sign_Spin
        routineState = STATE_SPINNING;

        // spintime starts at 0; first subq.w #1 makes it negative, triggering immediately
        spinTimer = 0;
        currentAnim = 0;
        sparkleTimer = 0;
        sparkleIndex = 0;
        animFrameIndex = 0;
        animDelayCounter = 0;
        currentFrame = FRAME_EGGMAN;
    }

    /**
     * Sign_Spin (routine 4): Handle spin animation and sparkle spawning.
     * <p>
     * ROM behavior:
     *   subq.w #1,spintime(a0)     - decrement spin timer
     *   bpl.s .chksparkle          - if >= 0, skip to sparkle check
     *   move.w #60,spintime(a0)    - reset to 60 frames
     *   addq.b #1,obAnim(a0)      - advance to next animation
     *   cmpi.b #3,obAnim(a0)      - check if all 3 cycles done
     *   bne.s .chksparkle          - if not, continue
     *   addq.b #2,obRoutine(a0)   - advance to Sign_SonicRun
     */
    private void updateSpinning() {
        // Decrement spin timer (ROM starts at 0, first subq makes it -1)
        spinTimer--;
        if (spinTimer < 0) {
            spinTimer = SPIN_CYCLE_FRAMES;
            currentAnim++;
            if (currentAnim >= SPIN_CYCLE_COUNT) {
                // All spin cycles complete - show Sonic and enter walk-off
                currentFrame = FRAME_SONIC;
                routineState = STATE_WALK_OFF;
                LOGGER.fine("S1 Signpost spin complete, entering walk-off state");
                return;
            }
            // Reset animation state for new cycle
            animFrameIndex = 0;
            animDelayCounter = 0;
        }

        // Update animation frame within current anim sequence
        // Driven by AnimateSprite with delay byte from Ani_Sign
        updateAnimFrame();

        // Spawn sparkle effects
        updateSparkles();
    }

    /**
     * Updates the current mapping frame based on the animation script.
     * Ani_Sign animations 0 and 3 are static (delay $F), while 1 and 2 cycle
     * through 4 frames at delay 1 (2 frames per step).
     */
    private void updateAnimFrame() {
        int[] sequence = ANIM_SEQUENCES[currentAnim];
        if (sequence.length == 1) {
            // Static animation (anim 0 = Eggman, anim 3 = Sonic)
            currentFrame = sequence[0];
            return;
        }

        // Cycling animation: advance frame at SPIN_ANIM_FRAME_DELAY interval
        animDelayCounter++;
        if (animDelayCounter >= SPIN_ANIM_FRAME_DELAY) {
            animDelayCounter = 0;
            animFrameIndex++;
            if (animFrameIndex >= sequence.length) {
                animFrameIndex = 0;
            }
        }
        currentFrame = sequence[animFrameIndex];
    }

    /**
     * Sparkle spawning from Sign_Spin.
     * ROM: subq.w #1,sparkletime(a0); bpl.s .fail;
     *      move.w #$B,sparkletime(a0)
     *      Spawns ring sparkle at positions from Sign_SparkPos table.
     */
    private void updateSparkles() {
        sparkleTimer--;
        if (sparkleTimer < 0) {
            sparkleTimer = SPARKLE_SPAWN_DELAY;

            int[] offset = SPARKLE_POSITIONS[sparkleIndex];
            final int sparkleX = spawn.x() + offset[0];
            final int sparkleY = spawn.y() + offset[1];

            if (services().objectManager() != null) {
                spawnFreeChild(() -> new SignpostSparkleObjectInstance(sparkleX, sparkleY));
            }

            // ROM: addq.b #2,sparkle_id(a0); andi.b #$E,sparkle_id(a0)
            // Increments by 2 and masks to $E => indices 0,2,4,6,0,2...
            // But Sign_SparkPos is accessed as byte pairs, so effective index cycles 0-7
            sparkleIndex = (sparkleIndex + 1) % SPARKLE_POSITIONS.length;
        }
    }

    /**
     * Sign_SonicRun (routine 6): Lock player controls, make Sonic walk right.
     * <p>
     * ROM (without FixBugs):
     *   btst #1,(v_player+obStatus).w     - check if player is in air
     *   bne.s loc_EC70                     - if in air, skip control lock
     *   move.b #1,(f_lockctrl).w           - lock controls
     *   move.w #btnR<<8,(v_jpadhold2).w    - force right input
     * loc_EC70:
     *   move.w (v_player+obX).w,d0         - player center X
     *   move.w (v_limitright2).w,d1         - right scroll boundary (= camera X after lock)
     *   addi.w #$128,d1
     *   cmp.w d1,d0                         - player center >= limitright2 + $128?
     *   blo.s locret_ECEE
     *   addq.b #2,obRoutine(a0)            - advance to GotThroughAct
     */
    private void updateWalkOff(AbstractPlayableSprite player) {
        if (isRetainedGiantRingPlayerSstDeleted(player)) {
            // The native player SST has been cleared, so no later player-slot
            // animation dispatch can rewrite Flash_Collect's retained
            // id_Null byte. Keep the structural engine sprite aligned with
            // that absent-slot state while the signpost commits v_endcard.
            player.setAnimationId(Sonic1AnimationIds.NULL.id());
            player.setForcedAnimationId(Sonic1AnimationIds.NULL.id());
            triggerGotThroughAct(player);
            return;
        }

        // ROM re-applies control lock every frame while player is on the ground.
        // If player is in air, skip the control lock (original S1 behavior).
        if (!player.getAir()) {
            player.setForceInputRight(true);
            player.setControlLocked(true);
        }

        // Check if player has walked far enough off-screen
        // ROM: move.w (v_limitright2).w,d1; addi.w #$128,d1
        // v_limitright2 = camera maxX (level boundary, unchanged by lock)
        Camera camera = services().camera();
        if (camera != null && !resultsSpawned) {
            int rightLimit = camera.getMaxX() + WALK_OFF_OFFSET;
            if (player.getCentreX() >= rightLimit) {
                triggerGotThroughAct(player);
            }
        }
    }

    /**
     * Object 7C clears the Sonic SST after collecting a giant ring. The engine
     * retains the structural sprite, so this conjunction is the S1-owned
     * equivalent of Sign_SonicRun's {@code tst.b (v_player+obID)} reading zero.
     */
    private boolean isRetainedGiantRingPlayerSstDeleted(AbstractPlayableSprite player) {
        var gameState = services().gameState();
        return gameState != null
                && gameState.isBigRingCollected()
                && !player.isNativeSlotPresent();
    }

    /**
     * GotThroughAct subroutine from the disassembly.
     * ROM: clr.b (v_invinc).w; clr.b (f_timecount).w;
     *      move.b #id_GotThroughCard,(v_endcard).w;
     *      move.w #bgm_GotThrough,d0; jsr (QueueSound2).l
     */
    private void triggerGotThroughAct(AbstractPlayableSprite player) {
        var levelGamestate = services().levelGamestate();
        final int elapsedSeconds = levelGamestate != null ? levelGamestate.getElapsedSeconds() : 0;
        final int ringCount = player.getRingCount();
        final int actNumber = services().currentAct() + 1; // 1-indexed for display
        final boolean specialStageAfter = services().gameState() != null
                && services().gameState().isBigRingCollected();
        Sonic1FixedEndCardSlot.ClaimResult claim = Sonic1FixedEndCardSlot.claim(
                services(),
                new Sonic1FixedEndCardSlot.ResultsData(
                        elapsedSeconds, ringCount, actNumber, specialStageAfter));
        Sonic1ResultsScreenObjectInstance card = claim.requireCard();
        if (claim.state() == Sonic1FixedEndCardSlot.ClaimState.EXISTING_COMMITTED) {
            resultsSpawned = true;
            routineState = STATE_COMPLETE;
            return;
        }
        if (!queueResultsPlc()) {
            return;
        }
        card.markResultsPlcCommitted();
        resultsSpawned = true;
        routineState = STATE_COMPLETE;
        LOGGER.info("S1 Player off-screen, triggering GotThroughAct");

        // ROM: clr.b (v_invinc).w - disable invincibility
        player.setInvincibleFrames(0);

        // ROM: move.w #bgm_GotThrough,d0; jsr (QueueSound2).l
        try {
            services().playMusic(GameMusic.ACT_CLEAR);
        } catch (Exception e) {
            LOGGER.warning("Failed to play stage clear music: " + e.getMessage());
        }

        LOGGER.info("S1 Results screen committed in fixed v_endcard slot");
    }

    private boolean queueResultsPlc() {
        try {
            Sonic1PlcService plc = services().gameModule().getGameService(Sonic1PlcService.class);
            if (plc != null) plc.replaceQueued(16);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getSignpostRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(currentFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        // From disassembly: move.b #4,obPriority(a0)
        return RenderPriority.clamp(4);
    }

    public int getCenterX() {
        return spawn.x();
    }

    public int getCenterY() {
        return spawn.y();
    }
}
