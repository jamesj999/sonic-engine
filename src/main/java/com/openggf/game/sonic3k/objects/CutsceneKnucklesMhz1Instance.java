package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;

/**
 * Generic cutscene Knuckles subtype $1C: {@code CutsceneKnux_MHZ1}.
 *
 * <p>This is the full offscreen actor created by {@code ChildObjDat_665AA}.
 * The visible peering sprite is a later {@code ChildObjDat_665B0} child, not
 * the subtype $1C object itself.
 */
public final class CutsceneKnucklesMhz1Instance extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int INITIAL_X = 0x02B0;
    private static final int INITIAL_Y = 0x066C;
    private static final int WALK_TARGET_X = 0x0360;
    private static final int WALK_X_VELOCITY = 0x0200;
    private static final int BUTTON_APPROACH_X_OFFSET = 0x28;
    private static final int BUTTON_JUMP_X_VELOCITY = 0x0100;
    private static final int BUTTON_JUMP_Y_VELOCITY = -0x0400;
    private static final int BUTTON_BOUNCE_Y_VELOCITY = -0x0300;
    private static final int FIRST_JUMP_Y_RADIUS = 0x17;
    private static final int BOUNCE_Y_RADIUS = 0x13;
    private static final int WAIT_BEFORE_WALK = (2 * 60) - 1;
    private static final int PRIORITY = 3;
    private static final int INITIAL_MAPPING_FRAME = 0x16;
    private static final int WALK_ANIMATION_DELAY = 4;
    private static final int[] WALK_ANIMATION_FRAMES = {
            0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11
    };
    private static final int JUMP_ANIMATION_DELAY = 1;
    private static final int[] JUMP_ANIMATION_FRAMES = {
            0x08, 0x04, 0x08, 0x05, 0x08, 0x06, 0x08, 0x07
    };

    private final Mhz1CutsceneButtonInstance parentButton;
    private final SubpixelMotion.State motion = new SubpixelMotion.State(
            INITIAL_X, INITIAL_Y, 0, 0, WALK_X_VELOCITY, 0);
    private Routine routine = Routine.INIT;
    private int timer;
    private int mappingFrame = INITIAL_MAPPING_FRAME;
    private int animFrameTimer;
    private int walkAnimationIndex;
    private int jumpAnimationIndex;
    private int yRadius = BOUNCE_Y_RADIUS;
    private boolean peerSpawned;
    private boolean peerReturned;
    private boolean themeTransitionSpawned;

    public CutsceneKnucklesMhz1Instance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    CutsceneKnucklesMhz1Instance(ObjectSpawn spawn, Mhz1CutsceneButtonInstance parentButton) {
        super(spawn, "CutsceneKnuxMHZ1");
        this.parentButton = parentButton;
    }

    @Override
    public CutsceneKnucklesMhz1Instance recreateForRewind(RewindRecreateContext ctx) {
        Mhz1CutsceneButtonInstance liveParent = findNearestLiveButton(ctx);
        return new CutsceneKnucklesMhz1Instance(ctx.spawn(), liveParent);
    }

    @Override
    public int getX() {
        return motion.x;
    }

    @Override
    public int getY() {
        return motion.y;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        switch (routine) {
            case INIT -> routineInit();
            case WAIT_BEFORE_WALK -> routineWaitBeforeWalk();
            case WALK_TO_TREE -> routineWalkToTree();
            case WAIT_FOR_PEER -> routineWaitForPeer();
            case MOVE_TO_BUTTON_JUMP -> routineMoveToButtonJump();
            case JUMP_TO_BUTTON, BOUNCE_AFTER_BUTTON -> routineJumpWithFloorCallback();
            case EXIT -> {
                if (!isOnScreen(0)) {
                    setDestroyed(true);
                    return;
                }
                animateWalk();
                SubpixelMotion.moveSprite2(motion);
            }
        }
    }

    @Override
    public void onUnload() {
        if (parentButton != null) {
            parentButton.detachSpawnedKnuckles(this);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CUTSCENE_KNUCKLES);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, motion.x, motion.y, false, false);
    }

    void signalPeerReturned() {
        peerReturned = true;
    }

    int getRoutineForTest() {
        return switch (routine) {
            case INIT -> 0x00;
            case WAIT_BEFORE_WALK -> 0x02;
            case WALK_TO_TREE -> 0x04;
            case WAIT_FOR_PEER -> 0x06;
            case MOVE_TO_BUTTON_JUMP -> 0x08;
            case JUMP_TO_BUTTON -> 0x0A;
            case BOUNCE_AFTER_BUTTON -> 0x0C;
            case EXIT -> 0x0E;
        };
    }

    private void routineInit() {
        timer = WAIT_BEFORE_WALK;
        fadeAndPlayKnucklesThemeOnce();
        AizIntroArtLoader.applyKnucklesPalette(services());
        routine = Routine.WAIT_BEFORE_WALK;
    }

    private void fadeAndPlayKnucklesThemeOnce() {
        if (themeTransitionSpawned) {
            return;
        }
        themeTransitionSpawned = true;
        spawnDynamicObject(new SongFadeTransitionInstance(2 * 60, Sonic3kMusic.KNUCKLES.id));
    }

    private void routineWaitBeforeWalk() {
        timer--;
        if (timer < 0) {
            routine = Routine.WALK_TO_TREE;
        }
    }

    private void routineWalkToTree() {
        if (motion.x >= WALK_TARGET_X) {
            motion.x = WALK_TARGET_X;
            routine = Routine.WAIT_FOR_PEER;
            spawnPeerOnce();
            return;
        }
        animateWalk();
        SubpixelMotion.moveSprite2(motion);
    }

    private void routineWaitForPeer() {
        if (!peerReturned) {
            return;
        }
        routine = Routine.MOVE_TO_BUTTON_JUMP;
        motion.xVel = WALK_X_VELOCITY;
        motion.yVel = 0;
    }

    private void routineMoveToButtonJump() {
        animateWalk();
        SubpixelMotion.moveSprite2(motion);
        int targetX = buttonApproachTargetX();
        if (motion.x < targetX) {
            return;
        }
        motion.x = targetX;
        motion.xSub = 0;
        motion.xVel = BUTTON_JUMP_X_VELOCITY;
        motion.yVel = BUTTON_JUMP_Y_VELOCITY;
        yRadius = FIRST_JUMP_Y_RADIUS;
        resetJumpAnimation();
        routine = Routine.JUMP_TO_BUTTON;
    }

    private void routineJumpWithFloorCallback() {
        animateJump();
        SubpixelMotion.moveSprite(motion, SubpixelMotion.S3K_GRAVITY);
        if (motion.yVel < 0 || motion.y < INITIAL_Y) {
            return;
        }
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(motion.x, motion.y, yRadius);
        if (!floor.foundSurface()) {
            if (motion.y < INITIAL_Y) {
                return;
            }
            motion.y = INITIAL_Y;
        } else {
            if (!floor.hasCollision()) {
                return;
            }
            motion.y += floor.distance();
        }
        motion.ySub = 0;
        if (routine == Routine.JUMP_TO_BUTTON) {
            yRadius = BOUNCE_Y_RADIUS;
            motion.yVel = BUTTON_BOUNCE_Y_VELOCITY;
            routine = Routine.BOUNCE_AFTER_BUTTON;
            return;
        }
        motion.xVel = WALK_X_VELOCITY;
        motion.yVel = 0;
        resetWalkAnimation();
        routine = Routine.EXIT;
    }

    private int buttonApproachTargetX() {
        if (parentButton == null) {
            return 0x0380 - BUTTON_APPROACH_X_OFFSET;
        }
        return parentButton.getX() - BUTTON_APPROACH_X_OFFSET;
    }

    private static Mhz1CutsceneButtonInstance findNearestLiveButton(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        Mhz1CutsceneButtonInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance object : ctx.objectServices().objectManager().getActiveObjects()) {
            if (!(object instanceof Mhz1CutsceneButtonInstance candidate) || candidate.isDestroyed()) {
                continue;
            }
            if (spawn == null) {
                return candidate;
            }
            long dx = candidate.getX() - spawn.x();
            long dy = candidate.getY() - spawn.y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private void spawnPeerOnce() {
        if (peerSpawned) {
            return;
        }
        peerSpawned = true;
        spawnFreeChild(() -> new CutsceneKnucklesMhz1PeerInstance(new ObjectSpawn(
                0, 0, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 0, 0, false, 0), this));
    }

    private void animateWalk() {
        if (mappingFrame == INITIAL_MAPPING_FRAME && walkAnimationIndex == 0 && animFrameTimer == 0) {
            mappingFrame = WALK_ANIMATION_FRAMES[0];
            animFrameTimer = WALK_ANIMATION_DELAY;
            return;
        }
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return;
        }
        walkAnimationIndex = (walkAnimationIndex + 1) % WALK_ANIMATION_FRAMES.length;
        mappingFrame = WALK_ANIMATION_FRAMES[walkAnimationIndex];
        animFrameTimer = WALK_ANIMATION_DELAY;
    }

    private void resetWalkAnimation() {
        walkAnimationIndex = 0;
        mappingFrame = WALK_ANIMATION_FRAMES[0];
        animFrameTimer = WALK_ANIMATION_DELAY;
    }

    private void resetJumpAnimation() {
        jumpAnimationIndex = 0;
        mappingFrame = JUMP_ANIMATION_FRAMES[0];
        animFrameTimer = JUMP_ANIMATION_DELAY;
    }

    private void animateJump() {
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return;
        }
        jumpAnimationIndex = (jumpAnimationIndex + 1) % JUMP_ANIMATION_FRAMES.length;
        mappingFrame = JUMP_ANIMATION_FRAMES[jumpAnimationIndex];
        animFrameTimer = JUMP_ANIMATION_DELAY;
    }

    private enum Routine {
        INIT,
        WAIT_BEFORE_WALK,
        WALK_TO_TREE,
        WAIT_FOR_PEER,
        MOVE_TO_BUTTON_JUMP,
        JUMP_TO_BUTTON,
        BOUNCE_AFTER_BUTTON,
        EXIT
    }
}
