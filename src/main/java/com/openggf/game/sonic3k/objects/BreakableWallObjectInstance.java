package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.ShieldType;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x0D - Breakable Wall (Sonic 3 & Knuckles).
 */
public class BreakableWallObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_BreakableWall} is installed from the S3K object pointer table at
     * {@code $0002131C} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:45524).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }


    private static final Logger LOG = Logger.getLogger(BreakableWallObjectInstance.class.getName());

    private static final int FRAGMENT_GRAVITY = 0x70;
    private static final int PRIORITY = 5;
    private static final int BREAK_SPEED_THRESHOLD = 0x480;
    private static final int ANIM_ROLL = 2;
    private static final int GLIDE_ACTIVE = 1;
    private static final int GLIDE_FALLING = 2;
    private static final int KNUCKLES_FALL_FROM_GLIDE_ANIM = 0x21;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    private static final int[][] VEL_RIGHT_12 = {
            {0x400, -0x500}, {0x600, -0x600}, {0x600, -0x100}, {0x800, -0x200},
            {0x680, 0}, {0x880, 0}, {0x600, 0x100}, {0x800, 0x200},
            {0x400, 0x500}, {0x600, 0x600}, {0x300, 0x600}, {0x500, 0x700},
    };
    private static final int[][] VEL_LEFT_12 = {
            {-0x600, -0x600}, {-0x400, -0x500}, {-0x800, -0x200}, {-0x600, -0x100},
            {-0x880, 0}, {-0x680, 0}, {-0x800, 0x200}, {-0x600, 0x100},
            {-0x600, 0x600}, {-0x400, 0x500}, {-0x500, 0x700}, {-0x300, 0x600},
    };
    private static final int[][] VEL_RIGHT_8A = {
            {0x400, -0x500}, {0x600, -0x600}, {0x600, -0x100}, {0x800, -0x200},
            {0x600, 0x100}, {0x800, 0x200}, {0x400, 0x500}, {0x600, 0x600},
    };
    private static final int[][] VEL_LEFT_8A = {
            {-0x600, -0x600}, {-0x400, -0x500}, {-0x800, -0x200}, {-0x600, -0x100},
            {-0x800, 0x200}, {-0x600, 0x100}, {-0x600, 0x600}, {-0x400, 0x500},
    };
    private static final int[][] VEL_RIGHT_20 = {
            {0x400, -0x500}, {0x500, -0x580}, {0x600, -0x600}, {0x700, -0x680},
            {0x600, -0x100}, {0x700, -0x180}, {0x800, -0x200}, {0x900, -0x280},
            {0x680, 0}, {0x780, 0}, {0x880, 0}, {0x980, 0},
            {0x600, 0x100}, {0x700, 0x180}, {0x800, 0x200}, {0x900, 0x280},
            {0x400, 0x500}, {0x500, 0x580}, {0x600, 0x600}, {0x700, 0x680},
    };
    private static final int[][] VEL_LEFT_20 = {
            {-0x700, -0x680}, {-0x600, -0x600}, {-0x500, -0x580}, {-0x400, -0x500},
            {-0x900, -0x280}, {-0x800, -0x200}, {-0x700, -0x180}, {-0x600, -0x100},
            {-0x980, 0}, {-0x880, 0}, {-0x780, 0}, {-0x680, 0},
            {-0x900, 0x280}, {-0x800, 0x200}, {-0x700, 0x180}, {-0x600, 0x100},
            {-0x700, 0x680}, {-0x600, 0x600}, {-0x500, 0x580}, {-0x400, 0x500},
    };
    private static final int[][] VEL_RIGHT_8B = {
            {0x400, -0x500}, {0x600, -0x600}, {0x600, -0x100}, {0x800, -0x200},
            {0x600, 0x100}, {0x800, 0x200}, {0x400, 0x500}, {0x600, 0x600},
    };
    private static final int[][] VEL_LEFT_8B = {
            {-0x600, -0x600}, {-0x400, -0x500}, {-0x800, -0x200}, {-0x600, -0x100},
            {-0x800, 0x200}, {-0x600, 0x100}, {-0x600, 0x600}, {-0x400, 0x500},
    };

    private enum BreakMode {
        STANDARD,
        KNUCKLES_ONLY,
        MGZ_SPIN_BREAK
    }

    private record ZoneConfig(
            String artKey,
            int halfWidth,
            int halfHeight,
            int[][] velRight,
            int[][] velLeft,
            BreakMode breakMode,
            int sheetFrameOffset
    ) {
        ZoneConfig(String artKey, int halfWidth, int halfHeight,
                   int[][] velRight, int[][] velLeft, BreakMode breakMode) {
            this(artKey, halfWidth, halfHeight, velRight, velLeft, breakMode, 0);
        }
    }

    private final ZoneConfig config;
    private boolean triggerControlled;
    private int x;
    private int y;
    private int mappingFrame;
    private boolean broken;

    private short savedPreContactXSpeed;
    private short savedPreContactYSpeed;
    private int savedPreContactAnimationId;

    public BreakableWallObjectInstance(ObjectSpawn spawn) {
        super(spawn, "BreakableWall");
        int subtype = spawn.subtype() & 0xFF;
        this.triggerControlled = (subtype & 0x80) != 0;
        this.mappingFrame = subtype & 0x0F;
        this.x = spawn.x();
        this.y = spawn.y();
        this.config = resolveConfig(subtype, mappingFrame);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(config.halfWidth + 0x0B, config.halfHeight, config.halfHeight + 1);
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj_BreakableWall calls SolidObjectFull, whose unsigned X-window
        // rejection is `bhi`, not `bhs` (sonic3k.asm:41405). A player exactly
        // flush with the padded right edge therefore remains a side contact and
        // has Status_Push reasserted without positional correction.
        return true;
    }

    @Override
    public boolean projectsPreMovementGroundXForSolidContact(PlayableEntity player) {
        // Obj_BreakableWall's SolidObjectFull call observes the player slot's
        // already-applied X step. This also lets an object-controlled carrier
        // publish its equivalent pending MoveSprite2 step through the shared
        // projection seam.
        return true;
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
        if (broken) {
            return;
        }

        SolidCheckpointBatch batch = checkpointAll();
        List<PlayableEntity> participants = playerQuery(playerEntity).playersFor(PLAYER_PARTICIPATION);
        for (int participantIndex = 0; participantIndex < participants.size(); participantIndex++) {
            PlayableEntity participant = participants.get(participantIndex);
            if (broken) {
                break;
            }
            if (participant instanceof AbstractPlayableSprite sprite) {
                applyCheckpointContact(sprite, batch.perPlayer().get(participant));
                if (broken && participantIndex == 0) {
                    restoreSidekickPushContactsAfterPrimaryBreak(participants, batch, participantIndex + 1);
                }
            }
        }
        if (broken) {
            return;
        }

        if (triggerControlled && isTriggerActive()) {
            setDestroyed(true);
        }
    }

    private void restoreSidekickPushContactsAfterPrimaryBreak(List<PlayableEntity> participants,
            SolidCheckpointBatch batch, int firstSidekickIndex) {
        // When Player_1 breaks the wall, sub_2165A returns to loc_215F4 and
        // Obj_BreakableWall still consumes Player_2's SolidObjectFull pushing
        // result. A rolling P2 has the velocity saved before the checkpoint
        // restored and Status_Push cleared (sonic3k.asm:45589-45620). The
        // checkpoint batch has already resolved both players, so mirror that
        // post-break cleanup before the wall slot becomes a fragment.
        for (int i = firstSidekickIndex; i < participants.size(); i++) {
            PlayableEntity participant = participants.get(i);
            if (!(participant instanceof AbstractPlayableSprite sidekick)) {
                continue;
            }
            PlayerSolidContactResult result = batch.perPlayer().get(participant);
            if (result == null || !result.pushingNow() || result.preContact().animationId() != ANIM_ROLL) {
                continue;
            }
            sidekick.setXSpeed(result.preContact().xSpeed());
            sidekick.setGSpeed(result.preContact().xSpeed());
            // ROM clears p2_pushing_bit on the wall in the Player_2 leg
            // (docs/skdisasm/sonic3k.asm:45720, :45739, :45858, :45871).
            services().objectManager().solidContacts().releaseObjectPushLatch(sidekick, this);
            sidekick.setPushing(false);
        }
    }

    private ObjectPlayerQuery playerQuery(PlayableEntity primary) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> primary, query::sidekicks);
    }

    private void applyCheckpointContact(AbstractPlayableSprite player, PlayerSolidContactResult result) {
        if (player == null || result == null || broken || result.kind() == com.openggf.game.solid.ContactKind.NONE) {
            return;
        }

        savedPreContactXSpeed = result.preContact().xSpeed();
        savedPreContactYSpeed = result.preContact().ySpeed();
        savedPreContactAnimationId = result.preContact().animationId();

        // MGZ spin-break walls key off side-contact feedback instead of the
        // generic push flag. The other modes still require a pushing contact.
        if (config.breakMode == BreakMode.MGZ_SPIN_BREAK) {
            if (result.kind() != com.openggf.game.solid.ContactKind.SIDE) {
                return;
            }
        } else if (!result.pushingNow() && !bypassesStandardPushGate(player)) {
            return;
        }

        boolean shouldBreak = switch (config.breakMode) {
            case STANDARD -> checkStandardBreak(player);
            case KNUCKLES_ONLY -> isKnuckles();
            case MGZ_SPIN_BREAK -> checkMgzSpinBreak(player, result.sideDistX());
        };

        if (shouldBreak) {
            performBreak(player);
        }
    }

    private boolean checkStandardBreak(AbstractPlayableSprite player) {
        if (player.isSuperSonic()) {
            return true;
        }
        if (isKnuckles()) {
            return true;
        }
        // Obj_BreakableWall gates standard spin breaks on anim(a1)==2 after saving x_vel,
        // not on the status rolling bit.
        if (savedPreContactAnimationId != ANIM_ROLL) {
            return false;
        }
        if (Math.abs(savedPreContactXSpeed) < BREAK_SPEED_THRESHOLD) {
            return false;
        }
        return true;
    }

    private boolean bypassesStandardPushGate(AbstractPlayableSprite player) {
        if (config.breakMode != BreakMode.STANDARD) {
            return false;
        }
        // Obj_BreakableWall branches around the wall's pushing-bit test for
        // Super Sonic/Knuckles and for status_secondary's fire-shield bit.
        // The roll-animation and speed gates still apply to the shield case.
        return player.isSuperSonic()
                || isKnuckles()
                || player.hasShield() && player.getShieldType() == ShieldType.FIRE;
    }

    /**
     * MGZ spin-break check (loc_2172E).
     * ROM: bclr #6,$37(a1) tests and consumes the tertiary side-contact bit set
     * by SolidObjectFull after a non-zero side correction. This is distinct from
     * the engine's persistent wall-cling flag, which also owns the MGZ top-platform
     * grab state. Knuckles-in-glide remains an engine fallback because the glide
     * path does not always raise the contact bit at the same point.
     */
    private boolean checkMgzSpinBreak(AbstractPlayableSprite player, int sideDistX) {
        if (player.consumeWallClingSideContact()) {
            return true;
        }
        // A later wall slot observes the carrier's already-applied MoveSprite2
        // step. The manual checkpoint represents its non-zero SolidObjectFull
        // d0 before the controller callback can publish status_tertiary bit 6.
        if (player.isWallCling() && sideDistX != 0) {
            return true;
        }
        return isKnuckles() && player.getDoubleJumpFlag() == GLIDE_ACTIVE;
    }

    boolean wouldBreakFromSideContact(AbstractPlayableSprite player) {
        if (player == null || broken) {
            return false;
        }
        return switch (config.breakMode) {
            case STANDARD -> player.isSuperSonic()
                    || isKnuckles()
                    || player.getAnimationId() == ANIM_ROLL
                            && Math.abs(player.getXSpeed()) >= BREAK_SPEED_THRESHOLD;
            case KNUCKLES_ONLY -> isKnuckles();
            // The projection guard runs before SolidObjectFull can raise the
            // transient side-contact bit, so the active MGZ grab/cling state
            // predicts that the resolving side pass is eligible to break.
            case MGZ_SPIN_BREAK -> player.isWallCling()
                    || isKnuckles() && player.getDoubleJumpFlag() == GLIDE_ACTIVE;
        };
    }

    boolean breaksFromTertiarySideFeedback() {
        return config.breakMode == BreakMode.MGZ_SPIN_BREAK;
    }

    private void performBreak(AbstractPlayableSprite player) {
        if (broken) {
            return;
        }
        broken = true;
        player.notifyObjectControlledSolidContactInvalidated(this);

        player.setXSpeed(savedPreContactXSpeed);
        player.setGSpeed(savedPreContactXSpeed);

        int[][] velTable;
        if (config.breakMode == BreakMode.MGZ_SPIN_BREAK) {
            boolean playerIsRight = player.getCentreX() > x;
            velTable = playerIsRight ? config.velRight : config.velLeft;
            if (!playerIsRight) {
                player.setX((short) (player.getX() - 8));
            }
        } else {
            player.setX((short) (player.getX() + 4));
            boolean playerIsRight = player.getCentreX() > x;
            if (playerIsRight) {
                velTable = config.velRight;
            } else {
                player.setX((short) (player.getX() - 8));
                velTable = config.velLeft;
            }
        }

        // ROM clears p1_pushing_bit on the wall in the Player_1 leg
        // (docs/skdisasm/sonic3k.asm:45711, :45848, :45933).
        services().objectManager().solidContacts().releaseObjectPushLatch(player, this);
        player.setPushing(false);

        if (config.breakMode == BreakMode.KNUCKLES_ONLY
                && isKnuckles() && player.getDoubleJumpFlag() == GLIDE_ACTIVE) {
            player.setDoubleJumpFlag(GLIDE_FALLING);
            player.setAnimationId(KNUCKLES_FALL_FROM_GLIDE_ANIM);
            if (player.getXSpeed() >= 0) {
                player.setDirection(Direction.RIGHT);
            } else {
                player.setDirection(Direction.LEFT);
            }
        }

        if (isOnScreen()) {
            try {
                services().playSfx(Sonic3kSfx.COLLAPSE.id);
            } catch (Exception e) {
                // Ignore audio failures.
            }
        }

        int brokenFrame = (mappingFrame + 1) - config.sheetFrameOffset;
        spawnFragments(brokenFrame, velTable);
        markRemembered();

        // ROM parity: sub_218CE ends with loc_21692 -> Delete_Current_Sprite. The
        // wall's solid/body representation is gone the instant fragments spawn; the
        // only thing that lingers is the fragment children. Retiring the object via
        // setDestroyed(true) mirrors that and lets `isSolidFor()` (via !broken) and
        // `wall.isDestroyed()` agree.
        setDestroyed(true);
    }

    private boolean isTriggerActive() {
        return Sonic3kLevelTriggerManager.testAny(0);
    }

    private void spawnFragments(int brokenFrameIndex, int[][] velTable) {
        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }

        ObjectSpriteSheet sheet = renderManager.getSheet(config.artKey);
        if (sheet == null || brokenFrameIndex >= sheet.getFrameCount()) {
            LOG.fine(() -> "BreakableWall: broken frame " + brokenFrameIndex
                    + " not found in sheet " + config.artKey);
            return;
        }

        SpriteMappingFrame brokenFrame = sheet.getFrame(brokenFrameIndex);
        int pieceCount = brokenFrame.pieces().size();
        int maxFragments = Math.min(pieceCount, velTable.length);

        int firstAllocatedPiece = 0;
        if (maxFragments > 0) {
            // BreakObjectToPieces starts with a1=a0, so piece zero morphs the
            // wall's existing SST slot in place before AllocateObjectAfterCurrent
            // is used for the remaining pieces (sonic3k.asm:216D8-21726).
            // Keeping that slot occupied is load-bearing for later S3K object
            // allocation and execution order.
            ObjectManager objectManager = services().objectManager();
            int transferredSlot = ObjectLifetimeOps.detachSlotForTransfer(this);
            if (objectManager != null && transferredSlot >= 0) {
                int xVel = velTable[0][0];
                int yVel = velTable[0][1];
                BreakableWallFragment firstFragment = new BreakableWallFragment(
                        x, y, brokenFrameIndex, 0, xVel, yVel, config.artKey,
                        config.halfWidth, config.halfHeight);
                ObjectLifetimeOps.addReplacementAtTransferredSlot(
                        objectManager, firstFragment, transferredSlot);
                // The ROM immediately falls through to loc_21692 after the
                // in-place conversion, so piece zero moves once this same tick.
                firstFragment.update(0, null);
                firstAllocatedPiece = 1;
            }
        }

        for (int i = firstAllocatedPiece; i < maxFragments; i++) {
            int xVel = velTable[i][0];
            int yVel = velTable[i][1];
            BreakableWallFragment fragment = new BreakableWallFragment(
                    x, y, brokenFrameIndex, i, xVel, yVel, config.artKey,
                    config.halfWidth, config.halfHeight);
            spawnDynamicObject(fragment);
        }
    }

    private void markRemembered() {
        try {
            var om = services().objectManager();
            ObjectLifetimeOps.markSpawnRemembered(om, spawn);
        } catch (Exception e) {
            // Safe fallback.
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
            renderer.drawFrameIndex(mappingFrame - config.sheetFrameOffset, x, y, false, false);
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

    private boolean isKnuckles() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration()) == PlayerCharacter.KNUCKLES;
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    private ZoneConfig resolveConfig(int subtype, int frame) {
        try {
            int zone = services().romZoneId();
            return resolveForZone(zone, subtype, frame);
        } catch (Exception e) {
            LOG.fine("Could not resolve zone config: " + e.getMessage());
        }
        return new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_AIZ,
                0x10, 0x28, VEL_RIGHT_12, VEL_LEFT_12, BreakMode.STANDARD);
    }

    private static ZoneConfig resolveForZone(int zone, int subtype, int frame) {
        return switch (zone) {
            case Sonic3kZoneIds.ZONE_AIZ ->
                    new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_AIZ,
                            0x10, 0x28, VEL_RIGHT_12, VEL_LEFT_12, BreakMode.STANDARD);
            case Sonic3kZoneIds.ZONE_HCZ -> {
                if (frame == 2) {
                    yield new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_HCZ_KNUX,
                            0x18, 0x20, VEL_RIGHT_8A, VEL_LEFT_8A, BreakMode.KNUCKLES_ONLY, 2);
                }
                yield new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_HCZ,
                        0x10, 0x20, VEL_RIGHT_8A, VEL_LEFT_8A, BreakMode.STANDARD);
            }
            case Sonic3kZoneIds.ZONE_MGZ -> {
                if ((subtype & 0x10) != 0) {
                    yield new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_MGZ,
                            0x20, 0x28, VEL_LEFT_20, VEL_RIGHT_20, BreakMode.KNUCKLES_ONLY);
                }
                if (frame == 2) {
                    yield new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_MGZ,
                            0x20, 0x28, VEL_LEFT_20, VEL_RIGHT_20, BreakMode.MGZ_SPIN_BREAK);
                }
                yield new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_MGZ,
                        0x20, 0x28, VEL_LEFT_20, VEL_RIGHT_20, BreakMode.STANDARD);
            }
            case Sonic3kZoneIds.ZONE_CNZ -> {
                if (frame == 2) {
                    yield new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_CNZ,
                            0x10, 0x20, VEL_RIGHT_8B, VEL_LEFT_8B, BreakMode.KNUCKLES_ONLY);
                }
                yield new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_CNZ,
                        0x10, 0x20, VEL_RIGHT_8B, VEL_LEFT_8B, BreakMode.STANDARD);
            }
            case Sonic3kZoneIds.ZONE_LBZ ->
                    new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_LBZ,
                            0x10, 0x20, VEL_RIGHT_8A, VEL_LEFT_8A, BreakMode.KNUCKLES_ONLY);
            case Sonic3kZoneIds.ZONE_MHZ ->
                    new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_MHZ,
                            0x10, 0x20, VEL_RIGHT_8B, VEL_LEFT_8B, BreakMode.KNUCKLES_ONLY);
            case Sonic3kZoneIds.ZONE_SOZ -> {
                if (frame == 4) {
                    yield new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_SOZ,
                            0x10, 0x30, VEL_RIGHT_12, VEL_LEFT_12, BreakMode.KNUCKLES_ONLY);
                }
                yield new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_SOZ,
                        0x10, 0x20, VEL_RIGHT_8B, VEL_LEFT_8B, BreakMode.KNUCKLES_ONLY);
            }
            case Sonic3kZoneIds.ZONE_LRZ ->
                    new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_LRZ,
                            0x10, 0x20, VEL_RIGHT_8B, VEL_LEFT_8B, BreakMode.STANDARD);
            default ->
                    new ZoneConfig(Sonic3kObjectArtKeys.BREAKABLE_WALL_AIZ,
                            0x10, 0x28, VEL_RIGHT_12, VEL_LEFT_12, BreakMode.STANDARD);
        };
    }

    public static class BreakableWallFragment extends AbstractObjectInstance implements RewindRecreatable {

        private int currentX;
        private int currentY;
        private int fragmentFrameIndex;
        private int pieceIndex;
        private String artKey;
        private int renderHalfWidth;
        private int renderHalfHeight;
        private boolean romRenderFlag = true;
        private final SubpixelMotion.State motionState;

        public BreakableWallFragment(int parentX, int parentY,
                                     int fragmentFrameIndex, int pieceIndex,
                                     int xVel, int yVel, String artKey) {
            this(parentX, parentY, fragmentFrameIndex, pieceIndex, xVel, yVel,
                    artKey, 0x10, 0x28);
        }

        public BreakableWallFragment(int parentX, int parentY,
                                     int fragmentFrameIndex, int pieceIndex,
                                     int xVel, int yVel, String artKey,
                                     int renderHalfWidth, int renderHalfHeight) {
            super(new ObjectSpawn(parentX, parentY, Sonic3kObjectIds.BREAKABLE_WALL,
                    0, 0, false, 0), "BreakableWallFragment");
            this.currentX = parentX;
            this.currentY = parentY;
            this.fragmentFrameIndex = fragmentFrameIndex;
            this.pieceIndex = pieceIndex;
            this.artKey = artKey;
            this.renderHalfWidth = renderHalfWidth;
            this.renderHalfHeight = renderHalfHeight;
            this.motionState = new SubpixelMotion.State(
                    currentX, currentY, 0, 0, xVel, yVel);
        }

        private BreakableWallFragment() {
            this(0, 0, 0, 0, 0, 0, Sonic3kObjectArtKeys.BREAKABLE_WALL_AIZ);
        }

        @Override
        public BreakableWallFragment recreateForRewind(RewindRecreateContext ctx) {
            ObjectSpawn capturedSpawn = ctx.spawn();
            int x = capturedSpawn != null ? capturedSpawn.x() : 0;
            int y = capturedSpawn != null ? capturedSpawn.y() : 0;
            return new BreakableWallFragment(
                    x,
                    y,
                    0,
                    0,
                    0,
                    0,
                    Sonic3kObjectArtKeys.BREAKABLE_WALL_AIZ);
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

            // loc_21692 consumes the render_flags sign bit retained from the
            // preceding Render_Sprites pass, after applying this tick's motion.
            if (!romRenderFlag) {
                setDestroyed(true);
            }
        }

        @Override
        public int getOnScreenHalfWidth() {
            return renderHalfWidth;
        }

        @Override
        public int getOnScreenHalfHeight() {
            return renderHalfHeight;
        }

        @Override
        public void refreshPostCameraRenderState() {
            romRenderFlag = isWithinRenderSpriteBounds(renderHalfWidth, renderHalfHeight);
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
                        currentX, currentY, false, false);
            }
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(1);
        }

        @Override
        public boolean isPersistent() {
            return !isDestroyed();
        }
    }
}
