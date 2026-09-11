package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.solid.PlayerStandingState;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.NativePositionOps;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * OOZ Launcher (Object 0x3D) - Striped block from Oil Ocean Zone that launches
 * a rolling player and breaks into fragments.
 * <p>
 * When a player lands on this block while rolling, the block breaks apart into
 * 16 fragments and spawns an invisible child object that tracks the player and
 * launches them toward the nearest LauncherBall (Object 0x48).
 * <p>
 * <b>Disassembly Reference:</b> docs/s2disasm/s2.asm Obj3D (around 50934-51211)
 * <p>
 * <h3>Subtypes</h3>
 * <table border="1">
 *   <tr><th>Subtype</th><th>Behavior</th></tr>
 *   <tr><td>0x00</td><td>Vertical: uses ArtNem_StripedBlocksVert, launches player RIGHT (+$800 X)</td></tr>
 *   <tr><td>!=0</td><td>Horizontal: uses ArtNem_StripedBlocksHoriz, launches player UP (-$800 Y)</td></tr>
 * </table>
 */
public class OOZLauncherObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(OOZLauncherObjectInstance.class.getName());

    // ========================================================================
    // ROM Constants (s2.asm lines 50494-50593)
    // ========================================================================

    // Solid collision parameters: d1=$1B, d2=$10, d3=$11
    private static final int SOLID_HALF_WIDTH = 0x1B;   // 27 pixels
    private static final int SOLID_HALF_HEIGHT = 0x11;   // 17 pixels (d3)
    private static final int SOLID_HEIGHT_D2 = 0x10;     // 16 pixels (d2 = half height for air)

    // Width for display/proximity (ROM: move.b #$10,width_pixels)
    private static final int WIDTH_PIXELS = 0x10;

    // Launch velocity (ROM: move.w #$800,x_vel or y_vel)
    private static final int LAUNCH_VELOCITY = 0x800;

    // Inertia set on player during launch (ROM: move.w #$800,inertia)
    private static final int LAUNCH_INERTIA = 0x800;

    // Fragment gravity (ROM: addi.w #$18,y_vel)
    private static final int FRAGMENT_GRAVITY = 0x18;

    // Fragment velocity table (ROM: word_2507A - 16 entries, X/Y pairs)
    // Each pair is initial velocity for one fragment piece
    private static final int[][] FRAGMENT_VELOCITIES = {
            {-0x400, -0x400},   // 0: Upper-left corner
            {-0x200, -0x400},   // 1: Upper-center-left
            { 0x200, -0x400},   // 2: Upper-center-right
            { 0x400, -0x400},   // 3: Upper-right corner
            {-0x3C0, -0x200},   // 4: Mid-upper-left
            {-0x1C0, -0x200},   // 5: Mid-upper-center-left
            { 0x1C0, -0x200},   // 6: Mid-upper-center-right
            { 0x3C0, -0x200},   // 7: Mid-upper-right
            {-0x380,  0x200},   // 8: Mid-lower-left
            {-0x180,  0x200},   // 9: Mid-lower-center-left
            { 0x180,  0x200},   // 10: Mid-lower-center-right
            { 0x380,  0x200},   // 11: Mid-lower-right
            {-0x340,  0x400},   // 12: Lower-left corner
            {-0x140,  0x400},   // 13: Lower-center-left
            { 0x140,  0x400},   // 14: Lower-center-right
            { 0x340,  0x400},   // 15: Lower-right corner
    };

    // Proximity detection bounds for invisible launcher
    // ROM: addi.w #$10,d0; cmpi.w #$20,d0 (horizontal)
    // ROM: cmpi.w #$10,d1 (vertical)
    private static final int PROXIMITY_HALF_X = 0x10;   // 16 pixels
    private static final int PROXIMITY_FULL_X = 0x20;   // 32 pixels
    private static final int PROXIMITY_Y = 0x10;         // 16 pixels

    // ========================================================================
    // State
    // ========================================================================

    private boolean isVertical;    // subtype == 0 → vertical (launch right)
    private boolean broken = false;
    private boolean launcherActive = false;
    private boolean invisibleLauncherOnly = false;
    private int sameFrameLauncherScanFrame = Integer.MIN_VALUE;
    private boolean parentFragmentActive = false;
    private int parentFragmentX;
    private int parentFragmentY;
    private int parentFragmentSubX;
    private int parentFragmentSubY;
    private int parentFragmentVelX;
    private int parentFragmentVelY;
    private int parentFragmentFrameIndex;
    private int parentFragmentPieceIndex;

    // Invisible launcher states per player (ROM routine 6 states).
    private final Map<AbstractPlayableSprite, LauncherPlayerState> playerStates = new IdentityHashMap<>();
    private static final Map<AbstractPlayableSprite, OOZLauncherObjectInstance> activeLaunchers = new IdentityHashMap<>();
    private static final Map<AbstractPlayableSprite, LauncherMoveSample> recentLauncherMoves = new IdentityHashMap<>();

    private final SolidObjectParams solidParams;

    public OOZLauncherObjectInstance(ObjectSpawn spawn, String name) {
        this(spawn, name, false);
    }

    private OOZLauncherObjectInstance(ObjectSpawn spawn, String name, boolean invisibleLauncherOnly) {
        super(spawn, name);
        this.isVertical = (spawn.subtype() & 0xFF) == 0;
        this.solidParams = SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HEIGHT_D2, SOLID_HALF_HEIGHT);
        this.invisibleLauncherOnly = invisibleLauncherOnly;
        if (invisibleLauncherOnly) {
            this.broken = true;
            this.launcherActive = true;
        }
    }

    @Override
    public OOZLauncherObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new OOZLauncherObjectInstance(ctx.spawn(), "OOZLauncher");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        if (invisibleLauncherOnly) {
            if (launcherActive && vIntRunCount != sameFrameLauncherScanFrame) {
                updateInvisibleLauncher(vIntRunCount, player);
            }
        } else if (parentFragmentActive) {
            updateParentFragment();
        } else if (!broken) {
            updateMainBlock(vIntRunCount, player);
        }
    }

    /**
     * Routine 2: Main block logic.
     * Saves player animation/velocity before solid collision check,
     * then checks if rolling player is standing on block.
     */
    private void updateMainBlock(int vIntRunCount, AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // Save player state before solid collision (ROM: Obj3D_Main)
        for (AbstractPlayableSprite participant : playerParticipants(player)) {
            LauncherPlayerState state = stateFor(participant);
            state.savedAnim = participant.getAnimationId();
            state.savedYVel = participant.getYSpeed();
            state.hasSavedState = true;
        }

        // Solid collision is handled by SolidObjectProvider/SolidObjectListener
        // The onSolidContact callback will check if player is rolling and trigger launch
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (broken || !contact.standing()) {
            return;
        }

        if (!playerParticipants(player).contains(player)) {
            return;
        }

        // Check if standing player is rolling (ROM: cmpi.b #AniIDSonAni_Roll,objoff_32)
        LauncherPlayerState state = stateFor(player);
        int savedAnim = state.hasSavedState ? state.savedAnim : player.getAnimationId();
        int savedYVel = state.hasSavedState ? state.savedYVel : player.getYSpeed();

        if (savedAnim == Sonic2AnimationIds.ROLL.id()) {
            for (AbstractPlayableSprite participant : playerParticipants(player)) {
                PlayerStandingState previous = services().solidExecutionRegistry().previousStanding(this, participant);
                if (participant == player || previous.standing()) {
                    LauncherPlayerState participantState = stateFor(participant);
                    int participantYVel = participantState.hasSavedState
                            ? participantState.savedYVel
                            : participant.getYSpeed();
                    launchPlayer(participant, participantYVel, frameCounter);
                }
            }
            breakBlock(frameCounter, player);
        }
    }

    /**
     * Launch a player from the block (ROM: loc_24EB8).
     * Sets rolling state, preserves Y velocity, and transitions to airborne.
     */
    private void launchPlayer(AbstractPlayableSprite player, int savedYVel, int frameCounter) {
        // ROM: bset #status.player.rolling,status(a1)
        // setRolling(true) handles y_radius=14, x_radius=7 internally
        player.setRolling(true);
        // ROM: move.b #AniIDSonAni_Roll,anim(a1)
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        // ROM: move.w d1,y_vel(a1) - restore saved Y velocity
        player.setYSpeed((short) savedYVel);
        // ROM: bset #status.player.in_air,status(a1)
        player.setAir(true);
        // ROM: bclr #status.player.on_object,status(a1)
        player.setOnObject(false);
        // ROM: move.b #2,routine(a1) - airborne routine (handled by setAir)
    }

    /**
     * Break the block into fragments and spawn invisible launcher (ROM: loc_24F04).
     */
    private void breakBlock(int frameCounter, AbstractPlayableSprite player) {
        broken = true;
        launcherActive = false;
        for (LauncherPlayerState state : playerStates.values()) {
            state.launcherState = 0;
        }

        // Obj3D loc_24F04 allocates the invisible routine-6 launcher with
        // AllocateObjectAfterCurrent before BreakObjectToPieces mutates the
        // current object into fragment routine 4 (s2.asm:51040-51062).
        OOZLauncherObjectInstance invisibleLauncher =
                spawnChild(() -> new OOZLauncherObjectInstance(spawn, "OOZLauncher", true));
        invisibleLauncher.updateInvisibleLauncher(frameCounter, player);
        invisibleLauncher.sameFrameLauncherScanFrame = frameCounter;
        int fragmentFrameIndex = isVertical ? 1 : 3;
        startParentFragment(fragmentFrameIndex);
        spawnFragmentChildren(fragmentFrameIndex);

        // Play smash sound
        try {
            services().playSfx(Sonic2Sfx.SLOW_SMASH.id);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }

        updateParentFragment();
    }

    /**
     * Keep the current Obj3D slot as fragment piece 0.
     */
    private void startParentFragment(int fragmentFrameIndex) {
        parentFragmentActive = true;
        parentFragmentX = spawn.x();
        parentFragmentY = spawn.y();
        parentFragmentSubX = parentFragmentX << 8;
        parentFragmentSubY = parentFragmentY << 8;
        parentFragmentVelX = FRAGMENT_VELOCITIES[0][0];
        parentFragmentVelY = FRAGMENT_VELOCITIES[0][1];
        parentFragmentFrameIndex = fragmentFrameIndex;
        parentFragmentPieceIndex = 0;
    }

    /**
     * Spawn remaining fragment pieces with velocities from ROM table.
     */
    private void spawnFragmentChildren(int fragmentFrameIndex) {
        ObjectManager objectManager = services().objectManager();
        ObjectRenderManager renderManager = services().renderManager();
        if (objectManager == null || renderManager == null) {
            return;
        }

        String artKey = isVertical ? Sonic2ObjectArtKeys.OOZ_LAUNCHER_VERT : Sonic2ObjectArtKeys.OOZ_LAUNCHER_HORIZ;
        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        ObjectSpriteSheet sheet = renderManager.getSheet(artKey);
        if (renderer == null || sheet == null) {
            return;
        }

        SpriteMappingFrame fragmentFrame = sheet.getFrameCount() > fragmentFrameIndex
                ? sheet.getFrame(fragmentFrameIndex) : null;
        if (fragmentFrame == null) {
            return;
        }

        List<SpriteMappingPiece> pieces = fragmentFrame.pieces();
        int count = Math.min(pieces.size(), FRAGMENT_VELOCITIES.length);

        for (int i = 1; i < count; i++) {
            SpriteMappingPiece piece = pieces.get(i);
            final int index = i;
            spawnChild(() -> new LauncherFragmentInstance(
                    spawn.x(), spawn.y(),
                    FRAGMENT_VELOCITIES[index][0], FRAGMENT_VELOCITIES[index][1],
                    piece, renderer));
        }
    }

    private void updateParentFragment() {
        parentFragmentSubX += parentFragmentVelX;
        parentFragmentSubY += parentFragmentVelY;
        parentFragmentX = parentFragmentSubX >> 8;
        parentFragmentY = parentFragmentSubY >> 8;
        parentFragmentVelY += FRAGMENT_GRAVITY;

        Camera camera = services().camera();
        if (camera != null && parentFragmentY > camera.getY() + 224 + 32) {
            setDestroyed(true);
        }
    }

    // ========================================================================
    // Routine 6: Invisible Launcher (ROM: Obj3D_InvisibleLauncher)
    // ========================================================================

    /**
     * Update the invisible launcher that tracks players after block breaks.
     * Each player has independent state: 0 = proximity detect, 2 = tracking.
     */
    private void updateInvisibleLauncher(int frameCounter, AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        boolean anyActive = false;
        for (AbstractPlayableSprite participant : playerParticipants(player)) {
            LauncherPlayerState state = stateFor(participant);
            state.launcherState = processLauncherState(participant, state.launcherState, frameCounter);
            anyActive |= state.launcherState != 0;
        }

        // With no tracked player, Obj3D branches to MarkObjGone3. That helper
        // returns while the object is inside the coarse camera range and only
        // deletes after it scrolls out (s2.asm:30259-30269).
        if (!anyActive) {
            if (!isInRange()) {
                launcherActive = false;
                setDestroyed(true);
            }
        }
    }

    /**
     * Per-player launcher state machine.
     *
     * @return updated state
     */
    private int processLauncherState(AbstractPlayableSprite player, int state, int frameCounter) {
        return switch (state) {
            case 0 -> processProximityDetection(player);
            case 2 -> processTracking(player, frameCounter);
            default -> 0;
        };
    }

    /**
     * State 0: Proximity detection (ROM: loc_24F84).
     * Checks if player is within bounds and starts the launch.
     */
    private int processProximityDetection(AbstractPlayableSprite player) {
        // Horizontal check: within 32 pixels centered on object
        // ROM: sub.w x_pos(a1),d0; addi.w #$10,d0; cmpi.w #$20,d0
        int dx = player.getCentreX() - spawn.x() + PROXIMITY_HALF_X;
        if (dx < 0 || dx >= PROXIMITY_FULL_X) {
            return 0;
        }

        // Vertical check
        int dy = player.getCentreY() - spawn.y();
        if (!isVertical) {
            dy += PROXIMITY_HALF_X;  // ROM: addi.w #$10,d1 (for horizontal subtype)
        }
        if (dy < 0 || dy >= PROXIMITY_Y) {
            return 0;
        }

        // ROM: Skip Tails if flying (CPU routine 4)
        // The engine doesn't expose Tails CPU routine directly, but this check
        // prevents capturing Tails while they're in flight mode
        if (player.isCpuControlled()
                && player.getAir() && !player.getRolling()) {
            return 0;
        }

        // Launch the player (ROM: loc_24FC2).
        // Obj3D does not write global Control_Locked; Obj01_Control keeps
        // refreshing Ctrl_1_Logical while obj_control owns movement.
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        // ROM: move.b #AniIDSonAni_Roll,anim(a1)
        player.setAnimationId(Sonic2AnimationIds.ROLL);
        // ROM: move.w #$800,inertia(a1)
        player.setGSpeed((short) LAUNCH_INERTIA);

        if (isVertical) {
            // Subtype 0: Launch right (ROM: loc_24FF0)
            // ROM: move.w y_pos(a0),y_pos(a1)
            NativePositionOps.writeYPosPreserveSubpixel(player, spawn.y());
            // ROM: move.w #$800,x_vel(a1); move.w #0,y_vel(a1)
            player.setXSpeed((short) LAUNCH_VELOCITY);
            player.setYSpeed((short) 0);
        } else {
            // Subtype != 0: Launch up (ROM: after tst.b subtype)
            // ROM: move.w x_pos(a0),x_pos(a1)
            NativePositionOps.writeXPosPreserveSubpixel(player, spawn.x());
            // ROM: move.w #0,x_vel(a1); move.w #-$800,y_vel(a1)
            player.setXSpeed((short) 0);
            player.setYSpeed((short) -LAUNCH_VELOCITY);
        }

        // ROM: Common post-launch setup (loc_25002)
        player.setPushing(false);
        player.setAir(true);
        player.setOnObject(true);
        activeLaunchers.put(player, this);

        // Play roll sound (ROM: move.w #SndID_Roll,d0; jsr PlaySound)
        try {
            services().playSfx(Sonic2Sfx.ROLL.id);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }

        return 2; // Advance to tracking state
    }

    /**
     * State 2: Tracking (ROM: loc_25036 / Obj3D_MoveCharacter).
     * Moves the player along their velocity until off-screen or captured by LauncherBall.
     */
    private int processTracking(AbstractPlayableSprite player, int frameCounter) {
        // If player is no longer on-object (captured by LauncherBall or released), stop tracking
        if (!player.isOnObject() || !player.isObjectControlled()) {
            return 0;
        }

        // Check if player is off-screen (ROM: btst #render_flags.on_screen)
        if (!isPlayerOnScreen(player)) {
            // Release player
            ObjectControlState.none().applyTo(player);
            player.setControlLocked(false);
            player.setAir(true);
            player.setOnObject(false);
            activeLaunchers.remove(player, this);
            return 0;
        }

        // Move player by velocity (ROM: Obj3D_MoveCharacter)
        // ROM uses 16.16 fixed point: ext.l d0; asl.l #8,d0; add.l d0,x_pos(a1)
        int beforeX = player.getCentreX();
        int beforeY = player.getCentreY();
        player.move(player.getXSpeed(), player.getYSpeed());
        recentLauncherMoves.put(player, new LauncherMoveSample(frameCounter, beforeX, beforeY));

        return 2; // Stay in tracking state
    }

    static boolean crossedIntoLauncherBallThisFrame(AbstractPlayableSprite player, int frameCounter,
                                                     int ballX, int ballY) {
        LauncherMoveSample sample = recentLauncherMoves.get(player);
        if (sample == null || sample.frameCounter != frameCounter) {
            return false;
        }
        return !insideLauncherBall(sample.beforeX, sample.beforeY, ballX, ballY)
                && insideLauncherBall(player.getCentreX(), player.getCentreY(), ballX, ballY);
    }

    private static boolean insideLauncherBall(int playerX, int playerY, int ballX, int ballY) {
        int dx = playerX - ballX + PROXIMITY_HALF_X;
        int dy = playerY - ballY + PROXIMITY_HALF_X;
        return dx >= 0 && dx < PROXIMITY_FULL_X && dy >= 0 && dy < PROXIMITY_FULL_X;
    }

    private List<AbstractPlayableSprite> playerParticipants(AbstractPlayableSprite updatePlayer) {
        List<PlayableEntity> queried = services().playerQuery().playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS);
        ArrayList<AbstractPlayableSprite> players = new ArrayList<>(queried.size() + 1);
        for (PlayableEntity participant : queried) {
            if (participant instanceof AbstractPlayableSprite sprite) {
                players.add(sprite);
            }
        }
        if (updatePlayer != null && !players.contains(updatePlayer)) {
            players.add(updatePlayer);
        }
        return players;
    }

    private LauncherPlayerState stateFor(AbstractPlayableSprite player) {
        return playerStates.computeIfAbsent(player, ignored -> new LauncherPlayerState());
    }

    private boolean isPlayerOnScreen(AbstractPlayableSprite player) {
        Camera camera = player.currentCamera();
        return camera == null || camera.isOnScreen(player);
    }

    public static void clearActiveLauncherFor(AbstractPlayableSprite player) {
        OOZLauncherObjectInstance launcher = activeLaunchers.remove(player);
        recentLauncherMoves.remove(player);
        if (launcher != null) {
            LauncherPlayerState state = launcher.playerStates.get(player);
            if (state != null) {
                state.launcherState = 0;
            }
        }
    }

    public static void clearActiveLaunchers() {
        activeLaunchers.clear();
        recentLauncherMoves.clear();
    }

    // ========================================================================
    // SolidObjectProvider
    // ========================================================================

    @Override
    public int getX() {
        return parentFragmentActive ? parentFragmentX : spawn.x();
    }

    @Override
    public int getY() {
        return parentFragmentActive ? parentFragmentY : spawn.y();
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return getX();
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return solidParams;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !broken;
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public boolean hasMonitorSolidity() {
        return false;
    }

    @Override
    public boolean landingPreservesRolling(PlayableEntity playerEntity) {
        // Obj3D_Main calls JmpTo7_SolidObject, then loc_24EB8 restores the
        // rolling bit and ball radii itself before forcing the player airborne
        // (docs/s2disasm/s2.asm:50981, 51003-51017). It never runs
        // Sonic_ResetOnFloor, so the SolidObject_Landed y_pos must survive
        // without the generic roll-clear's stand-radius lift.
        return true;
    }

    @Override
    public int getTopLandingSnapAdjustment(PlayableEntity playerEntity, int solidTopYRadius) {
        // The shared S2 full-solid overlap keeps the standing radius on the
        // bottom half, but Obj3D's break-frame top landing is immediately
        // followed by loc_24EB8's explicit roll-radius restore. Move the
        // SolidObject_Landed snap back to the live rolling y_radius surface
        // before that object-local launch state runs.
        return Math.max(0, solidTopYRadius - playerEntity.getYRadius());
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (parentFragmentActive) {
            appendParentFragmentRenderCommands();
            return;
        }
        if (broken) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        String artKey = isVertical ? Sonic2ObjectArtKeys.OOZ_LAUNCHER_VERT : Sonic2ObjectArtKeys.OOZ_LAUNCHER_HORIZ;
        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(isVertical ? 0 : 2, spawn.x(), spawn.y(), false, false);
    }

    private void appendParentFragmentRenderCommands() {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        String artKey = isVertical ? Sonic2ObjectArtKeys.OOZ_LAUNCHER_VERT : Sonic2ObjectArtKeys.OOZ_LAUNCHER_HORIZ;
        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        ObjectSpriteSheet sheet = renderManager.getSheet(artKey);
        if (renderer == null || !renderer.isReady() || sheet == null
                || sheet.getFrameCount() <= parentFragmentFrameIndex) {
            return;
        }

        SpriteMappingFrame fragmentFrame = sheet.getFrame(parentFragmentFrameIndex);
        if (fragmentFrame.pieces().size() <= parentFragmentPieceIndex) {
            return;
        }
        renderer.drawPieces(List.of(fragmentFrame.pieces().get(parentFragmentPieceIndex)),
                parentFragmentX, parentFragmentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw solid collision box
        int x = spawn.x();
        int y = spawn.y();
        float r = broken ? 0.3f : 0.0f;
        float g = broken ? 0.3f : 1.0f;
        float b = isVertical ? 1.0f : 0.5f;
        ctx.drawRect(x, y, SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, r, g, b);

        if (launcherActive) {
            // Draw proximity detection box
            int proxCenterX = x;
            int proxCenterY = isVertical ? (y + PROXIMITY_Y / 2) : y;
            ctx.drawRect(proxCenterX, proxCenterY, PROXIMITY_HALF_X, PROXIMITY_Y / 2,
                    1.0f, 1.0f, 0.0f);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public boolean isPersistent() {
        return invisibleLauncherOnly && launcherActive;
    }

    // ========================================================================
    // Fragment inner class
    // ========================================================================

    /**
     * Fragment piece spawned when the launcher block breaks.
     * Follows ballistic trajectory with gravity (ROM: Obj3D_Fragment, routine 4).
     */
    public static class LauncherFragmentInstance extends AbstractObjectInstance implements RewindRecreatable {

        private static final int GRAVITY = 0x18;  // ROM: addi.w #$18,y_vel(a0)
        /** Obj3D_Init sets width_pixels = $10; BreakObjectToPieces copies it to each piece. */
        private static final int FRAGMENT_WIDTH_PIXELS = 0x10;
        /** BuildSprites_ApproxYCheck assumed radius (Obj3D never sets explicit_height). */
        private static final int FRAGMENT_APPROX_RENDER_Y_MARGIN = 0x20;

        /** render_flags.on_screen as latched by the previous BuildSprites pass. */
        private boolean romRenderFlag = true;
        private int currentX;
        private int currentY;
        private int subX;   // 8.8 fixed point
        private int subY;   // 8.8 fixed point
        private int velX;   // 8.8 fixed point
        private int velY;   // 8.8 fixed point
        private final SpriteMappingPiece piece;
        private final PatternSpriteRenderer renderer;
        private final List<SpriteMappingPiece> pieceList;

        public LauncherFragmentInstance(int x, int y, int velX, int velY,
                                        SpriteMappingPiece piece, PatternSpriteRenderer renderer) {
            super(new ObjectSpawn(x, y, 0x3D, 0, 0, false, 0), "LauncherFragment");
            this.currentX = x;
            this.currentY = y;
            this.subX = x << 8;
            this.subY = y << 8;
            this.velX = velX;
            this.velY = velY;
            this.piece = piece;
            this.renderer = renderer;
            this.pieceList = piece != null ? List.of(piece) : List.of();
        }

        public LauncherFragmentInstance(int x, int y, int velX, int velY) {
            this(x, y, velX, velY, null, null);
        }

        @Override
        public LauncherFragmentInstance recreateForRewind(RewindRecreateContext ctx) {
            return new LauncherFragmentInstance(ctx.spawn().x(), ctx.spawn().y(), 0, 0, null, null);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (isDestroyed()) {
                return;
            }

            // ROM: JmpTo10_ObjectMove, then addi.w #$18,y_vel(a0)
            subX += velX;
            subY += velY;
            currentX = subX >> 8;
            currentY = subY >> 8;
            velY += GRAVITY;
            // ObjectMove writes x_pos/y_pos, so the piece's SST position (and therefore
            // everything downstream of it, including the BuildSprites on_screen latch)
            // must follow the integrated position rather than stay at the block's origin.
            updateDynamicSpawn(currentX & 0xFFFF, currentY & 0xFFFF);

            // ROM Obj3D_Fragment (docs/s2disasm/s2.asm:51069-51075):
            //   btst #render_flags.on_screen,render_flags(a0); beq -> DeleteObject.
            // The bit is the one the previous BuildSprites pass latched, so a piece that
            // left the render box in ANY direction is deleted on its next step. The
            // previous predicate only deleted pieces that fell below the camera, so
            // pieces thrown sideways or upward held their SST slot indefinitely and
            // skewed every later AllocateObject in the act.
            if (!romRenderFlag) {
                setDestroyed(true);
            }
        }

        @Override
        public void refreshPostCameraRenderState() {
            romRenderFlag = isWithinRenderSpriteBounds(getOnScreenHalfWidth(), getOnScreenHalfHeight());
        }

        @Override
        public int getOnScreenHalfWidth() {
            return FRAGMENT_WIDTH_PIXELS;
        }

        @Override
        public int getOnScreenHalfHeight() {
            return FRAGMENT_APPROX_RENDER_Y_MARGIN;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || renderer == null || !renderer.isReady() || pieceList.isEmpty()) {
                return;
            }
            renderer.drawPieces(pieceList, currentX, currentY, false, false);
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(4);
        }
    }

    private static final class LauncherPlayerState {
        private int launcherState;
        private int savedAnim;
        private int savedYVel;
        private boolean hasSavedState;
    }

    private record LauncherMoveSample(int frameCounter, int beforeX, int beforeY) {
    }

}
