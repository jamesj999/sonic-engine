package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.AnimalType;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.physics.ObjectTerrainUtils;
import uk.co.jamesj999.sonic.physics.TerrainCheckResult;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
public class EggPrisonAnimalInstance extends AbstractObjectInstance {
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
    private int xSub = 0;       // Sub-pixel accumulator
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

    public EggPrisonAnimalInstance(ObjectSpawn spawn, int delay) {
        super(spawn, "Animal");
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
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
        this.artVariant = ThreadLocalRandom.current().nextInt(ART_VARIANT_COUNT);
        int animalIndex = artVariant == 0 ? typeA : typeB;
        this.definition = AnimalType.fromIndex(animalIndex);
        this.mappingSetIndex = definition.mappingSet().ordinal();
        this.groundXVelocity = definition.xVel();
        this.groundYVelocity = definition.yVel();
        this.xVelocity = 0;
        this.yVelocity = 0;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        switch (state) {
            case PRISON_WAIT -> updatePrisonWait(frameCounter);
            case MAIN -> updateMain();
            case WALK -> updateWalk();
            case FLY -> updateFly();
        }
    }

    /**
     * ROM: Obj28_Prison (loc_11BF4)
     * Waits for delay, then activates.
     */
    private void updatePrisonWait(int frameCounter) {
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

        // ROM: btst #4,(Vint_runcount+3).w for random direction
        if ((frameCounter & 0x10) != 0) {
            groundXVelocity = -groundXVelocity;
        }
    }

    /**
     * ROM: Obj28_Main (loc_11ADE)
     * Falling after initial spawn.
     */
    private void updateMain() {
        objectMoveAndFall();

        if (yVelocity >= 0 && checkFloorCollision()) {
            // Hit ground - start moving
            xVelocity = groundXVelocity;
            yVelocity = groundYVelocity;
            animFrame = 1;
            state = definition.flying() ? State.FLY : State.WALK;
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
        yVelocity += GRAVITY;
        objectMove();
    }

    /**
     * ROM: ObjectMove
     * Apply velocities to position.
     */
    private void objectMove() {
        xSub += xVelocity;
        ySub += yVelocity;

        currentX += (xSub >> 8);
        currentY += (ySub >> 8);
        xSub &= 0xFF;
        ySub &= 0xFF;
    }

    /**
     * ROM: ObjCheckFloorDist
     * Check for floor collision and snap to it.
     */
    private boolean checkFloorCollision() {
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(currentX, currentY, 12);
        if (result.hasCollision()) {
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
        Camera camera = Camera.getInstance();
        if (camera == null) {
            return true;
        }
        int cameraX = camera.getX();
        int screenWidth = 320;
        return currentX >= cameraX - margin && currentX <= cameraX + screenWidth + margin;
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
