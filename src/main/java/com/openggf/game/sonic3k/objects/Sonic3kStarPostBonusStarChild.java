package com.openggf.game.sonic3k.objects;

import com.openggf.game.CheckpointState;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.objects.Sonic3kStarPostObjectInstance.BonusStarVariant;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * S3K bonus star child - spirals around StarPost when player has 20+ rings.
 * <p>
 * ROM: sub_2D3C8 (sonic3k.asm line 61828) creates 4 star children,
 * loc_2D47E (routine 8) handles their orbital motion and collision.
 * <p>
 * Setup (sub_2D3C8):
 * <ul>
 *   <li>Maps: Map_StarpostStars, art_tile: make_art_tile(ArtTile_StarPost+8,0,0)</li>
 *   <li>routine = 8, render_flags = 4, width_pixels = 8</li>
 *   <li>Center position: parent (x, y - 0x30)</li>
 *   <li>mapping_frame = 1, x_vel = -$400, y_vel = 0</li>
 *   <li>$34 = angle offset (0, $40, $80, $C0 per star)</li>
 * </ul>
 * <p>
 * Motion (loc_2D50A):
 * <ul>
 *   <li>$34 increments by $A each frame</li>
 *   <li>Orbital position uses same spiral algorithm as S2</li>
 *   <li>Scale grows to $80, full size until $180, then shrinks to $200</li>
 *   <li>Collision enabled at $36 = $80 (collision_flags = $D8)</li>
 *   <li>Deleted at $36 > $200</li>
 * </ul>
 * <p>
 * Animation: anim_frame increments each frame.
 * mapping_frame = (anim_frame &amp; 6) >> 1; if 3 then 1.
 * Produces pattern: 0, 1, 2, 1, 0, 1, 2, 1, ...
 * <p>
 * Collision (loc_2D47E):
 * On player touch, determines bonus stage type based on ring count formula
 * and triggers special stage entry.
 */
