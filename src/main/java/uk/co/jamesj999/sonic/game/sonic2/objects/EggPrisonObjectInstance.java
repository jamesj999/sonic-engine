package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.AnimalObjectInstance;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.SolidObjectParams;
import uk.co.jamesj999.sonic.level.objects.SolidObjectProvider;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Egg Prison / Capsule (Object 0x3E).
 * <p>
 * ROM-accurate implementation based on s2.asm loc_3F1E4 - loc_3F436.
 * <p>
 * Structure from Obj3E_ObjLoadData (4 sub-objects):
 * <ul>
 *   <li>Main body: routine=2, y_offset=0, width=$20, priority=4, frame=0</li>
 *   <li>Button: routine=4, y_offset=$28 (40) up, width=$10, priority=5, frame=4</li>
 *   <li>Lock: routine=6, y_offset=$18 (24) up, width=8, priority=3, frame=5</li>
 *   <li>Broken piece: routine=8, y_offset=0, width=$20, priority=4, frame=0</li>
 * </ul>
 * <p>
 * Button collision (loc_3F354):
 * - SolidObject with d1=$1B (27), d2=8, d3=8
 * - When standing_mask is set, button depresses 8 pixels and sets trigger flag
 * <p>
 * Breaking sequence (loc_3F2B4):
 * - Spawns explosion at lock position
 * - Lock flies with y_vel=-$400, x_vel=$800
 * - After $1D (29) frame delay, spawns 8 initial animals
 * <p>
 * Animal spawning (loc_3F3A8):
 * - Every 8 frames (Vint_runcount & 7 == 0), spawn one animal
 * - Random x offset: (random & $1F) - 6, optionally negated
 * - Animal delay: $C (12) frames
 * - After $B4 (180) frames, advance to final state
 * <p>
 * End state (loc_3F406):
 * - Loops through object RAM looking for animals
 * - When none remain, calls Load_EndOfAct
 */
public class EggPrisonObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider {
    private static final Logger LOGGER = Logger.getLogger(EggPrisonObjectInstance.class.getName());

    // === ROM Constants ===

    // Sub-object Y offsets from Obj3E_ObjLoadData (subtracted from main Y)
    private static final int BUTTON_Y_OFFSET = 0x28;  // 40 pixels above main
    private static final int LOCK_Y_OFFSET = 0x18;    // 24 pixels above main

    // SolidObject parameters for body (loc_3F278): d1=$2B, d2=$18, d3=$18
    private static final int BODY_HALF_WIDTH = 0x2B;  // 43 pixels
    private static final int BODY_HALF_HEIGHT = 0x18; // 24 pixels

    // SolidObject parameters for button (loc_3F354): d1=$1B, d2=8, d3=8
    private static final int BUTTON_HALF_WIDTH = 0x1B;  // 27 pixels
    private static final int BUTTON_HALF_HEIGHT = 8;

    // Button depression amount when stood on
    private static final int BUTTON_DEPRESS = 8;

    // Lock physics when flying off (loc_3F2B4)
    private static final int LOCK_X_VEL = 0x800;  // Fixed-point velocity
    private static final int LOCK_Y_VEL = -0x400;
    private static final int GRAVITY = 0x38;      // From ObjectMoveAndFall

    // Timing constants
    private static final int BREAK_DELAY = 0x1D;        // 29 frames before spawning initial animals
    private static final int INITIAL_ANIMAL_COUNT = 8;
    private static final int INITIAL_ANIMAL_DELAY_BASE = 0x9A;  // 154 frames for first animal
    private static final int INITIAL_ANIMAL_DELAY_STEP = 8;
    private static final int INITIAL_ANIMAL_X_OFFSET_START = -0x1C;  // -28
    private static final int INITIAL_ANIMAL_X_OFFSET_STEP = 7;
    private static final int SPAWN_ANIMAL_DELAY = 0xC;  // 12 frames delay for random animals
    private static final int SPAWN_PHASE_DURATION = 0xB4;  // 180 frames of random spawning
    private static final int FINAL_WAIT_DURATION = 0xB4;   // 180 frames wait before checking animals

