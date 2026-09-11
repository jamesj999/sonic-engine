package com.openggf.game.sonic2.objects;
import com.openggf.game.sonic2.slotmachine.CNZPrizeSoundState;
import com.openggf.level.objects.BoxObjectInstance;

import com.openggf.audio.GameSound;
import com.openggf.game.PlayableEntity;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.Sonic2ZoneFeatureProvider;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.*;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.game.sonic2.slotmachine.CNZSlotMachineManager;
import com.openggf.game.sonic2.slotmachine.CNZSlotMachineRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * CNZ Point Pokey Object (ObjD6).
 * <p>
 * A cage object that captures the player and awards prizes.
 * <p>
 * <b>Subtypes:</b>
 * <ul>
 *   <li><b>0x00</b>: Simple point giver - player enters cage, receives 10 points</li>
 *   <li><b>0x01</b>: Slot machine linked - triggers slot machine, spawns ring/bomb prizes</li>
 * </ul>
 * <p>
 * <b>Behavior (from s2.asm lines 58511-58771):</b>
 * <ol>
 *   <li>Solid object collision with player</li>
 *   <li>When player touches from above (d4 negative), capture sequence begins</li>
 *   <li>Player capture: position locked to cage center, velocity zeroed, rolling state, obj_control = 0x81</li>
 *   <li>For subtype 0x00: countdown timer, then eject</li>
 *   <li>For subtype 0x01: wait for slot machine, spawn prizes, then eject</li>
 *   <li>Exit: player ejected with downward velocity (+0x400)</li>
 *   <li>Sound effect: SndID_CasinoBonus (0xC0)</li>
 * </ol>
 * <p>
 * <b>Collision Parameters (from loc_2BBA6):</b>
 * <ul>
 *   <li>d1 = 0x23 (35) - half width + 11</li>
 *   <li>d2 = 0x10 (16) - air half height</li>
 *   <li>d3 = 0x11 (17) - ground half height</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm ObjD6 (Point Pokey / CNZ Cage)
 */
