package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AnimalObjectInstance;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.game.sonic2.objects.PointsObjectInstance;

import java.util.List;

/**
 * Rexon Head (0x97) - Individual head segment of the Rexon lava snake.
 * Based on disassembly Obj97.
 *
 * States:
 * - INIT: Set position, wait timer
 * - INITIAL_WAIT: Wait staggered frames per head index
 * - RAISE_HEAD: Launch upward/outward, decelerate
 * - NORMAL: Oscillate, fire projectiles (last head only)
 * - DEATH_DROP: Fall off screen if parent body destroyed
 */
public class RexonHeadObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, RewindRecreatable {

    // Collision size from disassembly (first 4 heads: 0x0B, last head: 0x8B with HURT flag)
    private static final int COLLISION_SIZE_NORMAL = 0x0B;

    // Initial wait timers per head index (from disassembly s2.asm:73803-73808)
    // byte_3744E indexed by (headIndex / 2)
    private static final int[] INITIAL_WAIT_TIMES = {
            30,  // head 0 (index 0): byte_3744E[0] = $1E
            24,  // head 1 (index 2): byte_3744E[1] = $18
            18,  // head 2 (index 4): byte_3744E[2] = $12
            12,  // head 3 (index 6): byte_3744E[3] = $C
            6    // head 4 (index 8): byte_3744E[4] = 6
    };

    // Raise phase timers per head index (from disassembly s2.asm:73833-73837)
    // byte_3744E indexed by (8 - headIndex) / 2 - reverse order creates snake unfurl effect
    private static final int[] RAISE_TIMERS = {
            6,   // head 0 (index 0): byte_3744E[(8-0)/2=4] = 6
            12,  // head 1 (index 2): byte_3744E[(8-2)/2=3] = $C
            18,  // head 2 (index 4): byte_3744E[(8-4)/2=2] = $12
            24,  // head 3 (index 6): byte_3744E[(8-6)/2=1] = $18
            30   // head 4 (index 8): byte_3744E[(8-8)/2=0] = $1E
    };

    // Death drop X velocities per head (from disassembly)
    private static final int[] DEATH_X_VELOCITIES = {
            0x80,   // head 0
            -0x100, // head 1
            0x100,  // head 2
            -0x80,  // head 3
            0x80    // head 4
    };

    // Oscillation lookup table (X, Y pairs, signed bytes) - from byte_376A8 (s2.asm:74068-74100)
    // Each head uses this to calculate offset to apply to the PREVIOUS head's position
    private static final int[] OSCILLATION_TABLE = {
            0x0F, 0x00,   // 0
            0x0F, 0xFF,   // 1
            0x0F, 0xFF,   // 2
            0x0F, 0xFE,   // 3
            0x0F, 0xFD,   // 4
            0x0F, 0xFC,   // 5
            0x0E, 0xFC,   // 6
            0x0E, 0xFB,   // 7
            0x0E, 0xFA,   // 8
            0x0E, 0xFA,   // 9
            0x0D, 0xF9,   // 10
            0x0D, 0xF8,   // 11
            0x0C, 0xF8,   // 12
            0x0C, 0xF7,   // 13
            0x0C, 0xF6,   // 14
            0x0B, 0xF6,   // 15
            0x0B, 0xF5,   // 16
            0x0A, 0xF5,   // 17
            0x0A, 0xF4,   // 18
            0x09, 0xF4,   // 19
            0x08, 0xF4,   // 20
            0x08, 0xF3,   // 21
            0x07, 0xF3,   // 22
            0x06, 0xF2,   // 23
            0x06, 0xF2,   // 24
            0x05, 0xF2,   // 25
            0x04, 0xF2,   // 26
            0x04, 0xF1,   // 27
            0x03, 0xF1,   // 28
            0x02, 0xF1,   // 29
            0x01, 0xF1,   // 30
            0x01, 0xF1    // 31
    };

    // Initial phase values per head (from byte_374BE, s2.asm:73871-73876)
    // Head 4 reads past the array in original (undefined), we use 0x18 as reasonable value
    private static final int[] INITIAL_PHASES = { 0x24, 0x20, 0x1C, 0x1A, 0x18 };