    // Animation frames (from obj3E.asm mappings)
    private static final int FRAME_BODY_CLOSED = 0;
    private static final int FRAME_BODY_OPEN_1 = 1;
    private static final int FRAME_BODY_OPEN_2 = 2;
    private static final int FRAME_BODY_OPEN_3 = 3;
    private static final int FRAME_BUTTON = 4;
    private static final int FRAME_LOCK = 5;

    // Animation script from Ani_obj3E
    // Script 0: $F, 0, $FF (hold frame 0 forever)
    // Script 1: 3, 0, 1, 2, 3, $FE, 1 (3 frame delay, frames 0-1-2-3, loop from frame 1)
    private static final int ANIM_FRAME_DELAY = 3;

    // === State Machine ===

    // Body routine_secondary states (mirroring ROM off_3F2AE)
    private static final int BODY_STATE_IDLE = 0;            // loc_3F2B4 - waiting for button
    private static final int BODY_STATE_BREAK_DELAY = 2;     // loc_3F2FC - waiting to spawn animals
    private static final int BODY_STATE_DONE = 4;            // return_3F352 - done

    // Broken piece routine_secondary states (mirroring ROM loc_3F3A8)
    private static final int BROKEN_STATE_WAITING = 0;       // Before button pressed
    private static final int BROKEN_STATE_SPAWNING = 2;      // Spawning random animals
    private static final int BROKEN_STATE_FINAL = 4;         // Waiting for animals to leave

    // === Instance State ===

    // Body state
    private int bodyRoutineSecondary = BODY_STATE_IDLE;
    private int bodyAnim = 0;
    private int bodyAnimFrame = FRAME_BODY_CLOSED;
    private int bodyAnimTimer = 0;
    private int breakDelayTimer = 0;

    // Button state (now managed by separate button object)
    private EggPrisonButtonObjectInstance buttonObject;
    private boolean buttonTriggered = false;  // objoff_32

    // Lock state
    private int lockX;
    private int lockY;
    private int lockXVel = 0;          // Fixed-point (8.8)
    private int lockYVel = 0;          // Fixed-point (8.8)
    private int lockXSub = 0;          // Sub-pixel accumulator
    private int lockYSub = 0;
    private int lockRoutineSecondary = 0;
    private boolean lockVisible = true;

    // Broken piece state (spawns random animals)
    private int brokenRoutineSecondary = BROKEN_STATE_WAITING;
    private int brokenAnimDuration = 0;

    // Player reference for results screen
    private AbstractPlayableSprite lastPlayer;
    private boolean resultsTriggered = false;

    // Frame counter for spawn timing
    private int globalFrameCounter = 0;

    public EggPrisonObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Initialize lock position (24 pixels above main body)
        this.lockX = spawn.x();
        this.lockY = spawn.y() - LOCK_Y_OFFSET;

