package com.openggf.game.sonic3k.objects;

import com.openggf.game.BonusStageType;
import com.openggf.game.PlayableEntity;
import com.openggf.game.CheckpointState;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Sonic 3&K StarPost/Checkpoint (Object 0x34).
 * <p>
 * Based on disassembly: Obj_StarPost (sonic3k.asm line 61554).
 * <p>
 * Init (routine 0, loc_2CFC0):
 * <ul>
 *   <li>Maps: Map_StarPost, art_tile: make_art_tile(ArtTile_StarPost+8,0,0)</li>
 *   <li>width_pixels: 8, height_pixels: 0x28 (40), priority: 0x280</li>
 *   <li>Checks respawn table bit 0 - if set, starpost already activated</li>
 *   <li>Compares subtype &amp; 0x7F against Last_star_post_hit &amp; 0x7F</li>
 * </ul>
 * <p>
 * Main (routine 2, sub_2D028) - collision check:
 * <ul>
 *   <li>X range: dx + 8 &lt; 0x10 (abs(dx) &lt; 8)</li>
 *   <li>Y range: dy + 0x40 &lt; 0x68</li>
 *   <li>On collision: plays sfx_Starpost, saves checkpoint</li>
 *   <li>Spawns orbiting star child at starpost Y - 0x14</li>
 *   <li>If 20+ rings: spawns 4 bonus stars (S3K uses 20, NOT 50 like S2)</li>
 * </ul>
 * <p>
 * Animation (Ani_Starpost):
 * <ul>
 *   <li>Anim 0: frame 0, delay 15 (idle - red ball)</li>
 *   <li>Anim 1: frame 1, delay 15 (activated - no ball while star orbits)</li>
 *   <li>Anim 2: frames 0, 4, delay 3 (spinning cycle - blinking)</li>
 * </ul>
 * <p>
 * Mapping frames (Map - Starpost.asm):
 * <ul>
 *   <li>Frame 0: Pole + red ball (idle)</li>
 *   <li>Frame 1: Pole only (no ball - used while star child is orbiting)</li>
 *   <li>Frame 2: Star ball alone (dongle/star child frame)</li>
 *   <li>Frame 3: Head alone</li>
 *   <li>Frame 4: Pole + blue ball (active/visited)</li>
 * </ul>
 */