    // Projectile constants
    private static final int PROJECTILE_INITIAL_DELAY = 32;  // Initial delay before first fire
    private static final int PROJECTILE_FIRE_INTERVAL = 127; // Frames between fires
    private static final int PROJECTILE_X_VELOCITY = 0x100;  // 1 pixel/frame (8.8 fixed)
    private static final int PROJECTILE_Y_VELOCITY = 0x80;   // Initial Y velocity

    private enum State {
        INIT,
        INITIAL_WAIT,
        RAISE_HEAD,
        NORMAL,
        DEATH_DROP
    }
    private final RexonBadnikInstance parent;
    // headIndex/headNumber/xFlip are non-final so GenericFieldCapturer captures them and
    // restoreObjectRewindState reapplies them after the rewind recreate hook rebuilds this head
    // (the hook passes placeholders; these values are not derivable from the ObjectSpawn).
    private int headIndex;  // 0, 2, 4, 6, or 8
    private int headNumber; // 0-4 for array indexing
    private boolean xFlip;

    private State state;
    private int currentX;
    private int currentY;
    private int baseX;  // Anchor position for oscillation
    private int baseY;
    private int xVelocity;  // 8.8 fixed point
    private int yVelocity;
    private int xSubpixel;
    private int ySubpixel;
    private int waitTimer;
    private int raiseTimer;  // Timer for raise phase duration (s2.asm:73837)
    private int oscillationPhase;     // Combined phase value (objoff_2B), 0x00-0x7F
    private int phaseDirection;       // +1 or -1 for bouncing (objoff_38)
    private int oscillationFrameCounter;  // (objoff_39) counts frames, only update every 4
    private int projectileTimer;
    private boolean destroyed;

    // Reference to the NEXT head toward the tip (null for head 4/tip)
    // In original: objoff_30 stores address of the next head
    // Each head controls the NEXT head's position during oscillation
    // Head 0 (anchor) → Head 1 → Head 2 → Head 3 → Head 4 (tip)
    // Head 0 stays at base position; oscillation ripples toward tip
    private RexonHeadObjectInstance linkedHead;

    public RexonHeadObjectInstance(ObjectSpawn spawn,
                                   RexonBadnikInstance parent, int x, int y,
                                   int headIndex, boolean xFlip) {
        super(spawn, "RexonHead");
        this.parent = parent;
        this.headIndex = headIndex;
        this.headNumber = headIndex / 2;  // Convert 0,2,4,6,8 to 0,1,2,3,4
        this.xFlip = xFlip;

        // Apply position offsets from Obj97_Init (s2.asm:73775-73784)
        // X offset: x_flip SET: +0x28 (40 pixels right), NOT set: -0x18 (-24 pixels left)
        int xOffset = xFlip ? 0x28 : -0x18;
        // Y offset: +0x10 (16 pixels down)
        int yOffset = 0x10;

        this.currentX = x + xOffset;
        this.currentY = y + yOffset;
        this.baseX = x + xOffset;
        this.baseY = y + yOffset;
        this.xVelocity = 0;
        this.yVelocity = 0;
        this.xSubpixel = 0;
        this.ySubpixel = 0;
        this.state = State.INIT;
        this.raiseTimer = 0;
        // Initialize phase from byte_374BE based on head number (s2.asm:73866-73868)
        this.oscillationPhase = INITIAL_PHASES[headNumber];
        this.phaseDirection = 1;  // Start incrementing (objoff_38 = 1)
        // Obj97_Init seeds objoff_39 with the head number, not 0
        // (s2.asm:74316-74318: d0 = objoff_2E >> 1 = headNumber, then
        // move.b d0,objoff_39). The phase/oscillation update only runs on the
        // frame where (objoff_39 + 1) & 3 == 0 (Obj97_Normal, s2.asm:74407-74414),
        // so each head advances its part of the wave on a different frame of the
        // 4-frame cycle. Seeding every head at 0 collapsed that stagger and left
        // the wave a couple of pixels off; in HTZ2 that nudged the attackable tip
        // head just outside Sonic's 16x16 touch band, so the rolling kill bounce
        // the ROM lands (Touch_KillEnemy "big bounce" neg.w y_vel, s2.asm:85385)
        // was missed (HTZ2 trace f1078: y_speed should flip +0568 -> -0568).
        this.oscillationFrameCounter = headNumber;
        this.projectileTimer = PROJECTILE_INITIAL_DELAY;
        this.destroyed = false;
        this.linkedHead = null;
    }