        // Spawn button as a separate object with full solid collision
        // This matches the ROM structure where button is a child object with routine 4
        spawnButtonObject();
    }

    /**
     * Spawns the button as a separate object with full solid collision.
     * ROM: Button is child object 2 with routine 4 (loc_3F354).
     */
    private void spawnButtonObject() {
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager == null) {
            return;
        }

        buttonObject = new EggPrisonButtonObjectInstance(spawn, this);
        objectManager.addDynamicObject(buttonObject);
    }

    /**
     * Called by button when player lands on it.
     * Public callback method for EggPrisonButtonObjectInstance.
     */
    public void onButtonTriggered() {
        if (!buttonTriggered) {
            buttonTriggered = true;

            // Pause level timer (ROM: clr.b Update_HUD_timer)
            var levelGamestate = LevelManager.getInstance().getLevelGamestate();
            if (levelGamestate != null) {
                levelGamestate.pauseTimer();
            }
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        this.lastPlayer = player;
        this.globalFrameCounter = frameCounter;

        // Update each sub-object according to its routine
        updateBody(player);
        updateLock();
        updateBrokenPiece(frameCounter);
    }

    /**
     * Body routine (loc_3F278).
     * Checks if button was triggered and handles breaking sequence.
     */
    private void updateBody(AbstractPlayableSprite player) {
        switch (bodyRoutineSecondary) {
            case BODY_STATE_IDLE -> updateBodyIdle();
            case BODY_STATE_BREAK_DELAY -> updateBodyBreakDelay();
            case BODY_STATE_DONE -> { /* Nothing */ }
        }

        // Update body animation
        updateBodyAnimation();
    }

    /**
     * loc_3F2B4 - Waiting for button to be triggered.
     */
    private void updateBodyIdle() {
        // Check if button was triggered (objoff_32 set by button routine)
        if (!buttonTriggered) {
            return;
        }

        // Mark as remembered so capsule never respawns (ROM: RememberState="true")
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager != null) {
            objectManager.markRemembered(spawn);
        }

        // Spawn explosion at lock position
        spawnExplosion(lockX, lockY);

        // Play explosion sound
        try {
            AudioManager.getInstance().playSfx(Sonic2Constants.SndID_Explosion);
        } catch (Exception e) {
            LOGGER.warning("Failed to play explosion sound: " + e.getMessage());
        }

        // Lock starts flying off
        lockXVel = LOCK_X_VEL;
        lockYVel = LOCK_Y_VEL;
        lockRoutineSecondary = 2;

        // Set break delay timer
        breakDelayTimer = BREAK_DELAY;

        // Advance body state
        bodyRoutineSecondary = BODY_STATE_BREAK_DELAY;

        LOGGER.fine("Egg Prison triggered at X=" + spawn.x());
    }

    /**
     * loc_3F2FC - Wait for delay, then spawn initial animals.
     */
    private void updateBodyBreakDelay() {
        breakDelayTimer--;
        if (breakDelayTimer > 0) {
            return;
        }

        // Switch to opening animation (anim=1)
        bodyAnim = 1;
        bodyAnimFrame = FRAME_BODY_CLOSED;
        bodyAnimTimer = ANIM_FRAME_DELAY;

        // Spawn 8 initial animals with staggered delays
        spawnInitialAnimals();

        // Tell broken piece to start spawning random animals after $B4 frames
        brokenAnimDuration = SPAWN_PHASE_DURATION;
        brokenRoutineSecondary = BROKEN_STATE_SPAWNING;

        // Body is done
        bodyRoutineSecondary = BODY_STATE_DONE;
    }

    /**
     * Updates body animation (Ani_obj3E).
     * Script 0: Hold frame 0 forever
     * Script 1: Frames 0,1,2,3 with 3 frame delay, loop from frame 1
     */
    private void updateBodyAnimation() {
        if (bodyAnim == 0) {
            bodyAnimFrame = FRAME_BODY_CLOSED;
            return;
        }

        // Anim 1: Opening animation
        bodyAnimTimer--;
        if (bodyAnimTimer <= 0) {
            bodyAnimTimer = ANIM_FRAME_DELAY;
            bodyAnimFrame++;

            // Clamp to max frame and loop behavior ($FE, 1 = loop from frame 1)
            if (bodyAnimFrame > FRAME_BODY_OPEN_3) {
                bodyAnimFrame = FRAME_BODY_OPEN_1;
            }
        }
    }

    /**
     * Lock routine (loc_3F38E).
     * When triggered, flies off using ObjectMoveAndFall physics.
     */
    private void updateLock() {
        if (lockRoutineSecondary == 0) {
            // Not triggered yet
            return;
        }

        // Apply ObjectMoveAndFall physics
        lockYVel += GRAVITY;
        lockXSub += lockXVel;
        lockYSub += lockYVel;

        // Convert from fixed-point to pixels
        lockX += (lockXSub >> 8);
        lockY += (lockYSub >> 8);
        lockXSub &= 0xFF;
        lockYSub &= 0xFF;

        // Check if lock is off screen
        Camera camera = Camera.getInstance();
        if (camera != null) {
            int screenRight = camera.getX() + 320 + 64;
            int screenBottom = camera.getY() + 224 + 64;
            if (lockX > screenRight || lockY > screenBottom) {
                lockVisible = false;
            }
        }
    }

    /**
     * Broken piece routine (loc_3F3A8).
     * Spawns random animals at regular intervals.
     */
    private void updateBrokenPiece(int frameCounter) {
        if (brokenRoutineSecondary == BROKEN_STATE_WAITING) {
            return;
        }

        if (brokenRoutineSecondary == BROKEN_STATE_SPAWNING) {
            // ROM: andi.b #7,d0 / bne.s skip_spawn
            // Spawn one animal every 8 frames
            if ((frameCounter & 7) == 0) {
                spawnRandomAnimal();
            }

            // Count down duration
            brokenAnimDuration--;
            if (brokenAnimDuration <= 0) {
                // Advance to final state, wait $B4 frames
                brokenRoutineSecondary = BROKEN_STATE_FINAL;
                brokenAnimDuration = FINAL_WAIT_DURATION;
            }
            return;
        }

        if (brokenRoutineSecondary == BROKEN_STATE_FINAL) {
            // loc_3F406: Wait 180 frames, then check every frame
            if (brokenAnimDuration > 0) {
                brokenAnimDuration--;
                return;  // Still waiting
            }

            // After wait, check if any animals remain
            if (!areAnimalsPresent()) {
                triggerEndOfAct();
            }
            // If animals still present, keep checking every frame (don't reset timer)
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: SolidObject(d1=$2B, d2=$18, d3=$18) at loc_3F278
        // Body is always solid (width=43px, height=24px)
        return new SolidObjectParams(
            BODY_HALF_WIDTH,    // 0x2B = 43 pixels
            BODY_HALF_HEIGHT,   // 0x18 = 24 pixels (air)
            BODY_HALF_HEIGHT    // 0x18 = 24 pixels (ground)
        );
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        // Always solid, even after breaking
        return true;
    }

    @Override
    public boolean shouldStayActiveWhenRemembered() {
        // Capsule needs to stay active to complete its animation sequence
        // (lock flying off, capsule opening, animals spawning, results screen)
        // It will self-destruct when results screen is triggered
        return true;
    }

    /**
     * Spawns 8 initial animals with staggered delay timers.
     * ROM: loc_3F2FC loop with d6=7, d5=$9A, d4=-$1C
     */
    private void spawnInitialAnimals() {
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager == null) {
            return;
        }

        int baseX = spawn.x();
        int baseY = spawn.y();
        int xOffset = INITIAL_ANIMAL_X_OFFSET_START;
        int delay = INITIAL_ANIMAL_DELAY_BASE;

        for (int i = 0; i < INITIAL_ANIMAL_COUNT; i++) {
            ObjectSpawn animalSpawn = new ObjectSpawn(
                    baseX + xOffset, baseY,
                    0x28, 0, 0, false, 0
            );
            EggPrisonAnimalInstance animal = new EggPrisonAnimalInstance(animalSpawn, delay);
            objectManager.addDynamicObject(animal);

            xOffset += INITIAL_ANIMAL_X_OFFSET_STEP;
            delay -= INITIAL_ANIMAL_DELAY_STEP;
        }
    }

    /**
     * Spawns a random animal at the capsule position.
     * ROM: loc_3F3A8 random spawn logic
     */
    private void spawnRandomAnimal() {
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager == null) {
            return;
        }

        int baseX = spawn.x();
        int baseY = spawn.y();

        // ROM: jsr RandomNumber / andi.w #$1F,d0 / subq.w #6,d0
        int randomOffset = ThreadLocalRandom.current().nextInt(32) - 6;

        // ROM: tst.w d1 / bpl + / neg.w d0
        // Uses high word of random number for direction
        if (ThreadLocalRandom.current().nextBoolean()) {
            randomOffset = -randomOffset;
        }

        ObjectSpawn animalSpawn = new ObjectSpawn(
                baseX + randomOffset, baseY,
                0x28, 0, 0, false, 0
        );
        EggPrisonAnimalInstance animal = new EggPrisonAnimalInstance(animalSpawn, SPAWN_ANIMAL_DELAY);
        objectManager.addDynamicObject(animal);
    }

    /**
     * Spawns an explosion at the given position.
     */
    private void spawnExplosion(int x, int y) {
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (objectManager == null || renderManager == null) {
            return;
        }

        ExplosionObjectInstance explosion = new ExplosionObjectInstance(0x27, x, y, renderManager);
        objectManager.addDynamicObject(explosion);
    }

    /**
     * Spawns a static destroyed capsule visual.
     * Mimics ROM orphaned child object behavior - body visual persists after parent deletion.
     */
    private void spawnDestroyedCapsule() {
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager == null) {
            return;
        }

        DestroyedEggPrisonObjectInstance destroyedCapsule =
                new DestroyedEggPrisonObjectInstance(spawn, spawn.x(), spawn.y());
        objectManager.addDynamicObject(destroyedCapsule);
    }

    /**
     * Checks if any animals are still present in object RAM.
     * ROM: loc_3F406 loop
     */
    private boolean areAnimalsPresent() {
        ObjectManager objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager == null) {
            return false;
        }

        for (var obj : objectManager.getActiveObjects()) {
            if (obj instanceof AnimalObjectInstance || obj instanceof EggPrisonAnimalInstance) {
                if (!obj.isDestroyed()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Triggers end of act sequence (Load_EndOfAct).
     */
    private void triggerEndOfAct() {
        if (resultsTriggered) {
            return;
        }
        resultsTriggered = true;

        LOGGER.info("All animals gone, triggering Load_EndOfAct");

        // Play stage clear music
        try {
            AudioManager.getInstance().playMusic(Sonic2Constants.MusID_StageClear);
        } catch (Exception e) {
            LOGGER.warning("Failed to play stage clear music: " + e.getMessage());
        }

        // Spawn results screen
        LevelManager levelManager = LevelManager.getInstance();
        var levelGamestate = levelManager.getLevelGamestate();
        int elapsedSeconds = levelGamestate != null ? levelGamestate.getElapsedSeconds() : 0;
        int ringCount = lastPlayer != null ? lastPlayer.getRingCount() : 0;
        int actNumber = levelManager.getCurrentAct() + 1;
        boolean allRingsCollected = levelManager.areAllRingsCollected();

        ResultsScreenObjectInstance resultsScreen = new ResultsScreenObjectInstance(
                elapsedSeconds, ringCount, actNumber, allRingsCollected);
        ObjectManager objectManager = levelManager.getObjectManager();
        if (objectManager != null) {
            objectManager.addDynamicObject(resultsScreen);
        }

        // Spawn static destroyed capsule visual before destroying main object
        // This mimics the ROM behavior where the body visual child object (routine 2)
        // remains alive after parent deletion (loc_3F406), keeping the open capsule visible
        spawnDestroyedCapsule();

        // Destroy button object
        if (buttonObject != null) {
            buttonObject.destroyButton();
        }

        // Mark this object for deletion
        setDestroyed(true);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        PatternSpriteRenderer renderer = renderManager != null
                ? renderManager.getEggPrisonRenderer()
                : null;

        if (renderer == null || !renderer.isReady()) {
            renderPlaceholder(commands);
            return;
        }

        int bodyX = spawn.x();
        int bodyY = spawn.y();

        // Draw main body (priority 4)
        renderer.drawFrameIndex(bodyAnimFrame, bodyX, bodyY, false, false);

        // Button now renders itself as a separate object

        // Draw lock (priority 3) if still visible
        if (lockVisible) {
            renderer.drawFrameIndex(FRAME_LOCK, lockX, lockY, false, false);
        }
    }

    private void renderPlaceholder(List<GLCommand> commands) {
        int x = spawn.x();
        int y = spawn.y();

        // Body
        appendBox(commands, x, y, BODY_HALF_WIDTH, BODY_HALF_HEIGHT, 0.8f, 0.6f, 0.2f);

        // Button renders itself as a separate object

        // Lock
        if (lockVisible) {
            appendBox(commands, lockX, lockY, 8, 8, 0.5f, 0.5f, 0.5f);
        }
    }

    private void appendBox(List<GLCommand> commands, int cx, int cy, int hw, int hh,
                           float r, float g, float b) {
        int left = cx - hw;
        int right = cx + hw;
        int top = cy - hh;
        int bottom = cy + hh;

        appendLine(commands, left, top, right, top, r, g, b);
        appendLine(commands, right, top, right, bottom, r, g, b);
        appendLine(commands, right, bottom, left, bottom, r, g, b);
        appendLine(commands, left, bottom, left, top, r, g, b);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return spawn.y();
    }
}
