package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.ShieldType;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x05 - AIZ/LRZ/EMZ Rock (Sonic 3 & Knuckles).
 */
public class AizLrzRockObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RomObjectCodePointerProvider,
        SpawnRewindRecreatable {

    private static final Logger LOG = Logger.getLogger(AizLrzRockObjectInstance.class.getName());

    private static final int[][] SIZE_TABLE = {
            {24, 39},
            {24, 23},
            {24, 15},
            {14, 15},
            {16, 40},
            {40, 16},
            {40, 16},
            {16, 32},
    };

    private static final int PUSH_RATE_PERIOD = 0x10;
    private static final int PUSH_MAX_DISTANCE = 0x40;

    private static final int BIT_BREAK_TOP = 0x01;
    private static final int BIT_PUSHABLE = 0x02;
    private static final int BIT_BREAK_SIDE = 0x04;
    private static final int BIT_BREAK_BOTTOM = 0x08;
    private static final int KNUCKLES_ONLY_STANDING_NIBBLE = 0x0F;
    private static final int SIDE_BREAK_SPEED_THRESHOLD = 0x480;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS;

    private static final int[][][] DEBRIS_POSITIONS = {
            {{-8, -0x18}, {0x0B, -0x1C}, {-4, -0x0C}, {0x0C, -4},
             {-0x0C, 4}, {4, 0x0C}, {-0x0C, 0x1C}, {0x0C, 0x1C}},
            {{-4, -0x0C}, {0x0B, -0x0C}, {-4, -4}, {-0x0C, 0x0C}, {0x0C, 0x0C}},
            {{-4, -4}, {0x0C, -4}, {-0x0C, 4}, {0x0C, 4}},
            {{-8, -8}, {8, -8}, {-8, 0}, {8, 0}, {-8, 8}, {8, 8}},
    };

    private static final int[][][] DEBRIS_VELOCITIES = {
            {{-0x300, -0x300}, {-0x2C0, -0x280}, {-0x2C0, -0x280}, {-0x280, -0x200},
             {-0x280, -0x180}, {-0x240, -0x180}, {-0x240, -0x100}, {-0x200, -0x100}},
            {{-0x200, -0x200}, {0x200, -0x200}, {-0x100, -0x1E0},
             {-0x1B0, -0x1C0}, {0x1C0, -0x1C0}},
            {{-0x100, -0x200}, {0x100, -0x1E0}, {-0x1B0, -0x1C0}, {0x1C0, -0x1C0}},
            {{-0xB0, -0x1E0}, {0xB0, -0x1D0}, {-0x80, -0x200},
             {0x80, -0x1E0}, {-0xD8, -0x1C0}, {0xE0, -0x1C0}},
    };

    private enum ZoneVariant {
        AIZ1(Sonic3kObjectArtKeys.AIZ1_ROCK, 0, 1, 3),
        AIZ2(Sonic3kObjectArtKeys.AIZ2_ROCK, 0, 2, 3),
        LRZ1(Sonic3kObjectArtKeys.LRZ1_ROCK, 4, 2, 0),
        LRZ2(Sonic3kObjectArtKeys.LRZ2_ROCK, 0, 3, 0),
        UNKNOWN(null, 0, 0, 0);

        final String artKey;
        final int frameOffset;
        final int sheetPalette;
        final int debrisBaseFrame;

        ZoneVariant(String artKey, int frameOffset, int sheetPalette, int debrisBaseFrame) {
            this.artKey = artKey;
            this.frameOffset = frameOffset;
            this.sheetPalette = sheetPalette;
            this.debrisBaseFrame = debrisBaseFrame;
        }
    }

    private int baseX;
    private int baseY;
    private int currentX;
    private int currentY;
    private ZoneVariant variant;
    private int sizeIndex;
    private int behaviorBits;
    private boolean knucklesOnly;
    private boolean knucklesOnlyStanding;
    private int displayFrame;

    private boolean contactPushingActive;
    private int pushRateTimer;
    private int pushDistanceRemaining = PUSH_MAX_DISTANCE;

    private boolean playerStandingOnRock;
    private boolean playerPushingSide;

    private boolean savedPreContactRolling;
    private int savedPreContactAnimationId;
    private int savedPreContactXSpeed;
    private int savedPreContactYSpeed;

    private boolean breaking;

    public AizLrzRockObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AIZLRZRock");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.currentX = baseX;
        this.currentY = baseY;

        this.sizeIndex = (spawn.subtype() >> 4) & 0x07;
        int lowerNibble = spawn.subtype() & 0x0F;
        this.knucklesOnlyStanding = (lowerNibble == KNUCKLES_ONLY_STANDING_NIBBLE);
        this.behaviorBits = knucklesOnlyStanding ? 0 : lowerNibble;
        this.knucklesOnly = (spawn.subtype() & 0x80) != 0;

        this.variant = resolveVariant();
        this.displayFrame = sizeIndex + variant.frameOffset;
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
        if (breaking) {
            return;
        }

        contactPushingActive = false;
        SolidCheckpointBatch batch = checkpointAll();
        for (PlayableEntity participant : playerQuery(playerEntity).playersFor(PLAYER_PARTICIPATION)) {
            if (breaking) {
                break;
            }
            if (participant instanceof AbstractPlayableSprite participantSprite) {
                PlayerSolidContactResult result = batch.perPlayer().get(participant);
                applyCheckpointContact(participantSprite, result, batch);
                if (!breaking && (behaviorBits & BIT_PUSHABLE) != 0) {
                    handlePush(participantSprite, result);
                }
                if (!breaking
                        && (behaviorBits & BIT_BREAK_SIDE) != 0
                        && isSideBreakCandidate(result)
                        && canSideBreak(participantSprite, result.pushingNow())) {
                    breakFromSide(participantSprite, batch);
                    return;
                }
            }
        }
        if (breaking) {
            return;
        }

        if (knucklesOnlyStanding && playerStandingOnRock && player != null) {
            if (isKnuckles()) {
                enterRollingLaunch(player);
                player.setYSpeed((short) -0x300);
                player.setAir(true);
                player.setOnObject(false);
                breakRock(player, batch);
            }
            playerStandingOnRock = false;
            playerPushingSide = false;
            return;
        }

        if ((behaviorBits & BIT_BREAK_TOP) != 0 && playerStandingOnRock && player != null) {
            if (savedPreContactAnimationId == Sonic3kAnimationIds.ROLL.id()) {
                enterRollingLaunch(player);
                player.setYSpeed((short) -0x300);
                player.setAir(true);
                player.setOnObject(false);
                breakRock(player, batch);
                playerStandingOnRock = false;
                playerPushingSide = false;
                return;
            }
        }

        playerStandingOnRock = false;
        playerPushingSide = false;

        updateDynamicSpawn(currentX, currentY);
    }

    private void applyCheckpointContact(AbstractPlayableSprite player, PlayerSolidContactResult result,
                                        SolidCheckpointBatch batch) {
        if (player == null || result == null || breaking) {
            return;
        }
        if (!result.pushingNow() && result.pushingLastFrame()) {
            // SolidObject_TestClearPush clears Status_Push when this rock's
            // per-player pushing bit was set at entry but SolidObjectFull no
            // longer reports a side push (sonic3k.asm:41503-41532). Manual
            // checkpoint batching retains that previous bit in the result.
            // ROM AIZLRZEMZRock clears this character's own pushing bit on
            // the rock -- p1 in the Player_1 leg, p2 in the Player_2 leg
            // (docs/skdisasm/sonic3k.asm:44189, :44202, :44233).
            services().objectManager().solidContacts().releaseObjectPushLatch(player, this);
            player.setPushing(false);
            if (player.getAnimationId() != Sonic3kAnimationIds.ROLL.id()
                    && player.getAnimationId() != Sonic3kAnimationIds.SPINDASH.id()) {
                // SolidObjectFull_Offset_1P publishes the paired
                // anim=Walk/prev_anim=Run word before clearing Status_Push;
                // Roll and Spindash branch directly to the clear helper
                // (sonic3k.asm:41503-41532).
                player.setAnimationId(Sonic3kAnimationIds.WALK.id());
                player.publishRunAsPreviousAnimation();
            }
        }
        if (result.kind() == ContactKind.NONE) {
            return;
        }

        savedPreContactRolling = result.preContact().rolling();
        savedPreContactAnimationId = result.preContact().animationId();
        savedPreContactXSpeed = result.preContact().xSpeed();
        savedPreContactYSpeed = result.preContact().ySpeed();

        if (result.standingNow()) {
            playerStandingOnRock = true;
        }
        if (result.pushingNow()) {
            playerPushingSide = true;
            if ((behaviorBits & BIT_PUSHABLE) != 0) {
                contactPushingActive = true;
            }
        }
        if (result.kind() == ContactKind.SIDE && knucklesOnly) {
            playerPushingSide = true;
        }

        if (!knucklesOnlyStanding
                && (behaviorBits & BIT_BREAK_BOTTOM) != 0
                && result.kind() == ContactKind.BOTTOM) {
            breakRock(player, batch);
            player.setYSpeed((short) savedPreContactYSpeed);
        }
    }

    private boolean isSideBreakCandidate(PlayerSolidContactResult result) {
        return result != null
                && result.kind() != ContactKind.NONE
                && (result.kind() == ContactKind.SIDE || result.pushingNow());
    }

    private boolean canSideBreak(AbstractPlayableSprite player, boolean pushingNow) {
        if (knucklesOnly) {
            return isKnuckles();
        }
        if (isKnuckles()) {
            return true;
        }
        if (player.isSuperSonic()) {
            return true;
        }
        // Obj_AIZLRZEMZRock_PushBreakMain gates side breaks on anim(a1)==2
        // after saving x_vel to $30/$36, not on the status rolling bit.
        if (savedPreContactAnimationId != Sonic3kAnimationIds.ROLL.id()
                || Math.abs(savedPreContactXSpeed) < SIDE_BREAK_SPEED_THRESHOLD) {
            return false;
        }
        if (player.getShieldType() == ShieldType.FIRE) {
            return true;
        }
        return pushingNow;
    }

    private void breakFromSide(AbstractPlayableSprite player, SolidCheckpointBatch batch) {
        if (savedPreContactRolling) {
            enterRollingLaunch(player);
        }
        player.setXSpeed((short) savedPreContactXSpeed);
        int playerX = player.getCentreX();
        if (playerX < currentX) {
            player.setCentreXPreserveSubpixel((short) (playerX - 4));
        } else {
            player.setCentreXPreserveSubpixel((short) (playerX + 4));
        }
        player.setGSpeed(player.getXSpeed());
        player.setPushing(false);
        breakRock(player, batch);
        playerStandingOnRock = false;
        playerPushingSide = false;
    }

    private void enterRollingLaunch(AbstractPlayableSprite player) {
        boolean wasRolling = player.getRolling();
        short centreY = player.getCentreY();
        player.setRolling(true);
        if (!wasRolling) {
            // ROM keeps y_pos fixed when the rock forces roll status and radii.
            // Preserve the current centre Y instead of using the normal
            // "feet planted" roll transition.
            player.setCentreYPreserveSubpixel(centreY);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        int idx = Math.clamp(sizeIndex, 0, SIZE_TABLE.length - 1);
        int halfWidth = SIZE_TABLE[idx][0];
        int halfHeight = SIZE_TABLE[idx][1];
        return SolidObjectParams.of(halfWidth + 0x0B, halfHeight, halfHeight + 1);
    }

    @Override
    public int getOnScreenHalfWidth() {
        // Render_Sprites builds the on-screen flag from the object's own
        // width_pixels/height_pixels bytes (sonic3k.asm:36347-36370), and
        // Obj_AIZLRZEMZRock writes both from AIZLRZEMZRock_SizeData indexed by
        // subtype >> 4 (sonic3k.asm:43844-43854, table at 43832-43840). The
        // shared 16-px default is wrong for every rock size: it widens the
        // horizontal gate for the $18/$28-wide entries and, worse, keeps the
        // $F-tall entries "on screen" one frame past the ROM, because the
        // vertical test is y_pos >= camera_y - height_pixels. That extra frame
        // leaves the rock solid for one pass after the camera has scrolled off
        // its bottom edge, so SolidObject_cont still runs its side push
        // (sonic3k.asm:41396-41398 gates that path on render_flags bit 7).
        return SIZE_TABLE[Math.clamp(sizeIndex, 0, SIZE_TABLE.length - 1)][0];
    }

    @Override
    public int getOnScreenHalfHeight() {
        // height_pixels from the same AIZLRZEMZRock_SizeData entry
        // (sonic3k.asm:43852-43854); see getOnScreenHalfWidth.
        return SIZE_TABLE[Math.clamp(sizeIndex, 0, SIZE_TABLE.length - 1)][1];
    }

    @Override
    public int getBalanceWidthPixels() {
        // Obj_AIZLRZEMZRock stores byte_1F9D0's unpadded width in
        // width_pixels. Tails_Move reads that byte for its on-object balance
        // window; SolidObjectFull alone receives the separate +$B extension.
        // (sonic3k.asm:43838-43848,43922-43935,27820-27837).
        return SIZE_TABLE[Math.clamp(sizeIndex, 0, SIZE_TABLE.length - 1)][0];
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj_AIZLRZEMZRock calls SolidObjectFull (sonic3k.asm:43935). For a player
        // who is not standing on the rock, SolidObjectFull_1P branches to loc_1DF88 ->
        // SolidObject_cont (sonic3k.asm:41022-41023, 41399), whose initial X gate is
        // cmp.w d3,d0 / bhi loc_1E0A2 (41403-41406): contact when d0 <= 2*halfwidth,
        // so the player's centre sitting exactly on the rock's right solid edge
        // (relX == width*2) is an inclusive zero-distance side contact and re-sets
        // Status_Push via loc_1E06E (41494-41500). Without this, AIZ2 trace f14193 --
        // Sonic rolling left into the rock at x=0x328B, exactly on its right edge --
        // dropped the push the ROM keeps (status 0x21 vs 0x01) after the roll-stop
        // animation change cleared it. Matches the horizontal-spring SolidObjectFull2_1P
        // inclusive-edge case.
        return true;
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
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // The intact AIZ/LRZ rock routines live in ROM bank $0001. S3K
        // sub_13EFC copies word 0 of the stood-on SST into Tails_CPU_interact.
        return 0x0001;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (breaking || variant == ZoneVariant.UNKNOWN || variant.artKey == null) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        PatternSpriteRenderer renderer = renderManager.getRenderer(variant.artKey);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(displayFrame, currentX, currentY, hFlip, vFlip);
        }
    }

    private void breakRock(AbstractPlayableSprite player, SolidCheckpointBatch batch) {
        breaking = true;
        releaseStandingParticipants(batch);

        if (isOnScreen()) {
            try {
                services().playSfx(Sonic3kSfx.COLLAPSE.id);
            } catch (Exception e) {
                // Ignore audio failures.
            }
        }

        spawnDebrisFragments(player);
        setDestroyed(true);
    }

    private void releaseStandingParticipants(SolidCheckpointBatch batch) {
        if (batch == null) {
            return;
        }
        for (var entry : batch.perPlayer().entrySet()) {
            if (!(entry.getKey() instanceof AbstractPlayableSprite participant)
                    || entry.getValue() == null
                    || !entry.getValue().standingNow()) {
                continue;
            }
            // sub_1FF1E clears both native standing bits before the side-break
            // debris conversion, regardless of which player broke the rock.
            participant.setAir(true);
            participant.setOnObject(false);
            var objectManager = services().objectManager();
            if (objectManager != null) {
                objectManager.clearRidingObject(participant);
            }
        }
    }

    private void spawnDebrisFragments(AbstractPlayableSprite player) {
        if (variant.artKey == null) {
            return;
        }

        int frameIdx = Math.clamp(sizeIndex, 0, DEBRIS_POSITIONS.length - 1);
        int[][] positions = DEBRIS_POSITIONS[frameIdx];
        int[][] velocities = DEBRIS_VELOCITIES[frameIdx];
        int fragmentCount = positions.length;
        int debrisStartFrame = variant.debrisBaseFrame;

        int firstFragment = 0;
        var objectManager = services().objectManager();
        if (fragmentCount > 0 && objectManager != null && getSlotIndex() >= 0) {
            RockDebrisChild debris = createDebrisFragment(
                    positions, velocities, debrisStartFrame, 0);
            int transferredSlot = ObjectLifetimeOps.detachSlotForTransfer(this);
            ObjectLifetimeOps.addReplacementAtTransferredSlot(
                    objectManager, debris, transferredSlot);
            firstFragment = 1;
        }

        for (int i = firstFragment; i < fragmentCount; i++) {
            RockDebrisChild debris = createDebrisFragment(
                    positions, velocities, debrisStartFrame, i);
            spawnDynamicObject(debris);
        }
    }

    private RockDebrisChild createDebrisFragment(int[][] positions, int[][] velocities,
                                                  int debrisStartFrame, int index) {
        int xPos = currentX + positions[index][0];
        int yPos = currentY + positions[index][1];
        int xVel = velocities[index][0];
        int yVel = velocities[index][1];
        int debrisFrame = debrisStartFrame + (index % 4);

        ObjectSpawn debrisSpawn = new ObjectSpawn(xPos, yPos, 0, 0, 0, false, 0);
        return new RockDebrisChild(
                debrisSpawn, xVel, yVel, debrisFrame, variant.artKey);
    }

    private void handlePush(AbstractPlayableSprite player, PlayerSolidContactResult result) {
        // ROM sub_200A2/sub_200CC moves the concrete player whose pushing bit is
        // set only when that player's saved pre-helper status also had
        // Status_Push (sonic3k.asm:44446-44478). The checkpoint preserves both
        // phases per player; using the old aggregate latch could let P2's first
        // contact move P1 one frame early.
        if (player == null || result == null
                || !result.pushingNow() || !result.pushingLastFrame()
                || !result.preContact().pushing()) {
            return;
        }

        int playerX = player.getCentreX();
        if (currentX >= playerX) {
            return;
        }

        pushRateTimer--;
        if (pushRateTimer >= 0) {
            return;
        }
        pushRateTimer = PUSH_RATE_PERIOD;

        if (pushDistanceRemaining <= 0) {
            return;
        }
        pushDistanceRemaining--;
        currentX--;
        // subq.w #1,x_pos(a1) changes only the ROM integer word and keeps
        // x_sub untouched (sonic3k.asm:44472-44473).
        NativePositionOps.addXPosPreserveSubpixel(player, -1);
        int halfHeight = SIZE_TABLE[Math.clamp(sizeIndex, 0, SIZE_TABLE.length - 1)][1];
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, halfHeight);
        if (floor.foundSurface()) {
            // sub_200CC follows each horizontal push with ObjCheckFloorDist and
            // adds d1 even when the adjacent floor is below the rock
            // (sonic3k.asm:44474-44475).
            currentY += floor.distance();
        }
    }

    private boolean isKnuckles() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration()) == PlayerCharacter.KNUCKLES;
    }

    private ZoneVariant resolveVariant() {
        try {
            int zone = services().romZoneId();
            int act = services().currentAct();
            if (zone == Sonic3kZoneIds.ZONE_AIZ) {
                return act == 0 ? ZoneVariant.AIZ1 : ZoneVariant.AIZ2;
            }
            if (zone == Sonic3kZoneIds.ZONE_LRZ) {
                return act == 0 ? ZoneVariant.LRZ1 : ZoneVariant.LRZ2;
            }
        } catch (Exception e) {
            LOG.fine("Could not resolve zone variant: " + e.getMessage());
        }
        return ZoneVariant.UNKNOWN;
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    private ObjectPlayerQuery playerQuery(PlayableEntity primary) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> primary, query::sidekicks);
    }
}
