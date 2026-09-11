package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.CheckpointState;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Special stage star - spirals around checkpoint when >= 50 rings.
 * <p>
 * ROM behavior (Obj79_Star / loc_1F536):
 * - objoff_30/32: center X/Y (checkpoint position - 0x30 Y)
 * - objoff_34: angle (starts at 0, 0x40, 0x80, 0xC0 for each star, increments
 * by 0xA per frame)
 * - objoff_36: lifetime counter (collision at 0x80, shrink at 0x180, delete at
 * 0x200)
 * - Complex orbital motion with 3D-like spiral effect
 * - Animation: frames 0, 1, 2, 1 based on (frame & 6) >> 1, with 3 -> 1 mapping
 * </p>
 */
public class CheckpointStarInstance extends AbstractObjectInstance
        implements RewindRecreatable, TouchResponseProvider, TouchResponseListener {
    private static final Logger LOGGER = Logger.getLogger(CheckpointStarInstance.class.getName());

    // ROM constants (from disassembly)
    private static final int COLLISION_START = 0x80; // Enable collision at this lifetime
    private static final int SHRINK_START = 0x180; // Start shrinking
    private static final int DELETE_AT = 0x200; // Delete when lifetime reaches this
    private static final int ANGLE_INCREMENT = 0xA; // Add to angle each frame

    /**
     * Obj79_Star's collision_flags is $D8: Touch_Special ($C0) with Touch_Sizes
     * index $18 (docs/s2disasm/s2.asm:44926, 85286-85302). Same decode shape as
     * Obj44's $D7, whose response also lands on loc_3FA00.
     */
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.SONIC2_SPECIAL_PROPERTY,
            true,
            false,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    @RewindTransient(reason = "Structural parent link; relinked to the live S2 checkpoint "
            + "with matching captured center on rewind recreate. Scalar star state is "
            + "reapplied by the generic field capturer.")
    private final CheckpointObjectInstance parentCheckpoint; // Reference to parent for marking as used
    private int centerX; // objoff_30
    private int centerY; // objoff_32
    private int angle; // objoff_34 (starts at angleOffset, increments by 0xA)
    private int lifetime; // objoff_36
    private int animFrame; // anim_frame counter for animation cycling
    private int currentX;
    private int currentY;
    private int mappingFrame;
    private boolean collisionEnabled;
    /** ROM {@code collision_property(a0)} — written by Touch_Special, consumed by Obj79_Star. */
    private int collisionProperty;

    public CheckpointStarInstance(CheckpointObjectInstance parent, int angleOffset) {
        super(createDummySpawn(parent), "CheckpointStar");
        this.parentCheckpoint = parent;
        this.centerX = parent.getCenterX();
        this.centerY = parent.getCenterY() - 0x30; // Y offset from ROM
        this.angle = angleOffset; // Starts at 0, 0x40, 0x80, or 0xC0
        this.lifetime = 0;
        this.animFrame = 0;
        this.mappingFrame = 1; // ROM starts with mapping_frame = 1
        this.collisionEnabled = false;

        // Initial position at center
        this.currentX = centerX;
        this.currentY = centerY;
    }

    CheckpointStarInstance(CheckpointObjectInstance parent) {
        this(parent, 0);
    }

    private static ObjectSpawn createDummySpawn(CheckpointObjectInstance parent) {
        return new ObjectSpawn(parent.getCenterX(), parent.getCenterY(), 0x79, 0, 0, false, 0);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        CheckpointObjectInstance liveParent =
                CheckpointDongleInstance.findLiveParentForRewind(ctx.objectServices().objectManager(), ctx.spawn());
        return liveParent == null ? null : new CheckpointStarInstance(liveParent, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // If checkpoint was used for special stage entry, destroy remaining stars.
        // In the original ROM, the level is fully reloaded when returning from special
        // stage, so stars don't persist. We simulate this by having stars self-destruct
        // when the usedForSpecialStage flag is set.
        var checkpointState = services().checkpointState();
        if (checkpointState instanceof CheckpointState cs && cs.isUsedForSpecialStage()) {
            setDestroyed(true);
            return;
        }

        // Obj79_Star's first act is to consume the collision_property the shared
        // TouchResponse pass wrote during the player's slot earlier this frame, and
        // only bit 0 — the main character's +1 from Touch_Special loc_3FA00 — enters
        // the special stage; the sidekick's +2 never does
        // (docs/s2disasm/s2.asm:44871-44880, 85286-85302, 85073-85098).
        int touched = collisionProperty;
        collisionProperty = 0;
        if ((touched & 1) != 0) {
            LOGGER.info("Player touched special stage star - requesting special stage entry");
            // Mark the parent checkpoint as used for special stage entry
            // This prevents stars from respawning when returning from special stage
            if (parentCheckpoint != null) {
                parentCheckpoint.markUsedForSpecialStage();
            }
            services().requestSpecialStageEntry();
        }

        // Line 44411: addi.w #$A, objoff_34(a0)
        angle = (angle + ANGLE_INCREMENT) & 0xFFFF;

        // Line 44443: addq.w #1, objoff_36(a0) — the ROM increments objoff_36 AFTER
        // the orbit maths but BEFORE choosing the scale, and the position write at
        // loc_1F5D6 is skipped entirely on the frame the star deletes itself.
        lifetime++;

        int scaleFactor;
        if (lifetime < COLLISION_START) {
            // cmpi.w #$80,d1 / bgt/beq not taken -> loc_1F5B4, scale by objoff_36
            scaleFactor = lifetime;
        } else {
            if (lifetime == COLLISION_START) {
                // loc_1F5BE: move.b #$D8,collision_flags(a0) — the star becomes
                // touchable from the NEXT frame's TouchResponse pass onwards, and
                // the ROM never clears the byte again.
                collisionEnabled = true;
            }
            // loc_1F5C4: cmpi.w #$180,d1 / ble.s loc_1F5D6 — no scaling at all in
            // the star's full-size window.
            if (lifetime <= SHRINK_START) {
                scaleFactor = -1;
            } else {
                // neg.w d1 / addi.w #$200,d1 / bmi.w JmpTo10_DeleteObject
                scaleFactor = DELETE_AT - lifetime;
                if (scaleFactor < 0) {
                    setDestroyed(true);
                    return;
                }
            }
        }

        // loc_1F5D6: x_pos = objoff_30 + d3, y_pos = objoff_32 + d0
        int[] offsets = romOrbitOffsets(scaleFactor);
        currentX = centerX + offsets[0];
        currentY = centerY + offsets[1];
        updateDynamicSpawn(currentX, currentY);

        // Update animation frame (ROM: lines 44476-44484)
        updateAnimation();

    }

    // ── ROM TouchResponse participation (collision_flags $D8) ────────────────

    @Override
    public int getCollisionFlags() {
        // AllocateObjectAfterCurrent clears the SST, so collision_flags is 0 until
        // loc_1F5BE writes $D8 at objoff_36 == $80 (docs/s2disasm/s2.asm:44926-44927).
        // $D8 decodes as Touch_Special ($C0) with Touch_Sizes index $18 = (4,4)
        // (s2.asm:85286-85292, 85141-85196).
        return collisionEnabled ? 0xD8 : 0;
    }

    @Override
    public int getCollisionProperty() {
        return collisionProperty;
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
    public boolean usesSonic2TouchSpecialPropertyResponse() {
        return true;
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        // Obj79_Star clears collision_property every frame (s2.asm:44879), so the
        // ROM re-tests the overlap each frame rather than latching it once.
        return true;
    }

    @Override
    public boolean requiresRenderFlagForTouch() {
        // S2's Touch_Loop tests collision_flags(a1) only; there is no
        // render_flags.on_screen gate (docs/s2disasm/s2.asm:85081-85090).
        return false;
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        // Touch_Special loc_3FA00: +1 for the main character, +2 for the sidekick
        // (docs/s2disasm/s2.asm:85073-85080).
        if (playerEntity instanceof AbstractPlayableSprite sprite && sprite.isCpuControlled()) {
            collisionProperty += 2;
        } else {
            collisionProperty += 1;
        }
    }

    /** Sign-extends a 68000 data register's low word (all Obj79_Star maths is {@code .w}). */
    private static int word(int value) {
        return (short) value;
    }

    /** {@code asr.w #n,dX} — arithmetic shift of the sign-extended low word. */
    private static int asrWord(int value, int shift) {
        return word(value) >> shift;
    }

    /**
     * Transcribes {@code Obj79_Star}'s orbit maths from {@code loc_1F554} through
     * {@code loc_1F5D6} (docs/s2disasm/s2.asm:44880-44943) instruction for
     * instruction, in 16-bit word arithmetic.
     * <p>
     * Two prior approximations are corrected here, both of which moved the star by
     * several pixels per frame:
     * <ul>
     *   <li>the sine/cosine came from {@code Math.sin}/{@code Math.cos} rather than
     *       {@code CalcSine} (s2.asm:4012-4024), whose {@code Sine_Data} table is not
     *       a rounded sine — index $93 holds -117 where a rounded sine gives -115.
     *       {@link com.openggf.physics.TrigLookupTable} carries that exact table;</li>
     *   <li>{@code neg.w d2 / andi.w #7,d2} (s2.asm:44900-44901) negates <em>then</em>
     *       masks, so d2 in 9..$F maps to (-d2) &amp; 7. The old code computed
     *       {@code -(d2 & 7)}, leaving d2 negative, which then made the
     *       {@code lsr.w #1,d2} loop at {@code loc_1F594} run on a negative value it
     *       can never reach zero from.</li>
     * </ul>
     * Verified against this run's recorded {@code object_near} rows: the transcription
     * reproduces all four stars' x/y exactly for every frame the second star post's
     * stars are on screen.
     *
     * @param scaleFactor {@code d1} at {@code loc_1F5B4}, or -1 for the unscaled
     *                    {@code loc_1F5C4} fall-through
     * @return {@code {d3, d0}} — the x and y offsets from {@code objoff_30/32}
     */
    private int[] romOrbitOffsets(int scaleFactor) {
        // jsr (CalcSine).l — d0 = sine, d1 = cosine (s2.asm:44884)
        int d0 = TrigLookupTable.sinHex(angle & 0xFF);
        int d1 = TrigLookupTable.cosHex(angle & 0xFF);

        // asr.w #5,d0 / asr.w #3,d1 / move.w d1,d3
        d0 = asrWord(d0, 5);
        d1 = asrWord(d1, 3);
        int d3 = d1;

        // move.w objoff_34(a0),d2 / andi.w #$3E0,d2 / lsr.w #5,d2
        int d2 = (angle & 0x3E0) >> 5;
        int d5 = 2;
        int d4 = 0;

        // cmpi.w #$10,d2 / ble.s + / neg.w d1
        if (d2 > 0x10) {
            d1 = word(-d1);
        }

        // andi.w #$F,d2 / cmpi.w #8,d2 / ble.s loc_1F594 / neg.w d2 / andi.w #7,d2
        d2 &= 0xF;
        if (d2 > 8) {
            d2 = (-d2) & 7;
        }

        // loc_1F594: lsr.w #1,d2 / beq.s + / add.w d1,d4 / + asl.w #1,d1 / dbf d5,-
        // Note the ROM branches on the Z flag of the SHIFT RESULT, not on the bit
        // shifted out, so d1 is accumulated while the remaining d2 is still non-zero.
        for (int i = 0; i <= d5; i++) {
            d2 = (d2 & 0xFFFF) >>> 1;
            if (d2 != 0) {
                d4 = word(d4 + d1);
            }
            d1 = word(d1 << 1);
        }

        // asr.w #4,d4 / add.w d4,d0
        d4 = asrWord(d4, 4);
        d0 = word(d0 + d4);

        if (scaleFactor >= 0) {
            // loc_1F5B4: muls.w d1,d0 / muls.w d1,d3 / asr.w #7,d0 / asr.w #7,d3.
            // muls produces a longword but asr.w only shifts the low word.
            d0 = asrWord(d0 * scaleFactor, 7);
            d3 = asrWord(d3 * scaleFactor, 7);
        }
        return new int[] { d3, d0 };
    }

    private void updateAnimation() {
        // ROM: lines 44476-44484
        // addq.b #1, anim_frame(a0)
        // move.b anim_frame(a0), d0
        // andi.w #6, d0
        // lsr.w #1, d0
        // cmpi.b #3, d0 / bne.s + / moveq #1, d0
        // move.b d0, mapping_frame(a0)

        animFrame++;
        int frame = (animFrame & 6) >> 1; // 0, 1, 2, 3 cycling
        if (frame == 3) {
            frame = 1; // 3 maps back to 1, so we get: 0, 1, 2, 1
        }
        mappingFrame = frame;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getCheckpointStarRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }
}
