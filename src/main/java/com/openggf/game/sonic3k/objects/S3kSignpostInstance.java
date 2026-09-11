package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.GameRng;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.camera.Camera;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * S3K end-of-act signpost (falls from sky after miniboss defeat).
 *
 * <p>ROM: Obj_Signpost (sonic3k.asm) — falling sign with spin animation,
 * bump-from-below mechanic, and hidden monitor interaction.
 *
 * <p>State machine: INIT -> FALLING -> LANDED -> RESULTS -> AFTER
 */
public class S3kSignpostInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(S3kSignpostInstance.class.getName());

    // ---- State machine ----
    private enum State { INIT, FALLING, LANDED, RESULTS, AFTER }

    /**
     * Engine-only timing adjustment retained until MGZ's real missing ROM
     * owner is identified. This does not describe native SST allocation:
     * captured MGZ and HCZ results children both allocate into lower slot 8
     * and begin on the next object pass.
     */
    enum ResultsChildTimingAdjustment {
        NONE(0),
        UNSUPPORTED_GROUNDED_COMPENSATION(1);

        private final int catchUpEntries;

        ResultsChildTimingAdjustment(int catchUpEntries) {
            this.catchUpEntries = catchUpEntries;
        }

        int catchUpEntries() {
            return catchUpEntries;
        }
    }

    private State state = State.INIT;

    // ---- Physics (pixel-level velocities, fixed-point 8.8 where noted) ----
    private int xVel;
    private int yVel;
    private int worldX;
    private int worldY;

    /** Subpixel accumulators for fractional movement (lower 8 bits). */
    private int subX;
    private int subY;

    // ---- Signpost flags ----
    private boolean landed;
    private int postLandTimer;
    private int bumpCooldown;

    // ---- Animation ----
    private int animFrame;
    private int animIndex;
    private int animTimer;

    /**
     * ROM-accurate spin animation sequences.
     * Default (Sonic/Tails): Eggman -> spin -> Tails -> spin -> face -> spin, loop.
     * Knuckles: Tails -> spin -> Knux -> spin -> face -> spin, loop.
     */
    private static final int[] ANIM_SONIC = {0, 4, 5, 6, 1, 4, 5, 6, 3, 4, 5, 6};
    private static final int[] ANIM_KNUCKLES = {1, 4, 5, 6, 2, 4, 5, 6, 3, 4, 5, 6};

    /**
     * Face frame lookup indexed by PlayerCharacter ordinal.
     * 0=SONIC_AND_TAILS -> Sonic face (0), 1=SONIC_ALONE -> Sonic face (0),
     * 2=TAILS_ALONE -> Tails face (1), 3=KNUCKLES -> Knuckles face (2).
     */
    private static final int[] FACE_FRAMES = {0, 0, 1, 2};

    private static final int GRAVITY = 0x0C;
    private static final int Y_RADIUS = 0x1E;
    private static final int ANIM_FRAME_DELAY = 2;
    private static final int SPARKLE_INTERVAL = 4;
    private static final int POST_LAND_TIMER = 0x40;
    private static final int BUMP_COOLDOWN = 0x20;
    private static final int RESULTS_CARRIED_RETIRE_DISPATCHES = 3;
    // Results children are embedded in the engine owner rather than allocated
    // as twelve later SSTs. The embedded render-flag retire pass already
    // represents the native child-slot deletes, so a post-object signpost must
    // not add another synthetic parent pass before Obj_TitleCardInit
    // (docs/skdisasm/sonic3k.asm:62600, 62691-62734).
    private static final int RESULTS_POST_OBJECT_RETIRE_DISPATCHES = 0;
    // A signpost that waits for the player to land still has one native parent
    // pass after its embedded child retirement.
    private static final int RESULTS_WAITED_LANDING_RETIRE_DISPATCHES = 1;

    // Bump detection box relative to signpost center
    private static final int BUMP_LEFT = -0x20;
    private static final int BUMP_RIGHT = 0x20;
    private static final int BUMP_TOP = -0x18;
    private static final int BUMP_BOTTOM = 0x18;

    // Wall bounce margins relative to camera
    private static final int WALL_RIGHT_MARGIN = 0x128;
    private static final int WALL_LEFT_MARGIN = 0x18;

    // Landing Y threshold relative to camera
    private static final int LAND_Y_THRESHOLD = 0x50;
    private static final int AFTER_X_RANGE = 0x280;
    private static final int AFTER_Y_BIAS = 0x80;
    private static final int AFTER_Y_RANGE = 0x200;

    private int[] animSequence;

    /**
     * ROM's Apparent_act — display-only act number, not affected by seamless reloads.
     * Non-final so the generic field capturer reapplies it after a rewind recreate
     * (the signpost's spawn is null, so the recreate hook uses a placeholder).
     */
    private int apparentAct;
    private int resultsTimerCatchUpEntries;
    private int resultsWaitDurationAdjustment;
    private int resultsPostControlHandoffDelayEntries;
    private boolean resultsWaitedForPlayerLanding;
    private boolean mainEndingPosePending;
    private boolean sidekickEndingPoseApplied;
    private boolean sidekickEndingPoseCheckArmed;
    private boolean landingSparklePending;
    private boolean bumpedFromBelow;
    private boolean preservesPostLandingSparkleGate;
    private boolean preservesPostObjectResultDispatchBoundary;
    private boolean preservesGroundedResultsDispatchBoundary;
    private boolean usesShortResultsChildRetireTail;
    private int nativeControlSlot = -1;

    /**
     * Creates the signpost at the given X position.
     * Y is set to above the camera in INIT state.
     *
     * @param spawnX      world X position for the signpost
     * @param apparentAct ROM's Apparent_act (0 = act 1 display, 1 = act 2 display)
     */
    public S3kSignpostInstance(int spawnX, int apparentAct) {
        this(spawnX, apparentAct, 0, 0, 0);
    }

    /**
     * Creates an end sign whose native post-object screen-event allocation
     * leaves one final sparkle gate visible after the engine's landing pass.
     */
    public S3kSignpostInstance(int spawnX, int apparentAct,
            boolean preservesPostLandingSparkleGate) {
        this(spawnX, apparentAct);
        this.preservesPostLandingSparkleGate = preservesPostLandingSparkleGate;
        this.preservesPostObjectResultDispatchBoundary = preservesPostLandingSparkleGate;
    }

    S3kSignpostInstance(int spawnX, int apparentAct, int resultsTimerCatchUpEntries,
            int resultsWaitDurationAdjustment, int resultsPostControlHandoffDelayEntries) {
        this(spawnX, apparentAct, resultsTimerCatchUpEntries, resultsWaitDurationAdjustment,
                resultsPostControlHandoffDelayEntries, false);
    }

    S3kSignpostInstance(int spawnX, int apparentAct, int resultsTimerCatchUpEntries,
            int resultsWaitDurationAdjustment, int resultsPostControlHandoffDelayEntries,
            boolean preservesGroundedResultsDispatchBoundary) {
        this(spawnX, apparentAct, resultsTimerCatchUpEntries, resultsWaitDurationAdjustment,
                resultsPostControlHandoffDelayEntries,
                preservesGroundedResultsDispatchBoundary, false);
    }

    S3kSignpostInstance(int spawnX, int apparentAct, int resultsTimerCatchUpEntries,
            int resultsWaitDurationAdjustment, int resultsPostControlHandoffDelayEntries,
            boolean preservesGroundedResultsDispatchBoundary,
            boolean usesShortResultsChildRetireTail) {
        super(null, "S3kSignpost");
        this.worldX = spawnX;
        this.worldY = 0; // Set properly in INIT
        this.apparentAct = apparentAct;
        this.resultsTimerCatchUpEntries = Math.max(0, resultsTimerCatchUpEntries);
        this.resultsWaitDurationAdjustment = Math.max(0, resultsWaitDurationAdjustment);
        this.resultsPostControlHandoffDelayEntries = Math.max(0, resultsPostControlHandoffDelayEntries);
        this.preservesGroundedResultsDispatchBoundary = preservesGroundedResultsDispatchBoundary;
        this.usesShortResultsChildRetireTail = usesShortResultsChildRetireTail;
    }

    private S3kSignpostInstance() {
        this(0, 0);
    }

    void preserveNativeControlAllocationBoundary(int slot) {
        nativeControlSlot = slot;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        int spawnX = ctx.spawn() != null ? ctx.spawn().x() : 0;
        return new S3kSignpostInstance(spawnX, 0);
    }

    @Override
    public int getX() {
        return worldX;
    }

    @Override
    public int getY() {
        return worldY;
    }

    public boolean isLanded() {
        return landed;
    }

    public void setLanded(boolean landed) {
        this.landed = landed;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    public static S3kSignpostInstance activeSignpost(ObjectManager objectManager) {
        if (objectManager == null) {
            return null;
        }
        return objectManager.activeObjectsOfType(S3kSignpostInstance.class).stream()
                .filter(signpost -> !signpost.isDestroyed())
                .findFirst()
                .orElse(null);
    }

    /**
     * ROM: Offset_ObjectsDuringTransition shifts Obj_EndSign's position by the
     * same delta as the players/camera during a seamless act reload (e.g. CNZ
     * (-$3000, +$200)), so the signpost stays on screen and in Obj_EndSignAfter
     * after the Act 1 -> Act 2 transition instead of being stranded at its old
     * Act 1 world position (docs/skdisasm/sonic3k.asm:176262-176279, CNZ1BGE_DoTransition).
     */
    @Override
    public void onCarriedAcrossSeamlessTransition(int offsetX, int offsetY) {
        worldX += offsetX;
        worldY += offsetY;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = resolveUpdatePlayer(playerEntity);
        if (isDestroyed()) {
            return;
        }
        if (landingSparklePending && isRomSparkleFrame(vIntRunCount)) {
            spawnRomSparkle();
            landingSparklePending = false;
        }

        switch (state) {
            case INIT -> updateInit(player);
            case FALLING -> updateFalling(vIntRunCount, player);
            case LANDED -> updateLanded(player);
            case RESULTS -> updateResults(player);
            case AFTER -> updateAfter(player);
        }
    }

    // =========================================================================
    // INIT
    // =========================================================================

    private void updateInit(AbstractPlayableSprite player) {
        var camera = services().camera();
        worldY = camera.getY() - 0x20;

        // Select animation based on player character
        PlayerCharacter pc = getPlayerCharacter();
        animSequence = (pc == PlayerCharacter.KNUCKLES) ? ANIM_KNUCKLES : ANIM_SONIC;
        animIndex = 0;
        animFrame = animSequence[0];
        animTimer = 0;

        try {
            services().playSfx(Sonic3kSfx.SIGNPOST.id);
        } catch (Exception e) {
            LOG.fine("Could not play signpost SFX: " + e.getMessage());
        }

        // Spawn the stub/post child
        spawnDynamicObject(new S3kSignpostStubChild(this));

        state = State.FALLING;
        LOG.fine("S3K Signpost INIT -> FALLING at X=" + worldX + " Y=" + worldY);
    }

    // =========================================================================
    // FALLING
    // =========================================================================

    private void updateFalling(int vIntRunCount, AbstractPlayableSprite player) {
        // ROM Obj_EndSignFall owns interaction before gravity and MoveSprite2:
        // sparkle -> EndSign_CheckPlayerHit -> addi #$C,y_vel -> movement.
        if (isRomSparkleFrame(vIntRunCount)) {
            spawnRomSparkle();
        }
        if (romBumpCheckAvailableAfterCooldownEntry(bumpCooldown)) {
            checkBumpFromBelow(player);
        } else {
            bumpCooldown--;
        }

        yVel = romVelocityAfterGravity(yVel);

        // Move (8.8 fixed-point accumulation)
        subX += xVel;
        worldX += subX >> 8;
        subX &= 0xFF;

        subY += yVel;
        worldY += subY >> 8;
        subY &= 0xFF;

        // Wall bounce
        var camera = services().camera();
        int camX = camera.getX();
        if (worldX > camX + WALL_RIGHT_MARGIN) {
            xVel = -Math.abs(xVel);
        } else if (worldX < camX + WALL_LEFT_MARGIN) {
            xVel = Math.abs(xVel);
        }

        // Animate spin
        advanceAnimation();

        // Landing check — use terrain collision (ROM: ObjCheckFloorDist)
        // Only check when moving downward and past the minimum camera-relative Y
        if (yVel > 0 && worldY >= camera.getY() + LAND_Y_THRESHOLD) {
            TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(worldX, worldY, Y_RADIUS);
            if (floor.distance() < 0) {
                // Snap to floor surface
                worldY += floor.distance();
            } else {
                return; // No floor contact yet — keep falling
            }
            landed = true;
            postLandTimer = initialPostLandTimer(
                    resultsTimerCatchUpEntries,
                    preservesPostObjectResultDispatchBoundary,
                    bumpedFromBelow);
            // ROM loc_838AA (docs/skdisasm/sonic3k.asm:176191-176194) is the whole
            // landing branch: it writes routine, $38 bit 0 and the $40 timer, and
            // touches NEITHER velocity nor either sub-pixel. The velocities are
            // cleared only later, by loc_838D6 when the post-land timer expires
            // (:176209-176218) -- which this class already does at that transition.
            //
            // Clearing x_vel here broke the hidden-monitor re-bounce. loc_838FA
            // (:176222-176227) resumes routine 2 with `move.b #$20,$20(a0)` and
            // `move.w #-$200,y_vel(a0)` only, so the signpost carries its
            // PRE-LANDING x_vel back into the air and keeps drifting sideways.
            // With x_vel zeroed the engine's signpost hung motionless in x after
            // the bounce while the ROM's drifted, and the two were ~40px apart by
            // the time the player's next bump-box test ran.
            state = State.LANDED;
            landingSparklePending = preservesPostLandingSparkleGate
                    && isRomSparkleFrame(vIntRunCount + 1);
            LOG.fine("S3K Signpost FALLING -> LANDED at Y=" + worldY);
        }
    }

    private void spawnRomSparkle() {
        spawnDynamicObject(new S3kSignpostSparkleChild(
                worldX, worldY + romSparkleYOffset(services().rng())));
    }

    /**
     * ROM {@code loc_839B8}: every signpost sparkle consumes one random word
     * and applies {@code (d0 & $1F) - $10} to its initial Y position.
     */
    static int romSparkleYOffset(GameRng rng) {
        return rng.nextBits(0x1F) - 0x10;
    }

    static boolean isRomSparkleFrame(int frameCounter) {
        return (frameCounter & (SPARKLE_INTERVAL - 1)) == 0;
    }

    static int romVelocityAfterGravity(int velocity) {
        return (short) (velocity + GRAVITY);
    }

    static boolean romBumpCheckAvailableAfterCooldownEntry(int cooldown) {
        return (cooldown & 0xFF) == 0;
    }

    /**
     * ROM: Signpost bump-from-below mechanic.
     * Player must be in animation #2 and moving upward, and within the bump
     * detection box.
     */
    private void checkBumpFromBelow(AbstractPlayableSprite player) {
        if (player == null || bumpCooldown > 0) {
            return;
        }

        // ROM EndSign_CheckPlayerHit range-tests both players once, then calls
        // sub_83A70 for Player 1 and afterwards for Player 2
        // (docs/skdisasm/sonic3k.asm:176347-176371, 176376-176397).
        //
        // FixBugs conditional at docs/skdisasm/sonic3k.asm:176357-176365. The
        // engine takes the FixBugs = 0 branch, which is what the shipped ROM
        // does. The fixed branch would push/pop d0 around the Player 1 call so
        // that the following `swap d0 / tst.w d0` still holds Player 2's
        // address; the un-fixed branch does not, and d0 is destroyed whenever
        // Player 1 actually scores a bump. It is destroyed by a route that is
        // easy to miss: sub_83A70 ends in `jmp (HUD_AddToScore).l`, a TAIL
        // jump, so HUD_AddToScore's own rts consumes the return address that
        // `bsr.w sub_83A70` pushed and returns straight to loc_83A6A -- with
        // whatever HUD_AddToScore left in d0.
        //
        // HUD_AddToScore has TWO exits and both leave the same thing in d0's
        // high word, which is the only half the following `swap d0 / tst.w d0`
        // can see (:17645-17665):
        //   - the ordinary `.end` exit returns after `move.l (a3),d0`, so d0 is
        //     the whole 32-bit Score;
        //   - the extra-life exit does `move.w #mus_ExtraLife,d0` and tail-jumps
        //     to Play_Music, which writes only `d0.b` into Z80 RAM and touches
        //     no register (:1471-1475; its stopZ80/startZ80 macros are pure
        //     memory writes, sonic3k.macros.asm:94-103). That `move.w` replaces
        //     only the LOW word, so the high word is still the Score's.
        // The extra-life exit is reachable in an ordinary run, not hypothetical,
        // so covering it matters: `swap d0` moves mus_ExtraLife into the high
        // word where `tst.w` cannot see it, and both exits therefore present the
        // Score's high word to the test.
        //
        // So on a frame where Player 1 bumps, the Player 2 test reads the HIGH
        // WORD of the score instead of Tails' address, and Player 2 can never
        // bump. Note what this does and does not make deterministic: the VALUE
        // varies from run to run because it is a function of the live score, but
        // the OUTCOME does not, because every value it can take is rejected
        // below. That residual branch is provably inert rather than merely
        // unlikely: Score is capped at 999999 = $F423F (:17649-17652), so the
        // swapped value is 0..$F. Zero returns immediately. For 1..$F the ROM
        // does `movea.w d0,a1` and reads `anim(a1)` = $20(a1) and then
        // `y_vel(a1)` = $1A(a1) out of the 68000 vector table at the bottom of
        // the ROM. In the locked-on ROM those bytes are $00 at every odd a1 and
        // $02 at a1 = 2, 6, $A, $E -- and at all four of those the y_vel word is
        // $0000, so `tst.w y_vel(a1) / bpl.s locret_83ABC` always returns. The
        // wild read therefore never reaches the bump body, and modelling it as
        // "no Player 2 bump this frame" is exact, not an approximation.
        //
        // Consequence for behaviour: at most ONE player bumps the signpost per
        // frame, and it is Player 1 whenever Player 1 qualifies. A same-frame
        // Player 2 bump is only reachable when Player 1 did not bump -- either
        // out of range, or in range but failing sub_83A70's animation/velocity
        // test, both of which return with d0 intact.
        boolean bumpedThisFrame = false;
        for (PlayableEntity candidate : playerQuery(player).playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (bumpedThisFrame) {
                break;
            }
            if (candidate instanceof AbstractPlayableSprite sprite && isRomBumpCandidate(worldX, worldY, sprite)) {
                applyRomBumpFromBelow(sprite);
                bumpedThisFrame = true;
            }
        }
    }

    private void applyRomBumpFromBelow(AbstractPlayableSprite player) {
        int kickX = romBumpXVelocity(worldX, player.getCentreX());
        // xVel/yVel are 8.8 fixed-point
        xVel = kickX;
        yVel = -0x200;
        bumpedFromBelow = true;

        try {
            services().playSfx(Sonic3kSfx.SIGNPOST.id);
        } catch (Exception e) {
            LOG.fine("Could not play signpost bump SFX: " + e.getMessage());
        }

        services().gameState().addScore(100);
        bumpCooldown = BUMP_COOLDOWN;
        LOG.fine("S3K Signpost bumped! xVel=" + xVel);
    }

    static boolean isRomBumpCandidate(int signpostX, int signpostY, AbstractPlayableSprite player) {
        if (!hasRomBumpPose(player)) {
            return false;
        }
        int dx = player.getCentreX() - signpostX;
        int dy = player.getCentreY() - signpostY;
        return dx >= BUMP_LEFT && dx < BUMP_RIGHT && dy >= BUMP_TOP && dy < BUMP_BOTTOM;
    }

    static int romBumpXVelocity(int signpostX, int playerX) {
        // ROM sub_83A70 (docs/skdisasm/sonic3k.asm:176381-176390):
        //   move.w x_pos(a0),d0
        //   sub.w  x_pos(a1),d0
        //   bne.s  loc_83A92
        //   moveq  #8,d0
        // loc_83A92:
        //   lsl.w  #4,d0
        //   move.w d0,x_vel(a0)
        // The #8 substitution happens BEFORE the shift, so an exactly aligned
        // player produces x_vel = 8 << 4 = $80, not 8. Substituting after the
        // shift understated the kick by a factor of 16 on alignment frames.
        int delta = (short) (signpostX - playerX);
        if (delta == 0) {
            delta = 8;
        }
        return (short) (delta << 4);
    }

    static boolean hasRomBumpPose(AbstractPlayableSprite player) {
        // ROM sub_83A70 only accepts anim(a1)==#2 and upward y_vel(a1);
        // it does not test Status_InAir (docs/skdisasm/sonic3k.asm:176372-176387).
        return player != null
                && player.getAnimationId() == Sonic3kAnimationIds.ROLL.id()
                && player.getYSpeed() < 0;
    }

    // =========================================================================
    // LANDED
    // =========================================================================

    private void updateLanded(AbstractPlayableSprite player) {
        // If a hidden monitor cleared our landed flag, bounce back up
        if (!landed) {
            yVel = -0x200;
            bumpCooldown = BUMP_COOLDOWN;
            state = State.FALLING;
            LOG.fine("S3K Signpost LANDED -> FALLING (hidden monitor bounce)");
            return;
        }

        // Continue spin animation during post-land timer
        advanceAnimation();

        postLandTimer--;
        if (romPostLandTimerExpired(postLandTimer)) {
            // Show final face frame
            PlayerCharacter pc = getPlayerCharacter();
            animFrame = FACE_FRAMES[pc.ordinal()];
            xVel = 0;
            yVel = 0;
            // loc_838D6 runs in the signpost's later object slot and writes
            // Ctrl_2_locked=$FF. Tails_Control therefore skips Tails_CPU_Control
            // beginning with the next player slot while retaining the last
            // Ctrl_2_logical word for ordinary movement.
            for (PlayableEntity candidate : playerQuery(player)
                    .playersFor(ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED)) {
                if (candidate instanceof AbstractPlayableSprite sprite && sprite != player) {
                    applySidekickInputLock(sprite);
                }
            }
            state = State.RESULTS;
            LOG.fine("S3K Signpost LANDED -> RESULTS");
        }
    }

    static boolean romPostLandTimerExpired(int timerAfterDecrement) {
        // Obj_EndSignLanded uses subq.w #1,$2E(a0); bmi.s, so $0000 is still
        // a waiting frame and only $FFFF advances (docs/skdisasm/sonic3k.asm:176198-176208).
        return (short) timerAfterDecrement < 0;
    }

    static int initialPostLandTimer(int configuredCatchUpEntries,
            boolean postObjectAllocationBoundary, boolean bumpedFromBelow) {
        // CNZ's post-object screen event publishes Obj_EndSign after the
        // engine object walk, so an unbumped sign has already consumed one
        // native falling/countdown boundary by the time the engine lands it.
        // A real EndSign_CheckPlayerHit bounce re-phases the falling owner and
        // retains the full $40 countdown instead (sonic3k.asm:176225-176253,
        // 176342-176387, 107590-107601).
        int nativeAllocationCatchUp = postObjectAllocationBoundary && !bumpedFromBelow ? 1 : 0;
        return Math.max(0, POST_LAND_TIMER - configuredCatchUpEntries - nativeAllocationCatchUp);
    }

    // =========================================================================
    // RESULTS
    // =========================================================================

    private void updateResults(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // Wait for player to be on the ground
        if (player.getAir()) {
            resultsWaitedForPlayerLanding = true;
            return;
        }

        boolean sidekickPoseWasAlreadyArmed = sidekickEndingPoseCheckArmed;
        boolean preservesRoutineSixDispatch = resultsWaitedForPlayerLanding
                || preservesPostObjectResultDispatchBoundary
                || preservesGroundedResultsDispatchBoundary;
        if (preservesRoutineSixDispatch || !usesShortResultsChildRetireTail) {
            // Obj_EndSignResults calls Set_PlayerEndingPose in its routine-6
            // dispatch, immediately after the grounded-player check.  The
            // following routine-8 dispatch owns only the sidekick handoff.
            applyMainPlayerEndingPose(player);
            sidekickEndingPoseCheckArmed = true;
        } else {
            // The short-tail owner retains the native post-object handoff:
            // its result owner becomes visible before the signpost's routine-8
            // pose work reaches the player slots.
            mainEndingPosePending = true;
        }

        // ROM Obj_EndSignLanded writes only Ctrl_2_locked before this routine;
        // Obj_EndSignResults calls Set_PlayerEndingPose with a1=Player_1 only
        // (sonic3k.asm:176198-176218,176229-176238). Tails keeps executing his
        // CPU movement until Obj_LevelResults' later Check_TailsEndPose path.
        for (PlayableEntity candidate : playerQuery(player)
                .playersFor(ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED)) {
            if (candidate instanceof AbstractPlayableSprite sprite) {
                if (sprite == player) {
                    continue;
                }
                applySidekickInputLock(sprite);
            }
        }

        // Spawn the results screen — pass apparentAct (ROM's Apparent_act), not
        // LevelManager.getCurrentAct(). AIZ reloads act 2 resources mid-level which
        // changes LevelManager.currentAct to 1, but Apparent_act stays 0 until results exit.
        if (services().gameState() != null) {
            services().gameState().setEndOfLevelActive(true);
        }
        ResultsChildTimingAdjustment resultsChildTimingAdjustment = resultsChildTimingAdjustment(
                resultsWaitedForPlayerLanding,
                preservesPostObjectResultDispatchBoundary,
                preservesGroundedResultsDispatchBoundary);
        Supplier<S3kResultsScreenObjectInstance> resultsFactory = () -> new S3kResultsScreenObjectInstance(
                getPlayerCharacter(), apparentAct, resultsWaitDurationAdjustment,
                resultsPostControlHandoffDelayEntries
                        + (preservesPostObjectResultDispatchBoundary ? 1 : 0),
                resultsChildRetireDispatches(resultsWaitedForPlayerLanding,
                        preservesPostObjectResultDispatchBoundary,
                        usesShortResultsChildRetireTail),
                resultsChildTimingAdjustment,
                usesShortResultsChildRetireTail);
        // Obj_EndSignResults calls AllocateObject.  Its lower free slot has
        // already passed in the current Process_Sprites walk, so the results
        // owner is published now but its Obj_LevelResultsInit dispatch starts
        // on the following pass, matching the native owner contract.
        if (useFirstFreeResultsOwner()) {
            spawnFreeChild(resultsFactory);
        } else {
            spawnChild(resultsFactory);
        }
        LOG.fine("S3K Signpost RESULTS -> AFTER (results instance spawned)");
        state = State.AFTER;
        if (preservesPostObjectResultDispatchBoundary && sidekickPoseWasAlreadyArmed) {
            applyNativeSidekickEndingPose(player);
        }
    }

    private boolean useFirstFreeResultsOwner() {
        int firstFreeSlot = services().objectManager() == null
                ? -1
                : services().objectManager().firstFreeDynamicSlot();
        return usesFirstFreeResultsOwner(
                resultsWaitedForPlayerLanding,
                preservesGroundedResultsDispatchBoundary,
                usesShortResultsChildRetireTail,
                firstFreeSlot,
                nativeControlSlot);
    }

    static boolean usesFirstFreeResultsOwner(boolean waitedForPlayerLanding,
            boolean preservesGroundedBoundary, boolean usesShortRetireTail,
            int firstFreeSlot, int nativeControlSlot) {
        // Obj_EndSignControl remains in the defeated boss's SST in the ROM.
        // The Java flow/sign split can sit later in the pool, so compare
        // FindFreeObj with that retained native owner boundary: a free slot
        // after it must stay on the forward, same-pass side of Process_Sprites.
        if (preservesGroundedBoundary && nativeControlSlot >= 0
                && firstFreeSlot > nativeControlSlot) {
            return false;
        }
        return preservesGroundedBoundary
                || (!waitedForPlayerLanding && !usesShortRetireTail);
    }

    static int resultsChildRetireDispatches(boolean waitedForPlayerLanding,
            boolean preservesPostObjectResultDispatchBoundary,
            boolean usesShortResultsChildRetireTail) {
        if (preservesPostObjectResultDispatchBoundary) {
            return RESULTS_POST_OBJECT_RETIRE_DISPATCHES;
        }
        return waitedForPlayerLanding || usesShortResultsChildRetireTail
                ? RESULTS_WAITED_LANDING_RETIRE_DISPATCHES
                : RESULTS_CARRIED_RETIRE_DISPATCHES;
    }

    static ResultsChildTimingAdjustment resultsChildTimingAdjustment(boolean waitedForPlayerLanding,
            boolean preservesPostObjectBoundary, boolean preservesGroundedOwnerBoundary) {
        return waitedForPlayerLanding || preservesPostObjectBoundary || preservesGroundedOwnerBoundary
                ? ResultsChildTimingAdjustment.NONE
                : ResultsChildTimingAdjustment.UNSUPPORTED_GROUNDED_COMPENSATION;
    }

    static void applySidekickInputLock(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return;
        }
        sprite.setControlLocked(true);
        if (sprite.getCpuController() != null) {
            sprite.getCpuController().setController2SignedLocked(true);
        }
    }

    static void applySidekickEndingPose(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return;
        }
        // Check_TailsEndPose clears Ctrl_2_locked immediately before tail-calling
        // Set_PlayerEndingPose (sonic3k.asm:181919-181940).
        sprite.setControlLocked(false);
        if (sprite.getCpuController() != null) {
            sprite.getCpuController().setController2SignedLocked(false);
        }
        applyMainPlayerEndingPose(sprite);
    }

    static void applyMainPlayerEndingPose(AbstractPlayableSprite sprite) {
        if (sprite == null) {
            return;
        }
        // Set_PlayerEndingPose writes object_control=$81, victory animation,
        // clears spin_dash_flag / Status_Push, and zeroes velocities, but does
        // not set Ctrl_1_locked
        // (docs/skdisasm/sonic3k.asm:181977-181988). Obj_EndSignLanded only
        // locks Ctrl_2 (docs/skdisasm/sonic3k.asm:176198-176218), so Sonic
        // keeps copying raw Ctrl_1 into Ctrl_1_logical while object_control
        // freezes movement; Sonic_RecordPos then stores that live input for
        // Tails' delayed follow history (docs/skdisasm/sonic3k.asm:21541-21545,
        // 22119-22136).
        ObjectControlState.nativeBit7FullControl().applyTo(sprite);
        sprite.setSpindash(false);
        sprite.setPushing(false);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAnimationId(Sonic3kAnimationIds.VICTORY);
    }

    private ObjectPlayerQuery playerQuery(PlayableEntity updatePlayer) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> updatePlayer, query::sidekicks);
    }

    private AbstractPlayableSprite resolveUpdatePlayer(PlayableEntity playerEntity) {
        if (playerEntity instanceof AbstractPlayableSprite sprite) {
            return sprite;
        }
        PlayableEntity queriedPlayer = services().playerQuery().mainPlayerOrNull();
        return queriedPlayer instanceof AbstractPlayableSprite sprite ? sprite : null;
    }

    // =========================================================================
    // AFTER
    // =========================================================================

    private void updateAfter(AbstractPlayableSprite player) {
        if (mainEndingPosePending) {
            mainEndingPosePending = false;
            applyMainPlayerEndingPose(player);
        }
        applyNativeSidekickEndingPose(player);
        if (isResultsScreenActive()) {
            return;
        }

        Camera camera = services().camera();
        if (camera != null && !isWithinRomAfterRange(worldX, worldY, camera.getX(), camera.getY())) {
            setDestroyed(true);
            LOG.fine("S3K Signpost destroyed (off-screen)");
        }
    }

    /**
     * Ends the deferred ending-pose tail when the retained native
     * {@code Obj_EndSignControl} owner restores control after the results
     * owner publishes its next routine. The engine keeps the signpost's
     * routine-8 work in this object, so without consuming both pending flags
     * it can reapply the victory pose after {@code Restore_PlayerControl} in
     * the same object pass (docs/skdisasm/sonic3k.asm:176229-176272,
     * 180437-180451).
     */
    void completeNativeResultsControlRestore() {
        mainEndingPosePending = false;
        sidekickEndingPoseApplied = true;
    }

    private void applyNativeSidekickEndingPose(AbstractPlayableSprite player) {
        if (sidekickEndingPoseApplied) {
            return;
        }
        if (!sidekickEndingPoseCheckArmed) {
            // The ROM's routine-6 dispatch allocates Obj_LevelResults and returns;
            // Check_TailsEndPose belongs to the later routine-8 dispatch. Keep
            // that distinct entry boundary even though the engine models the
            // results allocation as a free child (sonic3k.asm:176229-176272).
            sidekickEndingPoseCheckArmed = true;
            return;
        }
        PlayableEntity candidate = playerQuery(player).nativeP2OrNull();
        if (candidate instanceof AbstractPlayableSprite sidekick
                && !sidekick.getDead()
                && !sidekick.getAir()) {
            sidekickEndingPoseApplied = true;
            applySidekickEndingPose(sidekick);
        }
    }

    private boolean isResultsScreenActive() {
        return services().gameState() != null && services().gameState().isEndOfLevelActive();
    }

    static boolean isWithinRomAfterRange(int signpostX, int signpostY, int cameraX, int cameraY) {
        int dx = ((signpostX & 0xFF80) - ((cameraX - 0x80) & 0xFF80)) & 0xFFFF;
        if (dx > AFTER_X_RANGE) {
            return false;
        }
        int dy = (signpostY - cameraY + AFTER_Y_BIAS) & 0xFFFF;
        return dy <= AFTER_Y_RANGE;
    }

    // =========================================================================
    // Animation
    // =========================================================================

    private void advanceAnimation() {
        animTimer++;
        if (animTimer >= ANIM_FRAME_DELAY) {
            animTimer = 0;
            animIndex++;
            if (animIndex >= animSequence.length) {
                animIndex = 0;
            }
            animFrame = animSequence[animIndex];
        }
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getEndSignRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(animFrame, worldX, worldY, false, false);
    }

    private PatternSpriteRenderer getEndSignRenderer() {
        try {
            var renderManager = services().renderManager();
            if (renderManager != null) {
                return renderManager.getRenderer(Sonic3kObjectArtKeys.END_SIGN);
            }
        } catch (Exception e) {
            LOG.fine(() -> "S3kSignpostInstance.getEndSignRenderer: " + e.getMessage());
        }
        return null;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private PlayerCharacter getPlayerCharacter() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration());
    }

    public int getWorldX() {
        return worldX;
    }

    public int getWorldY() {
        return worldY;
    }

    @Override
    public String traceDebugDetails() {
        return String.format(
                "state=%s x=%04X y=%04X sub=%02X,%02X vel=%04X,%04X landed=%b timer=%04X anim=%02X/%02X",
                state,
                worldX & 0xFFFF,
                worldY & 0xFFFF,
                subX & 0xFF,
                subY & 0xFF,
                xVel & 0xFFFF,
                yVel & 0xFFFF,
                landed,
                postLandTimer & 0xFFFF,
                animFrame & 0xFF,
                animIndex & 0xFF);
    }
}
