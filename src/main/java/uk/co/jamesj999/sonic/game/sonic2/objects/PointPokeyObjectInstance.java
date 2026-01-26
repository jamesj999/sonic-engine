package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.game.GameModuleRegistry;
import uk.co.jamesj999.sonic.game.ZoneFeatureProvider;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ZoneFeatureProvider;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.slotmachine.CNZSlotMachineManager;
import uk.co.jamesj999.sonic.level.slotmachine.CNZSlotMachineRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

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
 *   <li>Exit: player ejected with upward velocity (-0x400)</li>
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
        implements SolidObjectProvider, SolidObjectListener {

    private static final Logger LOGGER = Logger.getLogger(PointPokeyObjectInstance.class.getName());

    // Collision dimensions from disassembly (loc_2BBA6)
    private static final int HALF_WIDTH = 0x23;         // 35
    private static final int AIR_HALF_HEIGHT = 0x10;    // 16
    private static final int GROUND_HALF_HEIGHT = 0x11; // 17

    // Timing constants
    private static final int COUNTDOWN_FRAMES = 0x78;   // 120 frames (~2 seconds)
    private static final int PRIZE_SPAWN_INTERVAL = 2;  // Spawn every other frame
    private static final int SFX_FRAME_OFFSET = 3;      // Offset for SFX timing (s2.asm: Vint_runcount+3)
    private static final int ACTIVE_ANIM_SPEED = 2;     // Toggle animation frame every 2 frames (from Ani_objD6)

    // Exit velocity
    private static final int EXIT_VELOCITY = 0x400;     // Downward exit speed (positive Y is down)

    // Player states
    private static final int STATE_IDLE = 0;
    private static final int STATE_OCCUPIED = 1;
    private static final int STATE_WAITING_SLOT = 2;
    private static final int STATE_SPAWNING_PRIZES = 3;

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
    private int prizeSpawnTimer = 0;
    private final int[] activePrizeCount = new int[1];  // Active prizes on screen (objoff_2C equivalent)

    // Contact tracking
    private int lastContactFrame = -2;

    // Animation timing (Bug fix #2)
    private int animationTimer = 0;

    // Track when player is occupied for priority control (Bug fix #4)
    private boolean playerOccupied = false;

    // Reference to level manager (for spawning prizes)
    private LevelManager levelManager;

    public PointPokeyObjectInstance(ObjectSpawn spawn, String name) {
        // Use cyan color for debug box
        super(spawn, name, HALF_WIDTH, GROUND_HALF_HEIGHT, 0.2f, 0.8f, 0.8f, false);
        this.isLinkedMode = (spawn.subtype() & 0xFF) == 0x01;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly: d1 = 0x23 (half-width), d2 = 0x10 (air), d3 = 0x11 (ground)
        return new SolidObjectParams(HALF_WIDTH, AIR_HALF_HEIGHT, GROUND_HALF_HEIGHT);
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
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
        // Get level manager for later use
        levelManager = LevelManager.getInstance();

        // Lock player to cage center (use center coordinates - spawn.x/y are center coords)
        player.setCentreX((short) spawn.x());
        player.setCentreY((short) spawn.y());

        // Zero out all velocity
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);

        // ROM: move.b #$81,obj_control(a1) - locks player control
        player.setControlLocked(true);

        // Force rolling state and pinball mode
        player.setPinballMode(true);
        player.setRolling(true);
        player.setAir(false);

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
            ZoneFeatureProvider provider = GameModuleRegistry.getCurrent().getZoneFeatureProvider();
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
        // Apply upward velocity
        player.setYSpeed((short) EXIT_VELOCITY);

        // Set player airborne
        player.setAir(true);

        // Release player control
        player.setControlLocked(false);
        player.setPinballMode(false);

        // Reset cage internal state
        resetCageState();
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

        // Deactivate slot machine state (preserves visual reel positions)
        if (slotMachineManager != null) {
            slotMachineManager.deactivate();
        }
        slotMachineManager = null;
        slotReward = 0;
        prizesToSpawn = 0;
        activePrizeCount[0] = 0;
        prizeAngle = 0;
        prizeSpawnTimer = 0;
    }

    private void playCasinoBonusSound() {
        try {
            AudioManager audioManager = AudioManager.getInstance();
            if (audioManager != null) {
                audioManager.playSfx(GameSound.CASINO_BONUS);
            }
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // If player entered debug mode while captured, reset cage state
        if (player.isDebugMode() && playerState != STATE_IDLE) {
            resetCageState();
            return;
        }

        switch (playerState) {
            case STATE_OCCUPIED -> updateOccupied(player, frameCounter);
            case STATE_WAITING_SLOT -> updateWaitingSlot(player, frameCounter);
            case STATE_SPAWNING_PRIZES -> updateSpawningPrizes(player, frameCounter);
        }
    }

    /**
     * Update for simple occupied state (subtype 0x00).
     * For this mode, SFX plays when countdown & 0x0F == 0 (s2.asm line 58731-58735).
     */
    private void updateOccupied(AbstractPlayableSprite player, int frameCounter) {
        // Decrement countdown
        countdown--;

        // Keep player locked in position
        keepPlayerLocked(player);

        // Animate cage (Bug fix #2: toggle between frames 0 and 1)
        updateCageAnimation();

        // Play SFX when countdown is at 16-frame boundary (s2.asm: countdown & 0x0F == 0)
        if ((countdown & 0x0F) == 0) {
            playCasinoBonusSound();
        }

        // Check if countdown expired
        if (countdown <= 0) {
            ejectPlayer(player);
        }
    }

    /**
     * Update while waiting for slot machine to finish.
     * SFX plays when (Vint_runcount+3) & 0x0F == 0 (s2.asm line 58704-58708).
     */
    private void updateWaitingSlot(AbstractPlayableSprite player, int frameCounter) {
        // Keep player locked
        keepPlayerLocked(player);

        // Animate cage (Bug fix #2: toggle between frames 0 and 1)
        updateCageAnimation();

        // Note: Slot machine is updated once per frame from Sonic2ZoneFeatureProvider.update()
        // (matching the original which calls SlotMachine from LevEvents_CNZ).
        // DO NOT update here - that would cause 2x speed reels!

        // Check if slot machine is done
        if (slotMachineManager != null && slotMachineManager.isComplete()) {
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
                prizeSpawnTimer = 0;
                playerState = STATE_SPAWNING_PRIZES;
            }
        } else {
            // Play sound at 16-frame intervals using global counter (s2.asm: (Vint_runcount+3) & 0x0F == 0)
            if (((frameCounter + SFX_FRAME_OFFSET) & 0x0F) == 0) {
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
    private void updateSpawningPrizes(AbstractPlayableSprite player, int frameCounter) {
        // Keep player locked
        keepPlayerLocked(player);

        // Animate cage (Bug fix #2: toggle between frames 0 and 1)
        updateCageAnimation();

        // NO SFX during prize spawning - ROM only plays sound during waiting/countdown

        // Spawn prizes every other frame, but only if < 16 on screen
        prizeSpawnTimer++;
        if (prizeSpawnTimer >= PRIZE_SPAWN_INTERVAL) {
            prizeSpawnTimer = 0;
            // Only spawn if more prizes to spawn AND less than 16 currently active
            if (prizesToSpawn > 0 && activePrizeCount[0] < MAX_PRIZES) {
                spawnPrize(player, frameCounter);
                prizesToSpawn--;
            }
        }

        // Check if all prizes spawned AND all collected/expired
        // (eject when prizesToSpawn == 0 AND activePrizeCount == 0)
        if (prizesToSpawn <= 0 && activePrizeCount[0] <= 0) {
            ejectPlayer(player);
        }
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
        // Use center coordinates - spawn.x/y are center coords
        player.setCentreX((short) spawn.x());
        player.setCentreY((short) spawn.y());
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
    }

    /**
     * Spawn a prize (ring or bomb) at an angle from the cage.
     * Per disassembly, prizes spiral inward from a starting radius of 128 pixels.
     */
    private void spawnPrize(AbstractPlayableSprite player, int frameCounter) {
        if (levelManager == null) {
            return;
        }

        ObjectManager objectManager = levelManager.getObjectManager();
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
            BombPrizeObjectInstance bomb = new BombPrizeObjectInstance(
                    startX, startY, spawn.x(), spawn.y(),
                    displayDelay, activePrizeCount, levelManager);
            objectManager.addDynamicObject(bomb);
            prizeAngle += BOMB_ANGLE_INCREMENT;
        } else {
            // Rings
            RingPrizeObjectInstance ring = new RingPrizeObjectInstance(
                    startX, startY, spawn.x(), spawn.y(),
                    displayDelay, activePrizeCount, levelManager);
            objectManager.addDynamicObject(ring);
            prizeAngle += RING_ANGLE_INCREMENT;
        }

        // Wrap angle
        prizeAngle &= 0xFF;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CNZ_CAGE);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }
        // No flipping for this object
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);

        // Request slot machine display render for linked cages (actual render deferred to after tilemap)
        if (isLinkedMode) {
            ZoneFeatureProvider provider = GameModuleRegistry.getCurrent().getZoneFeatureProvider();
            if (provider instanceof Sonic2ZoneFeatureProvider sonic2Provider) {
                sonic2Provider.requestSlotRender(spawn.x(), spawn.y());
            }
        }
    }

    @Override
    public int getPriorityBucket() {
        // Use bucket 3 to render after player (bucket 2) in same pass when occupied
        return playerOccupied ? RenderPriority.clamp(3) : RenderPriority.clamp(4);
    }

    /**
     * Make cage high-priority when player is inside (Bug fix #4).
     * This ensures the cage renders in front of Sonic when he's captured.
     */
    @Override
    public boolean isHighPriority() {
        return playerOccupied;
    }
}
