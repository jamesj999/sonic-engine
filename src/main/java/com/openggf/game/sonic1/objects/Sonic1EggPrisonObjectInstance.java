package com.openggf.game.sonic1.objects;

import com.openggf.audio.GameMusic;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.camera.Camera;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 1 Prison Capsule / EggPrison (Object 0x3E).
 * <p>
 * ROM-accurate implementation based on docs/s1disasm/_incObj/3E Prison Capsule.asm.
 * <p>
 * The capsule is a solid object that opens when the boss is defeated. The button
 * (subtype 1) sits on top; when Sonic lands on it after the boss is gone, the
 * opening sequence begins: explosions spawn, then 8 animals burst out, followed
 * by continuous animal spawning. When all animals are gone, GotThroughAct fires.
 * <p>
 * State machine (mirroring ROM routine progression):
 * <ol>
 *   <li>IDLE - Solid body, waiting for button trigger (Pri_BodyMain, routine 2)</li>
 *   <li>EXPLODING - Spawning explosion particles, 60 frames (Pri_Explosion, routine $A)</li>
 *   <li>ANIMAL_SPAWN - Initial burst of 8 + continuous spawning, 150 frames (Pri_Animals, routine $C)</li>
 *   <li>END_ACT - Checking for all animals to leave, then GotThroughAct (Pri_EndAct, routine $E)</li>
 * </ol>
 */
