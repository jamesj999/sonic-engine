package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;

/**
 * Object 0x6E &mdash; HCZ Water Drop (Sonic 3 &amp; Knuckles, Hydrocity Zone).
 * <p>
 * A spawner object that periodically creates falling water droplets. The spawner
 * sits at the level layout position displaying a small static drip (frame 6) and
 * spawns child drops at an interval determined by subtype. Children animate a
 * forming drip (frames 0-3), then fall with gravity, check for floor collision,
 * and play a splash animation (frames 4-5) before deleting.
 * <p>
 * Children have Special collision ($C7): touching them triggers a splash but
 * does not hurt the player. If the player's animation is 5 (push) at the time
 * of collision, their prev_anim is reset to force an animation restart.
 * <p>
 * ROM reference: Obj_WaterDrop (sonic3k.asm:75145-75239).
 */
public class HCZWaterDropObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final String ART_KEY = Sonic3kObjectArtKeys.HCZ_WATER_DROP;

    // ===== Dimensions from ROM (sonic3k.asm:75150-75153) =====
    private static final int WIDTH_PIXELS = 8;
    private static final int X_RADIUS = 8;
    private static final int Y_RADIUS = 7;

    // ===== Animation constants from Ani_HCZWaterDrop =====
    /** Animation speed: 5 frames per anim step (ROM: dc.b 4 = delay of 4+1 ticks) */
    private static final int ANIM_SPEED = 4;

    /** Animation 0 (drip forming): frames 0, 1, 2, 2, $FC, 3, $FE 1 */
    private static final int[] ANIM_0_FRAMES = {0, 1, 2, 2};
    /** After $FC fires, animation continues with frame 3 looping */
    private static final int ANIM_0_FALL_FRAME = 3;

    /** Animation 1 (splash): frames 4, 5, $FC */
    private static final int[] ANIM_1_FRAMES = {4, 5};

    // ===== Physics from ROM (sonic3k.asm:75186) =====
    /** Gravity: addi.w #8,y_vel(a0) — 8 subpixels/frame */
    private static final int GRAVITY = 8;

    // ===== Collision from ROM (sonic3k.asm:75175) =====
    /** collision_flags = $C7: Special type ($C0) + size index 7 */
    private static final int COLLISION_FLAGS = 0xC7;

    // ===== Player animation check (sonic3k.asm:75230) =====
    /** ROM: cmpi.b #5,anim(a2) — push animation ID */
    private static final int PUSH_ANIM_ID = 5;

    private int spawnX;
    private int spawnY;
    /** Spawn interval = subtype * 4 frames (sonic3k.asm:75161-75162) */
    private int spawnInterval;

    private int spawnTimer;

    public HCZWaterDropObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZWaterDrop");
        this.spawnX = spawn.x();
        this.spawnY = spawn.y();
        int subtype = spawn.subtype() & 0xFF;
        this.spawnInterval = subtype * 4;
    }

    @Override
    public HCZWaterDropObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HCZWaterDropObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) return;

        // ROM: subq.w #1,$30(a0) / bpl.s skip
        spawnTimer--;
        if (spawnTimer < 0) {
            // ROM: move.b subtype(a0),d0 / lsl.w #2,d0 / move.w d0,$30(a0)
            spawnTimer = spawnInterval;

            // ROM: tst.b render_flags(a0) / bpl.s skip — only spawn when on-screen
            if (isOnScreen(WIDTH_PIXELS)) {
                // ROM: AllocateObjectAfterCurrent — spawn child drop
                spawnChild(() -> new WaterDropChild(buildSpawnAt(spawnX, spawnY)));
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // ROM renders frame 6 via Sprite_OnScreen_Test, but the art_tile addition
        // ($235C + $FCA4 = $2000, truncated 16-bit) produces tile 0 / palette 1,
        // which is effectively invisible. Skip rendering the spawner.
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) return;
        ctx.drawRect(spawnX, spawnY, X_RADIUS, Y_RADIUS,
                0.3f, 0.6f, 1.0f);
    }

    @Override
    public int getX() { return spawnX; }

    @Override
    public int getY() { return spawnY; }

    @Override
    public ObjectSpawn getSpawn() { return buildSpawnAt(spawnX, spawnY); }

    // =========================================================================
    // Child: Falling Water Drop
    // =========================================================================

    /**
     * A falling water droplet spawned by the parent WaterDrop object.
     * <p>
     * Lifecycle (sonic3k.asm:75182-75239):
     * <ol>
     *   <li>Play forming animation (frames 0,1,2,2) with routine=0 (no movement)</li>
     *   <li>Animation $FC command increments routine to 2 — enables gravity+movement</li>
     *   <li>Fall with MoveSprite2 + gravity, showing frame 3</li>
     *   <li>On floor hit or player touch: switch to splash animation (frames 4,5)</li>
     *   <li>Splash $FC increments routine to 4 — object deletes</li>
     * </ol>
     */
    private static class WaterDropChild extends AbstractObjectInstance
            implements TouchResponseProvider, TouchResponseListener, SpawnRewindRecreatable {

        // State machine values matching ROM routine field
        private static final int STATE_FORMING = 0;   // routine = 0: animating, no movement
        private static final int STATE_FALLING = 2;    // routine = 2: movement + gravity active
        private static final int STATE_SPLASHING = 3;  // code pointer = loc_38336: animate only
        private static final int STATE_DELETE = 4;      // routine = 4: delete
        private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                true,
                true,
                false,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        private int x;
        private int y;
        private final SubpixelMotion.State motion;

        private int state = STATE_FORMING;
        private int collisionFlags = COLLISION_FLAGS;

        // Animation state
        private int animId;          // 0 = forming, 1 = splash
        private int animFrameIndex;  // index into current animation's frame array
        private int animTimer;       // counts down from ANIM_SPEED
        private int mappingFrame;    // current frame to render

        WaterDropChild(ObjectSpawn spawn) {
            super(spawn, "HCZWaterDropChild");
            this.x = spawn.x();
            this.y = spawn.y();
            this.motion = new SubpixelMotion.State(x, y, 0, 0, 0, 0);
            // ROM: anim_frame_timer=0 (copied from parent); first subq produces borrow,
            // so animation advances immediately on the first frame.
            this.animTimer = 0;
            this.mappingFrame = ANIM_0_FRAMES[0];
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (isDestroyed()) return;

            // State: falling (routine != 0) — apply movement + gravity + floor check
            // ROM: loc_382FC — tst.b routine(a0) / beq.s loc_38336
            if (state == STATE_FALLING) {
                // ROM: MoveSprite2 — apply velocity without gravity built in
                SubpixelMotion.moveSprite2(motion);
                // ROM: addi.w #8,y_vel(a0)
                motion.yVel += GRAVITY;
                x = motion.x;
                y = motion.y;

                // ROM: ObjCheckFloorDist / tst.w d1 / bpl.s skip
                TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, Y_RADIUS);
                if (floor.distance() < 0) {
                    // ROM: add.w d1,y_pos(a0) / clr.w y_vel(a0)
                    y += floor.distance();
                    motion.y = y;
                    motion.yVel = 0;
                    // ROM: move.w #(1<<8)|1,anim(a0) — set anim=1, prev_anim=1
                    // ROM: move.b #1,anim_frame_timer(a0) — timer=1, not immediate
                    startSplashAnimation(1);
                }
            }

            // Animate (all states run animation)
            // ROM: loc_38336 — Animate_Sprite + routine check
            updateAnimation();

            // ROM: cmpi.b #4,routine(a0) / bne.s continue
            if (state == STATE_DELETE) {
                setDestroyed(true);
                return;
            }
        }

        /**
         * Animate_Sprite equivalent for the two animation scripts.
         * Handles the $FC (increment routine by 2) and $FE (loop) commands.
         */
        private void updateAnimation() {
            animTimer--;
            if (animTimer >= 0) return;
            animTimer = ANIM_SPEED;

            if (animId == 0) {
                // Animation 0: forming drip
                if (animFrameIndex < ANIM_0_FRAMES.length) {
                    mappingFrame = ANIM_0_FRAMES[animFrameIndex];
                    animFrameIndex++;
                } else if (state == STATE_FORMING) {
                    // $FC command: increment routine by 2 (0 -> 2)
                    state = STATE_FALLING;
                    // Continue with frame 3 (falling frame, loops via $FE 1)
                    mappingFrame = ANIM_0_FALL_FRAME;
                }
                // Once in FALLING state, mappingFrame stays at 3 ($FE 1 loop)
            } else {
                // Animation 1: splash
                if (animFrameIndex < ANIM_1_FRAMES.length) {
                    mappingFrame = ANIM_1_FRAMES[animFrameIndex];
                    animFrameIndex++;
                } else {
                    // $FC command: increment routine by 2 (2 -> 4)
                    state = STATE_DELETE;
                }
            }
        }

        /**
         * Transitions to splash animation.
         *
         * @param initialTimer timer value: 1 for floor-hit path (ROM: move.b #1,anim_frame_timer),
         *                     0 for touch-response path (ROM: Animate_Sprite resets on anim change)
         */
        private void startSplashAnimation(int initialTimer) {
            state = STATE_SPLASHING;
            animId = 1;
            animFrameIndex = 0;
            animTimer = initialTimer;
            collisionFlags = 0;
        }

        // ===== Touch Response (collision_flags = $C7 = Special + size 7) =====

        @Override
        public int getCollisionFlags() {
            return collisionFlags;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile() {
            return TOUCH_RESPONSE_PROFILE;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return TOUCH_RESPONSE_PROFILE;
        }

        @Override
        public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
            if (state == STATE_DELETE || state == STATE_SPLASHING) return;
            if (collisionFlags == 0) return;

            // ROM: sub_38382 (sonic3k.asm:75229-75239)
            // If player's anim is 5 (push), reset prev_anim to force restart
            if (player.getAnimationId() == PUSH_ANIM_ID) {
                player.forceAnimationRestart();
            }

            // ROM: set anim to 1, clear collision, set routine to 2
            // Touch path: Animate_Sprite detects anim change, resets timer to 0
            startSplashAnimation(0);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) return;
            if (!isOnScreen(WIDTH_PIXELS)) return;

            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer == null) return;

            renderer.drawFrameIndex(mappingFrame, x, y, false, false);
        }

        @Override
        public void appendDebugRenderCommands(DebugRenderContext ctx) {
            if (ctx == null) return;
            ctx.drawRect(x, y, X_RADIUS, Y_RADIUS,
                    0.2f, 0.8f, 1.0f);
        }

        @Override
        public int getX() { return x; }

        @Override
        public int getY() { return y; }

        @Override
        public ObjectSpawn getSpawn() { return buildSpawnAt(x, y); }
    }
}
