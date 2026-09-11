package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.RomObjectSnapshot;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * ROM object: {@code Obj_CNZBalloon}.
 *
 * <p>CNZ balloons are local launchers, not path-following transport objects.
 * The S3K disassembly loads {@code Map_CNZBalloon} and uses the balloon's
 * center position as the contact anchor; on contact it applies the ROM bounce
 * impulse and restores normal player control.
 *
 * <p>The art sheet is loaded from the verified CNZ mapping table in
 * {@link com.openggf.game.sonic3k.Sonic3kObjectArt} using the lock-on ROM
 * offsets captured in {@code Sonic3kConstants}. The subtype's low 3 bits select
 * the balloon color variant, matching the SonLVL CNZ definition.
 */
public final class CnzBalloonInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, RewindRecreatable {

    private static final int COLLISION_FLAGS = 0xC0 | 0x17;
    private static final int PRIORITY_BUCKET = 5; // Obj_CNZBalloon: priority $280.
    private static final int WIDTH_HALF = 0x10;
    private static final int HEIGHT_HALF = 0x20;
    private static final int ROM_BOUNCE_Y_SPEED = 0x700;
    private static final int OFFSCREEN_X = 0x7F00;
    private static final int NORMAL_FRAME_DELAY = 7;
    private static final int POP_FRAME_DELAY = 2;
    private static final int[] NORMAL_FRAME_SEQUENCE = {0, 1, 2, 1};
    private static final int[] POP_FRAME_SEQUENCE = {3, 4};
    private static final int[] FRAME_BY_COLOR = {0, 5, 10, 15, 20};
    private static final int[] UNDERWATER_BUBBLER_CHILD_SUBTYPES = {0, 0, 1, 3};
    private static final int SNAPSHOT_BASE_Y_OFFSET = 0x32;
    private static final int SNAPSHOT_COLLISION_FLAGS_OFFSET = 0x28;
    private int subtype;
    private int baseY;
    private int angle;
    private boolean popped;
    private boolean movedOffscreen;
    private boolean initialized;
    private int animationTimer;
    private int normalAnimationIndex;
    private int popAnimationIndex;
    private int frameOffset;
    private int lastLaunchFrame = Integer.MIN_VALUE;
    private int lastObjectDispatchCounter = Integer.MIN_VALUE;
    private boolean pendingUnderwaterBubblerSpawn;

