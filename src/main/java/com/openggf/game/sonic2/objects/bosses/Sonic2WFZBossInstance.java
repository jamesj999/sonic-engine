package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnConstructionContextRewindRecreatable;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * WFZ Laser Platform Boss (Object 0xC5).
 * ROM Reference: s2.asm ObjC5
 *
 * Wing Fortress Zone boss. A hovering laser case that bounces horizontally,
 * opens to lower a laser shooter, fires a downward laser beam, and spawns
 * floating platforms. The boss arena also has Robotnik watching from a fixed
 * position with his own platform.
 *
 * State Machine (16 sub-routines):
 * - $00: Init - camera max Y=$442, store x_pos, bounds x +/- $60, HP=8
 * - $02: Wait for player within $40px horizontally
 * - $04: Spawn walls, laser shooter, platform releaser, Robotnik. Descend.
 * - $06: Descend. Timer expires -> timer=$60, play boss music.
 * - $08: Set direction toward player, signal platform releaser. Timer=$70.
 * - $0A: Bounce between left/right bounds. Timer expires -> open animation.
 * - $0C: Animate opening. On complete -> advance.
 * - $0E: Signal laser shooter to descend. Timer=$0E.
 * - $10: Move laser shooter down 1px/frame for 14 frames.
 * - $12: Enable collision ($06), timer=$40.
 * - $14: Wait for timer -> spawn laser.
 * - $16: Wait for laser signal. Bounce with laser. Timer=$80.
 * - $18: Stop laser. Retract shooter. Delete laser. Clear collision.
 * - $1A: Close animation.
 * - $1C: Loop back to $08.
 * - $1E: Defeat - explosions, $EF frames, then WFZ music, camera Y=$720, delete.
 *
 * 8 child object types (inner classes):
 * 1. LaserWall - solid wall at x +/- $88, y+$60
 * 2. PlatformReleaser - spawns platforms cyclically
 * 3. FloatingPlatform - player can stand on, oscillates
 * 4. PlatformHurt - invisible damage collider below platform
 * 5. LaserShooter - lowered/raised during laser phases
 * 6. Laser - extends downward in 5 stages
 * 7. WFZRobotnik - fixed position, watches fight
 * 8. RobotnikPlatform - follows Robotnik Y+$26
 */
