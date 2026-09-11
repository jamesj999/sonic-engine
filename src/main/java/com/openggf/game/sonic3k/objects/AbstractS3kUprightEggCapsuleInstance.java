package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.EggPrisonAnimalInstance;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared S3K upright {@code Obj_EggCapsule} behavior.
 *
 * <p>ROM contract: body runs {@code SolidObjectFull} with
 * {@code d1=$2B,d2=$18,d3=$18}; the top button is a child at
 * {@code child_dy=-$24} using {@code d1=$1B,d2=4,d3=6}. Standing contact with
 * that child sets the parent trigger bit consumed by the capsule-open routine.
 */
public abstract class AbstractS3kUprightEggCapsuleInstance extends AbstractObjectInstance
        implements MultiPieceSolidProvider {
    protected static final int PIECE_BODY = 0;
    protected static final int PIECE_BUTTON = 1;

    private static final int BODY_HALF_WIDTH = 0x2B;
    private static final int BODY_HALF_HEIGHT = 0x18;
    private static final int BUTTON_Y_OFFSET = -0x24;
    private static final int BUTTON_HALF_WIDTH = 0x1B;
    private static final int BUTTON_AIR_HALF_HEIGHT = 4;
    private static final int BUTTON_GROUND_HALF_HEIGHT = 6;
    private static final int POST_OPEN_DELAY = 0x40;

    private int centreX;
    private int centreY;
    private boolean buttonTriggered;
    private boolean opened;
    private boolean resultsStarted;
    private int postOpenTimer;
    protected S3kBossExplosionController explosionController;

    protected AbstractS3kUprightEggCapsuleInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.centreX = spawn.x();
        this.centreY = spawn.y();
    }

    protected AbstractS3kUprightEggCapsuleInstance(int x, int y, String name) {
        this(new ObjectSpawn(x, y, Sonic3kObjectIds.EGG_CAPSULE, 0, 0, false, 0), name);
    }

    @Override
    public int getX() {
        return centreX;
    }

    @Override
    public int getY() {
        return centreY;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        boolean buttonWasTriggered = buttonTriggered;
        checkpointAll();
        if (!opened) {
            if (buttonWasTriggered) {
                openCapsule();
            }
            return;
        }

        tickExplosionController();
        if (resultsStarted) {
            updateAfterResultsStarted(vIntRunCount, player);
            return;
        }
        postOpenTimer--;
        if (postOpenTimer < 0
                && player instanceof AbstractPlayableSprite sprite && !sprite.getAir()) {
            startResults(sprite);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return getPieceParams(PIECE_BODY);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public boolean skipsCpuSidekickWhenRenderFlagOffScreen() {
        // Obj_EggCapsule calls SolidObjectFull, whose wrapper tests Player_2's
        // render_flags sign before dispatching SolidObjectFull_1P.
        return true;
    }

    @Override
    public int getPieceCount() {
        return 2;
    }

    @Override
    public int getPieceX(int pieceIndex) {
        return centreX;
    }

    @Override
    public int getPieceY(int pieceIndex) {
        return pieceIndex == PIECE_BUTTON
                ? centreY + BUTTON_Y_OFFSET
                : centreY;
    }

    @Override
    public SolidObjectParams getPieceParams(int pieceIndex) {
        if (pieceIndex == PIECE_BUTTON) {
            return SolidObjectParams.of(BUTTON_HALF_WIDTH, BUTTON_AIR_HALF_HEIGHT, BUTTON_GROUND_HALF_HEIGHT);
        }
        return SolidObjectParams.of(BODY_HALF_WIDTH, BODY_HALF_HEIGHT, BODY_HALF_HEIGHT);
    }

    @Override
    public void onPieceContact(int pieceIndex, PlayableEntity player, SolidContact contact, int frameCounter) {
        if (pieceIndex == PIECE_BUTTON && !opened && contact != null && contact.standing()) {
            triggerButton();
        }
    }

    protected final boolean isOpened() {
        return opened;
    }

    protected final boolean isResultsStarted() {
        return resultsStarted;
    }

    protected int animalCount() {
        return 9;
    }

    protected int animalYOffset() {
        return -8;
    }

    /** ROM {@code sub_865DE}: every upright route except MGZ sets signed {@code Ctrl_2_locked}. */
    protected boolean locksNativeP2CpuOnOpen() {
        return true;
    }

    protected void updateAfterResultsStarted(int frameCounter, PlayableEntity player) {
    }

    /**
     * Whether a folded route's native {@code Obj_LevelResults} slot is known to
     * execute later than the capsule during the allocation pass.
     */
    protected boolean nativeResultsRunsInAllocationPass() {
        return false;
    }

    protected PlayerCharacter resolvePlayerCharacter() {
        if (services().configuration() == null) {
            return PlayerCharacter.SONIC_ALONE;
        }
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration());
    }

    private void triggerButton() {
        buttonTriggered = true;
    }

    private void openCapsule() {
        if (opened) {
            return;
        }
        opened = true;
        postOpenTimer = POST_OPEN_DELAY;
        if (locksNativeP2CpuOnOpen()
                && services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick
                && sidekick.getCpuController() != null) {
            // The capsule slot runs after Player_2, so this suppresses CPU input
            // generation beginning with the following player pass while the
            // already-latched Ctrl_2_logical word continues to drive physics.
            sidekick.getCpuController().setController2SignedLocked(true);
        }
        services().playSfx(Sonic3kSfx.EXPLODE.id);
        explosionController = new S3kBossExplosionController(centreX, centreY, 3, services().rng());
        spawnAnimals();
    }

    private void tickExplosionController() {
        if (explosionController == null || explosionController.isFinished()) {
            return;
        }
        explosionController.tick();
        for (var entry : explosionController.drainPendingExplosions()) {
            if (entry.playSfx()) {
                services().playSfx(Sonic3kSfx.EXPLODE.id);
            }
            spawnChild(() -> new S3kBossExplosionChild(entry.x(), entry.y()));
        }
    }

    private void spawnAnimals() {
        for (int i = 0; i < animalCount(); i++) {
            int animalX = centreX + (i % 2 == 0 ? -(8 + i * 4) : (8 + i * 4));
            int animalY = centreY + animalYOffset();
            int delay = i * 4;
            int artVariant = services().rng().nextBits(1);
            ObjectSpawn animalSpawn = new ObjectSpawn(animalX, animalY, 0x28, 0, 0, false, 0);
            spawnChild(() -> new EggPrisonAnimalInstance(animalSpawn, delay, artVariant));
        }
    }

    private void startResults(AbstractPlayableSprite player) {
        resultsStarted = true;
        if (services().gameState() != null) {
            services().gameState().setEndOfLevelActive(true);
        }
        for (PlayableEntity candidate : resultParticipants(player)) {
            if (candidate instanceof AbstractPlayableSprite sprite) {
                if (sprite == player || sprite.getCpuController() == null) {
                    lockForResults(sprite);
                } else {
                    // sub_868F8 ends Player_1 immediately; the following
                    // Check_TailsEndPose dispatch ends Player_2 one SST pass later.
                    sprite.getCpuController().queueNativeEndingPoseForNextPlayerSlot();
                }
            }
        }
        PlayerCharacter character = resolvePlayerCharacter();
        int currentAct = services().currentAct();
        // sub_868F8 calls AllocateObject, so Obj_LevelResults takes the lowest
        // free SST. If that slot has already run, Obj_LevelResultsInit and its
        // three KosM submissions wait for the next Process_Sprites pass
        // (sonic3k.asm:181978-181990).
        S3kResultsScreenObjectInstance result =
                spawnFreeChild(() -> createResultsScreen(character, currentAct));
        if (nativeResultsRunsInAllocationPass()
                && services().objectManager().reservedSlotWaitsForNextObjectPass(result.getSlotIndex())) {
            // A folded native object graph can leave the engine capsule in a
            // later SST than the ROM capsule even though the ROM's newly
            // allocated results slot is still ahead of its execution cursor.
            // Consume that real Obj_LevelResultsInit dispatch here; the child
            // remains in its allocated SST for all subsequent passes.
            result.update(services().objectManager().getVblaCounter(), player);
        }
    }

    /** Allows a retained post-capsule owner to keep control of its native handoff. */
    protected S3kResultsScreenObjectInstance createResultsScreen(PlayerCharacter character, int act) {
        return new S3kResultsScreenObjectInstance(character, act);
    }

    private List<PlayableEntity> resultParticipants(AbstractPlayableSprite player) {
        try {
            List<PlayableEntity> queried = services().playerQuery()
                    .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS);
            if (queried.contains(player)) {
                return queried;
            }
            List<PlayableEntity> participants = new ArrayList<>(queried.size() + 1);
            participants.add(player);
            participants.addAll(queried);
            return participants;
        } catch (RuntimeException ignored) {
            List<PlayableEntity> participants = new ArrayList<>();
            participants.add(player);
            participants.addAll(services().playerQuery()
                    .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS));
            return participants;
        }
    }

    private void lockForResults(AbstractPlayableSprite sprite) {
        // Set_PlayerEndingPose preserves status and Obj_EggCapsule still tail-calls
        // SolidObjectFull after its routine dispatch. Keep this capsule as the
        // sole valid support while object_control=$81 suppresses normal solids.
        sprite.setObjectControlledSolidContactObject(this);
        ObjectControlState.nativeBit7FullControl().applyTo(sprite);
        sprite.setControlLocked(true);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAnimationId(Sonic3kAnimationIds.VICTORY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.EGG_CAPSULE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(opened ? 1 : 0, centreX, centreY, false, false);
        renderer.drawFrameIndex(buttonTriggered ? 0x0C : 5,
                centreX, centreY + BUTTON_Y_OFFSET, false, false);
    }
}