    public CnzBalloonInstance(ObjectSpawn spawn) {
        super(spawn, "CNZBalloon");
        this.subtype = spawn.subtype();
        this.baseY = spawn.y();
    }


    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public CnzBalloonInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzBalloonInstance(ctx.spawn());
    }

    @Override
    public void hydrateFromRomSnapshot(RomObjectSnapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }

        int snapshotBaseY = snapshot.wordAt(SNAPSHOT_BASE_Y_OFFSET);
        if (snapshotBaseY != 0) {
            baseY = snapshotBaseY;
        }
        angle = snapshot.angle() & 0xFF;
        popped = snapshot.byteAt(SNAPSHOT_COLLISION_FLAGS_OFFSET) == 0;
        movedOffscreen = snapshot.xPos() == OFFSCREEN_X;
        initialized = true;
        updateDynamicSpawn(snapshot.xPos(), snapshot.yPos());

        int colorBase = FRAME_BY_COLOR[Math.min(subtype & 0x07, FRAME_BY_COLOR.length - 1)];
        int mappingFrame = snapshot.mappingFrame();
        if (mappingFrame >= colorBase && mappingFrame < colorBase + 5) {
            frameOffset = mappingFrame - colorBase;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        var objectServices = tryServices();
        int dispatchCounter = getSlotIndex() >= 0
                && objectServices != null && objectServices.objectManager() != null
                ? objectServices.objectManager().getFrameCounter()
                : vIntRunCount;
        if (pendingUnderwaterBubblerSpawn) {
            pendingUnderwaterBubblerSpawn = false;
            spawnUnderwaterBubblerChildren();
        }
        synchronizeRoutineState(dispatchCounter, true);

        // ROM Obj_CNZBalloon reacts only when Touch_Process sets
        // collision_property; the shared touch-response pass invokes
        // onTouchResponse with the Touch_Sizes hitbox.
    }

    @Override
    public void snapshotTouchResponseState() {
        var objectServices = tryServices();
        if (initialized && objectServices != null && objectServices.objectManager() != null) {
            // A retained balloon can remain in the live touch list after the
            // seamless transition rebuilds execution slots. Catch its local
            // routine up to the manager's Process_Sprites count before Touch_Loop.
            synchronizeRoutineState(objectServices.objectManager().getFrameCounter(), false);
        }
        super.snapshotTouchResponseState();
    }

    @Override
    public int getCollisionFlags() {
        var objectServices = tryServices();
        if (initialized && objectServices != null && objectServices.objectManager() != null) {
            // S3K preserves the previous Collision_response_list rather than
            // calling snapshotTouchResponseState() for every object. Its live
            // SST pointer still dereferences the current balloon, so catch the
            // retained counter epoch up at the first touch-state read as well.
            synchronizeRoutineState(objectServices.objectManager().getFrameCounter(), false);
        }
        if (movedOffscreen) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return getTouchResponseProfile(false);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY,
                !movedOffscreen,
                true,
                false,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }

    @Override
    public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
        if (movedOffscreen) {
            return;
        }
        launchPlayer(player, frameCounter);
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // Obj_CNZBalloon updates y_pos through its sine bob before tail-calling
        // Sprite_CheckDeleteTouch3. S3K's Collision_response_list stores the
        // balloon's SST pointer, so the next player-slot Touch_Loop dereferences
        // that live post-bob y_pos rather than the older pre-update coordinate
        // (docs/skdisasm/sonic3k.asm:66776-66795,20656-20710).
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_BALLOON);
        if (renderer == null) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x02) != 0;
        renderer.drawFrameIndex(getFrameIndex(), getX(), getY(), hFlip, vFlip);
    }

    private int getFrameIndex() {
        int color = subtype & 0x07;
        if (color >= FRAME_BY_COLOR.length) {
            color = FRAME_BY_COLOR.length - 1;
        }
        return FRAME_BY_COLOR[color] + frameOffset;
    }

    private void launchPlayer(PlayableEntity playerEntity, int frameCounter) {
        if (movedOffscreen || playerEntity == null || lastLaunchFrame == frameCounter) {
            return;
        }
        lastLaunchFrame = frameCounter;

        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        boolean firstPop = !popped;
        if ((subtype & 0x80) != 0) {
            player.setYSpeed((short) -0x380);
            if (firstPop && levelHasWater()) {
                // Touch_Process only sets collision_property in the player slot.
                // Obj_CNZBalloon consumes it later in its own ExecuteObjects slot,
                // where sub_3181E allocates the four Bubbler children. Deferring
                // allocation until update() keeps those children out of an
                // already-built pre-object execution order and preserves native
                // first-update/RNG timing.
                pendingUnderwaterBubblerSpawn = true;
            }
        } else {
            player.setYSpeed((short) -ROM_BOUNCE_Y_SPEED);
        }
        player.setAir(true);
        player.setRollingJump(false);
        player.setJumping(false);
        player.setControlLocked(false);
        ObjectControlState.none().applyTo(player);
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null) {
            objectManager.clearRidingObject(player);
        }
        player.setOnObject(false);
        // Retail sub_317AE appears to intend an underwater subtype-$80 snap,
        // but the four sub_3181E calls clobber a1 with Obj_Bubbler children
        // before the x_pos/y_pos writes. In normal play the player keeps their
        // position while only y_vel/status/control are changed (sonic3k.asm:
        // 66797-66808, 66842-66856).
        if (firstPop) {
            popped = true;
            // The later balloon SST slot owns Animate_Sprite initialization.
            // Leave its timer/index pending here; unlike the first bset, later
            // contacts see anim already odd and must not restart this progress.
            animationTimer = 0;
            popAnimationIndex = 0;
            frameOffset = POP_FRAME_SEQUENCE[0];

            try {
                services().playSfx(Sonic3kSfx.BALLOON.id);
            } catch (Exception ignored) {
                // Headless tests can omit the audio backend; launch state is still valid.
            }
        }
    }

    private boolean levelHasWater() {
        if (services().waterSystem() == null) {
            return false;
        }
        return services().waterSystem().hasWater(services().featureZoneId(), services().featureActId());
    }

    private void spawnUnderwaterBubblerChildren() {
        for (int childIndex = 0; childIndex < UNDERWATER_BUBBLER_CHILD_SUBTYPES.length; childIndex++) {
            int childSubtype = UNDERWATER_BUBBLER_CHILD_SUBTYPES[childIndex];
            int random = services().rng().nextWord();
            int offset = (random & 0x0F) - 8;
            int childX = getX() + offset;
            int childY = getY() + offset;
            if (childIndex == UNDERWATER_BUBBLER_CHILD_SUBTYPES.length - 1) {
                // After the fourth sub_3181E call, a1 still addresses that child.
                // Retail's intended player-position snap therefore overwrites the
                // fourth bubble's random offset with the balloon position.
                childX = getX();
                childY = getY();
            }
            final int spawnX = childX;
            final int spawnY = childY;
            // sub_3181E uses AllocateObject for each Obj_Bubbler child, so these
            // effects consume the lowest free SST slots before later placement
            // loads (docs/skdisasm/sonic3k.asm:66829-66841).
            spawnFreeChild(() -> new BubblerObjectInstance(
                    new ObjectSpawn(spawnX, spawnY, 0x54, childSubtype, 0, false, 0)));
        }
    }

    int getRenderFrameForTest() {
        return getFrameIndex();
    }

    boolean isPoppedForTest() {
        return popped;
    }

    boolean hasMovedOffscreenForTest() {
        return movedOffscreen;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("ang=%02X base=%04X frame=%d%s",
                angle & 0xFF,
                baseY & 0xFFFF,
                getFrameIndex(),
                popped ? ",popped" : "");
    }

    private void advanceAnimation() {
        if (movedOffscreen) {
            return;
        }
        if (popped) {
            advancePopAnimation();
        } else {
            advanceNormalAnimation();
        }
    }

    private void advanceNormalAnimation() {
        animationTimer--;
        if (animationTimer >= 0) {
            return;
        }
        animationTimer = NORMAL_FRAME_DELAY;
        frameOffset = NORMAL_FRAME_SEQUENCE[normalAnimationIndex];
        normalAnimationIndex = (normalAnimationIndex + 1) % NORMAL_FRAME_SEQUENCE.length;
    }

    private void advancePopAnimation() {
        animationTimer--;
        if (animationTimer >= 0) {
            return;
        }
        if (popAnimationIndex < POP_FRAME_SEQUENCE.length) {
            animationTimer = POP_FRAME_DELAY;
            frameOffset = POP_FRAME_SEQUENCE[popAnimationIndex++];
        } else {
            // ROM Anim - Balloon.asm pop sequences end with $FB, and Animate_Sprite's
            // $FB handler writes x_pos = $7F00 itself (sonic3k.asm:36223-36227).
            //
            // FixBugs audit (docs/skdisasm/sonic3k.asm:38, assembled as 0): the
            // follow-up test at loc_31776 (sonic3k.asm:66786-66793) reads
            // `tst.b routine` — absolute address $000024, a vector-table byte that
            // is $00 in the retail ROM — instead of `tst.b routine(a0)`. Both
            // branches are behaviourally identical here: the balloon animation never
            // uses code $FC, so routine(a0) also stays 0 and neither branch takes the
            // second x_pos write. Audited, no divergence; the engine's $FB-driven
            // offscreen move is the shipped outcome.
            //
            // Sprite_CheckDeleteTouch3 (sonic3k.asm:37369) then calls
            // Delete_Current_Sprite only when the normal offscreen test later
            // decides the balloon is past the camera margin.
            movedOffscreen = true;
            setDestroyedByOffscreen();
        }
    }

    private void initializeFromRomRoutine() {
        initialized = true;
        baseY = spawn.y();
        angle = services().rng().nextByte();
    }

    private void synchronizeRoutineState(int dispatchCounter, boolean initializeIfNeeded) {
        if (!initialized) {
            if (!initializeIfNeeded) {
                return;
            }
            initializeFromRomRoutine();
            lastObjectDispatchCounter = dispatchCounter - 1;
        }

        if (lastObjectDispatchCounter == Integer.MIN_VALUE) {
            lastObjectDispatchCounter = dispatchCounter - 1;
        }
        if (dispatchCounter < lastObjectDispatchCounter) {
            // Seamless act setup can re-base manager counters while the
            // ROM-retained SST balloon survives. Resume from the new epoch.
            lastObjectDispatchCounter = dispatchCounter - 1;
        }
        int dispatches = dispatchCounter - lastObjectDispatchCounter;
        if (dispatches <= 0) {
            return;
        }
        for (int i = 0; i < dispatches && !isDestroyed(); i++) {
            advanceAnimation();
            int bobbedY = baseY + bobOffset(angle);
            updateDynamicSpawn(movedOffscreen ? OFFSCREEN_X : spawn.x(), bobbedY);
            angle = (angle + 1) & 0xFF;
        }
        lastObjectDispatchCounter = dispatchCounter;
    }

    private static int bobOffset(int angle) {
        return TrigLookupTable.sinHex(angle) >> 5;
    }
}