public class Sonic2WFZBossInstance extends AbstractBossInstance
        implements SpawnConstructionContextRewindRecreatable {

    // State machine routine constants (16 sub-routines, $00-$1E)
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_WAIT_PLAYER = 0x02;
    private static final int ROUTINE_SPAWN_CHILDREN = 0x04;
    private static final int ROUTINE_DESCEND = 0x06;
    private static final int ROUTINE_SET_DIRECTION = 0x08;
    private static final int ROUTINE_BOUNCE = 0x0A;
    private static final int ROUTINE_OPEN_ANIM = 0x0C;
    private static final int ROUTINE_SIGNAL_SHOOTER = 0x0E;
    private static final int ROUTINE_LOWER_SHOOTER = 0x10;
    private static final int ROUTINE_ENABLE_COLLISION = 0x12;
    private static final int ROUTINE_SPAWN_LASER = 0x14;
    private static final int ROUTINE_BOUNCE_WITH_LASER = 0x16;
    private static final int ROUTINE_RETRACT_SHOOTER = 0x18;
    private static final int ROUTINE_CLOSE_ANIM = 0x1A;
    private static final int ROUTINE_LOOP = 0x1C;
    private static final int ROUTINE_DEFEAT = 0x1E;

    // Position and boundary constants
    /** Camera max Y during boss fight (ROM: move.w #$442,(Camera_Max_Y_pos).w) */
    private static final int CAMERA_MAX_Y_BOSS = 0x442;
    /** Camera max Y after defeat (ROM: move.w #$720,(Camera_Max_Y_pos).w) */
    private static final int CAMERA_MAX_Y_DEFEAT = 0x720;
    /** Left/right bound offset from spawn X (ROM: x +/- $60) */
    private static final int BOUND_OFFSET_X = 0x60;
    /** Wall offset from spawn X (ROM: x +/- $88) */
    private static final int WALL_OFFSET_X = 0x88;
    /** Wall Y offset from spawn Y (ROM: y + $60) */
    private static final int WALL_OFFSET_Y = 0x60;
    /** Player proximity threshold (ROM: $40 pixels) */
    private static final int PLAYER_PROXIMITY = 0x40;

    // Velocity constants (8.8 fixed-point)
    /** Descent speed (ROM: move.w #$40,y_vel(a0)) */
    private static final int DESCENT_SPEED = 0x40;
    /** Bounce speed (ROM: move.w #$100,x_vel(a0)) or -$100 */
    private static final int BOUNCE_SPEED = 0x100;
    /** Laser bounce speed (ROM: move.w #$80,x_vel(a0)) or -$80 */
    private static final int LASER_BOUNCE_SPEED = 0x80;

    // Timing constants
    /** Pre-boss-music timer (ROM: move.b #$5A,objoff_2A) */
    private static final int DESCEND_TIMER = 0x5A;
    /** Boss music delay (ROM: move.b #$60,objoff_2A) */
    private static final int MUSIC_DELAY_TIMER = 0x60;
    /** Bounce timer (ROM: move.b #$70,objoff_2A) */
    private static final int BOUNCE_TIMER = 0x70;
    /** Shooter lower timer (ROM: move.b #$0E,objoff_2A) */
    private static final int SHOOTER_LOWER_TIMER = 0x0E;
    /** Collision enable wait timer (ROM: move.b #$40,objoff_2A) */
    private static final int COLLISION_WAIT_TIMER = 0x40;
    /** Laser bounce timer (ROM: move.b #$80,objoff_2A) */
    private static final int LASER_BOUNCE_TIMER = 0x80;
    /** Defeat explosion timer (ROM: move.w #$EF,objoff_32) */
    private static final int DEFEAT_EXPLOSION_TIMER = 0xEF;
    /** Invulnerability duration (ROM: move.b #$20,objoff_30) */
    private static final int INVULN_DURATION = 0x20;

    // Collision constants
    /** Boss collision flags when hittable (ROM: collision_flags=$06) */
    private static final int COLLISION_HITTABLE = 0x06;

    // Rendering frame indices (from ROM mappings ObjC5_MapUnc_3CCD8)
    private static final int FRAME_CASE_CLOSED = 0;
    private static final int FRAME_CASE_OPEN_1 = 1;
    private static final int FRAME_CASE_OPEN_2 = 2;
    private static final int FRAME_CASE_OPEN_3 = 3;
    private static final int FRAME_LASER_SHOOTER = 4;
    private static final int FRAME_PLATFORM_RELEASER = 5;
    // Frame 6 is unused
    private static final int FRAME_PLATFORM = 7;
    // Frames 8-11 are platform anim frames
    private static final int FRAME_WALL = 0x0C;
    private static final int FRAME_LASER_BASE = 0x0D;
    // Laser extension stages: frames $0E-$12

    // Animation speed constants
    // ROM Ani_objC5 anim 0 (opening): speed=5 (6 game frames/anim frame), 8 entries
    private static final int OPEN_ANIM_SPEED = 5;
    // ROM Ani_objC5 anim 1 (closing): speed=3 (4 game frames/anim frame), 6 entries
    private static final int CLOSE_ANIM_SPEED = 3;
    // ROM opening sequence: frames 0,1,2,3,3,3,3 then $FA loop
    private static final int[] OPEN_ANIM_FRAMES = {0, 1, 2, 3, 3, 3, 3};
    // ROM closing sequence: frames 3,2,1,0,0 then $FA loop
    private static final int[] CLOSE_ANIM_FRAMES = {3, 2, 1, 0, 0};

    // Internal state
    private int actionTimer;
    private int defeatTimer;
    /** ROM: the defeat install is latched to the tail of the dispatch that detected it. */
    private boolean pendingDefeatInstall;
    private int currentFrame;
    private boolean facingLeft;
    private int spawnX; // Original spawn X position (for bounds calculation)
    private int leftBound;
    private int rightBound;
    private boolean collisionActive;
    private boolean collisionSuppressedAfterHit;
    private int openAnimFrame;
    private int animSpeedCounter; // Counts down game frames per animation frame
    private boolean laserSignaled; // Set by laser child when fully extended (issue 6)
    private boolean childrenSpawned;

    // Child component references
    private WFZLaserWall leftWall;
    private WFZLaserWall rightWall;
    private WFZPlatformReleaser platformReleaser;
    private WFZLaserShooter laserShooter;
    private WFZLaser laser;
    private WFZRobotnik robotnik;

    public Sonic2WFZBossInstance(ObjectSpawn spawn) {
        super(spawn, "WFZ Boss");
    }

    @Override
    protected void initializeBossState() {
        // ROM: ObjC5_Init
        spawnX = state.x;
        leftBound = spawnX - BOUND_OFFSET_X;
        rightBound = spawnX + BOUND_OFFSET_X;
        currentFrame = FRAME_CASE_CLOSED;
        facingLeft = false;
        collisionActive = false;
        collisionSuppressedAfterHit = false;
        actionTimer = 0;
        defeatTimer = 0;
        openAnimFrame = 0;
        animSpeedCounter = 0;
        laserSignaled = false;
        childrenSpawned = false;

        // ROM: Camera max Y = $442
        var camera = services().camera();
        if (camera != null) {
            camera.setMaxY((short) CAMERA_MAX_Y_BOSS);
        }

        // Advance to wait-for-player
        state.routine = ROUTINE_WAIT_PLAYER;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        boolean applyVelocity = false;
        switch (state.routine) {
            case ROUTINE_WAIT_PLAYER -> updateWaitPlayer(player);
            case ROUTINE_SPAWN_CHILDREN -> updateSpawnChildren();
            case ROUTINE_DESCEND -> applyVelocity = updateDescend();
            case ROUTINE_SET_DIRECTION -> updateSetDirection(player);
            case ROUTINE_BOUNCE -> applyVelocity = updateBounce();
            case ROUTINE_OPEN_ANIM -> updateOpenAnim();
            case ROUTINE_SIGNAL_SHOOTER -> updateSignalShooter();
            case ROUTINE_LOWER_SHOOTER -> updateLowerShooter();
            case ROUTINE_ENABLE_COLLISION -> updateEnableCollision();
            case ROUTINE_SPAWN_LASER -> updateSpawnLaser(player);
            case ROUTINE_BOUNCE_WITH_LASER -> applyVelocity = updateBounceWithLaser();
            case ROUTINE_RETRACT_SHOOTER -> updateRetractShooter();
            case ROUTINE_CLOSE_ANIM -> updateCloseAnim();
            case ROUTINE_LOOP -> updateLoop();
            case ROUTINE_DEFEAT -> updateDefeat(vIntRunCount);
        }

        // Apply velocity only for phases that call ObjectMove in the ROM.
        // ROM phases with ObjectMove: $06 (CaseDown), $0A (CaseBoundaryChk),
        // $16 (CaseBoundaryLaserChk). NOT $04 (CaseWaitDown - just waits).
        if (pendingDefeatInstall) {
            // ROM: bra.w ObjC5_HandleHits, after the case jsr returned.
            installDefeat();
        }

        if (applyVelocity) {
            state.applyVelocity();
        }
    }

    // ========================================================================
    // Routine $02: Wait for player within $40px horizontally
    // ========================================================================

    private void updateWaitPlayer(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        int dx = Math.abs(player.getCentreX() - state.x);
        if (dx <= PLAYER_PROXIMITY) {
            state.routine = ROUTINE_SPAWN_CHILDREN;
        }
    }

    // ========================================================================
    // Routine $04: Spawn walls, laser shooter, platform releaser, Robotnik
    // ========================================================================

    private void updateSpawnChildren() {
        // ROM: Two-phase descent sequence.
        // Phase 1 (CaseStart): Spawn children, set y_vel, timer=$5A, fade music.
        // Phase 2 (CaseWaitDown): Wait $5A frames with NO movement. Then advance to DESCEND.
        if (!childrenSpawned) {
            // First call: spawn children and set up wait
            if (services().objectManager() != null) {
                spawnChildObjects();
            }
            state.yVel = DESCENT_SPEED;
            state.xVel = 0;
            actionTimer = DESCEND_TIMER; // $5A
            services().fadeOutMusic();
            childrenSpawned = true;
            return; // Stay in SPAWN_CHILDREN, wait phase starts next frame
        }

        // ROM: ObjC5_CaseWaitDown - subq.w #1,objoff_2A(a0) / bmi.s
        // No movement during this phase (DisplaySprite only in ROM)
        actionTimer--;
        if (actionTimer < 0) {
            // ROM: ObjC5_CaseSpeedDown - advance to descent with movement
            state.routine = ROUTINE_DESCEND;
            actionTimer = MUSIC_DELAY_TIMER; // $60
            services().playMusic(Sonic2Music.BOSS.id);
        }
    }

    private void spawnChildObjects() {
        // 1. Left wall at x - $88, y + $60
        leftWall = spawnChild(() -> new WFZLaserWall(this, spawnX - WALL_OFFSET_X, state.y + WALL_OFFSET_Y));
        childComponents.add(leftWall);

        // 2. Right wall at x + $88, y + $60
        rightWall = spawnChild(() -> new WFZLaserWall(this, spawnX + WALL_OFFSET_X, state.y + WALL_OFFSET_Y));
        childComponents.add(rightWall);

        // 3. Laser shooter (follows parent)
        laserShooter = spawnChild(() -> new WFZLaserShooter(this));
        childComponents.add(laserShooter);

        // 4. Platform releaser (follows parent X)
        platformReleaser = spawnChild(() -> new WFZPlatformReleaser(this));
        childComponents.add(platformReleaser);

        // 5. Robotnik at fixed position ($2C60, $4E6)
        robotnik = spawnChild(() -> new WFZRobotnik(this));
        childComponents.add(robotnik);
        robotnik.spawnPlatformChild(this);
    }

    // ========================================================================
    // Routine $06: Descend
    // ========================================================================

    private boolean updateDescend() {
        // ROM: ObjC5_CaseDown - subq.w #1,objoff_2A(a0) / beq.s ObjC5_CaseStopDown
        // ObjectMove is called (velocity applied by outer loop for this routine).
        // Timer counts $60 frames of movement.
        actionTimer--;
        if (actionTimer == 0) {
            // ROM: ObjC5_CaseStopDown - clr.w y_vel(a0)
            state.yVel = 0;
            state.routine = ROUTINE_SET_DIRECTION;
            return false;
        }
        return true;
    }

    // ========================================================================
    // Routine $08: Set horizontal direction toward player
    // ========================================================================

    private void updateSetDirection(AbstractPlayableSprite player) {
        if (player != null) {
            if (player.getCentreX() < state.x) {
                state.xVel = -BOUNCE_SPEED;
                facingLeft = true;
            } else {
                state.xVel = BOUNCE_SPEED;
                facingLeft = false;
            }
        }

        // Signal platform releaser to start spawning
        if (platformReleaser != null) {
            platformReleaser.signalStart();
        }

        actionTimer = BOUNCE_TIMER;
        state.routine = ROUTINE_BOUNCE;
    }

    // ========================================================================
    // Routine $0A: Bounce between left/right bounds
    // ========================================================================

    private boolean updateBounce() {
        // ROM checks the timer before boundary handling; an expired frame displays
        // the opening animation setup without ObjectMove.
        actionTimer--;
        if (actionTimer < 0) {
            // ROM: ObjC5_CaseOpeningAnim - clr.b anim(a0)
            // Start open animation
            state.xVel = 0;
            openAnimFrame = 0;
            animSpeedCounter = OPEN_ANIM_SPEED; // ROM: animation speed counter
            currentFrame = OPEN_ANIM_FRAMES[0]; // Frame 0 (closed)
            state.routine = ROUTINE_OPEN_ANIM;
            return false;
        }

        bounceAtBounds();
        return true;
    }

    private void bounceAtBounds() {
        if (state.xVel < 0 && state.x < leftBound) {
            state.xVel = Math.abs(state.xVel);
            facingLeft = false;
        } else if (state.xVel >= 0 && state.x >= rightBound) {
            state.x = rightBound;
            state.xFixed = state.x << 16;
            state.xVel = -Math.abs(state.xVel);
            facingLeft = true;
        }
    }

    /**
     * ROM: ObjC5_CaseBoundaryLaserChk - STOP at bounds (clr.w x_vel).
     * Unlike bounceAtBounds which negates velocity, this clears it.
     */
    private void stopAtBounds() {
        if (state.xVel >= 0 && state.x >= rightBound) {
            state.x = rightBound;
            state.xFixed = state.x << 16;
            state.xVel = 0;
        } else if (state.xVel < 0 && state.x < leftBound) {
            state.xVel = 0;
        }
    }

    // ========================================================================
    // Routine $0C: Open animation
    // ========================================================================

    private void updateOpenAnim() {
        // ROM: Ani_objC5 anim 0: speed=5, frames {0,1,2,3,3,3,3}, then $FA loop.
        // AnimateSprite counts down speed counter; on 0 advances to next entry.
        // Speed=5 means 6 game frames per animation frame (counter: 5,4,3,2,1,0 then advance).
        animSpeedCounter--;
        if (animSpeedCounter < 0) {
            animSpeedCounter = OPEN_ANIM_SPEED;
            openAnimFrame++;
            if (openAnimFrame >= OPEN_ANIM_FRAMES.length) {
                // Animation complete, advance to signal shooter
                currentFrame = FRAME_CASE_OPEN_3;
                state.routine = ROUTINE_SIGNAL_SHOOTER;
                return;
            }
            currentFrame = OPEN_ANIM_FRAMES[openAnimFrame];
        }
    }

    // ========================================================================
    // Routine $0E: Signal laser shooter to descend
    // ========================================================================

    private void updateSignalShooter() {
        // Laser shooter descends
        laserSignaled = false;
        state.routine = ROUTINE_LOWER_SHOOTER;
        actionTimer = SHOOTER_LOWER_TIMER;
    }

    // ========================================================================
    // Routine $10: Move laser shooter down 1px/frame for 14 frames
    // ========================================================================

    private void updateLowerShooter() {
        // ROM: ObjC5_CaseLSDown - subq first, beq check, then move.
        // Timer=$0E. subq+beq = 13 frames of movement (14->13...1->0 triggers beq).
        actionTimer--;
        if (actionTimer == 0) {
            // ROM: ObjC5_CaseAddCollision
            state.routine = ROUTINE_ENABLE_COLLISION;
            actionTimer = COLLISION_WAIT_TIMER;
            collisionActive = true;
            if (state.hitCount <= 2) {
                collisionSuppressedAfterHit = true;
            }
            return;
        }
        if (laserShooter != null) {
            laserShooter.moveDown();
        }
    }

    // ========================================================================
    // Routine $12: Enable collision, timer=$40
    // ========================================================================

    private void updateEnableCollision() {
        // ROM: ObjC5_CaseWaitLoadLaser - subq.w #1,objoff_2A / bmi
        actionTimer--;
        if (actionTimer < 0) {
            state.routine = ROUTINE_SPAWN_LASER;
        }
    }

    // ========================================================================
    // Routine $14: Spawn laser
    // ========================================================================

    private void updateSpawnLaser(AbstractPlayableSprite player) {
        // ROM: ObjC5_CaseLoadLaser spawns laser then advances routine.
        // ObjC5_CaseWaitMove ($14) then waits for laser to signal.
        // We combine both in this routine.
        collisionActive = true;
        if (state.hitCount > 2) {
            collisionSuppressedAfterHit = false;
        }
        if (services().objectManager() != null && laser == null) {
            collisionSuppressedAfterHit = false;
            laser = spawnChild(() -> new WFZLaser(this));
            childComponents.add(laser);
            laserSignaled = false;
            return; // Wait for laser signal starting next frame
        }
        if (laser != null && !state.invulnerable && state.routine == ROUTINE_SPAWN_LASER) {
            collisionSuppressedAfterHit = false;
        }

        // ROM: ObjC5_CaseWaitMove - waits for laser to set status bit
        // btst #status.npc.misc,status(a1) / bne.s ObjC5_CaseLaserSpeed
        if (!laserSignaled) {
            return; // Keep waiting
        }

        // ROM: ObjC5_CaseLaserSpeed - set velocity and timer
        state.routine = ROUTINE_BOUNCE_WITH_LASER;
        actionTimer = LASER_BOUNCE_TIMER; // $80
        if (player != null) {
            if (player.getCentreX() < state.x) {
                state.xVel = -LASER_BOUNCE_SPEED;
            } else {
                state.xVel = LASER_BOUNCE_SPEED;
            }
        }
    }

    /** Called by laser child when fully extended. */
    void signalLaserExtended() {
        laserSignaled = true;
    }

    // ========================================================================
    // Routine $16: Bounce with laser
    // ========================================================================

    private boolean updateBounceWithLaser() {
        actionTimer--;
        if (actionTimer < 0) {
            state.routine = ROUTINE_RETRACT_SHOOTER;
            actionTimer = SHOOTER_LOWER_TIMER;
            state.xVel = 0;
            return false;
        }

        // ROM: ObjC5_CaseBoundaryLaserChk - STOP at boundary, don't bounce.
        // Unlike $0A which negates velocity, $16 uses clr.w x_vel(a0).
        stopAtBounds();
        return true;
    }

    // ========================================================================
    // Routine $18: Retract shooter, delete laser, clear collision
    // ========================================================================

    private void updateRetractShooter() {
        // ROM: ObjC5_CaseStopLaserDelete runs first:
        // Deletes laser, clears collision, sets timer=$0E for shooter retract.
        // Then ObjC5_CaseLSUp: subq+beq with moveUp each frame.
        if (laser != null) {
            laser.setDestroyed(true);
            laser = null;
        }
        collisionActive = false;

        // ROM: ObjC5_CaseLSUp - subq.w #1,objoff_2A / beq -> closing
        actionTimer--;
        if (actionTimer == 0) {
            // ROM: ObjC5_CaseClosingAnim - move.b #1,anim(a0)
            state.routine = ROUTINE_CLOSE_ANIM;
            openAnimFrame = 0; // Start at beginning of close sequence
            animSpeedCounter = CLOSE_ANIM_SPEED; // ROM: animation speed counter
            currentFrame = CLOSE_ANIM_FRAMES[0]; // Frame 3 (fully open)
            return;
        }
        if (laserShooter != null) {
            laserShooter.moveUp();
        }
    }

    // ========================================================================
    // Routine $1A: Close animation
    // ========================================================================

    private void updateCloseAnim() {
        // ROM: Ani_objC5 anim 1: speed=3, frames {3,2,1,0,0}, then $FA loop.
        // Speed=3 means 4 game frames per animation frame.
        animSpeedCounter--;
        if (animSpeedCounter < 0) {
            animSpeedCounter = CLOSE_ANIM_SPEED;
            openAnimFrame++;
            if (openAnimFrame >= CLOSE_ANIM_FRAMES.length) {
                // Animation complete, loop back
                currentFrame = FRAME_CASE_CLOSED;
                state.routine = ROUTINE_LOOP;
                return;
            }
            currentFrame = CLOSE_ANIM_FRAMES[openAnimFrame];
        }
    }

    // ========================================================================
    // Routine $1C: Loop back to $08
    // ========================================================================

    private void updateLoop() {
        state.routine = ROUTINE_SET_DIRECTION;
    }

    // ========================================================================
    // Routine $1E: Defeat
    // ========================================================================

    private void updateDefeat(int vIntRunCount) {
        // ROM: ObjC5_CaseDefeated - subq.w #1,objoff_30(a0) / bmi.s ObjC5_End
        defeatTimer--;

        if (defeatTimer < 0) {
            // ROM: ObjC5_End - play WFZ music, camera max Y=$720, delete.
            // Issue 16: ROM does NOT advance Dynamic_Resize_Routine here.
            services().playMusic(Sonic2Music.WING_FORTRESS.id);
            Camera camera = services().camera();
            if (camera != null) {
                // ROM ObjC5_End writes BOTH Camera_Max_Y_pos and
                // Camera_Max_Y_pos_target, immediately, in the boss's own slot
                // (docs/s2disasm/s2.asm:81519-81526).
                camera.setMaxY((short) CAMERA_MAX_Y_DEFEAT);
                camera.setMaxYTarget((short) CAMERA_MAX_Y_DEFEAT);
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
            ObjectLifetimeOps.markSpawnRemembered(services().objectManager(), spawn);
            setDestroyed(true);
            return;
        }

        // Spawn explosions
        if (defeatTimer % EXPLOSION_INTERVAL == 0) {
            spawnDefeatExplosion();
        }
    }

    // ========================================================================
    // Collision overrides
    // ========================================================================

    @Override
    public int getCollisionFlags() {
        if (state.invulnerable || state.defeated) {
            return 0;
        }
        if (collisionSuppressedAfterHit) {
            return 0;
        }
        // ROM: collision_flags=$06 only during phases $12-$16
        if (collisionActive) {
            return COLLISION_HITTABLE;
        }
        return 0;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_HITTABLE;
    }

    @Override
    protected int getInitialHitCount() {
        return DEFAULT_HIT_COUNT;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // Touch_Enemy_Part2 clears collision_flags. ObjC5_HandleHits only
        // restores them after the laser-shot status bit is set.
        collisionActive = false;
        collisionSuppressedAfterHit = true;
    }

    @Override
    protected int getInvulnerabilityDuration() {
        return INVULN_DURATION;
    }

    @Override
    protected int getPaletteLineForFlash() {
        // ROM: WFZ boss flashes palette line 2 (not line 1)
        return 2;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("wfzBoss sub=%02X timer=%04X xv=%04X yv=%04X colActive=%d colSup=%d hit=%d inv=%d laser=%d",
                state.routine & 0xFF,
                actionTimer & 0xFFFF,
                state.xVel & 0xFFFF,
                state.yVel & 0xFFFF,
                collisionActive ? 1 : 0,
                collisionSuppressedAfterHit ? 1 : 0,
                state.hitCount & 0xFF,
                state.invulnerable ? 1 : 0,
                laserSignaled ? 1 : 0);
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false; // Custom defeat logic in routine $1E
    }

    /**
     * {@code ObjC5_LaserCase} reads {@code routine_secondary(a0)} <em>once</em> at the head of
     * the object's update and {@code jsr}s the selected case
     * (docs/s2disasm/s2.asm:81246-81251); only after that case returns does it
     * {@code bra.w ObjC5_HandleHits}, whose {@code ObjC5_NoHitPointsLeft} arm writes
     * {@code objoff_30 = $EF} and {@code routine_secondary = $1E}
     * (docs/s2disasm/s2.asm:82045-82053).
     *
     * <p>The engine's touch scan runs before this object's {@code update()}, exactly as
     * {@code TouchResponse} runs from the player's own object code
     * (docs/s2disasm/s2.asm:38998) before the boss's slot. Two things follow from the ROM's
     * ordering: the previously selected case still runs on the killing frame, and the
     * {@code $1E} case does not run until the next object pass. Latch the install here and
     * apply it at the tail of {@link #updateBossLogic}, which is where
     * {@code ObjC5_HandleHits} sits.
     *
     * <p>{@code ObjC5_CaseDefeated} is {@code subq.w #1,objoff_30(a0) / bmi.s ObjC5_End}
     * (docs/s2disasm/s2.asm:81509-81515), so from $EF the end fires 240 dispatches after the
     * install, not 239.
     */
    @Override
    protected void onDefeatStarted() {
        pendingDefeatInstall = true;
    }

    /** ROM {@code ObjC5_NoHitPointsLeft} - runs after this frame's case, not before it. */
    private void installDefeat() {
        pendingDefeatInstall = false;
        state.routine = ROUTINE_DEFEAT;
        defeatTimer = DEFEAT_EXPLOSION_TIMER;
        state.xVel = 0;
        state.yVel = 0;
        collisionActive = false;
        collisionSuppressedAfterHit = false;

        // Signal children about defeat
        if (leftWall != null) {
            leftWall.signalDefeat();
        }
        if (rightWall != null) {
            rightWall.signalDefeat();
        }
        if (platformReleaser != null) {
            platformReleaser.signalDefeat();
        }
        if (laserShooter != null) {
            laserShooter.setDestroyed(true);
        }
        if (laser != null) {
            laser.setDestroyed(true);
            laser = null;
        }
        if (robotnik != null) {
            robotnik.signalDefeat();
        }
    }

    @Override
    public int getPriorityBucket() {
        return 4;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(
                Sonic2ObjectArtKeys.WFZ_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(currentFrame, state.x, state.y, facingLeft, false);
    }

    // ========================================================================
    // Accessors for tests
    // ========================================================================

    public int getCurrentRoutine() {
        return state.routine;
    }

    public int getDefeatTimer() {
        return defeatTimer;
    }

    public int getCurrentFrame() {
        return currentFrame;
    }

    public int getActionTimer() {
        return actionTimer;
    }

    public boolean isCollisionActive() {
        return collisionActive;
    }

    public int getLeftBound() {
        return leftBound;
    }

    public int getRightBound() {
        return rightBound;
    }

    public int getSpawnX() {
        return spawnX;
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

    // ========================================================================
    // Child Objects
    // ========================================================================

    private static Sonic2WFZBossInstance findNearestLiveBossForRewind(RewindRecreateContext ctx) {
        ObjectManager objectManager = objectManagerForRewind(ctx);
        if (objectManager == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        Sonic2WFZBossInstance best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (ObjectInstance inst : objectManager.getActiveObjects()) {
            if (inst instanceof Sonic2WFZBossInstance boss && !boss.isDestroyed()) {
                int distance = spawn == null ? 0 : Math.abs(boss.getSpawnX() - spawn.x());
                if (best == null || distance < bestDistance) {
                    best = boss;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static WFZFloatingPlatform findRestoredPlatformForHurt(RewindRecreateContext ctx) {
        ObjectManager objectManager = objectManagerForRewind(ctx);
        ObjectSpawn spawn = ctx.spawn();
        if (objectManager == null || spawn == null) {
            return null;
        }
        for (ObjectInstance inst : objectManager.getActiveObjects()) {
            if (inst instanceof WFZFloatingPlatform platform
                    && !platform.isDestroyed()
                    && platform.getX() == spawn.x()
                    && platform.getY() == spawn.y() - WFZPlatformHurt.Y_OFFSET) {
                return platform;
            }
        }
        return null;
    }

    private static ObjectManager objectManagerForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null) {
            return null;
        }
        return ctx.objectServices().objectManager();
    }

    private static void relinkWallForRewind(Sonic2WFZBossInstance boss, WFZLaserWall wall) {
        if (boss == null || wall == null) {
            return;
        }
        boolean leftSide = wall.getX() < boss.getSpawnX();
        boss.childComponents.removeIf(component ->
                component instanceof WFZLaserWall existing
                        && existing != wall
                        && (existing.getX() < boss.getSpawnX()) == leftSide);
        if (leftSide) {
            boss.leftWall = wall;
        } else {
            boss.rightWall = wall;
        }
        addChildComponentForRewind(boss, wall);
    }

    private static void relinkPlatformForRewind(Sonic2WFZBossInstance boss, WFZFloatingPlatform platform) {
        if (boss == null || platform == null) {
            return;
        }
        addChildComponentForRewind(boss, platform);
    }

    private static void relinkHurtForRewind(
            Sonic2WFZBossInstance boss,
            WFZFloatingPlatform platform,
            WFZPlatformHurt hurt) {
        if (boss == null || platform == null || hurt == null) {
            return;
        }
        platform.hurtChild = hurt;
        addChildComponentForRewind(boss, hurt);
    }

    private static void relinkPlatformReleaserForRewind(
            Sonic2WFZBossInstance boss,
            WFZPlatformReleaser platformReleaser) {
        if (boss == null || platformReleaser == null) {
            return;
        }
        boss.platformReleaser = platformReleaser;
        addChildComponentForRewind(boss, platformReleaser);
    }

    private static void relinkLaserShooterForRewind(
            Sonic2WFZBossInstance boss,
            WFZLaserShooter laserShooter) {
        if (boss == null || laserShooter == null) {
            return;
        }
        boss.laserShooter = laserShooter;
        addChildComponentForRewind(boss, laserShooter);
    }

    private static void relinkLaserForRewind(Sonic2WFZBossInstance boss, WFZLaser laser) {
        if (boss == null || laser == null) {
            return;
        }
        boss.laser = laser;
        addChildComponentForRewind(boss, laser);
    }

    private static void relinkRobotnikForRewind(Sonic2WFZBossInstance boss, WFZRobotnik robotnik) {
        if (boss == null || robotnik == null) {
            return;
        }
        boss.robotnik = robotnik;
        addChildComponentForRewind(boss, robotnik);
    }

    private static void relinkRobotnikPlatformForRewind(
            Sonic2WFZBossInstance boss,
            WFZRobotnik robotnik,
            WFZRobotnikPlatform robotnikPlatform) {
        if (boss == null || robotnik == null || robotnikPlatform == null) {
            return;
        }
        robotnik.robotnikPlatform = robotnikPlatform;
        addChildComponentForRewind(boss, robotnikPlatform);
    }

    private static WFZRobotnik findRestoredRobotnikForRewind(RewindRecreateContext ctx) {
        ObjectManager objectManager = objectManagerForRewind(ctx);
        if (objectManager == null) {
            return null;
        }
        for (ObjectInstance inst : objectManager.getActiveObjects()) {
            if (inst instanceof WFZRobotnik robotnik && !robotnik.isDestroyed()) {
                return robotnik;
            }
        }
        return null;
    }

    private static void addChildComponentForRewind(Sonic2WFZBossInstance boss, AbstractBossChild child) {
        if (!boss.childComponents.contains(child)) {
            boss.childComponents.add(child);
        }
    }

    /**
     * Laser Wall child (subtype $94, routine $04).
     * Solid wall at x +/- $88 from spawn. mapping_frame=$0C.
     * Width $13, Y radius $40, height $80.
     * On defeat: flash then fade-delete in 4 cycles.
     */
    static class WFZLaserWall extends AbstractBossChild implements SolidObjectProvider, RewindRecreatable {
        private boolean defeatSignaled;
        private int wallAnimFrame; // ROM anim_frame byte
        private int wallAnimFrameDuration; // ROM anim_frame_duration byte
        private int wallDeleteCounter; // ROM objoff_30 byte
        private boolean visibleThisFrame; // ROM objoff_2F display phase

        WFZLaserWall(Sonic2WFZBossInstance parent, int wallX, int wallY) {
            super(parent, "Laser Wall", 4, Sonic2ObjectIds.WFZ_BOSS);
            this.currentX = wallX;
            this.currentY = wallY;
            this.defeatSignaled = false;
            this.wallAnimFrame = 0;
            this.wallAnimFrameDuration = 0;
            this.wallDeleteCounter = 0;
            this.visibleThisFrame = true;
            updateDynamicSpawn();
        }

        private WFZLaserWall(Sonic2WFZBossInstance parent) {
            this(parent, parent.getX(), parent.getY());
        }

        @Override
        public WFZLaserWall recreateForRewind(RewindRecreateContext ctx) {
            Sonic2WFZBossInstance boss = findNearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            if (spawn == null) {
                // Drop, don't throw, on an absent spawn — match the sibling scans
                // (e.g. findRestoredPlatformForHurt). Prevents an NPE on spawn.x().
                return null;
            }
            WFZLaserWall wall = new WFZLaserWall(boss, spawn.x(), spawn.y());
            relinkWallForRewind(boss, wall);
            return wall;
        }

        void signalDefeat() {
            defeatSignaled = true;
            wallDeleteCounter = 4;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (!beginUpdate(vIntRunCount)) {
                return;
            }
            if (defeatSignaled) {
                updateDefeatDelete();
            } else {
                // ROM ObjC5_LaserWallWaitDelete: bchg #0,objoff_2F gates
                // DisplaySprite without changing the following SolidObject call.
                visibleThisFrame = !visibleThisFrame;
            }
            updateDynamicSpawn();
        }

        private void updateDefeatDelete() {
            // ROM ObjC5_LaserWallDelete: nested anim_frame_duration /
            // objoff_30 byte counters. The wall remains allocated and solid until
            // anim_frame reaches 5 and DeleteObject runs.
            visibleThisFrame = false;
            wallAnimFrameDuration = (wallAnimFrameDuration - 1) & 0xFF;
            if (signedByte(wallAnimFrameDuration) >= 0) {
                return;
            }

            int displayProbe = signedByte((wallAnimFrameDuration + 2) & 0xFF);
            if (displayProbe < 0) {
                wallAnimFrameDuration = wallAnimFrame;
                wallDeleteCounter = (wallDeleteCounter - 1) & 0xFF;
                if (signedByte(wallDeleteCounter) < 0) {
                    wallDeleteCounter = 0x10;
                    int nextFrame = (wallAnimFrame + 1) & 0xFF;
                    if (nextFrame >= 5) {
                        setDestroyed(true);
                        return;
                    }
                    wallAnimFrame = nextFrame;
                    wallAnimFrameDuration = nextFrame;
                }
            }
            // Every non-returning path reaches ObjC5_LaserWallDisplay, which
            // clears the active flicker bit and displays this frame.
            visibleThisFrame = true;
        }

        private static int signedByte(int value) {
            return (byte) (value & 0xFF);
        }

        @Override
        public void syncPositionWithParent() {
            // Walls are fixed position, do not follow parent
        }

        @Override
        protected boolean destroyWhenParentDestroyed() {
            return false;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || !visibleThisFrame) {
                return;
            }
            ObjectRenderManager renderManager =
                    services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(
                    Sonic2ObjectArtKeys.WFZ_BOSS);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(FRAME_WALL, currentX, currentY, false, false);
        }

        boolean isVisibleThisFrameForTest() {
            return visibleThisFrame;
        }

        // ROM: ObjC5_LaserWall calls SolidObject with d1=$13, d2=$40, d3=$80
        private static final SolidObjectParams WALL_SOLID_PARAMS =
                SolidObjectParams.of(0x13, 0x40, 0x80);

        @Override
        public SolidObjectParams getSolidParams() {
            return WALL_SOLID_PARAMS;
        }
    }

    /**
     * Platform Releaser child (subtype $96, routine $06).
     * mapping_frame=5, follows laser case X.
     * After signal: timer=$40, y_vel=$40, move down.
     * Spawns platforms cyclically (max 3, interval $80 frames).
     * On defeat: spawn explosions then delete.
     */
    static class WFZPlatformReleaser extends AbstractBossChild implements RewindRecreatable {
        private static final int PLATFORM_SPAWN_INTERVAL = 0x80;
        /**
         * ROM ObjC5_PlatformReleaserStop stores $10 in objoff_2A before entering
         * the load-wait routine. In this engine the parent signal reaches the child
         * during the same frame's child pass, so the first release must retain the
         * eight-frame phase that the ROM's object scheduler has already spent before
         * the recorded platform appears.
         */
        private static final int FIRST_SPAWN_TIMER = 0x18;
        private static final int MAX_PLATFORMS = 3;
        private static final int MOVE_DOWN_TIMER = 0x40;
        private static final int MOVE_DOWN_SPEED = 0x40;

        private boolean started;
        private boolean defeatSignaled;
        private int spawnTimer;
        private int platformCount;
        private int moveDownTimer;
        private int yFixed;

        WFZPlatformReleaser(Sonic2WFZBossInstance parent) {
            super(parent, "Platform Releaser", 4, Sonic2ObjectIds.WFZ_BOSS);
            this.started = false;
            this.defeatSignaled = false;
            this.spawnTimer = 0;
            this.platformCount = 0;
            this.moveDownTimer = 0;
            // ROM: ObjC5_PlatformReleaserInit - addq.w #8,y_pos(a0)
            // Issue 14: +8 Y offset from parent position
            this.currentX = parent.getX();
            this.currentY = parent.getY() + 8;
            this.yFixed = currentY << 16;
            updateDynamicSpawn();
        }

        @Override
        public WFZPlatformReleaser recreateForRewind(RewindRecreateContext ctx) {
            Sonic2WFZBossInstance boss = findNearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            WFZPlatformReleaser platformReleaser = new WFZPlatformReleaser(boss);
            relinkPlatformReleaserForRewind(boss, platformReleaser);
            return platformReleaser;
        }

        void signalStart() {
            if (!started) {
                started = true;
                // ROM: ObjC5_PlatformReleaserStop sets first timer=$10 (not $80).
                // Issue 15: First platform appears much sooner.
                spawnTimer = FIRST_SPAWN_TIMER;
                moveDownTimer = MOVE_DOWN_TIMER;
            }
        }

        void signalDefeat() {
            defeatSignaled = true;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) {
                return;
            }

            if (defeatSignaled) {
                // Spawn explosion then delete
                setDestroyed(true);
                return;
            }

            if (started) {
                // ROM: Two sequential phases (not parallel):
                // Phase $04: Move down for $40 frames with y_vel=$40
                // Phase $06: Spawn platforms cyclically
                if (moveDownTimer > 0) {
                    // Phase $04: ObjC5_PlatformReleaserDown
                    moveDownTimer--;
                    if (moveDownTimer == 0) {
                        // ROM: ObjC5_PlatformReleaserStop - clr.w y_vel, timer=$10
                        spawnTimer = FIRST_SPAWN_TIMER; // $10, not $80
                    } else {
                        yFixed += (MOVE_DOWN_SPEED << 8);
                        currentY = yFixed >> 16;
                    }
                } else {
                    // Phase $06: ObjC5_PlatformReleaserLoadWait
                    spawnTimer--;
                    if (spawnTimer == 0 && platformCount < MAX_PLATFORMS) {
                        spawnPlatform();
                        spawnTimer = PLATFORM_SPAWN_INTERVAL; // $80
                    }
                }
            }

            updateDynamicSpawn();
        }

        private void spawnPlatform() {
            if (services().objectManager() == null) {
                return;
            }
            Sonic2WFZBossInstance wfzParent = (Sonic2WFZBossInstance) parent;
            WFZFloatingPlatform platform = spawnChild(() -> new WFZFloatingPlatform(wfzParent, currentX, currentY));
            wfzParent.childComponents.add(platform);
            platform.spawnHurtChild(wfzParent);
            platformCount++;
        }

        @Override
        public void syncPositionWithParent() {
            // ROM LoadChildObject copies x_pos/y_pos once. ObjC5_PlatformReleaser
            // then only changes y_pos in its own Down routine; it does not follow
            // the laser case horizontally after the case starts moving.
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            ObjectRenderManager renderManager =
                    services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(
                    Sonic2ObjectArtKeys.WFZ_BOSS);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(FRAME_PLATFORM_RELEASER, currentX, currentY, false, false);
        }
    }

    /**
     * Floating Platform child (subtype $98, routine $08).
     * anim=3, mapping_frame=7. Player can stand on it (PlatformObject).
     * Descend y_vel=$100 for $60 frames, then go left x_vel=-$100, timer=$60.
     * Reverse x_vel every $C0 frames, oscillate Y by +/- $04.
     * Spawns PlatformHurt child below it.
     * On defeat: explode, delete hurt child.
     */
    static class WFZFloatingPlatform extends AbstractBossChild implements SolidObjectProvider, RewindRecreatable {
        private static final int DESCEND_SPEED = 0x100;
        private static final int DESCEND_DURATION = 0x60;
        private static final int HORIZONTAL_SPEED = 0x100;
        private static final int HORIZONTAL_TIMER = 0x60;
        private static final int REVERSE_INTERVAL = 0xC0;
        private static final int Y_OSCILLATION = 0x04;

        private int phase; // 0=descend, 1=horizontal movement
        private int phaseTimer;
        private int reverseTimer;
        private int xVel;
        private int yVel;
        private int xFixed;
        private int yFixed;
        private int baseY; // ROM: objoff_34 - Y reference for spring oscillation
        private WFZPlatformHurt hurtChild;

        WFZFloatingPlatform(Sonic2WFZBossInstance parent, int startX, int startY) {
            super(parent, "Floating Platform", 4, Sonic2ObjectIds.WFZ_BOSS);
            this.currentX = startX;
            this.currentY = startY;
            this.xFixed = startX << 16;
            this.yFixed = startY << 16;
            this.phase = 0;
            this.phaseTimer = DESCEND_DURATION;
            this.reverseTimer = 0;
            this.xVel = 0;
            this.yVel = DESCEND_SPEED;
            this.baseY = 0;
            updateDynamicSpawn();
        }

        private WFZFloatingPlatform(Sonic2WFZBossInstance parent) {
            this(parent, parent.getX(), parent.getY());
        }

        @Override
        public WFZFloatingPlatform recreateForRewind(RewindRecreateContext ctx) {
            Sonic2WFZBossInstance boss = findNearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            if (spawn == null) {
                // Drop, don't throw, on an absent spawn — match the sibling scans
                // (e.g. findRestoredPlatformForHurt). Prevents an NPE on spawn.x().
                return null;
            }
            WFZFloatingPlatform platform = new WFZFloatingPlatform(boss, spawn.x(), spawn.y());
            relinkPlatformForRewind(boss, platform);
            return platform;
        }

        private void spawnHurtChild(Sonic2WFZBossInstance wfzParent) {
            if (hurtChild != null || services().objectManager() == null) {
                return;
            }
            hurtChild = spawnChild(() -> new WFZPlatformHurt(wfzParent, this));
            wfzParent.childComponents.add(hurtChild);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) {
                return;
            }

            // Destroy platform and hurt child when boss is defeated
            if (parent != null && parent.getState().defeated) {
                if (hurtChild != null) {
                    hurtChild.setDestroyed(true);
                }
                setDestroyed(true);
                return;
            }

            switch (phase) {
                case 0 -> {
                    // ROM: ObjC5_PlatformDownWait - descend phase with ObjectMove
                    // subq.w #1,objoff_2A / beq -> PlatformLeft
                    phaseTimer--;
                    if (phaseTimer == 0) {
                        // ROM: ObjC5_PlatformLeft
                        phase = 1;
                        xVel = -HORIZONTAL_SPEED;
                        // ROM: move.w #$60,objoff_2A(a0) - first leftward timer
                        reverseTimer = HORIZONTAL_TIMER; // $60 (NOT $C0)
                        // ROM: move.w y_pos(a0),objoff_34(a0) - store base Y for oscillation
                        baseY = currentY;
                        // yVel stays from descent (ROM doesn't clear it here, ObjectMove applies it)
                    }
                    // ROM: ObjC5_PlatformMakeSolid - ObjectMove
                    xFixed += (xVel << 8);
                    yFixed += (yVel << 8);
                    currentX = xFixed >> 16;
                    currentY = yFixed >> 16;
                }
                case 1 -> {
                    // ROM: ObjC5_PlatformTestChangeDirection
                    // Check timer and reverse direction
                    reverseTimer--;
                    if (reverseTimer == 0) {
                        reverseTimer = REVERSE_INTERVAL; // $C0
                        xVel = -xVel;
                    }

                    // ROM: ObjC5_PlatformTestLeftRight / ObjC5_PlatformChangeY
                    // Spring-like Y oscillation: add +-4 to Y VELOCITY (not position)
                    // ROM: moveq #4,d0 / cmp.w objoff_34,y_pos / blo -> add / neg d0
                    // ROM: add.w d0,y_vel(a0)
                    int yAccel = Y_OSCILLATION;
                    if (currentY >= baseY) {
                        yAccel = -yAccel;
                    }
                    yVel += yAccel;

                    // ROM: ObjC5_PlatformMakeSolid - ObjectMove
                    xFixed += (xVel << 8);
                    yFixed += (yVel << 8);
                    currentX = xFixed >> 16;
                    currentY = yFixed >> 16;
                }
            }

            // Update hurt child position
            if (hurtChild != null && !hurtChild.isDestroyed()) {
                hurtChild.syncToParentPlatform(currentX, currentY);
            }

            updateDynamicSpawn();
        }

        @Override
        public void syncPositionWithParent() {
            // Platform moves independently
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            ObjectRenderManager renderManager =
                    services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(
                    Sonic2ObjectArtKeys.WFZ_BOSS);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(FRAME_PLATFORM, currentX, currentY, false, false);
        }

        // ROM: ObjC5_PlatformMakeSolid - PlatformObject with d1=$10, d2=8, d3=8
        // PlatformObject = top-solid only platform
        private static final SolidObjectParams PLATFORM_SOLID_PARAMS =
                SolidObjectParams.of(0x10, 8, 8);

        @Override
        public SolidObjectParams getSolidParams() {
            return PLATFORM_SOLID_PARAMS;
        }

        @Override
        public boolean isTopSolidOnly() {
            return true; // ROM: PlatformObject = top-solid only
        }
    }

    /**
     * Platform Hurt child (subtype $9A, routine $0A).
     * Invisible damage collider, collision_flags=$98.
     * Follows parent platform, offset Y+$0C.
     * Delete when parent signals defeat.
     */
    static class WFZPlatformHurt extends AbstractBossChild implements TouchResponseProvider, RewindRecreatable {
        private static final int Y_OFFSET = 0x0C;
        private static final int COLLISION_FLAGS = 0x98;
        private final WFZFloatingPlatform platformParent;

        WFZPlatformHurt(Sonic2WFZBossInstance bossParent, WFZFloatingPlatform platformParent) {
            super(bossParent, "Platform Hurt", 4, Sonic2ObjectIds.WFZ_BOSS);
            this.platformParent = platformParent;
            syncToParentPlatform(platformParent.getCurrentX(), platformParent.getCurrentY());
            updateDynamicSpawn();
        }

        private WFZPlatformHurt(
                Sonic2WFZBossInstance bossParent,
                WFZFloatingPlatform platformParent,
                int ignored) {
            this(bossParent, platformParent);
        }

        @Override
        public WFZPlatformHurt recreateForRewind(RewindRecreateContext ctx) {
            Sonic2WFZBossInstance boss = findNearestLiveBossForRewind(ctx);
            WFZFloatingPlatform platform = findRestoredPlatformForHurt(ctx);
            if (boss == null || platform == null) {
                return null;
            }
            WFZPlatformHurt hurt = new WFZPlatformHurt(boss, platform);
            relinkHurtForRewind(boss, platform, hurt);
            return hurt;
        }

        void syncToParentPlatform(int platformX, int platformY) {
            this.currentX = platformX;
            this.currentY = platformY + Y_OFFSET;
        }

        @Override
        public int getCollisionFlags() {
            if (isDestroyed()) {
                return 0;
            }
            return COLLISION_FLAGS;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) {
                return;
            }
            if (platformParent.isDestroyed()) {
                setDestroyed(true);
            }
            updateDynamicSpawn();
        }

        @Override
        public void syncPositionWithParent() {
            if (platformParent != null && !platformParent.isDestroyed()) {
                syncToParentPlatform(platformParent.getCurrentX(), platformParent.getCurrentY());
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Invisible - no rendering
        }
    }

    /**
     * Laser Shooter child (subtype $9C, routine $0C).
     * mapping_frame=4, follows parent position.
     * Controlled by parent (lowered/raised during laser phases).
     */
    static class WFZLaserShooter extends AbstractBossChild implements RewindRecreatable {
        private int yOffset;

        WFZLaserShooter(Sonic2WFZBossInstance parent) {
            // ROM ObjC5_SubObjData3 gives the lens/shooter sprite priority 5 (behind
            // the case at priority 4), so the closed cover occludes it and the open
            // frame reveals it. Bucket 3 drew the lens in FRONT of the cover, breaking
            // both the layering and the "cover opens to expose the lens" reveal.
            // s2.asm:82031-82032 (case pri 4), 82036-82038 (shooter pri 5).
            super(parent, "Laser Shooter", 5, Sonic2ObjectIds.WFZ_BOSS);
            this.yOffset = 0;
        }

        @Override
        public WFZLaserShooter recreateForRewind(RewindRecreateContext ctx) {
            Sonic2WFZBossInstance boss = findNearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            WFZLaserShooter laserShooter = new WFZLaserShooter(boss);
            relinkLaserShooterForRewind(boss, laserShooter);
            return laserShooter;
        }

        void moveDown() {
            yOffset++;
        }

        void moveUp() {
            if (yOffset > 0) {
                yOffset--;
            }
        }

        int getYOffset() {
            return yOffset;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) {
                return;
            }
            syncPositionWithParent();
            updateDynamicSpawn();
        }

        @Override
        public void syncPositionWithParent() {
            if (parent != null && !parent.isDestroyed()) {
                this.currentX = parent.getX();
                this.currentY = parent.getY() + yOffset;
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            ObjectRenderManager renderManager =
                    services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(
                    Sonic2ObjectArtKeys.WFZ_BOSS);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(FRAME_LASER_SHOOTER, currentX, currentY, false, false);
        }
    }

    /**
     * Laser child (subtype $9E, routine $0E).
     * ROM sub-state machine:
     *   $00 (Init): mapping=$0D, y+=$10, anim_frame=$C, y-=3. Advance.
     *   $02 (Flash): Flicker countdown. 12 frames flickering (anim_frame_duration counts
     *        from $C down with nested loops). On complete advance.
     *   $04 (WaitShoot): Timer=$40, countdown. On expire advance.
     *   $06 (Shoot): 5 stages, each adds $10 to y_pos, sets mapping+collision.
     *        On complete: signal parent, advance.
     *   $08 (Move): Follow parent X while laser is active.
     *
     * Flicker via bchg #0,objoff_2F toggles visibility each frame.
     * Signals parent when fully extended.
     */
    static class WFZLaser extends AbstractBossChild implements TouchResponseProvider, RewindRecreatable {
        private static final int[] LASER_MAPPING_FRAMES = {0x0E, 0x0F, 0x10, 0x11, 0x12};
        private static final int[] LASER_COLLISION_FLAGS = {0x86, 0xAB, 0xAC, 0xAD, 0xAE};
        /** ROM: ObjC5_LaseNext - move.w #$40,objoff_2A(a0) */
        private static final int CHARGE_WAIT_TIMER = 0x40;
        /** ROM: ObjC5_LaserInit - move.b #$C,anim_frame(a0) */
        private static final int INITIAL_FLASH_ANIM_FRAME = 0x0C;

        // Sub-states matching ROM routine_secondary values
        private static final int STATE_INIT = 0;
        private static final int STATE_FLASH = 2;
        private static final int STATE_WAIT_SHOOT = 4;
        private static final int STATE_SHOOT = 6;
        private static final int STATE_MOVE = 8;

        private int subState;
        private int currentMappingFrame;
        private boolean flickerToggle; // ROM: bchg #0,objoff_2F
        private int flashAnimFrame; // ROM anim_frame byte
        private int flashAnimFrameDuration; // ROM anim_frame_duration byte
        private int waitTimer;
        private int shootStage; // 0-4 for the 5 extension stages
        private boolean forceHide; // During flash phase, alternates visibility
        private int currentCollisionFlags; // ROM collision_flags(a0): 0 until shooting

        WFZLaser(Sonic2WFZBossInstance parent) {
            super(parent, "Laser", 4, Sonic2ObjectIds.WFZ_BOSS);
            this.subState = STATE_INIT;
            this.currentMappingFrame = FRAME_LASER_BASE; // $0D
            this.flickerToggle = false;
            this.flashAnimFrame = INITIAL_FLASH_ANIM_FRAME;
            this.flashAnimFrameDuration = 0;
            this.waitTimer = 0;
            this.shootStage = 0;
            this.forceHide = false;
            this.currentCollisionFlags = 0; // ROM ObjC5_LaserInit: collision_flags = 0
        }

        @Override
        public WFZLaser recreateForRewind(RewindRecreateContext ctx) {
            Sonic2WFZBossInstance boss = findNearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            WFZLaser laser = new WFZLaser(boss);
            relinkLaserForRewind(boss, laser);
            return laser;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) {
                return;
            }
            syncPositionWithParent();

            switch (subState) {
                case STATE_INIT -> {
                    // ROM: ObjC5_LaserInit
                    // mapping=$0D, priority=4, collision=0, y+=$10, anim_frame=$C, y-=3
                    // Net y offset: +$10 - 3 = +$0D
                    currentY += 0x0D;
                    flashAnimFrame = INITIAL_FLASH_ANIM_FRAME;
                    flashAnimFrameDuration = 0;
                    subState = STATE_FLASH;
                }
                case STATE_FLASH -> {
                    // ROM: ObjC5_LaserFlash uses anim_frame_duration as a nested
                    // byte counter. Only when it underflows past -2 does anim_frame
                    // decrement; anim_frame==1 advances to the charge wait.
                    forceHide = true;
                    flashAnimFrameDuration = (flashAnimFrameDuration - 1) & 0xFF;
                    int durationSigned = signedByte(flashAnimFrameDuration);
                    if (durationSigned >= 0) {
                        break;
                    }

                    int flickerProbe = signedByte((flashAnimFrameDuration + 2) & 0xFF);
                    if (flickerProbe >= 0) {
                        forceHide = false;
                        break;
                    }

                    int nextAnimFrame = (flashAnimFrame - 1) & 0xFF;
                    if (nextAnimFrame == 0) {
                        // ROM: ObjC5_LaseNext
                        subState = STATE_WAIT_SHOOT;
                        waitTimer = CHARGE_WAIT_TIMER; // $40
                        forceHide = false;
                    } else {
                        flashAnimFrame = nextAnimFrame;
                        flashAnimFrameDuration = nextAnimFrame;
                    }
                }
                case STATE_WAIT_SHOOT -> {
                    // ROM: ObjC5_LaseWaitShoot - subq.w #1,objoff_2A / bmi
                    waitTimer--;
                    if (waitTimer < 0) {
                        // ROM: ObjC5_LaseStartShooting - y+=$10
                        currentY += 0x10;
                        subState = STATE_SHOOT;
                        shootStage = 0;
                        // Set stage 0 mapping frame immediately so it's visible
                        // before the first shootStage++ in STATE_SHOOT
                        currentMappingFrame = LASER_MAPPING_FRAMES[0];
                    }
                }
                case STATE_SHOOT -> {
                    // ROM: ObjC5_LaserShoot - advance through 5 extension stages
                    // Each stage: y+=$10, set mapping_frame and collision_flags
                    shootStage++;
                    if (shootStage >= 5) {
                        // ROM: ObjC5_LaseShotOut - signal parent
                        subState = STATE_MOVE;
                        Sonic2WFZBossInstance wfzParent = (Sonic2WFZBossInstance) parent;
                        wfzParent.signalLaserExtended();
                    } else {
                        currentY += 0x10;
                        currentMappingFrame = LASER_MAPPING_FRAMES[shootStage];
                        // ROM ObjC5_LaserShoot: collision_flags = ObjC5_LaserCollisionData[stage]
                        // (s2.asm:81876,81895-81901). Retained through STATE_MOVE (ROM never
                        // clears it after LaseShotOut), so the fully-extended beam keeps hurting.
                        currentCollisionFlags = LASER_COLLISION_FLAGS[shootStage];
                    }
                }
                case STATE_MOVE -> {
                    // ROM: ObjC5_LaserMove - follow parent X
                    if (parent != null && !parent.isDestroyed()) {
                        currentX = parent.getX();
                    }
                }
            }

            // ROM: bchg #0,objoff_2F / bne -> skip display (flicker every other frame)
            flickerToggle = !flickerToggle;

            updateDynamicSpawn();
        }

        @Override
        public int getCollisionFlags() {
            // ROM keeps collision_flags(a0) live independent of the display flicker
            // (bchg #0,objoff_2F only gates DisplaySprite, s2.asm:81811-81813), so the
            // beam hurts every frame it is set, even on flicker-hidden frames.
            if (isDestroyed()) {
                return 0;
            }
            return currentCollisionFlags;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public void syncPositionWithParent() {
            if (parent != null && !parent.isDestroyed()) {
                Sonic2WFZBossInstance wfzParent = (Sonic2WFZBossInstance) parent;
                this.currentX = parent.getX();
                // Position below the laser shooter (only on init/flash/wait phases)
                if (subState <= STATE_WAIT_SHOOT && wfzParent.laserShooter != null) {
                    this.currentY = wfzParent.laserShooter.getCurrentY();
                }
            }
        }

        private static int signedByte(int value) {
            return (byte) (value & 0xFF);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            // ROM: bchg #0,objoff_2F / bne -> don't display
            if (flickerToggle || forceHide) {
                return;
            }
            ObjectRenderManager renderManager =
                    services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(
                    Sonic2ObjectArtKeys.WFZ_BOSS);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(currentMappingFrame, currentX, currentY, false, false);
        }
    }

    /**
     * Robotnik child (subtype $A0, routine $10).
     * Uses ObjC6 mappings (0x3D0EE), fixed at ($2C60, $4E6).
     * Spawns RobotnikPlatform child.
     * On defeat: timer=$C0, move down 1px/frame, then delete.
     */
    static class WFZRobotnik extends AbstractBossChild implements RewindRecreatable {
        private static final int ROBOTNIK_X = 0x2C60;
        private static final int ROBOTNIK_Y = 0x04E6;
        private static final int DEFEAT_MOVE_TIMER = 0xC0;
        // ROM: Ani_objC5_objC6 anim 1: speed=5, frames {6,7}, $FF loop
        private static final int ROBOTNIK_ANIM_SPEED = 5;
        private static final int[] ROBOTNIK_ANIM_FRAMES = {6, 7};

        private boolean defeatSignaled;
        private int defeatTimer;
        private WFZRobotnikPlatform robotnikPlatform;
        private int animFrameIndex; // Index into ROBOTNIK_ANIM_FRAMES
        private int animSpeedCounter; // Counts down for animation speed
        private int currentMappingFrame; // Frame to render

        WFZRobotnik(Sonic2WFZBossInstance parent) {
            super(parent, "WFZ Robotnik", 4, Sonic2ObjectIds.WFZ_BOSS);
            this.currentX = ROBOTNIK_X;
            this.currentY = ROBOTNIK_Y;
            this.defeatSignaled = false;
            this.defeatTimer = 0;
            // ROM: move.b #1,anim(a0) - anim 1 from init
            this.animFrameIndex = 0;
            this.animSpeedCounter = ROBOTNIK_ANIM_SPEED;
            this.currentMappingFrame = ROBOTNIK_ANIM_FRAMES[0];

        }

        @Override
        public WFZRobotnik recreateForRewind(RewindRecreateContext ctx) {
            Sonic2WFZBossInstance boss = findNearestLiveBossForRewind(ctx);
            if (boss == null) {
                return null;
            }
            WFZRobotnik robotnik = new WFZRobotnik(boss);
            relinkRobotnikForRewind(boss, robotnik);
            return robotnik;
        }

        private void spawnPlatformChild(Sonic2WFZBossInstance wfzParent) {
            if (robotnikPlatform != null || services().objectManager() == null) {
                return;
            }
            robotnikPlatform = spawnChild(() -> new WFZRobotnikPlatform(wfzParent, this));
            wfzParent.childComponents.add(robotnikPlatform);
        }

        @Override
        protected boolean destroyWhenParentDestroyed() {
            return false;
        }

        void signalDefeat() {
            defeatSignaled = true;
            defeatTimer = DEFEAT_MOVE_TIMER;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) {
                return;
            }

            if (defeatSignaled) {
                // ROM: ObjC5_RobotnikDown - subq.w #1,objoff_2A / bmi -> delete
                // Timer=$C0. subq+bmi = 192 frames of movement (timer values 191..0),
                // then at -1 deletes. Issue 13: use < 0 pattern.
                defeatTimer--;
                if (defeatTimer < 0) {
                    // ROM: ObjC5_RobotnikDelete
                    setDestroyed(true);
                    if (robotnikPlatform != null) {
                        robotnikPlatform.setDestroyed(true);
                    }
                } else {
                    // Move down 1px/frame
                    currentY++;
                }
            } else {
                // ROM: ObjC5_RobotnikAnimate - Ani_objC5_objC6 anim 1
                // Issue 12: Animate Robotnik (speed=5, frames 6,7 looping)
                animSpeedCounter--;
                if (animSpeedCounter < 0) {
                    animSpeedCounter = ROBOTNIK_ANIM_SPEED;
                    animFrameIndex = (animFrameIndex + 1) % ROBOTNIK_ANIM_FRAMES.length;
                    currentMappingFrame = ROBOTNIK_ANIM_FRAMES[animFrameIndex];
                }
            }

            // Update platform position
            if (robotnikPlatform != null && !robotnikPlatform.isDestroyed()) {
                robotnikPlatform.syncToRobotnik(currentX, currentY);
            }

            updateDynamicSpawn();
        }

        @Override
        public void syncPositionWithParent() {
            // Robotnik is at fixed position
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            ObjectRenderManager renderManager =
                    services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(
                    Sonic2ObjectArtKeys.WFZ_ROBOTNIK);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            // ROM: Ani_objC5_objC6 anim 1: frames 6,7 at speed 5
            renderer.drawFrameIndex(currentMappingFrame, currentX, currentY, false, false);
        }
    }

    /**
     * Robotnik Platform child (subtype $A2, routine $12).
     * Uses FloatingPlatform mappings (0x3CEBC), follows Robotnik Y+$26.
     */
    static class WFZRobotnikPlatform extends AbstractBossChild implements RewindRecreatable {
        private static final int Y_OFFSET = 0x26;
        private final WFZRobotnik robotnikParent;

        WFZRobotnikPlatform(Sonic2WFZBossInstance bossParent, WFZRobotnik robotnikParent) {
            super(bossParent, "Robotnik Platform", 5, Sonic2ObjectIds.WFZ_BOSS);
            this.robotnikParent = robotnikParent;
            syncToRobotnik(robotnikParent.getCurrentX(), robotnikParent.getCurrentY());
        }

        private WFZRobotnikPlatform(Sonic2WFZBossInstance bossParent) {
            this(bossParent, new WFZRobotnik(bossParent));
        }

        private WFZRobotnikPlatform(
                Sonic2WFZBossInstance bossParent,
                WFZRobotnik robotnikParent,
                int ignored) {
            this(bossParent, robotnikParent);
        }

        @Override
        public WFZRobotnikPlatform recreateForRewind(RewindRecreateContext ctx) {
            Sonic2WFZBossInstance boss = findNearestLiveBossForRewind(ctx);
            WFZRobotnik robotnik = findRestoredRobotnikForRewind(ctx);
            if (boss == null || robotnik == null) {
                return null;
            }
            WFZRobotnikPlatform robotnikPlatform = new WFZRobotnikPlatform(boss, robotnik);
            relinkRobotnikPlatformForRewind(boss, robotnik, robotnikPlatform);
            return robotnikPlatform;
        }

        void syncToRobotnik(int robotnikX, int robotnikY) {
            this.currentX = robotnikX;
            this.currentY = robotnikY + Y_OFFSET;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
            if (!beginUpdate(vIntRunCount)) {
                return;
            }
            if (robotnikParent.isDestroyed()) {
                setDestroyed(true);
            }
            updateDynamicSpawn();
        }

        @Override
        public void syncPositionWithParent() {
            if (robotnikParent != null && !robotnikParent.isDestroyed()) {
                syncToRobotnik(robotnikParent.getCurrentX(), robotnikParent.getCurrentY());
            }
        }

        @Override
        protected boolean destroyWhenParentDestroyed() {
            return false;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            ObjectRenderManager renderManager =
                    services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(
                    Sonic2ObjectArtKeys.WFZ_ROBOTNIK_PLATFORM);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(0, currentX, currentY, false, false);
        }
    }
}
