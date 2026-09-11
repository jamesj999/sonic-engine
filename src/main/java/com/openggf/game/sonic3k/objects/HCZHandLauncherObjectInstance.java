package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugColor;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.playable.Tails;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x3A - HCZ Hand Launcher (Hydrocity Zone).
 */
public class HCZHandLauncherObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_HCZHandLauncher} is installed from the S3K object pointer table at
     * {@code $00030AD0} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:65735).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0003}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0003;
    }


    private static final Logger LOG = Logger.getLogger(HCZHandLauncherObjectInstance.class.getName());

    private static final String ART_KEY = Sonic3kObjectArtKeys.HCZ_HAND_LAUNCHER;
    private static final int PRIORITY_NORMAL = 4;
    private static final int PRIORITY_GRABBED = 1;
    private static final int PRIORITY_CHILD = 5;

    private static final int FRAME_ARM_EXTENDED = 6;
    private static final int FRAME_GRABBED = 7;
    private static final int CHILD_FRAME_COUNT = 6;

    private static final int Y_OFFSET_REST = 0x50;
    private static final int Y_OFFSET_GRAB = 0x18;
    private static final int Y_OFFSET_TOP = 0x00;
    private static final int ARM_SPEED = 8;

    private static final int SOLID_HALF_WIDTH = 0x20;
    private static final int SOLID_HALF_HEIGHT = 0x11;

    private static final int DETECT_HALF_WIDTH = 0x20;
    private static final int GRAB_X_OFFSET = 8;
    private static final int GRAB_X_RANGE = 16;

    private static final int TIMER_PRE_GRAB = 19;
    private static final int TIMER_PRE_LAUNCH = 59;

    private static final int LAUNCH_X_VEL = 0x1000;
    private static final int LAUNCH_GROUND_VEL = 0x1000;
    private static final int ESCAPE_X_VEL = 0x800;
    private static final int ESCAPE_Y_VEL = -0x400;
    private static final int ESCAPE_GROUND_VEL = 0x800;
    private static final int GRAB_GROUND_VEL = 0x1000;
    private static final int GRAB_X_SNAP_OFFSET = 2;
    private static final int GRAB_Y_RADIUS_DEFAULT = 0x13;
    private static final int GRAB_Y_RADIUS_TAILS = 0x0F;
    private static final int GRAB_X_RADIUS = 9;

    private enum State { IDLE, LAUNCHING }

    private int baseX;
    private int baseY;
    private boolean facingLeft;

    private State state = State.IDLE;
    private int yOffset;
    private int timer;
    private boolean anyGrabbed;
    private final boolean[] playerGrabbed = new boolean[2];
    private final boolean[] playerStandingCheckpoint = new boolean[2];
    private int currentY;
    private int mappingFrame = FRAME_ARM_EXTENDED;
    private int priority = PRIORITY_NORMAL;
    private HandLauncherArmChild armChild;
    private boolean childSpawned;
    private boolean solidActive = true;

    public HCZHandLauncherObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZHandLauncher");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.facingLeft = (spawn.renderFlags() & 1) != 0;
        this.yOffset = Y_OFFSET_REST;
        this.currentY = baseY + yOffset;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!childSpawned) {
            childSpawned = true;
            try {
                armChild = spawnChild(() -> new HandLauncherArmChild(
                        new ObjectSpawn(baseX, baseY, Sonic3kObjectIds.HCZ_HAND_LAUNCHER,
                                0, spawn.renderFlags(), false, 0),
                        this));
            } catch (Exception e) {
                LOG.warning("Failed to spawn arm child: " + e.getMessage());
            }
        }

        AbstractPlayableSprite player = (playerEntity instanceof AbstractPlayableSprite sprite)
                ? sprite : null;

        int prevY = currentY;
        switch (state) {
            case IDLE -> updateIdle(player);
            case LAUNCHING -> updateLaunching(player);
        }

        currentY = baseY + yOffset;

        int deltaY = currentY - prevY;
        if (deltaY != 0 && anyGrabbed) {
            repositionGrabbedPlayers(player, deltaY);
        }
        refreshStandingCheckpoint(player);
    }

    private void repositionGrabbedPlayers(AbstractPlayableSprite player, int deltaY) {
        NativePlayerSlots slots = nativePlayerSlots(player);
        for (int pi = 0; pi < playerGrabbed.length; pi++) {
            AbstractPlayableSprite grabbedPlayer = slots.player(pi);
            if (playerGrabbed[pi] && grabbedPlayer != null) {
                grabbedPlayer.setY((short) (grabbedPlayer.getY() + deltaY));
            }
        }
    }

    private void updateIdle(AbstractPlayableSprite player) {
        boolean playerInRange = false;
        NativePlayerSlots slots = nativePlayerSlots(player);
        for (int pi = 0; pi < playerGrabbed.length; pi++) {
            AbstractPlayableSprite candidate = slots.player(pi);
            if (candidate != null && isPlayerInHorizontalRange(candidate)) {
                playerInRange = true;
                break;
            }
        }

        if (playerInRange) {
            if (anyGrabbed) {
                mappingFrame = FRAME_GRABBED;
                priority = PRIORITY_GRABBED;

                if (timer > 0) {
                    timer--;
                    processButtonCheckAllPlayers(player);
                } else if (yOffset > Y_OFFSET_TOP) {
                    yOffset -= ARM_SPEED;
                    processButtonCheckAllPlayers(player);
                } else {
                    state = State.LAUNCHING;
                    timer = TIMER_PRE_LAUNCH;
                    processButtonCheckAllPlayers(player);
                }
            } else {
                timer = TIMER_PRE_GRAB;
                mappingFrame = FRAME_ARM_EXTENDED;
                priority = PRIORITY_NORMAL;

                if (yOffset > Y_OFFSET_GRAB) {
                    yOffset -= ARM_SPEED;
                } else {
                    processButtonCheckAllPlayers(player);
                }
            }
        } else if (yOffset < Y_OFFSET_REST) {
            yOffset += ARM_SPEED;
        }

        solidActive = true;
    }

    private void updateLaunching(AbstractPlayableSprite player) {
        if (timer > 0) {
            timer--;
            processButtonCheckAllPlayers(player);
        } else if (yOffset == Y_OFFSET_REST) {
            anyGrabbed = false;
            state = State.IDLE;
            playSfx(Sonic3kSfx.DASH.id);
        } else {
            if (yOffset == Y_OFFSET_GRAB) {
                launchReleaseAllPlayers(player);
                mappingFrame = FRAME_ARM_EXTENDED;
                priority = PRIORITY_NORMAL;
            }
            yOffset += ARM_SPEED;
        }

        solidActive = (yOffset <= Y_OFFSET_GRAB);
    }

    private void processButtonCheckAllPlayers(AbstractPlayableSprite player) {
        NativePlayerSlots slots = nativePlayerSlots(player);
        for (int pi = 0; pi < playerGrabbed.length; pi++) {
            AbstractPlayableSprite candidate = slots.player(pi);
            if (candidate != null) {
                processButtonCheckForPlayer(candidate, pi, playerStandingCheckpoint[pi]);
            }
        }
    }

    private void processButtonCheckForPlayer(
            AbstractPlayableSprite player,
            int pi,
            boolean standingBeforeCurrentSolidPass) {
        if (pi >= playerGrabbed.length) {
            return;
        }

        if (playerGrabbed[pi]) {
            if (player.isJumpPressed()) {
                escapePlayer(player, pi);
            }
            return;
        }

        if (!standingBeforeCurrentSolidPass) {
            return;
        }

        if (!anyGrabbed) {
            int dx = player.getCentreX() + GRAB_X_OFFSET - baseX;
            if (dx < 0 || dx >= GRAB_X_RANGE) {
                return;
            }
        }

        if (player.isObjectControlled() || player.isDebugMode()) {
            return;
        }

        grabPlayer(player, pi);
    }

    private void grabPlayer(AbstractPlayableSprite player, int pi) {
        playerGrabbed[pi] = true;
        anyGrabbed = true;

        playSfx(Sonic3kSfx.ROLL.id);
        player.setAnimationId(0);
        if (player.getRolling()) {
            player.setRolling(false);
        }

        int yRadius = (player instanceof Tails) ? GRAB_Y_RADIUS_TAILS : GRAB_Y_RADIUS_DEFAULT;
        player.applyCustomRadii(GRAB_X_RADIUS, yRadius);
        // ROM writes object_control=1: movement is suppressed, but the signed
        // bit-7 gates used by CPU/touch/solid helpers remain clear.
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setPushing(false);

        int snapX = facingLeft ? baseX + GRAB_X_SNAP_OFFSET : baseX - GRAB_X_SNAP_OFFSET;
        // ROM move.w writes x_pos only and preserves the incoming subpixel word.
        NativePositionOps.writeXPosPreserveSubpixel(player, snapX);

        int gVel = facingLeft ? -GRAB_GROUND_VEL : GRAB_GROUND_VEL;
        player.setGSpeed((short) gVel);
        player.setDirection(facingLeft ? Direction.LEFT : Direction.RIGHT);
    }

    private void escapePlayer(AbstractPlayableSprite player, int pi) {
        playerGrabbed[pi] = false;

        int xDir = facingLeft ? -1 : 1;
        player.setGSpeed((short) (ESCAPE_GROUND_VEL * xDir));
        player.setXSpeed((short) (ESCAPE_X_VEL * xDir));
        player.setYSpeed((short) ESCAPE_Y_VEL);
        ObjectControlState.none().applyTo(player);
        player.setOnObject(false);
        clearEngineRidingObject(player);
        player.setAir(true);

        if (!playerGrabbed[0] && !playerGrabbed[1]) {
            anyGrabbed = false;
        }
    }

    private void launchReleaseAllPlayers(AbstractPlayableSprite player) {
        NativePlayerSlots slots = nativePlayerSlots(player);
        for (int pi = 0; pi < playerGrabbed.length; pi++) {
            AbstractPlayableSprite candidate = slots.player(pi);
            if (candidate != null) {
                launchReleasePlayer(candidate, pi, playerStandingCheckpoint[pi]);
            }
        }
    }

    private void launchReleasePlayer(
            AbstractPlayableSprite player,
            int pi,
            boolean standingBeforeCurrentSolidPass) {
        if (pi >= playerGrabbed.length) {
            return;
        }

        if (playerGrabbed[pi]) {
            playerGrabbed[pi] = false;

            int xDir = facingLeft ? -1 : 1;
            player.setGSpeed((short) (LAUNCH_GROUND_VEL * xDir));
            player.setXSpeed((short) (LAUNCH_X_VEL * xDir));
            player.setYSpeed((short) 0);
            player.setAnimationId(0);
            ObjectControlState.none().applyTo(player);
            player.setOnObject(false);
            clearEngineRidingObject(player);
            return;
        }

        if (standingBeforeCurrentSolidPass) {
            player.setOnObject(false);
            clearEngineRidingObject(player);
            player.setAir(true);
        }
    }

    private void refreshStandingCheckpoint(AbstractPlayableSprite player) {
        if (!solidActive) {
            playerStandingCheckpoint[0] = false;
            playerStandingCheckpoint[1] = false;
            return;
        }
        SolidCheckpointBatch batch = checkpointAll();
        NativePlayerSlots slots = nativePlayerSlots(player);
        for (int pi = 0; pi < playerStandingCheckpoint.length; pi++) {
            AbstractPlayableSprite candidate = slots.player(pi);
            PlayerSolidContactResult result = candidate != null
                    ? batch.perPlayer().get(candidate) : null;
            playerStandingCheckpoint[pi] = result != null && result.standingNow();
        }
    }

    private void clearEngineRidingObject(AbstractPlayableSprite player) {
        var objectManager = services().objectManager();
        if (objectManager != null) {
            objectManager.clearRidingObject(player);
        }
    }

    private boolean isPlayerInHorizontalRange(PlayableEntity player) {
        int leftEdge = baseX - DETECT_HALF_WIDTH;
        int dx = (player.getCentreX() - leftEdge) & 0xFFFF;
        return dx < (DETECT_HALF_WIDTH * 2);
    }

    private NativePlayerSlots nativePlayerSlots(AbstractPlayableSprite updatePlayer) {
        ObjectPlayerQuery query = services().playerQuery();
        PlayableEntity main = query.mainPlayerOrNull();
        if (!(main instanceof AbstractPlayableSprite) && updatePlayer != null) {
            main = updatePlayer;
        }

        PlayableEntity nativeP2 = null;
        for (PlayableEntity candidate : query.playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (candidate != main && candidate instanceof AbstractPlayableSprite) {
                nativeP2 = candidate;
                break;
            }
        }
        AbstractPlayableSprite p1 = (main instanceof AbstractPlayableSprite sp) ? sp : null;
        AbstractPlayableSprite p2 = (nativeP2 instanceof AbstractPlayableSprite sp && sp != p1) ? sp : null;
        return new NativePlayerSlots(p1, p2);
    }

    private record NativePlayerSlots(AbstractPlayableSprite p1, AbstractPlayableSprite p2) {
        private AbstractPlayableSprite player(int slot) {
            return switch (slot) {
                case 0 -> p1;
                case 1 -> p2;
                default -> null;
            };
        }
    }

    private void playSfx(int sfxId) {
        if (isOnScreen()) {
            try {
                services().playSfx(sfxId);
            } catch (Exception e) {
                // Ignore audio failures.
            }
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // Obj_HCZHandLauncher passes d3=$11 to both the fresh landing and
        // continued-ride paths; unlike SolidObjectFull callers, it does not
        // supply a separate +1 ground height (sonic3k.asm:65798-65802).
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // SolidObjectTop reaches loc_1E45A for a fresh launcher landing. Its
        // unsigned cmpi.w #-$10,d0 / blo rejects d0 == 0 and accepts only the
        // negative overlap band [-$10,-1] (sonic3k.asm:42048-42068).
        return true;
    }

    @Override
    public boolean usesPlatformObjectLandingSnap() {
        // Obj_HCZHandLauncher calls SolidObjectTop, whose fresh-contact path
        // reaches loc_1E45A/sub_1E410 and keeps its relative
        // playerY-distY+3 result. It does not run PlatformObject_ChkYRange's
        // absolute surface snap (sonic3k.asm:65818-65827, 41982-42068).
        return false;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return solidActive && (!player.isObjectControlled()
                || (player instanceof AbstractPlayableSprite sprite
                        && sprite.isObjectControlAllowsCpu()));
    }

    @Override
    public boolean allowsObjectControlledSolidContacts() {
        // MvSonicOnPtfm rejects only signed bit 7; the launcher's native
        // object_control=1 capture remains on the ordinary continued-ride path.
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        // Manual checkpoints drive standing reads directly.
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, baseX, currentY, facingLeft, false);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        float r = anyGrabbed ? 0.0f : 0.3f;
        float g = anyGrabbed ? 1.0f : 0.8f;
        float b = anyGrabbed ? 0.0f : 1.0f;

        int left = baseX - SOLID_HALF_WIDTH;
        int right = baseX + SOLID_HALF_WIDTH;
        int top = currentY - SOLID_HALF_HEIGHT;
        int bottom = currentY + SOLID_HALF_HEIGHT;
        ctx.drawLine(left, top, right, top, r, g, b);
        ctx.drawLine(left, bottom, right, bottom, r, g, b);
        ctx.drawLine(left, top, left, bottom, r, g, b);
        ctx.drawLine(right, top, right, bottom, r, g, b);

        ctx.drawCross(baseX, currentY, 4, 0.5f, 0.5f, 0.5f);
        ctx.drawLine(baseX - DETECT_HALF_WIDTH, currentY - 2,
                baseX + DETECT_HALF_WIDTH, currentY - 2, 0.2f, 0.2f, 0.6f);

        StringBuilder sb = ctx.getLabelBuilder();
        sb.append("Hand ");
        sb.append(state == State.LAUNCHING ? "LAUNCH" : "IDLE");
        sb.append(" y=").append(yOffset);
        sb.append(" t=").append(timer);
        if (anyGrabbed) {
            sb.append(" [GRAB]");
        }
        if (facingLeft) {
            sb.append(" <L");
        }
        ctx.drawWorldLabel(baseX, currentY - 20, 0, sb.toString(), DebugColor.CYAN);
    }

    @Override
    public int getX() {
        return baseX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(priority);
    }

    int getYOffset() {
        return yOffset;
    }

    boolean isFacingLeft() {
        return facingLeft;
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    void rewindAttachArmChild(HandLauncherArmChild child) {
        armChild = child;
    }

    public static class HandLauncherArmChild extends AbstractObjectInstance implements RewindRecreatable {
        private final HCZHandLauncherObjectInstance parent;
        private int currentFrame;

        public HandLauncherArmChild(ObjectSpawn spawn, HCZHandLauncherObjectInstance parent) {
            super(spawn, "HCZHandLauncherArm");
            this.parent = parent;
            this.currentFrame = 0;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            HCZHandLauncherObjectInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                    ctx, HCZHandLauncherObjectInstance.class);
            if (liveParent == null) {
                return null;
            }
            HandLauncherArmChild restored = new HandLauncherArmChild(ctx.spawn(), liveParent);
            liveParent.rewindAttachArmChild(restored);
            return restored;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()
                    || !services().objectManager().isActiveObjectInstance(parent)) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            if (parent.getYOffset() > Y_OFFSET_GRAB) {
                return;
            }

            currentFrame++;
            if (currentFrame >= CHILD_FRAME_COUNT) {
                currentFrame = 0;
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (parent.getYOffset() > Y_OFFSET_GRAB) {
                return;
            }

            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer != null) {
                int renderY = parent.getY();
                renderer.drawFrameIndex(currentFrame, parent.getX(), renderY,
                        parent.isFacingLeft(), false);
            }
        }

        @Override
        public int getX() {
            return parent.getX();
        }

        @Override
        public int getY() {
            return parent.getY();
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY_CHILD);
        }

        @Override
        public boolean isPersistent() {
            return !parent.isDestroyed();
        }
    }
}
