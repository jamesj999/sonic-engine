package com.openggf.game.sonic3k.objects;

import com.openggf.game.DynamicWaterHandler;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Lbz2KnucklesDynamicWaterHandler;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K S3KL object $1B - Launch Base Zone Act 2 pipe plug.
 *
 * <p>ROM reference: {@code Obj_LBZPipePlug} and {@code PipePlugSmashObject}
 * ({@code sonic3k.asm:53515-53779}).
 */
public final class LbzPipePlugObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_LBZPipePlug} is installed from the S3K object pointer table at
     * {@code $000273E4} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:53520).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }

    private static final int SOLID_HALF_WIDTH = 0x1B;
    private static final int AIR_HALF_HEIGHT = 0x20;
    private static final int GROUND_HALF_HEIGHT = 0x21;
    private static final int WIDTH_PIXELS = 0x10;
    private static final int HEIGHT_PIXELS = 0x20;
    private static final int INITIAL_MAPPING_FRAME = 7;
    private static final int PRIORITY_BUCKET = 4; // ROM priority $200.
    private static final int BREAK_ANIMATION_ID = 2;
    private static final int BREAK_SPEED_THRESHOLD = 0x0480;
    private static final int PLAYER_BREAK_NUDGE = 4;
    private static final int LEFT_SIDE_CORRECTION = -8;
    private static final int FALL_GRAVITY = 0x18;
    private static final int WATER_TARGET = 0x0660;
    private static final int WATER_SPEED_FAST = 2;
    private static final int DELAYED_TUNNEL_Y_OFFSET = 2;
    private static final int EXHAUST_Y_OFFSET = -0x20;
    private static final int EXHAUST_Y_VEL = 1;
    private static final int SMALL_SHARD_COUNT = 12;
    private static final int LARGE_CHUNK_COUNT = 4;

    private static final int[] LEFT_SHARD_VELOCITIES = {
            -0x380, -0x240, -0x300, -0x250, -0x280, -0x260, -0x200, -0x260,
            -0x180, -0x250, -0x100, -0x240, -0x380, -0x0C0, -0x300, -0x0D0,
            -0x280, -0x0E0, -0x200, -0x0E0, -0x180, -0x0D0, -0x100, -0x0C0,
            -0x400, -0x2C0, -0x100, -0x2C0, -0x400, -0x080, -0x100, -0x080
    };
    private static final int[] RIGHT_SHARD_VELOCITIES = {
            0x100, -0x240, 0x180, -0x250, 0x200, -0x260, 0x280, -0x260,
            0x300, -0x250, 0x380, -0x240, 0x100, -0x0C0, 0x180, -0x0D0,
            0x200, -0x0E0, 0x280, -0x0E0, 0x300, -0x0D0, 0x380, -0x0C0,
            0x100, -0x2C0, 0x400, -0x2C0, 0x100, -0x080, 0x400, -0x080
    };
    private static final int[] SHARD_MAPPING_FRAMES = {
            0, 1, 2, 3, 5, 0, 0, 1, 2, 3, 5, 0
    };
    private static final int[] SHARD_X_OFFSETS = {
            -0x0F, -0x0C, -0x07, -0x01, 0x04, 0x07, -0x0F, -0x0C,
            -0x07, -0x01, 0x04, 0x07
    };
    private static final int[] SHARD_Y_OFFSETS = {
            -0x10, -0x10, -0x10, -0x10, -0x10, -0x10, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
    };

    private final SubpixelMotion.State motion;
    private int mappingFrame = INITIAL_MAPPING_FRAME;
    private int framePieceIndex = -1;
    private boolean broken;

    public LbzPipePlugObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LBZPipePlug");
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!broken) {
            SolidCheckpointBatch batch = checkpointAll();
            applyBreakContacts(player, batch);
            return;
        }

        SubpixelMotion.moveSprite2(motion);
        motion.yVel += FALL_GRAVITY;
        updateDynamicSpawn(motion.x, motion.y);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, AIR_HALF_HEIGHT, GROUND_HALF_HEIGHT);
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return !broken;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (broken || !contact.pushing() || !(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }

        int savedXVel = player.getXSpeed();
        if (player.getAnimationId() != BREAK_ANIMATION_ID || Math.abs((short) savedXVel) < BREAK_SPEED_THRESHOLD) {
            return;
        }

        breakFrom(player, savedXVel, null, false);
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return HEIGHT_PIXELS;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_PIPE_PLUG);
        if (renderer != null) {
            if (framePieceIndex >= 0) {
                renderer.drawFramePieceByIndex(mappingFrame, framePieceIndex, getX(), getY(), false, false);
            } else {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false, 2);
            }
        }
    }

    String artKeyForTesting() {
        return Sonic3kObjectArtKeys.LBZ_PIPE_PLUG;
    }

    int mappingFrameForTesting() {
        return mappingFrame;
    }

    boolean isBrokenForTesting() {
        return broken;
    }

    private void applyBreakContacts(PlayableEntity mainPlayer, SolidCheckpointBatch batch) {
        if (batch == null || batch.perPlayer().isEmpty()) {
            return;
        }
        if (tryBreakFromCheckpoint(mainPlayer, batch, true)) {
            return;
        }
        for (var entry : batch.perPlayer().entrySet()) {
            PlayableEntity candidate = entry.getKey();
            if (candidate != mainPlayer && tryBreakFromCheckpoint(candidate, batch, false)) {
                return;
            }
        }
    }

    private boolean tryBreakFromCheckpoint(PlayableEntity entity, SolidCheckpointBatch batch, boolean mainPlayerPath) {
        PlayerSolidContactResult result = batch.perPlayer().get(entity);
        if (broken || result == null || !result.pushingNow()
                || !(entity instanceof AbstractPlayableSprite player)) {
            return false;
        }
        int savedXVel = result.preContact().xSpeed();
        if (result.preContact().animationId() != BREAK_ANIMATION_ID
                || Math.abs((short) savedXVel) < BREAK_SPEED_THRESHOLD) {
            return false;
        }
        breakFrom(player, savedXVel, batch, mainPlayerPath);
        return true;
    }

    private void breakFrom(AbstractPlayableSprite player, int savedXVel,
            SolidCheckpointBatch batch, boolean mainPlayerPath) {
        clearPushStateForBreak(player, savedXVel, batch, mainPlayerPath);
        int objectX = motion.x & 0xFFFF;
        NativePositionOps.addXPosPreserveSubpixel(player, PLAYER_BREAK_NUDGE);
        int playerX = player.getCentreX() & 0xFFFF;
        boolean brokeFromLeft = Integer.compareUnsigned(objectX, playerX) >= 0;
        if (brokeFromLeft) {
            NativePositionOps.addXPosPreserveSubpixel(player, LEFT_SIDE_CORRECTION);
        }

        int subtype = spawn.subtype() & 0xFF;
        if (subtype != 0) {
            if (subtype == 0x1F) {
                applyKnucklesWaterState(player);
            } else {
                spawnChild(() -> new AutomaticTunnelDelayedObjectInstance(
                        buildSpawnAt(motion.x, motion.y + DELAYED_TUNNEL_Y_OFFSET), subtype));
            }
            spawnChild(() -> new TunnelExhaustControlObjectInstance(
                    buildSpawnAt(motion.x, motion.y + EXHAUST_Y_OFFSET), subtype, 0, EXHAUST_Y_VEL));
        }

        spawnShards(brokeFromLeft);
        services().playSfx(Sonic3kSfx.COLLAPSE.id);
        broken = true;
    }

    private void clearPushStateForBreak(AbstractPlayableSprite player, int savedXVel,
            SolidCheckpointBatch batch, boolean mainPlayerPath) {
        if (batch != null) {
            for (var entry : batch.perPlayer().entrySet()) {
                if (entry.getKey() == player || !entry.getValue().pushingNow()
                        || !(entry.getKey() instanceof AbstractPlayableSprite other)) {
                    continue;
                }
                // ROM's test-and-clear on the plug's own p2 bit gates the
                // Player_2 leg (docs/skdisasm/sonic3k.asm:53562, :53587).
                services().objectManager().solidContacts().releaseObjectPushLatch(other, this);
                other.setPushing(false);
                if (mainPlayerPath && entry.getValue().preContact().animationId() == BREAK_ANIMATION_ID) {
                    int otherSavedXVel = entry.getValue().preContact().xSpeed();
                    other.setXSpeed((short) otherSavedXVel);
                    other.setGSpeed((short) otherSavedXVel);
                }
            }
        }
        // ROM loc_274D6 test-and-clears the plug's own p1 bit before the
        // Player_1 break (docs/skdisasm/sonic3k.asm:53592).
        services().objectManager().solidContacts().releaseObjectPushLatch(player, this);
        player.setPushing(false);
        player.setXSpeed((short) savedXVel);
        player.setGSpeed((short) savedXVel);
    }

    private void applyKnucklesWaterState(AbstractPlayableSprite player) {
        WaterSystem water = services().waterSystem();
        if (water == null) {
            return;
        }
        water.setWaterLevelTarget(Sonic3kZoneIds.ZONE_LBZ, 1, WATER_TARGET);
        if (player != null && player.isSuperSonic()) {
            water.setWaterSpeed(Sonic3kZoneIds.ZONE_LBZ, 1, WATER_SPEED_FAST);
        }
        DynamicWaterHandler handler = water.getDynamicWaterHandler(Sonic3kZoneIds.ZONE_LBZ, 1);
        if (handler instanceof Lbz2KnucklesDynamicWaterHandler lbz2Knuckles) {
            lbz2Knuckles.setPipePlugDestroyed(true);
        }
    }

    private void spawnShards(boolean brokeFromLeft) {
        int[] velocities = brokeFromLeft ? RIGHT_SHARD_VELOCITIES : LEFT_SHARD_VELOCITIES;
        for (int i = 0; i < SMALL_SHARD_COUNT; i++) {
            int xVel = velocities[i * 2];
            int yVel = velocities[i * 2 + 1];
            int frame = SHARD_MAPPING_FRAMES[i];
            int x = motion.x + SHARD_X_OFFSETS[i];
            int y = motion.y + SHARD_Y_OFFSETS[i];
            spawnChild(() -> new LbzPipePlugShardInstance(buildSpawnAt(x, y), frame, -1, xVel, yVel, true));
        }
        int velocityIndex = SMALL_SHARD_COUNT * 2;
        motion.xVel = velocities[velocityIndex];
        motion.yVel = velocities[velocityIndex + 1];
        framePieceIndex = 0;
        for (int i = 1; i < LARGE_CHUNK_COUNT; i++) {
            int xVel = velocities[velocityIndex + i * 2];
            int yVel = velocities[velocityIndex + i * 2 + 1];
            int pieceIndex = i;
            spawnChild(() -> new LbzPipePlugShardInstance(
                    buildSpawnAt(motion.x, motion.y), INITIAL_MAPPING_FRAME, pieceIndex, xVel, yVel, false));
        }
    }
}
