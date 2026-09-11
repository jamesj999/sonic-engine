package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * CPZ Boss Pipe - Extending pipe mechanism that sucks up liquid.
 * ROM Reference: s2.asm Obj5D (ROUTINE_PIPE, ROUTINE_PIPE_PUMP, ROUTINE_PIPE_RETRACT)
 * Extends down from the boss, pumps, then retracts.
 */
public class CPZBossPipe extends AbstractObjectInstance implements RewindRecreatable {

    private static final int SUB_WAIT = 0;
    private static final int SUB_EXTEND = 2;
    private static final int SUB_PUMP_INIT = 0;
    private static final int SUB_PUMP_ANIMATE = 2;
    private static final int SUB_PUMP_END = 4;
    private static final int SUB_RETRACT = 0;

    private static final int ROUTINE_PIPE = 0;
    private static final int ROUTINE_PUMP = 1;
    private static final int ROUTINE_RETRACT = 2;
    private static final int ROUTINE_SEGMENT = 3;

    private static final int PIPE_SEGMENT_COUNT = 0x0C;
    private final Sonic2CPZBossInstance mainBoss;

    private int x;
    private int y;
    private int renderFlags;
    private int routine;
    private int routineSecondary;
    private int anim;
    private int mappingFrame;
    private int yOffset;
    private int pipeSegments;
    private int timer;
    private int timer3;
    private boolean retractFlag;

    private final List<CPZBossPipeSegment> segments;
    private ObjectAnimationState animationState;

    public CPZBossPipe(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss) {
        super(spawn, "CPZ Boss Pipe");
        this.mainBoss = mainBoss;
        this.x = spawn.x();
        this.y = spawn.y();
        this.renderFlags = spawn.renderFlags();
        this.routine = ROUTINE_PIPE;
        this.routineSecondary = SUB_WAIT;
        this.anim = 1;
        this.mappingFrame = 0;
        this.yOffset = 0;
        this.pipeSegments = PIPE_SEGMENT_COUNT;
        this.timer = 0;
        this.timer3 = 2;
        this.retractFlag = false;
        this.segments = new ArrayList<>();
        this.animationState = new ObjectAnimationState(CPZBossAnimations.getDripperAnimations(), anim, mappingFrame);
        animate();  // Initialize mappingFrame to correct first frame for this anim
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CPZBossInstance boss = CpzBossRewindLinks.nearestBoss(ctx);
        return boss == null ? null : new CPZBossPipe(ctx.spawn(), boss);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        switch (routine) {
            case ROUTINE_PIPE -> updatePipe();
            case ROUTINE_PUMP -> updatePump();
            case ROUTINE_RETRACT -> updateRetract();
            case ROUTINE_SEGMENT -> updateSegment();
        }
    }

    private void updatePipe() {
        switch (routineSecondary) {
            case SUB_WAIT -> updatePipeWait();
            case SUB_EXTEND -> updatePipeExtend();
        }
    }