public class Sonic3kStarPostObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kStarPostObjectInstance.class.getName());

    // Activation zone dimensions (ROM: dx + 8 < $10, dy + $40 < $68)
    private static final int ACTIVATION_HALF_WIDTH = 8;
    private static final int ACTIVATION_Y_ABOVE = 0x40;
    private static final int ACTIVATION_Y_RANGE = 0x68;

    // S3K uses 20 rings for bonus stars (NOT 50 like S2)
    // ROM: cmpi.w #20,(Ring_count).w (line 61635)
    private static final int BONUS_STAR_RING_THRESHOLD = 20;

    /**
     * Bonus star variant determined by ring count at checkpoint activation.
     * ROM: loc_2D436 (sonic3k.asm lines 61856-61881).
     * <p>
     * Formula: {@code remainder = ((rings - 20) / 15) % divisor}
     * where divisor=3 for S3K (locked-on) and 2 for S&K standalone.
     * <p>
     * Each variant maps to a different bonus stage and loads different star art:
     * <ul>
     *   <li>RED (Stars3, remainder=0) → Slot Machine ($1500)</li>
     *   <li>BLUE (Stars1, remainder=1) → Glowing Spheres ($1400)</li>
     *   <li>YELLOW (Stars2, remainder=2) → Gumball Machine ($1300, S3K only)</li>
     * </ul>
     */
    public enum BonusStarVariant {
        YELLOW(1.0f, 1.0f, 0.3f, BonusStageType.GUMBALL,
                ObjectArtKeys.CHECKPOINT_STAR_YELLOW,
                Sonic3kConstants.ART_KOSM_STARPOST_STARS2_ADDR),
        BLUE(0.3f, 0.5f, 1.0f, BonusStageType.GLOWING_SPHERE,
                ObjectArtKeys.CHECKPOINT_STAR_BLUE,
                Sonic3kConstants.ART_KOSM_STARPOST_STARS1_ADDR),
        RED(1.0f, 0.3f, 0.3f, BonusStageType.SLOT_MACHINE,
                ObjectArtKeys.CHECKPOINT_STAR_RED,
                Sonic3kConstants.ART_KOSM_STARPOST_STARS3_ADDR);

        public final float r, g, b;
        public final BonusStageType bonusStageType;
        public final String artKey;
        public final int artSourceAddress;

        BonusStarVariant(
                float r,
                float g,
                float b,
                BonusStageType bonusStageType,
                String artKey,
                int artSourceAddress) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.bonusStageType = bonusStageType;
            this.artKey = artKey;
            this.artSourceAddress = artSourceAddress;
        }
    }

    /**
     * Computes the bonus star variant from ring count using the ROM formula.
     * ROM: loc_2D436 (sonic3k.asm lines 61857-61877).
     * <pre>
     * subi.w #20,d0      ; rings - 20
     * divu.w #15,d0      ; / 15
     * ext.l  d0          ; clear remainder
     * moveq  #3,d2       ; divisor (2 for SK alone, 3 for S3K)
     * divu.w d2,d0       ; / divisor
     * swap   d0          ; remainder
     * </pre>
     */
    static BonusStarVariant computeBonusStarVariant(int ringCount) {
        // ROM formula: remainder = ((rings - 20) / 15) % divisor
        // S3K locked-on: divisor=3 (all 3 bonus stages available)
        // ROM loc_2D47E dispatch (sonic3k.asm lines 61886-61912):
        //   remainder 0 -> SLOTS ($1500)
        //   remainder 1 -> PACHINKO / GLOWING_SPHERE ($1400)
        //   remainder 2 -> GUMBALL ($1300)
        int quotient = (ringCount - 20) / 15;
        int remainder = quotient % 3;
        return switch (remainder) {
            case 0 -> BonusStarVariant.RED;       // SLOT_MACHINE
            case 1 -> BonusStarVariant.BLUE;      // GLOWING_SPHERE
            case 2 -> BonusStarVariant.YELLOW;    // GUMBALL
            default -> BonusStarVariant.YELLOW;
        };
    }

    // Animation IDs matching Ani_Starpost
    private static final int ANIM_IDLE = 0;     // Frame 0 (red ball), delay 15, loop
    private static final int ANIM_NO_BALL = 1;  // Frame 1 (no ball), delay 15, loop
    private static final int ANIM_SPINNING = 2; // Frames 0, 4 alternating, delay 3

    // Mapping frames from Map - Starpost.asm
    private static final int FRAME_RED_BALL = 0;  // Pole + red ball
    private static final int FRAME_NO_BALL = 1;   // Pole only
    private static final int FRAME_STAR = 2;      // Star ball alone (child frame)
    private static final int FRAME_HEAD = 3;      // Head alone
    private static final int FRAME_BLUE_BALL = 4; // Pole + blue ball

    private int checkpointIndex;
    private boolean cameraLockFlag;
    private int animId;
    private int mappingFrame;
    private int animTimer;
    private int animFrameIndex;
    private boolean activated;
    private boolean starActive;
    private boolean initialized;

    public Sonic3kStarPostObjectInstance(ObjectSpawn spawn) {
        super(spawn, "StarPost");
        this.checkpointIndex = spawn.subtype() & 0x7F;
        this.cameraLockFlag = (spawn.subtype() & 0x80) != 0;

        this.animId = ANIM_IDLE;
        this.mappingFrame = FRAME_RED_BALL;
        this.animTimer = 0;
        this.animFrameIndex = 0;
        this.starActive = false;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        // Init routine (loc_2CFC0): a star post is already-activated when its
        // respawn bit is set (btst #0,(a2), sonic3k.asm:61582) OR
        // Last_star_post_hit >= subtype (:61588). The engine folds both into the
        // persistent activation MARK (getStarPostActivationMark), which equals the
        // checkpoint index in normal play but survives a bonus entry's zeroing of
        // Last_star_post_hit -- so a return-from-bonus star post the player is
        // repositioned onto stays used instead of re-triggering.
        var checkpointState = services().checkpointState();
        if (checkpointState != null && checkpointState.getStarPostActivationMark() >= this.checkpointIndex) {
            // loc_2D008: already activated - set anim 2 (spinning)
            this.activated = true;
            this.animId = ANIM_SPINNING;
            this.mappingFrame = FRAME_RED_BALL;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ensureInitialized();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // loc_2D012: main routine - check collision if not activated
        if (!activated && player != null) {
            checkActivation(player);
        }
        // loc_2D0F8: animate
        updateAnimation();
    }

    /**
     * sub_2D028: Check if player is within activation zone.
     * ROM lines 61601-61657.
     */
    private void checkActivation(AbstractPlayableSprite player) {
        var checkpointState = services().checkpointState();
        if (checkpointState == null) {
            return;
        }

        // ROM: cmp.b d2,d1 / bhs.w loc_2D0EA
        // If already activated (persistent mark >= this subtype; see ensureInitialized),
        // set to spinning if not already -- never re-run the touch/save.
        if (checkpointState.getStarPostActivationMark() >= this.checkpointIndex) {
            // loc_2D0EA: tst.b anim(a0) / bne.s locret / move.b #2,anim(a0)
            if (!activated) {
                activated = true;
                if (animId == ANIM_IDLE) {
                    animId = ANIM_SPINNING;
                }
            }
            return;
        }

        // ROM collision check: player center vs starpost position
        int px = player.getCentreX();
        int py = player.getCentreY();
        int cx = spawn.x();
        int cy = spawn.y();

        int dx = px - cx;
        int dy = py - cy;

        // ROM: addi.w #8,d0 / cmpi.w #$10,d0 / bhs.w locret
        if (dx + ACTIVATION_HALF_WIDTH < 0 || dx + ACTIVATION_HALF_WIDTH >= 16) {
            return;
        }
        // ROM: addi.w #$40,d0 / cmpi.w #$68,d0 / bhs.w locret
        if (dy + ACTIVATION_Y_ABOVE < 0 || dy + ACTIVATION_Y_ABOVE >= ACTIVATION_Y_RANGE) {
            return;
        }

        // Activate!
        if (checkpointState instanceof CheckpointState cs) {
            activate(player, cs);
        }
    }

    /**
     * Activation logic from sub_2D028 (lines 61617-61644).
     * <p>
     * ROM sequence:
     * 1. Play sfx_Starpost
     * 2. Allocate star child object (routine 6)
     * 3. If rings >= 20: spawn bonus stars (sub_2D3C8)
     * 4. Set anim to 1 (no ball)
     * 5. Call sub_2D164 (save checkpoint data)
     * 6. Set routine to 4 (terminal)
     * 7. Set respawn bit
     */
    private void activate(AbstractPlayableSprite player, CheckpointState checkpointState) {
        activated = true;
        starActive = true;
        // 1. ROM: moveq #signextendB(sfx_Starpost),d0 / jsr (Play_SFX).l
        try {
            services().playSfx(Sonic3kSfx.STARPOST.id);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }

        // 2. Spawn orbiting star child (routine 6, loc_2D10A/loc_2D12E)
        //    ROM: AllocateObject / move.b #6,routine(a1) / ...
        spawnStarChild();

        // 3. ROM: cmpi.w #20,(Ring_count).w / blo.s loc_2D0D0 / bsr.w sub_2D3C8
        if (shouldSpawnBonusStars(player)) {
            spawnBonusStars(player.getRingCount());
        }

        // 4. ROM loc_2D0D0: move.b #1,anim(a0)
        animId = ANIM_NO_BALL;
        mappingFrame = FRAME_NO_BALL;

        // 5. ROM: bsr.w sub_2D164 - save checkpoint data
        checkpointState.saveCheckpoint(checkpointIndex, spawn.x(), spawn.y(), cameraLockFlag);

        // 6. ROM: move.b #4,routine(a0) - terminal state (handled by activated=true)
        // 7. ROM: bset #0,(a2) - set respawn bit (handled by CheckpointState)

        LOGGER.fine("S3K StarPost " + checkpointIndex + " activated at (" + spawn.x() + ", " + spawn.y() + ")");
    }

    /**
     * Spawns the orbiting star child (ROM routine 6).
     * ROM: AllocateObject, set routine=6, center at (x, y-0x14), mapping_frame=2, lifetime=0x20.
     */
    private void spawnStarChild() {
        if (services().objectManager() != null) {
            spawnChild(() -> new Sonic3kStarPostStarChild(this));
        }
    }

    /**
     * ROM: cmpi.w #20,(Ring_count).w / blo.s (line 61635)
     * S3K requires 20 rings (not 50 like S2).
     */
    private boolean shouldSpawnBonusStars(AbstractPlayableSprite player) {
        int rings = player.getRingCount();
        return rings >= BONUS_STAR_RING_THRESHOLD;
    }

    /**
     * Spawns 4 bonus stars at angle offsets 0, 0x40, 0x80, 0xC0.
     * ROM: sub_2D3C8 (lines 61828-61881).
     * Star art variant is determined by ring count formula (loc_2D436).
     */
    private void spawnBonusStars(int ringCount) {
        if (services().objectManager() == null) {
            return;
        }
        BonusStarVariant variant = computeBonusStarVariant(ringCount);
        LOGGER.fine("S3K bonus stars: rings=" + ringCount + " → variant=" + variant
                + " (" + variant.bonusStageType + ")");
        // ROM: moveq #4-1,d1 / moveq #0,d2 / ... / addi.w #$40,d2 / dbf d1,...
        for (int i = 0; i < 4; i++) {
            int angleOffset = i * 0x40;
            spawnChild(() -> new Sonic3kStarPostBonusStarChild(this, angleOffset, variant));
        }
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null
                && renderManager.getArtProvider()
                instanceof Sonic3kObjectArtProvider provider) {
            provider.queueStarPostBonusArt(variant.artSourceAddress);
        }
    }

    /**
     * Marks this starpost as having been used for special stage entry.
     * Prevents bonus stars from respawning when returning from special stage.
     */
    public void markUsedForSpecialStage() {
        var checkpointState = services().checkpointState();
        if (checkpointState instanceof CheckpointState cs) {
            cs.markUsedForSpecialStage();
        }
        LOGGER.fine("S3K StarPost " + checkpointIndex + " marked as used for special stage entry");
    }

    /**
     * Called by the star child when its orbit expires.
     * ROM: loc_2D10A - move.b #2,anim(a1) / move.b #0,mapping_frame(a1)
     * Switch to spinning animation (anim 2: frames 0 and 4 alternating).
     */
    public void onStarComplete() {
        starActive = false;
        animId = ANIM_SPINNING;
        animFrameIndex = 0;
        mappingFrame = FRAME_RED_BALL;
    }

    /**
     * Update animation based on Ani_Starpost scripts.
     * <p>
     * Anim - Starpost.asm:
     * <ul>
     *   <li>byte_2D33E: $F, 0, $FF  (anim 0: frame 0, delay 15, loop)</li>
     *   <li>byte_2D341: $F, 1, $FF  (anim 1: frame 1, delay 15, loop)</li>
     *   <li>byte_2D344: 3, 0, 4, $FF  (anim 2: delay 3, frames 0,4, loop)</li>
     * </ul>
     */
    private void updateAnimation() {
        if (animTimer > 0) {
            animTimer--;
            return;
        }

        switch (animId) {
            case ANIM_IDLE:
                // Script: $F, 0, $FF - frame 0, delay 15, loop
                mappingFrame = FRAME_RED_BALL;
                animTimer = 15;
                break;
            case ANIM_NO_BALL:
                // Script: $F, 1, $FF - frame 1, delay 15, loop
                mappingFrame = FRAME_NO_BALL;
                animTimer = 15;
                break;
            case ANIM_SPINNING:
                // Script: 3, 0, 4, $FF - delay 3, alternate frames 0 and 4
                animFrameIndex = (animFrameIndex + 1) % 2;
                mappingFrame = (animFrameIndex == 0) ? FRAME_RED_BALL : FRAME_BLUE_BALL;
                animTimer = 3;
                break;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            appendFallbackBox(commands);
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getCheckpointRenderer();
        if (renderer == null || !renderer.isReady()) {
            appendFallbackBox(commands);
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }

    /**
     * Fallback debug wireframe rendering when no art is loaded.
     */
    private void appendFallbackBox(List<GLCommand> commands) {
        int cx = spawn.x();
        int cy = spawn.y();
        int hw = 8;
        int hh = 0x28;
        float r = activated ? 0.3f : 0.8f;
        float g = activated ? 0.3f : 0.2f;
        float b = activated ? 0.8f : 0.2f;

        // Draw wireframe rectangle
        appendLine(commands, cx - hw, cy - hh, cx + hw, cy - hh, r, g, b);
        appendLine(commands, cx + hw, cy - hh, cx + hw, cy + hh, r, g, b);
        appendLine(commands, cx + hw, cy + hh, cx - hw, cy + hh, r, g, b);
        appendLine(commands, cx - hw, cy + hh, cx - hw, cy - hh, r, g, b);

        // Draw cross
        appendLine(commands, cx - 4, cy, cx + 4, cy, r, g, b);
        appendLine(commands, cx, cy - 4, cx, cy + 4, r, g, b);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    public int getCheckpointIndex() {
        return checkpointIndex;
    }

    public boolean hasCameraLockFlag() {
        return cameraLockFlag;
    }

    public int getCenterX() {
        return spawn.x();
    }

    public int getCenterY() {
        return spawn.y();
    }

    @Override
    public int getPriorityBucket() {
        // ROM: move.w #$280,priority(a0) => priority bucket 5
        return RenderPriority.clamp(5);
    }
}
