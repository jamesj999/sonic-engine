package com.openggf.level.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameModule;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.ObjectInteractionRules;
import com.openggf.graphics.GLCommand;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.game.PlayableEntity;

import java.util.List;

public class AnimalObjectInstance extends AbstractObjectInstance
        implements SpawnServicesDefaultArgsRewindRecreatable {
    private static final int GRAVITY = 0x38;
    private static final int FLY_GRAVITY = 0x18;
    private static final int INITIAL_POP_VEL = -0x400;
    private static final int ANIM_TIMER_INIT = 7;
    private static final int FRAMES_PER_MAPPING = 3;
    private static final int ART_VARIANT_COUNT = 2;
    private static final int S2_RENDER_HALF_WIDTH = 8;
    private static final int APPROX_RENDER_HALF_HEIGHT = 32;

    private enum State {
        MAIN,
        WALK,
        FLY
    }

    private final PatternSpriteRenderer renderer;
    private int currentX;
    private int currentY;
    private int xSubpixel;
    private int ySubpixel;
    private int xVelocity;
    private int yVelocity;
    private int groundXVelocity;
    private int groundYVelocity;
    private int animFrameTimer;
    private int animFrame;
    private int mappingSetIndex;
    private int artVariant;
    private int pointsValue;
    private State state;
    private AnimalType definition;
    private boolean firstDisplayFrame;
    private boolean spawnedPoints;
    private boolean deferArtVariantRng;
    private boolean romRenderOnScreen;
    private transient DestructionEffects.PointsFactory pointsFactory;

    /**
     * Probe/rewind-compatible constructor with the same deferred ownership as
     * {@link #deferredArtVariant(ObjectSpawn, ObjectServices, DestructionEffects.PointsFactory)}.
     */
    public AnimalObjectInstance(ObjectSpawn spawn, ObjectServices services) {
        this(spawn, services, 0, null, true);
    }

    /**
     * Creates a subtype-0 animal without consuming RNG until its own first object dispatch.
     * Both S2 {@code Obj28_InitRandom} and S3K {@code loc_2C924} run after the
     * explosion has allocated the animal SST.
     */
    public static AnimalObjectInstance deferredArtVariant(
            ObjectSpawn spawn, ObjectServices services,
            DestructionEffects.PointsFactory pointsFactory) {
        return new AnimalObjectInstance(spawn, services, 0, pointsFactory, true);
    }

    /**
     * Rewind recreate path uses {@link SpawnServicesDefaultArgsRewindRecreatable}
     * to skip the {@code services.rng()} draw. The captured {@code artVariant}
     * and all other scalars are reapplied by the generic scalar pass.
     */
    private AnimalObjectInstance(ObjectSpawn spawn, ObjectServices services, int artVariant,
            DestructionEffects.PointsFactory pointsFactory) {
        this(spawn, services, artVariant, pointsFactory, false);
    }

    private AnimalObjectInstance(ObjectSpawn spawn, ObjectServices services, int artVariant,
            DestructionEffects.PointsFactory pointsFactory, boolean deferArtVariantRng) {
        super(spawn, "Animal");
        ObjectRenderManager renderManager = services.renderManager();
        this.renderer = renderManager != null ? renderManager.getAnimalRenderer() : null;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.animFrameTimer = ANIM_TIMER_INIT;
        this.animFrame = 2;
        this.state = State.MAIN;
        this.firstDisplayFrame = true;
        this.romRenderOnScreen = true;

        this.artVariant = artVariant;
        this.pointsValue = spawn.rawYWord();
        this.pointsFactory = pointsFactory;
        this.deferArtVariantRng = deferArtVariantRng;
        applyAnimalDefinition(renderManager);
        this.xVelocity = 0;
        this.yVelocity = INITIAL_POP_VEL;
    }

    private void applyAnimalDefinition(ObjectRenderManager renderManager) {
        int typeA = AnimalType.RABBIT.ordinal();
        int typeB = AnimalType.RABBIT.ordinal();
        if (renderManager != null) {
            typeA = renderManager.getAnimalTypeA();
            typeB = renderManager.getAnimalTypeB();
        }
        int animalIndex = artVariant == 0 ? typeA : typeB;
        this.definition = AnimalType.fromIndex(animalIndex);
        this.mappingSetIndex = definition.mappingSet().ordinal();
        this.groundXVelocity = definition.xVel();
        this.groundYVelocity = definition.yVel();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        initializeDeferredArtVariant();
        if (firstDisplayFrame) {
            firstDisplayFrame = false;
            spawnPointsOnce();
            return;
        }
        switch (state) {
            case MAIN -> updateMain();
            case WALK -> updateWalk();
            case FLY -> updateFly();
        }
    }

    private void initializeDeferredArtVariant() {
        if (!deferArtVariantRng) {
            return;
        }
        deferArtVariantRng = false;
        ObjectServices svc = services();
        this.artVariant = svc.rng().nextBits(1);
        applyAnimalDefinition(svc.renderManager());
    }

    private void spawnPointsOnce() {
        if (spawnedPoints || pointsFactory == null || pointsValue <= 0) {
            return;
        }
        spawnedPoints = true;
        ObjectServices svc = tryServices();
        ObjectManager objectManager = svc != null ? svc.objectManager() : null;
        if (objectManager == null) {
            return;
        }
        // S2 Obj28_InitRandom allocates Obj29 from the animal's own first
        // routine pass, after Obj27 copied objoff_3E into Obj28
        // (docs/s2disasm/s2.asm:46711-46715,24596-24636).
        objectManager.createDynamicObject(() -> pointsFactory.create(
                new ObjectSpawn(currentX, currentY, 0x29, 0, 0, false, 0), svc, pointsValue));
    }

    private void updateMain() {
        objectMoveAndFall();
        if (yVelocity >= 0 && checkFloorCollision()) {
            xVelocity = groundXVelocity;
            yVelocity = groundYVelocity;
            animFrame = 1;
            state = definition.flying() ? State.FLY : State.WALK;
        }
        if (!onScreen(64)) {
            setDestroyed(true);
        }
    }

    private void updateWalk() {
        objectMoveAndFall();
        animFrame = 1;
        if (yVelocity >= 0) {
            animFrame = 0;
            if (checkFloorCollision()) {
                yVelocity = groundYVelocity;
            }
        }
        if (!onScreen(64)) {
            setDestroyed(true);
        }
    }

    private void updateFly() {
        objectMove();
        yVelocity += FLY_GRAVITY;
        if (yVelocity >= 0 && checkFloorCollision()) {
            yVelocity = groundYVelocity;
        }

        animFrameTimer--;
        if (animFrameTimer < 0) {
            animFrameTimer = 1;
            animFrame = (animFrame + 1) & 1;
        }

        if (!onScreen(64)) {
            setDestroyed(true);
        }
    }

    private void objectMoveAndFall() {
        if (preservesObjectMoveXSubpixel()) {
            SubpixelMotion.State motion = new SubpixelMotion.State(
                    currentX, currentY, xSubpixel, ySubpixel, xVelocity, yVelocity);
            SubpixelMotion.objectFallXY(motion, GRAVITY);
            applyMotion(motion);
            return;
        }

        currentX += (xVelocity >> 8);
        SubpixelMotion.State motion = new SubpixelMotion.State(
                currentX, currentY, 0, ySubpixel, 0, yVelocity);
        SubpixelMotion.objectFall(motion, GRAVITY);
        applyVerticalMotion(motion);
    }

    private void objectMove() {
        if (preservesObjectMoveXSubpixel()) {
            SubpixelMotion.State motion = new SubpixelMotion.State(
                    currentX, currentY, xSubpixel, ySubpixel, xVelocity, yVelocity);
            SubpixelMotion.speedToPos(motion);
            applyMotion(motion);
            return;
        }

        currentX += (xVelocity >> 8);
        SubpixelMotion.State motion = new SubpixelMotion.State(
                currentX, currentY, 0, ySubpixel, 0, yVelocity);
        SubpixelMotion.speedToPosY(motion);
        applyVerticalMotion(motion);
    }

    private void applyMotion(SubpixelMotion.State motion) {
        currentX = motion.x;
        xSubpixel = motion.xSub;
        applyVerticalMotion(motion);
    }

    private void applyVerticalMotion(SubpixelMotion.State motion) {
        currentY = motion.y;
        ySubpixel = motion.ySub;
        yVelocity = motion.yVel;
    }

    private boolean preservesObjectMoveXSubpixel() {
        ObjectInteractionRules rules = animalObjectInteractionRules();
        return rules != null && rules.animalObjectPreservesObjectMoveXSubpixel();
    }

    private boolean usesRenderFlagDeleteBounds() {
        ObjectInteractionRules rules = animalObjectInteractionRules();
        return rules != null && rules.animalObjectUsesRenderFlagDeleteBounds();
    }

    private ObjectInteractionRules animalObjectInteractionRules() {
        ObjectServices ctx = tryServices();
        GameModule module = ctx != null ? ctx.gameModule() : null;
        GameRules rules = module != null ? module.getRules() : null;
        return rules != null ? rules.objectInteraction() : null;
    }

    private boolean checkFloorCollision() {
        // Use centralized terrain API (mirrors ROM's ObjCheckFloorDist)
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(currentX, currentY, 12);
        // S2 Obj28_Main/Walk/Fly and S3K Obj_Animal all accept only d1 < 0:
        // ObjCheckFloorDist; tst.w d1; bpl.s DisplaySprite/no-floor branch.
        if (result.distance() < 0) {
            currentY = currentY + result.distance();
            return true;
        }
        return false;
    }

    private int getFrameIndex() {
        int base = ((mappingSetIndex * ART_VARIANT_COUNT) + artVariant) * FRAMES_PER_MAPPING;
        return base + animFrame;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        boolean hFlip = xVelocity < 0;
        renderer.drawFrameIndex(getFrameIndex(), currentX, currentY, hFlip, false);
    }

    private boolean onScreen(int margin) {
        Camera camera = services().camera();
        if (usesRenderFlagDeleteBounds()) {
            // S2 Obj28 tests render_flags.on_screen during its routine; that bit
            // is the prior BuildSprites result, not a fresh post-move bounds test.
            return romRenderOnScreen;
        }

        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int screenWidth = viewportWidth();
        int screenHeight = viewportHeight();
        if (currentX < cameraX - margin || currentX > cameraX + screenWidth + margin)
            return false;
        if (currentY < cameraY - margin || currentY > cameraY + screenHeight + margin)
            return false;
        return true;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return S2_RENDER_HALF_WIDTH;
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (!usesRenderFlagDeleteBounds()) {
            return;
        }
        // BuildSprites updates render_flags.on_screen after Obj28 has displayed.
        Camera camera = services().camera();
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        romRenderOnScreen = currentX >= cameraX - getOnScreenHalfWidth()
                && currentX < cameraX + viewportWidth() + getOnScreenHalfWidth()
                && currentY >= cameraY - APPROX_RENDER_HALF_HEIGHT
                && currentY < cameraY + viewportHeight() + APPROX_RENDER_HALF_HEIGHT;
    }

    public int getX() {
        return currentX;
    }

    public int getY() {
        return currentY;
    }

    public void setX(int x) {
        this.currentX = x;
    }

    public void setY(int y) {
        this.currentY = y;
    }
}