    private RexonHeadObjectInstance() {
        this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), null, 0, 0, 0, false);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        RexonBadnikInstance parent = Sonic2BadnikChildRewindLinks.nearestRexon(ctx);
        ObjectSpawn spawn = ctx.spawn();
        if (parent == null) {
            return null;
        }
        RexonHeadObjectInstance head =
                new RexonHeadObjectInstance(spawn, parent, spawn.x(), spawn.y(), 0, false);
        parent.attachHeadForRewind(head);
        return head;
    }

    /**
     * Set the linked head (the next head toward the tip that this head controls).
     * In the original game, objoff_30 stores the address of the next head toward the tip.
     * Each head calculates an oscillation offset and applies it to the linked head.
     *
     * Chain direction: Head 0 → Head 1 → Head 2 → Head 3 → Head 4 (tip)
     * Tip (Head 4) has no linked head - it stays at its base position as the anchor.
     */
    public void setLinkedHead(RexonHeadObjectInstance head) {
        this.linkedHead = head;
    }

    int rewindHeadIndex() {
        return headIndex;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed) {
            return;
        }

        switch (state) {
            case INIT -> initHead();
            case INITIAL_WAIT -> updateInitialWait();
            case RAISE_HEAD -> updateRaiseHead();
            case NORMAL -> updateNormal(vIntRunCount);
            case DEATH_DROP -> updateDeathDrop();
        }
    }

    private void initHead() {
        waitTimer = INITIAL_WAIT_TIMES[headNumber];
        state = State.INITIAL_WAIT;
    }

    private void updateInitialWait() {
        waitTimer--;
        if (waitTimer <= 0) {
            // Start rising - Obj97_StartRaise always writes -$120, regardless of x_flip
            // (docs/s2disasm/s2.asm:73831). x_flip only changes the initial X offset.
            xVelocity = -0x120;
            yVelocity = -0x200;        // -$200 upward (s2.asm:73832)

            // Set raise timer (s2.asm:73833-73837)
            raiseTimer = RAISE_TIMERS[headNumber];

            state = State.RAISE_HEAD;
        }
    }

    private void updateRaiseHead() {
        // Obj97_RaiseHead always adds +$10 to x_vel (docs/s2disasm/s2.asm:73848).
        xVelocity += 0x10;

        // Decrement raise timer (s2.asm:73847)
        raiseTimer--;

        // Apply velocity via ObjectMove (s2.asm:73857)
        int xPos32 = (currentX << 8) | (xSubpixel & 0xFF);
        int yPos32 = (currentY << 8) | (ySubpixel & 0xFF);
        xPos32 += xVelocity;
        yPos32 += yVelocity;
        currentX = xPos32 >> 8;
        currentY = yPos32 >> 8;
        xSubpixel = xPos32 & 0xFF;
        ySubpixel = yPos32 & 0xFF;

        // Transition to NORMAL when timer expires (s2.asm:73848 bmi.s Obj97_StartNormalState)
        if (raiseTimer < 0) {
            // Stop movement via Obj_MoveStop (s2.asm:73864)
            xVelocity = 0;
            yVelocity = 0;
            // Store current position as base for oscillation
            baseX = currentX;
            baseY = currentY;
            state = State.NORMAL;
        }
    }

    private void updateNormal(int vIntRunCount) {
        // Fire projectile timer check (tip head only, headIndex == 8)
        // From s2.asm:73882-73886
        if (headIndex == 8) {
            projectileTimer--;
            if (projectileTimer < 0) {
                fireProjectile();
                projectileTimer = PROJECTILE_FIRE_INTERVAL;
            }
        }

        // Update oscillation every 4 frames (s2.asm:73889-73895)
        // objoff_39 counts up, only process when (count & 3) == 0
        oscillationFrameCounter++;
        if ((oscillationFrameCounter & 3) == 0) {
            updatePhase();
            applyOscillationToLinkedHead();
        }
    }

    /**
     * Update the oscillation phase with bouncing logic.
     * From loc_3758A (s2.asm:73956-73969):
     *
     * The phase bounces between bounds. When (phase - 0x18) == 0, < 0, or >= 0x10,
     * the direction reverses.
     *
     * This creates a bounded oscillation between roughly 0x18 and 0x28.
     */
    private void updatePhase() {
        // Add direction to phase (objoff_38 is +1 or -1)
        oscillationPhase = (oscillationPhase + phaseDirection) & 0x7F;

        // Check bounds (s2.asm:73961-73967)
        int adjusted = (oscillationPhase & 0xFF) - 0x18;
        if (adjusted == 0 || adjusted < 0 || adjusted >= 0x10) {
            // Reverse direction
            phaseDirection = -phaseDirection;
        }
    }

    /**
     * Apply oscillation offset to the linked (next) head toward the tip.
     * From Obj97_Oscillate (s2.asm:74003-74029):
     *
     * Each head calculates an offset from the oscillation table and applies it
     * to the NEXT head's position (toward the tip). The tip head (head 4) has no
     * linked head but IS controlled by head 3.
     *
     * Chain: Head 0 → Head 1 → Head 2 → Head 3 → Head 4 (tip)
     *
     * Head 0 is the ANCHOR - it's not controlled by anyone and stays at base position.
     * Each subsequent head is positioned relative to the previous one, creating
     * a cascading oscillation that ripples from body toward tip.
     */
    private void applyOscillationToLinkedHead() {
        // No linked head means nothing to move (tip has no link)
        if (linkedHead == null) {
            return;
        }

        // Get table index from phase (s2.asm:74008-74013)
        // phase & 0x1F gives table index 0-31, doubled for byte pairs
        int phase = oscillationPhase & 0x7F;
        int tableIndex = (phase & 0x1F) * 2;

        // Get raw X,Y offsets from table (s2.asm:74014-74017)
        int d2 = OSCILLATION_TABLE[tableIndex];      // X offset
        int d3 = OSCILLATION_TABLE[tableIndex + 1];  // Y offset

        // Sign extend (ext.w in assembly)
        if (d2 > 127) d2 -= 256;
        if (d3 > 127) d3 -= 256;

        // Get rotation quadrant from phase bits 5-6 (s2.asm:74018-74019)
        // (phase >> 4) & 6 gives 0, 2, 4, or 6
        int rotation = (phase >> 4) & 6;

        // Apply rotation transform (s2.asm:74020-74021, off_37652)
        switch (rotation) {
            case 0 -> {
                // return_3765A: no change (X, Y)
            }
            case 2 -> {
                // loc_3765C: exg d2,d3; neg.w d3 → (Y, -X)
                int temp = d2;
                d2 = d3;
                d3 = -temp;
            }
            case 4 -> {
                // loc_37662: neg.w d2; neg.w d3 → (-X, -Y)
                d2 = -d2;
                d3 = -d3;
            }
            case 6 -> {
                // loc_37668: exg d2,d3; neg.w d2 → (-Y, X)
                int temp = d2;
                d2 = -d3;
                d3 = temp;
            }
        }

        // Calculate linked head's new position (s2.asm:74022-74027)
        // X: add offset to this head's X (full word)
        // Y: add offset to this head's Y (low byte only in original, we use full)
        int newX = currentX + d2;
        int newY = currentY + d3;

        // Set linked head's position
        linkedHead.currentX = newX;
        linkedHead.currentY = newY;
    }

    private void fireProjectile() {
        // Projectile spawns ahead of head
        int xDir = xFlip ? 1 : -1;
        int projX = currentX + (xDir * 16);
        int projY = currentY + 4;

        // Very slow horizontal, with gravity
        int projXVel = xDir * PROJECTILE_X_VELOCITY;
        int projYVel = PROJECTILE_Y_VELOCITY;

        spawnFreeChild(() -> new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.REXON_FIREBALL,
                projX,
                projY,
                projXVel,
                projYVel,
                false,  // No gravity - original uses ObjectMove, not ObjectMoveAndFall
                xFlip
        ));
    }

    private void updateDeathDrop() {
        // Apply gravity
        yVelocity += 0x38;

        // Apply velocity
        int xPos32 = (currentX << 8) | (xSubpixel & 0xFF);
        int yPos32 = (currentY << 8) | (ySubpixel & 0xFF);
        xPos32 += xVelocity;
        yPos32 += yVelocity;
        currentX = xPos32 >> 8;
        currentY = yPos32 >> 8;
        xSubpixel = xPos32 & 0xFF;
        ySubpixel = yPos32 & 0xFF;

        // Check if off screen
        if (!isOnScreen(64)) {
            destroyed = true;
            setDestroyed(true);
        }
    }

    /**
     * Trigger death drop state when parent body is destroyed.
     */
    public void triggerDeathDrop() {
        if (state == State.DEATH_DROP || destroyed) {
            return;
        }

        state = State.DEATH_DROP;
        xVelocity = DEATH_X_VELOCITIES[headNumber];
        // Obj97_CheckHeadIsAlive only switches routine and writes x_vel
        // (docs/s2disasm/s2.asm:73935-73942); y_vel remains stopped.
        yVelocity = 0;
    }

    @Override
    public int getCollisionFlags() {
        // From s2.asm:73788-73794 (Obj97_Init):
        // Tip head (index 8 / headNumber 4): 0x0B = ENEMY category - can be attacked
        // Body heads (index 0-6 / headNumbers 0-3): 0x8B = HURT category - damages player
        if (headNumber == 4) {
            return 0x00 | (COLLISION_SIZE_NORMAL & 0x3F);  // ENEMY - attackable tip
        }
        return 0x80 | (COLLISION_SIZE_NORMAL & 0x3F);  // HURT - body segments damage player
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed) {
            return;
        }

        destroyHead(player);
    }

    private void destroyHead(AbstractPlayableSprite player) {
        destroyed = true;
        setDestroyed(true);

        // Spawn explosion
        spawnFreeChild(() -> new ExplosionObjectInstance(0x27, currentX, currentY,
                services().renderManager()));

        // Spawn animal
        spawnFreeChild(() -> AnimalObjectInstance.deferredArtVariant(
                new ObjectSpawn(currentX, currentY, 0x28, 0, 0, false, 0), services(), null));

        // Calculate and award points
        int pointsValue = 100;
        if (player != null) {
            pointsValue = player.incrementBadnikChain();
            services().gameState().addScore(pointsValue);
        }

        // Spawn points display
        int finalPointsValue = pointsValue;
        spawnFreeChild(() -> new PointsObjectInstance(
                new ObjectSpawn(currentX, currentY, 0x29, 0, 0, false, 0), services(), finalPointsValue));

        // Play explosion SFX
        services().playSfx(Sonic2Sfx.EXPLOSION.id);

        // Notify parent to trigger death drop for other heads
        if (parent != null) {
            parent.onHeadDestroyed(this);
        }
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(currentX, currentY);
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
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        // Don't render during initial wait
        if (state == State.INIT || state == State.INITIAL_WAIT) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.REXON);
        if (renderer == null) return;

        // From s2.asm:73791-73793:
        // - Tip head (headIndex == 8 / headNumber == 4): uses frame 0 (head with eyes)
        // - Neck segments (headIndex 0-6 / headNumber 0-3): use frame 1 (circular segment)
        int frame = (headNumber == 4) ? 0 : 1;
        renderer.drawFrameIndex(frame, currentX, currentY, xFlip, false);
    }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%s head=%d idx=%d xflip=%d vel=%04X,%04X phase=%02X dir=%d wait=%02X raise=%02X link=%d",
                state,
                headNumber,
                headIndex,
                xFlip ? 1 : 0,
                xVelocity & 0xFFFF,
                yVelocity & 0xFFFF,
                oscillationPhase & 0xFF,
                phaseDirection,
                waitTimer & 0xFF,
                raiseTimer & 0xFF,
                linkedHead != null ? 1 : 0);
    }
}
