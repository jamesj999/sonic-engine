package com.openggf.game.sonic3k.objects;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindStateful;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractMonitorObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectControlledSolidContactController;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.Direction;
import com.openggf.physics.FrameCollisionPlan;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.NativePositionOps;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Object 0x5B — MGZ Top Platform / Top Launcher.
 *
 * <p>ROM: {@code Obj_MGZTopPlatform} (sonic3k.asm:71475-72040).
 *
 * <p>Full port of the platform's state machines:
 * <ul>
 *   <li>{@code sub_34EEC} — per-player state machine with states 0 (landing
 *       detection), 2 (approach), 4 (grabbed), 6 (post-release).</li>
 *   <li>Airborne body (loc_34C98): gravity + MoveSprite2 + ground probe
 *       (sub_3526A). Airborne bit is only cleared when the floor probe
 *       actually snaps, not on every contact.</li>
 *   <li>Ground-ride (loc_34D1E): angle-driven {@code x_vel/y_vel} from
 *       {@code ground_vel}, wall probe, MoveSprite2, floor angle follow.</li>
 *   <li>Arc-travel (sub_35868): sine-interpolated teleport between waypoints
 *       read at {@code word_35784}/{@code word_357F6}.</li>
 *   <li>{@code sub_35202} post-sync: snaps grabbed player's X and Y to the
 *       platform after every frame's motion, preserving the ROM's ride feel.</li>
 * </ul>
 *
 * <p>Subtype != 0 spawns (from {@code Obj_MGZTopLauncher}, ID 0x5C) run the
 * solid collision + standing state machine but no autonomous motion.
 */