public class PointPokeyObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(PointPokeyObjectInstance.class.getName());

    // Collision dimensions from disassembly (loc_2BBA6)
    private static final int HALF_WIDTH = 0x23;         // 35
    private static final int AIR_HALF_HEIGHT = 0x10;    // 16
    private static final int GROUND_HALF_HEIGHT = 0x11; // 17

    // Timing constants
    private static final int COUNTDOWN_FRAMES = 0x78;   // 120 frames (~2 seconds)
    private static final int ACTIVE_ANIM_SPEED = 2;     // Toggle animation frame every 2 frames (from Ani_objD6)

    // Exit velocity
    private static final int EXIT_VELOCITY = 0x400;     // Downward exit speed (positive Y is down)

    // Player states
    private static final int STATE_IDLE = 0;
    private static final int STATE_OCCUPIED = 1;
    private static final int STATE_WAITING_SLOT = 2;
    private static final int STATE_SPAWNING_PRIZES = 3;
    private static final int STATE_RELEASE_COOLDOWN = 4;

    // Animation frames
    private static final int FRAME_IDLE = 0;
    private static final int FRAME_ACTIVE = 1;

    // Max prizes (from disassembly - 16 max)
    private static final int MAX_PRIZES = 16;

    // Angle increments for prize spawning (from s2.asm lines 58626, 58670)
    // Note: These are SWAPPED from what you might expect - bombs use 0x90, rings use 0x89
    private static final int RING_ANGLE_INCREMENT = 0x89;
    private static final int BOMB_ANGLE_INCREMENT = 0x90;

    // Prize spawn radius - ROM CalcSine returns ~256, divided by 2 = 128 pixels
    private static final int PRIZE_RADIUS = 0x80;  // 128 pixels (from s2.asm line 58631-58638)

    // State tracking
    private int playerState = STATE_IDLE;
    private int countdown = 0;
    private int mappingFrame = FRAME_IDLE;

    // Linked mode state
    private boolean isLinkedMode;
    private CNZSlotMachineManager slotMachineManager;
    private int slotReward = 0;
    private int prizesToSpawn = 0;        // Total prizes left to spawn (SlotMachine_Reward equivalent)
    private int prizeAngle = 0;
    private final int[] activePrizeCount = new int[1];  // Active prizes on screen (objoff_2C equivalent)

    // Contact tracking
    private int lastContactFrame = -2;

    // Animation timing (Bug fix #2)
    private int animationTimer = 0;

    // Track when player is occupied for priority control (Bug fix #4)
    private boolean playerOccupied = false;
    private boolean capturedPlayerUsesRideState = false;
    private boolean capturedPlayerPinballMode = false;
    private int capturedSidekickIndex = -1;

    // Reference to level manager (for spawning prizes)

    // Cached slot display offset (calculated once at first render)
    private int slotDisplayOffsetX = CNZSlotMachineRenderer.DEFAULT_OFFSET_X;
    private int slotDisplayOffsetY = CNZSlotMachineRenderer.DEFAULT_OFFSET_Y;
    private boolean slotDisplayOffsetCalculated = false;

    public PointPokeyObjectInstance(ObjectSpawn spawn, String name) {
        // Use cyan color for debug box
        super(spawn, name, HALF_WIDTH, GROUND_HALF_HEIGHT, 0.2f, 0.8f, 0.8f, false);
        this.isLinkedMode = (spawn.subtype() & 0xFF) == 0x01;
    }

    @Override
    public PointPokeyObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new PointPokeyObjectInstance(ctx.spawn(), "PointPokey");
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly: d1 = 0x23 (half-width), d2 = 0x10 (air), d3 = 0x11 (ground)
        return SolidObjectParams.of(HALF_WIDTH, AIR_HALF_HEIGHT, GROUND_HALF_HEIGHT);
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // ROM ObjD6 (PointPokey) reaches SolidObject_cont via
        // SolidObject_Always_SingleCharacter (s2.asm:59013), bypassing the
        // SolidObject_OnScreenTest render_flags(a0) gate at s2.asm:35330-35336.
        // Off-screen pokeys still resolve solid contact in ROM. Required so
        // enabling CollisionRules.solidObjectOffscreenGate for S2 does not
        // change CNZ pokey solid behaviour off-screen.
        return true;
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }

        lastContactFrame = frameCounter;

        // Only capture if in idle state and player is touching from above
        // The disassembly checks d4 < 0 which indicates player is above the object
        if (playerState == STATE_IDLE && contact.touchTop()) {
            capturePlayer(player);
        }
    }

    /**
     * Captures the player in the cage.
     * Based on loc_2BABE - loc_2BB10 in s2.asm.
     */
    private void capturePlayer(AbstractPlayableSprite player) {
        capturePlayer(player, true, sidekickIndexFor(player));
    }

    private void capturePlayer(AbstractPlayableSprite player, PlayerSolidContactResult solidResult) {
        capturePlayer(player, solidResult != null && solidResult.kind() == ContactKind.TOP, sidekickIndexFor(player));
    }

    private void capturePlayer(AbstractPlayableSprite player, boolean useRideState, int sidekickIndex) {
        // Force rolling state FIRST - this changes player height from 38 to 28.
        // Must be done before setCentreY, otherwise the center calculation uses
        // wrong height and shifts when setRolling changes it.
        capturedPlayerPinballMode = player.getPinballMode();
        player.setPinballMode(true);
        player.setRolling(true);

        capturedPlayerUsesRideState = useRideState;
        capturedSidekickIndex = sidekickIndex;
        if (capturedPlayerUsesRideState) {
            maintainCapturedRideState(player);
        }

        // ROM: move.b #$81,obj_control(a1). This is player obj_control, not
        // global Control_Locked; Obj01_Control still refreshes Ctrl_1_Logical
        // before skipping movement on obj_control bit 0 (s2.asm:36227-36235,
        // 59021).
        // Bit 0 (0x01): suppresses movement/control
        // Bit 7 (0x80): suppresses touch response and full physics
        ObjectControlState.nativeBit7FullControl().applyTo(player);

        // ROM writes only x_pos/y_pos here; x_sub/y_sub survive the capture
        // (s2.asm:58600-58601). Use centre-coordinate APIs because object
        // spawn coordinates map to ROM position fields in this engine.
        player.setCentreXPreserveSubpixel((short) spawn.x());
        player.setCentreYPreserveSubpixel((short) spawn.y());

        // Zero out all velocity
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);

        // Switch to appropriate state based on mode
        mappingFrame = FRAME_ACTIVE;
        animationTimer = 0;
        playerOccupied = true;

        if (isLinkedMode) {
            // Linked mode: try to trigger slot machine
            slotMachineManager = getSlotMachineManager();
            if (slotMachineManager != null && slotMachineManager.isAvailable()) {
                slotMachineManager.activate();
                playerState = STATE_WAITING_SLOT;
            } else {
                // Slot machine busy - use simple countdown instead
                playerState = STATE_OCCUPIED;
                countdown = COUNTDOWN_FRAMES;
            }
        } else {
            // Simple mode: use countdown
            playerState = STATE_OCCUPIED;
            countdown = COUNTDOWN_FRAMES;
        }

        // Play casino bonus sound
        playCasinoBonusSound();
    }

    /**
     * Gets the slot machine manager from the zone feature provider.
     */
    private CNZSlotMachineManager getSlotMachineManager() {
        try {
            ZoneFeatureProvider provider = services().zoneFeatureProvider();
            if (provider instanceof Sonic2ZoneFeatureProvider sonic2Provider) {
                return sonic2Provider.getSlotMachineManager();
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to get slot machine manager: " + e.getMessage());
        }
        return null;
    }

    /**
     * Ejects the player from the cage.
     * Based on loc_2BB12 - loc_2BB3A in s2.asm.
     */
    private void ejectPlayer(AbstractPlayableSprite player) {
        // Apply downward velocity (+0x400, positive Y = down)
        player.setYSpeed((short) EXIT_VELOCITY);

        // ObjD6 release clears on_object and sets in_air before writing
        // y_vel=$400 (s2.asm:58746-58756).
        player.setOnObject(false);
        player.setLatchedSolidObject(0, null);
        player.setAir(true);

        // Release player obj_control; ObjD6 does not touch global Control_Locked
        // (s2.asm:58746-58756).
        ObjectControlState.none().applyTo(player);
        // ObjD6 does not write pinball_mode on release. Restore the ROM byte
        // mirror that was active before the engine-only cage hold set it.
        player.setPinballMode(capturedPlayerPinballMode);

        // Enter the ROM post-release delay. loc_2BE9C clears the cage state
        // and SlotMachineInUse only after this timer expires.
        playerState = STATE_RELEASE_COOLDOWN;
        countdown = 0x1E;
        mappingFrame = FRAME_IDLE;
        animationTimer = 0;
        playerOccupied = false;
        capturedPlayerUsesRideState = false;
        capturedPlayerPinballMode = false;
        capturedSidekickIndex = -1;
        activePrizeCount[0] = 0;
        prizesToSpawn = 0;
        prizeAngle = 0;
    }

    /**
     * Resets cage internal state without modifying player.
     * Used when player enters debug mode or otherwise leaves unexpectedly.
     */
    private void resetCageState() {
        playerState = STATE_IDLE;
        mappingFrame = FRAME_IDLE;
        animationTimer = 0;
        playerOccupied = false;
        capturedPlayerUsesRideState = false;
        capturedPlayerPinballMode = false;

        // Clear the cage ownership latch without stopping the reels. ROM
        // ObjD6 clears SlotMachineInUse here, while LevEvents_CNZ keeps
        // SlotMachine_Routine ticking until the reel sequence finishes.
        if (slotMachineManager != null) {
            slotMachineManager.releaseUse();
        }
        slotMachineManager = null;
        slotReward = 0;
        prizesToSpawn = 0;
        activePrizeCount[0] = 0;
        prizeAngle = 0;
    }

    private void playCasinoBonusSound() {
        try {
            services().playSfx(GameSound.CASINO_BONUS);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }

        // If player entered debug mode while captured, reset cage state
        if (player.isDebugMode() && playerState != STATE_IDLE) {
            resetCageState();
            return;
        }

        if (playerState == STATE_IDLE) {
            resolveIdleCaptureCheckpoint(player);
            return;
        }

        AbstractPlayableSprite activePlayer = capturedPlayerFor(player);
        if (activePlayer.isDebugMode()) {
            resetCageState();
            return;
        }

        switch (playerState) {
            case STATE_OCCUPIED -> updateOccupied(activePlayer, vIntRunCount);
            case STATE_WAITING_SLOT -> updateWaitingSlot(activePlayer, vIntRunCount);
            case STATE_SPAWNING_PRIZES -> updateSpawningPrizes(activePlayer, vIntRunCount);
            case STATE_RELEASE_COOLDOWN -> updateReleaseCooldown();
        }
    }

    /**
     * Update for simple occupied state (subtype 0x00).
     * For this mode, SFX plays when countdown & 0x0F == 0 (s2.asm line 58731-58735).
     */
    private void updateOccupied(AbstractPlayableSprite player, int vIntRunCount) {
        if (releaseIfOccupiedOffScreen(player)) {
            return;
        }

        // Decrement countdown
        countdown--;

        // Keep player locked in position
        keepPlayerLocked(player);

        // Animate cage (Bug fix #2: toggle between frames 0 and 1)
        updateCageAnimation();

        // At 16-frame boundaries: play SFX, award 100 points, spawn floating score sprite
        // (s2.asm lines 58730-58746: loc_2BE5E)
        if ((countdown & 0x0F) == 0) {
            playCasinoBonusSound();

            // Award 100 points (per ROM: d0=10 → AddPoints2 adds d0*10 = 100)
            services().gameState().addScore(100);

            // Spawn floating "100" points sprite at cage position
            spawnFreeChild(() -> new PointsObjectInstance(
                    new ObjectSpawn(spawn.x(), spawn.y(), 0x29, 0, 0, false, 0),
                    services(), 100));
        }

        // ObjD6 decrements the timer, then releases only when the signed
        // result is negative; a zero result still runs the bonus tick
        // (s2.asm:58723-58745).
        if (countdown < 0) {
            ejectPlayer(player);
        }
    }

    /**
     * Update while waiting for slot machine to finish.
     * SFX plays when (Vint_runcount+3) & 0x0F == 0 (s2.asm line 58704-58708).
     */
    private void updateWaitingSlot(AbstractPlayableSprite player, int vIntRunCount) {
        if (releaseIfOccupiedOffScreen(player)) {
            return;
        }

        // Keep player locked
        keepPlayerLocked(player);

        // Animate cage (Bug fix #2: toggle between frames 0 and 1)
        updateCageAnimation();

        // Note: Slot machine is updated once per frame from Sonic2ZoneFeatureProvider.update()
        // (matching the original which calls SlotMachine from LevEvents_CNZ).
        // DO NOT update here - that would cause 2x speed reels!

        // Check if slot machine is done
        if (slotMachineManager != null && slotMachineManager.isComplete()) {
            // ROM ObjD6 branches to prize/release handling as soon as
            // SlotMachine_Routine returns to $18; reward 0 immediately ejects
            // (s2.asm:58727-58731, 58628-58638).
            slotReward = slotMachineManager.getReward();

            if (slotReward == 0) {
                // No reward - just eject
                ejectPlayer(player);
            } else {
                // Start spawning prizes
                // For bombs: always spawn 100 (0x64) per disassembly line 58607
                // For rings: spawn the actual reward amount (e.g., 30 for triple Sonic)
                prizesToSpawn = (slotReward < 0) ? 100 : slotReward;
                activePrizeCount[0] = 0;  // No prizes on screen yet
                prizeAngle = 0;
                playerState = STATE_SPAWNING_PRIZES;
            }
        } else {
            // Play sound at 16-frame intervals using the global counter
            // (s2.asm:59207-59209: move.b (Vint_runcount+3).w,d0 / andi.w #$F,d0 / bne).
            // "+3" there is the address of the longword's low byte, not an addend --
            // the gate is the raw counter masked, with no offset.
            if ((vIntRunCount & 0x0F) == 0) {
                playCasinoBonusSound();
            }
        }
    }

    /**
     * Update while spawning prizes.
     * NOTE: The ROM does NOT play any SFX during prize spawning (loc_2BC86).
     * Sound only plays during slot machine waiting or simple countdown mode.
     *
     * Per disassembly:
     * - Max 16 prizes can be on screen at once (objoff_2C limit at line 58657/58613)
     * - Continue spawning until prizesToSpawn reaches 0
     * - Eject when all prizes spawned AND all collected/expired (activePrizeCount == 0)
     */
    private void updateSpawningPrizes(AbstractPlayableSprite player, int vIntRunCount) {
        try {
            // Keep player locked
            keepPlayerLocked(player);

            // Animate cage (Bug fix #2: toggle between frames 0 and 1)
            updateCageAnimation();

            // NO SFX during prize spawning - ROM only plays sound during waiting/countdown

            // loc_2BD4E (rings, s2.asm:59156-59193) and its identical twin at
            // loc_2BC86 (bombs, s2.asm:59110-59148) gate on the *global* clock:
            // "btst #0,(Level_frame_counter+1).w / beq.w return_2BDF6". A prize is
            // therefore only ever created on an odd Level_frame_counter, and that
            // same branch returns before the "tst.w objoff_2C(a0) / beq loc_2BE2E"
            // release check, so while the reward is outstanding the release is only
            // polled on odd frames too. Once the reward is exhausted the "beq.w +"
            // at the head of loc_2BD4E skips the spawn block entirely and the
            // release check runs every frame.
            if (prizesToSpawn > 0) {
                if ((levelFrameCounter(vIntRunCount) & 1) == 0) {
                    return; // beq.w return_2BDF6 - no spawn, and no release poll
                }
                // cmpi.w #$10,objoff_2C(a0) / bhs.w return_2BDF6
                if (activePrizeCount[0] >= MAX_PRIZES) {
                    return;
                }
                spawnPrize(player, vIntRunCount);
                prizesToSpawn--;
            }

            releaseIfAllPrizesSettled(player);
        } finally {
            // ObjD6's bomb branch reaches loc_2BD48 even on even frames,
            // full child slots, and while waiting for the final impacts.
            if (slotReward < 0 && playerState == STATE_SPAWNING_PRIZES) {
                var state = services().gameModule().getGameService(
                        CNZPrizeSoundState.class);
                if (state != null) {
                    state.advanceBombPayout();
                }
            }
        }
    }

    /**
     * ROM-visible Level_frame_counter. The object update argument is
     * V_int_run_count, which de-phases from Level_frame_counter across lag
     * frames; loc_2BD4E reads Level_frame_counter.
     */
    private int levelFrameCounter(int vIntRunCount) {
        return services().levelManager() != null
                ? services().levelManager().getFrameCounter()
                : vIntRunCount;
    }

    private void releaseIfAllPrizesSettled(AbstractPlayableSprite contextPlayer) {
        // ObjDC/ObjD3 decrement ObjD6's objoff_2C through a shared pointer
        // (s2.asm:25490-25492). The cage only ever reads that counter at its own
        // point in the object update order ("tst.w objoff_2C(a0)" in loc_2BD4E),
        // so a child that runs after the cage cannot release the player in the
        // same frame - loc_2BE2E is reached on the cage's next update.
        if (playerState != STATE_SPAWNING_PRIZES
                || prizesToSpawn > 0
                || activePrizeCount[0] > 0
                || !playerOccupied) {
            return;
        }
        AbstractPlayableSprite activePlayer = capturedPlayerFor(contextPlayer);
        if (activePlayer != null) {
            ejectPlayer(activePlayer);
        }
    }

    private boolean releaseIfOccupiedOffScreen(AbstractPlayableSprite player) {
        // ROM ObjD6 occupied routine tests render_flags.on_screen before the
        // simple countdown or linked-slot handling. If bit 7 is clear, it jumps
        // directly to loc_2BE2E and releases the player (s2.asm:59152-59156).
        if (isWithinSolidContactBounds()) {
            return false;
        }
        ejectPlayer(player);
        return true;
    }

    private AbstractPlayableSprite capturedPlayerFor(AbstractPlayableSprite mainPlayer) {
        if (capturedSidekickIndex < 0) {
            return mainPlayer;
        }
        try {
            List<PlayableEntity> sidekicks = services().playerQuery().sidekicks();
            if (capturedSidekickIndex < sidekicks.size()
                    && sidekicks.get(capturedSidekickIndex) instanceof AbstractPlayableSprite sidekick) {
                return sidekick;
            }
        } catch (Exception ignored) {
            // Fall back to main if a minimal test service does not expose sidekicks.
        }
        return mainPlayer;
    }

    private int sidekickIndexFor(AbstractPlayableSprite player) {
        try {
            List<PlayableEntity> sidekicks = services().playerQuery().sidekicks();
            for (int i = 0; i < sidekicks.size(); i++) {
                if (sidekicks.get(i) == player) {
                    return i;
                }
            }
        } catch (Exception ignored) {
            // Main-player captures and reflection-based unit tests use -1.
        }
        return -1;
    }

    private void updateReleaseCooldown() {
        // loc_2BE9C decrements the release timer and only clears the per-player
        // state after the signed result goes negative. Linked cages clear
        // SlotMachineInUse at the same point, not on the release frame.
        countdown--;
        if (countdown >= 0) {
            return;
        }
        resetCageState();
    }

    /**
     * Update cage animation (Bug fix #2).
     * Toggles between FRAME_IDLE and FRAME_ACTIVE every ACTIVE_ANIM_SPEED frames.
     * This creates the two-color flashing effect from Ani_objD6 animation 1.
     */
    private void updateCageAnimation() {
        animationTimer++;
        if (animationTimer >= ACTIVE_ANIM_SPEED) {
            animationTimer = 0;
            // Toggle between frames 0 and 1
            mappingFrame = (mappingFrame == FRAME_IDLE) ? FRAME_ACTIVE : FRAME_IDLE;
        }
    }

    /**
     * Keep player locked in cage position.
     */
    private void keepPlayerLocked(AbstractPlayableSprite player) {
        if (capturedPlayerUsesRideState) {
            maintainCapturedRideState(player);
        }
        // Occupied routines keep the high-word position locked without
        // touching the subpixel words, matching 68000 word stores.
        player.setCentreXPreserveSubpixel((short) spawn.x());
        player.setCentreYPreserveSubpixel((short) spawn.y());
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
    }

    private void resolveIdleCaptureCheckpoint(AbstractPlayableSprite player) {
        // ObjD6 calls SolidObject_Always_SingleCharacter only from its idle
        // capture routine. Once objoff_30/34 moves to occupied state, the ROM
        // dispatches to cage-owned routines instead of re-running SolidObject
        // (s2.asm:58554-58566, 58694-58756).
        SolidCheckpointBatch batch = services().solidExecution().resolveSolidNowAll();
        PlayerSolidContactResult result = batch.perPlayer().get(player);
        if (capturesFromSolidReturn(result)) {
            capturePlayer(player, result);
            return;
        }
        // ROM ObjD6 processes Sidekick immediately after MainCharacter with
        // its own objoff_34 state bytes (s2.asm:59040-59044). Manual solid
        // checkpoints do not fire compatibility callbacks, so consume the
        // native-P2 checkpoint result directly instead of relying on
        // onSolidContact().
        for (var entry : batch.perPlayer().entrySet()) {
            if (entry.getKey() == player || !(entry.getKey() instanceof AbstractPlayableSprite sidekick)) {
                continue;
            }
            PlayerSolidContactResult sidekickResult = entry.getValue();
            if (capturesFromSolidReturn(sidekickResult)) {
                capturePlayer(sidekick, sidekickResult);
                return;
            }
        }
    }

    private static boolean capturesFromSolidReturn(PlayerSolidContactResult result) {
        // ROM ObjD6 captures when SolidObject_Always_SingleCharacter returns a
        // negative d4: -1 top or -2 bottom (s2.asm:59045-59046). Side contact
        // returns +1 and must not enter the cage.
        return result != null && (result.kind() == ContactKind.TOP || result.kind() == ContactKind.BOTTOM);
    }

    private void maintainCapturedRideState(AbstractPlayableSprite player) {
        // SolidObject_Always_SingleCharacter has already set the standing bit
        // and cleared in_air when ObjD6 captures the player, and the occupied
        // routines leave that state intact until loc_2BE2E releases the cage
        // (s2.asm:58554-58566, 58746-58756).
        player.setOnObject(true);
        player.setAir(false);
        player.setLatchedSolidObject(spawn.objectId(), this);
    }

    @Override
    public String traceDebugDetails() {
        CNZSlotMachineManager debugManager = slotMachineManager;
        if (debugManager == null && isLinkedMode) {
            debugManager = getSlotMachineManager();
        }
        boolean slotComplete = debugManager != null && debugManager.isComplete();
        int managerReward = debugManager != null ? debugManager.getReward() : 0;
        String managerState = debugManager != null ? debugManager.traceDebugState() : "none";
        return String.format(
                "state=%d linked=%d countdown=%d slotComplete=%d slotReward=%d mgrReward=%d prizes=%d active=%d occupied=%d frame=%d slot={%s}",
                playerState,
                isLinkedMode ? 1 : 0,
                countdown,
                slotComplete ? 1 : 0,
                slotReward,
                managerReward,
                prizesToSpawn,
                activePrizeCount[0],
                playerOccupied ? 1 : 0,
                mappingFrame,
                managerState);
    }

    /**
     * Spawn a prize (ring or bomb) at an angle from the cage.
     * Per disassembly, prizes spiral inward from a starting radius of 128 pixels.
     */
    private void spawnPrize(AbstractPlayableSprite player, int vIntRunCount) {
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        // Calculate spawn position using sin/cos
        // ROM uses angle 0-255 format, we convert to radians
        double radians = (prizeAngle / 256.0) * 2.0 * Math.PI;
        int offsetX = (int) (Math.sin(radians) * PRIZE_RADIUS);
        int offsetY = (int) (Math.cos(radians) * PRIZE_RADIUS);

        int startX = spawn.x() + offsetX;
        int startY = spawn.y() + offsetY;

        // Display delay - fixed constants from disassembly (s2.asm lines 58624, 58668)
        // Bombs: 0x1E = 30 frames, Rings: 0x1A = 26 frames
        int displayDelay = (slotReward < 0) ? 0x1E : 0x1A;

        // Increment active prize count (decremented by prize when it finishes)
        activePrizeCount[0]++;

        if (slotReward < 0) {
            // Bombs
            spawnFreeChild(() -> new BombPrizeObjectInstance(
                    startX, startY, spawn.x(), spawn.y(),
                    displayDelay, activePrizeCount, this));
            prizeAngle += BOMB_ANGLE_INCREMENT;
        } else {
            // Rings
            spawnFreeChild(() -> new RingPrizeObjectInstance(
                    startX, startY, spawn.x(), spawn.y(),
                    displayDelay, activePrizeCount, this));
            prizeAngle += RING_ANGLE_INCREMENT;
        }

        // Wrap angle
        prizeAngle &= 0xFF;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.CNZ_CAGE);
        if (renderer == null) {
            super.appendRenderCommands(commands);
            return;
        }
        // No flipping for this object
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);

        // Request slot machine display render for linked cages (actual render deferred to after tilemap)
        if (isLinkedMode) {
            // Calculate slot display offset on first render (varies between CNZ1 and CNZ2)
            if (!slotDisplayOffsetCalculated) {
                calculateSlotDisplayOffset();
            }

            ZoneFeatureProvider provider = services().zoneFeatureProvider();
            if (provider instanceof Sonic2ZoneFeatureProvider sonic2Provider) {
                sonic2Provider.requestSlotRender(spawn.x(), spawn.y(), slotDisplayOffsetX, slotDisplayOffsetY);
            }
        }
    }

    /**
     * Calculates the offset from cage center to slot display by scanning nearby chunks
     * for patterns using the slot machine VRAM tile indices.
     * <p>
     * This is necessary because the slot display position varies between CNZ1 (below cage)
     * and CNZ2 (above cage), and is determined by the level's chunk layout.
     */
    private void calculateSlotDisplayOffset() {
        slotDisplayOffsetCalculated = true;
        if (services().currentLevel() == null) {
            return;
        }

        // Search for slot display patterns (tiles $0550-$057F) within 128 pixels of cage
        int[] offset = services().findPatternOffset(
                spawn.x(),
                spawn.y(),
                CNZSlotMachineRenderer.SLOT_TILE_MIN,
                CNZSlotMachineRenderer.SLOT_TILE_MAX,
                128
        );

        if (offset != null) {
            // Found slot display tiles - the offset points to the first pattern's center
            // The findPatternOffset returns offset from ref to pattern center (pattern top-left + 4)
            // We need the offset to the pattern's top-left corner for rendering
            // Subtract 4 for both X and Y to convert from pattern center to pattern top-left
            slotDisplayOffsetX = offset[0] - 4;  // Pattern center to pattern left edge
            slotDisplayOffsetY = offset[1] - 4;  // Pattern center to pattern top edge

            LOGGER.fine("Slot display offset calculated: (" + slotDisplayOffsetX + ", " + slotDisplayOffsetY +
                    ") for cage at (" + spawn.x() + ", " + spawn.y() + ")");
        } else {
            LOGGER.fine("Slot display patterns not found near cage at (" + spawn.x() + ", " + spawn.y() +
                    "), using defaults");
        }
    }

    @Override
    public int getPriorityBucket() {
        // Use bucket 1 to render AFTER player (bucket 2) so cage appears in front
        // (higher buckets render first/behind, lower buckets render later/in front)
        return RenderPriority.clamp(1);
    }

    /**
     * Make cage high-priority.
     * This ensures the cage renders in front of Sonic.
     */
    @Override
    public boolean isHighPriority() {
        return true;
    }
}
