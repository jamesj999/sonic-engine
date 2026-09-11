package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x2A - Cork Floor (Sonic 3 & Knuckles).
 */
public class CorkFloorObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SlopedSolidProvider, SolidObjectListener,
        RomObjectCodePointerProvider, RewindRecreatable {

    private static final Logger LOG = Logger.getLogger(CorkFloorObjectInstance.class.getName());

    private static final int FRAGMENT_GRAVITY = 0x18;
    private static final int PRIORITY = 5;
    private static final int ROLL_BREAK_LAUNCH_YVEL = -0x300;
    /** {@code byte_2A894}, sampled by ICZ's {@code sub_1DDC6} path. */
    private static final byte[] ICZ_SLOPE_DATA = {
            0x23, 0x23, 0x22, 0x22, 0x21, 0x21, 0x20, 0x1F,
            0x1F, 0x1E, 0x1E, 0x1D, 0x1D, 0x1C, 0x1B, 0x1B,
            0x1A, 0x1A, 0x19, 0x19, 0x17, 0x16, 0x15, 0x15,
            0x14, 0x14, 0x13, 0x13
    };
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS;

    @Override
    public int romObjectCodePointerHighWord() {
        // Obj_CorkFloor is installed at 0x0002A618 in the S&K-side ROM.
        // Tails_CPU_interact stores word 0 of the stood-on object SST
        // (docs/skdisasm/sonic3k.asm:26816-26843).
        return 0x0002;
    }

    private static final int[][] VEL_TABLE_SMALL = {
            {-0x200, -0x200}, {0x200, -0x200},
            {-0x100, -0x100}, {0x100, -0x100},
    };

    private static final int[][] VEL_TABLE_MEDIUM = {
            {-0x100, -0x200}, {0x100, -0x200},
            {-0x0E0, -0x1C0}, {0x0E0, -0x1C0},
            {-0x0C0, -0x180}, {0x0C0, -0x180},
            {-0x0A0, -0x140}, {0x0A0, -0x140},
            {-0x080, -0x100}, {0x080, -0x100},
            {-0x060, -0x0C0}, {0x060, -0x0C0},
    };

    private static final int[][] VEL_TABLE_LARGE = {
            {-0x400, -0x400}, {-0x200, -0x400}, {0x200, -0x400}, {0x400, -0x400},
            {-0x3C0, -0x3C0}, {-0x1C0, -0x3C0}, {0x1C0, -0x3C0}, {0x3C0, -0x3C0},
            {-0x380, -0x380}, {-0x180, -0x380}, {0x180, -0x380}, {0x380, -0x380},
            {-0x340, -0x340}, {-0x140, -0x340}, {0x140, -0x340}, {0x340, -0x340},
    };

    private record ZoneConfig(
            String artKey,
            int halfWidth,
            int halfHeight,
            int[][] velTable,
            boolean iczPlaneMode
    ) {}

    private static final ZoneConfig AIZ1_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.CORK_FLOOR_AIZ1, 0x10, 0x28, VEL_TABLE_MEDIUM, false);
    private static final ZoneConfig AIZ2_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.CORK_FLOOR_AIZ2, 0x10, 0x2C, VEL_TABLE_MEDIUM, false);
    private static final ZoneConfig CNZ_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.CORK_FLOOR_CNZ, 0x20, 0x20, VEL_TABLE_LARGE, false);
    private static final ZoneConfig FBZ_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.CORK_FLOOR_FBZ, 0x10, 0x10, VEL_TABLE_SMALL, false);
    private static final ZoneConfig ICZ_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.CORK_FLOOR_ICZ, 0x10, 0x24, VEL_TABLE_MEDIUM, true);
    private static final ZoneConfig ICZ_SMALL_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.CORK_FLOOR_ICZ, 0x10, 0x10, VEL_TABLE_SMALL, false);
    private static final ZoneConfig LBZ_CONFIG = new ZoneConfig(
            Sonic3kObjectArtKeys.CORK_FLOOR_LBZ, 0x20, 0x20, VEL_TABLE_LARGE, false);

    private enum Mode {
        BREAK_FROM_BELOW,
        ROLL_TO_BREAK,
        ICZ_PLANE_SWITCH
    }

    private final ZoneConfig config;
    private Mode mode;
    private int subtype;
    private boolean hFlip;
    private int mappingFrame;
    private int x;
    private int y;
    private final int[][] effectiveVelTable;

    private boolean broken;
    private boolean playerStanding;
    private int savedPreContactYSpeed;
    private boolean savedPreContactRolling;
    private AbstractPlayableSprite rollingBreakPlayer;

    public CorkFloorObjectInstance(ObjectSpawn spawn) {
        super(spawn, "CorkFloor");
        this.x = spawn.x();
        this.y = spawn.y();
        this.subtype = spawn.subtype() & 0xFF;
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        this.config = resolveConfig(subtype);

        if (config.iczPlaneMode && (subtype & 0x10) == 0) {
            this.mode = Mode.ICZ_PLANE_SWITCH;
        } else if (subtype == 0) {
            this.mode = Mode.BREAK_FROM_BELOW;
        } else {
            this.mode = Mode.ROLL_TO_BREAK;
        }

        this.mappingFrame = config.iczPlaneMode ? (subtype & 0x0F) * 2 : 0;
        this.effectiveVelTable = config.iczPlaneMode && (subtype & 0x10) != 0
                ? VEL_TABLE_SMALL
                : config.velTable;
    }

    @Override
    public CorkFloorObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(
                ctx.objectServices(),
                () -> new CorkFloorObjectInstance(ctx.spawn()));
    }

    public boolean isBroken() {
        return broken;
    }

    public void forceBreakForTest() {
        broken = true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        if (mode == Mode.ICZ_PLANE_SWITCH) {
            // loc_2A6D4 passes only d2=height_pixels ($24) to sub_1DDC6;
            // unlike SolidObjectFull, there is no grounded d3=d2+1 surface.
            return SolidObjectParams.of(config.halfWidth + 0x0B, config.halfHeight, config.halfHeight);
        }
        return SolidObjectParams.of(config.halfWidth + 0x0B, config.halfHeight, config.halfHeight + 1);
    }

    @Override
    public byte[] getSlopeData() {
        // Only ICZ subtype bit 4 clear installs loc_2A6D4, which calls the
        // sloped full-solid helper. Every other variant calls SolidObjectFull.
        return mode == Mode.ICZ_PLANE_SWITCH ? ICZ_SLOPE_DATA : null;
    }

    @Override
    public boolean isSlopeFlipped() {
        return hFlip;
    }

    @Override
    public int getSlopeBaseline() {
        // sub_1DDC6 loc_1DECE subtracts byte_2A894[0] on fresh contact.
        return ICZ_SLOPE_DATA[0];
    }

    @Override
    public boolean addsSlopeCatchRangeToVerticalOverlap() {
        // sub_1DDC6 enters loc_1DECE with d2=height_pixels, then adds the
        // player's y_radius before classifying the sampled surface.
        return mode == Mode.ICZ_PLANE_SWITCH;
    }

    @Override
    public boolean forceAirOnRideExit() {
        // ICZ's sloped helper has a deliberately different continued-ride
        // exit from SolidObjectFull: sub_1DDC6/loc_1DE00 clears Status_OnObj
        // and the object's standing bit but does not set Status_InAir
        // (sonic3k.asm:41221-41264). This lets the next Player_AnglePos hand
        // the rider directly to terrain beneath the cork floor. Other cork
        // variants use SolidObjectFull and retain its ordinary airborne exit.
        return mode != Mode.ICZ_PLANE_SWITCH;
    }

    @Override
    public int getBalanceWidthPixels() {
        // Sonic_Move reads the object's width_pixels byte, not the default
        // 16-pixel render width nor SolidObjectFull's +$B side extension.
        return config.halfWidth;
    }

    @Override
    public boolean isTopSolidOnly() {
        return false;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        return !broken;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Manual checkpoints drive the current-frame contact state from update().
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (broken) {
            return;
        }

        playerStanding = false;
        savedPreContactRolling = false;
        rollingBreakPlayer = null;
        // Per-frame scratch: the riders SolidObjectFull reports standing on
        // this floor during THIS update, in participation order. It is a local
        // so it carries no state across frames and needs no rewind capture.
        List<AbstractPlayableSprite> standingRiders = new ArrayList<>(2);

        resolveLaterSlotLeftSiblingBeforeRollingLanding(player);
        SolidCheckpointBatch batch = checkpointAll();
        for (PlayableEntity participant : participatingPlayers(player)) {
            if (broken) {
                break;
            }
            if (participant instanceof AbstractPlayableSprite playable) {
                applyCheckpointContact(playable, batch.perPlayer().get(participant),
                        standingRiders);
            }
        }
        if (broken) {
            return;
        }

        if (mode == Mode.BREAK_FROM_BELOW) {
            playerStanding = false;
            return;
        }

        if (rollingBreakPlayer != null) {
            int launchY = rollingBreakPlayer.getCentreY();
            rollingBreakPlayer.setRolling(true);
            // ROM sub_2A58E writes y_radius/x_radius/status directly and does
            // not alter y_pos (sonic3k.asm:58542-58554). SolidObjectFull may
            // have just restored standing dimensions, so the engine's visual
            // height swap can otherwise move centre Y by five pixels.
            NativePositionOps.writeYPosPreserveSubpixel(rollingBreakPlayer, launchY);
            if (mode != Mode.ICZ_PLANE_SWITCH) {
                rollingBreakPlayer.setYSpeed((short) ROLL_BREAK_LAUNCH_YVEL);
            }
            rollingBreakPlayer.setAir(true);
            rollingBreakPlayer.setOnObject(false);
            dropOtherStandingRiders(standingRiders);

            performBreak(rollingBreakPlayer);
            playerStanding = false;
            return;
        }

        playerStanding = false;
    }

    private void resolveLaterSlotLeftSiblingBeforeRollingLanding(AbstractPlayableSprite player) {
        if (mode == Mode.BREAK_FROM_BELOW || player == null
                || player.getAnimationId() != 2 || player.getYSpeed() < 0) {
            return;
        }
        int playerBottom = player.getCentreY() + player.getYRadius();
        int floorTop = y - config.halfHeight;
        if (playerBottom < floorTop || playerBottom > y
                || Math.abs(player.getCentreX() - x) > config.halfWidth + player.getXRadius()) {
            return;
        }
        ObjectManager objectManager = getObjectManager();
        if (objectManager == null) {
            return;
        }
        for (CorkFloorObjectInstance sibling :
                objectManager.activeObjectsOfType(CorkFloorObjectInstance.class)) {
            if (sibling == this || sibling.broken || sibling.getSlotIndex() <= getSlotIndex()) {
                continue;
            }
            // Adjacent CorkFloor placements are loaded left-to-right by the
            // native object-position cursor. A helper-created right floor can
            // occupy an earlier engine slot, reversing the two SolidObjectFull
            // calls. Replay only the still-unexecuted adjacent left sibling so
            // the seam-side push occurs before this floor launches the rider.
            if (sibling.y == y && sibling.x + (config.halfWidth * 2) == x) {
                objectManager.processImmediateInlineSolidCheckpoint(sibling, player, List.of());
                return;
            }
        }
    }

    private List<PlayableEntity> participatingPlayers(PlayableEntity updatePlayer) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(PLAYER_PARTICIPATION);
        if (updatePlayer == null || participants.contains(updatePlayer)) {
            return participants;
        }
        ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
        withUpdatePlayer.add(updatePlayer);
        withUpdatePlayer.addAll(participants);
        return withUpdatePlayer;
    }

    private void applyCheckpointContact(AbstractPlayableSprite player, PlayerSolidContactResult result,
            List<AbstractPlayableSprite> standingRiders) {
        if (player == null || result == null || broken || result.kind() == ContactKind.NONE) {
            return;
        }

        savedPreContactYSpeed = result.preContact().ySpeed();
        // ROM Obj_CorkFloor caches Player_1+anim / Player_2+anim before
        // SolidObjectFull (sonic3k.asm:58493-58505) and breaks only when that
        // cached byte is anim=$02 (sonic3k.asm:58515-58528, 58532-58540).
        // Keep the per-frame decision per rider: the ROM stores P1/P2 cached
        // animation bytes separately, so a later non-rolling sidekick contact
        // must not erase the main player's roll-break checkpoint.
        boolean preContactRollAnimation = result.preContact().animationId() == 2;
        savedPreContactRolling |= preContactRollAnimation;

        if (result.standingNow()) {
            playerStanding = true;
            if (!standingRiders.contains(player)) {
                standingRiders.add(player);
            }
            if (preContactRollAnimation && canRollBreak(player) && rollingBreakPlayer == null) {
                rollingBreakPlayer = player;
            } else if (mode == Mode.ICZ_PLANE_SWITCH) {
                applyPlaneSwitch(player);
            }
        }

        if (mode == Mode.BREAK_FROM_BELOW && result.kind() == ContactKind.BOTTOM) {
            player.setYSpeed((short) savedPreContactYSpeed);
            performBreak(player);
        }
    }

    /**
     * ROM loc_2A542/loc_2A716: when BOTH riders are standing on the cork floor
     * and either cached animation byte is $02, the break path runs sub_2A588
     * (sub_2A7B0 for the ICZ sloped variant) once for Player_1 and once for
     * Player_2 (sonic3k.asm:58527-58534, 58762-58769). The rider whose cached
     * anim is not $02 falls straight through to loc_2A5AC / loc_2A7CE, which
     * still sets Status_InAir, clears Status_OnObj and writes routine 2
     * (sonic3k.asm:58566-58571, 58764-58768) — it just skips the roll, radii,
     * anim and the -$300 y_vel launch. The engine previously only ever
     * released the rolling breaker, so a standing non-rolling partner stayed
     * grounded on a floor that no longer exists.
     */
    private void dropOtherStandingRiders(List<AbstractPlayableSprite> standingRiders) {
        for (AbstractPlayableSprite rider : standingRiders) {
            if (rider == null || rider == rollingBreakPlayer) {
                continue;
            }
            rider.setAir(true);
            rider.setOnObject(false);
        }
    }

    private void applyPlaneSwitch(AbstractPlayableSprite player) {
        if ((subtype & 0x80) != 0) {
            player.setTopSolidBit((byte) 0x0E);
            player.setLrbSolidBit((byte) 0x0F);
        } else {
            player.setTopSolidBit((byte) 0x0C);
            player.setLrbSolidBit((byte) 0x0D);
        }
    }

    private boolean canRollBreak(AbstractPlayableSprite player) {
        if (mode != Mode.ICZ_PLANE_SWITCH) {
            return true;
        }
        return (subtype & 0x80) != 0 || (player.getTopSolidBit() & 0xFF) == 0x0E;
    }

    private void performBreak(AbstractPlayableSprite player) {
        if (broken) {
            return;
        }
        broken = true;

        int brokenFrame = mappingFrame + 1;

        if (isOnScreen()) {
            try {
                services().playSfx(Sonic3kSfx.COLLAPSE.id);
            } catch (Exception e) {
                // Ignore audio failures.
            }
        }

        spawnFragments(brokenFrame);
        markRemembered();

        try {
            ObjectManager om = getObjectManager();
            if (om != null && player != null) {
                om.clearRidingObject(player);
            }
        } catch (Exception e) {
            // Safe fallback.
        }
    }

    private void spawnFragments(int brokenFrameIndex) {
        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }

        com.openggf.level.objects.ObjectSpriteSheet sheet = renderManager.getSheet(config.artKey);
        if (sheet == null || brokenFrameIndex >= sheet.getFrameCount()) {
            LOG.fine(() -> "CorkFloor: broken frame " + brokenFrameIndex
                    + " not found in sheet " + config.artKey);
            return;
        }

        SpriteMappingFrame brokenFrame = sheet.getFrame(brokenFrameIndex);
        int pieceCount = brokenFrame.pieces().size();
        int maxFragments = Math.min(pieceCount, effectiveVelTable.length);

        for (int i = 0; i < maxFragments; i++) {
            int xVel = effectiveVelTable[i][0];
            int yVel = effectiveVelTable[i][1];
            CorkFloorFragment fragment = new CorkFloorFragment(
                    x, y, brokenFrameIndex, i, xVel, yVel, config.artKey, hFlip);
            spawnDynamicObject(fragment);
        }
    }

    private void markRemembered() {
        try {
            ObjectManager om = getObjectManager();
            ObjectLifetimeOps.markSpawnRemembered(om, spawn);
        } catch (Exception e) {
            // Safe fallback for tests.
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (broken) {
            return;
        }

        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(config.artKey);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(mappingFrame, x, y, hFlip, false);
        }
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
        return RenderPriority.clamp(PRIORITY);
    }

    private ObjectManager getObjectManager() {
        try {
            return services().objectManager();
        } catch (Exception e) {
            return null;
        }
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    private ZoneConfig resolveConfig(int subtype) {
        try {
            int zone = services().romZoneId();
            int act = services().currentAct();
            return switch (zone) {
                case Sonic3kZoneIds.ZONE_AIZ -> act == 0 ? AIZ1_CONFIG : AIZ2_CONFIG;
                case Sonic3kZoneIds.ZONE_CNZ -> CNZ_CONFIG;
                case Sonic3kZoneIds.ZONE_FBZ -> FBZ_CONFIG;
                case Sonic3kZoneIds.ZONE_ICZ ->
                        (subtype & 0x10) != 0 ? ICZ_SMALL_CONFIG : ICZ_CONFIG;
                case Sonic3kZoneIds.ZONE_LBZ -> LBZ_CONFIG;
                default -> {
                    LOG.warning("CorkFloor: unknown zone 0x" + Integer.toHexString(zone)
                            + ", defaulting to AIZ1 config");
                    yield AIZ1_CONFIG;
                }
            };
        } catch (Exception e) {
            LOG.fine("Could not resolve zone config: " + e.getMessage());
        }
        return AIZ1_CONFIG;
    }

    public static class CorkFloorFragment extends AbstractObjectInstance implements RewindRecreatable {

        private int currentX;
        private int currentY;
        private int fragmentFrameIndex;
        private int pieceIndex;
        private String artKey;
        private boolean hFlip;
        private final SubpixelMotion.State motionState;

        public CorkFloorFragment(int parentX, int parentY,
                                 int fragmentFrameIndex, int pieceIndex,
                                 int xVel, int yVel,
                                 String artKey, boolean hFlip) {
            super(new ObjectSpawn(parentX, parentY, Sonic3kObjectIds.CORK_FLOOR,
                    0, hFlip ? 1 : 0, false, 0), "CorkFloorFragment");
            this.currentX = parentX;
            this.currentY = parentY;
            this.fragmentFrameIndex = fragmentFrameIndex;
            this.pieceIndex = pieceIndex;
            this.artKey = artKey;
            this.hFlip = hFlip;
            this.motionState = new SubpixelMotion.State(
                    currentX, currentY, 0, 0, xVel, yVel);
        }

        private CorkFloorFragment() {
            this(0, 0, 0, 0, 0, 0, Sonic3kObjectArtKeys.CORK_FLOOR_AIZ1, false);
        }

        @Override
        public CorkFloorFragment recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn capturedSpawn = ctx.spawn();
            int x = capturedSpawn != null ? capturedSpawn.x() : 0;
            int y = capturedSpawn != null ? capturedSpawn.y() : 0;
            boolean capturedHFlip = capturedSpawn != null && (capturedSpawn.renderFlags() & 1) != 0;
            return new CorkFloorFragment(
                    x,
                    y,
                    0,
                    0,
                    0,
                    0,
                    Sonic3kObjectArtKeys.CORK_FLOOR_AIZ1,
                    capturedHFlip);
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
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            motionState.x = currentX;
            motionState.y = currentY;
            SubpixelMotion.moveSprite(motionState, FRAGMENT_GRAVITY);
            currentX = motionState.x;
            currentY = motionState.y;

            if (!isOnScreen(128)) {
                setDestroyed(true);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = getRenderManager();
            if (renderManager == null) {
                return;
            }

            PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFramePieceByIndex(fragmentFrameIndex, pieceIndex,
                        currentX, currentY, hFlip, false);
            }
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(PRIORITY);
        }

        @Override
        public boolean isPersistent() {
            return !isDestroyed();
        }
    }
}
