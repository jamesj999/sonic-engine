package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Collection;
import java.util.List;

/**
 * SBZ2 Eggman cutscene object (Object 0x82) from the Sonic 1 disassembly.
 * <p>
 * This is NOT a boss fight. It is a scripted cutscene where Eggman walks to a
 * button, leaps onto it, and signals the false floor (Object 0x83) to collapse.
 * <p>
 * Two sub-objects are managed:
 * <ol>
 *   <li>The Eggman body (this object, routines 0 and 2)</li>
 *   <li>A button child ({@link ScrapEggmanButton}, routine 4)</li>
 * </ol>
 * <p>
 * ROM reference: s1disasm, Obj82 (ScrapStomp / SEgg)
 */
public class Sonic1ScrapEggmanInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    // ---- Position constants from disassembly ----
    private static final int BOSS_SBZ2_X = Sonic1Constants.BOSS_SBZ2_X;
    private static final int BOSS_SBZ2_Y = Sonic1Constants.BOSS_SBZ2_Y;

    /** Eggman initial position: (BOSS_SBZ2_X + 0x110, BOSS_SBZ2_Y + 0x94) */
    private static final int EGGMAN_INIT_X = BOSS_SBZ2_X + 0x110; // 0x2160
    private static final int EGGMAN_INIT_Y = BOSS_SBZ2_Y + 0x94;  // 0x05A4

    /** Button position: (BOSS_SBZ2_X + 0xE0, BOSS_SBZ2_Y + 0xAC) */
    private static final int BUTTON_X = BOSS_SBZ2_X + 0xE0;  // 0x2130
    private static final int BUTTON_Y = BOSS_SBZ2_Y + 0xAC;  // 0x05BC

    /** X threshold for stopping horizontal movement during leap */
    private static final int LEAP_STOP_X = BOSS_SBZ2_X + 0xE2; // 0x2132

    /** Y threshold for detecting landing during leap */
    private static final int LANDING_Y = BOSS_SBZ2_Y + 0x85; // 0x0595

    /** Y clamp for final landing position */
    private static final int LANDING_CLAMP_Y = BOSS_SBZ2_Y + 0x8B; // 0x059B

    // ---- Velocity constants (8.8 fixed-point) ----
    /** Leap X velocity: -0xFC = approx -0.98 px/frame */
    private static final int LEAP_X_VEL = -0xFC;
    /** Leap Y velocity: -0x3C0 = approx -3.75 px/frame (jumping up) */
    private static final int LEAP_Y_VEL = -0x3C0;
    /** Gravity applied per frame during leap: 0x24 */
    private static final int GRAVITY = 0x24;

    // ---- Timer constants ----
    /** Laugh duration before leaping (180 frames = 3 seconds) */
    private static final int LAUGH_TIMER = 180;
    /** Sub-timer before launching leap (15 frames) */
    private static final int PRE_LEAP_TIMER = 15;

    // ---- Collision constants ----
    /** obColType = 0x0F, obColProp = 0x10: can hurt Sonic, size index 0x0F */
    private static final int COLLISION_SIZE_INDEX = 0x0F;
    private static final int COLLISION_PROPERTY = 0x10;

    // ---- Animation definitions ----
    // Anim 0 (stand): frame 0, duration 0x7E
    // Anim 1 (laugh): frames 1,2 alternating, duration 6
    // Anim 2 (jump):  frames 3,4,4,0,0,0, duration 0x0E

    private static final int[][] ANIM_FRAMES = {
            {0},             // Anim 0: stand
            {1, 2},          // Anim 1: laugh
            {3, 4, 4, 0, 0, 0} // Anim 2: jump
    };
    private static final int[] ANIM_DURATIONS = {
            0x7E, // Anim 0: stand
            6,    // Anim 1: laugh
            0x0E  // Anim 2: jump
    };

    // ---- State machine phases (ob2ndRout) ----
    private static final int PHASE_CHK_SONIC = 0;
    private static final int PHASE_PRE_LEAP = 2;
    private static final int PHASE_LEAP = 4;
    private static final int PHASE_LAUGH_POST = 6;

    // ---- Instance state ----

    private int currentX;
    private int currentY;

    /** 16.8 fixed-point X position (upper 16 bits = pixel X, lower 8 = subpixel) */
    private int xFixed;
    /** 16.8 fixed-point Y position */
    private int yFixed;

    /** X velocity in 8.8 fixed-point */
    private int xVel;
    /** Y velocity in 8.8 fixed-point */
    private int yVel;

    /** State machine phase (ob2ndRout equivalent) */
    private int phase;

    /** General-purpose countdown timer */
    private int timer;

    /** Current animation index */
    private int currentAnim;
    /** Current frame within the animation */
    private int animFrameIndex;
    /** Animation tick countdown */
    private int animTimer;
    /** Current mapping frame to render */
    private int mappingFrame;

    /** True when the button has been pressed (signals FalseFloor) */
    private boolean switchPressed;
    /** True when we have already signalled the false floor */
    private boolean floorSignalled;

    /** The button child object */
    private ScrapEggmanButton button;

    public Sonic1ScrapEggmanInstance(ObjectSpawn spawn) {
        this(spawn, spawn.objectId() == Sonic1ObjectIds.SCRAP_EGGMAN);
    }

    private Sonic1ScrapEggmanInstance(ObjectSpawn spawn, boolean spawnButton) {
        super(spawn, "ScrapEggman");
        // Initialise Eggman position
        this.currentX = EGGMAN_INIT_X;
        this.currentY = EGGMAN_INIT_Y;
        this.xFixed = currentX << 8;
        this.yFixed = currentY << 8;

        this.xVel = 0;
        this.yVel = 0;
        this.phase = PHASE_CHK_SONIC;
        this.switchPressed = false;
        this.floorSignalled = false;

        // Start with stand animation
        setAnimation(0);

        if (!spawnButton) {
            return;
        }

        // Spawn the button child
        ObjectSpawn buttonSpawn = new ObjectSpawn(
                BUTTON_X, BUTTON_Y,
                spawn.objectId(), 0, 0, false, 0);
        this.button = new ScrapEggmanButton(buttonSpawn, this);

        // Add button as dynamic object
        spawnDynamicObject(button);
    }

    @Override
    public Sonic1ScrapEggmanInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic1ScrapEggmanInstance restored = ObjectConstructionContext.construct(ctx.objectServices(),
                () -> new Sonic1ScrapEggmanInstance(ctx.spawn(), false));
        restored.adoptLiveButtonForRewind(ctx);
        return restored;
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public boolean isPersistent() {
        return true; // Boss-area cutscene object, must stay active
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        switch (phase) {
            case PHASE_CHK_SONIC -> updateChkSonic(player);
            case PHASE_PRE_LEAP -> updatePreLeap();
            case PHASE_LEAP -> updateLeap();
            case PHASE_LAUGH_POST -> updateLaughPost();
        }

        updateAnimation();
    }

    // ---- Phase 0: SEgg_ChkSonic ----
    // ROM: eggmanX - sonicX; bhs 128 (unsigned >= 128 means out of range).
    // Triggers when Sonic is within 128px to the LEFT of Eggman.
    // We use Math.abs for safety since Sonic always approaches from the left.
    private void updateChkSonic(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        int playerX = player.getCentreX();
        // ROM check: obX(a0) - player.obX; cmpi.w #128; bhs
        // Unsigned comparison: triggers when 0 <= (eggmanX - sonicX) < 128
        int dx = currentX - playerX;
        if (dx >= 0 && dx < 128) {
            // Sonic is close enough - start laughing
            timer = LAUGH_TIMER;
            setAnimation(1); // laugh
            phase = PHASE_PRE_LEAP;
        }

        // ROM always calls SpeedToPos even in phase 0 (velocities are 0)
        speedToPos();
    }

    // ---- Phase 2: SEgg_PreLeap ----
    // Count down 180-frame timer. When expired, start jump animation and prepare leap.
    private void updatePreLeap() {
        timer--;
        if (timer <= 0) {
            // Switch to jump animation
            setAnimation(2);
            // Shift Y down by 4px (ROM: addi.w #4,y_pos(a0))
            currentY += 4;
            yFixed = currentY << 8;
            // Set sub-timer for pre-leap delay
            timer = PRE_LEAP_TIMER;
            phase = PHASE_LEAP;
        }

        // ROM always calls SpeedToPos
        speedToPos();
    }

    // ---- Phase 4: SEgg_Leap ----
    // Sub-timer counts down. When timer reaches 0, velocities are set ONCE.
    // Then: apply gravity, check X threshold, check landing.
    private void updateLeap() {
        timer--;
        if (timer > 0) {
            // bgt.s loc_199D0 -> SpeedToPos
            speedToPos();
            return;
        }
        if (timer == 0) {
            // Timer just reached 0: set velocities
            xVel = LEAP_X_VEL;
            yVel = LEAP_Y_VEL;
        }
        // timer < 0: continue leap without re-setting velocities

        // Stop X movement when reaching button X position
        // ROM: cmpi.w #boss_sbz2_x+$E2,obX(a0); bgt.s (skip clear)
        if (currentX <= LEAP_STOP_X) {
            xVel = 0;
        }

        // Apply gravity to Y velocity
        yVel += GRAVITY;

        // Check for landing
        if (yVel > 0 && currentY >= LANDING_Y) {
            // ROM: move.w #"SW",obSubtype(a0) -> signals the button
            switchPressed = true;

            // Clamp to landing position
            if (currentY >= LANDING_CLAMP_Y) {
                currentY = LANDING_CLAMP_Y;
                yFixed = currentY << 8;
                yVel = 0;
                xVel = 0;

                // Landed: signal false floor to disintegrate
                if (!floorSignalled) {
                    signalFalseFloor();
                    floorSignalled = true;
                    // Advance to post-laugh phase
                    setAnimationForce(1); // laugh
                    phase = PHASE_LAUGH_POST;
                }
            }
        }

        // SpeedToPos: apply velocities to position
        speedToPos();
    }

    // ---- Phase 6: Eggman laughs while floor collapses ----
    private void updateLaughPost() {
        // ROM: SpeedToPos (velocities should be zero)
        speedToPos();
    }

    /**
     * ROM SpeedToPos equivalent: applies 8.8 velocity to 16.8 position.
     */
    private void speedToPos() {
        xFixed += xVel;
        currentX = xFixed >> 8;

        yFixed += yVel;
        currentY = yFixed >> 8;
    }

    /**
     * ROM SEgg_FindBlocks: Iterates all active objects to find any FalseFloor
     * (Object 0x83) implementing {@link Disintegratable} and signals it to
     * begin disintegrating.
     */
    private void signalFalseFloor() {
        if (services().objectManager() == null) {
            return;
        }

        // getActiveObjects() includes both placement-managed and dynamic objects
        Collection<ObjectInstance> activeObjects = services().objectManager().getActiveObjects();
        for (ObjectInstance obj : activeObjects) {
            if (obj instanceof Disintegratable target) {
                target.signalDisintegrate();
            }
        }
    }

    /**
     * Interface for objects that can be signalled to disintegrate by the
     * ScrapEggman cutscene. Implemented by Sonic1FalseFloorInstance (Object 0x83).
     */
    public interface Disintegratable {
        void signalDisintegrate();
    }

    // ---- Animation system ----

    private void setAnimation(int animIndex) {
        if (animIndex == currentAnim && animFrameIndex > 0) {
            return;
        }
        currentAnim = animIndex;
        animFrameIndex = 0;
        animTimer = ANIM_DURATIONS[animIndex];
        mappingFrame = ANIM_FRAMES[animIndex][0];
    }

    /** Force-set animation even if already playing */
    private void setAnimationForce(int animIndex) {
        currentAnim = animIndex;
        animFrameIndex = 0;
        animTimer = ANIM_DURATIONS[animIndex];
        mappingFrame = ANIM_FRAMES[animIndex][0];
    }

    private void updateAnimation() {
        if (currentAnim < 0 || currentAnim >= ANIM_FRAMES.length) {
            return;
        }

        animTimer--;
        if (animTimer <= 0) {
            int[] frames = ANIM_FRAMES[currentAnim];
            animFrameIndex++;
            if (animFrameIndex >= frames.length) {
                animFrameIndex = 0; // loop
            }
            mappingFrame = frames[animFrameIndex];
            animTimer = ANIM_DURATIONS[currentAnim];
        }
    }

    // ---- TouchResponseProvider ----

    @Override
    public int getCollisionFlags() {
        if (isDestroyed()) {
            return 0;
        }
        // obColType = 0x0F
        return 0x80 | (COLLISION_SIZE_INDEX & 0x3F);
    }

    @Override
    public int getCollisionProperty() {
        return COLLISION_PROPERTY;
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.SBZ2_EGGMAN);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    /**
     * Returns whether the switch/button has been pressed.
     * Called by the button child to poll state.
     */
    public boolean isSwitchPressed() {
        return switchPressed;
    }

    // ========================================================================
    // Inner class: ScrapEggmanButton (routine 4 child object)
    // ========================================================================

    /**
     * The button that Eggman leaps onto in the SBZ2 cutscene.
     * <p>
     * Two phases (ob2ndRout):
     * <ul>
     *   <li>Phase 0: Polls parent for switchPressed. When pressed, changes to frame 1, advances.</li>
     *   <li>Phase 2: Displays pressed state.</li>
     * </ul>
     */
    public static class ScrapEggmanButton extends AbstractObjectInstance implements RewindRecreatable {

        private static final int BUTTON_PHASE_WAITING = 0;
        private static final int BUTTON_PHASE_PRESSED = 2;
        private Sonic1ScrapEggmanInstance parent;
        private int buttonPhase;
        private int buttonFrame; // 0 = unpressed, 1 = pressed

        private int buttonX;
        private int buttonY;

        public ScrapEggmanButton(ObjectSpawn spawn, Sonic1ScrapEggmanInstance parent) {
            super(spawn, "ScrapEggmanButton");
            this.parent = parent;
            this.buttonPhase = BUTTON_PHASE_WAITING;
            this.buttonFrame = 0;
            this.buttonX = spawn.x();
            this.buttonY = spawn.y();
        }

        private ScrapEggmanButton(ObjectSpawn spawn, boolean rewindProbe) {
            this(spawn, (Sonic1ScrapEggmanInstance) null);
        }

        @Override
        public ScrapEggmanButton recreateForRewind(RewindRecreateContext ctx) {
            ScrapEggmanButton restoredButton = new ScrapEggmanButton(ctx.spawn(), (Sonic1ScrapEggmanInstance) null);
            Sonic1ScrapEggmanInstance restoredParent = nearestLiveParentForRewind(ctx);
            if (restoredParent != null) {
                restoredParent.button = restoredButton;
            }
            return restoredButton;
        }

        @Override
        public int getX() {
            return buttonX;
        }

        @Override
        public int getY() {
            return buttonY;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (isDestroyed()) {
                return;
            }

            switch (buttonPhase) {
                case BUTTON_PHASE_WAITING -> {
                    if (parent != null && parent.isSwitchPressed()) {
                        buttonFrame = 1; // pressed
                        buttonPhase = BUTTON_PHASE_PRESSED;
                    }
                }
                case BUTTON_PHASE_PRESSED -> {
                    // Just display - nothing to update
                }
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }

            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }

            PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.SBZ2_BUTTON);
            if (renderer == null || !renderer.isReady()) {
                return;
            }

            renderer.drawFrameIndex(buttonFrame, buttonX, buttonY, false, false);
        }
    }

    private void adoptLiveButtonForRewind(RewindRecreateContext ctx) {
        ObjectManager objectManager = objectManagerForRewind(ctx);
        if (objectManager == null) {
            return;
        }
        ScrapEggmanButton best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof ScrapEggmanButton candidate && !candidate.isDestroyed()) {
                int distance = Math.abs(candidate.getX() - BUTTON_X);
                if (distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        if (best != null) {
            best.parent = this;
            this.button = best;
        }
    }

    private static Sonic1ScrapEggmanInstance nearestLiveParentForRewind(RewindRecreateContext ctx) {
        ObjectManager objectManager = objectManagerForRewind(ctx);
        if (objectManager == null) {
            return null;
        }

        Sonic1ScrapEggmanInstance best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object instanceof Sonic1ScrapEggmanInstance parent && !parent.isDestroyed()) {
                int distance = Math.abs(parent.getX() - ctx.spawn().x());
                if (distance < bestDistance) {
                    best = parent;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static ObjectManager objectManagerForRewind(RewindRecreateContext ctx) {
        ObjectManager objectManager = ctx.objectManager();
        if (objectManager == null && ctx.objectServices() != null) {
            objectManager = ctx.objectServices().objectManager();
        }
        return objectManager;
    }
}
