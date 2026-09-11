package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcRequests;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SpawnConstructionContextRewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Chemical Plant Zone Boss (Object 0x5D).
 * <p>
 * ROM Reference: s2.asm Obj5D
 * <p>
 * This boss has a complex multi-component architecture:
 * - Main body (Eggpod) - this class
 * - Robotnik face sprite (CPZBossRobotnik)
 * - Jet flame (CPZBossFlame)
 * - Pump mechanism (CPZBossPump)
 * - Swinging container (CPZBossContainer)
 * - Extending pipe (CPZBossPipe)
 * - Gunk hazard (CPZBossGunk)
 * <p>
 * State machine (routine):
 * - DESCEND: Boss descends to target Y
 * - MOVE_TOWARD_TARGET: Move to left/right target position
 * - WAIT: Wait for pipe action to complete
 * - FOLLOW_PLAYER: Track player position
 * - EXPLODE: Defeat explosion sequence
 * - STOP_EXPLODING: Post-explosion bounce
 * - RETREAT: Flee off-screen
 */
public class Sonic2CPZBossInstance extends AbstractBossInstance
        implements SpawnConstructionContextRewindRecreatable {

    // Boss routine states
    private static final int MAIN_DESCEND = 0x00;
    private static final int MAIN_MOVE_TOWARD_TARGET = 0x02;
    private static final int MAIN_WAIT = 0x04;
    private static final int MAIN_FOLLOW_PLAYER = 0x06;
    private static final int MAIN_EXPLODE = 0x08;
    private static final int MAIN_STOP_EXPLODING = 0x0A;
    private static final int MAIN_RETREAT = 0x0C;

    // Position constants
    private static final int MAIN_START_X = 0x2B80;
    private static final int MAIN_START_Y = 0x04B0;
    private static final int MAIN_TARGET_Y = 0x04C0;
    private static final int MAIN_TARGET_RIGHT = 0x2B30;
    private static final int MAIN_TARGET_LEFT = 0x2A50;
    private static final int MAIN_FOLLOW_MIN_X = 0x2A28;
    private static final int MAIN_FOLLOW_MAX_X = 0x2B70;
    private static final int MAIN_RETREAT_CAMERA_MAX_X = 0x2C30;

    // Velocity constants (8.8 fixed-point)
    private static final int MAIN_DESCEND_YVEL = 0x0100;
    private static final int MAIN_MOVE_VEL = 0x0300;
    private static final int MAIN_RETREAT_XVEL = 0x0400;
    private static final int MAIN_RETREAT_YVEL = -0x0040;

    // Status bit flags (ROM: Obj5D_status)
    private static final int STATUS_SIDE = 0x08;      // bit3: which side to target
    private static final int STATUS_HIT = 0x02;       // bit1: ROM reads this, but Obj5D never sets it on hit
    private static final int STATUS_GUNK_READY = 0x04; // bit2: gunk ready to drop

    // Status2 bit flags (ROM: Obj5D_status2)
    private static final int STATUS2_ACTION0 = 0x01;  // Pipe active
    private static final int STATUS2_ACTION1 = 0x02;  // Advance extend animation
    private static final int STATUS2_ACTION2 = 0x04;  // Container moving
    private static final int STATUS2_ACTION3 = 0x08;  // Spawn gunk from extend
    private static final int STATUS2_ACTION4 = 0x10;  // Container returning
    private static final int STATUS2_ACTION5 = 0x20;  // Container dumping
    private static final int STATUS2_RETREAT = 0x40;  // In retreat phase

    // Child references
    private CPZBossRobotnik robotnik;
    private CPZBossFlame flame;
    private CPZBossPump pump;
    private CPZBossContainer container;
    private CPZBossPipe currentPipe;

    // Status flags
    private int status;
    private int status2;
    private boolean bossDefeated;
    /** Publication latch; keeps an equality-timed animal/explosion request retryable. */
    private boolean animalExplosionSubmitted;

    // Timing
    private int defeatTimer;

    /** ROM Obj5D_Init runs once, from the boss's own slot. */
    private boolean childComponentsSpawned;

    // Animation
    private int anim;
    private int mappingFrame;
    private ObjectAnimationState animationState;

    public Sonic2CPZBossInstance(ObjectSpawn spawn) {
        super(spawn, "CPZ Boss");
    }

    @Override
    protected void initializeBossState() {
        state.x = MAIN_START_X;
        state.y = MAIN_START_Y;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.xVel = 0;
        state.yVel = 0;
        state.routine = MAIN_DESCEND;
        state.renderFlags = 0;
        state.sineCounter = 0;

        status = 0;
        status2 = 0;
        bossDefeated = false;
        defeatTimer = 0;
        anim = 0;
        mappingFrame = 0;

        animationState = new ObjectAnimationState(
                CPZBossAnimations.getEggpodAnimations(), anim, mappingFrame);

        // Children are NOT spawned here. ROM Obj5D_Init allocates all five with
        // AllocateObjectAfterCurrent (docs/s2disasm/s2.asm:61628-61710), and it runs
        // as the boss's own routine 0 from the boss's SST slot, so every child lands
        // in a slot ABOVE the boss and the boss executes first each frame. This
        // engine's boss constructor runs before the object manager has given the
        // boss a slot, so spawning from here made the children take the lower free
        // slots and the boss the higher one -- inverting the ROM's execution order,
        // which left the container reading the boss's previous-frame position.
        // See spawnChildComponentsFromOwnSlot, called on the boss's first update.
    }

    /**
     * ROM {@code Obj5D_Init}: the boss's routine 0, executed from the boss's own
     * slot, allocating each child with {@code AllocateObjectAfterCurrent} in this
     * order -- Robotnik, Flame, Pump, Container, Pipe
     * (docs/s2disasm/s2.asm:61628-61710). Running it from the first update rather
     * than the constructor is what gives the boss the lowest slot of its family,
     * so it moves before the container samples its position.
     */
    private void spawnChildComponentsFromOwnSlot() {
        if (childComponentsSpawned) {
            return;
        }
        childComponentsSpawned = true;
        spawnRobotnik();
        spawnFlame();
        spawnPump();
        spawnContainer();
        spawnPipe();
    }

    @Override
    protected int getInitialHitCount() {
        return DEFAULT_HIT_COUNT;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return 0x0F;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        if (robotnik != null) {
            robotnik.setAnim(2); // Hurt face
        }
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false; // CPZ has custom defeat logic
    }

    @Override
    protected boolean defeatDeferralAppliesToThisBoss() {
        return true;
    }

    @Override
    protected void onDefeatStarted() {
        if (!isDefeatEntryPrepared() && !Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE)) return;
        bossDefeated = true;
        state.routine = MAIN_EXPLODE;
        defeatTimer = DEFEAT_TIMER_START;
        if (robotnik != null) {
            robotnik.setAnim(4);
        }
    }

    @Override
    protected boolean prepareDefeatEntry() {
        return Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE);
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        spawnChildComponentsFromOwnSlot();
        lookAtPlayer(player);

        switch (state.routine) {
            case MAIN_DESCEND -> updateMainDescend();
            case MAIN_MOVE_TOWARD_TARGET -> updateMainMoveTowardTarget();
            case MAIN_WAIT -> updateMainWait();
            case MAIN_FOLLOW_PLAYER -> updateMainFollowPlayer(player);
            case MAIN_EXPLODE -> updateMainExplode(vIntRunCount);
            case MAIN_STOP_EXPLODING -> updateMainStopExploding();
            case MAIN_RETREAT -> updateMainRetreat();
        }

        animate();
    }

    private void updateMainDescend() {
        state.yVel = MAIN_DESCEND_YVEL;
        applyMainMove();
        if ((state.yFixed >> 16) == MAIN_TARGET_Y) {
            state.yVel = 0;
            state.routine = MAIN_MOVE_TOWARD_TARGET;
        }
        updateMainPositionAndHover();
    }

    private void updateMainMoveTowardTarget() {
        int target = (status & STATUS_SIDE) != 0 ? MAIN_TARGET_LEFT : MAIN_TARGET_RIGHT;
        int baseX = state.xFixed >> 16;
        int diff = Math.abs(target - baseX);
        if (diff <= 3) {
            if ((state.yFixed >> 16) == MAIN_TARGET_Y) {
                // ROM Obj5D_Main_2_Stop branches straight to Pos_and_Collision after halting.
                state.xVel = 0;
                state.yVel = 0;
                state.routine = MAIN_WAIT;
                status ^= STATUS_SIDE;
                status2 |= STATUS2_ACTION0;   // Activate pipe
            }
            updateMainPositionAndHover();
            return;
        } else {
            state.xVel = target > baseX ? MAIN_MOVE_VEL : -MAIN_MOVE_VEL;
        }
        applyMainMove();
        updateMainPositionAndHover();
    }

    private void updateMainWait() {
        if ((status2 & STATUS2_ACTION0) != 0) {
            updateMainPositionAndHover();
            return;
        }
        state.routine = MAIN_FOLLOW_PLAYER;
        updateMainPositionAndHover();
    }

    private void updateMainFollowPlayer(AbstractPlayableSprite player) {
        int target = state.xFixed >> 16;
        if (player != null) {
            target = player.getCentreX() + 0x4C;
        }
        int baseX = state.xFixed >> 16;
        if (target > baseX) {
            state.xFixed += 0x10000;
        } else if (target < baseX) {
            state.xFixed -= 0x10000;
        }
        if ((state.xFixed >> 16) < MAIN_FOLLOW_MIN_X) {
            state.xFixed = MAIN_FOLLOW_MIN_X << 16;
        } else if ((state.xFixed >> 16) > MAIN_FOLLOW_MAX_X) {
            state.xFixed = MAIN_FOLLOW_MAX_X << 16;
        }
        updateMainPositionAndHover();
    }

    private void updateMainExplode(int vIntRunCount) {
        defeatTimer--;
        if (defeatTimer >= 0) {
            if ((vIntRunCount & EXPLOSION_INTERVAL - 1) == 0) {
                spawnDefeatExplosion();
            }
        } else {
            state.renderFlags |= 1;
            state.xVel = 0;
            state.routine = MAIN_STOP_EXPLODING;
            defeatTimer = -0x26;
        }
        state.x = state.xFixed >> 16;
        state.y = state.yFixed >> 16;
    }

    private void updateMainStopExploding() {
        defeatTimer++;
        if (defeatTimer == 0) {
            state.yVel = 0;
        } else if (defeatTimer < 0) {
            state.yVel += 0x18;
        } else {
            if (defeatTimer < 0x30) {
                state.yVel -= 8;
            } else if (defeatTimer >= 0x30 && !animalExplosionSubmitted) {
                if (!Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_ANIMALS_CPZ,
                        Sonic2Constants.PLC_EXPLOSION)) return;
                animalExplosionSubmitted = true;
                state.yVel = 0;
                services().playMusic(Sonic2Music.CHEMICAL_PLANT.id);
            } else if (defeatTimer >= 0x38) {
                state.routine = MAIN_RETREAT;
            }
        }
        applyMainMove();
        state.x = state.xFixed >> 16;
        state.y = state.yFixed >> 16;
    }

    private void updateMainRetreat() {
        status2 |= STATUS2_RETREAT;
        state.xVel = MAIN_RETREAT_XVEL;
        state.yVel = MAIN_RETREAT_YVEL;
        Camera camera = services().camera();
        if (camera != null) {
            if (camera.getMaxX() < MAIN_RETREAT_CAMERA_MAX_X) {
                camera.setMaxX((short) (camera.getMaxX() + 2));
            } else if (!isOnScreen()) {
                deleteMain();
                return;
            }
        }
        applyMainMove();
        state.x = state.xFixed >> 16;
        state.y = state.yFixed >> 16;
    }

    private void updateMainPositionAndHover() {
        int baseX = state.xFixed >> 16;
        int baseY = state.yFixed >> 16;
        int hover = calculateHoverOffset();
        state.x = baseX;
        state.y = baseY + hover;
    }

    private void lookAtPlayer(AbstractPlayableSprite player) {
        if (state.routine >= MAIN_EXPLODE || player == null) {
            return;
        }
        int dx = player.getCentreX() - state.x;
        if (dx > 0) {
            state.renderFlags |= 1;
        } else {
            state.renderFlags &= ~1;
        }
    }

    private void applyMainMove() {
        state.xFixed += (state.xVel << 8);
        state.yFixed += (state.yVel << 8);
    }

    private void deleteMain() {
        if (robotnik != null) {
            robotnik.setDestroyed(true);
        }
        // ROM: Current_Boss_ID is NEVER cleared in Sonic 2. It is written only by
        // the boss-arena setup routines (`move.b #N,(Current_Boss_ID).w`, ids 1-9)
        // and read by `tst.b`; docs/s2disasm/s2.asm contains no `clr.b` or
        // `move.b #0` for it, so it resets only via the level-load RAM clear and
        // persists to the end of the act. Sonic_Boundary's right-hand test widens
        // the side boundary by $40 only when it is zero (s2.asm:37243-37251), so
        // clearing it here let the character run 64px past the ROM's clamp.
        // Contrast S1, which DOES clear at the Egg Prison
        // (s1disasm/_incObj/3E Prison Capsule.asm:97), and S3K, which clears
        // Boss_flag at 31 sites. S2 is the exception.
        setDestroyed(true);
    }

    private void animate() {
        animationState.setAnimId(anim);
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    // ========================================================================
    // CHILD COMPONENT SPAWNING
    // ========================================================================

    private void spawnRobotnik() {
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn robotnikSpawn = new ObjectSpawn(state.x, state.y, Sonic2ObjectIds.CPZ_BOSS, 0, state.renderFlags, false, 0);
        robotnik = spawnChild(() -> new CPZBossRobotnik(robotnikSpawn, this));
    }

    private void spawnFlame() {
        if ((spawn.subtype() & 0x80) != 0) {
            return;
        }
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn flameSpawn = new ObjectSpawn(state.x, state.y, Sonic2ObjectIds.CPZ_BOSS, 0, state.renderFlags, false, 0);
        flame = spawnChild(() -> new CPZBossFlame(flameSpawn, this));
    }

    private void spawnPump() {
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn pumpSpawn = new ObjectSpawn(state.x, state.y, Sonic2ObjectIds.CPZ_BOSS, 0, state.renderFlags, false, 0);
        pump = spawnChild(() -> new CPZBossPump(pumpSpawn, this));
    }

    private void spawnContainer() {
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn containerSpawn = new ObjectSpawn(state.x, state.y, Sonic2ObjectIds.CPZ_BOSS, 0, state.renderFlags, false, 0);
        container = spawnChild(() -> new CPZBossContainer(containerSpawn, this));
    }

    private void spawnPipe() {
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn pipeSpawn = new ObjectSpawn(state.x, state.y, Sonic2ObjectIds.CPZ_BOSS, 0, state.renderFlags, false, 0);
        currentPipe = spawnChild(() -> new CPZBossPipe(pipeSpawn, this));
    }

    // ========================================================================
    // PUBLIC ACCESSORS - Used by child components
    // ========================================================================

    public int getRenderFlags() {
        return state.renderFlags;
    }

    public boolean isBossDefeated() {
        return bossDefeated;
    }

    public boolean isInRetreatPhase() {
        return (status2 & STATUS2_RETREAT) != 0;
    }

    public boolean isInvulnerable() {
        return state.invulnerable;
    }

    public int getInvulnerabilityTimer() {
        return state.invulnerabilityTimer;
    }

    public int getInvulnerabilityDuration() {
        return DEFAULT_INVULNERABILITY_DURATION;
    }

    // Pipe state
    public boolean isPipeActive() {
        return (status2 & STATUS2_ACTION0) != 0;
    }

    public void onPipeComplete() {
        // Obj5D_Pipe_Retract only deletes the pipe control object. Action 0 is
        // cleared later by Obj5D_Container_Extend when the fill animation ends
        // (s2.asm:62740-62742); the next pipe is spawned by the returning
        // container path (s2.asm:62649-62664).
    }

    // Dripper state
    public void onDripperCycleComplete() {
        status2 |= STATUS2_ACTION1;
    }

    public boolean shouldAdvanceExtend() {
        return (status2 & STATUS2_ACTION1) != 0;
    }

    public void clearAdvanceExtendFlag() {
        status2 &= ~STATUS2_ACTION1;
    }

    // Container state
    public boolean isContainerMoving() {
        return (status2 & STATUS2_ACTION2) != 0;
    }

    public boolean isContainerReturning() {
        return (status2 & STATUS2_ACTION4) != 0;
    }

    public boolean isContainerDumping() {
        return (status2 & STATUS2_ACTION5) != 0;
    }

    public boolean isContainerDropTriggered() {
        return (status2 & STATUS2_ACTION3) != 0;
    }

    public void onContainerDumpStart() {
        status2 |= STATUS2_ACTION5;
        status2 &= ~STATUS2_ACTION2;
    }

    public void onContainerDumpComplete() {
        status2 &= ~STATUS2_ACTION5;
        status2 |= STATUS2_ACTION3;
        status2 |= STATUS2_ACTION4;
    }

    public void onContainerCycleComplete() {
        status2 &= ~STATUS2_ACTION4;
        status2 &= ~STATUS2_ACTION2;
        spawnPipe();
    }

    public void onExtendComplete() {
        status2 &= ~STATUS2_ACTION0;
        status2 |= STATUS2_ACTION2;
    }

    // Gunk state
    public boolean shouldSpawnGunk() {
        return (status2 & STATUS2_ACTION3) != 0;
    }

    public void clearSpawnGunkFlag() {
        status2 &= ~STATUS2_ACTION3;
    }

    public void setGunkReady(boolean ready) {
        if (ready) {
            status |= STATUS_GUNK_READY;
        } else {
            status &= ~STATUS_GUNK_READY;
        }
    }

    public boolean isGunkReady() {
        return (status & STATUS_GUNK_READY) != 0;
    }

    public void onGunkLanded() {
        status2 |= STATUS2_ACTION2;
        status2 |= STATUS2_ACTION4;
        state.routine = MAIN_MOVE_TOWARD_TARGET;
    }

    // Hit state
    public boolean wasJustHit() {
        return (status & STATUS_HIT) != 0;
    }

    public void clearHitFlag() {
        status &= ~STATUS_HIT;
    }

    // ========================================================================
    // COLLISION
    // ========================================================================

    @Override
    public int getCollisionFlags() {
        if (state.routine >= MAIN_EXPLODE || bossDefeated || state.invulnerable) {
            return 0;
        }
        return 0xC0 | (getCollisionSizeIndex() & 0x3F);
    }

    // ========================================================================
    // RENDERING
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectServices svc = tryServices();
        ObjectRenderManager renderManager = svc != null ? svc.renderManager() : null;
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_BOSS_EGGPOD);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (mappingFrame < 0) {
            return;
        }

        boolean flipped = (state.renderFlags & 1) != 0;
        renderer.drawFrameIndex(mappingFrame, state.x, state.y, flipped, false);
    }

    @Override
    public int getPriorityBucket() {
        return 3;
    }

    @Override
    public boolean isPersistent() {
        // Obj5D_Main_Retreat owns its delete gate: it keeps widening
        // Camera_Max_X_pos to $2C30 before testing the on-screen bit.
        return true;
    }

    @Override
    protected boolean isOnScreen() {
        Camera camera = services().camera();
        int screenX = state.x - camera.getX();
        int screenY = state.y - camera.getY();
        return screenX >= -64 && screenX <= camera.getWidth() + 64
                && screenY >= -64 && screenY <= camera.getHeight() + 64;
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic2Sfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic2Sfx.BOSS_EXPLOSION.id;
    }

    @Override
    protected int getBossExplosionObjectId() {
        return com.openggf.game.sonic2.constants.Sonic2ObjectIds.BOSS_EXPLOSION;
    }
}