public class Sonic3kStarPostBonusStarChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kStarPostBonusStarChild.class.getName());

    // ROM lifecycle constants
    private static final int COLLISION_START = 0x80;  // Enable collision at this lifetime
    private static final int SHRINK_START = 0x180;    // Start shrinking
    private static final int DELETE_AT = 0x200;       // Delete when lifetime reaches this
    private static final int ANGLE_INCREMENT = 0xA;   // addi.w #$A,$34(a0) (line 61932)

    // S3K ring threshold for bonus stars
    private static final int RING_THRESHOLD = 20;

    // ROM Touch_Sizes entry $18 (collision_flags $D8 & $3F), sonic3k.asm:20713+24
    private static final int TOUCH_HALF_WIDTH = 4;
    private static final int TOUCH_HALF_HEIGHT = 4;
    // ROM Touch_NoInstaShield player width $10 -> half-width 8, sonic3k.asm:20650
    private static final int PLAYER_TOUCH_HALF_WIDTH = 8;
    @RewindTransient(reason = "Structural parent link; relinked to the nearest live "
            + "S3K starpost on rewind recreate. Scalar bonus-star state, including "
            + "variant, is reapplied by the generic field capturer.")
    private final Sonic3kStarPostObjectInstance parentStarPost;
    // Non-final so the generic field capturer reapplies it after a rewind
    // recreate (the bonus-stage type is not derivable from the spawn/parent).
    private BonusStarVariant variant;
    private int centerX;  // $30
    private int centerY;  // $32
    private int angle;          // $34
    private int lifetime;       // $36
    private int animFrame;
    private int currentX;
    private int currentY;
    private int mappingFrame;
    private boolean collisionEnabled;

    public Sonic3kStarPostBonusStarChild(Sonic3kStarPostObjectInstance parent, int angleOffset,
                                         BonusStarVariant variant) {
        super(createDummySpawn(parent), "StarPostBonusStar");
        this.parentStarPost = parent;
        this.variant = variant;
        this.centerX = parent.getCenterX();
        // ROM: subi.w #$30,d0 (line 61844)
        this.centerY = parent.getCenterY() - 0x30;
        this.angle = angleOffset;  // 0, 0x40, 0x80, or 0xC0
        this.lifetime = 0;
        this.animFrame = 0;
        this.mappingFrame = 1;  // ROM: move.b #1,mapping_frame(a1) (line 61849)
        this.collisionEnabled = false;
        this.currentX = centerX;
        this.currentY = centerY;
    }

    Sonic3kStarPostBonusStarChild(Sonic3kStarPostObjectInstance parent) {
        this(parent, 0, BonusStarVariant.YELLOW);
    }

    private static ObjectSpawn createDummySpawn(Sonic3kStarPostObjectInstance parent) {
        return new ObjectSpawn(parent.getCenterX(), parent.getCenterY(), 0x34, 0, 0, false, 0);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        Sonic3kStarPostObjectInstance liveParent =
                Sonic3kStarPostStarChild.findNearestLiveParentForRewind(
                        ctx.objectServices().objectManager(), ctx.spawn());
        return liveParent == null
                ? null
                : new Sonic3kStarPostBonusStarChild(liveParent, 0, BonusStarVariant.YELLOW);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Check if checkpoint was used for special stage entry already
        var checkpointState = services().checkpointState();
        if (checkpointState instanceof CheckpointState cs && cs.isUsedForSpecialStage()) {
            setDestroyed(true);
            return;
        }

        // ROM ORDER (loc_2D47E, sonic3k.asm:61891-61932): the object tests
        // collision_property FIRST and only then falls through to loc_2D50A's
        // motion. collision_property is written by the player's TouchResponse
        // (sonic3k.asm:20610, called from Obj_Sonic at :22022), which runs
        // earlier in the same Process_Sprites pass and therefore reads this
        // object's x_pos/y_pos as they stood at the END of the previous frame --
        // before this frame's loc_2D50A repositioning. Testing the overlap here,
        // ahead of updatePosition(), is what reproduces that one-frame ordering;
        // testing it after the move (as this object used to) fires the bonus
        // entry one LevelLoop iteration early.
        if (collisionEnabled && player != null && touchesPlayer(player)) {
            // ROM: loc_2D47E — S3K lampposts enter bonus stages via Restart_level_flag.
            // Engine: request bonus stage entry through LevelTransitionCoordinator.
            if (player.getRingCount() >= RING_THRESHOLD) {
                LOGGER.info("Requesting bonus stage entry: " + variant.bonusStageType
                        + " (variant=" + variant + ", rings=" + player.getRingCount() + ")");
                services().requestBonusStageEntry(variant.bonusStageType);
                if (parentStarPost != null) {
                    parentStarPost.markUsedForSpecialStage();
                }
                setDestroyed(true);
                return;
            }
        }

        // loc_2D50A: addi.w #$A,$34(a0)
        angle = (angle + ANGLE_INCREMENT) & 0xFFFF;

        // Calculate orbital position
        updatePosition();

        // addq.w #1,$36(a0)
        lifetime++;

        // Check collision enable: cmpi.w #$80,d1 / beq.s loc_2D574
        if (lifetime == COLLISION_START) {
            collisionEnabled = true;
            // ROM: move.b #$D8,collision_flags(a0)
        }

        // Check deletion: cmpi.w #$180,d1 / ble.s loc_2D58C
        if (lifetime > SHRINK_START) {
            int adjusted = -lifetime + DELETE_AT;
            if (adjusted < 0) {
                // loc_2D5C0: jmp (Delete_Current_Sprite).l
                setDestroyed(true);
                return;
            }
        }

        // Update animation
        updateAnimation();
    }

    /**
     * Orbital position calculation.
     * ROM: loc_2D50A through loc_2D58C (lines 61931-61997).
     * <p>
     * Algorithm:
     * 1. GetSineCosine on ($34 &amp; $FF)
     * 2. sin >>5 (d0), cos >>3 (d3)
     * 3. Complex bit manipulation for spiral effect using bits 5-9 of $34
     * 4. Scale by lifetime factor (growing/full/shrinking phases)
     * 5. Apply to center position
     */
    private void updatePosition() {
        int angleForCalc = angle & 0xFF;
        double radians = angleForCalc * Math.PI * 2 / 256.0;
        double sinVal = Math.sin(radians);
        double cosVal = Math.cos(radians);

        // ROM: asr.w #5,d0 (sin), asr.w #3,d1 (cos)
        int d0 = (int) (sinVal * 256) >> 5;  // Y base offset
        int d3 = (int) (cosVal * 256) >> 3;  // X base offset

        // ROM: Complex bit manipulation for figure-8/spiral effect
        // move.w $34(a0),d2 / andi.w #$3E0,d2 / lsr.w #5,d2
        int d2 = (angle >> 5) & 0x1F;  // bits 5-9 of angle
        int d4 = 0;
        int d5 = 2;
        int d1 = d3;

        // ROM: cmpi.w #$10,d2 / ble.s + / neg.w d1
        if (d2 > 0x10) {
            d1 = -d1;
        }

        // ROM: andi.w #$F,d2 / cmpi.w #8,d2 / ble.s loc_2D54A / neg.w d2 / andi.w #7,d2
        d2 &= 0xF;
        if (d2 > 8) {
            d2 = -(d2 & 7);
        }

        // ROM loop at loc_2D54A: lsr.w #1,d2 / beq.s + / add.w d1,d4 / asl.w #1,d1 / dbf d5,...
        for (int i = 0; i <= d5; i++) {
            d2 >>= 1;
            if (d2 != 0) {
                d4 += d1;
            }
            d1 <<= 1;
        }

        // ROM: asr.w #4,d4 / add.w d4,d0
        d4 >>= 4;
        d0 += d4;

        // Apply lifetime-based scaling (growing/full/shrinking)
        int scaleFactor;
        if (lifetime < COLLISION_START) {
            // Growing phase: scale proportional to lifetime
            scaleFactor = lifetime;
        } else if (lifetime <= SHRINK_START) {
            // Full size
            scaleFactor = COLLISION_START;
        } else {
            // Shrinking phase: neg.w d1 / addi.w #$200,d1
            scaleFactor = DELETE_AT - lifetime;
            if (scaleFactor < 0) {
                scaleFactor = 0;
            }
        }

        // ROM: muls.w d1,d0 / muls.w d1,d3 / asr.w #7,d0 / asr.w #7,d3
        d0 = (d0 * scaleFactor) >> 7;
        d3 = (d3 * scaleFactor) >> 7;

        // Apply to center position
        currentX = centerX + d3;
        currentY = centerY + d0;
    }

    /**
     * Animation update.
     * ROM (lines 61998-62007):
     * <pre>
     * addq.b #1,anim_frame(a0)
     * move.b anim_frame(a0),d0
     * andi.w #6,d0
     * lsr.w #1,d0
     * cmpi.b #3,d0
     * bne.s loc_2D5B6
     * moveq #1,d0
     * </pre>
     * Result: 0, 1, 2, 1, 0, 1, 2, 1, ...
     */
    private void updateAnimation() {
        animFrame++;
        int frame = (animFrame & 6) >> 1;  // 0, 1, 2, 3 cycling
        if (frame == 3) {
            frame = 1;  // 3 maps back to 1
        }
        mappingFrame = frame;
    }

    /**
     * ROM {@code Touch_Process} overlap test (sonic3k.asm:20654-20711) for this
     * object's {@code collision_flags} of {@code $D8} (loc_2D574,
     * sonic3k.asm:61979).
     *
     * <p>{@code $D8 & $3F} is {@code $18}, which indexes {@code Touch_Sizes}
     * (sonic3k.asm:20713) entry 24 = {@code dc.b 4,4} -- the object's half-width
     * and half-height. The player box is {@code Touch_NoInstaShield}
     * (sonic3k.asm:20641-20652): left edge {@code x_pos - 8} with width
     * {@code $10}, bottom edge {@code y_pos - (y_radius - 3)} with height
     * {@code (y_radius - 3) * 2}. {@code Touch_Width} / {@code Touch_Height}
     * then accept when the boxes overlap on both axes inclusively, which for
     * these symmetric boxes reduces to the two comparisons below.
     *
     * <p>The previous {@code dx < 16 && dy < 16} test was not from the ROM: it
     * was 4px too generous on each side in X, so the star could claim the player
     * before the ROM's box did.
     */
    private boolean touchesPlayer(AbstractPlayableSprite player) {
        int playerHalfHeight = player.getYRadius() - 3;
        int dx = Math.abs(player.getCentreX() - currentX);
        int dy = Math.abs(player.getCentreY() - currentY);
        return dx <= TOUCH_HALF_WIDTH + PLAYER_TOUCH_HALF_WIDTH
                && dy <= TOUCH_HALF_HEIGHT + playerHalfHeight;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getCheckpointStarRenderer(variant.artKey);
        if (renderer == null || !renderer.isReady()) {
            // Fallback: small dot with variant-specific color
            appendFallbackDot(commands);
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    /**
     * Fallback debug rendering: small sparkle dot using variant-specific color.
     */
    private void appendFallbackDot(List<GLCommand> commands) {
        int half = 2;
        float r = variant.r, g = variant.g, b = variant.b;
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, currentX - half, currentY, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, currentX + half, currentY, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, currentX, currentY - half, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, currentX, currentY + half, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }
}
