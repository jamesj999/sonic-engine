package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;
import java.util.logging.Logger;

/**
 * HCZ end boss blade water chute child (ROM: loc_6B4C4).
 *
 * <p>When a propeller blade hits the floor and explodes ({@code loc_6B73A}),
 * five of these children are spawned via {@code ChildObjDat_6BDE0} /
 * {@code CreateChild6_Simple}. Each child is positioned at a different Y
 * offset above the water surface, together forming a vertical water column
 * that can launch the player upward.
 *
 * <h3>ROM lifecycle:</h3>
 * <ol>
 *   <li><b>Init (loc_6B4C4):</b> {@code SetUp_ObjAttributes} with
 *       {@code ObjDat3_6BD5A} (Map_HCZEndBoss, initial frame 8).
 *       Calls {@code sub_6B96C} to position at water level + Y offset
 *       from {@code word_6B98E} (subtypes 0/2/4/6/8 map to -8/-0x18/-0x28/
 *       -0x38/-0x48 from water). Animation pointer set from {@code off_6B998}
 *       (per-subtype animation script). Wait timer {@code $2E} = subtype
 *       value. Subtype 0 also spawns bubble children (omitted).</li>
 *   <li><b>Wait (Obj_Wait):</b> Count down {@code $2E} frames. On expiry,
 *       callback {@code loc_6B4F2} transitions to active phase.</li>
 *   <li><b>Active (loc_6B502):</b> Each frame: call {@code sub_6BB40}
 *       (player launch check), then {@code AnimateRaw_DrawTouch}.</li>
 *   <li><b>Delete:</b> When animation reaches {@code $F4} end command,
 *       callback {@code Go_Delete_Sprite_2} destroys the object.</li>
 * </ol>
 *
 * <h3>Player launch (sub_6BB40 / sub_6BB5C):</h3>
 * <p>Bounding box check using {@code word_6BB92}: x_offset=-0x0C,
 * x_range=0x18 (so ±12 px), y_offset=-0x38, y_range=0x40 (so 0x38 above
 * to 0x08 below). If player is inside AND not object-controlled, sets
 * {@code y_vel = -$800} on the player (strong upward launch).
 *
 * <p>The check runs until {@code anim_frame >= $30} (frame 48), after
 * which the chute is dissipating and no longer launches.
 *
 * <h3>Animation scripts ({@code off_6B998}, Animate_RawMultiDelay format):</h3>
 * <p>Each subtype has a different-length animation script. Higher subtypes
 * (further from water) have shorter sequences since they appear and
 * dissipate faster. The scripts are stored as alternating
 * {@code mapping_frame, delay} bytes and end with {@code $F4} (end+callback).
 * The scripts use Map_HCZEndBoss frames showing water column segments.
 */