public class MGZTopPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, ObjectControlledSolidContactController,
        RomObjectCodePointerProvider, SpawnRewindRecreatable {
    private static final String ART_KEY = Sonic3kObjectArtKeys.MGZ_TOP_PLATFORM;

    // ROM: move.w #$280, priority(a0)
    private static final int PRIORITY_BUCKET = 5;

    // ROM: move.b #$18, width_pixels(a0); move.b #$C, height_pixels(a0)
    private static final int WIDTH_PIXELS = 0x18;
    private static final int HEIGHT_PIXELS = 0x0C;
    // ROM sub_3526A: airborne probe uses y_radius=$1F with a $13 y_pos offset (loc_34C98).
    private static final int AIRBORNE_Y_RADIUS = 0x1F;
    private static final int AIRBORNE_Y_OFFSET = 0x13;
    // ROM sub_34E6E / sub_3526A radii.
    private static final int GROUND_X_RADIUS = 0x0A;
    private static final int GROUND_Y_RADIUS = 0x0C;
    private static final int WALL_X_RADIUS = 0x18;
    private static final int WALL_Y_OFFSET = 0x0C;

    // Animation ROM: mapping_frame = ($24 >> 3) & 1
    private static final int ANIM_TIMER_SHIFT = 3;

    // Grab radius ROM: cmpi.w #$10, d0; bhs (skip) — biased by $0F if player was right of centre.
    private static final int GRAB_RADIUS = 0x10;
    private static final int GRAB_ENTRY_BIAS = 0x0F;

    // Jump-launch ROM: move.w #-$680, y_vel(a1)
    private static final short LAUNCH_Y_VEL = (short) -0x680;

    // Player mini-motion (sub_35504): d5=$C accel, d4=$80 skid, d6=$600 max.
    private static final int PLAYER_ACCEL = 0x0C;
    private static final int PLAYER_SKID = 0x80;
    private static final int PLAYER_MAX_SPEED = 0x600;

    // Lateral-launch gate / y-kick constants (ROM loc_35130/loc_350A6..).
    private static final int LATERAL_SOFT_CAP = 0x200;
    private static final int LATERAL_Y_KICK = 8;
    private static final int LATERAL_Y_MIN = -0x100;

    // Dash-on-landing thresholds ROM: cmpi.w #$40 / #$100 / #$800 / #$C00.
    private static final int DASH_THRESHOLD = 0x40;
    private static final int DASH_SPEED_LOW = 0x800;
    private static final int DASH_SPEED_HIGH = 0xC00;
    private static final int DASH_HIGH_THRESHOLD = 0x100;

    // Gravity ROM: addi.w #8 / $200 cap.
    private static final int GRAVITY = 8;
    private static final int MAX_Y_VEL = 0x200;
    private static final int RELEASE_GRAVITY = 0x38;

    // Ground-ride slope clamp ROM: cmpi.b #$1E (sub_34E6E).
    private static final int MAX_GROUND_ANGLE = 0x1E;

    // Waypoint table layout: count word (count - 1) + N × 16-byte entries.
    private static final int WAYPOINT_MATCH_WINDOW = 0x20;
    private static final int WAYPOINT_MATCH_HALF = 0x10;
    private static final int WAYPOINT_WORDS_PER_ENTRY = 8;

    /** Per-player sub_34EEC state. */
    private static final class PlayerGrabState implements RewindStateful<PlayerGrabState.Snapshot> {
        int routine;            // (a4): 0 / 2 / 4 / 6
        int entrySideBias;      // 1(a4): 0 = no bias; $F = came from right
        boolean standingNow;    // set by onSolidContact each frame
        boolean jumpHeldAtGrab; // edge-detect for release
        boolean grabbed;        // true while routine == 4 (fast check)
        int xSub;
        int ySub;
        boolean deferredWallClampPending;
        boolean deferredWallStopReady;
        int deferredWallClampX;
        int deferredWallClampXSub;
        int deferredWallSourceX;
        boolean deferredWallPreserveSubpixel;
        boolean deferredWallBoundaryRight;
        boolean projectedWallSubpixelValid;
        int projectedWallSubpixel;

        @Override
        public Snapshot captureRewindStateValue() {
            return new Snapshot(routine, entrySideBias, standingNow, jumpHeldAtGrab, grabbed, xSub, ySub,
                    deferredWallClampPending, deferredWallStopReady,
                    deferredWallClampX, deferredWallClampXSub,
                    deferredWallSourceX,
                    deferredWallPreserveSubpixel, deferredWallBoundaryRight,
                    projectedWallSubpixelValid, projectedWallSubpixel);
        }

        @Override
        public void restoreRewindStateValue(Snapshot state) {
            routine = state.routine();
            entrySideBias = state.entrySideBias();
            standingNow = state.standingNow();
            jumpHeldAtGrab = state.jumpHeldAtGrab();
            grabbed = state.grabbed();
            xSub = state.xSub();
            ySub = state.ySub();
            deferredWallClampPending = state.deferredWallClampPending();
            deferredWallStopReady = state.deferredWallStopReady();
            deferredWallClampX = state.deferredWallClampX();
            deferredWallClampXSub = state.deferredWallClampXSub();
            deferredWallSourceX = state.deferredWallSourceX();
            deferredWallPreserveSubpixel = state.deferredWallPreserveSubpixel();
            deferredWallBoundaryRight = state.deferredWallBoundaryRight();
            projectedWallSubpixelValid = state.projectedWallSubpixelValid();
            projectedWallSubpixel = state.projectedWallSubpixel();
        }

        private record Snapshot(
                int routine,
                int entrySideBias,
                boolean standingNow,
                boolean jumpHeldAtGrab,
                boolean grabbed,
                int xSub,
                int ySub,
                boolean deferredWallClampPending,
                boolean deferredWallStopReady,
                int deferredWallClampX,
                int deferredWallClampXSub,
                int deferredWallSourceX,
                boolean deferredWallPreserveSubpixel,
                boolean deferredWallBoundaryRight,
                boolean projectedWallSubpixelValid,
                int projectedWallSubpixel) {
        }
    }

    private record ProbeResult(int distance, int angle) {
    }

    private int currentSubtype;
    private boolean bodyDriven;

    // Platform state (ROM a0 fields) ===================================
    private int posX;
    private int posY;
    private int homeX;          // $30(a0)
    private int homeY;          // $32(a0)
    private int groundVel;       // ground_vel(a0)
    private int xVel;            // x_vel(a0)
    private int yVel;            // y_vel(a0)
    private int angle;           // angle(a0), signed byte wrapped to 0..$FF
    private int timer;           // $24(a0)
    private int rolling;         // $34(a0)
    private int arcMode;         // $35(a0)
    private int arcProgress;     // $3C(a0)
    private int arcLimit;        // $2E(a0)
    private int arcFlagsHi;      // $3E(a0)
    private int arcFlagsLo;      // $3F(a0)
    private int arcTimer;        // $3A(a0)
    private int arcDataIndex;    // decoded form of $36(a0)
    private int arcXSub;         // 16.16 subpixel used by sub_35868 linear legs
    private int arcYSub;         // 16.16 subpixel used by sub_35868 linear legs
    private int[] activeWaypointTable;
    private boolean airborne;    // status bit 1
    private boolean carryLatched; // ROM: $2D(a0), consumed by next body update
    private boolean nextCarryLatched;
    /**
     * Set when {@code sub_3519A} has flipped the platform into post-release drift
     * mode ({@code loc_34D92}). The platform then only runs {@code MoveSprite} and
     * the off-screen test; state machines and physics are disabled.
     */
    private boolean releasedFlight;
    private final SubpixelMotion.State motion;

    // Per-player state.
    private final Map<PlayableEntity, PlayerGrabState> playerStates = new IdentityHashMap<>();

    // Waypoints (lazy).
    private int[] waypointAct1;
    private int[] waypointAct2;
    private int firstActivatedWaypointEntryStart = -1;
    private int lastActivatedWaypointEntryStart = -1;
    private int waypointActivationCount;

    /**
     * ROM {@code Obj_MGZTopPlatform} writes its own routine address into word 0
     * of the object SST ({@code move.l #loc_34C54,(a0)}, sonic3k.asm:71495-71497),
     * so the high word an S3K sidekick reads through
     * {@code Tails_CPU_interact} while standing on this platform is {@code $0003}
     * ({@code $00034C54}). {@code sub_13EFC} (sonic3k.asm:26816-26843) latches
     * that word on every on-object frame and compares it against the next
     * off-screen on-object frame's word; without it a later landing on a
     * {@code $0002xxxx} object cannot mismatch and the sidekick is never parked
     * by {@code sub_13ECA}.
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0003;
    }

    public MGZTopPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MGZTopPlatform");
        this.currentSubtype = spawn.subtype() & 0xFF;
        this.bodyDriven = (currentSubtype == 0);
        this.posX = spawn.x();
        this.posY = spawn.y();
        this.homeX = posX;
        this.homeY = posY;
        this.motion = new SubpixelMotion.State(posX, posY, 0, 0, 0, 0);
    }

    @Override public int getX() { return posX; }
    @Override public int getY() { return posY; }
    @Override public int getPriorityBucket() { return RenderPriority.clamp(PRIORITY_BUCKET); }

    // =============================================================
    // Solid collision
    // =============================================================

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM loc_34F04: d1 = width+$B; d2 = height; d3 = height+1.
        return SolidObjectParams.of(WIDTH_PIXELS + 0x0B, HEIGHT_PIXELS, HEIGHT_PIXELS + 1);
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // ROM SolidObjectFull_1P reaches the no-contact path only when the
        // unsigned biased X distance is strictly greater than d1*2. A player
        // flush with the padded right edge therefore remains a side contact
        // and keeps the platform/player pushing bits set.
        return true;
    }

    @Override
    public int getBalanceWidthPixels() {
        // Tails_Move reads width_pixels(a1), which Obj_MGZTopPlatform sets to
        // $18. The full-solid collision helper is wider (width+$B), while the
        // generic full-solid fallback of 16 is narrower; neither is the ROM
        // edge-balance width.
        return WIDTH_PIXELS;
    }

    @Override
    public boolean usesPreUpdatePositionForSolidContact(PlayableEntity player) {
        // ROM loc_34C54 calls sub_34EEC for P1/P2 before the platform body moves
        // (sonic3k.asm:71508-71525); the post-motion player snap happens later
        // at loc_34D62/sub_35202 (sonic3k.asm:71576-71584,72045-72064).
        return true;
    }

    @Override
    public boolean carriesRiderOnHorizontalMove(PlayableEntity player) {
        // sub_34EEC passes the platform's current x_pos to SolidObjectFull_1P
        // before MoveSprite2 advances the body, so MvSonicOnPtfm sees no X
        // delta. The post-move sub_35202 path only copies X while a player is
        // grabbed (state >= 4); an ordinary standing rider is not carried.
        return false;
    }

    @Override
    public boolean usesPreUpdateYForContinuedRide(PlayableEntity player) {
        // The same pre-MoveSprite2 SolidObjectFull_1P call re-seats an ordinary
        // rider on the old Y. sub_35202 does not apply a post-move correction
        // until the object-local player state reaches the grabbed path.
        return true;
    }

    @Override
    public boolean projectsPreMovementGroundXForSolidContact(PlayableEntity player) {
        // Obj_MGZTopPlatform runs sub_34EEC after the player object has already
        // applied its grounded movement for the frame. The engine's inline
        // solid pass runs before that movement, so project the pending flat-X
        // step when testing a fresh side entry. This is what lets the ROM's
        // inclusive SolidObjectFull_1P right edge catch a player that moves
        // exactly onto x_pos + $23 during Obj01.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        // sub_3519A replaces the object's update address with loc_34D92,
        // whose drift-only routine never calls SolidObject again.
        if (releasedFlight) {
            return false;
        }
        // ROM state 4 grab: bclr #Status_OnObj / bset #Status_InAir on player, and
        // bclr d6,status(a0) on platform -> effectively disables solid coupling for
        // the grabbed player. We mirror by dropping solidity for that player.
        PlayerGrabState s = playerStates.get(player);
        return s == null || !s.grabbed;
    }

    /**
     * Read-only seam for headless parity tests to detect MGZ carried-player ownership
     * without reflective access to {@code playerStates}.
     */
    public boolean isPlayerGrabbed(PlayableEntity player) {
        PlayerGrabState state = playerStates.get(player);
        return state != null && state.grabbed;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (playerEntity == null) {
            return;
        }
        PlayerGrabState state = playerStates.computeIfAbsent(playerEntity, k -> new PlayerGrabState());
        if (contact.standing()) {
            state.standingNow = true;
            // ROM state 0 -> 2 transition (loc_34F2A): entrySide = (platform < player) ? 1 : 0.
            // I.e., player to the right of platform -> bias = $F.
            if (state.routine == 0) {
                int platformMinusPlayer = posX - playerEntity.getCentreX();
                state.entrySideBias = (platformMinusPlayer < 0) ? GRAB_ENTRY_BIAS : 0;
                // SolidObjectFull_1P is inline at the start of native state 0,
                // then loc_34F2A falls straight through to the approach/grab
                // check in that same object slot. AUTO_AFTER_UPDATE publishes
                // this engine checkpoint after the Java update body, so consume
                // the fresh landing here and perform the matching post-sync now.
                if (playerEntity instanceof AbstractPlayableSprite player
                        && !player.isWallCling()) {
                    state.routine = 2;
                    playerStateApproach(player, state);
                    if (state.grabbed) {
                        snapGrabbedPlayer(player);
                    }
                }
            }
        }
        // ROM SolidObjectFull: while the wall-cling bit is set (grabbed), a horizontal
        // push records a side-contact flag and a ceiling hit records a top-contact
        // flag. The grabbed state machine reads and clears these the same frame to
        // zero ground_vel / y_vel.
        if (!(playerEntity instanceof AbstractPlayableSprite sprite)) {
            return;
        }
        if (!sprite.isWallCling()) {
            return;
        }
        if (contact.touchSide() && contact.sideDistX() != 0 && contact.movingInto()) {
            sprite.setWallClingSideContact(true);
        }
        if (contact.touchBottom()) {
            sprite.setWallClingTopContact(true);
        }
    }

    @Override
    public boolean allowsObjectControlledSolidContact(PlayableEntity player, ObjectInstance candidate) {
        return candidate instanceof BreakableWallObjectInstance
                // Obj_Monitor's S3K SolidObject_cont rejects only a negative
                // object_control byte. The platform's positive bit-0 carry state
                // therefore still receives the monitor's side-contact flag, which
                // loc_350A6 consumes to stop platform movement.
                || candidate instanceof AbstractMonitorObjectInstance
                || candidate instanceof CollapsingBridgeObjectInstance bridge && bridge.isMgzStompMode()
                // Sinking mud executes its inline SolidObjectTop later in the
                // object-slot pass. ROM permits that landing even while this
                // platform still owns the player's object_control bit; the
                // resulting OnObj state releases the carrier on its next slot.
                || candidate instanceof SinkingMudObjectInstance
                // Obj_Spikes reaches SolidObject_cont, whose signed
                // object_control test accepts this carrier's positive bit 0.
                // Its side/top callback can therefore hurt and release the
                // carried player before the platform's own post-sync.
                || candidate instanceof Sonic3kSpikeObjectInstance
                || candidate instanceof Sonic3kSpringObjectInstance;
    }

    @Override
    public void onObjectControlledSolidContact(
            PlayableEntity playerEntity, ObjectInstance candidate, SolidContact contact) {
        if (!(playerEntity instanceof AbstractPlayableSprite player) || contact == null) {
            return;
        }
        if (candidate instanceof BreakableWallObjectInstance wall
                && contact.touchSide()
                && !wall.isDestroyed()
                && getExecutionSlotIndex() < wall.getExecutionSlotIndex()) {
            PlayerGrabState state = playerStates.computeIfAbsent(player, key -> new PlayerGrabState());
            // The later wall publishes status_tertiary after this carrier has
            // finished. Retain that flag for the carrier's next SST turn and
            // keep re-publishing it while SolidObjectFull reports contact.
            if (state.deferredWallPreserveSubpixel
                    && !state.deferredWallClampPending
                    && !state.deferredWallStopReady
                    && movingAwayFromDeferredWall(player, state)) {
                return;
            }
            state.deferredWallBoundaryRight = wall.getX() >= player.getCentreX();
            if (fractionalStepLeavesWall(player, state)) {
                state.deferredWallClampPending = false;
                state.deferredWallStopReady = false;
                return;
            }
            state.deferredWallClampPending = true;
            state.deferredWallStopReady = !player.isCpuControlled();
            state.deferredWallClampX = player.getCentreX();
            state.deferredWallClampXSub = player.getXSubpixelRaw();
            state.deferredWallSourceX = wall.getX();
            state.deferredWallPreserveSubpixel = true;
            return;
        }
        if (candidate instanceof BreakableWallObjectInstance wall
                && contact.touchSide()
                && !wall.isDestroyed()
                && getExecutionSlotIndex() > wall.getExecutionSlotIndex()
                // A zero-distance boundary only needs deferred replay while
                // the carrier's later word-only snap would put the player
                // beyond that boundary. Once the platform itself has returned
                // to the clamped X, sub_35504 is free to accumulate away-input
                // ground velocity again.
                && (contact.sideDistX() != 0
                        || !wall.breaksFromTertiarySideFeedback()
                                && carrierSnapWouldCrossBoundary(player, candidate))) {
            PlayerGrabState state = playerStates.computeIfAbsent(player, key -> new PlayerGrabState());
            // The engine's S3K post-movement solid pass reaches this wall
            // before the controller body, while the ROM's slot order applies
            // the wall after the platform's MoveSprite2/post-sync. Preserve
            // the wall's already-resolved native X and replay that final write
            // after this controller's body; its tertiary stop flag becomes
            // visible to the controller on the following native tick.
            state.deferredWallClampPending = true;
            state.deferredWallClampX = player.getCentreX();
            state.deferredWallSourceX = wall.getX();
            if (state.projectedWallSubpixelValid) {
                state.deferredWallClampXSub = state.projectedWallSubpixel;
            } else if (!player.isCpuControlled()) {
                state.deferredWallClampXSub = (player.getXSubpixelRaw()
                        + (previewPlayerGroundSpeed(player) << 8)) & 0xFFFF;
            } else {
                state.deferredWallClampXSub = player.getXSubpixelRaw();
            }
            state.projectedWallSubpixelValid = false;
            return;
        }
        // SolidObject_cont writes status_tertiary before this platform's later
        // object slot runs. loc_350A6 clears the flags and stops the matching
        // platform axis before copying its velocity back to Player_1.
        if (contact.touchSide() && contact.sideDistX() != 0 && contact.movingInto()) {
            player.setWallClingSideContact(true);
        }
        if (contact.touchBottom()) {
            player.setWallClingTopContact(true);
        }
    }

    @Override
    public Short projectedSolidContactXSpeed(
            PlayableEntity player, ObjectInstance candidate) {
        if (player == null) {
            return null;
        }
        if (candidate instanceof BreakableWallObjectInstance wall
                && getExecutionSlotIndex() < wall.getExecutionSlotIndex()) {
            // This carrier slot has already completed its MoveSprite2 and
            // post-sync before the later wall runs. There is no pending carrier
            // displacement for SolidObjectFull to project into the wall check.
            return 0;
        }
        if (candidate instanceof BreakableWallObjectInstance wall
                && player instanceof AbstractPlayableSprite sprite
                && getExecutionSlotIndex() > wall.getExecutionSlotIndex()
                && sprite.getCentreX() == wall.getX() + wall.getSolidParams().halfWidth()) {
            // This wall's SST slot runs before the carrier and the player is
            // exactly on SolidObjectFull's inclusive right boundary. Its d0
            // folds to zero, so the wall returns before the later platform's
            // MoveSprite2 step; do not project that future carrier movement.
            return 0;
        }
        if (candidate instanceof BreakableWallObjectInstance wall
                && player instanceof AbstractPlayableSprite sprite
                && wall.wouldBreakFromSideContact(sprite)) {
            // A breaking contact removes the wall in its own checkpoint and
            // restores the saved incoming velocity; it must use that real
            // checkpoint position. Projection is only needed for a persistent
            // wall whose side flag is consumed by this later carrier slot.
            return null;
        }
        if (candidate instanceof BreakableWallObjectInstance
                && player instanceof AbstractPlayableSprite sprite
                && !sprite.isCpuControlled()) {
            PlayerGrabState state = playerStates.computeIfAbsent(sprite, key -> new PlayerGrabState());
            int miniMotionXSpeed = previewPlayerGroundSpeed(sprite);
            state.projectedWallSubpixel = (sprite.getXSubpixelRaw() + (miniMotionXSpeed << 8)) & 0xFFFF;
            state.projectedWallSubpixelValid = true;
        }
        // The platform's later word-only post-sync supplies the pending
        // integer displacement. P1's fractional displacement is retained
        // separately above from sub_35504's ground_vel-driven MoveSprite2.
        return player.getXSpeed();
    }

    @Override
    public void onObjectControlledSolidContactInvalidated(
            PlayableEntity player, ObjectInstance candidate) {
        if (!(candidate instanceof BreakableWallObjectInstance wall)) {
            return;
        }
        PlayerGrabState state = playerStates.get(player);
        if (state != null && state.deferredWallSourceX == wall.getX()) {
            state.deferredWallClampPending = false;
            state.deferredWallStopReady = false;
            state.deferredWallClampX = 0;
            state.deferredWallClampXSub = 0;
            state.deferredWallSourceX = 0;
            state.deferredWallPreserveSubpixel = false;
            state.deferredWallBoundaryRight = false;
            state.projectedWallSubpixelValid = false;
        }
    }

    @Override
    public boolean ownsCarriedPlayerForRewind(PlayableEntity player) {
        return !isDestroyed() && isPlayerGrabbed(player);
    }

    // =============================================================
    // Main update
    // =============================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        // ROM loc_34D92: post-release drift. MoveSprite + anim + offscreen test only.
        if (releasedFlight) {
            runReleasedFlight();
            return;
        }

        AbstractPlayableSprite primary = (playerEntity instanceof AbstractPlayableSprite p) ? p : null;
        AbstractPlayableSprite sidekick = resolveSidekick();

        // ROM loc_34C54: sub_34EEC is invoked for P1 then P2 BEFORE platform motion.
        if (primary != null) {
            runPlayerStateMachine(primary, true);
        }
        if (sidekick != null) {
            runPlayerStateMachine(sidekick, false);
        }

        // sub_3519A replaces the object's update pointer, but it does not redirect
        // the invocation already executing. The current loc_34C54 tick therefore
        // still runs the platform body and both sub_35202 post-sync calls; the new
        // loc_34D92 drift path takes effect on the following object tick.

        // Platform body — subtype != 0 stays passive until Obj_MGZTopLauncher releases it.
        if (bodyDriven) {
            if (arcMode != 0) {
                runArcTravel();
            } else if (airborne) {
                runAirborne();
            } else {
                runGroundPath();
            }
        }

        // ROM loc_34D62: sub_35202 for both players (post-motion snap).
        nextCarryLatched = false;
        if (primary != null) snapGrabbedPlayer(primary);
        if (sidekick != null) snapGrabbedPlayer(sidekick);
        shareDeferredWallClampWithGrabbedSidekick(primary, sidekick);
        if (primary != null) applyDeferredWallClamp(primary);
        if (sidekick != null) applyDeferredWallClamp(sidekick);
        if (primary != null) clearDepartedWallPush(primary);
        if (sidekick != null) clearDepartedWallPush(sidekick);
        carryLatched = nextCarryLatched;

        // Reset per-frame standing flags; SolidContacts will re-assert next tick.
        for (PlayerGrabState ps : playerStates.values()) {
            ps.standingNow = false;
            ps.projectedWallSubpixelValid = false;
        }
        updateDynamicSpawn(posX, posY);
        // ROM loc_34D62 ends in Sprite_OnScreen_Test. Its chunk-aligned,
        // X-only check clears the layout respawn bit when this live platform
        // leaves the object window, allowing a later cursor pass to reload it.
        if (!isInRangeAt(posX)) {
            setDestroyedByOffscreen();
        }
    }

    /**
     * ROM loc_34D92: drift with current xVel/yVel (no gravity, no state machines).
     * The ROM teleports off-screen to {@code $7F00} and then defers to
     * {@code Sprite_OnScreen_Test} for deletion; we simply destroy once the
     * platform leaves the camera window.
     */
    private void runReleasedFlight() {
        // loc_34D92 reads the render_flags on-screen bit produced by the prior
        // render pass before it moves the object. The frame-start position and
        // camera bounds are therefore the correct retained-bit inputs here.
        boolean renderFlagOnScreen = isWithinRenderSpriteBounds(WIDTH_PIXELS, HEIGHT_PIXELS);
        movePlatform(true, RELEASE_GRAVITY);
        timer = (timer + 4) & 0xFFFF;
        updateDynamicSpawn(posX, posY);
        if (!renderFlagOnScreen) {
            // ROM writes x_pos=$7F00, then falls through to
            // Sprite_OnScreen_Test so the respawn-table bit is cleared.
            posX = 0x7F00;
            updateDynamicSpawn(posX, posY);
        }
        if (!isInRangeAt(posX)) {
            setDestroyedByOffscreen();
        }
    }

    // =============================================================
    // Platform body paths
    // =============================================================

    /**
     * ROM loc_34D0E..loc_34D5E: on-ground branch. Runs every frame — {@code sub_34E6E}
     * (floor-follow) fires unconditionally so an idle platform stays attached to the
     * track; {@code sub_34DBC}/{@code sub_35666} internally gate on {@code ground_vel}.
     */
    private void runGroundPath() {
        // ROM loc_34D1E: angle-driven velocities from ground_vel. With groundVel == 0
        // this produces zero velocity and MoveSprite2 is a no-op, but the floor probe
        // below still executes.
        int a = angle & 0xFF;
        int sin = TrigLookupTable.sinHex(a);
        int cos = TrigLookupTable.cosHex(a);
        xVel = (cos * groundVel) >> 8;
        yVel = (sin * groundVel) >> 8;

        if (groundVel != 0) {
            applyGroundWallResponse();
        }
        movePlatform(false, 0);

        // ROM tst.b $2D(a0): a carried/standing player suppresses the floor probe.
        if (!carryLatched) {
            // ROM sub_34E6E: floor-follow — ALWAYS runs (even with ground_vel == 0).
            probeGroundAngle();
        }
        timer = (timer + 4) & 0xFFFF;

        if (groundVel == 0) {
            rolling = 0;
        }

        // ROM sub_35666: waypoint check. Internally gates on rolling != 0.
        if (rolling != 0) {
            checkWaypoints();
        }
    }

    /** ROM loc_34C98..loc_34CD0: airborne branch (status bit 1). */
    private void runAirborne() {
        applyAirborneGravity();

        movePlatform(false, 0);
        switch (TrigLookupTable.calcMovementQuadrant((short) xVel, (short) yVel)) {
            case 0x40 -> runAirborneRight();
            case 0x80 -> runAirborneUp();
            case 0xC0 -> runAirborneLeft();
            default -> runAirborneDown();
        }

        if (carryLatched) {
            yVel = 0;
            xVel = 0;
            airborne = false;
            return;
        }
        if (!airborne) {
            int absX = Math.abs(xVel);
            if (absX >= DASH_THRESHOLD) {
                int speed = (absX < DASH_HIGH_THRESHOLD) ? DASH_SPEED_LOW : DASH_SPEED_HIGH;
                if (xVel < 0) speed = -speed;
                xVel = speed;
                groundVel = speed;
                rolling = 1;
            }
        }

    }

    private void applyAirborneGravity() {
        // ROM loc_34C88 compares the pre-add value and then adds $8 without a
        // post-add clamp. A value of $1FC therefore becomes $204 and remains
        // there on following ticks because it is already >= $200.
        if (yVel < MAX_Y_VEL) {
            yVel += GRAVITY;
        }
    }

    /** ROM sub_35868: sine-arced teleport between waypoints. */
    private void runArcTravel() {
        if (arcMode != 2) {
            arcTimer--;
            if (arcTimer >= 0) {
                SubpixelMotion.State arcMotion = new SubpixelMotion.State(
                        posX,
                        posY,
                        arcXSub,
                        arcYSub,
                        xVel,
                        yVel);
                SubpixelMotion.speedToPos(arcMotion);
                posX = arcMotion.x;
                posY = arcMotion.y;
                arcXSub = arcMotion.xSub;
                arcYSub = arcMotion.ySub;
                timer = (timer + 4) & 0xFFFF;
                return;
            }
            if (arcMode == 3) {
                if ((byte) arcFlagsHi < 0) {
                    groundVel = -groundVel;
                }
                arcMode = 0;
                syncArcSubpixelsToMotion();
                timer = (timer + 4) & 0xFFFF;
                return;
            }
            arcProgress = 0;
            arcMode = 2;
            if (activeWaypointTable == null || arcDataIndex >= activeWaypointTable.length) {
                arcMode = 0;
                syncArcSubpixelsToMotion();
                timer = (timer + 4) & 0xFFFF;
                return;
            }
            arcLimit = activeWaypointTable[arcDataIndex++];
            if ((arcFlagsLo & 0x7F) != 0) {
                arcProgress = arcLimit;
            }
        }

        int phase = arcProgress << 1;
        if ((byte) arcFlagsLo < 0) {
            phase = -phase;
        }
        int sin = TrigLookupTable.sinHex(phase & 0xFF);
        posX = homeX + ((sin * 0x5800) >> 16);
        posY = homeY + arcProgress;

        int step = (Math.abs(groundVel) == DASH_SPEED_HIGH) ? 3 : 2;
        if ((arcFlagsLo & 0x7F) != 0) {
            arcProgress -= step;
            if (arcProgress > 0) {
                timer = (timer + 4) & 0xFFFF;
                return;
            }
        } else {
            arcProgress += step;
            if (arcProgress < arcLimit) {
                timer = (timer + 4) & 0xFFFF;
                return;
            }
        }

        arcMode = 3;
        if (activeWaypointTable == null || arcDataIndex + 1 >= activeWaypointTable.length) {
            arcMode = 0;
            syncArcSubpixelsToMotion();
            timer = (timer + 4) & 0xFFFF;
            return;
        }
        int tailX = activeWaypointTable[arcDataIndex++];
        int tailY = activeWaypointTable[arcDataIndex++];
        computeArcLinearVelocity(tailX, tailY);
        timer = (timer + 4) & 0xFFFF;
    }

    // =============================================================
    // Per-player state machine (sub_34EEC)
    // =============================================================

    private void runPlayerStateMachine(AbstractPlayableSprite player, boolean isPrimary) {
        PlayerGrabState state = playerStates.computeIfAbsent(player, k -> new PlayerGrabState());

        // ROM loc_34FBC safety-release: if player is dead/hurt/in debug mode, fall
        // through to loc_3500A which releases the grab and, for P1, invokes sub_3519A
        // (flip platform to released-flight drift). Hurt uses an explicit helper so
        // MGZ carry ownership is cleared from the affected player state before the
        // shared hurt routine continues on subsequent frames.
        if (state.routine == 4 && player.isHurt()) {
            releaseForExternalHurt(player, state);
            if (isPrimary) {
                enterReleasedFlight();
            }
            return;
        }
        if (state.routine == 4 && (player.getDead() || player.isDebugMode())) {
            releasePlayer(player, state, false);
            if (isPrimary) {
                enterReleasedFlight();
            }
            return;
        }

        switch (state.routine) {
            case 0 -> playerStateLanding(player, state);
            case 2 -> playerStateApproach(player, state);
            case 4 -> playerStateGrabbed(player, state, isPrimary);
            case 6 -> playerStateReleased(player, state);
            default -> state.routine = 0;
        }
    }

    /** ROM loc_34F04: state 0 — wait for standing, then advance to state 2. */
    private void playerStateLanding(AbstractPlayableSprite player, PlayerGrabState state) {
        if (state.standingNow) {
            state.routine = 2;
            // ROM loc_34F2A falls through into loc_34F6A on the same object tick.
            // Without that immediate approach/grab check, the passive launcher child
            // can miss the centre-crossing window and never arm the stand launcher.
            playerStateApproach(player, state);
        }
    }

    /** ROM loc_34F4C: state 2 — wait for player to cross centre, then grab. */
    private void playerStateApproach(AbstractPlayableSprite player, PlayerGrabState state) {
        // ROM loc_34F6A: if standing bit was cleared (player no longer on top),
        // state resets to 0 — but the grab-radius check (loc_34F72) still runs.
        if (!state.standingNow) {
            state.routine = 0;
        }
        int dx = player.getCentreX() - posX;
        // ROM: cmpi.w #$10, d0; bhs locret (unsigned compare).
        int d0 = (dx + state.entrySideBias) & 0xFFFF;
        if (d0 < GRAB_RADIUS) {
            grabPlayer(player, state);
        }
    }

    /**
     * ROM loc_34F84 grab initiation:
     * <ul>
     *   <li>{@code move.w x_pos(a0), x_pos(a1)} — snap player X.</li>
     *   <li>{@code move.b default_y_radius+$18, y_radius(a1)} — stretch (engine handles).</li>
     *   <li>{@code bset #0, object_control(a1)}.</li>
     *   <li>{@code bset #Status_InAir(a1) / bclr #Status_OnObj(a1)}.</li>
     *   <li>{@code bclr d6, status(a0)} — platform clears its own standing bit.</li>
     *   <li>{@code addq.b #2, (a4)} — state -> 4.</li>
     *   <li>No x_vel/y_vel write; the player's pre-grab velocity is preserved.</li>
     * </ul>
     *
     * <p>ROM does NOT modify player Y here — sub_35202 will snap it at end of frame.
     */
    private void grabPlayer(AbstractPlayableSprite player, PlayerGrabState state) {
        NativePositionOps.writeXPosPreserveSubpixel(player, posX);
        // ROM loc_34F84 sets object_control bit 0, not the signed bit-7 gate.
        // Normal player movement is suppressed, while later object slots may
        // still execute their SolidObject helpers against the carried player.
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setMgzTopPlatformCarrySolidContactObject(this);
        player.setControlLocked(false);
        player.setOnObject(false);
        player.setAir(true);
        // ROM loc_34F84 (sonic3k.asm:71804-71817) arms object_control and
        // Status_InAir, but does not touch x_vel/y_vel.
        player.applyCustomRadii(player.getXRadius(), player.getStandYRadius() + 0x18);
        ObjectServices svc = tryServices();
        if (svc != null && svc.objectManager() != null) {
            svc.objectManager().clearRidingObject(player);
        }
        player.setWallCling(true);
        player.setWallClingSideContact(false);
        player.setWallClingTopContact(false);
        state.jumpHeldAtGrab = player.isJumpPressed();
        state.grabbed = true;
        state.routine = 4;
        // ROM loc_34F84 writes only x_pos(a1), preserving x_sub/y_sub. The
        // following loc_35070 MoveSprite2 path must continue from those fractions.
        syncGrabStateSubpixelsFromPlayer(player, state);
    }

    /** ROM loc_34FBC: state 4. Jump / ride / centering. */
    private void playerStateGrabbed(AbstractPlayableSprite player, PlayerGrabState state, boolean isPrimary) {
        applyGrabbedCollisionRadii(player);
        if (state.deferredWallStopReady) {
            player.setWallClingSideContact(true);
            state.deferredWallStopReady = false;
        }

        // ROM: jump (A|B|C) -> launch + release.
        boolean jumpPressed = player.isJumpPressed();
        if (state.jumpHeldAtGrab) {
            if (!jumpPressed) {
                state.jumpHeldAtGrab = false;
            }
        } else if (jumpPressed) {
            launchPlayerVertically(player, state, isPrimary);
            return;
        }

        // ROM loc_35028: if L/R held, facing is left to the normal input path; otherwise,
        // if the platform has x_vel, face per its sign (Facing bit reflects platform drift).
        // The player is object-controlled so the normal facing-from-gSpeed update doesn't
        // run — we set it explicitly here.
        int logicalInput = player.getLogicalInputState();
        boolean logicalLeft = (logicalInput & AbstractPlayableSprite.INPUT_LEFT) != 0;
        boolean logicalRight = (logicalInput & AbstractPlayableSprite.INPUT_RIGHT) != 0;
        if (!logicalLeft && !logicalRight && xVel != 0) {
            player.setDirection(xVel < 0 ? Direction.LEFT : Direction.RIGHT);
        }

        if (!isPrimary) {
            // ROM P2 exits here (rts in loc_35048).
            return;
        }

        // ROM loc_3505C: P1-only motion path.
        // ROM loc_35064: skip sub_35504 entirely when the player is already in
        // spring animation. The spring handoff below must read the spring's
        // x_vel/y_vel untouched on the next grabbed-state frame.
        boolean springAnim = player.getAnimationId() == Sonic3kAnimationIds.SPRING.id();
        boolean springCarryActive = airborne && player.getSpringing();
        if (!springAnim && !springCarryActive) {
            runPlayerGroundMotion(player);
        }
        moveGrabbedPlayer(player, state, !(springAnim || springCarryActive));

        if (player.hasMgzTopPlatformSpringHandoffPending()) {
            xVel = player.getMgzTopPlatformSpringHandoffXVel();
            yVel = player.getMgzTopPlatformSpringHandoffYVel();
            player.clearMgzTopPlatformSpringHandoff();
            player.setYSpeed((short) (player.getYSpeed() + RELEASE_GRAVITY));
            airborne = true;
            rolling = 0;
            return;
        }

        if (player.getAnimationId() == Sonic3kAnimationIds.SPRING.id()) {
            xVel = player.getXSpeed();
            yVel = player.getYSpeed();
            player.setYSpeed((short) (player.getYSpeed() + RELEASE_GRAVITY));
            airborne = true;
            rolling = 0;
            return;
        }

        if (springCarryActive) {
            // Keep the carried spring launch in its dedicated path while the spring lock
            // is still active. Re-entering centering here flattens the route spring into
            // the wrong mostly-horizontal motion.
            player.setXSpeed((short) xVel);
            player.setYSpeed((short) yVel);
            return;
        }

        if (player.consumeWallClingSideContact()) {
            groundVel = 0;
            xVel = 0;
        }
        if (player.consumeWallClingTopContact()) {
            yVel = 0;
        }

        // ROM loc_350C8 copies the platform's current movement back to the player
        // before the centering math changes the platform velocity again.
        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);
        if (rolling == 0) {
            applyCenteringOrLateralLaunch(player);
        }
    }

    private void applyGrabbedCollisionRadii(AbstractPlayableSprite player) {
        // The native state byte remains on the loc_34F4C entry while the
        // platform owns the player. Its fall-through reaches loc_34F84 again
        // and rewrites default_y_radius+$18 every object tick. This matters
        // when an earlier/later solid (the MGZ stomp bridge) has called
        // Player_TouchFloor and temporarily restored the standing radii: the
        // following frame's earlier spring slot must still see the enlarged
        // carried-player collision height.
        player.applyCustomRadii(player.getXRadius(), player.getStandYRadius() + 0x18);
        // loc_34F84 also reasserts object_control bit 0. Multiple live top
        // platforms may retain native per-player state simultaneously; the
        // last platform slot to execute is therefore the owner whose pending
        // contact flags earlier slots must publish on the following frame.
        player.setMgzTopPlatformCarrySolidContactObject(this);
    }

    private void applyDeferredWallClamp(AbstractPlayableSprite player) {
        PlayerGrabState state = playerStates.get(player);
        if (state == null || !state.deferredWallClampPending) {
            return;
        }
        boolean leavingWall = state.deferredWallPreserveSubpixel
                && (integerPositionLeavesWall(player, state)
                        || fractionalStepLeavesWall(player, state));
        if (!leavingWall) {
            NativePositionOps.writeXPosPreserveSubpixel(player, state.deferredWallClampX);
        }
        if (!state.deferredWallPreserveSubpixel) {
            player.setSubpixelRaw(state.deferredWallClampXSub, player.getYSubpixelRaw());
        }
        player.setXSpeed((short) 0);
        if (!state.deferredWallPreserveSubpixel || !leavingWall) {
            player.setGSpeed((short) 0);
        }
        // SolidObject's airborne side branch corrects position without setting
        // Status_Push. P2 remains airborne throughout this carry path.
        if (!player.getAir()) {
            player.setPushing(true);
        }
        state.deferredWallClampPending = false;
        // loc_35048 returns immediately for Player_2; only P1 reaches the
        // carrier's status_tertiary consumption at loc_350A6.
        state.deferredWallStopReady = !player.isCpuControlled() && !leavingWall;
    }

    private void shareDeferredWallClampWithGrabbedSidekick(
            AbstractPlayableSprite primary, AbstractPlayableSprite sidekick) {
        if (primary == null || sidekick == null) {
            return;
        }
        PlayerGrabState primaryState = playerStates.get(primary);
        PlayerGrabState sidekickState = playerStates.get(sidekick);
        if (primaryState == null
                || !primaryState.deferredWallClampPending
                || sidekickState == null
                || !sidekickState.grabbed
                || sidekickState.deferredWallClampPending) {
            return;
        }
        // sub_35202 runs for both player slots after the platform body. When
        // the shared carrier reaches a side boundary, both grabbed positions
        // finish on that same native wall coordinate even if only P1 supplied
        // the side-contact flag to the folded engine checkpoint.
        sidekickState.deferredWallClampPending = true;
        sidekickState.deferredWallClampX = primaryState.deferredWallClampX;
        sidekickState.deferredWallClampXSub = sidekick.getXSubpixelRaw();
        sidekickState.deferredWallSourceX = primaryState.deferredWallSourceX;
        sidekickState.deferredWallPreserveSubpixel = primaryState.deferredWallPreserveSubpixel;
        sidekickState.deferredWallBoundaryRight = primaryState.deferredWallBoundaryRight;
    }

    private void clearDepartedWallPush(AbstractPlayableSprite player) {
        PlayerGrabState state = playerStates.get(player);
        if (state == null
                || state.deferredWallClampPending
                || state.deferredWallClampX == 0
                || player.getCentreX() == state.deferredWallClampX) {
            return;
        }
        if (!player.isCpuControlled()) {
            player.setPushing(false);
            // The native P1 mini-movement path publishes prev_anim=1 when it
            // leaves the push state, so Animate_Sonic restarts AniSonic00 on
            // the next player slot while retaining this tick's push mapping.
            // P2 returns at loc_35048 and never enters this path.
            player.getAnimationManager().publishPreviousAnimationId(1);
        }
        state.deferredWallClampX = 0;
        state.deferredWallClampXSub = 0;
        state.deferredWallSourceX = 0;
        state.deferredWallPreserveSubpixel = false;
        state.deferredWallBoundaryRight = false;
    }

    private boolean fractionalStepLeavesWall(
            AbstractPlayableSprite player, PlayerGrabState state) {
        int speed = player.getGSpeed();
        int subpixel = player.getXSubpixelRaw() & 0xFFFF;
        if (state.deferredWallBoundaryRight && speed < 0) {
            return subpixel <= ((-speed) << 8);
        }
        return !state.deferredWallBoundaryRight
                && speed > 0
                && subpixel >= 0x10000 - (speed << 8);
    }

    private boolean movingAwayFromDeferredWall(
            AbstractPlayableSprite player, PlayerGrabState state) {
        int speed = player.getGSpeed();
        return state.deferredWallBoundaryRight ? speed < 0 : speed > 0;
    }

    private boolean integerPositionLeavesWall(
            AbstractPlayableSprite player, PlayerGrabState state) {
        int currentX = player.getCentreX();
        return state.deferredWallBoundaryRight
                ? currentX < state.deferredWallClampX
                : currentX > state.deferredWallClampX;
    }

    private int previewPlayerGroundSpeed(AbstractPlayableSprite player) {
        int speed = player.getGSpeed();
        int logicalInput = player.getLogicalInputState();
        boolean right = (logicalInput & AbstractPlayableSprite.INPUT_RIGHT) != 0;
        boolean left = (logicalInput & AbstractPlayableSprite.INPUT_LEFT) != 0;
        if (left && !right) {
            if (speed > 0) {
                return speed < PLAYER_SKID ? -PLAYER_SKID : speed - PLAYER_SKID;
            }
            return Math.max(-PLAYER_MAX_SPEED, speed - PLAYER_ACCEL);
        }
        if (right && !left) {
            if (speed < 0) {
                return speed >= -PLAYER_SKID ? PLAYER_SKID : speed + PLAYER_SKID;
            }
            return Math.min(PLAYER_MAX_SPEED, speed + PLAYER_ACCEL);
        }
        if (speed > 0) {
            return Math.max(0, speed - PLAYER_ACCEL);
        }
        if (speed < 0) {
            return Math.min(0, speed + PLAYER_ACCEL);
        }
        return 0;
    }

    private boolean carrierSnapWouldCrossBoundary(
            AbstractPlayableSprite player, ObjectInstance boundary) {
        int predictedPlatformX = predictCoordinate(posX, motion.xSub, xVel);
        int clampedPlayerX = player.getCentreX();
        return boundary.getX() >= clampedPlayerX
                ? predictedPlatformX > clampedPlayerX
                : predictedPlatformX < clampedPlayerX;
    }

    /** ROM sub_35504: accumulate player ground_vel from input (accel $C, skid $80, max $600). */
    private void runPlayerGroundMotion(AbstractPlayableSprite player) {
        int gSpeed = player.getGSpeed();
        int logicalInput = player.getLogicalInputState();
        boolean right = (logicalInput & AbstractPlayableSprite.INPUT_RIGHT) != 0;
        boolean left = (logicalInput & AbstractPlayableSprite.INPUT_LEFT) != 0;

        if (left && !right) {
            if (gSpeed > 0) {
                // Skid ROM sub_3555C loc_35596: sub.w d4, d0 (=$80).
                gSpeed = gSpeed < PLAYER_SKID ? -PLAYER_SKID : gSpeed - PLAYER_SKID;
                if (mgzMiniMotionSkidThresholdMet(player, gSpeed, true)) {
                    player.setAnimationId(Sonic3kAnimationIds.SKID);
                    player.setDirection(Direction.RIGHT);
                }
            } else {
                // Accel ROM loc_35578: sub.w d5, d0 (=$C).
                setMiniMotionDirection(player, Direction.LEFT);
                gSpeed -= PLAYER_ACCEL;
                if (gSpeed < -PLAYER_MAX_SPEED) gSpeed = -PLAYER_MAX_SPEED;
                player.setAnimationId(Sonic3kAnimationIds.WALK);
            }
        } else if (right && !left) {
            if (gSpeed < 0) {
                // add.w sets carry when the signed-negative word wraps through
                // zero; the ROM then seeds +$80 instead of stopping at zero.
                gSpeed = gSpeed >= -PLAYER_SKID ? PLAYER_SKID : gSpeed + PLAYER_SKID;
                if (mgzMiniMotionSkidThresholdMet(player, gSpeed, false)) {
                    player.setAnimationId(Sonic3kAnimationIds.SKID);
                    player.setDirection(Direction.LEFT);
                }
            } else {
                setMiniMotionDirection(player, Direction.RIGHT);
                gSpeed += PLAYER_ACCEL;
                if (gSpeed > PLAYER_MAX_SPEED) gSpeed = PLAYER_MAX_SPEED;
                player.setAnimationId(Sonic3kAnimationIds.WALK);
            }
        } else {
            // ROM loc_35524..loc_3554E friction toward 0 (uses d5 = $C).
            if (gSpeed > 0) {
                gSpeed -= PLAYER_ACCEL;
                if (gSpeed < 0) gSpeed = 0;
            } else if (gSpeed < 0) {
                gSpeed += PLAYER_ACCEL;
                if (gSpeed > 0) gSpeed = 0;
            }
        }
        player.setGSpeed((short) gSpeed);
        // ROM loc_3554E: x_vel = ground_vel; y_vel = 0.
        player.setXSpeed((short) gSpeed);
        player.setYSpeed((short) 0);
    }

    private void setMiniMotionDirection(AbstractPlayableSprite player, Direction direction) {
        if (player.getDirection() != direction) {
            // sub_3555C/sub_355E4 use bset/bclr on Status_Facing. When that
            // changes the bit, they clear Status_Push and write prev_anim=1
            // before selecting Walk. Animate therefore restarts AniSonic00 on
            // the next player slot even though the raw anim byte remains zero.
            player.setPushing(false);
            player.getAnimationManager().publishPreviousAnimationId(1);
        }
        player.setDirection(direction);
    }

    private boolean mgzMiniMotionSkidThresholdMet(AbstractPlayableSprite player,
            int groundSpeed, boolean leftInput) {
        // The released ROM's sub_3555C/sub_355E4 uses d0, rather than d1, for
        // the angle test. That overwrites only d0's low byte before the signed
        // +/-$400 skid comparison, so preserve the accelerated speed's high
        // byte and reproduce the shipped result (sonic3k.asm:72360-72467).
        int angleByte = (((player.getAngle() & 0xFF) + 0x20) & 0xC0);
        short corruptedSpeed = (short) ((groundSpeed & 0xFF00) | angleByte);
        return leftInput ? corruptedSpeed >= 0x400 : corruptedSpeed <= -0x400;
    }

    /**
     * ROM loc_350A6..loc_35170: centering / off-centre launch.
     *
     * <ul>
     *   <li>{@code d0 = player.x - platform.x}.</li>
     *   <li>{@code d0 > 0}: if {@code xVel < $200}, {@code xVel += 4*d0}. Then if
     *       {@code xVel >= 0} (bmi skip): {@code yVel -= 8} and, if {@code yVel > -$100},
     *       {@code yVel += -xVel/16} (i.e. subtract |xVel|/16). Set airborne.</li>
     *   <li>{@code d0 < 0}: mirror. Gate on {@code xVel > -$200}; y-kick condition is
     *       {@code xVel < 0} (bpl skip); {@code yVel += xVel/16} (xVel is negative).</li>
     *   <li>{@code d0 == 0}: decelerate {@code xVel} by 1 toward 0 and clear upward y_vel.</li>
     * </ul>
     */
    private void applyCenteringOrLateralLaunch(AbstractPlayableSprite player) {
        int dx = player.getCentreX() - posX;
        if (dx == 0) {
            // ROM loc_35170.
            int timerDelta = 1;
            if (xVel > 0) {
                xVel -= 1;
                timerDelta = -1;
            } else if (xVel < 0) {
                xVel += 1;
            }
            timer = (timer + timerDelta) & 0xFFFF;
            if (yVel < 0) yVel = 0;
            return;
        }

        if (dx > 0) {
            // ROM loc_35130: x_vel gate.
            if (xVel < LATERAL_SOFT_CAP) {
                xVel += dx * 4;
            }
            timer = (timer + dx) & 0xFFFF;
            // ROM loc_35148: y-kick only when x_vel >= 0 (bmi skip).
            if (xVel >= 0) {
                yVel -= LATERAL_Y_KICK;
                if (yVel > LATERAL_Y_MIN) {
                    // ROM loc_35148 (sonic3k.asm:71966-71974): neg.w before
                    // asr.w #4, then add after the -$100 compare. There is no
                    // post-add clamp, so the result can overshoot below -$100.
                    int add = (-xVel) >> 4;
                    yVel += add;
                }
            }
        } else {
            // dx < 0 (ROM fall-through at loc_350C8 ... loc_35128).
            if (xVel > -LATERAL_SOFT_CAP) {
                xVel += dx * 4; // dx is negative
            }
            timer = (timer + dx) & 0xFFFF;
            // ROM loc_3510A: y-kick only when x_vel < 0 (bpl skip).
            if (xVel < 0) {
                yVel -= LATERAL_Y_KICK;
                if (yVel > LATERAL_Y_MIN) {
                    // ROM loc_3510A (sonic3k.asm:71943-71951): asr.w #4 on
                    // negative xVel, then add after the -$100 compare.
                    int add = xVel >> 4; // negative
                    yVel += add;
                }
            }
        }

        // ROM loc_35128/loc_35168: bset #1, status -> airborne.
        airborne = true;
    }

    private void launchPlayerVertically(AbstractPlayableSprite player, PlayerGrabState state,
                                        boolean isPrimary) {
        releasePlayer(player, state, true);
        // ROM: move.w #-$680, y_vel(a1); jumping=1; y_radius=$E; x_radius=7; anim=ROLL;
        // bset #Status_Roll; sfx_Jump.
        player.setYSpeed(LAUNCH_Y_VEL);
        // loc_34FBC deliberately preserves x_vel and ground_vel.
        player.setJumping(true);
        player.setAir(true);
        player.setRolling(true);
        player.applyRollingRadii(false);
        player.setAnimationId(Sonic3kAnimationIds.ROLL.id());
        playSfx(Sonic3kSfx.JUMP);

        // ROM loc_3500A: beq.w sub_3519A — only P1 flips the platform to drift mode
        // (loc_34D92). P2 jump-launch only releases that player; the platform
        // continues running its state machines.
        if (isPrimary) {
            enterReleasedFlight();
        }
    }

    /**
     * ROM sub_3519A: overwrite the platform's update routine with {@code loc_34D92}
     * and force both players' slot-state to 6. Called when P1 jump-launches or when
     * {@code sub_35202}'s edge-case path fires.
     */
    private void enterReleasedFlight() {
        enterReleasedFlight(null, null);
    }

    private void enterReleasedFlight(AbstractPlayableSprite knownPlayer, ObjectInstance knownSupport) {
        releasedFlight = true;
        carryLatched = false;
        nextCarryLatched = false;
        for (Map.Entry<PlayableEntity, PlayerGrabState> entry : playerStates.entrySet()) {
            PlayerGrabState ps = entry.getValue();
            if (entry.getKey() instanceof AbstractPlayableSprite player) {
                if (ps.routine == 4) {
                    releasePlayer(player, ps, true, player == knownPlayer ? knownSupport : null);
                } else if (ps.routine != 0) {
                    player.setMgzTopPlatformCarrySolidContactObject(null);
                    player.setOnObject(false);
                    ps.grabbed = false;
                    ps.entrySideBias = 0;
                }
                // ROM sub_3519A writes 6 to both $40(a0) and $42(a0)
                // unconditionally, including a P2 slot which never contacted the
                // platform. The later same-frame sub_35202 call consumes that state.
                ps.routine = 6;
            } else {
                ps.routine = 6;
                ps.grabbed = false;
                ps.entrySideBias = 0;
            }
        }
    }

    /** ROM sub_3519A / loc_34D92 reset; we only clear the state bits. */
    private void playerStateReleased(AbstractPlayableSprite player, PlayerGrabState state) {
        state.routine = 0;
        state.entrySideBias = 0;
        state.grabbed = false;
    }

    private void releasePlayer(AbstractPlayableSprite player, PlayerGrabState state, boolean airborneRelease) {
        releasePlayer(player, state, airborneRelease, null);
    }

    private void releasePlayer(AbstractPlayableSprite player, PlayerGrabState state, boolean airborneRelease,
            ObjectInstance knownFormerSupport) {
        ObjectServices svc = tryServices();
        ObjectInstance formerSupport = knownFormerSupport != null
                ? knownFormerSupport
                : svc != null && svc.objectManager() != null
                        ? svc.objectManager().getRidingObject(player) : null;
        // The player slot has already executed for this frame. Clearing ROM
        // object_control bit 0 here enables normal movement on the next slot.
        ObjectControlState.none().applyTo(player);
        player.setMgzTopPlatformCarrySolidContactObject(null);
        player.setControlLocked(false);
        player.setOnObject(false);
        player.setForcedAnimationId(-1);
        if (!airborneRelease) {
            int nativeCentreY = player.getCentreY();
            player.restoreDefaultRadii();
            NativePositionOps.writeYPosPreserveSubpixel(player, nativeCentreY);
        }
        player.clearWallClingState();
        player.suppressNextJumpPress();
        if (airborneRelease) {
            player.setAir(true);
        }
        if (svc != null && svc.objectManager() != null) {
            if (airborneRelease && formerSupport != null) {
                // The engine resolves solids before object updates. ROM instead
                // reaches this carrier slot before the later support slot, so
                // sub_3519A's air write makes that slot clear its standing bit
                // without applying another surface snap.
                svc.objectManager().clearRidingObjectAfterControllerAirborneRelease(
                        player, formerSupport);
            } else {
                svc.objectManager().clearRidingObject(player);
            }
        }
        state.entrySideBias = 0;
        state.xSub = 0;
        state.ySub = 0;
        state.routine = 6;
        state.grabbed = false;
    }

    /**
     * External hurt keeps the player in the hurt routine, but MGZ carry ownership must
     * still be dropped immediately so the next frame does not re-enter attached logic.
     */
    private void releaseForExternalHurt(AbstractPlayableSprite player, PlayerGrabState state) {
        releasePlayer(player, state, false);
    }

    // =============================================================
    // sub_35202 — post-motion snap for grabbed players
    // =============================================================

    /**
     * ROM sub_35202 (state 4, !Status_OnObj branch):
     * <pre>
     *   y_pos(a1) = (y_pos(a0) - $C) - default_y_radius(a1)
     *   x_pos(a1) = x_pos(a0)
     *   $2D(a0) = 0
     * </pre>
     */
    private void snapGrabbedPlayer(AbstractPlayableSprite player) {
        PlayerGrabState state = playerStates.get(player);
        if (state == null || state.routine < 4) {
            return;
        }
        ObjectInstance riding = currentRidingObject(player);
        if (state.routine == 4) {
            if (riding instanceof SinkingMudObjectInstance && riding != this) {
                enterReleasedFlight(player, riding);
                // ROM sub_35202 calls sub_3519A, then falls through to
                // loc_35248: P1 still anchors the released platform to its
                // native y_pos before the following P2 slot is post-synced.
                int defaultYR = player.getStandYRadius();
                NativePositionOps.writeXPosPreserveSubpixel(player, posX);
                posY = player.getCentreY() + defaultYR + 0x0D;
                nextCarryLatched = true;
                return;
            }
        }
        if (player.isOnObject() && riding == this) {
            // ROM sub_35202 Status_OnObj branch:
            //   x_pos(a1) = x_pos(a0)                 ; player X follows platform X
            //   y_pos(a0) = y_pos(a1) + default_y_radius + $D
            int defaultYR = player.getStandYRadius();
            NativePositionOps.writeXPosPreserveSubpixel(player, posX);
            posY = player.getCentreY() + defaultYR + 0x0D;
            nextCarryLatched = true;
            return;
        }
        int platformTop = posY - HEIGHT_PIXELS;
        int defaultYR = player.getStandYRadius();
        short snapY = (short) (platformTop - defaultYR);
        short snapX = (short) posX;
        NativePositionOps.writeXPosPreserveSubpixel(player, snapX);
        NativePositionOps.writeYPosPreserveSubpixel(player, snapY);
        // ROM sub_35202 (sonic3k.asm:72051-72058) only snaps x_pos/y_pos and
        // clears the carry latch. It deliberately preserves the x_vel written by
        // sub_35504/loc_3554E (sonic3k.asm:72352-72354) earlier in the frame.
        nextCarryLatched = false;
    }

    // =============================================================
    // Terrain probes
    // =============================================================

    private void applyGroundWallResponse() {
        int rotation = (groundVel < 0) ? 0x40 : 0xC0;
        int rotatedAngle = (angle + rotation) & 0xFF;
        ProbeResult probe = runGroundWallProbe(rotatedAngle);
        if (probe == null || probe.distance >= 0) {
            return;
        }

        int velocityAdjustment = probe.distance << 8;
        int mode = (rotatedAngle + 0x20) & 0xC0;
        switch (mode) {
            case 0x00 -> yVel += velocityAdjustment;
            case 0x40 -> {
                if ((((probe.angle & 0xFF) + 0x30) & 0xFF) < 0x60) {
                    return;
                }
                xVel -= velocityAdjustment;
                yVel = 0;
                groundVel = 0;
            }
            case 0x80 -> yVel -= velocityAdjustment;
            default -> {
                if ((((probe.angle & 0xFF) + 0x30) & 0xFF) < 0x60) {
                    return;
                }
                xVel += velocityAdjustment;
                yVel = 0;
                groundVel = 0;
            }
        }
    }

    /**
     * ROM sub_34DBC → sub_F6B4: single-point probe in the rotated-angle direction,
     * at the predicted next-frame position (x_pos + x_vel, y_pos + y_vel). The
     * dispatch targets (sub_F828 floor, CheckCeilingDist_WithRadius, loc_FAA4 right
     * wall, loc_FDC8 left wall) all use x_radius=$18 for the probe offset —
     * y_radius=$13 is never actually consumed by these single-sensor variants.
     */
    private ProbeResult runGroundWallProbe(int rotatedAngle) {
        int predictedX = predictCoordinate(posX, motion.xSub, xVel);
        int predictedY = predictCoordinate(posY, motion.ySub, yVel) - WALL_Y_OFFSET;
        int probeMode = anglePosQuadrant(rotatedAngle);
        return switch (probeMode) {
            case 0x00 -> toProbeResult(
                    ObjectTerrainUtils.checkFloorDistWithFlipAwareAngle(predictedX, predictedY, WALL_X_RADIUS), 0x00);
            case 0x40 -> toProbeResult(
                    ObjectTerrainUtils.checkLeftWallDistWithFlipAwareAngle(predictedX - WALL_X_RADIUS, predictedY), 0x40);
            case 0x80 -> toProbeResult(
                    ObjectTerrainUtils.checkCeilingDistWithFlipAwareAngle(predictedX, predictedY, WALL_X_RADIUS), 0x80);
            default -> toProbeResult(
                    ObjectTerrainUtils.checkRightWallDistWithFlipAwareAngle(predictedX + WALL_X_RADIUS, predictedY), 0xC0);
        };
    }

    private static int predictCoordinate(int pos, int sub, int vel) {
        int total = (sub & 0xFF) + (vel & 0xFF);
        return pos + (vel >> 8) + (total >> 8);
    }

    private ProbeResult toProbeResult(TerrainCheckResult result, int fallbackAngle) {
        if (result == null || !result.foundSurface()) {
            return null;
        }
        int probeAngle = result.angle() & 0xFF;
        if ((probeAngle & 1) != 0) {
            probeAngle = fallbackAngle & 0xFF;
        }
        return new ProbeResult(result.distance(), probeAngle);
    }

    private static int anglePosQuadrant(int angle) {
        angle &= 0xFF;
        int check = (angle + 0x20) & 0xFF;
        if ((check & 0x80) != 0) {
            int d0 = angle;
            if ((angle & 0x80) != 0) {
                d0 = (d0 - 1) & 0xFF;
            }
            return (d0 + 0x20) & 0xC0;
        }
        int d0 = angle;
        if ((angle & 0x80) != 0) {
            d0 = (d0 + 1) & 0xFF;
        }
        return (d0 + 0x1F) & 0xC0;
    }

    /**
     * ROM sub_34E6E: floor-follow on the ground path. Uses {@code y_radius = $C}
     * (platform body half-height) so the probe bottom lands exactly at the
     * platform's feet — no y_pos offset needed.
     */
    private void probeGroundAngle() {
        ProbeResult floor = probeGroundFloor();
        if (floor == null) {
            // ROM: if Sonic_CheckFloor returns no floor, d1 is large positive which
            // falls into the drop-gap test below and sets airborne.
            airborne = true;
            rolling = 0;
            return;
        }
        angle = clampGroundAngle(floor.angle);
        int dist = floor.distance;
        if (dist == 0) {
            return;
        }
        if (dist < 0) {
            // ROM: cmpi.w #-$E, d1; blt.s locret_34EB8 — skip if overlap > 14.
            if (dist < -0x0E) return;
            posY += dist;
        } else {
            // ROM loc_34EBA: drop gap capped at min(|x_vel.byte| + 4, $E).
            int maxDrop = Math.min(Math.abs((byte) xVel) + 4, 0x0E);
            if (dist <= maxDrop) {
                posY += dist;
            } else {
                airborne = true;
                rolling = 0;
            }
        }
    }

    private static int clampGroundAngle(int byteValue) {
        int signed = (byte) byteValue;
        if (signed > MAX_GROUND_ANGLE) signed = MAX_GROUND_ANGLE;
        else if (signed < -MAX_GROUND_ANGLE) signed = -MAX_GROUND_ANGLE;
        return signed & 0xFF;
    }

    // =============================================================
    // Waypoint arc (sub_35666)
    // =============================================================

    private void checkWaypoints() {
        int[] table = waypointTableForCurrentAct();
        if (table == null || table.length < 2) return;
        int count = table[0];
        for (int e = 0; e <= count; e++) {
            int i = 1 + e * WAYPOINT_WORDS_PER_ENTRY;
            if (i + (WAYPOINT_WORDS_PER_ENTRY - 1) >= table.length) break;
            int triggerX = table[i];
            int triggerY = table[i + 1];
            int dxWin = (triggerX - posX) + WAYPOINT_MATCH_HALF;
            if (dxWin < 0 || dxWin >= WAYPOINT_MATCH_WINDOW) continue;
            int dyWin = (triggerY - posY) + WAYPOINT_MATCH_HALF;
            if (dyWin < 0 || dyWin >= WAYPOINT_MATCH_WINDOW) continue;
            int flagByte = (table[i + 2] >> 8) & 0xFF;
            int probe = ((flagByte & 0x7F) != 0) ? -groundVel : groundVel;
            if (probe < 0) continue;
            activateArc(table, i);
            return;
        }
    }

    private void activateArc(int[] table, int entryStart) {
        arcMode = 1;
        // ROM stores the whole word at $3E(a0). sub_35868 then uses:
        // - $3F(a0) (low byte) for the arc direction/progress logic
        // - $3E(a0) (high byte) for the final ground_vel sign flip after the tail
        // - move.b 4(a1),d2 (high byte) for the trigger-direction gate in sub_35666
        arcFlagsHi = (table[entryStart + 2] >> 8) & 0xFF;
        arcFlagsLo = table[entryStart + 2] & 0xFF;
        int destX = table[entryStart + 3];
        int destY = table[entryStart + 4];
        int linearDestY = destY;
        int deltaY = table[entryStart + 5];
        if ((arcFlagsLo & 0x7F) != 0) {
            destY -= deltaY;
        }
        homeX = (short) destX;
        homeY = (short) destY;
        activeWaypointTable = table;
        if (firstActivatedWaypointEntryStart < 0) {
            firstActivatedWaypointEntryStart = entryStart;
        }
        lastActivatedWaypointEntryStart = entryStart;
        waypointActivationCount++;
        arcDataIndex = entryStart + 5;
        arcProgress = 0;
        arcLimit = 0;
        arcTimer = 0;
        syncMotionSubpixelsToArc();
        // ROM sub_35666 keeps d5 as the original destination Y for the initial
        // linear approach. Only d0 (stored to $32) receives the delta adjustment
        // used later as the arc centre by sub_35868.
        computeArcLinearVelocity(destX, linearDestY);
    }

    private int[] waypointTableForCurrentAct() {
        int act = currentAct();
        if (act == 0) {
            if (waypointAct1 == null) {
                waypointAct1 = readWaypointTable(Sonic3kConstants.MGZ_TOP_PLATFORM_WAYPOINTS_ACT1_ADDR);
            }
            return waypointAct1;
        }
        if (waypointAct2 == null) {
            waypointAct2 = readWaypointTable(Sonic3kConstants.MGZ_TOP_PLATFORM_WAYPOINTS_ACT2_ADDR);
        }
        return waypointAct2;
    }

    private int currentAct() {
        ObjectServices svc = tryServices();
        if (svc == null || svc.levelManager() == null) return 0;
        try {
            return svc.levelManager().getCurrentAct();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private int[] readWaypointTable(int romAddr) {
        ObjectServices svc = tryServices();
        if (svc == null || svc.romManager() == null) return null;
        try {
            Rom rom = svc.romManager().getRom();
            if (rom == null) return null;
            RomByteReader reader = RomByteReader.fromRom(rom);
            int count = reader.readU16BE(romAddr);
            int entries = count + 1;
            int[] out = new int[1 + entries * WAYPOINT_WORDS_PER_ENTRY];
            out[0] = count;
            int base = romAddr + 2;
            for (int i = 0; i < entries * WAYPOINT_WORDS_PER_ENTRY; i++) {
                out[1 + i] = reader.readS16BE(base + i * 2);
            }
            return out;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void runAirborneDown() {
        resolveLeftWall(false);
        resolveRightWall(false);
        ProbeResult floor = probeAirborneFloor();
        if (floor == null) {
            return;
        }
        int dist = floor.distance;
        if (dist >= 0 || dist < -0x0E) {
            return;
        }
        landFromAirDefault(floor);
    }

    private void runAirborneRight() {
        resolveLeftWall(true);

        ProbeResult ceiling = probeAirborneCornerCeiling();
        if (ceiling != null && ceiling.distance < 0) {
            posY -= ceiling.distance;
            if (yVel < 0) {
                yVel = 0;
            }
            return;
        }

        if (yVel < 0) {
            return;
        }
        ProbeResult floor = probeAirborneFloor();
        if (floor != null && floor.distance < 0) {
            posY += floor.distance;
            angle = clampGroundAngle(floor.angle);
            airborne = false;
            yVel = 0;
            groundVel = xVel;
        }
    }

    private void runAirborneUp() {
        resolveLeftWall(false);
        resolveRightWall(false);

        ProbeResult ceiling = probeAirborneCeiling();
        if (ceiling == null || ceiling.distance >= 0) {
            return;
        }
        posY -= ceiling.distance;
        if (((ceiling.angle + 0x20) & 0x40) == 0) {
            yVel = 0;
            return;
        }
        angle = clampGroundAngle(ceiling.angle);
        airborne = false;
        groundVel = yVel;
        if ((byte) ceiling.angle < 0) {
            groundVel = -groundVel;
        }
    }

    private void runAirborneLeft() {
        resolveRightWall(true);

        ProbeResult ceiling = probeAirborneCornerCeiling();
        if (ceiling != null && ceiling.distance < 0) {
            posY -= ceiling.distance;
            if (yVel < 0) {
                yVel = 0;
            }
            return;
        }

        if (yVel < 0) {
            return;
        }
        ProbeResult floor = probeAirborneFloor();
        if (floor != null && floor.distance < 0) {
            posY += floor.distance;
            angle = clampGroundAngle(floor.angle);
            airborne = false;
            yVel = 0;
            groundVel = xVel;
        }
    }

    private void resolveRightWall(boolean steepWallTransfersToGround) {
        ProbeResult wall = probeAirborneRightWall();
        if (wall == null || wall.distance >= 0) {
            return;
        }
        posX += wall.distance;
        applyAirborneSideWallVelocity(steepWallTransfersToGround, wall.angle);
    }

    private void resolveLeftWall(boolean steepWallTransfersToGround) {
        ProbeResult wall = probeAirborneLeftWall();
        if (wall == null || wall.distance >= 0) {
            return;
        }
        posX -= wall.distance;
        applyAirborneSideWallVelocity(steepWallTransfersToGround, wall.angle);
    }

    private void applyAirborneSideWallVelocity(boolean steepWallTransfersToGround, int wallAngle) {
        if (!steepWallTransfersToGround) {
            xVel = 0;
            return;
        }
        // ROM loc_3536E/loc_3547A always applies the position correction, but
        // retains x_vel when (angle+$30) is below $60. Only a steep wall turns
        // the diagonal airborne approach into ground motion.
        if (((wallAngle + 0x30) & 0xFF) >= 0x60) {
            xVel = 0;
            groundVel = yVel;
        }
    }

    // ROM sub_3526A wall probes: sub_FA1A / sub_FD32 are PAIRED wall checks at
    // (x_pos ± x_radius, y_pos ± y_radius). x_radius=$18, y_radius=$C at these call
    // sites, and y_pos is already posY - $13 (from the loc_34C98 shift).
    private ProbeResult probeAirborneRightWall() {
        int midY = posY - AIRBORNE_Y_OFFSET;
        return chooseDeeperProbe(
                ObjectTerrainUtils.checkRightWallDistWithFlipAwareAngle(posX + WALL_X_RADIUS, midY - WALL_Y_OFFSET),
                ObjectTerrainUtils.checkRightWallDistWithFlipAwareAngle(posX + WALL_X_RADIUS, midY + WALL_Y_OFFSET),
                0xC0);
    }

    private ProbeResult probeAirborneLeftWall() {
        int midY = posY - AIRBORNE_Y_OFFSET;
        return chooseDeeperProbe(
                ObjectTerrainUtils.checkLeftWallDistWithFlipAwareAngle(posX - WALL_X_RADIUS, midY - WALL_Y_OFFSET),
                ObjectTerrainUtils.checkLeftWallDistWithFlipAwareAngle(posX - WALL_X_RADIUS, midY + WALL_Y_OFFSET),
                0x40);
    }

    private ProbeResult probeGroundFloor() {
        return chooseDeeperProbe(
                ObjectTerrainUtils.checkFloorDistWithFlipAwareAngle(posX + GROUND_X_RADIUS, posY, GROUND_Y_RADIUS),
                ObjectTerrainUtils.checkFloorDistWithFlipAwareAngle(posX - GROUND_X_RADIUS, posY, GROUND_Y_RADIUS),
                0x00);
    }

    private ProbeResult probeAirborneFloor() {
        return chooseDeeperProbe(
                ObjectTerrainUtils.checkFloorDistWithFlipAwareAngle(
                        posX + GROUND_X_RADIUS, posY - AIRBORNE_Y_OFFSET, AIRBORNE_Y_RADIUS),
                ObjectTerrainUtils.checkFloorDistWithFlipAwareAngle(
                        posX - GROUND_X_RADIUS, posY - AIRBORNE_Y_OFFSET, AIRBORNE_Y_RADIUS),
                0x00);
    }

    // ROM sub_3526A ceiling probe: sub_FB5A is a PAIRED check at y = y_pos - y_radius
    // with sensors at x ± (x_radius - 2). x_radius=$A here, so sensors at x ± 8; probe
    // Y resolves to (posY - $13) - $1F = posY - $32.
    private ProbeResult probeAirborneCeiling() {
        int cornerX = GROUND_X_RADIUS - 2;
        return chooseDeeperProbe(
                ObjectTerrainUtils.checkCeilingDistWithFlipAwareAngle(
                        posX + cornerX, posY - AIRBORNE_Y_OFFSET, AIRBORNE_Y_RADIUS),
                ObjectTerrainUtils.checkCeilingDistWithFlipAwareAngle(
                        posX - cornerX, posY - AIRBORNE_Y_OFFSET, AIRBORNE_Y_RADIUS),
                0x80);
    }

    private ProbeResult probeAirborneCornerCeiling() {
        // ROM loc_3536E / loc_3547A call the same sub_FB5A as the straight-up case.
        return probeAirborneCeiling();
    }

    private ProbeResult chooseDeeperProbe(TerrainCheckResult primary, TerrainCheckResult secondary, int fallbackAngle) {
        ProbeResult first = toProbeResult(primary, fallbackAngle);
        ProbeResult second = toProbeResult(secondary, fallbackAngle);
        if (first == null) return second;
        if (second == null) return first;
        return (second.distance <= first.distance) ? second : first;
    }

    private void landFromAirDefault(ProbeResult floor) {
        posY += floor.distance;
        angle = clampGroundAngle(floor.angle);
        airborne = false;

        int floorAngle = floor.angle & 0xFF;
        if (((floorAngle + 0x20) & 0x40) != 0) {
            xVel = 0;
            if (yVel > -0x40) {
                yVel = -0x40;
            }
            groundVel = yVel;
        } else if (((floorAngle + 0x10) & 0x20) != 0) {
            yVel >>= 1;
            groundVel = yVel;
        } else {
            yVel = 0;
            groundVel = xVel;
        }

        if ((byte) floorAngle < 0) {
            groundVel = -groundVel;
        }
    }

    private void computeArcLinearVelocity(int destX, int destY) {
        int absGroundVel = Math.abs(groundVel);
        int d2 = absGroundVel;
        int d3 = absGroundVel;
        if (d2 == 0) {
            d2 = DASH_SPEED_LOW;
            d3 = DASH_SPEED_LOW;
        }

        int dx = destX - posX;
        if (dx < 0) {
            d2 = -d2;
        }
        int dy = destY - posY;
        if (dy < 0) {
            d3 = -d3;
        }

        if (Math.abs(dy) >= Math.abs(dx)) {
            int duration = (d3 == 0) ? 0 : (int) ((((long) dy) << 16) / d3);
            int calcXVel = (dx == 0 || duration == 0) ? 0 : (int) ((((long) dx) << 16) / duration);
            xVel = (short) calcXVel;
            yVel = (short) d3;
            arcTimer = arcTimerByteFromWord(Math.abs(duration));
            return;
        }

        int duration = (d2 == 0) ? 0 : (int) ((((long) dx) << 16) / d2);
        int calcYVel = (dy == 0 || duration == 0) ? 0 : (int) ((((long) dy) << 16) / duration);
        yVel = (short) calcYVel;
        xVel = (short) d2;
        arcTimer = arcTimerByteFromWord(Math.abs(duration));
    }

    private static int arcTimerByteFromWord(int durationWord) {
        // ROM writes the 16-bit duration to $3A(a0), then decrements the byte at
        // $3A(a0). On 68000 that is the high byte of the stored word.
        return (durationWord >> 8) & 0xFF;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void onUnload() {
        releaseAllPlayers();
    }

    @Override
    public void setDestroyed(boolean destroyed) {
        if (destroyed && !isDestroyed()) {
            releaseAllPlayers();
        }
        super.setDestroyed(destroyed);
    }

    // =============================================================
    // Render
    // =============================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) return;
        int frame = (timer >> ANIM_TIMER_SHIFT) & 1;
        renderer.drawFrameIndex(frame, posX, posY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) return;
        ctx.drawRect(posX, posY, WIDTH_PIXELS + 0x0B, HEIGHT_PIXELS,
                bodyDriven ? 0.2f : 0.6f,
                airborne ? 0.4f : 0.9f,
                rolling != 0 ? 0.4f : 0.9f);
    }

    // =============================================================
    // Helpers
    // =============================================================

    private AbstractPlayableSprite resolveSidekick() {
        ObjectServices svc = tryServices();
        if (svc == null) return null;
        PlayableEntity main = svc.playerQuery().mainPlayerOrNull();
        for (PlayableEntity sk : svc.playerQuery().playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (sk == main) {
                continue;
            }
            if (sk instanceof AbstractPlayableSprite p) {
                return p;
            }
        }
        return null;
    }

    private void playSfx(Sonic3kSfx sfx) {
        ObjectServices svc = tryServices();
        if (svc != null) {
            svc.playSfx(sfx.id);
        }
    }

    private void movePlatform(boolean withGravity, int gravity) {
        motion.x = posX;
        motion.y = posY;
        motion.xVel = (short) xVel;
        motion.yVel = (short) yVel;
        if (withGravity) {
            SubpixelMotion.moveSprite(motion, gravity);
        } else {
            SubpixelMotion.moveSprite2(motion);
        }
        posX = motion.x;
        posY = motion.y;
        xVel = (short) motion.xVel;
        yVel = (short) motion.yVel;
    }

    private void moveGrabbedPlayer(AbstractPlayableSprite player, PlayerGrabState state) {
        moveGrabbedPlayer(player, state, true);
    }

    private void moveGrabbedPlayer(AbstractPlayableSprite player,
                                   PlayerGrabState state,
                                   boolean resolveTerrainAfterMove) {
        // ROM loc_35070 calls MoveSprite2 on Player_1, adding x_vel/y_vel<<8 to
        // the full x_pos:x_sub/y_pos:y_sub longs. Keep the visible sprite
        // subpixel words in lockstep; otherwise the next centering step is a
        // frame late when x_sub/y_sub were nonzero at grab entry.
        player.move(player.getXSpeed(), player.getYSpeed());
        syncGrabStateSubpixelsFromPlayer(player, state);
        if (resolveTerrainAfterMove) {
            resolveGrabbedPlayerTerrain(player);
        }
        clampGrabbedPlayerToLevel(player);
        syncGrabStateSubpixelsFromPlayer(player, state);
    }

    private static void syncGrabStateSubpixelsFromPlayer(AbstractPlayableSprite player,
                                                         PlayerGrabState state) {
        state.xSub = (player.getXSubpixelRaw() >> 8) & 0xFF;
        state.ySub = (player.getYSubpixelRaw() >> 8) & 0xFF;
    }

    private void resolveGrabbedPlayerTerrain(AbstractPlayableSprite player) {
        if (player == null || !player.getAir()) {
            // A permitted later solid slot can land the carried player while
            // leaving object_control bit 0 active. The native loc_35070 path is
            // only MoveSprite2 + Player_LevelBound, so it does not immediately
            // turn that grounded result back into an air probe. This is
            // observable when an MGZ stomp bridge clears OnObj without setting
            // InAir before the carrier's later slot runs.
            return;
        }
        CollisionSystem collisionSystem = player.currentCollisionSystemOrNull();
        if (collisionSystem == null) {
            return;
        }
        collisionSystem.resolveAirCollision(FrameCollisionPlan.terrainOnly(), player, landed -> landed.setYSpeed((short) 0));
        // ROM MGZ carry does not feed terrain angle back into Sonic's walk/run
        // presentation; the captured state keeps flat-surface animation semantics.
        player.setAngle((byte) 0);
    }

    private void releaseAllPlayers() {
        for (Map.Entry<PlayableEntity, PlayerGrabState> entry : playerStates.entrySet()) {
            PlayerGrabState state = entry.getValue();
            state.grabbed = false;
            state.routine = 0;
            state.entrySideBias = 0;
            if (entry.getKey() instanceof AbstractPlayableSprite player) {
                if (player.isMgzTopPlatformCarryOwnedBy(this)) {
                    ObjectControlState.none().applyTo(player);
                    player.setMgzTopPlatformCarrySolidContactObject(null);
                    player.setControlLocked(false);
                    player.setOnObject(false);
                    player.setForcedAnimationId(-1);
                    player.restoreDefaultRadii();
                    player.clearWallClingState();
                    ObjectServices svc = tryServices();
                    if (svc != null && svc.objectManager() != null) {
                        svc.objectManager().clearRidingObject(player);
                    }
                }
            }
        }
        playerStates.clear();
    }

    void syncFromLauncher(int newX, int newY) {
        posX = newX;
        posY = newY;
        homeX = newX;
        homeY = newY;
    }

    void advanceAnimationTimer(int delta) {
        timer = (timer + delta) & 0xFFFF;
    }

    boolean isAnyPlayerGrabbed() {
        for (PlayerGrabState state : playerStates.values()) {
            if (state.routine == 4) {
                return true;
            }
        }
        return false;
    }

    boolean isBodyDriven() {
        return bodyDriven;
    }

    void activateFromLauncher(int launchVelocity) {
        currentSubtype = 0;
        bodyDriven = true;
        groundVel = launchVelocity;
        xVel = launchVelocity;
        arcMode = 0;
        firstActivatedWaypointEntryStart = -1;
        lastActivatedWaypointEntryStart = -1;
        waypointActivationCount = 0;
        syncMotionSubpixelsToArc();
        rolling = 1;
        airborne = false;
    }

    private void syncMotionSubpixelsToArc() {
        arcXSub = (motion.xSub & 0xFF) << 8;
        arcYSub = (motion.ySub & 0xFF) << 8;
    }

    private void syncArcSubpixelsToMotion() {
        motion.xSub = (arcXSub >> 8) & 0xFF;
        motion.ySub = (arcYSub >> 8) & 0xFF;
    }

    private ObjectInstance currentRidingObject(AbstractPlayableSprite player) {
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return null;
        }
        return svc.objectManager().getRidingObject(player);
    }

    private void clampGrabbedPlayerToLevel(AbstractPlayableSprite player) {
        if (player == null || player.currentLevelManager() == null
                || player.currentLevelManager().getCurrentLevel() == null) {
            return;
        }
        int minX = player.currentLevelManager().getCurrentLevel().getMinX() + player.getXRadius();
        int maxX = player.currentLevelManager().getCurrentLevel().getMaxX() - player.getXRadius();
        int minY = player.currentLevelManager().getCurrentLevel().getMinY() + player.getYRadius();
        int maxY = player.currentLevelManager().getCurrentLevel().getMaxY() - player.getYRadius();
        int clampedX = Math.max(minX, Math.min(maxX, player.getCentreX()));
        int clampedY = Math.max(minY, Math.min(maxY, player.getCentreY()));
        if (clampedX != player.getCentreX()) {
            NativePositionOps.writeXPosPreserveSubpixel(player, clampedX);
        }
        if (clampedY != player.getCentreY()) {
            NativePositionOps.writeYPosPreserveSubpixel(player, clampedY);
        }
    }

    /**
     * ROM {@code Obj_MGZTopPlatform} init stores
     * {@code move.b #$18,width_pixels(a0)} / {@code move.b #$C,height_pixels(a0)}
     * (docs/skdisasm/sonic3k.asm:71485-71486). Render_Sprites builds the
     * render_flags bit-7 box from those bytes, and that bit is the gate
     * SolidObjectTop tests before doing any solid work
     * (sonic3k.asm:41390-41392). The AbstractObjectInstance default of 16 is
     * narrower than $18 and taller than $C, so the top platform stopped being
     * solid before the ROM's box left the screen. See pitfall P60.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return 0x18;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return 0x0C;
    }
}