public class Sonic1EggPrisonObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SpawnRewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic1EggPrisonObjectInstance.class.getName());

    // === Native width and SolidObject collision from Pri_Var/Pri_BodyMain ===
    private static final int BODY_ACTIVE_WIDTH = 0x20; // obActWid, read by Sonic_Move
    private static final int BODY_HALF_WIDTH = 0x2B;   // 43 pixels
    private static final int BODY_HALF_HEIGHT = 0x18;   // 24 pixels

    // From Pri_Var: subtype 0 priority = 4
    private static final int PRIORITY = 4;

    // From disassembly: move.w obY(a0),pri_origY(a0)
    // Button sits at original Y, body renders at original Y
    // After opening: addq.w #8,obY(a0) moves switch down 8 pixels

    // === Explosion phase timing ===
    // From disassembly: move.w #60,obTimeFrame(a0) in Pri_Switched
    private static final int EXPLOSION_TIMER = 60;

    // === Animal burst parameters ===
    // From disassembly: moveq #7,d6 (8 animals); move.w #$9A,d5; moveq #-$1C,d4
    private static final int INITIAL_ANIMAL_COUNT = 8;
    private static final int INITIAL_ANIMAL_DELAY_BASE = 0x9A;
    private static final int INITIAL_ANIMAL_DELAY_STEP = 8;
    private static final int INITIAL_ANIMAL_X_OFFSET_START = -0x1C;
    private static final int INITIAL_ANIMAL_X_OFFSET_STEP = 7;

    // === Continuous spawn phase ===
    // From disassembly: move.w #150,obTimeFrame(a0)
    private static final int SPAWN_PHASE_DURATION = 150;
    // From disassembly: move.w #$C,objoff_36(a1) — animal delay
    private static final int SPAWN_ANIMAL_DELAY = 0xC;
    // From Pri_SpawnAnimals: addi.w #32,obY(a0)
    private static final int ANIMAL_SPAWN_Y_OFFSET = 32;

    // === Explosion random spread ===
    // ROM: move.b d0,d1 / lsr.b #2,d1 / subi.w #$20,d1 → X range [-32, +31]
    // ROM: lsr.w #8,d0 / lsr.b #3,d0 → Y range [0, 31]
    private static final int EXPLOSION_X_RANGE = 0x20;  // subtracted from 0-63
    // === Mapping frame indices from Map_Pri ===
    private static final int FRAME_CAPSULE = 0;
    private static final int FRAME_BROKEN = 2;
    private static final int FRAME_BLANK = 6;

    // Released S1 Pri_EndAct starts at native slot 1 and uses
    // ((128 - 1) / 2) - 1 as DBF's counter: 63 iterations, slots 1..63.
    // Dynamic objects begin at slot 32, so animals in slots 64..127 are
    // accidentally invisible to the completion scan.
    private static final int RELEASED_END_ACT_LAST_SCANNED_SLOT = 63;

    // === State machine ===
    private enum State {
        IDLE,           // Pri_BodyMain (routine 2): waiting for button
        EXPLODING,      // Pri_Explosion (routine $A): spawning explosions
        ANIMAL_SPAWN,   // Pri_Animals (routine $C): continuous random spawning
        END_ACT,        // Pri_EndAct (routine $E): waiting for animals to clear
        COMPLETE        // GotThroughAct triggered, results screen active
    }

    private State state = State.IDLE;
    private int timer;
    private int buttonTriggerVIntRunCount = -1;
    private int currentFrame = FRAME_CAPSULE;
    private boolean buttonTriggered;
    private boolean resultsTriggered;

    // ROM obX/obY of the object that actually drives Pri_Explosion /
    // Pri_SpawnAnimals / Pri_Animals -- the depressed SWITCH, not the body.
    // See onButtonTriggered(int,int).
    private int spawnerX;
    private int spawnerY;

    // Button sub-object
    private Sonic1EggPrisonButtonObjectInstance buttonObject;

    // Player reference for results screen
    private AbstractPlayableSprite lastPlayer;

    public Sonic1EggPrisonObjectInstance(ObjectSpawn spawn) {
        super(spawn, "EggPrison");
    }

    /**
     * Called by the button sub-object (subtype 1) to register itself with this body.
     * The button is a separate placement entry in the ROM; it finds us on first update.
     */
    public void registerButton(Sonic1EggPrisonButtonObjectInstance button) {
        this.buttonObject = button;
    }

    /**
     * Called by button when player lands on it.
     * Corresponds to Pri_Switched first-time trigger path.
     */
    public void onButtonTriggered() {
        onButtonTriggered(spawn.x(), spawn.y());
    }

    /**
     * ROM Pri_Switch is the SWITCH object's own routine: it does
     * {@code addq.w #8,obY(a0)} and then advances its OWN obRoutine to $A
     * (Pri_Explosion), $C (Pri_Animals) and $E (Pri_EndAct)
     * (docs/s1disasm/_incObj/3E Prison Capsule.asm:88-115). Every
     * {@code obX(a0)}/{@code obY(a0)} that Pri_Explosion, Pri_SpawnAnimals and
     * Pri_Animals read to place explosions and animals is therefore the
     * DEPRESSED SWITCH's position, not the capsule body's. The engine splits the
     * one ROM slot into a body and a button instance, so the button hands its
     * post-depression origin over here when it fires.
     *
     * @param spawnerX the switch's {@code obX}
     * @param spawnerY the switch's {@code obY} after {@code addq.w #8,obY(a0)}
     */
    void onButtonTriggered(int spawnerX, int spawnerY) {
        if (buttonTriggered) {
            return;
        }
        buttonTriggered = true;
        this.spawnerX = spawnerX;
        this.spawnerY = spawnerY;
        // ROM Pri_Switch only writes routine=$A/obTimeFrame=60 and returns; the
        // first Pri_Explosion pass is always the FRAME AFTER the trigger,
        // regardless of where the body sits in object RAM
        // (docs/s1disasm/_incObj/3E Prison Capsule.asm:88-115). The engine runs
        // the explosion phase on the body object, so when the body's slot
        // executes after the button's in the same frame it would otherwise
        // tick the 60-frame timer once on the trigger frame, ending the phase
        // a frame early and shifting the whole animal window.
        ObjectManager triggerObjectManager = services().objectManager();
        buttonTriggerVIntRunCount = triggerObjectManager != null ? triggerObjectManager.getVblaCounter() : -1;

        LOGGER.info("S1 EggPrison triggered at X=" + spawn.x());

        // ROM: clr.b (f_timecount).w — stop time counter
        var levelGamestate = services().levelGamestate();
        if (levelGamestate != null) {
            levelGamestate.pauseTimer();
        }

        // ROM: Pri_Switch (3E Prison Capsule.asm:97) only does clr.b (f_lockscreen).w
        // here — it does NOT touch v_limitleft2/v_limitright2. The camera keeps
        // scrolling to v_limitright2, which each act-3 boss's escape routine already
        // expanded to boss_*_end via addq.w #2,(v_limitright2). Locking the camera to
        // its current X here froze it ~10px short of v_limitright2 in LZ3, where the
        // player lands on the switch (un-roll) before the camera has finished
        // scrolling to the boundary (boss_lz_end=$2031), diverging camera_x by 1px
        // and growing. The Signpost screen-lock (v_limitleft2 = v_limitright2) is a
        // separate end-of-act path that the act-3 boss zones never run, so the camera
        // must remain free to reach v_limitright2.
        Camera camera = services().camera();
        if (camera != null && camera.getFrozen()) {
            camera.setFrozen(false);
        }

        // Clear boss fight state so doLevelBoundary allows Sonic to exceed
        // the right screen edge (+64 extra when boss fight is not active).
        // This is needed even if the boss was never "defeated" (e.g. LZ boss
        // just escapes without being hit 8 times).
        services().gameState().setCurrentBossId(0);
        // ROM: clr.b (f_lockscreen).w (s1disasm/_incObj/3E Prison Capsule.asm:97)
        // — release the persistent screen lock that Sonic_LevelBound consumes for
        // its +64 right-boundary extension gate.
        services().gameState().setScreenLocked(false);

        // ROM: move.b #1,(f_lockctrl).w — lock player controls
        // ROM: move.w #(btnR<<8),(v_jpadhold2).w — force right input
        // These are applied in the update loop to ensure they stick

        // Body stays on FRAME_CAPSULE during explosion phase.
        // It switches to FRAME_BROKEN when .makeanimal fires (v_bossstatus = 2 in ROM)

        // Begin explosion phase (Pri_Switched sets routine = $A)
        state = State.EXPLODING;
        timer = EXPLOSION_TIMER;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }
        this.lastPlayer = player;

        switch (state) {
            case IDLE -> updateIdle();
            case EXPLODING -> {
                if (vIntRunCount != buttonTriggerVIntRunCount) {
                    updateExploding(vIntRunCount);
                }
            }
            case ANIMAL_SPAWN -> updateAnimalSpawn(vIntRunCount);
            case END_ACT -> updateEndAct(player);
            case COMPLETE -> { /* Nothing — results screen active */ }
        }

        // Keep player locked to right during opening sequence
        if (buttonTriggered && state != State.IDLE && state != State.COMPLETE) {
            player.setForceInputRight(true);
            player.setControlLocked(true);
        }
    }

    /**
     * Pri_BodyMain (routine 2): Solid object, waiting for button trigger.
     * In S1, the body just acts as solid until the button activates.
     */
    private void updateIdle() {
        // Nothing to do — button handles the trigger via onButtonTriggered()
    }

    /**
     * Pri_Explosion (routines 6/8/$A): Spawn explosion particles.
     * ROM: Spawn one explosion every 8 frames with random offset.
     * After timer expires, spawn initial animal burst.
     */
    private void updateExploding(int vIntRunCount) {
        // ROM: move.b (v_vbla_byte).w,d0 / andi.b #7,d0 / bne.s .skip
        if ((vIntRunCount & 7) == 0) {
            spawnExplosion();
        }

        timer--;
        if (timer <= 0) {
            // ROM: .makeanimal — move.b #2,(v_bossstatus).w
            // This triggers Pri_BodyMain to show broken frame (frame 2)
            currentFrame = FRAME_BROKEN;

            // ROM: move.b #$C,obRoutine(a0) — switch to animal spawning
            // ROM: move.b #6,obFrame(a0) — blank frame for explosion sub-object.
            // The switch/explosion driver is the SAME ROM object slot as the
            // button, so this is the button going invisible for the rest of
            // the act (3E Prison Capsule.asm:134-137) — not a destroy.
            if (buttonObject != null) {
                buttonObject.goBlank();
            }
            spawnInitialAnimals();
            state = State.ANIMAL_SPAWN;
            timer = SPAWN_PHASE_DURATION;
        }
    }

    /**
     * Pri_Animals (routine $C): Continuous random animal spawning.
     * ROM: Every 8 frames, spawn one animal at random X offset.
     */
    private void updateAnimalSpawn(int vIntRunCount) {
        // ROM: move.b (v_vbla_byte).w,d0 / andi.b #7,d0 / bne.s .skip
        if ((vIntRunCount & 7) == 0) {
            spawnRandomAnimal();
        }

        timer--;
        if (timer <= 0) {
            state = State.END_ACT;
        }
    }

    /**
     * Pri_EndAct (routine $E): Wait for all animals to leave, then trigger level end.
     * ROM: Loops through object RAM looking for id_Animals (0x28).
     */
    private void updateEndAct(AbstractPlayableSprite player) {
        // The released-game Pri_EndAct scans immediately. Its apparent
        // `move.w #3*60,obTimeFrame(a0)` predecessor is guarded by FixBugs=0
        // and is explicitly unused: Pri_EndAct never reads obTimeFrame.
        if (!areAnimalsPresent()) {
            triggerGotThroughAct(player);
        }
    }

    /**
     * Spawns an explosion at random offset from capsule center.
     * ROM: Pri_Explosion random offset logic.
     */
    private void spawnExplosion() {
        ObjectManager objectManager = services().objectManager();
        final ObjectRenderManager renderManager = services().renderManager();
        if (objectManager == null || renderManager == null) {
            return;
        }

        // ROM: move.w obX(a0),obX(a1) / move.w obY(a0),obY(a1) -- a0 is the
        // depressed switch (3E Prison Capsule.asm:118-120).
        final int baseX = spawnerX;
        final int baseY = spawnerY;

        // ROM: move.b d0,d1 / lsr.b #2,d1 / subi.w #$20,d1 → X offset [-32, +31]
        int random = services().rng().nextWord();
        final int xOff = ((random & 0xFF) >>> 2) - EXPLOSION_X_RANGE;
        // ROM: lsr.w #8,d0 / lsr.b #3,d0 → Y offset [0, 31]
        final int yOff = ((random >>> 8) & 0xFF) >>> 3;

        // ROM: Explosion object 0x3F plays sfx_Bomb on init
        spawnFreeChild(() -> new ExplosionObjectInstance(
                0x3F, baseX + xOff, baseY + yOff, renderManager, Sonic1Sfx.BOSS_EXPLOSION.id));
    }

    /**
     * Spawns 8 initial animals with staggered delays.
     * ROM: Pri_Explosion .makeanimal loop — d6=7, d5=$9A, d4=-$1C
     */
    private void spawnInitialAnimals() {
        if (services().objectManager() == null) {
            return;
        }

        // ROM Pri_SpawnAnimals: addi.w #32,obY(a0) -- "load all animals 32px
        // below explosions" (3E Prison Capsule.asm:138). This is a permanent
        // write to the spawner's own obY, so Pri_Animals' later per-8-frame
        // spawns use the shifted origin too.
        spawnerY += ANIMAL_SPAWN_Y_OFFSET;

        final int baseX = spawnerX;
        final int baseY = spawnerY;
        int xOffset = INITIAL_ANIMAL_X_OFFSET_START;
        int delay = INITIAL_ANIMAL_DELAY_BASE;

        for (int i = 0; i < INITIAL_ANIMAL_COUNT; i++) {
            final int fXOffset = xOffset;
            final int fDelay = delay;
            // Pri_SpawnAnimals only writes obX/obY/animal_prisondelay into the new
            // slot; it draws no random number. The animal's OWN Anml_FromEnemy init
            // calls RandomNumber on its first execution frame
            // (3E Prison Capsule.asm:152-160; 28, 29 Animals and Points.asm:171-176).
            spawnFreeChild(() -> {
                ObjectSpawn animalSpawn = new ObjectSpawn(
                        baseX + fXOffset, baseY,
                        0x28, 0, 0, false, 0);
                return new Sonic1AnimalsObjectInstance(animalSpawn, 0, fDelay);
            });

            xOffset += INITIAL_ANIMAL_X_OFFSET_STEP;
            delay -= INITIAL_ANIMAL_DELAY_STEP;
        }
    }

    /**
     * Spawns a random animal at the capsule position.
     * ROM: Pri_Animals random spawn — andi.w #$1F,d0 / subq.w #6,d0
     */
    private void spawnRandomAnimal() {
        if (services().objectManager() == null) {
            return;
        }

        // Pri_Animals reuses the spawner's obX/obY, which Pri_SpawnAnimals
        // already pushed 32px down (3E Prison Capsule.asm:138,170-172).
        final int baseX = spawnerX;
        final int baseY = spawnerY;

        // ROM: jsr (RandomNumber).l / andi.w #$1F,d0 / subq.w #6,d0
        // Exactly one RandomNumber call here; the animal's own init draws again
        // (3E Prison Capsule.asm:174-181).
        int random = services().rng().nextWord();
        int randomOffset = (random & 0x1F) - 6;
        // ROM: tst.w d1 / bpl.s .setX / neg.w d0 — d1 holds the NEW seed after
        // RandomNumber, so the sign test reads bit 15 of the updated seed's low
        // word, not of the returned d0 (3E Prison Capsule.asm:180-185;
        // _incObj/sub RandomNumber.asm).
        if ((services().rng().getSeed() & 0x8000) != 0) {
            randomOffset = -randomOffset;
        }
        final int fOffset = randomOffset;

        spawnFreeChild(() -> {
            ObjectSpawn animalSpawn = new ObjectSpawn(
                    baseX + fOffset, baseY,
                    0x28, 0, 0, false, 0);
            return new Sonic1AnimalsObjectInstance(animalSpawn, 0, SPAWN_ANIMAL_DELAY);
        });
    }

    /**
     * Checks if any Sonic1AnimalsObjectInstance objects remain active.
     * ROM: Pri_EndAct loop through object RAM for id_Animals.
     */
    private boolean areAnimalsPresent() {
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return false;
        }

        for (var obj : objectManager.getActiveObjects()) {
            if (obj instanceof Sonic1AnimalsObjectInstance animal
                    && !obj.isDestroyed()
                    && releasedEndActScansSlot(animal.getSlotIndex())) {
                return true;
            }
        }
        return false;
    }

    static boolean releasedEndActScansSlot(int slotIndex) {
        return slotIndex >= 1 && slotIndex <= RELEASED_END_ACT_LAST_SCANNED_SLOT;
    }

    /**
     * GotThroughAct — triggers level completion and results screen.
     * ROM: jsr (GotThroughAct).l / jmp (DeleteObject).l
     */
    private void triggerGotThroughAct(AbstractPlayableSprite player) {
        if (resultsTriggered) {
            return;
        }
        var levelGamestate = services().levelGamestate();
        final int elapsedSeconds = levelGamestate != null ? levelGamestate.getElapsedSeconds() : 0;
        final int ringCount = player.getRingCount();
        final int actNumber = services().currentAct() + 1;
        Sonic1FixedEndCardSlot.ClaimResult claim = Sonic1FixedEndCardSlot.claim(
                services(),
                new Sonic1FixedEndCardSlot.ResultsData(
                        elapsedSeconds, ringCount, actNumber, false));
        Sonic1ResultsScreenObjectInstance card = claim.requireCard();
        if (claim.state() == Sonic1FixedEndCardSlot.ClaimState.EXISTING_COMMITTED) {
            resultsTriggered = true;
            state = State.COMPLETE;
            if (buttonObject != null) {
                buttonObject.detachFromParent();
            }
            return;
        }
        if (!queueResultsPlc()) {
            return;
        }
        card.markResultsPlcCommitted();
        resultsTriggered = true;
        state = State.COMPLETE;

        LOGGER.info("S1 EggPrison: all animals gone, triggering GotThroughAct");

        // ROM: clr.b (v_invinc).w — clear invincibility
        player.setInvincibleFrames(0);

        // ROM: move.w #bgm_GotThrough,d0; jsr (QueueSound2).l
        try {
            services().playMusic(GameMusic.ACT_CLEAR);
        } catch (Exception e) {
            LOGGER.warning("Failed to play stage clear music: " + e.getMessage());
        }

        // Detach button (keep it alive for visual during results)
        if (buttonObject != null) {
            buttonObject.detachFromParent();
        }
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

    // === SolidObjectProvider ===

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(BODY_HALF_WIDTH, BODY_HALF_HEIGHT, BODY_HALF_HEIGHT);
    }

    @Override
    public int getBalanceWidthPixels() {
        // Pri_Var stores obActWid=$20 for the capsule body. Pri_BodyMain adds
        // Sonic's $B solid width only when it passes d1=$2B to SolidObject;
        // Sonic_Move later reads the unchanged obActWid byte for balancing.
        // docs/s1disasm/_incObj/3E Prison Capsule.asm:31,69
        return BODY_ACTIVE_WIDTH;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return true;
    }

    @Override
    public boolean shouldStayActiveWhenRemembered() {
        return true;
    }

    // === Rendering ===

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        PatternSpriteRenderer renderer = renderManager != null
                ? renderManager.getEggPrisonRenderer()
                : null;

        if (renderer == null || !renderer.isReady()) {
            renderPlaceholder(commands);
            return;
        }

        renderer.drawFrameIndex(currentFrame, spawn.x(), spawn.y(), false, false);
    }

    private void renderPlaceholder(List<GLCommand> commands) {
        int x = spawn.x();
        int y = spawn.y();
        int left = x - BODY_HALF_WIDTH;
        int right = x + BODY_HALF_WIDTH;
        int top = y - BODY_HALF_HEIGHT;
        int bottom = y + BODY_HALF_HEIGHT;

        appendLine(commands, left, top, right, top, 0.8f, 0.6f, 0.2f);
        appendLine(commands, right, top, right, bottom, 0.8f, 0.6f, 0.2f);
        appendLine(commands, right, bottom, left, bottom, 0.8f, 0.6f, 0.2f);
        appendLine(commands, left, bottom, left, top, 0.8f, 0.6f, 0.2f);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                             float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawRect(spawn.x(), spawn.y(), BODY_HALF_WIDTH, BODY_HALF_HEIGHT, 0.0f, 1.0f, 0.0f);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }
}
