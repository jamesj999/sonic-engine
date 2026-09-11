package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.graphics.GLCommand;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Animal released from Egg Prison (Object 0x28 routine $1C).
 * <p>
 * ROM-accurate implementation based on s2.asm Obj28_Prison (loc_11BF4).
 * <p>
 * Prison mode behavior:
 * <ul>
 *   <li>Waits for objoff_36 delay to count down</li>
 *   <li>Then transitions to routine=2 (normal animal main state)</li>
 *   <li>Uses objoff_38=1 flag to indicate prison spawn</li>
 * </ul>
 * <p>
 * Normal animal behavior after activation:
 * <ul>
 *   <li>Falls with gravity until hitting ground</li>
 *   <li>On ground, bounces with zone-specific velocities</li>
 *   <li>Walking or flying depending on animal type</li>
 * </ul>
 */
public class EggPrisonAnimalInstance extends AbstractObjectInstance
        implements SpawnTrailingZeroIntsRewindRecreatable {
    // Physics constants from s2.asm
    private static final int GRAVITY = 0x38;        // Standard gravity
    private static final int FLY_GRAVITY = 0x18;    // Reduced gravity for flying animals
    private static final int ANIM_TIMER_INIT = 7;
    private static final int FRAMES_PER_MAPPING = 3;
    private static final int ART_VARIANT_COUNT = 2;

    // Routine states matching ROM Obj28_Index
    private enum State {
        PRISON_WAIT,   // routine=$1C - waiting for delay
        MAIN,          // routine=2 - falling after spawn
        WALK,          // routine=4,8,A,C,10,14,16,18,1A - ground movement
        FLY            // routine=6,E,12 - flying movement
    }

    private final PatternSpriteRenderer renderer;
    private int currentX;
    private int currentY;
    private int xVelocity;      // Fixed-point (8.8)
    private int yVelocity;      // Fixed-point (8.8)
    private int xSub = 0;       // 16.16 sub-pixel accumulator for ObjectMove/ObjectFall
    private int ySub = 0;
    private int groundXVelocity;
    private int groundYVelocity;
    private int animFrameTimer;
    private int animFrame;
    private int mappingSetIndex;
    private int artVariant;
    private int waitDelay;
    private State state;
    private AnimalType definition;
    private boolean romRenderOnScreen = true;

    public EggPrisonAnimalInstance(ObjectSpawn spawn, int delay, int artVariant) {
        super(spawn, "Animal");
        ObjectRenderManager renderManager = getRenderManager();
        this.renderer = renderManager != null ? renderManager.getAnimalRenderer() : null;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.animFrameTimer = ANIM_TIMER_INIT;
        this.animFrame = 2;  // Prison animals start with mapping frame 2
        this.waitDelay = delay;
        this.state = State.PRISON_WAIT;

        // Get zone-specific animal types
        int typeA = AnimalType.RABBIT.ordinal();
        int typeB = AnimalType.RABBIT.ordinal();
        if (renderManager != null) {
            typeA = renderManager.getAnimalTypeA();
            typeB = renderManager.getAnimalTypeB();
        }

        // ROM: jsr RandomNumber / andi.w #1,d0
        this.artVariant = artVariant & (ART_VARIANT_COUNT - 1);
        int animalIndex = this.artVariant == 0 ? typeA : typeB;
        this.definition = AnimalType.fromIndex(animalIndex);
        this.mappingSetIndex = definition.mappingSet().ordinal();
        this.groundXVelocity = definition.xVel();
        this.groundYVelocity = definition.yVel();
        this.xVelocity = 0;
        this.yVelocity = 0;
    }

    EggPrisonAnimalInstance(ObjectSpawn spawn) {
        this(spawn, 0, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        switch (state) {
            case PRISON_WAIT -> updatePrisonWait(vIntRunCount);
            case MAIN -> updateMain(vIntRunCount);
            case WALK -> updateWalk();
            case FLY -> updateFly();
        }
    }

    /**
     * ROM: Obj28_Prison (loc_11BF4)
     * Waits for delay, then activates.
     */
    private void updatePrisonWait(int vIntRunCount) {
        // Check if still on screen
        if (!isOnScreen(64)) {
            setDestroyed(true);
            return;
        }

        // ROM: subq.w #1,objoff_36(a0) / bne.w DisplaySprite
        waitDelay--;
        if (waitDelay > 0) {
            return;
        }

        // ROM: move.b #2,routine(a0) / move.b #1,priority(a0)
        state = State.MAIN;
        yVelocity = -0x400;  // Initial upward velocity
        animFrame = 2;
    }

    /**
     * ROM: Obj28_Main (loc_11ADE)
     * Falling after initial spawn.
     */
    private void updateMain(int vIntRunCount) {
        objectMoveAndFall();

        if (yVelocity >= 0 && checkFloorCollision()) {
            // Hit ground - start moving
            xVelocity = groundXVelocity;
            yVelocity = groundYVelocity;
            animFrame = 1;
            state = definition.flying() ? State.FLY : State.WALK;
            // Prison animals sample Vint_runcount only after floor contact,
            // while Obj28_Main switches to the walking/flying routine
            // (docs/s2disasm/s2.asm:24644-24667).
            if ((vIntRunCount & 0x10) != 0) {
                xVelocity = -xVelocity;
                groundXVelocity = -groundXVelocity;
            }
        }

        if (!isOnScreen(64)) {
            setDestroyed(true);
        }
    }

    /**
     * ROM: Obj28_Walk (loc_11B38)
     * Ground movement with bouncing.
     */
    private void updateWalk() {
        objectMoveAndFall();
        animFrame = 1;

        if (yVelocity >= 0) {
            animFrame = 0;
            if (checkFloorCollision()) {
                yVelocity = groundYVelocity;
            }
        }

        if (!isOnScreen(64)) {
            setDestroyed(true);
        }
    }

    /**
     * ROM: Obj28_Fly (loc_11B74)
     * Flying movement with reduced gravity.
     */
    private void updateFly() {
        objectMove();
        yVelocity += FLY_GRAVITY;

        if (yVelocity >= 0 && checkFloorCollision()) {
            yVelocity = groundYVelocity;
        }

        // Animate wings
        animFrameTimer--;
        if (animFrameTimer < 0) {
            animFrameTimer = 1;
            animFrame = (animFrame + 1) & 1;
        }

        if (!isOnScreen(64)) {
            setDestroyed(true);
        }
    }

    /**
     * ROM: ObjectMoveAndFall
     * Apply gravity and move.
     */
    private void objectMoveAndFall() {
        SubpixelMotion.State motion = new SubpixelMotion.State(
                currentX, currentY, xSub, ySub, xVelocity, yVelocity);
        SubpixelMotion.objectFallXY(motion, GRAVITY);
        applyMotion(motion);
    }

    /**
     * ROM: ObjectMove
     * Apply velocities to position.
     */
    private void objectMove() {
        SubpixelMotion.State motion = new SubpixelMotion.State(
                currentX, currentY, xSub, ySub, xVelocity, yVelocity);
        SubpixelMotion.speedToPos(motion);
        applyMotion(motion);
    }

    private void applyMotion(SubpixelMotion.State motion) {
        currentX = motion.x;
        currentY = motion.y;
        xSub = motion.xSub;
        ySub = motion.ySub;
        yVelocity = motion.yVel;
    }

    /**
     * ROM: ObjCheckFloorDist
     * Check for floor collision and snap to it.
     */
    private boolean checkFloorCollision() {
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(currentX, currentY, 12);
        // S2 Obj28_Main/Walk/Fly accepts only a negative ObjCheckFloorDist:
        // tst.w d1 / bpl.s DisplaySprite, so a zero-distance probe must not
        // advance the prison animal into its walk/fly routine early.
        if (result.distance() < 0) {
            currentY = currentY + result.distance();
            return true;
        }
        return false;
    }

    /**
     * Calculate the sprite frame index based on animal type and variant.
     */
    private int getFrameIndex() {
        int base = ((mappingSetIndex * ART_VARIANT_COUNT) + artVariant) * FRAMES_PER_MAPPING;
        return base + animFrame;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        // Don't render during wait state (invisible in prison)
        if (state == State.PRISON_WAIT) {
            return;
        }
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        boolean hFlip = xVelocity < 0;
        renderer.drawFrameIndex(getFrameIndex(), currentX, currentY, hFlip, false);
    }

    @Override
    protected boolean isOnScreen(int margin) {
        return romRenderOnScreen;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return 8;
    }

    @Override
    public void refreshPostCameraRenderState() {
        Camera camera = services().camera();
        if (camera == null) {
            romRenderOnScreen = true;
            return;
        }
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int screenWidth = viewportWidth();
        int screenHeight = viewportHeight();
        // S2 Obj28_Prison/Main/Walk/Fly delete through the cached
        // render_flags.on_screen bit, not a fresh wide margin
        // (docs/s2disasm/s2.asm:24732-24734,24644-24646,24683-24686,24713-24716).
        romRenderOnScreen = currentX >= cameraX - getOnScreenHalfWidth()
                && currentX < cameraX + screenWidth + getOnScreenHalfWidth()
                && currentY >= cameraY - 32
                && currentY < cameraY + screenHeight + 32;
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }
}
