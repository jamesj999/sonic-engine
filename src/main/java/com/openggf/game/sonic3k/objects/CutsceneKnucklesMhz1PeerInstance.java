package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * MHZ1 peering Knuckles child spawned by {@code Obj_MHZ1CutsceneButton}.
 *
 * <p>ROM reference: {@code Obj_CutsceneKnuckles} subtype $1C, which dispatches
 * to {@code loc_62F72}. The child uses {@code Map_MHZKnuxPeer} and
 * {@code ArtKosM_MHZKnuxPeer}, not the shared cutscene Knuckles DPLC sheet.
 */
public final class CutsceneKnucklesMhz1PeerInstance extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int INITIAL_X = 0x0374;
    private static final int INITIAL_Y = 0x066C;
    private static final int INITIAL_FRAME = 4;
    private static final int PRIORITY = 3;
    private static final int OUTWARD_X_VELOCITY = 0x0200;
    private static final int RETURN_X_VELOCITY = -0x0400;
    private static final int OUTWARD_WAIT = 7;
    private static final int RETURN_WAIT = 0x0F;
    private static final int[][] PEER_ANIMATION = {
            {0, 4},
            {0, 9},
            {1, 0x1D},
            {0, 4},
            {2, 4},
            {3, 0x1D},
            {2, 4},
            {0, 4},
            {1, 0x1D},
            {0, 4}
    };

    private final SubpixelMotion.State motion = new SubpixelMotion.State(
            INITIAL_X, INITIAL_Y, 0, 0, OUTWARD_X_VELOCITY, 0);
    private State state = State.MOVE_OUT;
    private int mappingFrame = INITIAL_FRAME;
    private int waitTimer = OUTWARD_WAIT;
    private int animFrameOffset;
    private int animTimer;
    private final CutsceneKnucklesMhz1Instance parent;

    public CutsceneKnucklesMhz1PeerInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    CutsceneKnucklesMhz1PeerInstance(ObjectSpawn spawn, CutsceneKnucklesMhz1Instance parent) {
        super(spawn, "CutsceneKnucklesMhz1Peer");
        this.parent = parent;
    }

    @Override
    public CutsceneKnucklesMhz1PeerInstance recreateForRewind(RewindRecreateContext ctx) {
        CutsceneKnucklesMhz1Instance liveParent = findNearestLiveParent(ctx);
        return new CutsceneKnucklesMhz1PeerInstance(ctx.spawn(), liveParent);
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
        switch (state) {
            case MOVE_OUT -> updateMoveOut();
            case ANIMATING -> updateAnimation();
            case MOVE_BACK -> updateMoveBack();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ1_CUTSCENE_KNUCKLES_PEER);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, motion.x, motion.y, false, false);
    }

    private void updateMoveOut() {
        SubpixelMotion.moveSprite2(motion);
        if (--waitTimer < 0) {
            state = State.ANIMATING;
        }
    }

    private void updateAnimation() {
        if (--animTimer >= 0) {
            return;
        }
        animFrameOffset += 2;
        int scriptIndex = animFrameOffset / 2;
        if (scriptIndex >= PEER_ANIMATION.length) {
            state = State.MOVE_BACK;
            motion.xVel = RETURN_X_VELOCITY;
            waitTimer = RETURN_WAIT;
            animFrameOffset = 0;
            return;
        }
        mappingFrame = PEER_ANIMATION[scriptIndex][0];
        animTimer = PEER_ANIMATION[scriptIndex][1];
    }

    private void updateMoveBack() {
        SubpixelMotion.moveSprite2(motion);
        if (--waitTimer < 0) {
            if (parent != null) {
                parent.signalPeerReturned();
            }
            setDestroyed(true);
        }
    }

    private static CutsceneKnucklesMhz1Instance findNearestLiveParent(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        CutsceneKnucklesMhz1Instance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance object : ctx.objectServices().objectManager().getActiveObjects()) {
            if (!(object instanceof CutsceneKnucklesMhz1Instance candidate) || candidate.isDestroyed()) {
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

    private enum State {
        MOVE_OUT,
        ANIMATING,
        MOVE_BACK
    }
}