public class HczEndBossBladeWaterChute extends AbstractBossChild implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(HczEndBossBladeWaterChute.class.getName());

    // =========================================================================
    // Player launch box (ROM: word_6BB92)
    //   x_offset=-0x0C, x_range=0x18, y_offset=-0x38, y_range=0x40
    // =========================================================================
    private static final int LAUNCH_X_OFFSET = -0x0C;
    private static final int LAUNCH_X_RANGE  =  0x18;
    private static final int LAUNCH_Y_OFFSET = -0x38;
    private static final int LAUNCH_Y_RANGE  =  0x40;

    /** Player y_vel set on launch (ROM: move.w #-$800,y_vel(a2)). */
    private static final int LAUNCH_Y_VEL = -0x800;

    /** Animation frame index threshold for disabling launch (ROM: cmpi.b #$30). */
    private static final int LAUNCH_FRAME_THRESHOLD = 0x30;

    // =========================================================================
    // Y offsets from water level per subtype (ROM: word_6B98E)
    //   subtype 0 = -8, 2 = -0x18, 4 = -0x28, 6 = -0x38, 8 = -0x48
    // =========================================================================
    private static final int[] Y_OFFSETS = { -8, -0x18, -0x28, -0x38, -0x48 };

    // =========================================================================
    // Animation scripts (ROM: off_6B998 → byte_6BE3F..byte_6BEEB)
    // Format: Animate_RawMultiDelay (mapping_frame, delay pairs; $F4 = end)
    // =========================================================================
    private static final int[][] ANIM_SCRIPTS = {
        // byte_6BE3F (subtype 0, closest to water — longest)
        { 8,  0, 8, 0, 0x1B, 0, 9, 0, 0x1C, 0, 0x0A, 0, 0x1D, 0, 0x0A, 0,
          0x1D, 0, 0x0A, 0, 0x1D, 0, 0x0A, 0, 0x1D, 0, 0x0A, 0, 0x1D, 0,
          0x0A, 0, 0x1D, 0, 0x0A, 0, 0x1D, 0, 0x0A, 0, 0x1D, 0, 0x0B, 2,
          0x1E, 2, 0x0C, 3, 0x1F, 3 },
        // byte_6BE76 (subtype 2)
        { 8,  0, 8, 0, 0x1B, 0, 9, 0, 0x1C, 0, 0x0A, 0, 0x1D, 0, 0x0A, 0,
          0x1D, 0, 0x0A, 0, 0x1D, 0, 0x0A, 0, 0x1D, 0, 0x0A, 0, 0x1D, 0,
          0x0A, 0, 0x1D, 0, 0x0B, 2, 0x1E, 2, 0x0C, 3, 0x1F, 3 },
        // byte_6BEA5 (subtype 4)
        { 8,  0, 8, 0, 0x1B, 0, 9, 0, 0x1C, 0, 0x0A, 0, 0x1D, 0, 0x0A, 0,
          0x1D, 0, 0x0A, 0, 0x1D, 0, 0x0A, 0, 0x1D, 0, 0x0B, 2, 0x1E, 2,
          0x0C, 3, 0x1F, 3 },
        // byte_6BECC (subtype 6)
        { 8,  0, 8, 0, 0x1B, 0, 9, 0, 0x1C, 0, 0x0A, 0, 0x1D, 0, 0x0A, 0,
          0x1D, 0, 0x0A, 0, 0x1D, 0, 0x0B, 2, 0x1E, 2, 0x0C, 3, 0x1F, 3 },
        // byte_6BEEB (subtype 8, furthest from water — shortest)
        { 8,  0, 8, 0, 0x1B, 0, 9, 0, 0x1C, 0, 0x0A, 0, 0x1D, 0, 0x0B, 2,
          0x1E, 2, 0x0C, 3, 0x1F, 3 },
    };

    // =========================================================================
    // State machine
    // =========================================================================
    private static final int STATE_WAIT   = 0;
    private static final int STATE_ACTIVE = 1;

    // =========================================================================
    // Instance state
    // =========================================================================
    private final HczEndBossInstance boss;
    // Non-final so the generic field capturer reapplies it after a rewind
    // recreate through the HCZ end-boss restore path.
    private int slotIndex;               // 0-4 (subtype / 2)

    private int state;
    private int waitTimer;               // staggered start delay
    private boolean initialized;

    // Animate_RawMultiDelay emulation
    private int animFrameIndex;          // ROM anim_frame byte offset into animScript
    private int animFrameTimer;          // ticks remaining for current frame
    private int mappingFrame;            // current mapping frame being displayed
    private boolean animComplete;        // true when $F4 end reached

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * @param boss      Parent boss instance (for spawnDynamicChild and defeat signal).
     * @param bladeX    World X where the blade exploded (column centered here).
     * @param slotIndex 0-4 corresponding to subtypes 0/2/4/6/8.
     */
    public HczEndBossBladeWaterChute(HczEndBossInstance boss, int bladeX, int slotIndex) {
        super(boss, "HCZEndBossBladeWaterChute[" + slotIndex + "]", 3, 0);
        this.boss = boss;
        this.slotIndex = Math.min(Math.max(slotIndex, 0), 4);

        // Position: blade's X, water level + Y offset
        this.currentX = bladeX;
        int waterY = getWaterLevelY();
        this.currentY = waterY + Y_OFFSETS[this.slotIndex];

        // ROM: $2E = subtype (wait timer for stagger: 0, 2, 4, 6, 8 frames)
        this.waitTimer = this.slotIndex * 2;
        this.state = STATE_WAIT;

        // Init animation to the SetUp_ObjAttributes mapping frame.
        // Animate_RawMultiDelay starts with anim_frame/anim_frame_timer at 0,
        // so the first active frame advances to byte offset 2 immediately.
        this.animFrameIndex = 0;
        this.animFrameTimer = 0;
        // ROM: SetUp_ObjAttributes with initial mapping_frame = 8
        this.mappingFrame = 8;
        this.animComplete = false;

        updateDynamicSpawn();
    }

    private HczEndBossBladeWaterChute(ObjectSpawn spawn, HczEndBossInstance boss, int ignored) {
        this(boss, spawn.x(), 0);
    }

    @Override
    public HczEndBossBladeWaterChute recreateForRewind(RewindRecreateContext ctx) {
        HczEndBossInstance restoredBoss = HczEndBossRewindLinks.nearestBoss(ctx);
        if (restoredBoss == null || ctx.spawn() == null) {
            return null;
        }
        return ObjectConstructionContext.construct(
                ctx.objectServices(),
                () -> new HczEndBossBladeWaterChute(restoredBoss, ctx.spawn().x(), 0));
    }

    // =========================================================================
    // Main update
    // =========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!beginUpdate(vIntRunCount)) {
            return;
        }

        if (boss.isDefeatSignal()) {
            setDestroyed(true);
            return;
        }

        if (animComplete) {
            setDestroyed(true);
            return;
        }

        // loc_6B4C4 only performs SetUp_ObjAttributes/sub_6B96C and installs
        // Obj_Wait. That wait routine cannot dispatch until the child's next
        // SST pass, even when subtype 0 gives it a zero timer.
        if (!initialized) {
            initialized = true;
            return;
        }

        switch (state) {
            case STATE_WAIT -> {
                // ROM: Obj_Wait — count down stagger delay
                waitTimer--;
                if (waitTimer < 0) {
                    state = STATE_ACTIVE;
                }
            }
            case STATE_ACTIVE -> {
                // ROM loc_6B502: sub_6BB40 (player launch) + AnimateRaw_DrawTouch
                checkPlayerLaunch(player);
                tickAnimation();
            }
        }
    }

    // =========================================================================
    // Player launch check (ROM: sub_6BB40 / sub_6BB5C)
    // =========================================================================

    /**
     * ROM sub_6BB40: checks both players against the launch bounding box.
     * If a player is inside AND not object-controlled, sets y_vel = -$800.
     *
     * <p>ROM: cmpi.b #$30,anim_frame(a0); bhs locret — disabled when
     * animation has progressed past frame index 0x30 (chute is dissipating).
     */
    private void checkPlayerLaunch(PlayableEntity player) {
        // ROM: cmpi.b #$30,anim_frame(a0); bhs.s locret
        if (animFrameIndex >= LAUNCH_FRAME_THRESHOLD) {
            return;
        }

        try {
            ObjectPlayerQuery query = services().playerQuery();
            ObjectPlayerQuery participants = new ObjectPlayerQuery(() -> player, query::sidekicks);
            for (PlayableEntity candidate : participants.playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
                tryLaunchPlayer(candidate);
            }
        } catch (Exception e) {
            tryLaunchPlayer(player);
        }
    }

    /**
     * ROM sub_6BB5C: single-player launch check.
     * Bounding box: word_6BB92 = {-$0C, $18, -$38, $40}.
     *
     * <pre>
     *   if object_control(player) != 0: skip
     *   x_check = obj_x + (-$0C) = obj_x - 12
     *   if player_x < x_check: skip
     *   x_check += $18  → obj_x + 12
     *   if player_x >= x_check: skip
     *   y_check = obj_y + (-$38) = obj_y - 56
     *   if player_y < y_check: skip
     *   y_check += $40  → obj_y + 8
     *   if player_y >= y_check: skip
     *   player.y_vel = -$800
     * </pre>
     */
    private void tryLaunchPlayer(PlayableEntity player) {
        if (player == null || player.getDead()) {
            return;
        }
        // ROM: tst.b object_control(a2); bne.s locret
        if (player.isObjectControlled()) {
            return;
        }

        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // X range check
        int xCheck = currentX + LAUNCH_X_OFFSET;
        if (playerX < xCheck) {
            return;
        }
        xCheck += LAUNCH_X_RANGE;
        if (playerX >= xCheck) {
            return;
        }

        // Y range check
        int yCheck = currentY + LAUNCH_Y_OFFSET;
        if (playerY < yCheck) {
            return;
        }
        yCheck += LAUNCH_Y_RANGE;
        if (playerY >= yCheck) {
            return;
        }

        // Launch! ROM: move.w #-$800,y_vel(a2)
        player.setYSpeed((short) LAUNCH_Y_VEL);
    }

    // =========================================================================
    // Animation (Animate_RawMultiDelay emulation)
    // =========================================================================

    /**
     * Emulates Animate_RawMultiDelay: decrements the current timer each frame.
     * When it expires, advances ROM {@code anim_frame} by 2 and reads the next
     * {@code mapping_frame, delay} pair from the script. A negative byte marks
     * a command such as {@code $F4} end.
     */
    private void tickAnimation() {
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            return;
        }

        int[] animScript = animScript();
        int nextFrameIndex = animFrameIndex + 2;
        if (nextFrameIndex >= animScript.length) {
            animComplete = true;
            return;
        }

        int frameValue = animScript[nextFrameIndex];
        if (frameValue >= 0x80) {
            animComplete = true;
            return;
        }

        animFrameIndex = nextFrameIndex;
        mappingFrame = frameValue;
        animFrameTimer = animScript[nextFrameIndex + 1];
    }

    /**
     * Animation data for this chute's slot. Derived from {@link #slotIndex} (not
     * cached in a field) so it stays consistent after a rewind recreate reapplies
     * the captured {@code slotIndex}.
     */
    private int[] animScript() {
        return ANIM_SCRIPTS[slotIndex];
    }

    // =========================================================================
    // Water level access
    // =========================================================================

    private int getWaterLevelY() {
        try {
            WaterSystem ws = services().waterSystem();
            if (ws == null) {
                return 0x1000;
            }
            int zoneId = services().featureZoneId();
            int actId  = services().featureActId();
            if (ws.hasWater(zoneId, actId)) {
                return ws.getWaterLevelY(zoneId, actId);
            }
        } catch (Exception e) {
            LOG.fine(() -> "HczEndBossBladeWaterChute.getWaterLevelY: " + e.getMessage());
        }
        return 0x1000;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || state == STATE_WAIT) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }
}