    private void updatePipeWait() {
        if (mainBoss == null || mainBoss.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        if (!mainBoss.isPipeActive()) {
            return;
        }

        x = mainBoss.getX();
        y = mainBoss.getY() + 0x18;
        renderFlags = mainBoss.getRenderFlags();
        pipeSegments = PIPE_SEGMENT_COUNT;
        routineSecondary = SUB_EXTEND;
        pipeSegments--;
        int segmentIndex = (PIPE_SEGMENT_COUNT - 1) - pipeSegments;
        yOffset = segmentIndex * 8;
        anim = 1;
        updateSegment();
    }

    private void updatePipeExtend() {
        if (mainBoss == null || mainBoss.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        if (services().objectManager() == null) {
            updateSegment();
            return;
        }

        int nextSegments = pipeSegments - 1;
        if (nextSegments < 0) {
            routineSecondary = SUB_PUMP_INIT;
            routine = ROUTINE_PUMP;
            updateSegment();
            return;
        }

        int segmentIndex = (PIPE_SEGMENT_COUNT - 1) - nextSegments;
        int offset = segmentIndex * 8;
        spawnPipeSegment(offset);
        pipeSegments = nextSegments;
        updateSegment();
    }

    private void updatePump() {
        switch (routineSecondary) {
            case SUB_PUMP_INIT -> updatePumpInit();
            case SUB_PUMP_ANIMATE -> updatePumpAnimate();
            case SUB_PUMP_END -> updatePumpEnd();
        }
    }

    private void updatePumpInit() {
        if (services().objectManager() == null) {
            updateSegment();
            return;
        }

        spawnPumpHead();
        spawnDripper();
        routine = ROUTINE_SEGMENT;
        routineSecondary = 0;
        anim = 1;
        updateSegment();
    }

    private void updatePumpAnimate() {
        if (mainBoss == null || mainBoss.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        if (mainBoss.isBossDefeated()) {
            setDestroyed(true);
            return;
        }

        x = mainBoss.getX();
        y = mainBoss.getY();
        renderFlags = mainBoss.getRenderFlags();

        timer--;
        if (timer == 0) {
            timer = 0x12;
            yOffset -= 8;
            if (yOffset < 0) {
                timer = 6;
                routineSecondary = SUB_PUMP_END;
                return;
            }
            if (yOffset == 0) {
                anim = 3;
                timer = 0x0C;
            }
        }
        y += yOffset;
        animate();
    }

    private void updatePumpEnd() {
        timer--;
        if (timer != 0) {
            return;
        }

        timer3--;
        if (timer3 != 0) {
            anim = 2;
            timer = 0x12;
            routineSecondary = SUB_PUMP_ANIMATE;
            yOffset = 0x58;
            animate();  // Sync mappingFrame with new anim before returning
            return;
        }

        beginRetractFromPump();
    }

    void beginRetractFromPump() {
        // Obj5D_Pipe_Pump_4 switches the control segment to Obj5D_Pipe_Retract
        // with Obj5D_y_offset = $B*8 before deleting the pump head (s2.asm:62208).
        routine = ROUTINE_RETRACT;
        routineSecondary = SUB_RETRACT;
        yOffset = 0x58;
        retractFlag = false;
    }

    private void updateRetract() {
        if (mainBoss == null || mainBoss.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        if (!retractFlag) {
            // FixBugs = 0 (docs/s2disasm/s2.asm:27 `fixBugs = 0`): the shipped ROM
            // takes the `else` arm of Obj5D_Pipe_Retract_ChkID
            // (docs/s2disasm/s2.asm:62244-62253), which borrows d7 -- RunObjects'
            // OWN object-loop counter -- as a scratch register for the id compare:
            //
            //     moveq   #0,d7
            //     move.b  #ObjID_CPZBoss,d7
            //     cmp.b   id(a1),d7
            //
            // The disassembly's own comment says so: "'d7' should not be used
            // here. This causes the 'RunObjects' routine to either run too few
            // objects or too many objects, causing all sorts of errors." The
            // fixBugs arm uses `cmpi.b #ObjID_CPZBoss,id(a1)` instead and leaves
            // d7 alone, so under it none of this happens. The engine models the
            // shipped arm, because the traces record shipped-ROM behaviour.
            //
            // The value left in the counter is the boss's own object id --
            // ObjID_CPZBoss = $5D = 93 (docs/s2disasm/s2.constants.asm, the Obj5D
            // pointer-table entry at docs/s2disasm/s2.asm:30009). It is a number
            // that happens to be lying in the compare, not a duration: nothing
            // here is tuned or measured.
            //
            // The search is only reached while Obj5D_flag is clear; once the
            // retract finishes, loc_2DFD8 sets it and the entry test at
            // docs/s2disasm/s2.asm:62220-62221 jumps past the search -- and so
            // past this write -- on every later frame.
            services().objectManager().overrideRemainingObjectLoopSlots(
                    this, Sonic2ObjectIds.CPZ_BOSS);
            // Signal segments to retract one by one
            for (int i = segments.size() - 1; i >= 0; i--) {
                CPZBossPipeSegment seg = segments.get(i);
                if (!seg.isDestroyed() && !seg.isRetracting()) {
                    seg.startRetract();
                    yOffset -= 8;
                    if (yOffset <= 0) {
                        retractFlag = true;
                    }
                    break;
                }
            }
        }

        if (retractFlag && yOffset <= 0) {
            setDestroyed(true);
            mainBoss.onPipeComplete();
        }

        updateSegment();
    }

    private void updateSegment() {
        if (mainBoss == null || mainBoss.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        if (mainBoss.isBossDefeated()) {
            // Convert to falling part
            spawnFallingPart();
            setDestroyed(true);
            return;
        }

        x = mainBoss.getX();
        y = mainBoss.getY();
        renderFlags = mainBoss.getRenderFlags();

        if (routineSecondary == 4) {
            y += 0x18;
        }

        y += yOffset;
        anim = 1;
        animate();
    }

    private void spawnPipeSegment(int offset) {
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn segSpawn = new ObjectSpawn(x, y, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        CPZBossPipeSegment segment = spawnChild(() -> new CPZBossPipeSegment(segSpawn, mainBoss, this, offset));
        segments.add(segment);
    }

    /**
     * Re-appends a rewind-recreated segment to this pipe's segment list. The list is a
     * final identity collection (structural rewind state rebuilt by its owner, not captured
     * through the identity table), so each recreated {@link CPZBossPipeSegment} relinks
     * itself here during restore — keeping the retract sequence driven off a populated list.
     */
    void reregisterRestoredSegment(CPZBossPipeSegment segment) {
        segments.add(segment);
    }

    private void spawnPumpHead() {
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn pumpSpawn = new ObjectSpawn(x, y, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        spawnChild(() -> new CPZBossPipePump(pumpSpawn, mainBoss, this));
    }

    private void spawnDripper() {
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn dripperSpawn = new ObjectSpawn(x, y, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        spawnChild(() -> new CPZBossDripper(dripperSpawn, mainBoss, this));
    }

    private void spawnFallingPart() {
        if (services().objectManager() == null) {
            return;
        }
        var motion = randomPipeMotion();
        ObjectSpawn partSpawn = new ObjectSpawn(x, y + yOffset, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        spawnChild(() -> new CPZBossFallingPart(partSpawn, 1, motion.xVel(), motion.timer()));
    }

    private Sonic2Rng.PipeShardMotion randomPipeMotion() {
        return Sonic2Rng.nextPipeShardMotion(services().rng());
    }

    private void animate() {
        if (animationState == null) {
            return;
        }
        animationState.setAnimId(anim);
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    public int getPipeX() {
        return x;
    }

    public int getPipeY() {
        return y;
    }

    public int getPipeYOffset() {
        return yOffset;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_BOSS_PARTS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (mappingFrame < 0) {
            return;
        }

        boolean flipped = (renderFlags & 1) != 0;
        renderer.drawFrameIndex(mappingFrame, x, y + yOffset, flipped, false);
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
        return 4;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), renderFlags, spawn.respawnTracked(), spawn.rawYWord());
    }
}
