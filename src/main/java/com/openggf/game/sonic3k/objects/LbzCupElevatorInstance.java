package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.RewindStateful;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * S3K S3KL object $18 - LBZ cup elevator.
 *
 * <p>ROM reference: {@code Obj_LBZCupElevator} and
 * {@code LBZCupElevator_Action} (sonic3k.asm:52537-53149).
 */
public final class LbzCupElevatorInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_LBZCupElevator} is installed from the S3K object pointer table at
     * {@code $0002694E} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:52542).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }

    private static final int WIDTH_PIXELS = 0x20;
    private static final int HEIGHT_PIXELS = 0x10;
    private static final int SOLID_SIDE_PADDING = 0x0B;
    private static final int PLAYER_Y_OFFSET = -0x10;
    private static final int CAPTURE_X_BIAS = 8;
    private static final int CAPTURE_X_RANGE = 0x10;
    private static final int RELEASE_COOLDOWN = 0x12;
    private static final int OFFSCREEN_RELEASE_COOLDOWN = 60;
    private static final int PRIORITY_LOW = 1;      // priority=$80
    private static final int PRIORITY_HIGH = 4;     // priority=$200
    private static final int PLAYER_PRIORITY = 2;   // priority=$100
    private static final int PLAYER_CUP_PRIORITY = 5; // priority=$280
    static final int ATTACH_MAPPING_FRAME = 1;
    private static final int[] PLAYER_TWIST_FRAMES = {
            0x55, 0x59, 0x5A, 0x5B, 0x5A, 0x59, 0x55, 0x56, 0x57, 0x58, 0x57, 0x56
    };
    private static final boolean[] PLAYER_TWIST_H_FLIPS = {
            false, true, true, false, false, false, true, true, true, false, false, false
    };

    private static final int ROUTINE_WAIT_ENTER_UP = 0x00;
    private static final int ROUTINE_MOVE_UP = 0x02;
    private static final int ROUTINE_WAIT_EXIT_TOP = 0x04;
    private static final int ROUTINE_WAIT_ENTER_DOWN = 0x06;
    private static final int ROUTINE_MOVE_DOWN = 0x08;
    private static final int ROUTINE_WAIT_EXIT_BOTTOM = 0x0A;
    private static final int ROUTINE_SPIN_1 = 0x0C;
    private static final int ROUTINE_FLING_1 = 0x0E;
    private static final int ROUTINE_FLING_2 = 0x10;
    private static final int ROUTINE_SPIN_2 = 0x12;
    private static final int ROUTINE_FLING_3 = 0x14;

    private final PlayerState p1 = new PlayerState();
    private final PlayerState p2 = new PlayerState();
    private boolean hFlip;
    private boolean flingAtEnd;

    private int x;
    private int y;
    private int anchorX;
    private int anchorY;
    private int travelDistance;
    private int travelProgress;
    private int activationFlag;
    private int routine;
    private int angleWord;
    private int spinSpeed;
    private int priorityBucket = PRIORITY_LOW;
    private int xVel;
    private int yVel;
    private int xSub;
    private int ySub;
    private boolean flickerMode;
    private boolean flickerHidden;
    private boolean attachHidden;
    private boolean cutsceneControlWasLocked;
    @RewindTransient(reason = "Structural child reference; the attachment child is spawned from parent state.")
    private AttachChild attachChild;
    @RewindTransient(reason = "Structural child reference; the base child is spawned from parent state.")
    private BaseChild baseChild;

    public LbzCupElevatorInstance(ObjectSpawn spawn) {
        super(spawn, "LBZCupElevator");
        this.hFlip = (spawn.renderFlags() & 1) != 0;
        this.flingAtEnd = (spawn.subtype() & 0x80) != 0;
        this.anchorX = spawn.x();
        this.anchorY = spawn.y();
        this.travelDistance = (spawn.subtype() & 0x0F) * 0x60;
        this.x = spawn.x() + 0x40;
        this.y = spawn.y();
        if (!hFlip) {
            this.x = spawn.x() - 0x40;
            this.angleWord = 0x8080;
        }

        if ((spawn.subtype() & 0x30) != 0) {
            this.travelProgress = travelDistance;
            this.anchorY += travelDistance;
            this.routine = ROUTINE_WAIT_ENTER_DOWN;
            if ((spawn.subtype() & 1) != 0) {
                this.angleWord = (angleWord & 0xFF00) | ((angleWord + 0x80) & 0xFF);
            }
        }
        updateDynamicSpawn(x, y);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS + SOLID_SIDE_PADDING;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return HEIGHT_PIXELS;
    }

    @Override
    public int getBalanceWidthPixels() {
        // Player_Move reads width_pixels(a1)=$20 for its object-edge balance
        // calculation. Obj18's SolidObjectFull call separately extends d1 by
        // $0B; that contact padding is not part of the SST width byte.
        return WIDTH_PIXELS;
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        int objRounded = anchorX & 0xFF80;
        int cameraBack = (cameraX - 0x80) & 0xFF80;
        int distance = (objRounded - cameraBack) & 0xFFFF;
        return distance > 0x280;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDisabledForKnucklesRoute()) {
            setDestroyed(true);
            return;
        }
        ensureChildren();
        if (flickerMode) {
            updateFlicker();
            // Obj_LBZElevatorCupFlicker flickers every other frame (Level_frame_counter+1 bit 0)
            // and parks itself far away once it leaves the screen (move.w #$7FFF,x_pos).
            flickerHidden = (vIntRunCount & 1) != 0;
            if (!isOnScreen(WIDTH_PIXELS)) {
                // Obj_LBZElevatorCupFlicker exits through Sprite_OnScreen_Test,
                // which clears this layout entry's respawn bit before deleting
                // the SST slot. The cup must therefore be eligible for a fresh
                // placement load when the camera later returns.
                setDestroyedByOffscreen();
                return;
            }
            updateDynamicSpawn(x, y);
            return;
        }

        updateAction();
        applyOrbitalX();
        if (tryServices() != null) {
            checkpointAll();
        }
        boolean cutsceneControlLocked = isLbz1KnucklesCutsceneControlLocked();
        boolean cutsceneReleased = cutsceneControlWasLocked && !cutsceneControlLocked;
        processPlayers(playerEntity, cutsceneReleased);
        cutsceneControlWasLocked = cutsceneControlLocked;
        updateDynamicSpawn(x, y);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        // Obj18 calls SolidObjectFull before LBZCupElevator_PlayerControl, so a
        // player who lands in this SST dispatch is immediately eligible for
        // capture by the same object routine.
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (flickerMode && flickerHidden) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_CUP_ELEVATOR);
        if (renderer != null) {
            renderer.drawFrameIndex(0, x, y, hFlip, false);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(WIDTH_PIXELS + SOLID_SIDE_PADDING, HEIGHT_PIXELS, HEIGHT_PIXELS + 1);
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        return !isDestroyed()
                && !isCapturedByThis(playerEntity)
                && playerStateFor(playerEntity).cooldown == 0
                && isSolidAngle();
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // The engine can retain a provisional P2 standing bit while Player 2
        // is still airborne below an already-active cup. Obj18's live P2 bit
        // is clear in that slot ordering, so let the Full2 new-contact path
        // re-evaluate the CPU sidekick against the sampled P2 position.
        return activationFlag == 0
                || !(player instanceof AbstractPlayableSprite sprite && sprite.isCpuControlled());
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // Obj18 calls SolidObjectFull2_1P for both players. Unlike the regular
        // SolidObjectFull entry, that helper does not test Player 2's on-screen
        // render flag before resolving the P2 standing bit.
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // Obj18 keeps its standing/pushing bits in the live SST status byte
        // while x_pos changes on every orbit step. updateDynamicSpawn rebuilds
        // the engine placement identity after each checkpoint, so the native
        // bits must follow this instance rather than that moving spawn record.
        return true;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // SolidObject_cont rejects horizontal separation with `bhi`, so the
        // exact x_pos == cup_x + d1 boundary remains a live side contact.
        return true;
    }

    @Override
    public boolean preservesObjectManagedRideWhileNotSolidFor(PlayableEntity player) {
        return isCapturedByThis(player);
    }

    int getTravelDistanceForTest() {
        return travelDistance;
    }

    int getTravelProgressForTest() {
        return travelProgress;
    }

    int getAnchorYForTest() {
        return anchorY;
    }

    int getRoutineForTest() {
        return routine;
    }

    int getAngleForTest() {
        return angleWord & 0xFFFF;
    }

    static int attachXForTest(int anchorX, boolean hFlip, int angleByte) {
        return attachX(anchorX, angleByte);
    }

    private void ensureChildren() {
        if (attachChild != null || baseChild != null) {
            return;
        }
        try {
            attachChild = spawnChild(() -> new AttachChild(this));
            baseChild = spawnChild(() -> new BaseChild(this));
        } catch (IllegalStateException ignored) {
            // Direct object tests run without ObjectServices.
        }
    }

    void rewindAttachChild(AttachChild child) {
        attachChild = child;
    }

    void rewindAttachChild(BaseChild child) {
        baseChild = child;
    }

    private void updateAction() {
        switch (routine) {
            case ROUTINE_WAIT_ENTER_UP, ROUTINE_WAIT_ENTER_DOWN -> updateWaitEnter();
            case ROUTINE_MOVE_UP -> updateMoveUp();
            case ROUTINE_WAIT_EXIT_TOP -> updateWaitExitTop();
            case ROUTINE_MOVE_DOWN -> updateMoveDown();
            case ROUTINE_WAIT_EXIT_BOTTOM -> updateWaitExitBottom();
            case ROUTINE_SPIN_1 -> updateSpin1();
            case ROUTINE_FLING_1 -> updateFling1();
            case ROUTINE_FLING_2 -> updateFling2();
            case ROUTINE_SPIN_2 -> updateSpin2();
            case ROUTINE_FLING_3 -> updateFling3();
            default -> routine = ROUTINE_WAIT_ENTER_UP;
        }
    }

    private void updateWaitEnter() {
        if (p1.inside || p2.inside) {
            activationFlag = 1;
            routine += 2;
        }
    }

    private void updateMoveUp() {
        if (travelProgress == travelDistance) {
            if (flingAtEnd) {
                routine = ROUTINE_SPIN_1;
                spinSpeed = 0x600;
                updateSpin1();
                return;
            }
            priorityBucket = PRIORITY_LOW;
            activationFlag = 0;
            routine += 2;
            return;
        }
        travelProgress += 4;
        updateTravelPosition();
        updatePriorityOnAngleCrossing();
    }

    private void updateWaitExitTop() {
        if (!p1.inside && !p2.inside) {
            routine += 2;
        }
    }

    private void updateMoveDown() {
        if (travelProgress == 0) {
            if (flingAtEnd) {
                routine = ROUTINE_SPIN_2;
                spinSpeed = 0x600;
                updateSpin2();
                return;
            }
            priorityBucket = PRIORITY_LOW;
            activationFlag = 0;
            routine += 2;
            return;
        }
        travelProgress -= 4;
        updateTravelPosition();
        updatePriorityOnAngleCrossing();
    }

    private void updateWaitExitBottom() {
        if (!p1.inside && !p2.inside) {
            routine = ROUTINE_WAIT_ENTER_UP;
        }
    }

    private void updateTravelPosition() {
        int angle = ((travelProgress * 0x155) >> 8) + angleLow();
        setAngleByte(angle);
        y = anchorY - travelProgress;
    }

    private void updateSpin1() {
        if (spinSpeed == 0x1000) {
            int angle = angleByte();
            if (!hFlip) {
                int adjusted = (angle + 0x40) & 0xFF;
                if (adjusted >= 0xF0) {
                    setAngleByte(0xC0);
                    attachHidden = true;
                    routine += 2;
                    return;
                }
            } else {
                int adjusted = (angle + 0xC0) & 0xFF;
                if (adjusted >= 0xF0) {
                    setAngleByte(0x40);
                    attachHidden = true;
                    routine += 4;
                    return;
                }
            }
        } else {
            spinSpeed += 0x10;
        }
        setAngleByte(angleByte() + spinSpeedAngleStep());
        updatePriorityOnAngleCrossing();
    }

    private void updateSpin2() {
        if (spinSpeed == 0x1000) {
            int adjusted = (angleByte() + 0xB0) & 0xFF;
            if (adjusted >= 0xF0) {
                setAngleByte(0x40);
                attachHidden = true;
                routine += 2;
                return;
            }
        } else {
            spinSpeed += 0x10;
        }
        setAngleByte(angleByte() - spinSpeedAngleStep());
        updatePriorityOnAngleCrossing();
    }

    /**
     * Per-frame angle advance during a spin. ROM does {@code move.b $2E(a0),d0}, which on the
     * big-endian 68000 reads the <em>high</em> byte of the {@code spinSpeed} word. The cup therefore
     * ramps smoothly ($06..$10 per frame) and keeps rotating once {@code spinSpeed} pins at $1000
     * ($10 per frame) until the exit-angle window is crossed. Reading the low byte instead spins
     * wildly and freezes at $1000 (low byte $00), trapping the rider forever.
     */
    private int spinSpeedAngleStep() {
        return (spinSpeed >> 8) & 0xFF;
    }

    private void updateFling1() {
        if (anchorX >= 0x16C0) {
            anchorX = 0x16C0;
            beginFlicker(-0x200);
        } else {
            anchorX += 0x10;
        }
    }

    private void updateFling2() {
        if (anchorX <= 0x2AE0) {
            anchorX = 0x2AE0;
            beginFlicker(0x200);
        } else {
            anchorX -= 0x10;
        }
    }

    private void updateFling3() {
        if (anchorX >= 0x2B20) {
            anchorX = 0x2B20;
            beginFlicker(-0x200);
        } else {
            anchorX += 0x10;
        }
    }

    private void beginFlicker(int launchXVel) {
        flickerMode = true;
        xVel = launchXVel;
        yVel = 0;
        xSub = 0;
        ySub = 0;
        playSfx(Sonic3kSfx.DEATH.id);
        flingPlayer(p1, mainPlayerOrNull(), -0x300);
        flingPlayer(p2, nativeP2OrNull(), -0x400);
    }

    /**
     * Obj_LBZElevatorCupFlicker runs {@code MoveSprite}: the cup arcs away on its launch x velocity
     * while gravity ({@code $38}) drags it down, so it falls off-screen instead of sliding flat.
     */
    private void updateFlicker() {
        SubpixelMotion.State state = new SubpixelMotion.State(x, y, xSub, ySub, xVel, yVel);
        SubpixelMotion.moveSprite(state, SubpixelMotion.S3K_GRAVITY);
        x = state.x;
        y = state.y;
        xSub = state.xSub;
        ySub = state.ySub;
        yVel = state.yVel;
    }

    private void applyOrbitalX() {
        x = anchorX + (TrigLookupTable.cosHex(angleByte()) >> 2);
    }

    private void updatePriorityOnAngleCrossing() {
        if (priorityBucket == PRIORITY_HIGH) {
            if (isAngleNegative()) {
                priorityBucket = PRIORITY_LOW;
                playSfx(Sonic3kSfx.HOVERPAD.id);
            }
        } else if (!isAngleNegative()) {
            priorityBucket = PRIORITY_HIGH;
            playSfx(Sonic3kSfx.HOVERPAD.id);
        }
    }

    private void processPlayers(PlayableEntity playerEntity, boolean cutsceneReleased) {
        processPlayer(mainSprite(playerEntity), p1, cutsceneReleased);
        processPlayer(nativeP2OrNull(), p2, cutsceneReleased);
    }

    private void processPlayer(AbstractPlayableSprite player, PlayerState state, boolean cutsceneReleased) {
        if (player == null) {
            return;
        }
        if (cutsceneReleased && state.inside) {
            // CutsceneKnux_LBZ1 clears object_control after Obj18's slot.  On
            // the following cup dispatch, preserve the standing contact but
            // retire the cup's movement-control write so it does not restore
            // object_control=$03 over the cutscene handoff.
            state.cutsceneReleased = true;
        }
        if (!state.inside) {
            if (state.cooldown > 0) {
                state.cooldown--;
                return;
            }
            if (canCapture(player)) {
                capturePlayer(player, state);
            }
            return;
        }

        if (!isPlayerValidForCapture(player)) {
            releasePlayer(player, state, OFFSCREEN_RELEASE_COOLDOWN, false);
            return;
        }
        if (state.cutsceneReleased) {
            if (player.isJumpJustPressed()) {
                jumpReleasePlayer(player, state);
                publishInitialJumpMapping(player);
                return;
            }
            holdPlayerPosition(player);
            return;
        }
        if (player.isJumpJustPressed() && !isLbz1KnucklesCutsceneControlLocked()) {
            jumpReleasePlayer(player, state);
            return;
        }
        holdPlayer(player);
    }

    private boolean canCapture(AbstractPlayableSprite player) {
        if (!isSolidAngle() || player.isObjectControlled()) {
            return false;
        }
        if (!isPlayerStandingOnThis(player)) {
            return false;
        }
        if (activationFlag != 0) {
            return true;
        }
        int dx = player.getCentreX() - x + CAPTURE_X_BIAS;
        return dx >= 0 && dx < CAPTURE_X_RANGE;
    }

    private void capturePlayer(AbstractPlayableSprite player, PlayerState state) {
        state.inside = true;
        state.cutsceneReleased = false;
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setAnimationId(0);
        // Native writes anim=$00 after the player's Animate dispatch, then
        // object_control bit 1 suppresses later Animate calls while held. It
        // does not clear prev_anim or the script cursor. Preserving both is
        // observable when anim=$02 is restored on release: a prior Roll can
        // resume instead of restarting at its first mapping.
        holdPlayer(player);
    }

    private void holdPlayer(AbstractPlayableSprite player) {
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        holdPlayerPosition(player);
    }

    private void holdPlayerPosition(AbstractPlayableSprite player) {
        NativePositionOps.writeXPosPreserveSubpixel(player, x);
        NativePositionOps.writeYPosPreserveSubpixel(player, y + PLAYER_Y_OFFSET);
        player.setPriorityBucket(isPlayerBehindCup() ? PLAYER_PRIORITY : PLAYER_CUP_PRIORITY);
        player.setLatchedSolidObject(Sonic3kObjectIds.LBZ_CUP_ELEVATOR, this);
        player.setOnObject(true);
        applyCupPlayerTwistFrame(player);
    }

    private void jumpReleasePlayer(AbstractPlayableSprite player, PlayerState state) {
        releasePlayer(player, state, RELEASE_COOLDOWN, true);
        player.setJumping(false);
        player.applyCustomRadii(7, 0x0E);
        player.setAnimationId(2);
        short releaseY = player.getCentreY();
        player.setRolling(true);
        // ROM changes y_radius/status without changing centre-based y_pos.
        // setRolling changes the engine's visual bounds, so restore the same
        // native centre word while preserving its subpixel fraction.
        NativePositionOps.writeYPosPreserveSubpixel(player, releaseY);
        player.setRollingJump(false);
        if (player.isLeftPressed()) {
            player.setXSpeed((short) -0x200);
        }
        if (player.isRightPressed()) {
            player.setXSpeed((short) 0x200);
        }
        player.setYSpeed((short) -0x480);
    }

    private static void publishInitialJumpMapping(AbstractPlayableSprite player) {
        if (player.getAnimationSet() != null
                && player.getAnimationSet().getScript(2) != null
                && !player.getAnimationSet().getScript(2).frames().isEmpty()) {
            // The native object write is visible in the same post-object
            // snapshot as the release velocities. Publish the character's own
            // first jump-script mapping rather than retaining the cup twist.
            var script = player.getAnimationSet().getScript(2);
            player.setMappingFrame(script.frames().getFirst());
            player.setAnimationFrameIndex(1);
            player.setAnimationTick(Math.max(0, 0x400 - Math.abs(player.getGSpeed())) >> 8);
            if (player.getAnimationManager() != null) {
                player.getAnimationManager().publishPreviousAnimationId(2);
            }
        }
    }

    private void releasePlayer(AbstractPlayableSprite player, PlayerState state, int cooldown, boolean airborne) {
        state.inside = false;
        state.cutsceneReleased = false;
        state.cooldown = cooldown;
        player.setPriorityBucket(PLAYER_PRIORITY);
        ObjectControlState.none().applyTo(player);
        player.setObjectMappingFrameControl(false);
        player.setLatchedSolidObjectId(0);
        player.setOnObject(false);
        player.setPushing(false);
        if (airborne) {
            player.setAir(true);
        }
    }

    private void flingPlayer(PlayerState state, AbstractPlayableSprite player, int yVelocity) {
        if (!state.inside || player == null) {
            return;
        }
        state.inside = false;
        state.cutsceneReleased = false;
        state.cooldown = RELEASE_COOLDOWN;
        ObjectControlState.none().applyTo(player);
        player.setObjectMappingFrameControl(false);
        player.setLatchedSolidObjectId(0);
        player.setOnObject(false);
        player.setPushing(false);
        player.setAir(true);
        // sub_26E08 writes routine=4 directly. This is the player's Hit
        // dispatcher even though the cup supplies bespoke launch velocities
        // and does not run the ordinary ring-loss damage path.
        player.setHurt(true);
        player.setYSpeed((short) yVelocity);
        player.setXSpeed((short) (hFlip ? 0x200 : -0x200));
        if (hFlip) {
            // sub_26E08 negates x_vel and sets Status_Facing together when
            // the cup's status bit 0 is set.
            player.setDirection(Direction.LEFT);
        }
        player.setGSpeed((short) 0);
        player.setAnimationId(0x1A);
    }

    private boolean isPlayerValidForCapture(AbstractPlayableSprite player) {
        return !player.getDead() && !player.isHurt() && !player.isDebugMode();
    }

    private boolean isLbz1KnucklesCutsceneControlLocked() {
        var objectServices = tryServices();
        if (objectServices == null) {
            return false;
        }
        return S3kRuntimeStates.currentLbz(objectServices.zoneRuntimeRegistry())
                .map(com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState::isLbz1KnucklesCutsceneControlLocked)
                .orElse(false);
    }

    private void applyCupPlayerTwistFrame(AbstractPlayableSprite player) {
        int index = ((angleByte() + 0x0B) & 0xFF) / 0x16;
        if (index >= PLAYER_TWIST_FRAMES.length) {
            index = 0;
        }
        player.setObjectMappingFrameControl(true);
        player.setMappingFrame(PLAYER_TWIST_FRAMES[index]);
        boolean hFlip = PLAYER_TWIST_H_FLIPS[index];
        // loc_32610 masks and replaces only render_flags bits 0-1. It does not
        // mutate Status_Facing, so the visual twist must remain independent of
        // the player's gameplay-facing direction while the cup owns the frame.
        player.setRenderFlips(hFlip, false);
    }

    private boolean isDisabledForKnucklesRoute() {
        return (spawn.subtype() & 0x40) != 0
                && currentPlayerCharacter() == PlayerCharacter.KNUCKLES;
    }

    private PlayerCharacter currentPlayerCharacter() {
        try {
            return S3kRuntimeStates.resolvePlayerCharacter(
                    services().zoneRuntimeRegistry(),
                    services().configuration());
        } catch (IllegalStateException ignored) {
            return PlayerCharacter.SONIC_AND_TAILS;
        }
    }

    private boolean isPlayerStandingOnThis(AbstractPlayableSprite player) {
        return player.isOnObject() && player.getLatchedSolidObjectInstance() == this;
    }

    @Override
    public String traceDebugDetails() {
        AbstractPlayableSprite player = mainPlayerOrNull();
        AbstractPlayableSprite sidekick = nativeP2OrNull();
        return "routine=" + routine
                + " angle=" + Integer.toHexString(angleByte())
                + " activation=" + activationFlag
                + " p1=" + p1.inside + "/" + p1.cooldown
                + " p2=" + p2.inside + "/" + p2.cooldown
                + " solid=" + (player != null && isSolidFor(player))
                + " standing=" + (player != null && isPlayerStandingOnThis(player))
                + " latchSelf=" + (player != null && player.getLatchedSolidObjectInstance() == this)
                + " p2present=" + (sidekick != null)
                + " p2solid=" + (sidekick != null && isSolidFor(sidekick))
                + " p2standing=" + (sidekick != null && isPlayerStandingOnThis(sidekick))
                + " p2latchSelf=" + (sidekick != null && sidekick.getLatchedSolidObjectInstance() == this)
                + " p2prev=" + (sidekick != null
                        ? Integer.toHexString(sidekick.getCentreX(1) & 0xFFFF) + ","
                                + Integer.toHexString(sidekick.getCentreY(1) & 0xFFFF)
                        : "none");
    }

    private boolean isCapturedByThis(PlayableEntity playerEntity) {
        return playerEntity instanceof AbstractPlayableSprite player
                && player.isObjectControlled()
                && player.getLatchedSolidObjectInstance() == this;
    }

    private PlayerState playerStateFor(PlayableEntity playerEntity) {
        if (playerEntity instanceof AbstractPlayableSprite player
                && player == nativeP2OrNull()) {
            return p2;
        }
        return p1;
    }

    private boolean isSolidAngle() {
        int delta = (angleByte() - 0x20) & 0xFF;
        return delta >= 0x40;
    }

    private boolean isPlayerBehindCup() {
        int delta = (angleByte() + 0x20) & 0xFF;
        return delta >= 0xC0;
    }

    private boolean isAngleNegative() {
        return (angleByte() & 0x80) != 0;
    }

    private int angleByte() {
        return (angleWord >> 8) & 0xFF;
    }

    private int angleLow() {
        return angleWord & 0xFF;
    }

    private void setAngleByte(int value) {
        angleWord = ((value & 0xFF) << 8) | angleLow();
    }

    private AbstractPlayableSprite mainSprite(PlayableEntity playerEntity) {
        return playerEntity instanceof AbstractPlayableSprite sprite ? sprite : null;
    }

    private AbstractPlayableSprite mainPlayerOrNull() {
        try {
            PlayableEntity player = services().playerQuery().mainPlayerOrNull();
            return player instanceof AbstractPlayableSprite sprite ? sprite : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private AbstractPlayableSprite nativeP2OrNull() {
        try {
            PlayableEntity player = services().playerQuery().nativeP2OrNull();
            return player instanceof AbstractPlayableSprite sprite ? sprite : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private void playSfx(int sfxId) {
        try {
            services().playSfx(sfxId);
        } catch (Exception ignored) {
            // Direct object tests and missing audio backends should not affect object logic.
        }
    }

    private static int attachX(int anchorX, int angleByte) {
        int offset = (TrigLookupTable.cosHex(angleByte) * 3) >> 5;
        return anchorX + offset;
    }

    private static final class PlayerState implements RewindStateful<PlayerState.Snapshot> {
        boolean inside;
        boolean cutsceneReleased;
        int cooldown;

        @Override
        public Snapshot captureRewindStateValue() {
            return new Snapshot(inside, cutsceneReleased, cooldown);
        }

        @Override
        public void restoreRewindStateValue(Snapshot state) {
            inside = state.inside();
            cutsceneReleased = state.cutsceneReleased();
            cooldown = state.cooldown();
        }

        private record Snapshot(boolean inside, boolean cutsceneReleased, int cooldown) {
        }
    }

    private static final class AttachChild extends AbstractObjectInstance implements RewindRecreatable {
        @RewindTransient(reason = "Structural parent pointer; attachment position and lifetime derive from parent state.")
        private final LbzCupElevatorInstance parent;
        private int x;

        private AttachChild(LbzCupElevatorInstance parent) {
            super(parent.getSpawn(), "LBZCupElevatorAttach");
            this.parent = parent;
            this.x = attachX(parent.spawn.x(), parent.angleByte());
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            LbzCupElevatorInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                    ctx, LbzCupElevatorInstance.class);
            if (liveParent == null) {
                return null;
            }
            AttachChild restored = new AttachChild(liveParent);
            liveParent.rewindAttachChild(restored);
            return restored;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed() || parent.attachHidden) {
                setDestroyed(true);
                return;
            }
            x = attachX(parent.spawn.x(), parent.angleByte());
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return parent.y;
        }

        @Override
        public int getPriorityBucket() {
            return parent.isAngleNegative() ? 2 : 3;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_CUP_ELEVATOR);
            if (renderer != null) {
                boolean hFlip = (((parent.angleByte() - 0x40) & 0x80) != 0);
                renderer.drawFrameIndex(ATTACH_MAPPING_FRAME, x, getY(), hFlip, false);
            }
        }
    }

    private static final class BaseChild extends AbstractObjectInstance implements RewindRecreatable {
        @RewindTransient(reason = "Structural parent pointer; base position and lifetime derive from parent state.")
        private final LbzCupElevatorInstance parent;

        private BaseChild(LbzCupElevatorInstance parent) {
            super(parent.getSpawn(), "LBZCupElevatorBase");
            this.parent = parent;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            LbzCupElevatorInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                    ctx, LbzCupElevatorInstance.class);
            if (liveParent == null) {
                return null;
            }
            BaseChild restored = new BaseChild(liveParent);
            liveParent.rewindAttachChild(restored);
            return restored;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
            }
        }

        @Override
        public int getX() {
            return parent.spawn.x();
        }

        @Override
        public int getY() {
            return parent.y;
        }

        @Override
        public int getPriorityBucket() {
            return 2;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_CUP_ELEVATOR);
            if (renderer != null) {
                renderer.drawFrameIndex(2, getX(), getY(), false, false);
            }
        }
    }
}
