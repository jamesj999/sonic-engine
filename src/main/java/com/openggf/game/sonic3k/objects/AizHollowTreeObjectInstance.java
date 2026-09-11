package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * S3K Obj 0x03 - AIZ Hollow Tree.
 *
 * <p>Primary disassembly references:
 * Obj_AIZHollowTree / sub_1F7CE / AIZTree_SetPlayerPos (sonic3k.asm:43601-43820).
 */
public class AizHollowTreeObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int TREE_CAPTURE_MIN_X = 0x2C99;
    private static final int TREE_CAPTURE_MAX_X = 0x2D66;

    private static final int CAMERA_LOCK_X = 0x2C60;
    private static final int CAMERA_RELEASE_MIN_X = 0x1300;
    private static final int CAMERA_RELEASE_MAX_X = 0x4000;
    private static final int CAMERA_LOCK_TIMER = 0x3C;

    private static final int MIN_CAPTURE_X_SPEED = 0x600;

    // AIZTree_PlayerFrames table.
    private static final int[] PLAYER_FRAMES = {
            0x69, 0x6A, 0x6B, 0x77, 0x6C, 0x6C, 0x6D, 0x6D, 0x6E, 0x6E, 0x6F, 0x6F,
            0x70, 0x70, 0x71, 0x71, 0x72, 0x72, 0x73, 0x73, 0x74, 0x74, 0x75, 0x75,
            0x76, 0x76, 0x77, 0x77, 0x6C, 0x6C, 0x6D, 0x6D, 0x6E, 0x6E, 0x6F, 0x6F,
            0x70, 0x70, 0x71, 0x71, 0x72, 0x72, 0x73, 0x73, 0x74, 0x74, 0x75, 0x75,
            0x6B, 0x6B, 0x6A, 0x6A, 0x69, 0x69
    };

    private static final int PLAYER_SLOT_MAIN = 0;
    private static final int PLAYER_SLOT_SIDEKICK = 1;
    // ROM global event word used by Obj_AIZ1TreeRevealControl and AIZ1_ScreenEvent.
    private static int eventsFg4;

    private int treeX;
    private int treeY;
    private final int[] progress = new int[2];
    private final boolean[] riding = new boolean[2];
    private final boolean[] releaseObjectControlPending = new boolean[2];
    private final String[] lastDecision = {"init", "init"};

    private int cameraLockTimer;

    public AizHollowTreeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AIZHollowTree");
        this.treeX = spawn.x();
        this.treeY = spawn.y();
    }

    public static void resetTreeRevealCounter() {
        eventsFg4 = 0;
    }

    public static int getTreeRevealCounter() {
        return eventsFg4;
    }

    public static void setTreeRevealCounter(int value) {
        eventsFg4 = Math.max(0, value);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        updatePlayer(player, PLAYER_SLOT_MAIN, true);
        AbstractPlayableSprite sidekick = firstTrackedSidekick();
        if (sidekick != null) {
            /*
             * FixBugs audit (docs/skdisasm/sonic3k.asm:38, assembled as 0 in the
             * shipped ROM). AIZHollowTree_CheckPlayers (sonic3k.asm:43654-43668)
             * derives Player 2's ride bit as `addq.b #p2_standing_bit-
             * p1_standing_bit,d6` instead of `moveq #p2_standing_bit,d6`. When
             * Player 1 was riding, AIZTree_SetPlayerPos ends in
             * `jmp (Perform_Player_DPLC)` (sonic3k.asm:43822) and leaves d6
             * clobbered, so the sidekick's `btst d6,status(a0)` reads a dirty bit
             * (bit 1 rather than bit 4) — the ROM comment's "player 2 behaves
             * erratically". The FixBugs=1 branch always uses the clean p2 bit.
             *
             * This engine slot tracks riders with a per-slot boolean and therefore
             * implements the FIXED branch. CnzWireCageObjectInstance models the
             * un-fixed dirty-d6 form (DIRTY_P2_STANDING_BIT there); porting the
             * same treatment here is an outstanding audit finding, left out of this
             * pass because it changes sidekick behaviour on AIZ1, the primary
             * release slice. Note the ROM only dirties d6 on the ground ride path:
             * AIZHollowTree_FallIfProgressComplete saves and restores d6 around its
             * AIZTree_SetPlayerPos call (sonic3k.asm:43764-43770).
             */
            updatePlayer(sidekick, PLAYER_SLOT_SIDEKICK, false);
        }
        updateCameraLock(player);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Object is logic-only in ROM (no mappings/art configured in Obj_AIZHollowTree init).
    }

    private AbstractPlayableSprite firstTrackedSidekick() {
        return services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick
                ? sidekick
                : null;
    }

    private void updatePlayer(AbstractPlayableSprite player,
            int slot,
            boolean mainPlayer) {
        if (player == null) {
            lastDecision[slot] = "missing";
            return;
        }

        if (releaseObjectControlPending[slot]) {
            releaseObjectControlPending[slot] = false;
            ObjectControlState.none().applyTo(player);
            lastDecision[slot] = "release-control";
        }

        if (!riding[slot]) {
            tryCapturePlayer(player, slot, mainPlayer);
            return;
        }

        advanceRideInertia(player);

        int absGroundSpeed = Math.abs(player.getGSpeed());
        if (absGroundSpeed < MIN_CAPTURE_X_SPEED) {
            if (progressWord(progress[slot]) >= 0x400) {
                lastDecision[slot] = "fall-low-speed-done";
                fallOffTree(player, slot);
                return;
            }
            setPlayerOnTree(player, slot);
            lastDecision[slot] = "fall-low-speed-early";
            fallOffTree(player, slot);
            return;
        }

        if (!player.getAir()) {
            int dy = player.getCentreY() - treeY;
            int check = dy + 0x90;
            if (check < 0 || check > 0x130) {
                lastDecision[slot] = "fall-y-range";
                fallOffTree(player, slot);
                return;
            }
            setPlayerOnTree(player, slot);
            lastDecision[slot] = "ride-ground";
            return;
        }

        if (player.getCentreX() < TREE_CAPTURE_MIN_X) {
            player.setCentreX((short) TREE_CAPTURE_MIN_X);
            player.setXSpeed((short) 0x400);
        }
        if (player.getCentreX() >= TREE_CAPTURE_MAX_X) {
            player.setCentreX((short) TREE_CAPTURE_MAX_X);
            player.setXSpeed((short) -0x400);
        }
        lastDecision[slot] = "fall-air";
        fallOffTree(player, slot);
    }

    private void tryCapturePlayer(AbstractPlayableSprite player, int slot, boolean mainPlayer) {
        if (player.getAir()) {
            lastDecision[slot] = "no-capture-air";
            return;
        }
        int dx = (player.getCentreX() + 0x10) - treeX;
        if (dx < 0 || dx >= 0x40) {
            lastDecision[slot] = "no-capture-x";
            return;
        }
        int dy = player.getCentreY() - treeY;
        if (dy < -0x5A || dy > 0xA0) {
            lastDecision[slot] = "no-capture-y";
            return;
        }
        if (player.getXSpeed() < MIN_CAPTURE_X_SPEED) {
            lastDecision[slot] = "no-capture-speed";
            return;
        }
        if (isObjectControlActive(player)) {
            lastDecision[slot] = "no-capture-control";
            return;
        }

        riding[slot] = true;
        progress[slot] = 0;
        releaseObjectControlPending[slot] = false;

        player.setOnObject(true);
        player.setLatchedSolidObject(Sonic3kObjectIds.AIZ_HOLLOW_TREE, this);
        player.setAngle((byte) 0);
        player.setYSpeed((short) 0);
        player.setObjectMappingFrameControl(true);
        player.setForcedAnimationId(Sonic3kAnimationIds.WALK);
        // The tree owns the player's vertical path and animation while riding.
        // setPlayerOnTree applies the preserved horizontal inertia before the
        // ROM path formula so the path delta still uses the moved x_pos.
        ObjectControlState.nativeBits0To6CpuAllowedMovementActive().applyTo(player);
        player.setSuppressGroundWallCollision(true);
        player.setControlLocked(false);
        player.setAir(false);
        // RideObject_SetRide semantics: preserve horizontal inertia as ground speed.
        player.setGSpeed(player.getXSpeed());
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        lastDecision[slot] = "capture";
        // Obj_AIZHollowTree sets object_control bits 6 and 1 only
        // (sonic3k.asm:43688-43693). Bit 6 skips Sonic_WalkSpeed's
        // CalcRoomInFront wall probe (sonic3k.asm:22713-22714), while the
        // lack of bit 7 means CPU/touch dispatch is not suppressed.

        if (mainPlayer) {
            // Obj_AIZHollowTree writes Camera_min/max_X_pos=$2C60 and $38=$3C
            // immediately after Player_1 capture (sonic3k.asm:43702-43704).
            // Tails_Check_Screen_Boundaries reads Camera_min_X_pos+$10 on the
            // next Tails physics tick and clamps Tails there when crossed
            // (sonic3k.asm:28414-28450). The engine camera step runs later in
            // this frame, so defer only the visible horizontal clamp; keep the
            // boundary word live for sidekick/player boundary logic.
            Camera camera = services().camera();
            camera.setMinX((short) CAMERA_LOCK_X);
            camera.setMaxX((short) CAMERA_LOCK_X);
            camera.deferHorizontalBoundaryClampOnce();
            cameraLockTimer = CAMERA_LOCK_TIMER;
            spawnDynamicObject(new AizTreeRevealControlObjectInstance(treeX, treeY));
        }
    }

    private void setPlayerOnTree(AbstractPlayableSprite player, int slot) {
        // This object is logic-only and not a SolidObjectProvider, so SolidContacts would
        // otherwise clear onObject each frame. Keep this sticky while the tree ride is active.
        player.setOnObject(true);
        player.setLatchedSolidObject(Sonic3kObjectIds.AIZ_HOLLOW_TREE, this);
        player.setObjectMappingFrameControl(true);
        player.setForcedAnimationId(Sonic3kAnimationIds.WALK);

        int progressValue = progress[slot];
        progressValue += player.getGSpeed() << 8;
        progress[slot] = progressValue;
        if (progressValue < 0) {
            lastDecision[slot] = "fall-progress-negative";
            fallOffTree(player, slot);
            return;
        }

        int progressWord = progressWord(progressValue);
        if (progressWord >= 0x400) {
            Camera camera = services().camera();
            camera.setMinX((short) CAMERA_RELEASE_MIN_X);
            camera.setMaxX((short) CAMERA_RELEASE_MAX_X);
        }

        int oldX = player.getCentreX();
        int angle = (progressWord >>> 1) & 0xFF;
        int sin = TrigLookupTable.sinHex(angle);
        int xOffset = (sin * 0x7000) >> 16;
        int newX = treeX + xOffset;
        player.setCentreXPreserveSubpixel((short) newX);
        player.setXSpeed((short) ((newX - oldX) << 8));

        int oldY = player.getCentreY();
        int yOffset = 0x90 - (progressWord >>> 2);
        int newY = treeY + yOffset;
        player.setCentreYPreserveSubpixel((short) newY);
        player.setYSpeed((short) ((newY - oldY) << 8));

        int frameIndex = ((progressWord >>> 1) / 0x0B);
        frameIndex = Math.clamp(frameIndex, 0, PLAYER_FRAMES.length - 1);
        player.setMappingFrame(PLAYER_FRAMES[frameIndex]);
    }

    @Override
    public String traceDebugDetails() {
        return String.format("tree m=%s/%04X s=%s/%04X fg4=%04X cam=%02X",
                lastDecision[PLAYER_SLOT_MAIN],
                progressWord(progress[PLAYER_SLOT_MAIN]),
                lastDecision[PLAYER_SLOT_SIDEKICK],
                progressWord(progress[PLAYER_SLOT_SIDEKICK]),
                eventsFg4 & 0xFFFF,
                cameraLockTimer & 0xFF);
    }

    private void advanceRideInertia(AbstractPlayableSprite player) {
        if (!player.isObjectControlSuppressesMovement()) {
            return;
        }
        player.setXSpeed(player.getGSpeed());
        player.setYSpeed((short) 0);
        player.move(player.getGSpeed(), (short) 0);
    }

    private void fallOffTree(AbstractPlayableSprite player, int slot) {
        riding[slot] = false;
        progress[slot] = 0;

        player.setAir(true);
        // Hollow-tree fall-off in ROM updates collision radius (center-based) directly.
        // In this engine, unrolling changes sprite height from top-left coordinates;
        // offset by half the height delta to keep center alignment equivalent.
        if (player.getRolling()) {
            player.setY((short) (player.getY() - (player.getRollHeightAdjustment() / 2)));
        }
        player.setRolling(false);
        player.applyStandingRadii(false);
        // move.w #1,anim writes the adjacent big-endian bytes anim=Walk and
        // prev_anim=Run; it does not select the engine's Run animation id.
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        player.publishRunAsPreviousAnimation();
        player.setOnObject(false);
        player.setLatchedSolidObjectId(0);
        player.setFlipsRemaining(0);
        player.setFlipSpeed(4);
        player.setForcedAnimationId(-1);
        player.setObjectMappingFrameControl(false);
        player.setControlLocked(false);
        ObjectControlState.none().applyTo(player);
        player.setSuppressGroundWallCollision(false);
        releaseObjectControlPending[slot] = false;
        player.setXSpeed((short) (player.getXSpeed() >> 1));
        player.setYSpeed((short) (player.getYSpeed() >> 1));
    }

    // 68k move.w (a2) over a long reads the upper 16 bits (big-endian layout).
    private static int progressWord(int progressLong) {
        return (progressLong >>> 16) & 0xFFFF;
    }

    private static boolean isObjectControlActive(AbstractPlayableSprite player) {
        // Disasm capture gate is "tst.b object_control(a1)" (any bit set).
        // In the engine, bit-0 control lock and full object control are separate flags,
        // and bit-1 parity for this object maps to objectMappingFrameControl ownership.
        return player.isObjectControlled()
                || player.isControlLocked()
                || player.isObjectMappingFrameControl();
    }

    private void updateCameraLock(AbstractPlayableSprite mainPlayer) {
        if (riding[PLAYER_SLOT_MAIN] || riding[PLAYER_SLOT_SIDEKICK] || cameraLockTimer <= 0 || mainPlayer == null) {
            return;
        }

        Camera camera = services().camera();
        cameraLockTimer--;
        if (cameraLockTimer == 0) {
            camera.setMinX((short) CAMERA_RELEASE_MIN_X);
            camera.setMaxX((short) CAMERA_RELEASE_MAX_X);
            return;
        }

        if (camera.getMinX() != CAMERA_RELEASE_MIN_X) {
            if (mainPlayer.getCentreX() >= 0x2D00) {
                camera.setMinX((short) CAMERA_RELEASE_MIN_X);
            } else {
                camera.setMinX((short) (camera.getMinX() - 4));
            }
        }

        if (camera.getMaxX() != CAMERA_RELEASE_MAX_X) {
            if (mainPlayer.getCentreX() < 0x2D00) {
                camera.setMaxX((short) CAMERA_RELEASE_MAX_X);
            } else {
                camera.setMaxX((short) (camera.getMaxX() + 4));
            }
        }
    }

    /**
     * Obj_AIZ1TreeRevealControl parity shim.
     * Tracks the Events_fg_4 counter used by AIZ terrain reveal scripting.
     */
    // Package-private so session-level rewind tests can name the type directly.
    // The parent only spawns this control shim on the one-shot first-capture
    // transition, so a held rewind must recreate it.
    static final class AizTreeRevealControlObjectInstance extends AbstractObjectInstance
            implements SpawnCoordinateRewindRecreatable {
        // Mirrors object RAM word $2E (with low byte at $2F used for odd/even gating).
        private int timer2EWord;

        private AizTreeRevealControlObjectInstance() {
            this(0, 0);
        }

        AizTreeRevealControlObjectInstance(int x, int y) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "AIZ1TreeRevealControl");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (timer2EWord != 0 && eventsFg4 == 0) {
                setDestroyed(true);
                return;
            }

            timer2EWord = (timer2EWord - 1) & 0xFFFF;
            if (player == null) {
                return;
            }

            // Disasm parity:
            // move.w #$480,d0 / sub.w (Player_1+y_pos).w,d0 / lsr.w #3,d0 / addq.w #3,d0
            int target = (0x480 - (player.getCentreY() & 0xFFFF)) & 0xFFFF;
            target = (target >>> 3) + 3;
            if (Integer.compareUnsigned(target, eventsFg4) < 0 && (timer2EWord & 1) == 0) {
                return;
            }
            eventsFg4++;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Logic-only control object.
        }
    }
}
