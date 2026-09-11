package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.EggPrisonAnimalInstance;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * Shared route-8 floating egg prison used by S3K end sequences.
 *
 * <p>ROM reference: Obj_EggCapsule routine 8 (camera-relative descent/hover).
 * The capsule floats upside-down, pans with the camera, opens from an
 * underside button hit, then runs the explosion, animal, and results flow.
 */
public abstract class AbstractS3kFloatingEndEggCapsuleInstance extends AbstractObjectInstance
        implements MultiPieceSolidProvider {
    private static final int OBJECT_ID = 0x81;
    protected static final int X_OFFSET = 0xA0;
    protected static final int Y_START_OFFSET = -0x40;
    private static final int Y_TARGET_OFFSET = 0x40;
    private static final int LEFT_BOUND_OFFSET = 0x30;
    private static final int RIGHT_BOUND_OFFSET = 0x110;
    private static final int PRIORITY = 5;

    private static final int SOLID_HALF_WIDTH = 0x2B;
    private static final int SOLID_HALF_HEIGHT = 0x18;
    private static final int BUTTON_SOLID_HALF_WIDTH = 0x1B;
    private static final int BUTTON_SOLID_HALF_HEIGHT_AIR = 0x5;
    private static final int BUTTON_SOLID_HALF_HEIGHT_GROUND = 0x9;
    private static final int BUTTON_RECESS = 8;
    private static final int PIECE_BUTTON = 1;
    private static final int BUTTON_Y_OFFSET = 0x24;
    private static final int TRIGGER_X_LEFT = -0x1A;
    private static final int TRIGGER_X_RIGHT = -0x1A + 0x34;
    private static final int TRIGGER_Y_TOP = -0x1C;
    private static final int TRIGGER_Y_BOTTOM = -0x1C + 0x38;
    private static final int POST_OPEN_DELAY = 0x40;
    private static final int ANIMAL_COUNT = 9;
    private static final int SWING_MAX_SPEED = 0xC0;
    private static final int SWING_ACCELERATION = 0x10;

    private int currentX;
    private int solidBodyX;
    private int currentY;
    private int ySubpixel;
    private int yVelocity = SWING_MAX_SPEED;
    private boolean swingDescending;
    private int xDirection = 1;
    private int mappingFrame;
    private boolean opened;
    private boolean buttonTriggered;
    private boolean resultsStarted;
    private boolean releaseTriggered;
    private int postOpenTimer;
    private int buttonRecess;
    private int buttonTriggerSource;
    private int buttonTriggerVIntRunCount = -1;
    private int openFrame = -1;
    private boolean laterSupportOwnerEligibilityDeferred;
    private boolean parentMotionEligibilityDeferred;
    private boolean routeInitPending;
    private S3kBossExplosionController explosionController;

    protected AbstractS3kFloatingEndEggCapsuleInstance(int initialX, int initialY, String debugName) {
        this(initialX, initialY, debugName, false);
    }

    protected AbstractS3kFloatingEndEggCapsuleInstance(int initialX, int initialY, String debugName,
            boolean routeInitPending) {
        super(new ObjectSpawn(initialX, initialY, OBJECT_ID, 0, 0, false, 0), debugName);
        this.currentX = initialX;
        this.solidBodyX = initialX;
        this.currentY = initialY;
        this.routeInitPending = routeInitPending;
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
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("cap=%s/%04X t=%s o=%s r=%s mf=%02X btn=%02X src=%s tf=%04X of=%04X",
                opened ? (resultsStarted ? "results" : "open") : "float",
                postOpenTimer & 0xFFFF,
                buttonTriggered ? "1" : "0",
                opened ? "1" : "0",
                resultsStarted ? "1" : "0",
                mappingFrame & 0xFF,
                buttonRecess & 0xFF,
                switch (buttonTriggerSource) {
                    case 1 -> "p1";
                    case 2 -> "p2";
                    default -> "--";
                },
                buttonTriggerVIntRunCount & 0xFFFF,
                openFrame & 0xFFFF);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public int getPieceCount() {
        return 2;
    }

    @Override
    public int getPieceX(int pieceIndex) {
        return pieceIndex == PIECE_BUTTON ? currentX : solidBodyX;
    }

    @Override
    public int getPieceY(int pieceIndex) {
        if (pieceIndex == PIECE_BUTTON) {
            return currentY + BUTTON_Y_OFFSET - buttonRecess;
        }
        return currentY;
    }

    @Override
    public SolidObjectParams getPieceParams(int pieceIndex) {
        if (pieceIndex == PIECE_BUTTON) {
            return SolidObjectParams.of(BUTTON_SOLID_HALF_WIDTH,
                    BUTTON_SOLID_HALF_HEIGHT_AIR, BUTTON_SOLID_HALF_HEIGHT_GROUND);
        }
        return getSolidParams();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // Obj_EggCapsule saves x_pos in d4 before dispatching its routine and
        // passes that saved coordinate to SolidObjectFull after movement. The
        // separate button child refreshes from the parent's current position.
        // Preserve both anchors when collapsing those native slots into one
        // engine object (sonic3k.asm:181501-181545,181739-181767).
        solidBodyX = currentX;
        onBeforeCapsuleUpdate();
        if (!opened) {
            if (routeInitPending) {
                // ROM Obj_EggCapsule first executes loc_8657A for route-8
                // capsule setup, creates the button/sprite children, then
                // returns through SolidObjectFull/Draw_Sprite. loc_8662A
                // motion and loc_86770 button checks begin on later object
                // routine entries (sonic3k.asm:181496-181545,181588-181647).
                routeInitPending = false;
                initializeRoute8FromCamera();
                checkpointAll();
                return;
            }
            updateRoute8BeforeTrigger();
            if (buttonTriggered) {
                openCapsule();
            }
            updateSwingAndMove();
            if (!opened) {
                // ROM loc_86770 is a separate button child: Refresh_ChildPosition,
                // sub_86A54, then Check_PlayerInRange/y_vel. Run the collapsed
                // child solid checkpoint before testing the trigger bit
                // (sonic3k.asm:181739-181767,182049-182054).
                checkpointAll();
                scanButtonTrigger(vIntRunCount, playerEntity);
            } else {
                checkpointAll();
            }
        } else if (!resultsStarted) {
            if (explosionController != null && !explosionController.isFinished()) {
                explosionController.tick();
                spawnPendingExplosions();
            }

            // Both sub_868F8 and sub_86984 pre-decrement the $40 word and
            // branch while the signed result remains non-negative.
            postOpenTimer--;
            if (postOpenTimer < 0
                    && playerEntity instanceof AbstractPlayableSprite player
                    && shouldStartResults(player)) {
                startResults(player);
            }
            updateSwingAndMove();
            checkpointAll();
        } else if (resultsStarted && !releaseTriggered) {
            onResultsActiveWait();
            if (services().gameState().isEndOfLevelFlag()) {
                releaseTriggered = true;
                onEndingPoseLockClear();
                onResultsComplete();
            }
            updateSwingAndMove();
            checkpointAll();
        } else {
            updateSwingAndMove();
            checkpointAll();
        }
    }

    protected void onBeforeCapsuleUpdate() {
        // Route-specific pre-dispatch state.
    }

    private void initializeRoute8FromCamera() {
        int cameraX = services().camera().getX();
        int cameraY = services().camera().getY();

        currentX = (cameraX + X_OFFSET) & 0xFFFF;
        currentY = (cameraY + Y_START_OFFSET) & 0xFFFF;
        ySubpixel = 0;
        xDirection = 1;
        yVelocity = SWING_MAX_SPEED;
        swingDescending = false;
    }

    private void updateRoute8BeforeTrigger() {
        int cameraX = services().camera().getX();
        int cameraY = services().camera().getY();

        // ROM loc_8662A compares the current x_pos against the current
        // camera-relative edge, possibly negates $3A, then adds $3A. It does
        // not clamp overshoot (sonic3k.asm:181604-181625).
        if (xDirection >= 0) {
            int rightBound = (cameraX + RIGHT_BOUND_OFFSET) & 0xFFFF;
            if (Integer.compareUnsigned(rightBound, currentX & 0xFFFF) < 0) {
                xDirection = -xDirection;
            }
        } else {
            int leftBound = (cameraX + LEFT_BOUND_OFFSET) & 0xFFFF;
            if (Integer.compareUnsigned(leftBound, currentX & 0xFFFF) >= 0) {
                xDirection = -xDirection;
            }
        }
        currentX = (currentX + xDirection) & 0xFFFF;

        int targetY = (cameraY + targetYOffset()) & 0xFFFF;
        int yStep = Integer.compareUnsigned(targetY, currentY & 0xFFFF) > 0 ? 0x4000 : -0x4000;
        addYLongword(yStep);
    }

    protected int targetYOffset() {
        return Y_TARGET_OFFSET;
    }

    private void updateSwingAndMove() {
        updateSwingVelocity();
        addYLongword(yVelocity << 8);
    }

    private void updateSwingVelocity() {
        int velocity;
        if (!swingDescending) {
            velocity = yVelocity - SWING_ACCELERATION;
            if (velocity <= -SWING_MAX_SPEED) {
                swingDescending = true;
                velocity += SWING_ACCELERATION;
            }
        } else {
            velocity = yVelocity + SWING_ACCELERATION;
            if (velocity >= SWING_MAX_SPEED) {
                swingDescending = false;
                velocity -= SWING_ACCELERATION;
            }
        }
        yVelocity = velocity;
    }

    private void addYLongword(int delta) {
        int full = ((currentY & 0xFFFF) << 16) | (ySubpixel & 0xFFFF);
        full += delta;
        currentY = (full >>> 16) & 0xFFFF;
        ySubpixel = full & 0xFFFF;
    }

    private void scanButtonTrigger(int vIntRunCount, PlayableEntity playerEntity) {
        ObjectPlayerQuery query = playerQuery(playerEntity);
        PlayableEntity nativeP1 = query.mainPlayerOrNull();
        boolean eligibleCandidateFound = false;
        for (PlayableEntity candidate : query.playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (candidate instanceof AbstractPlayableSprite player
                    && shouldTriggerButton(player, candidate == nativeP1)) {
                eligibleCandidateFound = true;
                int supportSlot = player.getInteractSlotIndex();
                if (defersCollapsedButtonPastLaterSupportOwner()
                        && !laterSupportOwnerEligibilityDeferred
                        && supportSlot > getSlotIndex()) {
                    // The ROM button is a later child SST. A support owner
                    // after this folded parent has not published its current
                    // contact state when the parent-equivalent entry runs.
                    laterSupportOwnerEligibilityDeferred = true;
                    continue;
                }
                if (defersButtonEligibilityCreatedByParentMotion()
                        && !parentMotionEligibilityDeferred
                        && !shouldTriggerButtonAtX(player, candidate == nativeP1, solidBodyX)) {
                    // Parent movement and the later button child occupy
                    // separate native dispatches. Do not let movement folded
                    // into this entry create eligibility until the next
                    // child-equivalent entry.
                    parentMotionEligibilityDeferred = true;
                    continue;
                }
                // ROM loc_86770 only switches the button child to loc_867CA
                // and sets parent $38 bit 1. The parent Obj_EggCapsule
                // routine sees that bit on its next object slot and then
                // runs sub_865DE (sonic3k.asm:181739-181767,181556-181570).
                buttonTriggered = true;
                buttonRecess = BUTTON_RECESS;
                buttonTriggerSource = candidate == nativeP1 ? 1 : 2;
                buttonTriggerVIntRunCount = vIntRunCount;
                break;
            }
        }
        if (!eligibleCandidateFound) {
            laterSupportOwnerEligibilityDeferred = false;
            parentMotionEligibilityDeferred = false;
        }
    }

    protected boolean defersCollapsedButtonPastLaterSupportOwner() {
        return false;
    }

    protected boolean defersButtonEligibilityCreatedByParentMotion() {
        return false;
    }

    private boolean shouldTriggerButton(AbstractPlayableSprite player, boolean nativeP1) {
        return shouldTriggerButtonAtX(player, nativeP1, currentX);
    }

    private boolean shouldTriggerButtonAtX(AbstractPlayableSprite player, boolean nativeP1,
            int buttonX) {
        // ROM loc_86770 refreshes the button child from parent x/y, runs
        // sub_86A54, then calls Check_PlayerInRange before the parent routine's
        // Swing_UpAndDown render motion (sonic3k.asm:181739-181767,181604-181647).
        int buttonY = currentY + BUTTON_Y_OFFSET;
        int dx = player.getCentreX() - buttonX;
        int dy = player.getCentreY() - buttonY;
        return player.getYSpeed() < 0
                && isAllowedButtonTriggerCharacterState(player, nativeP1)
                && dx >= TRIGGER_X_LEFT
                && dx < TRIGGER_X_RIGHT
                && dy >= TRIGGER_Y_TOP
                && dy < TRIGGER_Y_BOTTOM;
    }

    private boolean isAllowedButtonTriggerCharacterState(AbstractPlayableSprite player, boolean nativeP1) {
        if (!nativeP1) {
            // ROM loc_86770 checks Player_2 only after Player_1 is absent or
            // rejected, then branches to the trigger immediately after the
            // upward-y-velocity test (sonic3k.asm:181777-181800).
            return true;
        }
        if (isTailsCharacter(player)) {
            return true;
        }
        // ROM loc_86770 accepts native Player_1 Sonic/Knuckles only when
        // anim(a1) is #2 before setting the parent trigger bit
        // (sonic3k.asm:181777-181800).
        return player.getAnimationId() == Sonic3kAnimationIds.ROLL.id();
    }

    private boolean isTailsCharacter(AbstractPlayableSprite player) {
        return player.getCode().toLowerCase(java.util.Locale.ROOT).contains("tails");
    }

    private void openCapsule() {
        if (opened) {
            return;
        }
        opened = true;
        openFrame = buttonTriggerVIntRunCount + 1;
        mappingFrame = 1;
        buttonRecess = BUTTON_RECESS;
        // ROM sub_865DE stores $2E=$40. Both results routines pre-decrement
        // and wait for signed underflow (sonic3k.asm:181556-181570,
        // 181900-181918,182027-182046).
        postOpenTimer = POST_OPEN_DELAY;

        try {
            services().playSfx(Sonic3kSfx.EXPLODE.id);
        } catch (Exception e) {
            // Ignore audio errors.
        }

        explosionController = new S3kBossExplosionController(currentX, currentY, 3, services().rng());
        spawnAnimals();
        onParentOpen();
    }

    protected void onParentOpen() {
        // Zone-specific sub_865DE side effects.
    }

    private void spawnPendingExplosions() {
        if (explosionController == null) {
            return;
        }
        var pending = explosionController.drainPendingExplosions();
        for (var entry : pending) {
            if (entry.playSfx()) {
                try {
                    services().playSfx(Sonic3kSfx.EXPLODE.id);
                } catch (Exception e) {
                    // Ignore audio errors.
                }
            }
            spawnChild(() -> new S3kBossExplosionChild(entry.x(), entry.y()));
        }
    }

    private void spawnAnimals() {
        for (int i = 0; i < getAnimalCount(); i++) {
            int animalX = currentX + (i % 2 == 0 ? -(8 + i * 4) : (8 + i * 4));
            int animalY = currentY - 8;
            int delay = i * 4;
            ObjectSpawn spawn = new ObjectSpawn(animalX, animalY, 0x28, 0, 0, false, 0);
            int artVariant = services().rng().nextBits(1);
            int index = i;
            spawnChild(() -> createCapsuleAnimal(spawn, delay, artVariant, index));
        }
    }

    protected int getAnimalCount() {
        return ANIMAL_COUNT;
    }

    protected AbstractObjectInstance createCapsuleAnimal(ObjectSpawn spawn, int delay, int artVariant, int index) {
        return new EggPrisonAnimalInstance(spawn, delay, artVariant);
    }

    protected boolean shouldStartResults(AbstractPlayableSprite player) {
        return !player.getAir()
                && player.getXSpeed() == 0
                && player.getYSpeed() == 0
                && player.getGSpeed() == 0;
    }

    protected void startResults(AbstractPlayableSprite player) {
        if (resultsStarted) {
            return;
        }
        resultsStarted = true;
        services().gameState().setEndOfLevelFlag(false);
        services().gameState().setEndOfLevelActive(true);
        if (shouldLockPlayersForResults()) {
            for (PlayableEntity candidate : playerQuery(player)
                    .playersFor(resultsLockParticipationPolicy())) {
                if (candidate instanceof AbstractPlayableSprite sprite) {
                    lockForResults(sprite);
                }
            }
        }
        spawnResultsScreen();
    }

    protected AbstractObjectInstance createResultsScreen() {
        return new S3kResultsScreenObjectInstance(getPlayerCharacter(), services().currentAct());
    }

    protected void spawnResultsScreen() {
        spawnChild(this::createResultsScreen);
    }

    protected boolean shouldLockPlayersForResults() {
        return true;
    }

    protected ObjectPlayerParticipationPolicy resultsLockParticipationPolicy() {
        return ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS;
    }

    protected void lockForResults(AbstractPlayableSprite sprite) {
        ObjectControlState.nativeBit7FullControl().applyTo(sprite);
        sprite.setControlLocked(true);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAnimationId(Sonic3kAnimationIds.VICTORY);
        sprite.setForcedAnimationId(Sonic3kAnimationIds.VICTORY);
    }

    protected PlayerCharacter getPlayerCharacter() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration());
    }

    protected void onResultsComplete() {
        // Zone-specific post-results handoff.
    }

    protected void onResultsActiveWait() {
        // Zone-specific Obj_EggCapsule routine $0C side effects.
    }

    protected void onEndingPoseLockClear() {
        // Zone-specific Check_TailsEndPose side effects.
    }

    private ObjectPlayerQuery playerQuery(PlayableEntity updatePlayer) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> updatePlayer, query::sidekicks);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.EGG_CAPSULE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, true);

        int buttonFrame = buttonTriggered ? 0xC : 0x5;
        int buttonY = currentY + BUTTON_Y_OFFSET - buttonRecess;
        renderer.drawFrameIndex(buttonFrame, currentX, buttonY, false, true);
    }
}
