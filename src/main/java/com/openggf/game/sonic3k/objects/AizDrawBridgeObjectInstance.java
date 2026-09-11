package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * AIZ draw bridge used by the AIZ2 boss-end cutscene.
 *
 * <p>ROM reference: Obj_AIZDrawBridge. The ROM object uses child multisprites;
 * this version keeps the same sequence timing and renders the repeated segments
 * directly from the parent for simplicity.
 */
public class AizDrawBridgeObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_AIZDrawBridge} is installed from the S3K object pointer table at
     * {@code $0002B12A} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:59495).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }


    private static final int PRIORITY = 5;
    private static final int SEGMENT_COUNT = 14;
    private static final int SEGMENT_SPACING = 16;
    private static final int HALF_WIDTH = 0x6B;
    private static final int HEIGHT = 8;
    private static final int DROP_DISTANCE = 0x68;
    private static final int COLLAPSE_DELAY = 0x0E;
    private static final int[] FALL_DELAYS = {8, 0x10, 0x0C, 0x0E, 6, 0x0A, 4, 2, 8, 0x10, 0x0C, 0x0E, 6, 0x0A};

    private int pivotX;
    private int pivotY;
    private boolean xFlip;
    private boolean reverseVertical;
    private int settledAngle;
    private boolean cutsceneOverride;

    private int currentX;
    private int currentY;
    private int angle;
    private int angleStep;
    private boolean dropStarted;
    private boolean settled;
    private boolean settledAngleReached;
    private boolean collapseStarted;
    private int collapseTimer;

    private final List<PlayableEntity> standingPlayers = new ArrayList<>(2);
    private final int[] pieceX = new int[SEGMENT_COUNT];
    private final int[] pieceY = new int[SEGMENT_COUNT];

    public AizDrawBridgeObjectInstance(ObjectSpawn spawn) {
        this(spawn, false);
    }

    private AizDrawBridgeObjectInstance(ObjectSpawn spawn, boolean cutsceneOverride) {
        super(spawn, "AIZDrawBridge");
        this.pivotX = spawn.x();
        this.pivotY = spawn.y();
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.reverseVertical = (spawn.renderFlags() & 0x02) != 0;
        this.cutsceneOverride = cutsceneOverride;
        this.angle = reverseVertical ? 0x40 : -0x40;
        // $38 starts at +/-$40 and $34 advances toward either $00 or $80.
        // Which endpoint is reached depends on both status direction bits.
        this.settledAngle = reverseVertical ^ xFlip ? 0x80 : 0;
        this.angleStep = xFlip ? -2 : 2;
        this.currentX = pivotX;
        this.currentY = pivotY + (reverseVertical ? DROP_DISTANCE : -DROP_DISTANCE);
        updateBridgePieces();
    }

    public static AizDrawBridgeObjectInstance createCutsceneOverride() {
        AizDrawBridgeObjectInstance bridge = new AizDrawBridgeObjectInstance(
                new ObjectSpawn(0x4B48, 0x0218, 0x32, 0, 1, false, 0), true);
        // This replacement stands in for the layout object that has already
        // consumed _unkFAA3 and completed its rotation before loc_694AA creates
        // the capsule. Preserve that live ROM routine state instead of replaying
        // the drop from object init.
        bridge.dropStarted = true;
        bridge.settled = true;
        bridge.settledAngleReached = true;
        bridge.angle = bridge.settledAngle;
        bridge.currentX = bridge.pivotX - DROP_DISTANCE;
        bridge.currentY = bridge.pivotY;
        bridge.updateBridgePieces();
        return bridge;
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
        // Normal/wait routines reach AIZDrawBridge_Solid's range tail, but
        // loc_2B452 only counts down then deletes (sonic3k.asm:59769-59791).
        // The cutscene replacement represents a layout owner that was already
        // live and settled when folded out of the native SST graph. Keep it
        // alive until the button starts loc_2B452; otherwise the dynamic object
        // cannot respawn after the ordinary range tail removes it.
        return (cutsceneOverride || collapseStarted) && !isDestroyed();
    }

    @Override
    public boolean checksOutOfRangeAfterRoutine() {
        // AIZDrawBridge_WaitCollapseTrigger consumes _unkFAA9 before the
        // normal Solid/range tail. If collapse begins this dispatch, the new
        // loc_2B452 operation must already suppress that tail.
        return true;
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        // $30(a0) keeps the pivot x_pos while the displayed bridge moves.
        // The ROM compares it with Camera_X_pos_coarse_back at fixed $280.
        int coarseBack = (cameraX - 0x80) & 0xFF80;
        int distance = ((pivotX & 0xFF80) - coarseBack) & 0xFFFF;
        return distance > 0x280;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isHighPriority() {
        // This engine flag means "above every terrain pixel". Keep the bridge
        // tile-occluded so priority terrain masks it, with the palette mask
        // below preserving its position in front of the waterfall.
        return false;
    }

    @Override
    public int getTileOcclusionPaletteMask() {
        // The ROM arena's palette-3 high-priority pixels are the waterfall;
        // palette 0-2 high-priority pixels are foreground terrain.
        return 0b0111;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HEIGHT, HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Obj_AIZDrawBridge calls SolidObjectFull2, not SolidObjectTop
        // (sonic3k.asm:59625-59643). New top landings therefore narrow
        // d1=$6B back to width_pixels=$60 before RideObject_SetRide.
        return false;
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // SolidObjectFull2_1P falls through to SolidObject_cont without the
        // SolidObject_OnScreenTest used by SolidObjectFull_1P.
        return true;
    }

    @Override
    public boolean suppressesObjectEdgeBalance() {
        // Obj_AIZDrawBridge keeps status bit 7 set (ori.b #$80,status).
        // Sonic_Move tests that bit before reading width_pixels and skips the
        // object-edge balance/facing branch while the player rides it.
        return true;
    }

    @Override
    public boolean allowsObjectControlledSolidContacts() {
        return cutsceneOverride;
    }

    @Override
    public boolean rejectsBit7ObjectControlNewSolidContact(PlayableEntity player) {
        // Set_PlayerEndingPose runs after the bridge's ROM solid pass and does
        // not clear the standing bits it just observed.
        return !cutsceneOverride;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        // ROM loc_2B2E8 still falls through to SolidObjectFull2 while the
        // collapse delay counts down. Player support ends only when loc_2B45E
        // ejects the standing players and deletes the parent object.
        return settled && !isDestroyed();
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (contact.standing() && !standingPlayers.contains(player)) {
            standingPlayers.add(player);
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!cutsceneOverride && Aiz2BossEndSequenceState.isCutsceneOverrideObjectsActive()) {
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }

        if (!dropStarted && Aiz2BossEndSequenceState.isBridgeDropTriggered()) {
            dropStarted = true;
            currentX = pivotX + (xFlip ? -DROP_DISTANCE : DROP_DISTANCE);
            currentY = pivotY;
            services().playSfx(Sonic3kSfx.FLIP_BRIDGE.id);
        }

        if (dropStarted && !settled) {
            if (settledAngleReached) {
                // Obj_AIZDrawBridge checks $38 for $80/0 before adding $34; the
                // flat/full SolidObjectFull2 phase starts on the next routine
                // entry after the angle reaches its target (sonic3k.asm:59591-59613, 59625-59643).
                settled = true;
                services().playSfx(Sonic3kSfx.FLIP_BRIDGE.id);
            } else {
                angle += angleStep;
                if ((angleStep > 0 && angle >= settledAngle) || (angleStep < 0 && angle <= settledAngle)) {
                    angle = settledAngle;
                    settledAngleReached = true;
                }
                updateBridgePieces();
            }
        }

        if (settled && !collapseStarted && Aiz2BossEndSequenceState.isButtonPressed()) {
            collapseStarted = true;
            collapseTimer = COLLAPSE_DELAY;
            spawnFallingSegments();
            services().playSfx(Sonic3kSfx.BRIDGE_COLLAPSE.id);
            // loc_2B2E8 initializes $34, creates the falling pieces through
            // loc_2B498, and returns. loc_2B452 first decrements on the next
            // object entry (sonic3k.asm:59614-59623,59764-59791).
            return;
        }

        if (collapseStarted) {
            if (collapseTimer > 0) {
                collapseTimer--;
                // ROM keeps the standing bits alongside the newly-set air/roll
                // state until the delayed parent deletion ejects both players.
                for (PlayableEntity standingPlayer : standingPlayers) {
                    standingPlayer.setOnObject(true);
                }
            } else {
                ejectStandingPlayers();
                ObjectLifetimeOps.deleteNoRespawn(this);
            }
        }
    }

    private void updateBridgePieces() {
        double radians = (angle * Math.PI * 2.0) / 256.0;
        double stepX = Math.cos(radians) * SEGMENT_SPACING;
        double stepY = Math.sin(radians) * SEGMENT_SPACING;
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            pieceX[i] = pivotX + (int) Math.round(stepX * i);
            pieceY[i] = pivotY + (int) Math.round(stepY * i);
        }
    }

    private void spawnFallingSegments() {
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            final int px = pieceX[i], py = pieceY[i], delay = FALL_DELAYS[i];
            spawnChild(() -> new FallingBridgeSegment(px, py, delay));
        }
    }

    private void ejectStandingPlayers() {
        for (PlayableEntity player : List.copyOf(standingPlayers)) {
            player.setOnObject(false);
            player.setPushing(false);
            player.setAir(true);
            ObjectManager objectManager = services().objectManager();
            if (objectManager != null) {
                objectManager.clearRidingObject(player);
            }
            if (player instanceof AbstractPlayableSprite sprite) {
                // loc_2B45E/loc_2B478 run after both player animation slots and
                // write anim=$1B immediately while retaining the displayed
                // mapping selected earlier in the frame.
                sprite.setAnimationId(Sonic3kAnimationIds.HURT_FALL);
                // Use forcedAnimationId so the normal animation system doesn't
                // overwrite HURT_FALL on the next frame based on movement state.
                sprite.setForcedAnimationId(Sonic3kAnimationIds.HURT_FALL);
            }
        }
        standingPlayers.clear();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (collapseStarted) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.AIZ_DRAW_BRIDGE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            renderer.drawFrameIndex(1, pieceX[i], pieceY[i], false, false);
        }
    }

    private static final class FallingBridgeSegment extends AbstractObjectInstance implements SpawnRewindRecreatable {
        private int x;
        private int y;
        private int delay;
        private final SubpixelMotion.State motion;

        private FallingBridgeSegment(int x, int y, int delay) {
            super(new ObjectSpawn(x, y, 0x32, 0, 0, false, delay), "AIZDrawBridgeSegment");
            this.x = x;
            this.y = y;
            this.delay = delay;
            this.motion = new SubpixelMotion.State(x, y, 0, 0, 0, 0);
        }

        private FallingBridgeSegment(ObjectSpawn spawn) {
            super(spawn, "AIZDrawBridgeSegment");
            this.x = spawn.x();
            this.y = spawn.y();
            this.delay = spawn.rawYWord();
            this.motion = new SubpixelMotion.State(x, y, 0, 0, 0, 0);
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
        public boolean isPersistent() {
            return !isDestroyed();
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY);
        }

        @Override
        public boolean isHighPriority() {
            return false;
        }

        @Override
        public int getTileOcclusionPaletteMask() {
            return 0b0111;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (delay > 0) {
                delay--;
                return;
            }
            SubpixelMotion.objectFall(motion, 0x38);
            x = motion.x;
            y = motion.y;
            if (!isOnScreen(128)) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.AIZ_DRAW_BRIDGE);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(1, x, y, false, false);
        }
    }
}
